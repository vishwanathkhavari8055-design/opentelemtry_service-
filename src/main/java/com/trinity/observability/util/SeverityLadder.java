package com.trinity.observability.util;

/**
 * Grades an observed value into a severity band.
 *
 * <p>Equivalent to the template form operators usually write by hand:</p>
 *
 * <pre>
 *   {{ if gt $value 90.0 }}disaster
 *   {{ else if gt $value 80.0 }}critical
 *   {{ else if gt $value 70.0 }}warning
 *   {{ else }}info{{ end }}
 * </pre>
 *
 * <p>Done here rather than in the OpenObserve template for one reason that
 * matters: a template computes the grade once, at notification time, in a place
 * nobody can query. Doing it here means the thresholds live in PostgreSQL beside
 * the rule, can be changed without editing a template, are visible in the API,
 * and — because {@code severity_source} is recorded — a graded alert can later
 * be explained rather than argued about.</p>
 *
 * <h2>Thresholds are per rule</h2>
 *
 * <p>There is deliberately no global default. The metrics are on different
 * scales: {@code process_cpu_usage} reports a 0..1 fraction while a percentage
 * metric reports 0..100. A shared 90/80/70 would grade every fractional metric
 * as {@code info} forever, which looks like the feature working and is in fact
 * the feature silently doing nothing.</p>
 */
public final class SeverityLadder {

    private SeverityLadder() { }

    public static final String DISASTER = "disaster";
    public static final String CRITICAL = "critical";
    public static final String ERROR    = "error";
    public static final String WARNING  = "warning";
    public static final String INFO     = "info";

    /**
     * Grades {@code value} against the configured bands, worst first.
     *
     * <p>Bounds are INCLUSIVE — a value of exactly 90 with
     * {@code disasterAt = 90} grades disaster. Exclusive comparison
     * ({@code > 90}) leaves a value sitting precisely on its threshold in the
     * band below, which reads as a bug every time someone tests with the round
     * number they just configured.</p>
     *
     * <p>Any tier may be null, meaning "not used". A rule can therefore define
     * only {@code criticalAt} and get critical-or-info with nothing in between.</p>
     *
     * @return the band name, or {@code null} when no tier is configured at all —
     *         the signal to the caller that this rule does not use the ladder
     *         and its static severity should stand
     */
    public static String grade(Double value, Double disasterAt, Double criticalAt, Double warningAt) {
        if (!isEnabled(disasterAt, criticalAt, warningAt)) return null;
        // No value to grade. Not an error: a rule can fire on a row count with
        // no aggregate. Falling through to info would understate a real firing,
        // so the caller is told to use its other sources instead.
        if (value == null) return null;

        if (reaches(value, disasterAt)) return DISASTER;
        if (reaches(value, criticalAt)) return CRITICAL;
        if (reaches(value, warningAt))  return WARNING;
        return INFO;
    }

    /** True when {@code threshold} is configured and {@code value} is at or above it. */
    private static boolean reaches(double value, Double threshold) {
        return threshold != null && value >= threshold;
    }

    /** True when at least one band is configured. */
    public static boolean isEnabled(Double disasterAt, Double criticalAt, Double warningAt) {
        return disasterAt != null || criticalAt != null || warningAt != null;
    }

    /**
     * Rejects thresholds that are not in descending order.
     *
     * <p>{@code disaster=70, critical=80} is accepted by the database and then
     * behaves nonsensically — the disaster test matches first, so nothing is
     * ever critical and the two look swapped. Caught at write time, where the
     * person who typed it is still watching.</p>
     *
     * @throws IllegalArgumentException if the bands overlap or invert
     */
    public static void validate(Double disasterAt, Double criticalAt, Double warningAt) {
        requireAbove(DISASTER, disasterAt, CRITICAL, criticalAt);
        requireAbove(CRITICAL, criticalAt, WARNING, warningAt);
        requireAbove(DISASTER, disasterAt, WARNING, warningAt);
    }

    /** Throws when both bands are configured and {@code higher} is not strictly above {@code lower}. */
    private static void requireAbove(String higherName, Double higher, String lowerName, Double lower) {
        if (higher != null && lower != null && higher <= lower) {
            throw new IllegalArgumentException(
                    "severityThresholds." + higherName + " (" + higher + ") must be greater than "
                            + "severityThresholds." + lowerName + " (" + lower + ")");
        }
    }
}
