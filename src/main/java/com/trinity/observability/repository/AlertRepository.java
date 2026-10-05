package com.trinity.observability.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trinity.observability.dto.alerts.AlertEntityStatusCount;
import com.trinity.observability.dto.alerts.AlertState;
import com.trinity.observability.entity.AlertEntity;

/**
 * Alert episodes.
 *
 * <p>The important query here is {@link #findOpenEpisode}: it is how a repeat
 * firing finds the row to fold itself into instead of creating a duplicate. It
 * mirrors the partial unique index {@code uq_alerts_open_episode}, which is the
 * actual guarantee — the query is the fast path, the index is what holds under
 * a race.</p>
 *
 * <h2>"Open" means neither RESOLVED nor CLOSED</h2>
 *
 * <p>Both queries below test against {@link AlertState#terminalStatuses()} rather
 * than naming RESOLVED alone. That list and the two index predicates in migration
 * V3 have to agree: a CLOSED alert that still matched "open" would keep reserving
 * its fingerprint, so the next genuine firing would fold into the closed incident
 * instead of opening a new episode — and the operator who closed it would watch it
 * silently come back to life with a stale first-seen time.</p>
 */
@Repository
public interface AlertRepository extends JpaRepository<AlertEntity, Long> {

    /** The single open (neither resolved nor closed) episode for a fingerprint. */
    @Query("""
            SELECT a FROM AlertEntity a
            WHERE a.org = :org
              AND a.fingerprint = :fingerprint
              AND a.status NOT IN :terminal
            """)
    Optional<AlertEntity> findOpenEpisode(@Param("org") String org,
                                         @Param("fingerprint") String fingerprint,
                                         @Param("terminal") List<AlertState> terminal);

    /**
     * The open episode for a fingerprint, using the canonical terminal set.
     *
     * <p>A default method rather than a second {@code @Query}, so there is exactly
     * one place that decides what "open" means.</p>
     */
    default Optional<AlertEntity> findOpenEpisode(String org, String fingerprint) {
        return findOpenEpisode(org, fingerprint, AlertState.terminalStatuses());
    }

    /** Highest episode number seen for a fingerprint, so a new incident numbers correctly. */
    @Query("""
            SELECT COALESCE(MAX(a.episode), 0) FROM AlertEntity a
            WHERE a.org = :org AND a.fingerprint = :fingerprint
            """)
    int findMaxEpisode(@Param("org") String org, @Param("fingerprint") String fingerprint);

