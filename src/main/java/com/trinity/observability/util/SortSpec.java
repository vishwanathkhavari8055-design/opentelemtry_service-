package com.trinity.observability.util;

import java.util.Map;

/**
 * Parses a Spring-style {@code "field,direction"} sort param against a
 * whitelist. Returning enum-shaped output (column + direction) keeps the SQL
 * builder from interpolating user input — only the resolved values from
 * {@link #LOGS_FIELD_MAP} ever reach the query.
 *
 * Why this exists: {@code LogRequestDTO.sort} is bound from the query string,
 * so without a whitelist we'd be one careless concat away from
 * {@code ORDER BY x; DROP TABLE …}. The HTTP/SQL bridge OpenObserve uses has
 * no PreparedStatement layer, so this whitelist *is* the security boundary.
 */
public final class SortSpec {

    public enum Direction {
        ASC("ASC"), DESC("DESC");

        private final String sql;
        Direction(String sql) { this.sql = sql; }
        public String sql() { return sql; }
    }

    private static final String COL_TIMESTAMP = "_timestamp";
    private static final String COL_SERVICE_NAME = "service_name";

    /** UI field name -> OpenObserve column. */
    private static final Map<String, String> LOGS_FIELD_MAP = Map.of(
        "timestamp",   COL_TIMESTAMP,
        COL_TIMESTAMP, COL_TIMESTAMP,
        "severity",    "severity",
        "serviceName", COL_SERVICE_NAME,
        COL_SERVICE_NAME, COL_SERVICE_NAME
    );

    private final String column;
    private final Direction direction;

    private SortSpec(String column, Direction direction) {
        this.column = column;
        this.direction = direction;
    }

    public String column() { return column; }
    public Direction direction() { return direction; }

    /** Default: latest logs first. */
    public static SortSpec defaultLogs() {
        return new SortSpec(COL_TIMESTAMP, Direction.DESC);
    }

    /**
     * Parse {@code "field,dir"}. Unknown fields/directions fall back to the
     * default — we don't throw, because malformed query params shouldn't 500
     * the request; they should produce the well-defined default ordering.
     */
    public static SortSpec parseLogs(String raw) {
        if (raw == null || raw.isBlank()) return defaultLogs();
        String[] parts = raw.trim().split(",", 2);
        String field = parts[0].trim();
        String column = LOGS_FIELD_MAP.get(field);
        if (column == null) return defaultLogs();
        Direction dir = Direction.DESC;
        if (parts.length == 2 && "ASC".equalsIgnoreCase(parts[1].trim())) {
            dir = Direction.ASC;
        }
        return new SortSpec(column, dir);
    }

    /** Build the {@code ORDER BY x DIR} clause. Both inputs are whitelisted. */
    public String toSqlClause() {
        return "ORDER BY " + column + " " + direction.sql();
    }
}
