package com.trinity.observability.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Severity ordering for hierarchical log filtering.
 *
 * Issue #14: the original code did exact-match severity, so a user picking
 * {@code WARN} only saw warnings — errors and fatals were filtered out, which
 * is the opposite of what most operators expect ("show me everything that's
 * worth my attention"). This helper resolves a level into the inclusive
 * upward set: {@code WARN -> [WARN, ERROR, FATAL]}.
 *
 * The standard OTel severity order is preserved (TRACE &lt; DEBUG &lt; INFO &lt;
 * WARN &lt; ERROR &lt; FATAL); the rank values are arbitrary but monotonic.
 */
public final class SeverityRank {

    private SeverityRank() {}

    private static final Map<String, Integer> RANK;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("TRACE", 0);
        m.put("DEBUG", 1);
        m.put("INFO",  2);
        m.put("WARN",  3);
        m.put("ERROR", 4);
        m.put("FATAL", 5);
        RANK = Collections.unmodifiableMap(m);
    }

    /** Returns the canonical severity list ordered low→high. */
    public static List<String> all() {
        return new java.util.ArrayList<>(RANK.keySet());
    }

    /**
     * Returns severities at or above {@code level} (inclusive). Unknown
     * inputs fall back to a single-element list containing the input —
     * preserves exact-match behavior so unknown severities still filter
     * to themselves rather than returning everything.
     */
    public static List<String> atLeast(String level) {
        if (level == null) return Collections.emptyList();
        String upper = level.trim().toUpperCase();
        Integer min = RANK.get(upper);
        if (min == null) return Collections.singletonList(upper);
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : RANK.entrySet()) {
            if (e.getValue() >= min) out.add(e.getKey());
        }
        return out;
    }

    /** Equivalent to a single-element list — used by EXACT mode callers. */
    public static List<String> exact(String level) {
        return level == null ? Collections.emptyList()
                             : Arrays.asList(level.trim().toUpperCase());
    }
}