    /**
     * Filtered page for the alerts screen.
     *
     * <p>{@code statuses} being empty means "no status filter". The caller
     * resolves the default — unresolved only — rather than this query assuming
     * it, so that the default lives next to the API contract that documents it.</p>
     *
     * <p>{@code CAST(:search AS string)} is required, not decorative. When the
     * parameter is null the driver sends it untyped, PostgreSQL infers
     * {@code bytea}, and the whole query dies with
     * {@code function lower(bytea) does not exist} — for every caller, including
     * those not searching at all. The cast pins the type so a null search still
     * plans as text. The same applies to every other nullable text parameter
     * added below.</p>
     *
     * <p>Search spans the fields the toolbar's single box is expected to cover —
     * alert name, incident type, service, host, stream and trace id — because a
     * "global search" that silently only matched the name would look broken to anyone
     * pasting a trace id into it.</p>
     *
     * <p>{@code incidentType} is in that list for a specific reason: the alerts screen
     * has no Service or Type dropdown, deliberately — see {@code AlertFilters.jsx},
     * which removed them because this box covers them. The type is now the grouping
     * identity AND is rendered as a badge on every escalated row, so a box that could
     * not match the text the user is looking straight at would be the exact "looks
     * broken" failure the rest of this list exists to avoid.</p>
     *
     * <p>{@code from} and {@code to} are NOT NULLABLE, and that is a fix rather
     * than a limitation. A null {@code Instant} parameter reaches PostgreSQL
     * untyped, and it answers {@code could not determine data type of parameter}
     * — killing the whole query for every caller, including those not filtering by
     * time. The {@code CAST(… AS string)} trick above solves the same problem for
     * text, but there is no equivalent that reads well for a timestamp, so the
     * caller supplies explicit open bounds instead. See
     * {@code AlertQueryService} for the sentinels.</p>
     */
    /*
     * COLLAPSE — one row per fingerprint, the newest episode.
     *
     * A fingerprint accumulates one row per EPISODE: the condition fires, is
     * resolved (often by the staleness sweep), fires again, and each cycle is a new
     * row. That is right for history and wrong for a worklist — the same pod's
     * memory alert appeared three times in a 24h window, which reads as three
     * problems and makes the row count meaningless.
     *
     * Collapsing happens HERE, in the WHERE clause, and not in Java for the reason
     * this class's other javadoc keeps returning to: the table is paged and totalled
     * by the database. Filtering after the page is fetched gives short pages and a
     * total that disagrees with the rows on screen.
     *
     * Newest-by-id rather than by episode because id cannot tie. Episodes are
     * numbered per fingerprint and should be unique, but "should" decided by a
     * subquery that returns two rows puts a duplicate back on screen; id is the
     * primary key, so exactly one row per fingerprint survives, and the newest
     * episode always holds the highest id.
     *
     * NOT EXISTS (… AND b.id > a.id) rather than the equivalent
     * `a.id = (SELECT MAX(b.id) …)`. The two select exactly the same rows — "a is
     * the newest in its group" and "nothing in a's group is newer than a" are the
     * same statement — but the MAX form is a correlated subquery PostgreSQL
     * re-evaluates per candidate row, while NOT EXISTS plans as a single anti-join.
     * Measured on this deployment that was the whole cost of the alerts table:
     * /api/alerts/query took 20.5 s with collapse on and 0.97 s with it off, and
     * the page query and its COUNT both paid it. See ix_alerts_collapse (V13) for
     * the index that makes the anti-join cheap — LOWER(org) is why the existing
     * ix_alerts_fingerprint could not serve this.
     *
     * UPDATE: uncorrelated IN (SELECT MAX(id) … GROUP BY fingerprint). Because the
     * NOT EXISTS sits inside an OR (with `:collapse = FALSE` and the NULL escape),
     * PostgreSQL cannot turn it into an anti-join after all — EXPLAIN ANALYZE shows
     * it running as a correlated SubPlan, one index probe per row: 16,905 probes,
     * ~200 ms, paid by the page query AND its COUNT. The IN form has no reference to
     * `a`, so it is evaluated ONCE as a hashed SubPlan: 69 ms on the same data, and
     * the gap widens as the table grows. Same rows — "a has the highest id in its
     * (org, fingerprint) group" either way; the org is pinned by the outer
     * LOWER(org) predicate, so grouping by fingerprint alone matches the old
     * LOWER(b.org) = LOWER(a.org) correlation. Verified to return identical counts.
     *
     * The `fingerprint IS NULL` escape is load-bearing. In SQL, NULL = NULL is
     * unknown, so a malformed alert that never got a fingerprint would match no
     * subquery row and vanish from the table entirely — the collapse would silently
     * hide exactly the alerts most likely to need looking at.
     */
    @Query("""
            SELECT a FROM AlertEntity a
            WHERE LOWER(a.org) = LOWER(:org)
              AND (:collapse = FALSE
                   OR a.fingerprint IS NULL
                   OR a.id IN (SELECT MAX(b.id) FROM AlertEntity b
                               WHERE LOWER(b.org) = LOWER(:org)
                                 AND b.fingerprint IS NOT NULL
                               GROUP BY b.fingerprint))
              AND (:#{#statuses.isEmpty()} = TRUE OR a.status IN :statuses)
              AND (:#{#severities.isEmpty()} = TRUE OR LOWER(a.severity) IN :severities)
              AND (:streamName  IS NULL OR LOWER(a.streamName)  = LOWER(CAST(:streamName AS string)))
              AND (:serviceName IS NULL OR LOWER(a.serviceName) = LOWER(CAST(:serviceName AS string)))
              AND (:source      IS NULL OR LOWER(a.source)      = LOWER(CAST(:source AS string)))
              AND (:ruleId      IS NULL OR a.alertRuleId = :ruleId)
              AND a.lastFiredAt >= :from
              AND a.lastFiredAt <= :to
              AND (:search      IS NULL OR
                   LOWER(a.alertName)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR
                   LOWER(COALESCE(a.incidentType, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR
                   LOWER(COALESCE(a.serviceName, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR
                   LOWER(COALESCE(a.hostName, ''))    LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR
                   LOWER(COALESCE(a.streamName, ''))  LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR
                   LOWER(COALESCE(a.traceId, ''))     LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR
                   LOWER(COALESCE(a.fingerprint, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    // Each filter is a named JPQL bind parameter, so they cannot be folded into one object.
    @SuppressWarnings("java:S107")
    Page<AlertEntity> search(@Param("org") String org,
                             @Param("collapse") boolean collapse,
                             @Param("statuses") List<AlertState> statuses,
                             @Param("severities") List<String> severities,
                             @Param("streamName") String streamName,
                             @Param("serviceName") String serviceName,
                             @Param("source") String source,
                             @Param("ruleId") Long ruleId,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             @Param("search") String search,
                             Pageable pageable);

    /**
     * Open episodes whose last firing is older than the cutoff.
     *
     * <p>Feeds the staleness sweep. OpenObserve never sends a "recovered"
     * callback, so an alert that simply stops firing has to be closed by
     * inference — this is the query that finds those.</p>
     */
    @Query("""
            SELECT a FROM AlertEntity a
            WHERE a.status NOT IN :terminal
              AND a.lastFiredAt < :cutoff
            ORDER BY a.lastFiredAt ASC
            """)
    List<AlertEntity> findStaleOpenEpisodes(@Param("cutoff") Instant cutoff,
                                            @Param("terminal") List<AlertState> terminal,
                                            Pageable pageable);

    /** {@link #findStaleOpenEpisodes} with the canonical terminal set. */
    default List<AlertEntity> findStaleOpenEpisodes(Instant cutoff, Pageable pageable) {
        return findStaleOpenEpisodes(cutoff, AlertState.terminalStatuses(), pageable);
    }

    /** Status counts in one round trip, for the nav badge and the summary cards. */
    @Query("""
            SELECT new com.trinity.observability.dto.alerts.AlertEntityStatusCount(a.status, a.severity, COUNT(a))
            FROM AlertEntity a
            WHERE a.org = :org
            GROUP BY a.status, a.severity
            """)
    List<AlertEntityStatusCount> countByStatusAndSeverity(@Param("org") String org);

    /**
     * Same counts, restricted to a time window.
     *
     * <p>Separate from the unbounded version rather than taking nullable bounds:
     * the summary cards must agree with the table they sit above, and the table is
     * always windowed. Sharing one query with null bounds would make it easy for a
     * caller to get the two out of step without noticing.</p>
     *
     * <p>Bounds are NOT NULLABLE, for the same reason as {@link #search}: a null
     * {@code Instant} parameter reaches PostgreSQL untyped and it answers
     * {@code could not determine data type of parameter}, failing the query for
     * every caller. The caller passes explicit open bounds for "all time".</p>
     *
     * <p>The collapse subquery is uncorrelated so it runs once as a hashed SubPlan
     * instead of once per row — see the COLLAPSE note on {@link #search}.</p>
     */
    @Query("""
            SELECT new com.trinity.observability.dto.alerts.AlertEntityStatusCount(a.status, a.severity, COUNT(a))
            FROM AlertEntity a
            WHERE a.org = :org
              AND (:collapse = FALSE
                   OR a.fingerprint IS NULL
                   OR a.id IN (SELECT MAX(b.id) FROM AlertEntity b
                               WHERE b.org = :org AND b.fingerprint IS NOT NULL
                               GROUP BY b.fingerprint))
              AND a.lastFiredAt >= :from
              AND a.lastFiredAt <= :to
            GROUP BY a.status, a.severity
            """)
    List<AlertEntityStatusCount> countByStatusAndSeverityInWindow(@Param("org") String org,
                                                                  @Param("collapse") boolean collapse,
                                                                  @Param("from") Instant from,
                                                                  @Param("to") Instant to);

    List<AlertEntity> findByOrgAndFingerprintOrderByEpisodeDesc(String org, String fingerprint);

    /** Distinct values for a filter dropdown. Nulls are excluded by the caller's column predicate. */
    @Query("""
            SELECT DISTINCT a.serviceName FROM AlertEntity a
            WHERE a.org = :org AND a.serviceName IS NOT NULL
            ORDER BY a.serviceName
            """)
    List<String> findDistinctServiceNames(@Param("org") String org);

    @Query("""
            SELECT DISTINCT a.streamName FROM AlertEntity a
            WHERE a.org = :org AND a.streamName IS NOT NULL
            ORDER BY a.streamName
            """)
    List<String> findDistinctStreamNames(@Param("org") String org);

    @Query("""
            SELECT DISTINCT a.source FROM AlertEntity a
            WHERE a.org = :org AND a.source IS NOT NULL
            ORDER BY a.source
            """)
    List<String> findDistinctSources(@Param("org") String org);

    /** Alerts by id, restricted to one org — bulk operations must not cross tenants. */
    @Query("SELECT a FROM AlertEntity a WHERE a.org = :org AND a.id IN :ids")
    List<AlertEntity> findAllByOrgAndIdIn(@Param("org") String org, @Param("ids") List<Long> ids);
}
