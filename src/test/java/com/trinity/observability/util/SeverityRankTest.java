package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeverityRankTest {

    @Test
    void allReturnsCanonicalOrder() {
        assertEquals(List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"),
            SeverityRank.all());
    }

    @Test
    void atLeastWarnIncludesWarnErrorFatal() {
        List<String> levels = SeverityRank.atLeast("WARN");
        assertEquals(List.of("WARN", "ERROR", "FATAL"), levels);
    }

    @Test
    void atLeastInfoIncludesInfoWarnErrorFatal() {
        List<String> levels = SeverityRank.atLeast("INFO");
        assertEquals(List.of("INFO", "WARN", "ERROR", "FATAL"), levels);
    }

    @Test
    void atLeastTraceIncludesAll() {
        assertEquals(SeverityRank.all(), SeverityRank.atLeast("TRACE"));
    }

    @Test
    void atLeastFatalIsJustFatal() {
        assertEquals(List.of("FATAL"), SeverityRank.atLeast("FATAL"));
    }

    @Test
    void atLeastIsCaseInsensitive() {
        assertEquals(SeverityRank.atLeast("WARN"), SeverityRank.atLeast("warn"));
        assertEquals(SeverityRank.atLeast("WARN"), SeverityRank.atLeast(" Warn "));
    }

    @Test
    void unknownSeverityFallsBackToItself() {
        // Defensive: unknown severities filter to themselves rather than
        // returning everything (which would be a security/UX surprise).
        List<String> levels = SeverityRank.atLeast("ALERT");
        assertEquals(List.of("ALERT"), levels);
    }

    @Test
    void exactReturnsSingleElement() {
        assertEquals(List.of("WARN"), SeverityRank.exact("warn"));
    }

    @Test
    void nullInputsReturnEmpty() {
        assertTrue(SeverityRank.atLeast(null).isEmpty());
        assertTrue(SeverityRank.exact(null).isEmpty());
    }
}
