package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeFormatterTest {

    @Test
    void testEpochMicros() {
        assertEquals("1970-01-01T00:00:00Z", TimeFormatter.microsToIso(0L));
    }

    @Test
    void testWholeSecond() {
        assertEquals("2026-05-06T05:24:32Z", TimeFormatter.microsToIso(1778045072_000_000L));
    }

    @Test
    void testSubSecondPrecisionPreserved() {
        String iso = TimeFormatter.microsToIso(1778045072_123456L);
        assertEquals("2026-05-06T05:24:32.123456Z", iso);
    }

    @Test
    void testSingleMicrosecond() {
        String iso = TimeFormatter.microsToIso(1778045072_000001L);
        assertTrue(iso.endsWith(".000001Z"), "Expected sub-second precision, got: " + iso);
    }

    @Test
    void testRealTimestampFromOpenObserve() {
        String iso = TimeFormatter.microsToIso(1778047130_081764L);
        assertEquals("2026-05-06T05:58:50.081764Z", iso);
    }
}
