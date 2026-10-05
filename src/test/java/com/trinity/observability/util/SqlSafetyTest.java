package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSafetyTest {

    @Test
    void testResolveLogColumnHappyPath() {
        assertEquals("service_name", SqlSafety.resolveLogColumn("serviceName"));
        assertEquals("severity",     SqlSafety.resolveLogColumn("severity"));
        assertEquals("instrumentation_library_name", SqlSafety.resolveLogColumn("scopeName"));
        assertEquals("trace_id",     SqlSafety.resolveLogColumn("traceId"));
    }

    @Test
    void testResolveLogColumnUnknownThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> SqlSafety.resolveLogColumn("attackerInjectedField"));
        assertThrows(IllegalArgumentException.class,
            () -> SqlSafety.resolveLogColumn("severity_text"));
    }

    @Test
    void testResolveTraceColumnHappyPath() {
        assertEquals("trace_id",            SqlSafety.resolveTraceColumn("traceId"));
        assertEquals("span_kind",           SqlSafety.resolveTraceColumn("spanKind"));
        assertEquals("db_system",           SqlSafety.resolveTraceColumn("dbSystem"));
        assertEquals("http_request_method", SqlSafety.resolveTraceColumn("httpRequestMethod"));
    }

    @Test
    void testResolveMetricColumnHappyPath() {
        assertEquals("service_name", SqlSafety.resolveMetricColumn("serviceName"));
        assertEquals("host_name",    SqlSafety.resolveMetricColumn("hostName"));
    }

    @Test
    void testEscapeStringLiteralBasic() {
        assertEquals("foo",     SqlSafety.escapeStringLiteral("foo"));
        assertEquals("O''Brien", SqlSafety.escapeStringLiteral("O'Brien"));
        assertEquals("''",       SqlSafety.escapeStringLiteral("'"));
    }

    @Test
    void testEscapeStringLiteralBlocksInjectionAttempt() {
        String malicious = "foo'; DROP TABLE \"default\"; --";
        String escaped = SqlSafety.escapeStringLiteral(malicious);
        assertEquals("foo''; DROP TABLE \"default\"; --", escaped);
    }

    @Test
    void testEscapeStringLiteralNullThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> SqlSafety.escapeStringLiteral(null));
    }

    @Test
    void testValidateIdentifierHappyPath() {
        assertEquals("order-service",       SqlSafety.validateIdentifier("order-service"));
        assertEquals("IoTOpsCIMService",    SqlSafety.validateIdentifier("IoTOpsCIMService"));
        assertEquals("svc_v2.test",         SqlSafety.validateIdentifier("svc_v2.test"));
        assertEquals("a",                   SqlSafety.validateIdentifier("a"));
    }

    @Test
    void testValidateIdentifierRejectsDangerousChars() {
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier("foo'bar"));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier("foo bar"));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier("foo;bar"));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier("foo\"bar"));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier(""));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier(null));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.validateIdentifier("foo--bar"));
    }

    @Test
    void testEscapeLikePatternBasic() {
        assertEquals("foo", SqlSafety.escapeLikePattern("foo"));
    }

    @Test
    void testEscapeLikePatternEscapesWildcards() {
        assertEquals("foo\\%bar", SqlSafety.escapeLikePattern("foo%bar"));
        assertEquals("foo\\_bar", SqlSafety.escapeLikePattern("foo_bar"));
        assertEquals("100\\%",    SqlSafety.escapeLikePattern("100%"));
    }

    @Test
    void testEscapeLikePatternEscapesBackslash() {
        assertEquals("a\\\\b", SqlSafety.escapeLikePattern("a\\b"));
    }

    @Test
    void testEscapeLikePatternEscapesSingleQuote() {
        assertEquals("O''Brien", SqlSafety.escapeLikePattern("O'Brien"));
    }

    @Test
    void testEscapeLikePatternCombined() {
        String input = "100% O'Brien_data\\path";
        String expected = "100\\% O''Brien\\_data\\\\path";
        assertEquals(expected, SqlSafety.escapeLikePattern(input));
    }

    @Test
    void testEscapeLikePatternNullThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> SqlSafety.escapeLikePattern(null));
    }
}
