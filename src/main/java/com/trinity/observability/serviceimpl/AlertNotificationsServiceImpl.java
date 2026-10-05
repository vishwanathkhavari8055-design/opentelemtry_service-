package com.trinity.observability.serviceimpl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.trinity.observability.config.AlertPersistenceConfig;
import com.trinity.observability.config.AlertLifecycleProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertEntityStatusCount;
import com.trinity.observability.dto.alerts.AlertNotificationsRequestDTO;
import com.trinity.observability.dto.alerts.AlertNotificationsResponseDTO;
import com.trinity.observability.dto.alerts.AlertState;
import com.trinity.observability.entity.AlertEntity;
import com.trinity.observability.entity.AlertEventEntity;
import com.trinity.observability.exception.ResourceNotFoundException;
import com.trinity.observability.repository.AlertEventRepository;
import com.trinity.observability.repository.AlertRepository;
import com.trinity.observability.service.AlertNotificationsService;

/**
 * The live alert feed, stored in PostgreSQL.
 *
 * <h2>Model</h2>
 *
 * <p>Two tables, doing two different jobs. {@code alerts} holds one row per
 * <em>episode</em> and is mutated in place — it answers "what is wrong right
 * now". {@code alert_events} is append-only and answers "what happened", which
 * has to survive the episode row being overwritten by the next firing, because
 * acknowledgements and resolutions are decisions people are accountable for.</p>
 *
 * <p>De-duplication is enforced by the database, not by this class: the partial
 * unique index {@code uq_alerts_open_episode} permits at most one non-resolved
 * row per fingerprint. Two webhook deliveries racing for the same alert cannot
 * both insert, and the loser folds into the winner instead.</p>
 *
 * <h2>Resolution</h2>
 *
 * <p>OpenObserve notifies on evaluation and never sends a "recovered" callback,
 * so an alert that simply stops firing has to be closed by inference. That is
 * {@link #sweepStaleAlerts()}, running on a timer rather than being derived on
 * read — with a database there is one authoritative row, and having a read path
 * silently mutate it would make two concurrent readers disagree about when the
 * alert ended.</p>
 */
@Service
@ConditionalOnBean(AlertPersistenceConfig.class)
public class AlertNotificationsServiceImpl implements AlertNotificationsService {

    private static final Logger log = LogManager.getLogger(AlertNotificationsServiceImpl.class);


    private static final int DEFAULT_HISTORY_HOURS = 24;
    private static final int MAX_HISTORY_HOURS     = 720;
    private static final int DEFAULT_HISTORY_LIMIT = 200;
    private static final int MAX_HISTORY_LIMIT     = 1_000;
    private static final int DEFAULT_LIST_LIMIT    = 500;
    private static final int MAX_LIST_LIMIT        = 2_000;
    private static final int MAX_SWEEP_BATCH       = 500;

    /**
     * Bounds standing in for "no time filter".
     *
     * <p>Needed because a null {@code Instant} parameter reaches PostgreSQL untyped
     * and fails the whole query — see the note on {@code AlertRepository.search}.
     * Year 9999 rather than {@code Instant.MAX}: the JDBC driver rejects
     * {@code Instant.MAX} outright, and a sentinel that throws is worse than none.</p>
     */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    private static final int MAX_ACTOR_CHARS = 128;
    private static final int MAX_NOTE_CHARS  = 500;

    /**
     * Multiplier on an alert's own evaluation period before it is called over.
     * Three missed evaluations, not one — a single skipped run during a scrape
     * hiccup is normal and must not read as "recovered".
     */
    private static final int STALE_PERIOD_MULTIPLIER = 3;

    private static final List<String> SEVERITY_ORDER =
            List.of("disaster", "critical", "error", "warning", "info");

    private final AlertRepository alerts;
    private final AlertEventRepository events;
    private final OpenObserveConfig config;
    private final AlertLifecycleProperties lifecycle;



