package com.trinity.observability.service;

import java.util.List;

/**
 * The Product Catalog's effect on the rest of the application: a SQL predicate
 * that keeps disabled resources out of every Logs, Traces and Metrics query.
 *
 * <h2>Why a predicate rather than post-filtering</h2>
 *
 * <p>Filtering rows after they arrive would be wrong, not merely slow. Every one
 * of these screens paginates and aggregates INSIDE OpenObserve — a page of 50
 * logs, a {@code GROUP BY trace_id}, a percentile over durations. Dropping rows
 * afterwards would give short pages, wrong counts and wrong percentiles. So the
 * exclusion goes into the WHERE clause, where the engine can apply it before it
 * counts anything.</p>
 *
 * <h2>Fail open, always</h2>
 *
 * <p>Every implementation must answer {@code ""} rather than throw. This sits in
 * the path of the three screens the product exists to provide; a registry that
 * cannot be read is a reason to filter nothing, never a reason to show the
 * operator an error where their logs used to be.</p>
 */
public interface CatalogTelemetryFilter {

    /**
     * The predicate to AND into a query over one signal.
     *
     * <p>Returned WITHOUT a leading {@code AND} and already parenthesised, so it
     * drops into either calling convention: {@code sql.append("AND ").append(p)}
     * for the builders that start from {@code WHERE 1=1}, or straight into a
     * predicate list for the ones that join with {@code AND}.</p>
     *
     * @param signal {@code logs}, {@code traces} or {@code metrics}
     * @return a predicate, or {@code ""} when nothing needs filtering — which is
     *         the case on an empty registry, and is what makes adopting this
     *         module a no-op until somebody registers something
     */
    String sqlPredicate(String signal);

    /**
     * Whether a single identifier value may be shown.
     *
     * <p>For the endpoints that return identifiers rather than telemetry — the
     * logs service-name dropdown, the uptime inventory. A disabled resource must
     * not appear in a picker either, or the operator can select something that
     * then returns nothing.</p>
     */
    boolean permits(String signal, String column, String value);

    /** {@link #permits} over a list, preserving order. Null-safe. */
    List<String> retainPermitted(String signal, String column, List<String> values);

    /**
     * A filter that permits everything.
     *
     * <p>Used by the test-only constructors on the telemetry services, so their
     * existing unit tests keep exercising exactly the SQL they always did, and by
     * any future caller that must not be catalog-scoped.</p>
     */
    static CatalogTelemetryFilter permitAll() {
        return PermitAll.INSTANCE;
    }

    /** Implementation of {@link #permitAll()}. */
    final class PermitAll implements CatalogTelemetryFilter {

        private static final PermitAll INSTANCE = new PermitAll();

        private PermitAll() {
        }

        @Override
        public String sqlPredicate(String signal) {
            return "";
        }

        @Override
        public boolean permits(String signal, String column, String value) {
            return true;
        }

        @Override
        public List<String> retainPermitted(String signal, String column, List<String> values) {
            return values == null ? List.of() : values;
        }
    }
}
