package com.trinity.observability.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.dto.alerts.AlertEvent;

/**
 * Turns one row of an OpenObserve alert stream into an {@link AlertEvent}.
 *
 * <p>The counterpart to {@link AlertPayloadNormalizer}, which does the same job
 * for a webhook body. Two classes rather than one because the inputs differ in a
 * way that matters: a webhook body is arbitrary JSON of unknown shape, while a
 * stream row is a FLAT map whose keys are decided by the deployment's own
 * notification template. So this one is driven entirely by
 * {@link AlertIngestProperties.Mapping} — no key names appear in this file at
 * all.</p>
 *
 * <h2>Everything is stringly typed, on purpose</h2>
 *
 * <p>OpenObserve infers a stream's schema from its first write, and a template
 * renders every field as text — so {@code matched_records} arrives as
 * {@code "6"}, not {@code 6}, and {@code current_usage} can be
 * {@code "82.7, 81.7, 76.7"} (one value per matched record) rather than a single
 * number. Numeric parsing therefore tolerates a list and takes its maximum: the
 * worst value in the set is what an operator is being paged about, and taking the
 * first would report whichever host the query happened to return first.</p>
 */
public final class AlertStreamRowMapper {

    private static final Logger log = LogManager.getLogger(AlertStreamRowMapper.class);

    /** Everything from the first character that cannot be part of a number, e.g. a unit or "%". */
    private static final java.util.regex.Pattern TRAILING_UNIT =
            java.util.regex.Pattern.compile("[^0-9eE+.\\-].*$");

    /** OpenObserve's own ingest timestamp column. Not configurable — it is the engine's. */
    public static final String TIMESTAMP_COLUMN = "_timestamp";

    /**
     * Identity token → the event field it reads. A token absent from this map
     * contributes an empty part (see {@link #identityParts}).
     */
    private static final Map<String, Function<AlertEvent, String>> IDENTITY_GETTERS = Map.of(
            "org", AlertEvent::getOrg,
            "alertname", AlertEvent::getAlertName,
            // The severity-stripped rule identity. Preferred over `alertname` for
            // the incident key: it is what makes an escalation one incident.
            "incidenttype", AlertEvent::getIncidentType,
            "streamtype", AlertEvent::getStreamType,
            "streamname", AlertEvent::getStreamName,
            "severity", AlertEvent::getSeverity,
            "servicename", AlertEvent::getServiceName,
            "namespace", AlertEvent::getNamespace,
            "entities", AlertEvent::getEntities);

    private AlertStreamRowMapper() {
    }

    /**
     * Map a stream row.
     *
     * @param row        the flat hit as returned by a search
     * @param orgFallback org to attribute the alert to when the row does not name one
     * @param mapping    which source keys to read for each logical field
     * @param maxPayloadChars cap on the retained raw copy
     * @return a populated event; never null
     */
    public static AlertEvent map(Map<String, Object> row, String orgFallback,
            AlertIngestProperties.Mapping mapping, int maxPayloadChars) {
        // Default identity, for callers that do not configure one.
        return map(row, orgFallback, mapping, maxPayloadChars, DEFAULT_IDENTITY_FIELDS);
    }

    /**
     * The identity fields an alert is fingerprinted on when none is configured.
     *
     * <p>Kept in step with the {@code alerts.ingest.identity-fields} default in
     * {@code application.properties}. Two different defaults for the same decision is
     * how a library consumer ends up grouping alerts differently from the service that
     * ships with them, and the symptom — duplicate incidents on one deployment only —
     * gives no hint where to look.</p>
     *
     * <p>{@code incidentType} rather than {@code alertName}: the severity-stripped
     * identity is what makes a condition that escalates one incident.
     * {@code serviceName} so two services breaching the same metric stay two.</p>
     */
    public static final java.util.List<String> DEFAULT_IDENTITY_FIELDS =
            java.util.List.of("org", "serviceName", "incidentType");

    /**
     * As {@link #map(Map, String, AlertIngestProperties.Mapping, int)}, with the
     * fields that identify one alert supplied explicitly.
     *
     * <p>See {@code alerts.ingest.identity-fields}: including a field the template
     * emits inconsistently splits one alert into two identities, which shows up as
     * duplicate rows and as an alert that starts a fresh history instead of a new
     * episode when it fires again.</p>
     */
    public static AlertEvent map(Map<String, Object> row, String orgFallback,
            AlertIngestProperties.Mapping mapping, int maxPayloadChars,
            java.util.List<String> identityFields) {
        return map(row, orgFallback, mapping, maxPayloadChars, identityFields,
                AlertSeverityRank.DEFAULT_ORDER, true);
    }

