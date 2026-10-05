package com.trinity.observability.service;

import java.util.UUID;

import com.trinity.observability.dto.alerts.AlertRuleRequestDTO;
import com.trinity.observability.dto.alerts.AlertRuleResponseDTO;

/**
 * Alert rules, owned by PostgreSQL.
 *
 * <h2>Where the truth lives</h2>
 *
 * <p>A rule is created here first and only then pushed to OpenObserve, which
 * acts purely as the evaluation engine. The OpenObserve copy is derived and
 * disposable: {@link #syncAll} rebuilds every one of them from PostgreSQL, so
 * losing the OpenObserve side costs an evaluation gap and nothing more.</p>
 *
 * <p>This is the opposite arrangement to {@link AlertsService}, which is a
 * pass-through to OpenObserve's own store. Both exist on purpose —
 * {@code AlertsService} still serves rules authored directly in OpenObserve's
 * UI, which this service does not own and must not silently adopt.</p>
 *
 * <h2>Failure model</h2>
 *
 * <p>The local write and the remote push are deliberately not one atomic unit,
 * because they cannot be: PostgreSQL and OpenObserve share no transaction. So
 * the local write commits first and the push is recorded as a state
 * ({@code syncStatus}) rather than being required to succeed. A rule that
 * OpenObserve rejected still exists, is still visible, and is retried by the
 * next sync — which is strictly better than losing the definition because the
 * evaluation engine happened to be down when someone pressed Save.</p>
 */
public interface AlertRuleService {

    /**
     * Store a rule, then push it to OpenObserve.
     *
     * <p>Returns successfully even when the push fails; inspect
     * {@code rule.syncStatus} and {@code rule.syncError} on the result. The
     * definition is safe either way.</p>
     *
     * @throws com.trinity.observability.exception.ResourceConflictException
     *         if a live rule of the same name already exists in this org
     */
    AlertRuleResponseDTO.WriteResult create(AlertRuleRequestDTO.SaveRule request);

    /** Replace a rule, then push the new definition to OpenObserve. */
    AlertRuleResponseDTO.WriteResult update(UUID uuid, AlertRuleRequestDTO.SaveRule request);

    /**
     * Soft-delete a rule here and remove it from OpenObserve.
     *
     * <p>If the remote delete fails the rule is marked
     * {@link com.trinity.observability.entity.SyncStatus#DELETE_PENDING} and
     * retried by the next sync. It must not be forgotten: a rule deleted here
     * but still live there would keep paging people with nothing in this
     * database to explain where the alert is coming from.</p>
     */
    AlertRuleResponseDTO.WriteResult delete(UUID uuid, String actor);

    /** Enable or disable without touching the rest of the definition. */
    AlertRuleResponseDTO.WriteResult setEnabled(UUID uuid, boolean enabled, String actor);

    /**
     * Change WHEN a rule acts, without touching what it says.
     *
     * <p>The counterpart to {@link #setEnabled}: that one answers "on or off",
     * this one answers "on, off until I say, or on at this time". It exists
     * separately from {@link #update} because postponing a rule should not
     * require the caller to resend the whole definition — a client that echoes
     * back a stale copy to change one date is how edits get lost.</p>
     *
     * @param activation NOW, LATER or SCHEDULED; case-insensitive
     * @param activateAt ISO-8601 WITH an offset, required for SCHEDULED and
     *                   ignored otherwise
     */
    AlertRuleResponseDTO.WriteResult setActivation(UUID uuid, String activation,
                                                   String activateAt, String actor);

    AlertRuleResponseDTO.Rule get(UUID uuid);

    AlertRuleResponseDTO.Page<AlertRuleResponseDTO.Rule> list(AlertRuleRequestDTO.ListFilter filter);

    /**
     * Reconcile every rule in PostgreSQL into OpenObserve.
     *
     * <p>This is the disaster-recovery path named in the architecture: point it
     * at an OpenObserve that has lost its alerts and it recreates them all.
     * It works by reading what OpenObserve currently has, then creating what is
     * missing, updating what exists, and deleting what should no longer be
     * there — so it is idempotent and safe to run repeatedly, including against
     * a fully populated instance.</p>
     */
    AlertRuleResponseDTO.SyncResult syncAll();

    /** Push one rule, for retrying a single failure without a full reconcile. */
    AlertRuleResponseDTO.WriteResult syncOne(UUID uuid);
}
