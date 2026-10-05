package com.trinity.observability.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class MappingUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    private MappingUtil() {
    }

    public static String getStringValue(Map<String, Object> map, String... paths) {
        for (String path : paths) {
            Object val = getValue(map, path);
            if (val != null) {
                if (val instanceof String str)
                    return str;
                try {
                    return mapper.writeValueAsString(val);
                } catch (Exception e) {
                    return String.valueOf(val);
                }
            }
        }
        return null;
    }

    public static Long getLongValue(Map<String, Object> map, String... paths) {
        for (String path : paths) {
            Object val = getValue(map, path);
            if (val instanceof Number num)
                return num.longValue();
            if (val instanceof String str) {
                try {
                    return Long.parseLong(str);
                } catch (Exception ignored) {
                    // Not a parsable number; try next path
                }
            }
        }
        return null;
    }

    public static Double getDoubleValue(Map<String, Object> map, String... paths) {
        for (String path : paths) {
            Object val = getValue(map, path);
            if (val instanceof Number num)
                return num.doubleValue();
            if (val instanceof String str) {
                try {
                    return Double.parseDouble(str);
                } catch (Exception ignored) {
                    // Not a parsable number; try next path
                }
            }
        }
        return null;
    }

    /**
     * Greedy path finder that handles mixed flat and nested Maps.
     * It iteratively checks if any key in the current Map is a prefix of the
     * remaining path.
     */
    private static Object getValue(Map<?, ?> map, String path) {
        if (map == null || path == null)
            return null;

        Object current = map;
        String remainingPath = path;

        while (current instanceof Map<?, ?> currentMap && !remainingPath.isEmpty()) {
            // 1. Try absolute literal match for the remaining path
            if (currentMap.containsKey(remainingPath)) {
                return currentMap.get(remainingPath);
            }

            // 2. Greedy search, then 3. first-dot split (see resolveKey)
            String foundKey = resolveKey(currentMap, remainingPath);
            if (foundKey == null) {
                return null;
            }

            current = currentMap.get(foundKey);
            remainingPath = remainingPath.substring(foundKey.length() + 1);
        }

        return (remainingPath.isEmpty()) ? current : null;
    }

    /**
     * The key to descend through: first any key that is a dotted prefix of the
     * remaining path (greedy search), and as a last resort the segment before the
     * first dot. Null when neither exists.
     */
    private static String resolveKey(Map<?, ?> currentMap, String remainingPath) {
        String foundKey = findPrefixKey(currentMap, remainingPath);
        return (foundKey != null) ? foundKey : findFirstSegmentKey(currentMap, remainingPath);
    }

    /** Returns the first key (as a String) that is a dotted prefix of {@code remainingPath}, or null. */
    private static String findPrefixKey(Map<?, ?> currentMap, String remainingPath) {
        for (Object keyObj : currentMap.keySet()) {
            String key = String.valueOf(keyObj);
            if (remainingPath.startsWith(key + ".")) {
                return key;
            }
        }
        return null;
    }

    /** Returns the segment before the first dot if it is a key of {@code currentMap}, or null. */
    private static String findFirstSegmentKey(Map<?, ?> currentMap, String remainingPath) {
        int dotIdx = remainingPath.indexOf('.');
        if (dotIdx > 0) {
            String part = remainingPath.substring(0, dotIdx);
            if (currentMap.containsKey(part)) {
                return part;
            }
        }
        return null;
    }
}
