package com.trinity.observability.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.dto.alerts.AlertEvent;
import com.trinity.observability.dto.alerts.AlertState;

/**
 * Turns whatever OpenObserve actually posted into an {@link AlertEvent}.
 *
 * <h2>Why this is lenient</h2>
 *
 * <p>The body of an alert notification is not defined by OpenObserve — it is
 * defined by a <em>template the user wrote</em>, in a text box, with no
 * validation beyond "is it a string". This service ships a canonical template
 * (see {@code AlertNotificationsServiceImpl#canonicalTemplateBody}), but it
 * cannot assume it: templates are edited in OpenObserve's own UI, alerts
 * created before this feature existed already point at other ones, and a single
 * destination can be reused by rules nobody here configured.</p>
 *
 * <p>So every field is looked up through a list of aliases, every number is
 * accepted as either a number or a string (OpenObserve's substitution is
 * textual, so {@code {alert_count}} arrives as {@code "12"}, not {@code 12}),
 * and nothing is ever rejected for being unrecognised. An alert that arrives in
 * a shape this class does not understand still becomes a visible row carrying
 * its raw body — the one outcome that must never happen is a real alert being
 * dropped because its template was odd.</p>
 */
public final class AlertPayloadNormalizer {

    private AlertPayloadNormalizer() { }

    // Alias lists, most-specific first. OpenObserve's own variable names lead,
    // the camelCase and generic forms cover hand-written and third-party templates.
    private static final List<String> ORG_KEYS       = List.of("org_name", "orgName", "org", "organization");
    private static final List<String> NAME_KEYS      = List.of("alert_name", "alertName", "alert", "name", "title");
    private static final List<String> TYPE_KEYS      = List.of("alert_type", "alertType", "type");
    private static final List<String> STREAM_KEYS    = List.of("stream_name", "streamName", "stream");
    private static final List<String> STREAMTYPE_KEYS= List.of("stream_type", "streamType");
    private static final List<String> SEVERITY_KEYS  = List.of("severity", "level", "priority", "urgency");
    private static final List<String> PERIOD_KEYS    = List.of("alert_period", "period");
    private static final List<String> OPERATOR_KEYS  = List.of("alert_operator", "operator");
    private static final List<String> THRESHOLD_KEYS = List.of("alert_threshold", "threshold");
    private static final List<String> COUNT_KEYS     = List.of("alert_count", "observed_count", "count");
    private static final List<String> AGG_KEYS       = List.of("alert_agg_value", "agg_value", "value");
    private static final List<String> START_KEYS     = List.of("alert_start_time", "start_time", "startsAt", "startTime");
    private static final List<String> END_KEYS       = List.of("alert_end_time", "end_time", "endsAt", "endTime");
    private static final List<String> URL_KEYS       = List.of("alert_url", "url", "generatorURL", "link");
    /** Last-resort name source: a text-only template still names something useful. */
    private static final List<String> TEXT_KEYS      = List.of("text", "message", "msg", "body", "description");

    private static final int MAX_DERIVED_NAME_CHARS = 120;
    private static final String SEVERITY_WARNING = "warning";

    /**
     * Builds an event from a parsed notification body.
     *
     * @param payload          the parsed body; may be an empty object when the body did not parse
     * @param orgFallback      org to use when the payload does not name one
     * @param severityOverride from {@code ?severity=} on the webhook URL; wins over the payload
     *                         so one shared template can serve a critical and a warning
     *                         destination without being duplicated
     * @param receivedMicros   arrival time, used when the payload carries no usable timestamp
     */
    public static AlertEvent normalize(JsonNode payload, String orgFallback,
                                       String severityOverride, long receivedMicros) {
        AlertEvent e = new AlertEvent();
        e.setEventType(AlertState.FIRING);
        e.setActor("openobserve");
        e.setTimestampMicros(receivedMicros);

        String org = firstText(payload, ORG_KEYS);
        e.setOrg(OrgContext.isValid(org) ? org : orgFallback);

        e.setAlertName(deriveName(payload));
        e.setAlertType(firstText(payload, TYPE_KEYS));
        e.setStreamName(firstText(payload, STREAM_KEYS));
        e.setStreamType(firstText(payload, STREAMTYPE_KEYS));
        e.setOperator(firstText(payload, OPERATOR_KEYS));
        e.setAlertUrl(firstText(payload, URL_KEYS));

        e.setPeriod(toInt(firstText(payload, PERIOD_KEYS)));
        e.setThreshold(toLong(firstText(payload, THRESHOLD_KEYS)));
        e.setObservedCount(toLong(firstText(payload, COUNT_KEYS)));
        e.setAggValue(toDouble(firstText(payload, AGG_KEYS)));

        e.setWindowStartMicros(toMicros(firstText(payload, START_KEYS)));
        e.setWindowEndMicros(toMicros(firstText(payload, END_KEYS)));

        applySeverity(e, payload, severityOverride);

        e.setFingerprint(fingerprint(e.getOrg(), e.getAlertName(), e.getStreamType(), e.getStreamName()));
        return e;
    }

