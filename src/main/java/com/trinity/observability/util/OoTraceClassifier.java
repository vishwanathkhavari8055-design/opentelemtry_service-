package com.trinity.observability.util;

import java.util.Map;

/**
 * Classifies OpenTelemetry-shaped span hits from OpenObserve.
 *
 * <p>OTel-native counterpart to {@link TraceClassifier}: branches on flat OO columns
 * (<code>span_kind</code>, <code>db_system</code>, <code>http_request_method</code>,
 * <code>operation_name</code>) instead of the Elastic APM model
 * ({@code processor.event}, {@code span.type}, {@code labels.*}). Reuses the
 * {@link TraceClassifier.SpanType} enum so downstream code stays unchanged.</p>
 *
 * <p>Per the APM→OTel translation table in MIGRATION-PLAN.md.</p>
 */
public final class OoTraceClassifier {

    private static final String GENERIC_SQL = "sql";

    /**
     * Lower-cased OTel {@code db_system} values that {@link #resolveDbTechnology} renames.
     * Any other value — including mongodb, redis, neo4j, mssql and oracle — is returned as is.
     */
    private static final Map<String, String> DB_SYSTEM_ALIASES = Map.of(
            "sqlserver", "mssql",
            "oracledb", "oracle",
            "mysql", GENERIC_SQL,
            "postgresql", GENERIC_SQL,
            "mariadb", GENERIC_SQL,
            "h2", GENERIC_SQL,
            "sqlite", GENERIC_SQL,
            "db2", GENERIC_SQL);

    private OoTraceClassifier() {}

    /**
     * Classify a flat OpenObserve hit into a span type.
     * Cyclomatic complexity: 6.
     */
    public static TraceClassifier.SpanType classify(Map<String, Object> hit) {
        if (hit == null) return TraceClassifier.SpanType.INTERNAL;

        if (hit.get("db_system") != null) {
            return TraceClassifier.SpanType.DATABASE;
        }
        if (isGraphQl((String) hit.get("operation_name"))) {
            return TraceClassifier.SpanType.GRAPHQL;
        }
        String spanKind = (String) hit.get("span_kind");
        if ("SPAN_KIND_SERVER".equals(spanKind)) {
            return TraceClassifier.SpanType.HTTP_INCOMING;
        }
        if ("SPAN_KIND_CLIENT".equals(spanKind) && hit.get("http_request_method") != null) {
            return TraceClassifier.SpanType.HTTP_OUTGOING;
        }
        return TraceClassifier.SpanType.INTERNAL;
    }

    /**
     * Resolve the OTel <code>db_system</code> value to a normalised technology label.
     * Returns one of: {@code mongodb, redis, neo4j, mssql, oracle, sql} or the
     * lower-cased original value if unknown. Returns {@code null} when not a DB span.
     *
     * <p>Cyclomatic complexity: 3 (alias table lookup).</p>
     */
    public static String resolveDbTechnology(Map<String, Object> hit) {
        if (hit == null) return null;
        Object raw = hit.get("db_system");
        if (!(raw instanceof String s)) return null;

        String dbSystem = s.toLowerCase();
        return DB_SYSTEM_ALIASES.getOrDefault(dbSystem, dbSystem);
    }

    /** Cyclomatic complexity: 3. */
    private static boolean isGraphQl(String opName) {
        if (opName == null) return false;
        String lower = opName.toLowerCase();
        return lower.startsWith("graphql") || lower.contains("/graphql");
    }
}
