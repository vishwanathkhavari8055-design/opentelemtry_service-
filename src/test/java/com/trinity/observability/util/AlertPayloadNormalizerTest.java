package com.trinity.observability.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.dto.alerts.AlertEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AlertPayloadNormalizer}.
 *
 * <p>The cases that matter here are the hostile ones. A notification body is
 * produced by a template a user typed into OpenObserve, so this class is the
 * boundary between "whatever they wrote" and the rest of the feature, and the
 * tests are weighted accordingly: numbers arriving as strings, variables that
 * were never substituted, timestamps in three different units, and bodies that
 * carry almost nothing at all.</p>
 */
class AlertPayloadNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = 1_777_000_000_000_000L;

    private ObjectNode payload() {
        return JSON.createObjectNode();
    }

    private AlertEvent normalize(ObjectNode node) {
        return AlertPayloadNormalizer.normalize(node, "default", null, NOW);
    }

    // ── canonical template ──────────────────────────────────────────────────

    @Test
    void testCanonicalTemplateShapeIsFullyExtracted() {
        ObjectNode p = payload();
        p.put("org_name", "trinity");
        p.put("alert_name", "api-5xx-critical");
        p.put("alert_type", "scheduled");
        p.put("stream_type", "logs");
        p.put("stream_name", "default");
        p.put("alert_period", "10");
        p.put("alert_operator", ">=");
        p.put("alert_threshold", "5");
        p.put("alert_count", "12");
        p.put("alert_agg_value", "12.5");
        p.put("alert_url", "https://oo.example/web/alerts");

        AlertEvent e = normalize(p);

        assertEquals("trinity", e.getOrg());
        assertEquals("api-5xx-critical", e.getAlertName());
        assertEquals("scheduled", e.getAlertType());
        assertEquals("logs", e.getStreamType());
        assertEquals("default", e.getStreamName());
        assertEquals(Integer.valueOf(10), e.getPeriod());
        assertEquals(">=", e.getOperator());
        assertEquals(Long.valueOf(5), e.getThreshold());
        assertEquals(Long.valueOf(12), e.getObservedCount());
        assertEquals(Double.valueOf(12.5), e.getAggValue());
        assertEquals("https://oo.example/web/alerts", e.getAlertUrl());
    }

    /**
     * OpenObserve substitutes template variables as TEXT, so every number in a
     * quoted template arrives quoted. Treating those as absent would empty the
     * whole "12 events, threshold 5" line the UI exists to show.
     */
    @Test
    void testNumbersArriveAsStringsAndAreCoerced() {
        ObjectNode p = payload();
        p.put("alert_name", "x");
        p.put("alert_count", "42");
        p.put("alert_threshold", "7");
        p.put("alert_agg_value", "3.75");

        AlertEvent e = normalize(p);

        assertEquals(Long.valueOf(42), e.getObservedCount());
        assertEquals(Long.valueOf(7), e.getThreshold());
        assertEquals(Double.valueOf(3.75), e.getAggValue());
    }

    /**
     * A template naming a variable the alert does not populate leaves the
     * placeholder in place. Storing the literal "{alert_count}" would put that
     * string in the UI where a number belongs.
     */
    @Test
    void testUnsubstitutedPlaceholdersAreTreatedAsAbsent() {
        ObjectNode p = payload();
        p.put("alert_name", "real-name");
        p.put("stream_type", "{stream_type}");
        p.put("alert_count", "{alert_count}");
        p.put("alert_url", "{alert_url}");

        AlertEvent e = normalize(p);

        assertEquals("real-name", e.getAlertName());
        assertNull(e.getStreamType(), "unsubstituted placeholder must not be stored");
        assertNull(e.getObservedCount());
        assertNull(e.getAlertUrl());
    }

    // ── aliases and fallbacks ───────────────────────────────────────────────

    @Test
    void testCamelCaseAndGenericAliasesAreAccepted() {
        ObjectNode p = payload();
        p.put("alertName", "camel-alert");
        p.put("streamName", "prod-logs");
        p.put("orgName", "acme");

        AlertEvent e = normalize(p);

        assertEquals("camel-alert", e.getAlertName());
        assertEquals("prod-logs", e.getStreamName());
        assertEquals("acme", e.getOrg());
    }

    /**
     * A text-only template still identifies something. Collapsing all of them
     * onto one "Unnamed alert" row would merge unrelated alerts into a single
     * fingerprint and hide all but the first.
     */
    @Test
    void testTextOnlyTemplateBecomesTheAlertName() {
        ObjectNode p = payload();
        p.put("text", "Alert high-latency fired on stream default");

        AlertEvent e = normalize(p);

        assertEquals("Alert high-latency fired on stream default", e.getAlertName());
    }

    @Test
    void testEmptyPayloadStillProducesAUsableEvent() {
        AlertEvent e = normalize(payload());

        assertEquals("Unnamed alert", e.getAlertName());
        assertEquals("default", e.getOrg(), "falls back to the supplied org");
        assertEquals("warning", e.getSeverity());
        assertTrue(e.getFingerprint().matches("^[a-f0-9]{16}$"));
    }

    @Test
    void testInvalidOrgInPayloadFallsBackRatherThanBeingUsed() {
        ObjectNode p = payload();
        p.put("alert_name", "x");
        // A traversal attempt: org lands in a URL path, so it must not pass through.
        p.put("org_name", "../../etc");

        assertEquals("default", normalize(p).getOrg());
    }

    // ── severity ────────────────────────────────────────────────────────────

    @Test
    void testExplicitSeverityWins() {
        ObjectNode p = payload();
        p.put("alert_name", "some-info-alert");
        p.put("severity", "critical");

        assertEquals("critical", normalize(p).getSeverity());
    }

    @Test
    void testUrlOverrideBeatsThePayload() {
        ObjectNode p = payload();
        p.put("alert_name", "x");
        p.put("severity", "info");

        AlertEvent e = AlertPayloadNormalizer.normalize(p, "default", "critical", NOW);

        assertEquals("critical", e.getSeverity(),
                "?severity= exists so one template can back several destinations");
    }

    @Test
    void testSeverityIsGuessedFromTheAlertNameWhenAbsent() {
        ObjectNode p = payload();
        p.put("alert_name", "prod-api-5xx-CRITICAL");

        assertEquals("critical", normalize(p).getSeverity());
    }

    @Test
    void testUnknownSeverityFloorsAtWarningNotInfo() {
        ObjectNode p = payload();
        p.put("alert_name", "some-rule");
        p.put("severity", "banana");

        assertEquals("warning", normalize(p).getSeverity(),
                "defaulting an unknown alert to info would hide it");
    }

    // ── fingerprinting ──────────────────────────────────────────────────────

    /**
     * The whole deduplication story rests on this: the same rule firing with a
     * different observed value must land on the same row, not create a new one
     * every evaluation.
     */
    @Test
    void testFingerprintIgnoresTheObservedValue() {
        ObjectNode first = payload();
        first.put("alert_name", "api-5xx");
        first.put("stream_name", "default");
        first.put("alert_count", "12");

        ObjectNode second = payload();
        second.put("alert_name", "api-5xx");
        second.put("stream_name", "default");
        second.put("alert_count", "900");

        assertEquals(normalize(first).getFingerprint(), normalize(second).getFingerprint());
    }

    @Test
    void testFingerprintSeparatesDifferentRulesAndOrgs() {
        String a = AlertPayloadNormalizer.fingerprint("default", "api-5xx", "logs", "default");
        String b = AlertPayloadNormalizer.fingerprint("default", "api-4xx", "logs", "default");
        String c = AlertPayloadNormalizer.fingerprint("other", "api-5xx", "logs", "default");
        String d = AlertPayloadNormalizer.fingerprint("default", "api-5xx", "logs", "other-stream");

        assertNotEquals(a, b);
        assertNotEquals(a, c, "the same rule name in another org is another alert");
        assertNotEquals(a, d);
    }

    /**
     * Field separation matters: without it, ("ab","c") and ("a","bc") would
     * concatenate identically and two unrelated alerts would share a row.
     */
    @Test
    void testFingerprintIsNotVulnerableToFieldRunTogether() {
        assertNotEquals(
                AlertPayloadNormalizer.fingerprint("o", "ab", "c", "d"),
                AlertPayloadNormalizer.fingerprint("o", "a", "bc", "d"));
    }

    // ── timestamps ──────────────────────────────────────────────────────────

    @Test
    void testMicrosecondsPassThrough() {
        assertEquals(Long.valueOf(1_777_000_000_000_000L),
                AlertPayloadNormalizer.toMicros("1777000000000000"));
    }

    /**
     * Nanoseconds, which the live stream read turned up in the wild.
     *
     * <p>They sit above the microsecond threshold, so before this they passed straight
     * through and rendered as {@code +58561-09-09T00:16:40Z} — a date absurd enough to
     * read as a rendering fault rather than a unit one, which is how it survived
     * unnoticed in the stored rows as well.</p>
     */
    @Test
    void testNanosecondsAreScaledDown() {
        assertEquals(Long.valueOf(1_777_000_000_000_000L),
                AlertPayloadNormalizer.toMicros("1777000000000000000"));
    }

    @Test
    void testMillisecondsAreScaledUp() {
        assertEquals(Long.valueOf(1_777_000_000_000_000L),
                AlertPayloadNormalizer.toMicros("1777000000000"));
    }

    @Test
    void testSecondsAreScaledUp() {
        assertEquals(Long.valueOf(1_777_000_000_000_000L),
                AlertPayloadNormalizer.toMicros("1777000000"));
    }

    @Test
    void testIsoTimestampIsAccepted() {
        assertEquals(Long.valueOf(1_700_000_000_000_000L),
                AlertPayloadNormalizer.toMicros("2023-11-14T22:13:20Z"));
    }

    /**
     * A value too small to be a plausible epoch is discarded rather than
     * guessed at — misreading the unit by 1000x would place an alert in 1970.
     */
    @Test
    void testImplausibleTimestampsAreDiscarded() {
        assertNull(AlertPayloadNormalizer.toMicros("12345"));
        assertNull(AlertPayloadNormalizer.toMicros("0"));
        assertNull(AlertPayloadNormalizer.toMicros("-1"));
        assertNull(AlertPayloadNormalizer.toMicros("not-a-date"));
        assertNull(AlertPayloadNormalizer.toMicros(""));
        assertNull(AlertPayloadNormalizer.toMicros(null));
    }

    @Test
    void testWindowTimestampsAreReadFromTheCanonicalFields() {
        ObjectNode p = payload();
        p.put("alert_name", "x");
        p.put("alert_start_time", "1777000000000000");
        p.put("alert_end_time", "1777000600000000");

        AlertEvent e = normalize(p);

        assertEquals(Long.valueOf(1_777_000_000_000_000L), e.getWindowStartMicros());
        assertEquals(Long.valueOf(1_777_000_600_000_000L), e.getWindowEndMicros());
    }
}
