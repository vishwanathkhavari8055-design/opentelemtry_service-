package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.client.OpenObserveClientService.SearchOutcome;
import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertEvent;
import com.trinity.observability.entity.AlertIngestCursorEntity;
import com.trinity.observability.service.AlertIngestService;
import com.trinity.observability.service.AlertProcessingService;
import com.trinity.observability.util.AlertPayloadNormalizer;
import com.trinity.observability.util.AlertStreamRowMapper;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;

/**
 * The alert poller.
 *
 * <p>Reads new rows from the configured OpenObserve stream and hands each one to
 * {@link AlertProcessingService} to be folded into PostgreSQL. Holds no alert
 * logic itself — this class is about <em>when</em> and <em>how far</em> to read;
 * what an alert means is the processing service's job.</p>
 *
 * <h2>Transaction boundaries</h2>
 *
 * <p>Each row is stored in its OWN transaction, and the cursor is advanced in
 * another one afterwards. That is deliberate and it is not the obvious choice:
 * wrapping a whole batch in one transaction would be faster, but a single
 * poison row — a template that renders something the mapper chokes on — would
 * roll back the other four hundred alerts in the batch, on every poll, forever.
 * Per-row commits mean one bad alert costs one alert.</p>
 *
 * <p>The cursor advancing separately is safe in the direction that matters: if
 * storing succeeds and the cursor write fails, the rows are re-read next poll and
 * discarded by the {@code event_hash} unique index. The reverse — advancing past
 * rows that were not stored — cannot happen, because the cursor is only advanced
 * after the loop completes.</p>
 */
@Service
public class AlertIngestServiceImpl implements AlertIngestService {

    private static final Logger log = LogManager.getLogger(AlertIngestServiceImpl.class);

    /** A cursor with no successful poll in this long is reported unhealthy. */
    private static final long UNHEALTHY_AFTER_MULTIPLIER = 4L;

    private final AlertIngestCursorStore cursors;
    private final AlertProcessingService processing;
    private final OpenObserveClientService openObserve;
    private final OpenObserveConfig openObserveConfig;
    private final AlertIngestProperties properties;
    private final AlertIncidentProperties incidentProperties;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public AlertIngestServiceImpl(AlertIngestCursorStore cursors,
            AlertProcessingService processing,
            OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig,
            AlertIngestProperties properties,
            AlertIncidentProperties incidentProperties) {
        this.cursors = cursors;
        this.processing = processing;
        this.openObserve = openObserve;
        this.openObserveConfig = openObserveConfig;
        this.properties = properties;
        this.incidentProperties = incidentProperties;
    }

    /**
     * The timer.
     *
     * <p>{@code fixedDelayString} rather than {@code fixedRate}: a poll that takes
     * longer than the interval must not have the next one start on top of it. Two
     * concurrent polls would read the same range and race on the cursor.</p>
     */
    @Scheduled(fixedDelayString = "${alerts.ingest.interval-ms:30000}",
               initialDelayString = "${alerts.ingest.initial-delay-ms:20000}")
    public void scheduledPoll() {
        try {
            pollOnce();
        } catch (Exception e) {
            // Belt to pollOnce's braces. An exception escaping a @Scheduled method
            // is logged by Spring but the schedule continues; catching it here lets
            // us say something useful about it first.
            log.error("Alert ingest poll failed unexpectedly: {}", e.getMessage(), e);
        }
    }

    @Override
    public List<PollResult> pollOnce() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        if (!openObserveEnabled) {
            log.debug("Alert ingest skipped — openobserve.enabled=false");
            return List.of();
        }

