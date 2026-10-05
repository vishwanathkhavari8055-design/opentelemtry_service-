package com.trinity.observability.entity;

/**
 * Where a rule stands relative to the evaluation engine.
 *
 * <p>Kept as explicit state rather than being inferred from
 * {@code openobserve_id IS NULL}, because "never pushed" and "pushed and then
 * rejected" need different handling and look identical otherwise.</p>
 */
public enum SyncStatus {

    /** Stored locally, not yet accepted by OpenObserve. Picked up by the next sync. */
    PENDING,

    /** OpenObserve holds a current copy. */
    SYNCED,

    /**
     * OpenObserve refused it. The rule still exists here and is still the
     * source of truth — it simply is not being evaluated, which is a state an
     * operator must be able to see rather than guess at. {@code sync_error}
     * carries the reason.
     */
    FAILED,

    /**
     * Deleted here, still present in OpenObserve.
     *
     * <p>Reached when the local delete succeeded but the remote one did not.
     * Without this the rule would keep firing forever with nothing in this
     * database explaining why — deletion has to survive OpenObserve being
     * unreachable at the moment the button was pressed.</p>
     */
    DELETE_PENDING
}
