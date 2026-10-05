package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.client.OpenObserveClientService.SearchOutcome;
import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertEvent;
import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.dto.alerts.AlertQueryResponseDTO;
import com.trinity.observability.service.AlertLiveStreamService;
import com.trinity.observability.util.AlertAttributeProjector;
import com.trinity.observability.util.AlertDescriptionNormalizer;
import com.trinity.observability.util.AlertMeasurementReader;
import com.trinity.observability.util.AlertStreamRowMapper;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;

/**
 * Reads the alert stream directly. See {@link AlertLiveStreamService} for why this
 * exists alongside the stored path.
 *
 * <p>Deliberately shares the ingest's mapping configuration rather than defining its
 * own: the whole point is that these are the same firings the poller would have
 * stored, so a field the poller can read and this cannot would be a bug that only
 * appears when the pipeline is already broken.</p>
 */
@Service
public class AlertLiveStreamServiceImpl implements AlertLiveStreamService {

    private static final Logger log = LogManager.getLogger(AlertLiveStreamServiceImpl.class);

    /**
     * Rows pulled from the stream before grouping.
     *
     * <p>High, because the stream holds one row per firing and the bursts are the
     * interesting part — a cap of a few hundred would silently drop whole alerts
     * behind one noisy one. Bounded all the same: this runs on a request thread, and
     * an unbounded scan of a busy stream would tie up the screen it is meant to
     * rescue. Reaching it sets {@code truncated}, which the UI states.</p>
     */
    private static final int MAX_SCAN_ROWS = 5_000;

    /** Largest page served, matching the stored path's cap. */
    private static final int MAX_SIZE = 250;

    private static final int DEFAULT_SIZE = 50;

    /**
     * Window used when the caller does not name one.
     *
     * <p>The stored path treats a blank window as "all time"; here that is not
     * available — a stream query needs bounds, and an unbounded one is the scan
     * OpenObserve answers with an empty 200 rather than an error.</p>
     */
    private static final String DEFAULT_WINDOW = "now-24h";

    private static final List<String> SEVERITY_RANK =
            List.of("disaster", "critical", "error", "warning", "info");

    private final OpenObserveClientService openObserve;
    private final OpenObserveConfig config;
    private final AlertIngestProperties ingest;
    private final AlertIncidentProperties incident;

    public AlertLiveStreamServiceImpl(OpenObserveClientService openObserve,
            OpenObserveConfig config,
            AlertIngestProperties ingest,
            AlertIncidentProperties incident) {
        this.openObserve = openObserve;
        this.config = config;
        this.ingest = ingest;
        this.incident = incident;
    }

