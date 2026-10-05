package com.trinity.observability.util;

import java.util.Map;
import java.util.Set;

/**
 * Single-responsibility: finds the raw DB statement from all known locations
 * in an Elasticsearch APM source document.
 *
 * Used by both TraceClassifier and TraceInterpreter.
 */
public class DbStatementResolver {

    /** Placeholder statements, compared exactly (after trimming). */
    private static final Set<String> PLACEHOLDERS = Set.of("?", "{}");

    /** Meaningless or overly generic statements, compared lower-cased (after trimming). */
    private static final Set<String> TRIVIAL_WORDS = Set.of(
            "undefined", "null", "query", "statement", "operation", "find", "ping");

    private DbStatementResolver() {}

    /**
     * Returns the first non-trivial DB statement found in the source,
     * checking all known APM field locations in priority order.
     */
    public static String find(Map<String, Object> source) {
        // Priority 1: Direct statements/queries
        String stmt = MappingUtil.getStringValue(source,
                "span.db.statement",
                "db.statement",
                "labels.db_statement",
                "labels.db_query",
                "db.query.text",
                "neo4j.query",
                "neo4j.statement",
                "labels.neo4j_query",
                "labels.query");

        if (isNonTrivial(stmt)) return stmt;

        // Priority 2: Semantic operations (Mongo/Redis command labels)
        String op = MappingUtil.getStringValue(source,
                "db.operation",
                "labels.db_operation",
                "db.redis.command",
                "labels.db_redis_command",
                "span.db.operation");

        if (isNonTrivial(op)) return op;

        // Priority 3: Deep-scan fallback
        return deepScan(source);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private static boolean isNonTrivial(String s) {
        if (s == null || s.isEmpty()) return false;
        
        String trimmed = s.trim();
        String lower = trimmed.toLowerCase();

        // Reject placeholders, meaningless content and overly generic keywords
        // that lack context on their own
        return !PLACEHOLDERS.contains(trimmed) && !TRIVIAL_WORDS.contains(lower);
    }

    @SuppressWarnings("unchecked")
    private static String deepScan(Object obj) {
        if (obj instanceof Map) {
            return scanMap((Map<?, ?>) obj);
        } else if (obj instanceof java.util.List) {
            return scanList((java.util.List<?>) obj);
        }
        return null;
    }

    private static String scanMap(Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String found = scanEntry(entry);
            if (found != null) return found;
        }
        return null;
    }

    private static String scanList(java.util.List<?> list) {
        for (Object item : list) {
            String found = deepScan(item);
            if (found != null) return found;
        }
        return null;
    }

    /** A statement from one map entry: its own string value, or one nested beneath it. */
    private static String scanEntry(Map.Entry<?, ?> entry) {
        String key = String.valueOf(entry.getKey()).toLowerCase();
        Object val = entry.getValue();
        if (val instanceof String sVal) {
            return isStatementKey(key) && isNonTrivial(sVal) ? sVal : null;
        } else if (val instanceof Map || val instanceof java.util.List) {
            return deepScan(val);
        }
        return null;
    }

    private static boolean isStatementKey(String key) {
        return key.contains("query") || key.contains("statement") || key.contains("operation");
    }
}
