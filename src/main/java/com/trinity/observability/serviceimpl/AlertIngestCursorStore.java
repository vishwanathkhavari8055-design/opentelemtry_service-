package com.trinity.observability.serviceimpl;


import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.trinity.observability.entity.AlertIngestCursorEntity;
import com.trinity.observability.repository.AlertIngestCursorRepository;
import com.trinity.observability.util.TimeFormatter;

/**
 * The alert poller's watermark, in its own bean.
 *
 * <p>Its own bean SPECIFICALLY so the {@code @Transactional} annotations take
 * effect. Spring's transaction support is proxy-based, so a method annotated
 * {@code @Transactional} that another method of the SAME class calls directly runs
 * with no transaction at all — the call never leaves the object, so it never passes
 * through the proxy. Keeping these three methods here means the poller's calls are
 * real inter-bean calls and each one genuinely gets {@code REQUIRES_NEW}.</p>
 *
 * <p>{@code REQUIRES_NEW} matters because the cursor must commit independently of
 * whatever the poll is doing. A watermark written in the same transaction as a
 * failing batch would roll back with it, and the poller would re-read the same
 * range on every poll forever.</p>
 */
@Component
public class AlertIngestCursorStore {

    private static final Logger log = LogManager.getLogger(AlertIngestCursorStore.class);

    /** Upper bound on a stored error message, so one huge upstream body cannot bloat the row. */
    private static final int MAX_ERROR_CHARS = 2_000;

    private final AlertIngestCursorRepository cursors;

    public AlertIngestCursorStore(AlertIngestCursorRepository cursors) {
        this.cursors = cursors;
    }

    /**
     * The cursor for this (org, stream), created at {@code backfillFromMicros} on
     * first use.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AlertIngestCursorEntity loadOrCreate(String org, String stream, long backfillFromMicros) {
        Optional<AlertIngestCursorEntity> existing = cursors.findByOrgAndStreamName(org, stream);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            AlertIngestCursorEntity created = cursors.save(
                    AlertIngestCursorEntity.startingAt(org, stream, backfillFromMicros));
            if (log.isInfoEnabled()) {
                log.info("Alert ingest cursor created for org '{}' stream '{}', starting at {}",
                        org, stream, TimeFormatter.microsToIso(backfillFromMicros));
            }
            return created;
        } catch (DataIntegrityViolationException race) {
            // Another instance created it first; its watermark is as good as ours.
            return cursors.findByOrgAndStreamName(org, stream).orElseThrow(() -> race);
        }
    }

    /**
     * Advance the watermark after a successful poll.
     *
     * <p>Re-reads the row inside this transaction rather than saving the caller's
     * detached copy: the poll may have taken a while, and blindly writing a stale
     * copy would clobber a concurrent update and trip optimistic locking.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long cursorId, long newestMicros, int stored) {
        cursors.findById(cursorId).ifPresentOrElse(cursor -> {
            cursor.recordSuccess(newestMicros, stored);
            cursors.save(cursor);
        }, () -> log.warn("Alert ingest cursor {} disappeared before its watermark could be "
                + "advanced", cursorId));
    }

    /**
     * Record a failed poll. The watermark is deliberately left where it was.
     *
     * @return the new consecutive-failure count, so the caller can decide how
     *         loudly to log without re-reading the row. The caller's own copy of
     *         the entity is stale by now — this method committed in a separate
     *         transaction — and reading the count off it would under-report by one
     *         every time, so the escalation threshold would never quite be reached.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long cursorId, String error) {
        Optional<AlertIngestCursorEntity> found = cursors.findById(cursorId);
        if (found.isEmpty()) {
            return 0;
        }
        AlertIngestCursorEntity cursor = found.get();
        cursor.recordFailure(truncate(error));
        return cursors.save(cursor).getConsecutiveFailures();
    }

    @Transactional(readOnly = true)
    public List<AlertIngestCursorEntity> findAll() {
        return cursors.findAllByOrderByOrgAsc();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeObsoleteStreams(String validStreamName) {
        try {
            cursors.deleteObsoleteStreams(validStreamName);
        } catch (Exception e) {
            log.warn("Could not purge obsolete cursor stream records: {}", e.getMessage());
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }
}
