package com.trinity.observability.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trinity.observability.entity.AlertEventEntity;

/** Append-only audit trail. Nothing here updates or deletes. */
@Repository
public interface AlertEventRepository extends JpaRepository<AlertEventEntity, Long> {

    /**
     * History for an org, optionally narrowed to one alert.
     *
     * <p>Filtered by fingerprint rather than {@code alert_id} so the trail spans
     * every episode of the same alert. Scoping to one alert row would silently
     * cut the history at the last time the alert resolved, which is usually the
     * moment the person reading it cares most about.</p>
     */
    @Query("""
            SELECT e FROM AlertEventEntity e
            WHERE e.org = :org
              AND (:fingerprint IS NULL OR e.fingerprint = :fingerprint)
              AND e.occurredAt >= :since
            """)
    Page<AlertEventEntity> findHistory(@Param("org") String org,
                                       @Param("fingerprint") String fingerprint,
                                       @Param("since") Instant since,
                                       Pageable pageable);

    /**
     * Whether this exact stream row has already been ingested.
     *
     * <p>The cheap pre-check for the poller. {@code uq_alert_events_hash} is the
     * real guarantee — this only avoids doing the work in the common case, which is
     * the overlap window the poller deliberately re-reads every time.</p>
     */
    boolean existsByOrgAndEventHash(String org, String eventHash);

    /** One alert's trail, newest first, spanning every episode of its fingerprint. */
    List<AlertEventEntity> findByOrgAndFingerprintOrderByOccurredAtDesc(
            String org, String fingerprint, Pageable pageable);
}