    /**
     * As above, with the incident-type derivation supplied explicitly.
     *
     * <p>{@code severityOrder} and {@code stripSeveritySuffix} come from
     * {@code alerts.incident.*}. They are parameters rather than reads of a static
     * default because the type they derive is part of the fingerprint: a deployment
     * that changes its severity vocabulary re-identifies its alerts, and that has to
     * be a configuration decision rather than something this class assumes.</p>
     */
    public static AlertEvent map(Map<String, Object> row, String orgFallback,
            AlertIngestProperties.Mapping mapping, int maxPayloadChars,
            java.util.List<String> identityFields,
            java.util.List<String> severityOrder, boolean stripSeveritySuffix) {
        AlertEvent event = new AlertEvent();

        String org = first(row, mapping.keysFor("org"));
        event.setOrg(isBlank(org) ? orgFallback : org);

        event.setTimestampMicros(timestampMicros(row, mapping));

        String alertName = first(row, mapping.keysFor("alertName"));
        event.setAlertName(isBlank(alertName) ? "Unnamed alert" : trim(alertName, 255));

        // The KIND of problem, with the severity level taken out of it — so a rule set
        // that expresses levels as separate rules ("…-warning", "…-disaster") still
        // aggregates into one incident that escalates. See AlertTypeNormalizer.
        event.setIncidentType(trim(AlertTypeNormalizer.incidentType(
                event.getAlertName(), severityOrder, stripSeveritySuffix), 255));

        event.setAlertType(first(row, mapping.keysFor("alertType")));
        event.setStreamName(first(row, mapping.keysFor("streamName")));
        event.setStreamType(first(row, mapping.keysFor("streamType")));
        event.setOperator(first(row, mapping.keysFor("operator")));
        event.setAlertUrl(first(row, mapping.keysFor("alertUrl")));

        event.setPeriod(toInt(first(row, mapping.keysFor("period"))));
        event.setThreshold(toLong(first(row, mapping.keysFor("threshold"))));
        event.setObservedCount(toLong(first(row, mapping.keysFor("observedCount"))));
        event.setAggValue(toDouble(first(row, mapping.keysFor("aggValue"))));

        event.setWindowStartMicros(AlertPayloadNormalizer.toMicros(first(row, mapping.keysFor("windowStart"))));
        event.setWindowEndMicros(AlertPayloadNormalizer.toMicros(first(row, mapping.keysFor("windowEnd"))));

        String severity = first(row, mapping.keysFor("severity"));
        event.setSeverity(AlertPayloadNormalizer.normalizeSeverity(severity, event.getAlertName()));
        event.setSeverityExplicit(!isBlank(severity));
        event.setSeveritySource(event.isSeverityExplicit() ? "payload" : "name-guess");

        // Normalised, not stored raw. A template that loops over matched records
        // emits one separator-delimited block per entity with the list separator
        // landing inside the text, so the raw form is a 2,400-character blob with
        // a leading "----------------" and a trailing comma. The untouched original
        // is still in rawPayload for the Raw Event panel.
        AlertDescriptionNormalizer.Normalized description =
                AlertDescriptionNormalizer.normalize(first(row, mapping.keysFor("description")));
        event.setDescription(description.text());
        event.setSummary(first(row, mapping.keysFor("summary")));
        event.setEntities(first(row, mapping.keysFor("entities")));
        event.setServiceName(first(row, mapping.keysFor("serviceName")));
        event.setHostName(first(row, mapping.keysFor("hostName")));
        event.setNamespace(first(row, mapping.keysFor("namespace")));
        event.setEnvironment(first(row, mapping.keysFor("environment")));
        event.setTraceId(first(row, mapping.keysFor("traceId")));

        applyEntityFallbacks(event);

        // Identity comes from the CONFIGURED fields only — never from the whole row.
        //
        // Hashing every column the row happens to carry looks like a stronger
        // identity and is in fact no identity at all: `alert_count`,
        // `alert_agg_value`, `current_usage`, `alert_start_time` and
        // `alert_end_time` all change on every evaluation, so each firing of ONE
        // rule produced a fingerprint nothing had ever seen. findOpenEpisode then
        // matched nothing, and every poll opened a brand new alert row for a
        // problem that had never gone away — the wall of repeating alerts this
        // fingerprint exists to prevent.
        event.setFingerprint(AlertPayloadNormalizer.fingerprintOf(
                identityParts(event, identityFields)));
        event.setRawPayload(rawCopy(row, maxPayloadChars));
        event.setEventHash(hashRow(row, event));
        return event;
    }

    /**
     * Timestamp comes from the engine's own column, falling back to the
     * template's start time and then to now. A row with no usable time would
     * otherwise land at epoch 0 and sort to the bottom of the feed forever.
     */
    private static long timestampMicros(Map<String, Object> row,
            AlertIngestProperties.Mapping mapping) {
        Long ts = toMicros(row.get(TIMESTAMP_COLUMN));
        if (ts == null) {
            ts = AlertPayloadNormalizer.toMicros(first(row, mapping.keysFor("windowStart")));
        }
        return ts == null ? AlertPayloadNormalizer.nowMicros() : ts;
    }