    public AlertNotificationsServiceImpl(AlertRepository alerts,
                                         AlertEventRepository events,
                                         OpenObserveConfig config,
                                         AlertLifecycleProperties lifecycle) {
        this.alerts = alerts;
        this.events = events;
        this.config = config;
        this.lifecycle = lifecycle;
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.Active> listActive(
            AlertNotificationsRequestDTO.ListFilter filter) {
        return fetchActive(filter);
    }

    private AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.Active> fetchActive(
            AlertNotificationsRequestDTO.ListFilter filter) {

        String org = config.getOrg();
        int limit = clamp(filter.getLimit(), DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);

        Pageable pageable = PageRequest.of(0, limit,
                Sort.by(Sort.Direction.DESC, "lastFiredAt"));

        // Severity is a single value on this legacy filter but a list on the
        // repository, which the enterprise screen needs for multi-select. Wrapping
        // it keeps THIS endpoint's contract byte-for-byte unchanged — the existing
        // UI still calls it and must keep working.
        String severity = blankToNull(filter.getSeverity());

        org.springframework.data.domain.Page<AlertEntity> page = alerts.search(
                org,
                // Collapsed to one row per alert, like the newer screen. This
                // endpoint's job is "what is active now", and an alert that has
                // fired, gone quiet and fired again is one active problem, not
                // three — listing each episode padded the active list with rows
                // that are all the same incident.
                true,
                statusesFor(filter.getState()),
                severity == null ? List.of() : List.of(severity),
                blankToNull(filter.getStreamName()),
                null,
                null,
                null,
                // Explicit open bounds. A null Instant parameter reaches PostgreSQL
                // untyped and fails the entire query — see the note on
                // AlertRepository.search. This endpoint has never filtered by time,
                // so "everything" is the correct window for it.
                Instant.EPOCH,
                FAR_FUTURE,
                blankToNull(filter.getSearch()),
                pageable);

        AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.Active> out = newPage(org);
        out.setSupported(true);
        out.setTotal((int) Math.min(Integer.MAX_VALUE, page.getTotalElements()));
        page.getContent().forEach(a -> out.getItems().add(toActive(a)));

        log.info("listActive() returning {} of {} alert(s) for org={} (state={})",
                out.getItems().size(), out.getTotal(), org, filter.getState());
        return out;
    }

    /**
     * Resolves the {@code state} filter to concrete statuses.
     *
     * <p>Absent means UNRESOLVED, not everything. A feed defaulting to the full
     * set buries the three things currently broken under every alert that has
     * ever fired, which is how alert screens end up ignored.</p>
     */
    private List<AlertState> statusesFor(String state) {
        String value = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "active".equals(value) || "unresolved".equals(value)) {
            return List.of(AlertState.FIRING, AlertState.ACKNOWLEDGED);
        }
        if ("all".equals(value)) {
            return List.of();
        }
        AlertState parsed = AlertState.parse(value, null);
        if (parsed == null) {
            throw new IllegalArgumentException("Unknown state '" + state
                    + "' (expected firing, acknowledged, resolved, active or all)");
        }
        return List.of(parsed);
    }

    @Override
    @Transactional(readOnly = true)
    public AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.HistoryItem> history(
            String fingerprint, Integer hours, Integer limit) {

        String org = config.getOrg();
        int windowHours = clamp(hours, DEFAULT_HISTORY_HOURS, MAX_HISTORY_HOURS);
        int rowLimit = clamp(limit, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT);
        Instant since = Instant.now().minus(Duration.ofHours(windowHours));

        org.springframework.data.domain.Page<AlertEventEntity> page = events.findHistory(
                org,
                blankToNull(fingerprint),
                since,
                PageRequest.of(0, rowLimit, Sort.by(Sort.Direction.DESC, "occurredAt")));

        AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.HistoryItem> out = newPage(org);
        out.setSupported(true);
        out.setTotal((int) Math.min(Integer.MAX_VALUE, page.getTotalElements()));
        page.getContent().forEach(e -> out.getItems().add(toHistoryItem(e)));

        log.info("history() returning {} event(s) for org={} (fingerprint={}, {}h)",
                out.getItems().size(), org, fingerprint, windowHours);
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public AlertNotificationsResponseDTO.Summary summary() {
        String org = config.getOrg();
        AlertNotificationsResponseDTO.Summary s = new AlertNotificationsResponseDTO.Summary();
        s.setOrg(org);
        s.setSupported(true);

        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        SEVERITY_ORDER.forEach(sev -> bySeverity.put(sev, 0));

        for (AlertEntityStatusCount row : alerts.countByStatusAndSeverity(org)) {
            int count = (int) Math.min(Integer.MAX_VALUE, row.getCount());
            switch (row.getStatus()) {
                case FIRING       -> s.setFiring(s.getFiring() + count);
                case ACKNOWLEDGED -> s.setAcknowledged(s.getAcknowledged() + count);
                case RESOLVED     -> s.setResolved(s.getResolved() + count);
                default           -> { /* ignore other status */ }
            }
            if (row.getStatus() != AlertState.RESOLVED) {
                bySeverity.merge(row.getSeverity() == null ? "warning" : row.getSeverity(),
                        count, Integer::sum);
            }
        }
        s.setBySeverity(bySeverity);
        s.setNeedsAttention(s.getFiring() + s.getAcknowledged());
        return s;
    }

    // ── transitions ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AlertNotificationsResponseDTO.WriteResult acknowledge(
            String fingerprint, AlertNotificationsRequestDTO.Action action) {

        AlertEntity alert = requireOpen(fingerprint);
        String actor = sanitise(action == null ? null : action.getActor(), MAX_ACTOR_CHARS, "unknown");
        String note = sanitise(action == null ? null : action.getNote(), MAX_NOTE_CHARS, null);

        alert.acknowledge(actor, note);
        alerts.save(alert);
        events.save(AlertEventEntity.of(alert, AlertState.ACKNOWLEDGED, Instant.now(), actor, note));

        log.info("Alert acknowledged: fingerprint={} org={} by='{}'", fingerprint, alert.getOrg(), actor);
        return writeResult(alert, "Alert acknowledged");
    }

