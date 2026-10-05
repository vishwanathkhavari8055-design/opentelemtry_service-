package com.trinity.observability.service;

import com.trinity.observability.dto.alerts.AlertNotificationsRequestDTO;
import com.trinity.observability.dto.alerts.AlertNotificationsResponseDTO;

/**
 * The live alert feed: what is firing right now, what someone has picked up,
 * and what is over.
 *
 * <p>Distinct from {@link AlertsService}, which manages alert <em>definitions</em>.
 * This one is about their <em>output</em> — the notifications OpenObserve emits
 * when a rule matches. The two meet only at the destination that connects them.</p>
 *
 * <h2>How an alert gets here</h2>
 *
 * <pre>
 *   rule matches in OpenObserve
 *        │  an OpenObserve DESTINATION posts the rendered template into
 *        │  OpenObserve's own ingest API — this service is not in that path
 *        ▼
 *   stream: alert_events
 *        │  polled on a timer by AlertIngestService, stored by
 *        │  AlertProcessingService
 *        ▼
 *   alerts        — one row per EPISODE, updated in place
 *   alert_events  — append-only audit trail, de-duplicated by row hash
 *        ▼
 *   GET /api/alerts/notifications     ← {@link #listActive}
 * </pre>
 *
 * <p>PostgreSQL is the source of truth. The two tables answer two different
 * questions: {@code alerts} is "what is wrong right now" and is mutated as an
 * incident develops, while {@code alert_events} is "what happened" and is never
 * rewritten — an acknowledgement is a decision someone is accountable for, and
 * that record has to outlive the next firing overwriting the alert row.</p>
 */
public interface AlertNotificationsService {


    /**
     * Alerts for the current org, newest firing first.
     *
     * <p>With no {@code state} filter this returns UNRESOLVED alerts only —
     * firing and acknowledged. A feed that defaults to showing everything ever
     * received buries the four things currently on fire under nine hundred that
     * are not. Pass {@code state=all} for the full set, or {@code state=resolved}
     * for recent history.</p>
     */
    AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.Active> listActive(
            AlertNotificationsRequestDTO.ListFilter filter);

    /**
     * The event trail, read straight from the stream rather than the projection.
     *
     * @param fingerprint restrict to one alert, or null for all
     * @param hours       lookback window; defaults to 24, capped at 720 (30 days)
     * @param limit       max rows; defaults to 200
     */
    AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.HistoryItem> history(
            String fingerprint, Integer hours, Integer limit);

    /** Counts for the nav badge, without shipping the whole list. */
    AlertNotificationsResponseDTO.Summary summary();

    /**
     * Mark an alert as being dealt with.
     *
     * <p>Does not silence it — it will keep firing and the occurrence count will
     * keep rising. Acknowledging says "a human owns this", not "stop telling me".</p>
     */
    AlertNotificationsResponseDTO.WriteResult acknowledge(
            String fingerprint, AlertNotificationsRequestDTO.Action action);

    /** Close an alert by hand, ahead of the staleness window. */
    AlertNotificationsResponseDTO.WriteResult resolve(
            String fingerprint, AlertNotificationsRequestDTO.Action action);



    /**
     * Discard the in-memory projection for the current org and replay it from
     * the stream.
     *
     * <p>Exists because the warm-up is not retried on failure: if OpenObserve
     * was unreachable at the moment an org was first read, the feed would stay
     * empty until a restart without this.</p>
     *
     * @return number of alerts in the rebuilt projection
     */
    int reload();

}