    /**
     * Severity, whether it was stated, and where it came from.
     *
     * <p>{@code severityOverride} comes from {@code ?severity=} on the webhook URL and
     * wins over the payload.</p>
     */
    private static void applySeverity(AlertEvent e, JsonNode payload, String severityOverride) {
        boolean overridden = severityOverride != null && !severityOverride.isBlank();
        String severity = overridden
                ? severityOverride
                : firstText(payload, SEVERITY_KEYS);
        e.setSeverity(normalizeSeverity(severity, e.getAlertName()));
        // Recorded so the caller can tell a stated severity from a guessed one:
        // a guess should defer to the severity on the matching rule, a stated
        // value should not. matchSeverity, not isBlank — "severity": "banana"
        // is not a severity, and treating it as one would let a typo outrank
        // the rule's real setting.
        boolean explicit = matchSeverity(
                severity == null ? "" : severity.trim().toLowerCase(Locale.ROOT)) != null;
        e.setSeverityExplicit(explicit);
        e.setSeveritySource(severitySource(explicit, overridden, e.getAlertName()));
    }

    private static String severitySource(boolean explicit, boolean overridden, String alertName) {
        if (explicit) {
            return overridden ? "url-override" : "payload";
        }
        // Not stated anywhere, so it was inferred. Distinguishing the two
        // inferences matters: a name-guess is a reasonable signal, whereas
        // "default" means we genuinely have no idea and picked the floor.
        String name = alertName == null ? "" : alertName.toLowerCase(Locale.ROOT);
        return matchSeverity(name) != null ? "name-guess" : "default";
    }

    /**
     * Identity of an alert across firings.
     *
     * <p>Deliberately does NOT include the observed value, the timestamp or the
     * evaluation window. Those change on every evaluation, and including any of
     * them would make each firing of one misbehaving rule a brand new row —
     * turning a single ongoing problem into an unreadable wall of duplicates,
     * which is the specific failure that makes alert feeds get ignored.</p>
     *
     * <p>Truncated to 16 hex characters: 64 bits, which is far past collision
     * risk for a per-org set of alert rules, and short enough to appear in a URL
     * and a log line without dominating them.</p>
     */
    public static String fingerprint(String org, String alertName, String streamType, String streamName) {
        return fingerprintOf(java.util.List.of(
                nz(org), nz(alertName), nz(streamType), nz(streamName)));
    }

