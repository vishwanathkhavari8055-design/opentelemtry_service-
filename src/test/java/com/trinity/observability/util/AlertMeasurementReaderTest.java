package com.trinity.observability.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Limit column's source of truth.
 *
 * <p>The table shows Current Usage beside Limit because 87.4 is not actionable
 * until you know it is 87.4% of 2048 MB. The obvious home for that ceiling —
 * {@code alerts.threshold} — is empty for every alert on this deployment, because
 * the notification template renders it as {@code limit_mb} for a memory alert and
 * {@code total_gb} for a disk one. These tests pin the reading of those, and the
 * cases where refusing to guess matters more than filling the column.</p>
 */
class AlertMeasurementReaderTest {

    private static final List<String> LIMIT_FIELDS = List.of(
            "limit_mb:MB", "total_gb:GB", "alert_threshold", "threshold", "limit");
    private static final List<String> DISPLAY_FIELDS = List.of("display_value", "display");

    private static JsonNode parse(String json) {
        return AlertMeasurementReader.parse(json);
    }

    private static String limit(String json) {
        return AlertMeasurementReader.limitLabel(parse(json), LIMIT_FIELDS);
    }

    // ── the real payload shapes on this deployment ────────────────────────────

    @Test
    void aMemoryAlertsCeilingIsReadInMegabytes() {
        assertEquals("2048 MB", limit("{\"alertname\":\"mem\",\"limit_mb\":2048.0}"));
    }

    @Test
    void aDiskAlertsCeilingIsReadInGigabytes() {
        assertEquals("91 GB", limit("{\"alertname\":\"disk\",\"total_gb\":91.0}"));
    }

    /**
     * A whole number stays whole.
     *
     * <p>OpenObserve writes these as JSON doubles, so 2048 MB arrives as
     * {@code 2048.0}. Rendering that trailing ".0" in a narrow column claims a
     * precision the source never reported.</p>
     */
    @Test
    void wholeNumbersDoNotGainADecimalPoint() {
        assertEquals("5120 MB", limit("{\"limit_mb\":5120.0}"));
    }

    @Test
    void aFractionalCeilingKeepsOneDecimal() {
        assertEquals("91.5 GB", limit("{\"total_gb\":91.46}"));
    }

    // ── refusing to invent a value ────────────────────────────────────────────

    /**
     * The alerts with no ceiling at all must render blank, not zero.
     *
     * <p>A pod in {@code Pending} and a restart count are not percentages of
     * anything. Substituting 0, or borrowing the observed value, would put a number
     * in the column that no rule ever compared against.</p>
     */
    @Test
    void anAlertWithNoCeilingHasNoLimit() {
        assertNull(limit("{\"alertname\":\"K8s Pod Crash\",\"current_usage\":1,"
                + "\"display_value\":\"state: Pending\"}"));
        assertNull(limit("{\"alertname\":\"K8s Pod Restart Loop\",\"current_usage\":20884}"));
    }

    @Test
    void aCpuPercentageAlertHasNoNumericCeiling() {
        // "99.8% of CPU limit" — the ceiling is implicit in the phrasing, and there
        // is no field carrying it. Inventing 100 would be a fabricated fact.
        assertNull(limit("{\"alertname\":\"cpu\",\"current_usage\":99.8,"
                + "\"display_value\":\"99.8% of CPU limit\"}"));
    }

    @Test
    void aNullValuedKeyIsSkippedRatherThanRendered() {
        assertNull(limit("{\"limit_mb\":null}"));
    }

    // ── ordering, units and robustness ────────────────────────────────────────

    /** Order is priority, so the most specific key present wins. */
    @Test
    void theFirstConfiguredKeyPresentWins() {
        assertEquals("2048 MB", limit("{\"limit_mb\":2048.0,\"total_gb\":91.0,\"threshold\":7}"));
        assertEquals("91 GB", limit("{\"total_gb\":91.0,\"threshold\":7}"));
    }

    /** A key declared without a unit renders the bare number — no guessed unit. */
    @Test
    void aKeyWithNoDeclaredUnitRendersTheNumberAlone() {
        assertEquals("7", limit("{\"threshold\":7}"));
    }

    /**
     * Text the template wrote is passed through untouched.
     *
     * <p>A template is free to render "2 GiB" itself, and reformatting a string
     * somebody deliberately authored would lose their meaning.</p>
     */
    @Test
    void aTextualCeilingIsPassedThrough() {
        assertEquals("2 GiB MB", limit("{\"limit_mb\":\"2 GiB\"}"));
    }