    @Override
    public AlertQueryResponseDTO.Page live(AlertQueryRequestDTO.Search request) {
        String org = config.getOrg();
        int page = pageOf(request);
        int size = clampSize(request == null ? null : request.getSize());

        String expression = windowOf(request);
        long endMicros = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(expression, endMicros);

        AlertQueryResponseDTO.Page out = new AlertQueryResponseDTO.Page();
        out.setSource("live-stream");
        out.setOrg(org);
        out.setPage(page);
        out.setSize(size);
        out.setSortBy("lastFiredAt");
        out.setSortDir("desc");
        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));

        SearchOutcome outcome;
        try {
            outcome = fetch(org, startMicros, endMicros);
        } catch (Exception e) {
            // The stored path is already empty or this would not have been called, so
            // throwing here would replace one uninformative screen with another. The
            // page comes back empty and partial, and the caller says which.
            log.warn("Live alert read failed (org={}, stream={}): {}",
                    org, ingest.getStream(), e.getMessage());
            out.setPartial(true);
            return out;
        }

        List<Map<String, Object>> hits = outcome.hits() == null ? List.of() : outcome.hits();
        out.setPartial(outcome.partial());
        out.setTruncated(hits.size() >= MAX_SCAN_ROWS);

        List<Group> groups = group(hits, org);
        List<Group> matching = matchingSorted(groups, request);

        out.setTotal(matching.size());
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        List<AlertQueryResponseDTO.Row> rows = new ArrayList<>(to - from);
        for (Group group : matching.subList(from, to)) {
            rows.add(toRow(group));
        }
        out.setItems(rows);
        out.setHasMore(to < matching.size());

        log.info("Live alert read (org={}, stream={}): {} row(s) scanned, {} alert(s), "
                        + "partial={}, truncated={}",
                org, ingest.getStream(), hits.size(), matching.size(),
                outcome.partial(), out.isTruncated());
        return out;
    }

    /** The requested page index; 0 when absent or negative. */
    private static int pageOf(AlertQueryRequestDTO.Search request) {
        return request == null || request.getPage() == null || request.getPage() < 0
                ? 0 : request.getPage();
    }

    /** The requested window expression, trimmed; {@link #DEFAULT_WINDOW} when absent. */
    private static String windowOf(AlertQueryRequestDTO.Search request) {
        return request == null || isBlank(request.getWindow())
                ? DEFAULT_WINDOW : request.getWindow().trim();
    }

    /** The groups the request's filters admit, in the requested order. */
    private List<Group> matchingSorted(List<Group> groups, AlertQueryRequestDTO.Search request) {
        List<Group> matching = new ArrayList<>();
        for (Group candidate : groups) {
            if (matches(candidate, request)) {
                matching.add(candidate);
            }
        }
        matching.sort(comparator(request == null ? null : request.getSortBy(),
                request == null ? null : request.getSortDir()));
        return matching;
    }

    // ── reading ─────────────────────────────────────────────────────────────

    /**
     * Newest first, so the scan cap cuts the OLDEST firings rather than the ones the
     * operator opened the screen to see. The ingest poller orders the same query ASC
     * for the opposite reason — it is walking forward and must not skip a row.
     */
    private SearchOutcome fetch(String org, long startMicros, long endMicros) {
        String stream = SqlSafety.validateIdentifier(ingest.getStream());
        String sql = "SELECT * FROM \"" + stream + "\" "
                + "WHERE " + AlertStreamRowMapper.TIMESTAMP_COLUMN + " >= " + startMicros + " "
                + "AND " + AlertStreamRowMapper.TIMESTAMP_COLUMN + " <= " + endMicros + " "
                + "ORDER BY " + AlertStreamRowMapper.TIMESTAMP_COLUMN + " DESC";

        String previousOrg = OrgContext.get();
        try {
            // Same reason as the poller: the client builds its URL from OrgContext, and
            // a request thread may carry a different tenant's value than the one asked
            // for here. Restored in the finally block.
            if (OrgContext.isValid(org)) {
                OrgContext.set(org);
            } else {
                OrgContext.clear();
            }
            return openObserve.searchDetailed(sql, startMicros, endMicros, 0,
                    MAX_SCAN_ROWS, ingest.getStreamType());
        } finally {
            if (previousOrg == null) {
                OrgContext.clear();
            } else {
                OrgContext.set(previousOrg);
            }
        }
    }

    /**
     * Firings folded into one entry per fingerprint.
     *
     * <p>The same identity the poller uses, so an alert has the same fingerprint on
     * this screen whichever path drew it — otherwise switching to the fallback would
     * appear to renumber every alert on the page.</p>
     */
    private List<Group> group(List<Map<String, Object>> hits, String org) {
        Map<String, Group> byFingerprint = new LinkedHashMap<>();
        int unmappable = 0;

        for (Map<String, Object> row : hits) {
            AlertEvent event = mapRow(row, org);
            if (event == null) {
                unmappable++;
                continue;
            }
            String key = event.getFingerprint() == null || event.getFingerprint().isBlank()
                    ? "name:" + nz(event.getAlertName()) : event.getFingerprint();
            byFingerprint.computeIfAbsent(key, k -> new Group()).add(event);
        }

        if (unmappable > 0) {
            log.warn("Live alert read skipped {} unmappable row(s) of {}", unmappable, hits.size());
        }
        return new ArrayList<>(byFingerprint.values());
    }

    /** Maps one stream row, or returns null when it cannot be read. */
    private AlertEvent mapRow(Map<String, Object> row, String org) {
        try {
            return AlertStreamRowMapper.map(row, org, ingest.getMapping(),
                    ingest.getMaxPayloadChars(), ingest.getIdentityFields(),
                    incident.getSeverityOrder(), incident.isStripSeveritySuffix());
        } catch (Exception e) {
            // One unreadable row must not cost the page, exactly as in ingest.
            return null;
        }
    }

    /** One alert's firings inside the window. */
    private static final class Group {

        /**
         * The NEWEST firing, which is what every descriptive field is taken from.
         *
         * <p>Rows arrive newest-first, so the first one seen wins. That matches the
         * stored path, where each firing overwrites the identity fields — an alert
         * whose affected hosts changed should read as what it is now.</p>
         */
        private AlertEvent newest;
        private long firstMicros = Long.MAX_VALUE;
        private long lastMicros = Long.MIN_VALUE;
        private int occurrences;

        void add(AlertEvent event) {
            if (newest == null) {
                newest = event;
            }
            occurrences++;
            long micros = event.getTimestampMicros();
            if (micros > 0) {
                firstMicros = Math.min(firstMicros, micros);
                lastMicros = Math.max(lastMicros, micros);
            }
        }

        long durationSeconds() {
            if (firstMicros == Long.MAX_VALUE || lastMicros == Long.MIN_VALUE) {
                return 0L;
            }
            return Math.max(0L, (lastMicros - firstMicros) / 1_000_000L);
        }
    }

    // ── filtering and sorting ───────────────────────────────────────────────

    /**
     * Only the filters the stream can actually answer.
     *
     * <p>Search and severity are applied; service, stream, source and rule are not,
     * because a filter that silently does nothing is worse than one that is absent —
     * the operator would read an unfiltered list as a filtered one.</p>
     */
    private boolean matches(Group group, AlertQueryRequestDTO.Search request) {
        if (request == null) {
            return true;
        }
        AlertEvent event = group.newest;

        List<String> severities = request.getSeverity();
        if (severities != null && !severities.isEmpty() && !hasSeverity(event, severities)) {
            return false;
        }
        return matchesSearch(event, request.getSearch());
    }

    /** True when the event's severity is one of {@code severities}, ignoring case. */
    private static boolean hasSeverity(AlertEvent event, List<String> severities) {
        String severity = nz(event.getSeverity()).toLowerCase(Locale.ROOT);
        for (String wanted : severities) {
            if (wanted != null && wanted.trim().equalsIgnoreCase(severity)) {
                return true;
            }
        }
        return false;
    }

    /** True when there is no search text, or the event's text fields contain it. */
    private static boolean matchesSearch(AlertEvent event, String search) {
        if (search != null && !search.isBlank()) {
            String needle = search.trim().toLowerCase(Locale.ROOT);
            String haystack = String.join(" ",
                    nz(event.getAlertName()), nz(event.getServiceName()), nz(event.getHostName()),
                    nz(event.getEntities()), nz(event.getNamespace()), nz(event.getEnvironment()),
                    nz(event.getDescription()), nz(event.getSummary()), nz(event.getTraceId()))
                    .toLowerCase(Locale.ROOT);
            return haystack.contains(needle);
        }
        return true;
    }

    private Comparator<Group> comparator(String sortBy, String sortDir) {
        Comparator<Group> byField = switch (sortBy == null ? "" : sortBy.trim()) {
            case "severity" -> Comparator.comparingInt(
                    g -> severityRank(g.newest.getSeverity()));
            case "alertName" -> Comparator.comparing(
                    g -> nz(g.newest.getAlertName()), String.CASE_INSENSITIVE_ORDER);
            case "serviceName" -> Comparator.comparing(
                    g -> nz(g.newest.getServiceName()), String.CASE_INSENSITIVE_ORDER);
            case "occurrences" -> Comparator.comparingInt(g -> g.occurrences);
            case "firstFiredAt" -> Comparator.comparingLong(g -> g.firstMicros);
            default -> Comparator.comparingLong(g -> g.lastMicros);
        };
        // Descending by default: on an alerts screen the newest, worst and loudest are
        // all wanted at the top, and every default sort here is one of those.
        boolean ascending = "asc".equalsIgnoreCase(sortDir);
        return ascending ? byField : byField.reversed();
    }

    private static int severityRank(String severity) {
        int index = SEVERITY_RANK.indexOf(nz(severity).toLowerCase(Locale.ROOT));
        // Unknown severities sort last rather than first: a value nobody recognises is
        // not evidence of urgency.
        return index < 0 ? SEVERITY_RANK.size() : index;
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private AlertQueryResponseDTO.Row toRow(Group group) {
        AlertEvent event = group.newest;
        AlertQueryResponseDTO.Row row = new AlertQueryResponseDTO.Row();

        // NO id, deliberately. Every write route takes one, so a row without an id
        // cannot be acknowledged, resolved or closed by any caller — the absence is
        // what makes "read-only" true rather than merely displayed.
        row.setId(null);
        row.setFingerprint(event.getFingerprint());
        row.setAlertName(event.getAlertName());
        // Set here too, not only on the stored path: this fallback groups by the SAME
        // fingerprint the poller uses, so the rows are already grouped by incident type
        // and omitting it would show the identity column blank on exactly the screen an
        // operator falls back to when they cannot trust the stored table.
        row.setIncidentType(event.getIncidentType());
        row.setSeverity(event.getSeverity());
        row.setSeveritySource(event.getSeveritySource());
        // Everything in the stream is a firing — the stream records that a condition
        // was met and nothing else. Whether it has since recovered is a question only
        // the lifecycle can answer, and the lifecycle is what is unavailable here.
        row.setStatus("FIRING");
        row.setSource("live-stream");

        row.setServiceName(event.getServiceName());
        row.setHostName(event.getHostName());
        row.setNamespace(event.getNamespace());
        row.setEnvironment(event.getEnvironment());
        row.setStreamName(event.getStreamName());
        row.setStreamType(event.getStreamType());

        row.setTriggeredAt(iso(group.firstMicros));
        row.setLastFiredAt(iso(group.lastMicros));
        row.setLastUpdatedAt(iso(group.lastMicros));
        row.setDurationSeconds(group.durationSeconds());
        row.setDuration(humanDuration(group.durationSeconds()));

        row.setOccurrences(group.occurrences);
        // Episode 0, not 1: episodes are counted across an alert's whole history and
        // this path can only see one window of it. Claiming "episode 1" would assert
        // this is the first time the problem has happened, which it cannot know.
        row.setEpisode(0);

        AlertDescriptionNormalizer.Normalized description =
                AlertDescriptionNormalizer.normalize(event.getDescription());
        row.setDescription(description.text());
        row.setDescriptionHeadline(description.headline());
        row.setAffectedCount(description.affectedCount());
        row.setSummary(event.getSummary());
        row.setEntities(event.getEntities());
        row.setTraceId(event.getTraceId());
        row.setAlertUrl(event.getAlertUrl());

        row.setThreshold(event.getThreshold());
        row.setObservedCount(event.getObservedCount());
        row.setAggValue(event.getAggValue());
        row.setOperator(event.getOperator());
        row.setPeriodMinutes(event.getPeriod());
        row.setWindowStart(event.getWindowStartMicros() == null
                ? null : TimeFormatter.microsToIso(event.getWindowStartMicros()));
        row.setWindowEnd(event.getWindowEndMicros() == null
                ? null : TimeFormatter.microsToIso(event.getWindowEndMicros()));
        row.setParseError(event.getParseError());

        // The same generic projection the stored path does, from the same raw copy — so
        // the details panel is populated on this screen too. It costs one parse per
        // grouped row rather than per hit, because the projection describes the NEWEST
        // firing and that is the only one whose values are current.
        AlertAttributeProjector.Attributes attributes = AlertAttributeProjector.project(
                AlertMeasurementReader.parse(event.getRawPayload()),
                incident.getExcludeAttributes(), incident.getMaxAttributes());
        row.setCurrentValues(attributes.currentValues());
        row.setLabels(attributes.labels());

        // Explicitly empty: no id means no transition route can address this row, so
        // offering an action would be offering one that cannot be taken. The default is
        // already an empty list; saying so here keeps the reason next to the decision.
        row.setActions(java.util.List.of());
        return row;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static int clampSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }

    private static String iso(long micros) {
        return micros == Long.MAX_VALUE || micros == Long.MIN_VALUE
                ? null : TimeFormatter.microsToIso(micros);
    }

    private static String humanDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
