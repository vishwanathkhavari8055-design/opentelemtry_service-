package com.trinity.observability.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.entity.SyncStatus;

/**
 * Alert rule definitions — the source of truth.
 *
 * <p>Every finder filters {@code deletedAt IS NULL}. Deletion here is soft, so
 * a query that forgot the predicate would resurrect removed rules into the UI
 * and, worse, into {@code syncAll}, which would recreate them in OpenObserve
 * and start paging people about rules they deleted. The two methods that
 * deliberately include deleted rows say so in their names.</p>
 */
@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, Long> {

    Optional<AlertRuleEntity> findByUuidAndDeletedAtIsNull(UUID uuid);

    /**
     * By uuid, INCLUDING soft-deleted rows.
     *
     * <p>Needed by the sync path, which has to keep acting on rules after they
     * are deleted here — a delete is not finished until OpenObserve has also
     * dropped it, and until then the row must stay reachable.</p>
     */
    Optional<AlertRuleEntity> findByUuid(UUID uuid);

    Optional<AlertRuleEntity> findByOrgAndNameAndDeletedAtIsNull(String org, String name);

    Optional<AlertRuleEntity> findByOrgAndOpenObserveIdAndDeletedAtIsNull(String org, String openObserveId);

    boolean existsByOrgAndNameAndDeletedAtIsNull(String org, String name);

    List<AlertRuleEntity> findByOrgAndDeletedAtIsNullOrderByNameAsc(String org);

    /**
     * Filtered page for the rules table.
     *
     * <p>Written out rather than derived from a method name because the
     * optional-filter pattern ({@code :param IS NULL OR column = :param}) has no
     * derived-query equivalent, and four overloads for four filter combinations
     * is worse than one query.</p>
     *
     * <p>{@code CAST(:search AS string)} is load-bearing: a null parameter is
     * sent untyped, PostgreSQL infers {@code bytea}, and the query fails with
     * {@code function lower(bytea) does not exist} for every caller — not just
     * those searching. See {@link AlertRepository#search}.</p>
     */
    @Query("""
            SELECT r FROM AlertRuleEntity r
            WHERE r.deletedAt IS NULL
              AND r.org = :org
              AND (:enabled    IS NULL OR r.enabled    = :enabled)
              AND (:streamType IS NULL OR r.streamType = :streamType)
              AND (:severity   IS NULL OR r.severity   = :severity)
              AND (:syncStatus IS NULL OR r.syncStatus = :syncStatus)
              AND (:search     IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<AlertRuleEntity> search(@Param("org") String org,
                                 @Param("enabled") Boolean enabled,
                                 @Param("streamType") String streamType,
                                 @Param("severity") String severity,
                                 @Param("syncStatus") SyncStatus syncStatus,
                                 @Param("search") String search,
                                 Pageable pageable);

    /** Live rules for an org, in a stable order so a sync run is reproducible. */
    @Query("""
            SELECT r FROM AlertRuleEntity r
            WHERE r.deletedAt IS NULL AND r.org = :org
            ORDER BY r.id ASC
            """)
    List<AlertRuleEntity> findAllLiveForSync(@Param("org") String org);

    /**
     * Rules deleted here but still believed to exist in OpenObserve.
     *
     * <p>Deliberately includes soft-deleted rows — these are exactly the ones a
     * sync must chase down and remove remotely. Without this they would keep
     * firing with nothing in the UI explaining where the alert came from.</p>
     */
    @Query("""
            SELECT r FROM AlertRuleEntity r
            WHERE r.org = :org
              AND r.syncStatus = com.trinity.observability.entity.SyncStatus.DELETE_PENDING
              AND r.openObserveId IS NOT NULL
            ORDER BY r.id ASC
            """)
    List<AlertRuleEntity> findPendingDeletionsIncludingDeleted(@Param("org") String org);

    /**
     * Staged rules whose start time has arrived.
     *
     * <p>Feeds the activation sweep behind "enable later". Three predicates, and
     * each one is load-bearing:</p>
     *
     * <ul>
     *   <li>{@code deletedAt IS NULL} — a rule deleted while staged must not be
     *       resurrected by its own timer, which is the one way a deleted rule
     *       could start paging people.</li>
     *   <li>{@code enabled = false} — the sweep only ever turns rules ON. A row
     *       somebody enabled by hand before its time is already where it wants
     *       to be, and re-writing it would push it to OpenObserve for nothing.</li>
     *   <li>{@code activationMode = SCHEDULED} — {@code LATER} is waiting for a
     *       person, deliberately, and no timer may decide on their behalf.</li>
     * </ul>
     *
     * <p>Oldest due first, so a backlog is cleared in the order it accrued.</p>
     */
    @Query("""
            SELECT r FROM AlertRuleEntity r
            WHERE r.deletedAt IS NULL
              AND r.org = :org
              AND r.enabled = false
              AND r.activationMode = com.trinity.observability.entity.ActivationMode.SCHEDULED
              AND r.activateAt IS NOT NULL
              AND r.activateAt <= :now
            ORDER BY r.activateAt ASC
            """)
    List<AlertRuleEntity> findDueForActivation(@Param("org") String org,
                                               @Param("now") java.time.Instant now,
                                               Pageable pageable);

    long countByOrgAndSyncStatusAndDeletedAtIsNull(String org, SyncStatus syncStatus);

    /**
     * Rules whose stored definition is not what OpenObserve is evaluating.
     *
     * <p>Feeds the retry sweep, which is what makes manual synchronisation
     * unnecessary. Covers three states, and the third is why soft-deleted rows are
     * included rather than filtered out:</p>
     *
     * <ul>
     *   <li>PENDING — stored but never successfully pushed.</li>
     *   <li>FAILED — a push was attempted and rejected.</li>
     *   <li>DELETE_PENDING — deleted here but possibly still firing there, which is
     *       the most urgent of the three and the only one whose row is deleted.</li>
     * </ul>
     *
     * <p>Ordered oldest-first so a rule that has been broken longest is retried
     * before one that broke a moment ago.</p>
     */
    @Query("""
            SELECT r FROM AlertRuleEntity r
            WHERE r.org = :org
              AND r.syncStatus IN (
                    com.trinity.observability.entity.SyncStatus.PENDING,
                    com.trinity.observability.entity.SyncStatus.FAILED,
                    com.trinity.observability.entity.SyncStatus.DELETE_PENDING)
            ORDER BY r.updatedAt ASC
            """)
    List<AlertRuleEntity> findOutOfSync(@Param("org") String org, Pageable pageable);
}
