package com.trinity.observability.util;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves a human-readable DB statement from a flat OpenObserve span hit.
 *
 * <p>OTel-native counterpart to {@link DbStatementResolver}: drops the recursive
 * map-walk (flat OO hits don't need it) and reads the canonical OTel columns
 * directly. Order of preference:
 * <ol>
 *   <li><code>db_statement</code> — the verbatim query/command if available</li>
 *   <li><code>db_operation</code> — the high-level operation name (e.g. "find")</li>
 *   <li><code>operation_name</code> — only if it looks like a query
 *       (begins with SELECT/INSERT/MATCH/...)</li>
 * </ol>
 */
public final class OoDbStatementResolver {

    private static final Pattern QUERY_LIKE = Pattern.compile(
        "^\\s*(SELECT|INSERT|UPDATE|DELETE|MATCH|MERGE|CALL|FIND|GET|SET|HSET|HGET|EXEC)\\b",
        Pattern.CASE_INSENSITIVE);

    private OoDbStatementResolver() {}

    /** Cyclomatic complexity: 4. */
    public static String find(Map<String, Object> hit) {
        if (hit == null) return null;

        String stmt = stringValue(hit, "db_statement");
        if (stmt != null) return stmt;

        String op = stringValue(hit, "db_operation");
        if (op != null) return op;

        String opName = stringValue(hit, "operation_name");
        return looksLikeQuery(opName) ? opName : null;
    }

    /** Cyclomatic complexity: 4 (boolean short-circuit). */
    private static String stringValue(Map<String, Object> hit, String key) {
        Object v = hit.get(key);
        if (!(v instanceof String)) return null;
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    /** Cyclomatic complexity: 2. */
    private static boolean looksLikeQuery(String s) {
        return s != null && QUERY_LIKE.matcher(s).find();
    }
}
