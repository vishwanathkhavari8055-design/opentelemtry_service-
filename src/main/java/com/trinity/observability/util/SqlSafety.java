package com.trinity.observability.util;

import java.util.Map;
import java.util.regex.Pattern;

public final class SqlSafety {

    private static final Pattern IDENTIFIER_RE = Pattern.compile("^[A-Za-z0-9_.\\-]+$");
    private static final String FIELD_SERVICE_NAME = "serviceName";
    private static final String COL_SERVICE_NAME = "service_name";

    private static final Map<String, String> ALLOWED_LOG_FILTERS = Map.of(
        FIELD_SERVICE_NAME, COL_SERVICE_NAME,
        "severity",     "severity",
        "hostName",     "host_name",
        "osType",       "os_type",
        "scopeName",    "instrumentation_library_name",
        "traceId",      "trace_id",
        "spanId",       "span_id"
    );

    private static final Map<String, String> ALLOWED_TRACE_FILTERS = Map.of(
        "traceId",          "trace_id",
        "spanId",           "span_id",
        FIELD_SERVICE_NAME, COL_SERVICE_NAME,
        "operationName",    "operation_name",
        "spanKind",         "span_kind",
        "dbSystem",         "db_system",
        "httpRequestMethod","http_request_method"
    );

    private static final Map<String, String> ALLOWED_METRIC_FILTERS = Map.of(
        FIELD_SERVICE_NAME, COL_SERVICE_NAME,
        "hostName",     "host_name"
    );

    private SqlSafety() {}

    public static String resolveLogColumn(String dtoField) {
        return resolveOrThrow(ALLOWED_LOG_FILTERS, dtoField, "log");
    }

    public static String resolveTraceColumn(String dtoField) {
        return resolveOrThrow(ALLOWED_TRACE_FILTERS, dtoField, "trace");
    }

    public static String resolveMetricColumn(String dtoField) {
        return resolveOrThrow(ALLOWED_METRIC_FILTERS, dtoField, "metric");
    }

    private static String resolveOrThrow(Map<String, String> table, String field, String kind) {
        String col = table.get(field);
        if (col == null) {
            throw new IllegalArgumentException(
                "Unknown " + kind + " filter field: '" + field + "'. Allowed: " + table.keySet());
        }
        return col;
    }

    public static String escapeStringLiteral(String value) {
        if (value == null) {
            throw new IllegalArgumentException("escapeStringLiteral: value must not be null");
        }
        return value.replace("'", "''");
    }

    public static String validateIdentifier(String value) {
        if (value == null || !IDENTIFIER_RE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Invalid identifier: '" + value + "' (must match " + IDENTIFIER_RE.pattern() + ")");
        }
        if (value.contains("--")) {
            throw new IllegalArgumentException(
                "Invalid identifier: '" + value + "' (contains SQL comment marker '--')");
        }
        return value;
    }

    public static String escapeLikePattern(String value) {
        if (value == null) {
            throw new IllegalArgumentException("escapeLikePattern: value must not be null");
        }
        return value.replace("\\", "\\\\")
                    .replace("%",  "\\%")
                    .replace("_",  "\\_")
                    .replace("'",  "''");
    }
}