    @Override
    @Transactional
    public AlertNotificationsResponseDTO.WriteResult resolve(
            String fingerprint, AlertNotificationsRequestDTO.Action action) {

        AlertEntity alert = requireOpen(fingerprint);
        String actor = sanitise(action == null ? null : action.getActor(), MAX_ACTOR_CHARS, "unknown");
        String note = sanitise(action == null ? null : action.getNote(), MAX_NOTE_CHARS, null);

        alert.resolve(actor, note, false);
        alerts.save(alert);
        events.save(AlertEventEntity.of(alert, AlertState.RESOLVED, Instant.now(), actor, note));

        log.info("Alert resolved: fingerprint={} org={} by='{}'", fingerprint, alert.getOrg(), actor);
        return writeResult(alert, "Alert resolved");
    }

    private AlertEntity requireOpen(String fingerprint) {
        String org = config.getOrg();
        String fp = validFingerprint(fingerprint);
        return alerts.findOpenEpisode(org, fp).orElseThrow(() -> new ResourceNotFoundException(
                "No open alert with fingerprint '" + fp + "' in org " + org
                        + " — it may already be resolved."));
    }

    // ── staleness sweep ─────────────────────────────────────────────────────

    /**
     * Closes alerts that have stopped firing.
     *
     * <p>Runs in its own transaction, in small batches, and is safe to run on
     * several instances at once: each update is guarded by the row's optimistic
     * lock, so a losing instance simply skips a row another already closed.</p>
     */
    @Scheduled(fixedDelayString = "${alerts.lifecycle.sweeper-interval-ms:60000}",
               initialDelayString = "${alerts.lifecycle.sweeper-initial-delay-ms:30000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweepStaleAlerts() {
        if (!lifecycle.isAutoResolveEnabled()) {
            // A deployment whose rules notify on recovery as well as on breach has
            // a real signal, and inferring from silence would be strictly worse
            // than the one it already has.
            return;
        }
        int baseMinutes = Math.max(1, lifecycle.getStaleAfterMinutes());
        Instant now = Instant.now();

        // Candidates are anything past the BASE window; the per-alert window may
        // be longer, so the final decision is made per row below. Querying on
        // the base window keeps the index usable instead of forcing a scan with
        // arithmetic in the predicate.
        List<AlertEntity> candidates = alerts.findStaleOpenEpisodes(
                now.minus(Duration.ofMinutes(baseMinutes)),
                PageRequest.of(0, MAX_SWEEP_BATCH));

        int closed = 0;
        for (AlertEntity alert : candidates) {
            if (!isStale(alert, now, baseMinutes)) continue;
            alert.resolve("auto:stale", null, true);
            alerts.save(alert);
            events.save(AlertEventEntity.of(alert, AlertState.RESOLVED,
                    alert.getResolvedAt(), "auto:stale", "Closed automatically after no further firings"));
            closed++;
        }

        if (closed > 0) {
            log.info("Stale sweep closed {} alert(s) of {} candidate(s)", closed, candidates.size());
        }
        if (candidates.size() >= MAX_SWEEP_BATCH) {
            log.warn("Stale sweep hit its batch cap of {} — more remain and will be closed on the "
                    + "next run", MAX_SWEEP_BATCH);
        }
    }

    /**
     * Whether an alert has been quiet long enough to call over.
     *
     * <p>The window is the configured floor or three of the alert's own
     * evaluation periods, whichever is longer. A rule evaluated hourly would
     * otherwise be declared recovered fifteen minutes after every firing and
     * reopened on the next, producing a stream of phantom incidents.</p>
     */
    private boolean isStale(AlertEntity alert, Instant now, int baseMinutes) {
        long periodMinutes = alert.getPeriodMinutes() == null
                ? 0L : (long) alert.getPeriodMinutes() * STALE_PERIOD_MULTIPLIER;
        long windowMinutes = Math.max(baseMinutes, periodMinutes);
        return alert.getLastFiredAt().isBefore(now.minus(Duration.ofMinutes(windowMinutes)));
    }

