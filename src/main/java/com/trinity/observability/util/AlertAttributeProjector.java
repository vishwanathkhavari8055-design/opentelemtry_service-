package com.trinity.observability.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Projects an alert's latest payload into two generic maps: the numbers, and
 * everything else.
 *
 * <h2>Why generic and not columns</h2>
 *
 * <p>The alternative is a field per metric — {@code cpuValue}, {@code ramValue},
 * {@code diskValue} — and that design is wrong on the day it ships, because the next
 * metric somebody alerts on needs a schema change, a DTO change, a mapper change and a
 * UI change to become visible. There is no list of metrics here and no place to put
 * one: whatever the notification template emitted is what appears, so a rule on a
 * metric nobody has thought of yet renders correctly with no code change at all.</p>
 *
 * <h2>Why the split</h2>
 *
 * <p>{@code currentValues} holds what parses as a number and {@code labels} holds what
 * does not, because the two are read differently: a value is compared against a
 * threshold and trended, a label is matched and grouped. Handing the UI one
 * undifferentiated map would make it guess, and it would guess by inspecting the key
 * name — which is exactly the hardcoding this class exists to avoid.</p>
 *
 * <h2>Read-side only</h2>
 *
 * <p>Nothing here writes and nothing here can fail a request, matching
 * {@link AlertMeasurementReader}, which reads the same {@code raw_payload} for the
 * measurement labels. A payload that will not parse yields empty maps, which render as
 * an empty panel — the alert itself is intact and its {@code parse_error} already
 * records that the template was odd.</p>
 */
public final class AlertAttributeProjector {

    private AlertAttributeProjector() {
    }

    /** Longest value retained. A template that loops over entities emits kilobytes. */
    private static final int MAX_VALUE_CHARS = 512;

    /** Longest key retained, matching the dimension column width that preceded this. */
    private static final int MAX_KEY_CHARS = 128;

    /** The two maps a payload projects onto. */
    public record Attributes(Map<String, Double> currentValues, Map<String, String> labels) {

        public static Attributes empty() {
            return new Attributes(Map.of(), Map.of());
        }

        public boolean isEmpty() {
            return currentValues.isEmpty() && labels.isEmpty();
        }
    }

    /**
     * Split a parsed payload into values and labels.
     *
     * @param payload parsed row, from {@link AlertMeasurementReader#parse}; null yields
     *        {@link Attributes#empty()}
     * @param exclude keys to omit — the long rendered blobs the table already shows in
     *        their own columns, so the panel is not a second copy of the description
     * @param maxAttributes cap across BOTH maps combined; <= 0 means the default
     * @return never null
     */
    public static Attributes project(JsonNode payload, Collection<String> exclude,
            int maxAttributes) {
        if (payload == null || !payload.isObject()) {
            return Attributes.empty();
        }
        int cap = maxAttributes > 0 ? maxAttributes : 60;
        Set<String> skip = normalizedSkipSet(exclude);

        // Sorted, so the panel does not reorder itself between two refreshes of the
        // same alert. JSON object order is not guaranteed to be stable across the
        // parse, and a panel whose rows shuffle on a 30-second poll is unreadable.
        Map<String, Double> values = new TreeMap<>();
        Map<String, String> labels = new TreeMap<>();

        var fields = payload.fields();
        while (fields.hasNext() && values.size() + labels.size() < cap) {
            var entry = fields.next();
            String key = projectedKey(entry.getKey(), skip);
            String text = key == null ? null : scalarText(entry.getValue());
            if (text != null) {
                recordAttribute(values, labels, key, text);
            }
        }

        return new Attributes(new LinkedHashMap<>(values), new LinkedHashMap<>(labels));
    }

    /** The normalised key for a payload field, or null when the field is not shown. */
    private static String projectedKey(String rawKey, Set<String> skip) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        // `_`-prefixed keys are the engine's own bookkeeping (`_timestamp`), not
        // anything an operator is being paged about.
        if (rawKey.startsWith("_")) {
            return null;
        }
        String key = trim(rawKey.trim().toLowerCase(Locale.ROOT), MAX_KEY_CHARS);
        return skip.contains(key) ? null : key;
    }

    /** The trimmed text of a scalar node, or null when there is nothing to show. */
    private static String scalarText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        // Nested structures are skipped rather than flattened. A template that
        // emits an object here is emitting something this projection cannot label
        // honestly, and inventing dotted keys for it would put shapes in the panel
        // that do not exist in the payload.
        if (node.isObject() || node.isArray()) {
            return null;
        }

        String text = node.asText("").trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    /** Files the text under values when it is a number, under labels otherwise. */
    private static void recordAttribute(Map<String, Double> values, Map<String, String> labels,
            String key, String text) {
        Double number = asNumber(text);
        if (number != null) {
            values.put(key, number);
        } else {
            labels.put(key, trim(text, MAX_VALUE_CHARS));
        }
    }

    /**
     * A number, or null when the text is not one.
     *
     * <p>A rendered LIST of numbers is deliberately NOT treated as a number here, and
     * that differs from {@code AlertStreamRowMapper}, which takes the maximum of one.
     * The difference is intended: that mapper is producing the single value the rule
     * tested, while this is describing what the payload said. "82.7, 81.7, 76.7" is
     * three entities' readings, and collapsing it to 82.7 in a panel labelled with the
     * raw key would misreport the other two as absent.</p>
     */
    private static Double asNumber(String text) {
        try {
            double parsed = Double.parseDouble(text);
            // NaN and the infinities serialise to JSON that no consumer can read, and
            // they are never a real measurement.
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Set<String> normalizedSkipSet(Collection<String> exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return Set.of();
        }
        return exclude.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
