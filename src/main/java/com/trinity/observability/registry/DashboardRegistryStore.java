package com.trinity.observability.registry;

import java.util.List;
import java.util.Optional;

/**
 * Where dashboard registrations are kept.
 *
 * <p>Two implementations, chosen by {@code grafana.registry.store}: PostgreSQL
 * when this deployment has the alert/catalog datasource, and a JSON file when it
 * does not. The interface exists so the Dashboard Catalog service never learns
 * which one it got — and so the feature is usable on a deployment with no database
 * at all, which is the difference between "works in any environment" and "works in
 * ours".</p>
 *
 * <p>Every method takes a {@code scopeKey}. It is the tenancy boundary — see
 * {@link DashboardRegistration#scopeKey()} — and passing it explicitly rather than
 * reading a thread-local inside the store keeps the stores testable and keeps the
 * decision about what the current scope is in exactly one place.</p>
 */
public interface DashboardRegistryStore {

    /** Every registration in one scope, in no guaranteed order. */
    List<DashboardRegistration> list(String scopeKey);

    Optional<DashboardRegistration> find(String scopeKey, String uid);

    /** Insert or replace, keyed on (scopeKey, uid). Returns what was stored. */
    DashboardRegistration save(DashboardRegistration registration);

    /** @return true when a row was actually removed. */
    boolean delete(String scopeKey, String uid);

    /** One line for the startup log and the health endpoint, e.g. "PostgreSQL". */
    String describe();
}