    @Override
    @Transactional(readOnly = true)
    public int reload() {
        // Nothing to rebuild: PostgreSQL holds the state directly, so there is
        // no in-memory projection that can drift from it. Kept on the interface
        // because the endpoint is public API; it now reports the live count and
        // is otherwise a no-op.
        String org = config.getOrg();
        AlertNotificationsRequestDTO.ListFilter all = new AlertNotificationsRequestDTO.ListFilter();
        all.setState("all");
        all.setLimit(1);
        int total = fetchActive(all).getTotal();
        log.info("reload() no-op — PostgreSQL is authoritative; org={} holds {} alert(s)", org, total);
        return total;
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private AlertNotificationsResponseDTO.Active toActive(AlertEntity e) {
        AlertNotificationsResponseDTO.Active a = new AlertNotificationsResponseDTO.Active();
        a.setFingerprint(e.getFingerprint());
        a.setOrg(e.getOrg());
        a.setAlertName(e.getAlertName());
        a.setAlertType(e.getAlertType());
        a.setStreamType(e.getStreamType());
        a.setStreamName(e.getStreamName());
        a.setSeverity(e.getSeverity());
        a.setSeveritySource(e.getSeveritySource());
        a.setState(e.getStatus().wire());
        a.setActions(AlertState.availableActions(e.getStatus()));
        a.setFirstFiredAt(iso(e.getFirstFiredAt()));
        a.setLastFiredAt(iso(e.getLastFiredAt()));
        a.setFirstFiredAtMicros(toMicros(e.getFirstFiredAt()));
        a.setLastFiredAtMicros(toMicros(e.getLastFiredAt()));
        a.setOccurrences(e.getOccurrences());
        a.setEpisodes(e.getEpisode());
        a.setAcknowledgedBy(e.getAcknowledgedBy());
        a.setAcknowledgedAt(iso(e.getAcknowledgedAt()));
        a.setNewSinceAck(e.getNewSinceAck());
        a.setResolvedBy(e.getResolvedBy());
        a.setResolvedAt(iso(e.getResolvedAt()));
        a.setAutoResolved(e.isAutoResolved());
        a.setNote(e.getNote());
        a.setPeriod(e.getPeriodMinutes());
        a.setOperator(e.getOperator());
        a.setThreshold(e.getThreshold());
        a.setObservedCount(e.getObservedCount());
        a.setAggValue(e.getAggValue());
        a.setAlertUrl(e.getAlertUrl());
        a.setParseError(e.getParseError());
        return a;
    }

    private AlertNotificationsResponseDTO.HistoryItem toHistoryItem(AlertEventEntity e) {
        AlertNotificationsResponseDTO.HistoryItem h = new AlertNotificationsResponseDTO.HistoryItem();
        h.setFingerprint(e.getFingerprint());
        h.setEventType(e.getEventType() == null ? null : e.getEventType().wire());
        h.setOccurredAt(iso(e.getOccurredAt()));
        h.setOccurredAtMicros(toMicros(e.getOccurredAt()));
        h.setActor(e.getActor());
        h.setNote(e.getNote());
        h.setObservedCount(e.getObservedCount());
        h.setAggValue(e.getAggValue());
        return h;
    }

    private AlertNotificationsResponseDTO.WriteResult writeResult(AlertEntity alert, String message) {
        AlertNotificationsResponseDTO.WriteResult w = new AlertNotificationsResponseDTO.WriteResult();
        w.setOk(true);
        w.setMessage(message);
        w.setAlert(toActive(alert));
        return w;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private <T> AlertNotificationsResponseDTO.Page<T> newPage(String org) {
        AlertNotificationsResponseDTO.Page<T> p = new AlertNotificationsResponseDTO.Page<>();
        p.setItems(new ArrayList<>());
        p.setOrg(org);
        p.setBackend("postgresql");
        return p;
    }


    private static String validFingerprint(String fp) {
        String v = fp == null ? "" : fp.trim().toLowerCase(Locale.ROOT);
        if (!v.matches("^[a-f0-9]{16}$")) {
            throw new IllegalArgumentException(
                    "Invalid fingerprint: '" + fp + "' (expected 16 hex characters)");
        }
        return v;
    }


    private static long toMicros(Instant instant) {
        return instant == null ? 0L
                : instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }


    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String sanitise(String value, int maxChars, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim();
        return v.length() > maxChars ? v.substring(0, maxChars) : v;
    }

    private static int clamp(Integer requested, int fallback, int max) {
        if (requested == null || requested <= 0) return fallback;
        return Math.min(requested, max);
    }
}