    /**
     * Fingerprint from an explicit, ordered list of identity parts.
     *
     * <p>Exists because WHICH fields identify an alert is a per-deployment fact
     * rather than a constant. Including {@code streamName} split one alert into two
     * on this instance -- the template emits it on some firings and not others --
     * which produced duplicate rows AND broke episode continuity, because a changed
     * fingerprint is a NEW alert rather than the next episode of an existing one.
     * See {@code alerts.ingest.identity-fields}.</p>
     *
     * <p>Joined with NUL, which cannot occur in any part, so no combination of
     * values can collide by straddling a boundary: ("ab","c") must not hash the
     * same as ("a","bc").</p>
     */
    public static String fingerprintOf(java.util.List<String> parts) {
        String seed = String.join("\0", parts.stream()
                .map(AlertPayloadNormalizer::nz).toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Collapses free-form severity onto the four levels the UI renders.
     *
     * <p>OpenObserve has no severity concept of its own, so when the template
     * does not supply one it is guessed from the alert's name — teams
     * overwhelmingly encode it there ("prod-api-5xx-critical"). A guess is
     * better than defaulting everything to one level, which would make the
     * colour coding meaningless; {@code warning} is the floor because silently
     * calling an unknown alert {@code info} hides it.</p>
     */
    public static String normalizeSeverity(String raw, String alertName) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String matched = matchSeverity(v);
        if (matched != null) return matched;

        String fromName = matchSeverity(alertName == null ? "" : alertName.toLowerCase(Locale.ROOT));
        return fromName != null ? fromName : SEVERITY_WARNING;
    }

    private static String matchSeverity(String v) {
        if (v.isEmpty()) return null;
        if (contains(v, "disaster", "sev0", "p0", "catastroph"))                return "disaster";
        if (contains(v, "critical", "crit", "fatal", "sev1", "p1", "emergency")) return "critical";
        if (contains(v, "error", "err", "sev2", "p2", "high"))                   return "error";
        if (contains(v, SEVERITY_WARNING, "warn", "sev3", "p3", "medium"))              return SEVERITY_WARNING;
        if (contains(v, "info", "informational", "notice", "sev4", "p4", "low")) return "info";
        return null;
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String deriveName(JsonNode payload) {
        String name = firstText(payload, NAME_KEYS);
        if (name != null) return name;

        // A text-only template ("Alert {alert_name} fired on {stream_name}")
        // still identifies something. Using it keeps such alerts distinguishable
        // from each other instead of collapsing every one onto a single row.
        String text = firstText(payload, TEXT_KEYS);
        if (text != null) {
            return text.length() > MAX_DERIVED_NAME_CHARS
                    ? text.substring(0, MAX_DERIVED_NAME_CHARS) + "…"
                    : text;
        }
        return "Unnamed alert";
    }

    /** First alias present as a non-blank scalar. Nested objects are ignored. */
    private static String firstText(JsonNode payload, List<String> keys) {
        if (payload == null || !payload.isObject()) return null;
        for (String key : keys) {
            String v = usableText(payload.get(key));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** The trimmed text of a scalar node, or null when it is absent, blank or unsubstituted. */
    private static String usableText(JsonNode n) {
        if (!isScalar(n)) return null;
        String v = n.asText("").trim();
        // An unsubstituted variable — the template named something the alert
        // does not have. Treat as absent rather than storing "{alert_count}".
        boolean unsubstituted = v.startsWith("{") && v.endsWith("}");
        return v.isEmpty() || unsubstituted ? null : v;
    }

    private static boolean isScalar(JsonNode n) {
        return n != null && !n.isNull() && !n.isMissingNode() && !n.isObject() && !n.isArray();
    }

    private static Integer toInt(String v) {
        Long l = toLong(v);
        return l == null ? null : l.intValue();
    }

    private static Long toLong(String v) {
        Double d = toDouble(v);
        return d == null ? null : d.longValue();
    }

    private static Double toDouble(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Coerces a timestamp to epoch microseconds.
     *
     * <p>The unit is inferred from magnitude rather than trusted, because it
     * genuinely varies: OpenObserve's own variables are microseconds, a
     * hand-written template may pass through a millisecond field from the
     * matched row, and an ISO string is what a human writes. Guessing wrong by
     * a factor of 1000 would place an alert in 1970 or in the year 57000, so
     * anything that does not land in a plausible range is discarded instead.</p>
     */
    public static Long toMicros(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();

        Double num = toDouble(s);
        if (num != null) {
            return numericMicros(num.longValue());
        }

        try {
            Instant i = Instant.parse(s);
            return i.getEpochSecond() * 1_000_000L + i.getNano() / 1_000L;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A numeric timestamp of inferred unit, as epoch microseconds; null when implausible. */
    private static Long numericMicros(long n) {
        if (n <= 0) return null;
        // Ordered widest-unit-first, and the top bound is not optional: without it
        // a nanosecond timestamp is ≥ the microsecond threshold and passes straight
        // through, placing the alert in the year 58561. The javadoc above always
        // claimed such values were discarded; only the lower end actually was.
        if (n >= 1_000_000_000_000_000_000L) return n / 1_000L;// ns   (≥ 2001)
        if (n >= 1_000_000_000_000_000L) return n;            // µs   (≥ 2001)
        if (n >= 1_000_000_000_000L)     return n * 1_000L;    // ms   (≥ 2001)
        if (n >= 1_000_000_000L)         return n * 1_000_000L;// s    (≥ 2001)
        return null;                                           // too small to be real
    }

    /** Current time in the same unit every OpenObserve stream uses. */
    public static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