    @Test
    void anUnparseablePayloadYieldsNothingRatherThanThrowing() {
        assertNull(parse("not json at all"));
        assertNull(limit("not json at all"));
        assertNull(limit(""));
        assertNull(limit(null));
    }

    @Test
    void aJsonArrayPayloadIsNotTreatedAsAnObject() {
        assertNull(parse("[{\"limit_mb\":1}]"));
    }

    // ── the used side of the measurement ──────────────────────────────────────

    private static final List<String> USED_FIELDS = List.of(
            "used_mb:MB", "used_gb:GB", "used_bytes:B", "used");

    private static String used(String json) {
        return AlertMeasurementReader.usedLabel(parse(json), USED_FIELDS);
    }

    /**
     * The row the operator actually reads: 4040 MB of 5120 MB.
     *
     * <p>Both halves come out of the same payload, which is the point — the template
     * reports the absolute figures beside the percentage, so the table does not have
     * to present "78.9" and hope somebody knows what it is 78.9% of.</p>
     */
    @Test
    void aMemoryAlertsUsedAmountIsReadInMegabytes() {
        String row = "{\"alertname\":\"K8s Pod High Memory Usage\",\"current_usage\":\"78.9\","
                + "\"display_value\":\"78.9% (4040.0MB / 5120.0MB)\","
                + "\"limit_mb\":5120,\"used_mb\":4040}";
        assertEquals("4040 MB", used(row));
        assertEquals("5120 MB", limit(row));
    }

    @Test
    void aDiskAlertsUsedAmountIsReadInGigabytes() {
        String row = "{\"alertname\":\"K8s Node High Disk Usage\",\"current_usage\":\"81.4\","
                + "\"display_value\":\"81.4% (74.0GB / 91.0GB)\","
                + "\"total_gb\":91.0,\"used_gb\":74.0}";
        assertEquals("74 GB", used(row));
        assertEquals("91 GB", limit(row));
    }

    /** An integer in the payload and a double in the payload must render alike. */
    @Test
    void integerAndDoubleFormsOfTheSameAmountAgree() {
        assertEquals("5120 MB", limit("{\"limit_mb\":5120}"));
        assertEquals("5120 MB", limit("{\"limit_mb\":5120.0}"));
        assertEquals("4040 MB", used("{\"used_mb\":4040}"));
        assertEquals("4040 MB", used("{\"used_mb\":4040.0}"));
    }

    /**
     * The alerts with no absolute magnitude report no used amount.
     *
     * <p>Current Usage then falls back to the raw measure, because for a restart
     * count or a pod phase that IS the whole measurement — there is no "4040 of
     * 5120" to show.</p>
     */
    @Test
    void anAlertWithNoAbsoluteAmountHasNoUsedLabel() {
        assertNull(used("{\"alertname\":\"K8s Pod Restart Loop\",\"current_usage\":20884}"));
        assertNull(used("{\"alertname\":\"K8s Pod Crash\",\"current_usage\":1,"
                + "\"display_value\":\"state: Pending\"}"));
        assertNull(used("{\"alertname\":\"K8s Pod High CPU Usage\",\"current_usage\":99.8,"
                + "\"display_value\":\"99.8% of CPU limit\"}"));
    }

    /** Used and limit are read independently — one present, the other absent. */
    @Test
    void theTwoSidesAreReadIndependently() {
        assertEquals("4040 MB", used("{\"used_mb\":4040}"));
        assertNull(limit("{\"used_mb\":4040}"));
        assertEquals("5120 MB", limit("{\"limit_mb\":5120}"));
        assertNull(used("{\"limit_mb\":5120}"));
    }

    // ── display value ─────────────────────────────────────────────────────────

    /**
     * The one measurement field every alert shape here carries.
     *
     * <p>Which is exactly why it is the tooltip: it is what explains a row whose
     * Limit is legitimately blank.</p>
     */
    @Test
    void theTemplatesOwnRenderingIsReadForTheTooltip() {
        JsonNode payload = parse("{\"display_value\":\"87.4% (1790.0MB / 2048.0MB)\"}");
        assertEquals("87.4% (1790.0MB / 2048.0MB)",
                AlertMeasurementReader.displayValue(payload, DISPLAY_FIELDS));
    }

    @Test
    void aBlankDisplayValueFallsThroughToTheNextKey() {
        JsonNode payload = parse("{\"display_value\":\"  \",\"display\":\"state: Pending\"}");
        assertEquals("state: Pending",
                AlertMeasurementReader.displayValue(payload, DISPLAY_FIELDS));
    }

    @Test
    void noDisplayFieldMeansNoTooltipText() {
        assertNull(AlertMeasurementReader.displayValue(parse("{\"a\":1}"), DISPLAY_FIELDS));
    }
}