        List<PollResult> results = new ArrayList<>();
        String stream = properties.getStream();
        try {
            cursors.purgeObsoleteStreams(stream);
        } catch (Exception e) {
            log.trace("Obsolete cursor stream purge skipped: {}", e.getMessage());
        }
        for (String org : organizations()) {
            results.add(pollOrg(org, stream));
        }
        return results;
    }

    /**
     * Which organizations to poll.
     *
     * <p>The configured list, or the default org when it is empty. Deduplicated
     * and validated because each entry becomes part of an OpenObserve URL path.</p>
     */
    private Set<String> organizations() {
        Set<String> orgs = new LinkedHashSet<>();
        for (String configured : properties.getOrgs()) {
            if (OrgContext.isValid(configured)) {
                orgs.add(configured.trim());
            } else if (configured != null && !configured.isBlank()) {
                log.warn("Ignoring alerts.ingest.orgs entry '{}' — not a valid organization identifier",
                        configured);
            }
        }
        if (orgs.isEmpty()) {
            orgs.add(openObserveConfig.getDefaultOrg());
        }
        return orgs;
    }

    /** One organization & stream's poll. Never throws. */
    private PollResult pollOrg(String org, String stream) {
        long backfillFrom = nowMicros() - properties.getBackfillMinutes() * 60L * 1_000_000L;
        AlertIngestCursorEntity cursor = cursors.loadOrCreate(org, stream, backfillFrom);

        long now = nowMicros();

        // The position this poll reads from. Held as a local rather than re-read from
        // the entity, because the backlog guard below may move it before the poll runs
        // and every decision after that point has to agree about where it is.
        long watermark = resetWatermarkIfStale(org, stream, cursor, backfillFrom, now);
        watermark = skipBacklogIfTooOld(org, stream, cursor, watermark, now);

        ReadWindow window = chooseWindow(org, stream, cursor, watermark, now);
        long startMicros = window.startMicros();
        long endMicros = window.endMicros();
        boolean catchingUp = window.catchingUp();

        int batchSize = properties.getBatchSize();
        FetchedRows fetched = fetchAllPages(org, stream, cursor, watermark, window, batchSize);
        if (fetched.earlyResult() != null) {
            return fetched.earlyResult();
        }
        List<Map<String, Object>> allRows = fetched.rows();

        StoreTally tally = storeAll(allRows, org, stream, watermark);
        int stored = tally.stored;
        int duplicates = tally.duplicates;
        long newestMicros = settleNewestMicros(org, stream, allRows.size(), batchSize, catchingUp,
                endMicros, watermark, tally.newestMicros);

        cursors.recordSuccess(cursor.getId(), newestMicros, stored);

        if (stored > 0 || duplicates > 0) {
            if (log.isInfoEnabled()) {
                log.info("Alert ingest (org={}, stream={}): {} row(s) fetched, {} new alert(s) stored, {} active alert(s) updated, watermark now {}",
                        org, stream, allRows.size(), stored, duplicates,
                        TimeFormatter.microsToIso(newestMicros));
            }
        } else if (catchingUp) {
            if (log.isInfoEnabled()) {
                log.info("Alert ingest (org={}, stream={}): catching up — nothing in {} .. {}, "
                        + "watermark now {}", org, stream, TimeFormatter.microsToIso(startMicros),
                        TimeFormatter.microsToIso(endMicros), TimeFormatter.microsToIso(newestMicros));
            }
        } else {
            log.debug("Alert ingest (org={}, stream={}): nothing new", org, stream);
        }
        return new PollResult(org, stream, allRows.size(), stored, duplicates, newestMicros, null);
    }

    /** The span one poll reads, and whether it is a catch-up chunk rather than the live tail. */
    private record ReadWindow(long startMicros, long endMicros, boolean catchingUp) {
    }

    /** Either every page read for a poll, or the result the poll must return early with. */
    private record FetchedRows(List<Map<String, Object>> rows, PollResult earlyResult) {
    }

    /** Running counts for the rows one poll stores. */
    private static final class StoreTally {
        int stored;
        int duplicates;
        long newestMicros;
    }

    /**
     * Pulls a cursor's watermark back to the backfill position when it has never ingested
     * anything yet sits ahead of that position, or when it is in the future.
     */
    private long resetWatermarkIfStale(String org, String stream, AlertIngestCursorEntity cursor,
                                       long backfillFrom, long now) {
        long watermark = cursor.getLastEventMicros();

        // If the cursor has 0 events ingested and its watermark is ahead of backfillFrom,
        // reset watermark to backfillFrom so all historical active alerts in the window are scanned.
        if (cursor.getEventsIngested() == 0 && watermark > backfillFrom) {
            watermark = backfillFrom;
            cursors.recordSuccess(cursor.getId(), watermark, 0);
            if (log.isInfoEnabled()) {
                log.info("Reset cursor watermark to backfill position {} for org '{}' stream '{}'",
                        TimeFormatter.microsToIso(watermark), org, stream);
            }
        }

        // If the watermark is in the future compared to now, reset it to backfillFrom
        if (watermark > now) {
            watermark = backfillFrom;
            cursors.recordSuccess(cursor.getId(), watermark, 0);
            if (log.isWarnEnabled()) {
                log.warn("Reset future cursor watermark {} to backfill position {} for org '{}' stream '{}'",
                        TimeFormatter.microsToIso(cursor.getLastEventMicros()), TimeFormatter.microsToIso(watermark), org, stream);
            }
        }
        return watermark;
    }

    /**
     * A backlog too old to be worth walking.
     *
     * <p>Chunked catch-up is correct but it is not fast: it advances at most one
     * chunk per poll, and a cursor two days behind a busy stream has millions of
     * rows in front of it. Measured here on 2026-08-13 — the watermark was 49.6h
     * back with 3,551,442 rows after it, which at batch-size 500 every 30s is
     * roughly 59 HOURS of walking before the screen shows anything current. An
     * alerts screen that will be right the day after tomorrow is not an alerts
     * screen.
     *
     * <p>So past this bound the poller stops trying to be complete and starts being
     * current: it jumps to the normal backfill position and reads forward from
     * there. What that skips is history, and history is the part nobody is paged
     * about. It is logged at WARN with the range, because it IS a gap.
     *
     * <p>On a genuinely quiet system this jumps too — a watermark is only as old as
     * the last event — but there it skips nothing, because there is nothing in the
     * range to skip. One WARN per max-backlog-hours of silence is the whole cost.
     */
    private long skipBacklogIfTooOld(String org, String stream, AlertIngestCursorEntity cursor,
                                     long watermark, long now) {
        long maxBacklog = properties.maxBacklogMicros();
        if (maxBacklog <= 0 || (now - watermark) <= maxBacklog) {
            return watermark;
        }
        long target = now - properties.getBackfillMinutes() * 60L * 1_000_000L;
        if (target <= watermark) {
            return watermark;
        }
        cursors.recordSuccess(cursor.getId(), target, 0);
        if (log.isWarnEnabled()) {
            log.warn("Alert ingest (org={}, stream={}): watermark {} is {} hours behind — past "
                    + "the {}h backlog bound, so it has been moved to {} and reading resumes "
                    + "from there. Alerts that fired in the skipped range will NOT appear. "
                    + "Raise alerts.ingest.max-backlog-hours (or set it to 0) to walk the whole "
                    + "gap instead.",
                    org, stream, TimeFormatter.microsToIso(watermark),
                    (now - watermark) / 3_600_000_000L, properties.getMaxBacklogHours(),
                    TimeFormatter.microsToIso(target));
        }
        return target;
    }

    /** Decides the span this poll reads, starting from {@code watermark}. */
    private ReadWindow chooseWindow(String org, String stream, AlertIngestCursorEntity cursor,
                                    long watermark, long now) {
        // Cap the span so a long gap is walked in servable pieces rather than
        // attempted as one query OpenObserve answers with empty-and-partial. See
        // AlertIngestProperties.maxWindowMinutes for the measurements behind this.
        long maxSpan = properties.maxWindowMicros();

        // Decided from the WATERMARK rather than from the overlapped start, so the
        // answer does not depend on the overlap that is chosen below on the strength
        // of it. A minute either way cannot matter to a threshold measured in hours.
        boolean catchingUp = maxSpan > 0 && (now - watermark) > maxSpan;

        long startMicros = Math.max(0L, watermark - properties.getOverlapSeconds() * 1_000_000L);
        if (catchingUp) {
            startMicros = watermark;
        }

        // A watermark far behind the stream's first row is usually not a backlog at
        // all — it is a cursor describing a DIFFERENT OpenObserve. Skip to where
        // this instance's data actually begins rather than walking the gap in
        // chunks. Only while catching up, so the live tail costs nothing extra.
        if (catchingUp) {
            long jumped = fastForwardPastEmptyHistory(org, stream, cursor, startMicros);
            if (jumped > startMicros) {
                startMicros = jumped;
                catchingUp = (now - startMicros) > maxSpan;
            }
        }

        long endMicros = catchingUp ? startMicros + maxSpan : now;
        return new ReadWindow(startMicros, endMicros, catchingUp);
    }

    /**
     * Reads every page of the window, or returns the result the poll has to end with
     * when a page fails or comes back partial.
     */
    private FetchedRows fetchAllPages(String org, String stream, AlertIngestCursorEntity cursor,
                                      long watermark, ReadWindow window, int batchSize) {
        long startMicros = window.startMicros();
        long endMicros = window.endMicros();
        List<Map<String, Object>> allRows = new ArrayList<>();
        int offset = 0;
        boolean hasMore = true;
        int pageCount = 0;
        int maxPages = 50; // Cap at 25,000 alerts per poll to prevent memory exhaustion

        while (hasMore && pageCount < maxPages) {
            pageCount++;
            SearchOutcome outcome;
            try {
                outcome = fetchRows(org, stream, startMicros, endMicros, offset);
            } catch (Exception e) {
                int failures = cursors.recordFailure(cursor.getId(), e.getMessage());
                logFailure(org, stream, failures, e);
                return new FetchedRows(allRows,
                        new PollResult(org, stream, 0, 0, 0, watermark, e.getMessage()));
            }

            if (outcome.partial()) {
                return new FetchedRows(allRows, handlePartial(org, stream, cursor, watermark, startMicros,
                        endMicros, window.catchingUp(), outcome.error()));
            }

            List<Map<String, Object>> hits = outcome.hits();
            allRows.addAll(hits);
            log.info("Ingest poll page {} fetched {} hit(s) (total accumulated: {})", pageCount, hits.size(), allRows.size());

            if (hits.size() < batchSize) {
                hasMore = false;
            } else {
                offset += batchSize;
            }
        }
        return new FetchedRows(allRows, null);
    }

    /** Stores every mappable row and tracks the newest row timestamp seen. */
    private StoreTally storeAll(List<Map<String, Object>> allRows, String org, String stream, long watermark) {
        StoreTally tally = new StoreTally();
        tally.newestMicros = watermark;

        for (Map<String, Object> row : allRows) {
            Long rowMicros = rowTimestamp(row);
            if (rowMicros != null && rowMicros > tally.newestMicros) {
                tally.newestMicros = rowMicros;
            }
            AlertEvent event = mapSafely(row, org);
            if (event != null) {
                storeOne(event, org, stream, tally);
            }
        }
        return tally;
    }

    private void storeOne(AlertEvent event, String org, String stream, StoreTally tally) {
        try {
            boolean isNew = processing.store(event, properties.getSourceLabel());
            if (isNew) {
                tally.stored++;
            } else {
                tally.duplicates++;
            }
        } catch (DataIntegrityViolationException duplicate) {
            tally.duplicates++;
            log.trace("Alert row already ingested (hash={})", event.getEventHash());
        } catch (Exception e) {
            log.error("Could not store alert '{}' from stream '{}' (org={}): {}",
                    event.getAlertName(), stream, org, e.getMessage(), e);
        }
    }

    /** The watermark to record once the rows are stored. */
    @SuppressWarnings("java:S107")
    private long settleNewestMicros(String org, String stream, int rowCount, int batchSize,
                                    boolean catchingUp, long endMicros, long watermark, long newestMicros) {
        boolean chunkFullyRead = rowCount < batchSize;
        if (catchingUp && chunkFullyRead && endMicros > newestMicros) {
            newestMicros = endMicros;
        }

        if (!chunkFullyRead && newestMicros <= watermark) {
            newestMicros = watermark + 1L;
            if (log.isWarnEnabled()) {
                log.warn("Alert ingest (org={}, stream={}): a full batch of {} row(s) landed entirely "
                        + "at or before the watermark {} — stepping 1µs past it so the poll can "
                        + "progress.",
                        org, stream, rowCount,
                        TimeFormatter.microsToIso(watermark));
            }
        }
        return newestMicros;
    }

    /**
     * A window OpenObserve could not scan in full.
     *
     * <p>Retried rather than skipped, up to a point. While catching up, a chunk
     * that stays partial is stepped over once the retry budget is spent — some
     * ranges are permanently unreadable (OpenObserve's file list referencing
     * parquet files that no longer exist), and blocking on one of those means no
     * alert after it is ever read. In the live tail nothing is skipped: the window
     * ends at "now", and rows there are usually about to become readable.</p>
     */
    @SuppressWarnings("java:S107")
    private PollResult handlePartial(String org, String stream, AlertIngestCursorEntity cursor,
            long watermark, long startMicros, long endMicros, boolean catchingUp, String error) {
        String detail = "OpenObserve served only part of "
                + TimeFormatter.microsToIso(startMicros) + " .. "
                + TimeFormatter.microsToIso(endMicros)
                + (error == null ? "" : " (" + error + ")");
        int failures = cursors.recordFailure(cursor.getId(), detail);

        if (catchingUp && failures >= properties.getPartialRetriesBeforeSkip()) {
            // Skip the chunk. Loud, and it names the range, because alerts that
            // fired inside it are being given up on.
            cursors.recordSuccess(cursor.getId(), endMicros, 0);
            if (log.isErrorEnabled()) {
                log.error("Alert ingest (org={}, stream={}): range {} .. {} is UNREADABLE after {} "
                        + "attempts — skipping it so ingest can continue. Any alert that fired in "
                        + "that range will not appear. Cause: {}",
                        org, stream, TimeFormatter.microsToIso(startMicros),
                        TimeFormatter.microsToIso(endMicros), failures, detail);
            }
            return new PollResult(org, stream, 0, 0, 0, endMicros, detail);
        }

        if (failures >= properties.getFailuresBeforeAlarm()) {
            log.error("Alert ingest (org={}, stream={}) has returned partial data {} times in a "
                    + "row — ALERTS MAY BE MISSING. {}", org, stream, failures, detail);
        } else {
            log.warn("Alert ingest (org={}, stream={}) returned partial data (attempt {}): {}",
                    org, stream, failures, detail);
        }
        return new PollResult(org, stream, 0, 0, 0, watermark, detail);
    }

    /**
     * Move the watermark to the stream's first row when it sits before it.
     *
     * <p>Makes an OpenObserve URL change a non-event. The watermark is stored in
     * PostgreSQL but it names a position in OpenObserve's timeline, so repointing
     * {@code openobserve.host} leaves it describing an instance that is no longer
     * being read. Chunked catch-up alone would then walk the whole gap in
     * {@code max-window-minutes} steps — correct, but a cursor six months stale
     * needs roughly 730 polls to reach the present, and the Alerts screen stays
     * empty for hours while it does.</p>
     *
     * <p>Asking the instance where its data starts costs one small GET and skips
     * the walk entirely. Nothing readable is given up: the range being stepped
     * over is a range this instance does not hold. That also covers retention
     * having aged the gap out, and the stream having been dropped and recreated —
     * the answer comes from the data rather than from an assumption about why the
     * cursor is behind.</p>
     *
     * @return the (possibly advanced) start; the input unchanged if there is no
     *         reason or no way to move it
     */
    private long fastForwardPastEmptyHistory(String org, String stream,
            AlertIngestCursorEntity cursor, long startMicros) {
        if (!properties.isFastForwardToStreamStart()) {
            return startMicros;
        }

        long earliest;
        try {
            JsonNode schema = openObserve.getJson("/api/" + org + "/streams/" + stream
                    + "/schema?type=" + properties.getStreamType());
            long rawDocTime = schema.path("stats").path("doc_time_min").asLong(0L);
            earliest = rawDocTime > 0 ? AlertPayloadNormalizer.toMicros(String.valueOf(rawDocTime)) : 0L;
        } catch (Exception e) {
            // Not fatal and not even a failure worth recording: the poll can still
            // proceed with the chunked window, just more slowly.
            log.debug("Could not read stream start for org '{}' stream '{}': {}",
                    org, stream, e.getMessage());
            return startMicros;
        }

        // 0 means "empty stream" or "not reported" — neither justifies a jump.
        if (earliest <= 0L || earliest <= startMicros) {
            return startMicros;
        }

        // Land slightly before the first row rather than exactly on it, for the
        // same reason every window carries an overlap.
        long target = Math.max(0L, earliest - properties.getOverlapSeconds() * 1_000_000L);
        cursors.recordSuccess(cursor.getId(), target, 0);
        if (log.isWarnEnabled()) {
            log.warn("Alert ingest (org={}, stream={}): watermark was {}, but this stream's data "
                    + "only begins at {} — fast-forwarding. Expected after an OpenObserve address "
                    + "change, a retention purge, or the stream being recreated; the skipped range "
                    + "holds no rows on this instance.",
                    org, stream, TimeFormatter.microsToIso(startMicros),
                    TimeFormatter.microsToIso(earliest));
        }
        return target;
    }

    /**
     * Read the next batch of rows.
     *
     * <p>Ordered ASCENDING so the watermark advances monotonically through the
     * batch: with DESC, a batch truncated at {@code batchSize} would leave the
     * OLDEST rows unread while the cursor had already moved past them.</p>
     */
    private SearchOutcome fetchRows(String org, String stream,
            long startMicros, long endMicros, int offset) {
        String safeStream = SqlSafety.validateIdentifier(stream);
        String sql = "SELECT * FROM \"" + safeStream + "\" "
                + "WHERE " + AlertStreamRowMapper.TIMESTAMP_COLUMN + " >= " + startMicros + " "
                + "AND " + AlertStreamRowMapper.TIMESTAMP_COLUMN + " <= " + endMicros + " "
                + "ORDER BY " + AlertStreamRowMapper.TIMESTAMP_COLUMN + " DESC";

        if (log.isDebugEnabled()) {
            log.debug("Alert ingest SQL (org={}): {}", org, sql);
        }

        String previousOrg = OrgContext.get();
        SearchOutcome outcome;
        try {
            if (OrgContext.isValid(org)) {
                OrgContext.set(org);
            } else {
                OrgContext.clear();
            }
            log.debug("Alert ingest [query] org={}, stream={}, startMicros={}, endMicros={}, offset={}",
                    org, stream, startMicros, endMicros, offset);
            outcome = openObserve.searchDetailed(sql, startMicros, endMicros, offset,
                    properties.getBatchSize(), properties.getStreamType());

            // There is deliberately NO "nothing found, so re-read the last 24 hours"
            // fallback here.
            //
            // One existed and it was a trap: the bounded window is empty on every quiet
            // poll, which is most of them, so the fallback fired constantly and re-read
            // a full day of rows every 30 seconds. That is the repeated whole-stream
            // scan this poller's watermark exists to avoid, it is how OpenObserve is
            // driven into MemoryTableOverflowError, and it re-drove the entire
            // processing pipeline over already-stored rows on every tick.
            //
            // An empty window is not a failure that needs working around — it means
            // nothing fired, which is the normal state of a healthy system. The genuine
            // failures it was masking have their own handling: `partial` is retried and
            // then reported, a stale watermark is reported as `stalled` on
            // /ingest-status, and a cursor pointing at the wrong instance is corrected
            // by fastForwardPastEmptyHistory.
            return outcome;
        } finally {
            if (previousOrg == null) {
                OrgContext.clear();
            } else {
                OrgContext.set(previousOrg);
            }
        }
    }

    /** Mapping must not be able to kill the batch either. */
    private AlertEvent mapSafely(Map<String, Object> row, String org) {
        try {
            return AlertStreamRowMapper.map(row, org, properties.getMapping(),
                    properties.getMaxPayloadChars(), properties.getIdentityFields(),
                    incidentProperties.getSeverityOrder(),
                    incidentProperties.isStripSeveritySuffix());
        } catch (Exception e) {
            log.error("Could not map an alert row from org '{}': {}", org, e.getMessage(), e);
            return null;
        }
    }

    private static Long rowTimestamp(Map<String, Object> row) {
        Object value = row.get(AlertStreamRowMapper.TIMESTAMP_COLUMN);
        return value instanceof Number number ? number.longValue() : null;
    }

    // ── cursor bookkeeping ───────────────────────────────────────────────────

    /**
     * Escalate the log level once failures persist.
     *
     * <p>A single failed poll is noise — OpenObserve restarts, networks blip, and
     * the next poll covers the same range. A run of them means alerts are not being
     * recorded, which is worth waking someone for.</p>
     */
    private void logFailure(String org, String stream, int failures, Exception e) {
        if (failures >= properties.getFailuresBeforeAlarm()) {
            log.error("Alert ingest has failed {} consecutive times for org '{}' stream '{}' — "
                    + "NO ALERTS ARE BEING RECORDED. Last error: {}",
                    failures, org, stream, e.getMessage());
        } else {
            log.warn("Alert ingest poll failed for org '{}' stream '{}' (attempt {}): {}",
                    org, stream, failures, e.getMessage());
        }
    }

    @Override
    public List<CursorStatus> cursorStatus() {
        String activeStream = properties.getStream();
        try {
            cursors.purgeObsoleteStreams(activeStream);
        } catch (Exception e) {
            log.trace("Obsolete cursor purge skipped: {}", e.getMessage());
        }

        long unhealthyAfterMs = properties.getIntervalMs() * UNHEALTHY_AFTER_MULTIPLIER;
        Instant now = Instant.now();

        List<CursorStatus> out = new ArrayList<>();
        for (AlertIngestCursorEntity cursor : cursors.findAll()) {
            if (!activeStream.equalsIgnoreCase(cursor.getStreamName())) {
                continue;
            }
            boolean healthy = cursor.getConsecutiveFailures() == 0
                    && cursor.getLastSuccessAt() != null
                    && cursor.getLastSuccessAt().isAfter(now.minusMillis(unhealthyAfterMs));
            long watermarkAgeSeconds = Math.max(0L,
                    (nowMicros() - cursor.getLastEventMicros()) / 1_000_000L);
            out.add(new CursorStatus(
                    cursor.getOrg(),
                    cursor.getStreamName(),
                    TimeFormatter.microsToIso(cursor.getLastEventMicros()),
                    iso(cursor.getLastPolledAt()),
                    iso(cursor.getLastSuccessAt()),
                    cursor.getLastError(),
                    cursor.getConsecutiveFailures(),
                    cursor.getEventsIngested(),
                    healthy,
                    watermarkAgeSeconds,
                    isStalled(healthy, watermarkAgeSeconds)));
        }
        return out;
    }

    /**
     * Succeeding, but not getting anywhere.
     *
     * <p>An old watermark is not on its own a fault: on a quiet system the last event
     * genuinely was hours ago, and a caught-up poller deliberately leaves the
     * watermark on that event so the overlap window keeps re-reading the tail.</p>
     *
     * <p>What is NOT normal is an old watermark on a poller that is succeeding. Once
     * the gap exceeds {@code max-window-minutes} the poll switches to catch-up mode,
     * and a successful catch-up poll ALWAYS advances the watermark — to the newest row
     * it read, or to the end of the chunk when the chunk came back in full. So a
     * watermark that stays behind that boundary while polls report success is a poller
     * that cannot move, and the only honest thing to do is say so: this is exactly the
     * state that showed a green banner over an empty table for 32 hours.</p>
     *
     * <p>One extra interval of slack, so the sample taken between a poll starting and
     * committing does not report a stall that is about to clear itself.</p>
     */
    private boolean isStalled(boolean healthy, long watermarkAgeSeconds) {
        if (!healthy) {
            // Failing polls are already reported as unhealthy, with the error. Calling
            // that "stalled" as well would put two banners on one problem.
            return false;
        }
        long catchUpBoundarySeconds = properties.getMaxWindowMinutes() * 60L
                + properties.getIntervalMs() / 1000L;
        return properties.getMaxWindowMinutes() > 0 && watermarkAgeSeconds > catchUpBoundarySeconds;
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }

    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
