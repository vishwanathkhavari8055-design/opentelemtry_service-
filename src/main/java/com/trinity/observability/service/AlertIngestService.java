package com.trinity.observability.service;

import java.util.List;

/**
 * Reads fired alerts out of an OpenObserve stream and stores them in PostgreSQL.
 *
 * <p>This is what replaced the inbound webhook. The direction of the dependency
 * is the whole point: a webhook needs OpenObserve to reach this service, which on
 * this deployment meant a free ngrok tunnel whose URL changed on every restart —
 * so alerts stopped arriving silently, and nothing in the system could tell the
 * difference between "quiet" and "broken". Polling uses the same outbound path
 * every other screen already uses, and records a watermark, so a stalled ingest is
 * a value you can query rather than an absence you have to notice.</p>
 *
 * <h2>Where the alerts come from</h2>
 *
 * <pre>
 *   rule matches in OpenObserve
 *        │  an OpenObserve alert DESTINATION posts the rendered template
 *        │  into OpenObserve's own ingest API — this service is not involved
 *        ▼
 *   stream: alert_events
 *        │  polled on a timer               ← {@link #pollOnce()}
 *        ▼
 *   alerts        — one row per EPISODE, updated in place
 *   alert_events  — append-only audit trail, de-duplicated by row hash
 * </pre>
 */
public interface AlertIngestService {

    /** Outcome of polling one (org, stream) pair. */
    record PollResult(String org, String stream, int rowsRead, int stored, int duplicates,
            long watermarkMicros, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Poll every configured organization once.
     *
     * <p>Never throws. An alert ingest that dies on a transient upstream failure
     * is an alert ingest that stops for good the first time OpenObserve restarts,
     * so every failure is recorded against the cursor and reported in the result
     * instead of propagating.</p>
     *
     * @return one result per organization polled; empty when ingest is disabled
     */
    List<PollResult> pollOnce();

    /**
     * Ingest health, for the UI banner and for whoever is asked why the alert list
     * looks empty.
     */
    List<CursorStatus> cursorStatus();

    /**
     * One cursor's state, flattened for display.
     *
     * <p>{@code healthy} and {@code stalled} answer different questions and a poller
     * can be the first without being the second. {@code healthy} means the polls are
     * SUCCEEDING; {@code stalled} means they are succeeding without the watermark
     * moving, which is the failure that hid a 32-hour outage behind a green banner.
     * Neither implies the other, so both are reported.</p>
     *
     * @param watermarkAgeSeconds how far behind now the watermark sits. On a quiet
     *        system this is simply the time since the last alert fired, so it is a
     *        number to display rather than a condition to judge — {@code stalled} is
     *        the judgement.
     */
    record CursorStatus(String org, String stream, String lastEventAt, String lastPolledAt,
            String lastSuccessAt, String lastError, int consecutiveFailures,
            long eventsIngested, boolean healthy, long watermarkAgeSeconds, boolean stalled) {
    }
}
