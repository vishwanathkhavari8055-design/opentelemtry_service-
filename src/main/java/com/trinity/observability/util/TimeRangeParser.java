package com.trinity.observability.util;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeRangeParser {

    private static final Pattern REL = Pattern.compile("now(?:-(\\d+)([smhdw]))?");

    private TimeRangeParser() {}

    public static long toMicros(String expr, long defaultMicros) {
        return toMicros(expr, defaultMicros, nowMicros());
    }

    /**
     * Resolves {@code expr} against an explicit "now" instead of reading the clock.
     *
     * <p>A caller that pairs a relative start with an {@code end} it captured
     * earlier must anchor both to the same instant. Re-reading the clock here makes
     * the resulting span shorter than the expression asks for by however long the
     * caller took to get here, so {@code now-15m} measured against an earlier
     * {@code end} spans 14m59.999s rather than 15m. Anything that compares the span
     * back to the rung that produced it -- the infrastructure coverage ladder -- then
     * reads one rung low.</p>
     */
    public static long toMicros(String expr, long defaultMicros, long nowMicros) {
        if (expr == null || expr.isBlank()) return defaultMicros;
        String trimmed = expr.trim();

        Matcher m = REL.matcher(trimmed);
        if (m.matches()) {
            long delta = 0L;
            if (m.group(1) != null) {
                long n = Long.parseLong(m.group(1));
                delta = n * unitMicros(m.group(2));
            }
            return nowMicros - delta;
        }

        Instant t = Instant.parse(trimmed);
        return t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000L;
    }

    /** Microseconds in one {@code unit} of a relative expression ({@code s m h d w}). */
    private static long unitMicros(String unit) {
        return switch (unit) {
            case "s" -> 1_000_000L;
            case "m" -> 60_000_000L;
            case "h" -> 3_600_000_000L;
            case "d" -> 86_400_000_000L;
            case "w" -> 604_800_000_000L;
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    public static long defaultStartMicros() {
        return nowMicros() - 12L * 3_600_000_000L;
    }

    public static long defaultEndMicros() {
        return nowMicros();
    }

    /** Wall clock in epoch micros, the unit every range in this codebase uses. */
    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
