package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeParserTest {

    @Test
    void testNullReturnsDefault() {
        assertEquals(42L, TimeRangeParser.toMicros(null, 42L));
    }

    @Test
    void testBlankReturnsDefault() {
        assertEquals(42L, TimeRangeParser.toMicros("   ", 42L));
        assertEquals(42L, TimeRangeParser.toMicros("", 42L));
    }

    @Test
    void testNowReturnsCurrentMicros() {
        long before = System.currentTimeMillis() * 1_000L;
        long parsed = TimeRangeParser.toMicros("now", 0L);
        long after  = System.currentTimeMillis() * 1_000L;
        assertTrue(parsed >= before, "now should be >= before-call wall clock");
        assertTrue(parsed <= after + 1_000_000L, "now should not be too far in the future");
    }

    @Test
    void testNowMinusOneHour() {
        long now    = TimeRangeParser.toMicros("now",    0L);
        long oneAgo = TimeRangeParser.toMicros("now-1h", 0L);
        long delta  = now - oneAgo;
        long oneHour = 3_600_000_000L;
        assertTrue(Math.abs(delta - oneHour) < 100_000L,
            "Delta should be ~1h, got " + delta + " µs");
    }

    @Test
    void testNowMinusVariousUnits() {
        long now = TimeRangeParser.toMicros("now", 0L);

        assertEqualsWithin(now - 30_000_000L,            TimeRangeParser.toMicros("now-30s", 0L), 200_000L);
        assertEqualsWithin(now - 5L * 60_000_000L,       TimeRangeParser.toMicros("now-5m",  0L), 200_000L);
        assertEqualsWithin(now - 24L * 3_600_000_000L,   TimeRangeParser.toMicros("now-24h", 0L), 200_000L);
        assertEqualsWithin(now - 7L * 86_400_000_000L,   TimeRangeParser.toMicros("now-7d",  0L), 200_000L);
        assertEqualsWithin(now - 2L * 604_800_000_000L,  TimeRangeParser.toMicros("now-2w",  0L), 200_000L);
    }

    @Test
    void testIsoAbsolute() {
        assertEquals(1778045072_000_000L, TimeRangeParser.toMicros("2026-05-06T05:24:32Z", 0L));
    }

    @Test
    void testIsoWithSubSecondPrecision() {
        assertEquals(1778045072_123456L, TimeRangeParser.toMicros("2026-05-06T05:24:32.123456Z", 0L));
    }

    @Test
    void testInvalidExpressionThrows() {
        assertThrows(java.time.format.DateTimeParseException.class,
            () -> TimeRangeParser.toMicros("yesterday", 0L));
        assertThrows(java.time.format.DateTimeParseException.class,
            () -> TimeRangeParser.toMicros("2026-05-06", 0L));
    }

    @Test
    void testDefaultStartAndEndAre12HoursApart() {
        long start = TimeRangeParser.defaultStartMicros();
        long end   = TimeRangeParser.defaultEndMicros();
        long delta = end - start;
        long twelveHours = 12L * 3_600_000_000L;
        assertTrue(Math.abs(delta - twelveHours) < 1_000_000L,
            "Default range should be ~12h, got " + delta + " µs");
    }

    @Test
    void testAnchoredRelativeSpansExactlyWhatItNames() {
        // A caller that captured "end" and then resolves a relative start against it
        // must get a span of exactly the named width, however long it took to get
        // there. Resolving against a fresh clock read instead loses the elapsed time,
        // and a consumer that buckets the span by whole minutes then reads a rung low
        // -- 15m arriving as 14m59.999s. One microsecond of drift is enough.
        for (String rung : new String[] {"now-5m", "now-15m", "now-30m", "now-1h"}) {
            long end = TimeRangeParser.defaultEndMicros();
            // Let the wall clock move past the captured end before resolving.
            while (TimeRangeParser.defaultEndMicros() == end) {
                Thread.onSpinWait();
            }
            long span = end - TimeRangeParser.toMicros(rung, end, end);
            assertEquals(expectedMicros(rung), span,
                    rung + " should span exactly what it names, got " + span + " µs");
        }
    }

    @Test
    void testAnchoredResolutionIgnoresTheWallClock() {
        // The anchor is the only "now" that matters, so the result is reproducible
        // rather than a function of when the test happened to run.
        long anchor = 1778045072_000_000L;
        assertEquals(anchor, TimeRangeParser.toMicros("now", 0L, anchor));
        assertEquals(anchor - 15L * 60_000_000L,
                TimeRangeParser.toMicros("now-15m", 0L, anchor));
        // An absolute timestamp is unaffected by the anchor, and a blank expression
        // still falls back to the default rather than to the anchor.
        assertEquals(1778045072_000_000L,
                TimeRangeParser.toMicros("2026-05-06T05:24:32Z", 0L, anchor));
        assertEquals(42L, TimeRangeParser.toMicros("  ", 42L, anchor));
    }

    private static long expectedMicros(String rung) {
        switch (rung) {
            case "now-5m":  return 5L * 60_000_000L;
            case "now-15m": return 15L * 60_000_000L;
            case "now-30m": return 30L * 60_000_000L;
            case "now-1h":  return 3_600_000_000L;
            default: throw new IllegalArgumentException(rung);
        }
    }

    private static void assertEqualsWithin(long expected, long actual, long tolerance) {
        assertTrue(Math.abs(expected - actual) < tolerance,
            "expected " + expected + " ± " + tolerance + ", got " + actual);
    }
}
