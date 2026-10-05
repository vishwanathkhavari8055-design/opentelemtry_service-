package com.trinity.observability.service;

import com.trinity.observability.dto.alerts.AlertEvent;

/**
 * Folds one fired alert into PostgreSQL.
 *
 * <p>Split out of the old notifications service so that the two questions it used
 * to answer at once are separable: <em>how an alert arrives</em> (now
 * {@link AlertIngestService}, previously a webhook controller) and <em>what
 * storing one means</em> (here). The ingest path changed completely; none of this
 * had to.</p>
 *
 * <h2>Two layers of duplicate prevention, doing different jobs</h2>
 *
 * <ol>
 *   <li><b>Episode</b> — {@code uq_alerts_open_episode} keeps at most one open
 *       alert per fingerprint, so a condition that keeps matching every minute is
 *       ONE row with a rising occurrence count rather than sixty rows. This is
 *       about what the operator reads.</li>
 *   <li><b>Event</b> — {@code uq_alert_events_hash} rejects a stream row that has
 *       already been ingested, so re-reading a range (which the poller does
 *       deliberately, via its overlap window) does not inflate that occurrence
 *       count or duplicate the audit trail. This is about what the numbers mean.</li>
 * </ol>
 *
 * <p>The first existed before; the second is what a polling ingest requires and a
 * push ingest did not.</p>
 */
public interface AlertProcessingService {

    /**
     * Store one firing.
     *
     * <p>Transactional and idempotent: calling it twice with the same event stores
     * one firing.</p>
     *
     * @param event  the mapped alert
     * @param source label for {@code alerts.source}, identifying the ingest path
     * @return true when this firing was newly recorded, false when it had already
     *         been ingested (the caller counts it as a duplicate, not an error)
     */
    boolean store(AlertEvent event, String source);
}