    /**
     * Service falls back to the affected-entity list when the template names
     * no service. On this deployment `endpoint` is "kubeworker11 : /", which
     * is the only identity the row carries — leaving Service blank would make
     * the column and its filter useless for every infrastructure alert.
     */
    private static void applyEntityFallbacks(AlertEvent event) {
        if (isBlank(event.getServiceName()) && !isBlank(event.getEntities())) {
            event.setServiceName(trim(firstCsvItem(event.getEntities()), 255));
        }
        if (isBlank(event.getHostName()) && !isBlank(event.getEntities())) {
            event.setHostName(trim(firstCsvItem(event.getEntities()), 255));
        }
    }

    /**
     * The identity values, in the configured order.
     *
     * <p>An unrecognised token contributes an empty part rather than throwing: the
     * configuration is validated at startup, so reaching here with one would mean
     * the validator and this method disagree, and dropping an alert is a worse
     * answer than fingerprinting it slightly more coarsely.</p>
     */
    private static java.util.List<String> identityParts(AlertEvent event,
            java.util.List<String> identityFields) {
        java.util.List<String> parts = new java.util.ArrayList<>(identityFields.size());
        for (String field : identityFields) {
            String token = field == null ? "" : field.trim().toLowerCase(java.util.Locale.ROOT);
            Function<AlertEvent, String> getter = IDENTITY_GETTERS.get(token);
            parts.add(getter == null ? "" : getter.apply(event));
        }
        return parts;
    }

    /**
     * Stable hash identifying this stream row, for idempotent re-ingest.
     *
     * <p>Built from the engine timestamp plus the fields that identify a firing,
     * NOT from the whole row: OpenObserve adds columns over time and a row
     * re-serialised with one extra null field must still hash the same, or the
     * duplicate guard silently stops working exactly when the schema drifts.</p>
     */
    @SuppressWarnings("java:S4790")
    private static String hashRow(Map<String, Object> row, AlertEvent event) {
        Object ts = row.get(TIMESTAMP_COLUMN);
        // NUL separator, as in AlertPayloadNormalizer.fingerprint: it cannot occur
        // in any of the inputs, so no combination of values can collide by
        // straddling the boundary between two of them.
        String seed = String.join("\0",
                nz(String.valueOf(ts)),
                nz(event.getOrg()),
                nz(event.getAlertName()),
                nz(event.getStreamName()),
                nz(event.getEntities()),
                nz(event.getAggValue() == null ? null : event.getAggValue().toString()));
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // MD5 is mandated by the platform, so this is unreachable. Returning
            // null degrades to "no duplicate guard for this row" rather than
            // failing the whole poll.
            log.warn("Could not hash alert stream row: {}", e.getMessage());
            return null;
        }
    }

    /** A JSON-ish copy of the row for the Raw Event panel, capped. */
    private static String rawCopy(Map<String, Object> row, int maxChars) {
        StringBuilder out = new StringBuilder("{");
        boolean firstEntry = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (!firstEntry) {
                out.append(',');
            }
            firstEntry = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"').append(escape(value.toString())).append('"');
            }
        }
        out.append('}');
        String json = out.toString();
        return (maxChars > 0 && json.length() > maxChars) ? json.substring(0, maxChars) : json;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** First non-blank value among the candidate keys. */
    private static String first(Map<String, Object> row, List<String> keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return null;
    }

    /** First item of a comma-separated rendered list. */
    private static String firstCsvItem(String value) {
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }

    /**
     * Parse a number that may be a rendered LIST of numbers.
     *
     * <p>Takes the maximum. A template that reports one value per matched record
     * ("82.7, 81.7, 76.7") is describing several breaching entities at once, and
     * the worst of them is what the alert is about — taking the first would report
     * whichever host the query happened to order first, which changes between
     * evaluations of the same condition.</p>
     */
    private static Double toDouble(String value) {
        if (value == null) {
            return null;
        }
        Double best = null;
        for (String part : value.split(",")) {
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            // Tolerate a trailing unit or percent sign, which templates often add.
            candidate = TRAILING_UNIT.matcher(candidate).replaceAll("");
            try {
                double parsed = Double.parseDouble(candidate);
                if (best == null || parsed > best) {
                    best = parsed;
                }
            } catch (NumberFormatException ignored) {
                // Not a number — a template can put anything in this field.
            }
        }
        return best;
    }

    private static Long toLong(String value) {
        Double parsed = toDouble(value);
        return parsed == null ? null : parsed.longValue();
    }

    private static Integer toInt(String value) {
        Double parsed = toDouble(value);
        return parsed == null ? null : parsed.intValue();
    }

    /** Epoch micros from the engine's own numeric {@code _timestamp}. */
    private static Long toMicros(Object value) {
        if (value == null) {
            return null;
        }
        return AlertPayloadNormalizer.toMicros(value.toString());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
