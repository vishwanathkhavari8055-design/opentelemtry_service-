package com.trinity.observability.util;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pulls the measurement fields the Alerts table shows out of a stored
 * {@code raw_payload}.
 *
 * <p>Read-side only, and deliberately so — see {@link
 * com.trinity.observability.config.AlertDisplayProperties} for why the limit is
 * not a column. Nothing here writes, and nothing here can fail a request: an
 * unparseable payload yields no value, which renders as a dash.</p>
 */
public final class AlertMeasurementReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Guard against a pathological payload: this is called once per table row. */
    private static final int MAX_PAYLOAD_CHARS = 64_000;

    private AlertMeasurementReader() {
    }

    /** Parse once per row; both readers below take the result. */
    public static JsonNode parse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()
                || rawPayload.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(rawPayload);
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            // A payload that does not parse is not an error worth surfacing — the
            // alert itself is intact and its parse_error already records the fact.
            return null;
        }
    }

    /**
     * The ceiling the observed value was measured against, with its unit.
     *
     * @param payload parsed payload, or null
     * @param fields  {@code key} or {@code key:unit} entries, most specific first
     * @return e.g. {@code "2048 MB"}, or null when no configured key is present
     */
    public static String limitLabel(JsonNode payload, List<String> fields) {
        return unitLabel(payload, fields);
    }

    /**
     * How much is actually in use, with its unit.
     *
     * <p>Same extraction as the limit — a first-match-wins scan over declared
     * {@code key:unit} pairs — because "4040 MB" and "5120 MB" are the same kind of
     * fact read from two different keys. Kept as its own named method so call sites
     * say which side of the measurement they mean.</p>
     *
     * @return e.g. {@code "4040 MB"}, or null when no configured key is present
     */
    public static String usedLabel(JsonNode payload, List<String> fields) {
        return unitLabel(payload, fields);
    }

    /**
     * First configured key present in the payload, rendered with its declared unit.
     *
     * @param fields {@code key} or {@code key:unit} entries, most specific first
     */
    private static String unitLabel(JsonNode payload, List<String> fields) {
        if (payload == null || fields == null) {
            return null;
        }
        for (String entry : fields) {
            String label = entryLabel(payload, entry);
            if (label != null) {
                return label;
            }
        }
        return null;
    }

    /**
     * One {@code key} or {@code key:unit} entry rendered from the payload, or null when
     * the entry is unusable or its key is absent.
     */
    private static String entryLabel(JsonNode payload, String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        int sep = entry.indexOf(':');
        String key = (sep < 0 ? entry : entry.substring(0, sep)).trim();
        if (key.isEmpty()) {
            return null;
        }
        String number = presentNumber(payload.get(key));
        if (number == null) {
            return null;
        }
        String unit = sep < 0 ? "" : entry.substring(sep + 1).trim();
        return unit.isEmpty() ? number : number + " " + unit;
    }

    /** The node rendered as a number, or null when it is absent or not numeric. */
    private static String presentNumber(JsonNode value) {
        return hasValue(value) ? numberText(value) : null;
    }

    /** True when the payload actually carries the key with a non-null value. */
    private static boolean hasValue(JsonNode value) {
        return value != null && !value.isNull() && !value.isMissingNode();
    }

    /** The template's own rendering of the measurement, if it provided one. */
    public static String displayValue(JsonNode payload, List<String> fields) {
        if (payload == null || fields == null) {
            return null;
        }
        for (String key : fields) {
            String text = displayText(payload, key);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** One display key's non-blank text, or null when the key is unusable or absent. */
    private static String displayText(JsonNode payload, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        JsonNode value = payload.get(key.trim());
        if (!hasValue(value)) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    /**
     * Render a payload number without inventing precision.
     *
     * <p>OpenObserve writes these as JSON doubles, so a 2048 MB limit arrives as
     * {@code 2048.0}. Printing that trailing ".0" in a narrow column claims a
     * precision the source did not report, so a whole number is shown whole.
     * Strings are passed through — a template is free to render "2 GiB" itself, and
     * reformatting text somebody deliberately wrote would lose their meaning.</p>
     */
    private static String numberText(JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (!value.isNumber()) {
            return null;
        }
        double d = value.asDouble();
        if (!Double.isFinite(d)) {
            return null;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return TrimmedDecimal.format(d);
    }

    /** One decimal place, which is the precision these templates actually carry. */
    private static final class TrimmedDecimal {
        static String format(double d) {
            return String.format(java.util.Locale.ROOT, "%.1f", d);
        }
    }
}
