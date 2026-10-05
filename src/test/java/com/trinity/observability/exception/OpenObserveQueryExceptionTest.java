package com.trinity.observability.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenObserveQueryExceptionTest {

    @Test
    void testFieldsArePreserved() {
        OpenObserveQueryException ex = new OpenObserveQueryException(
            20004, "Search field not found", "019dfbe0dd317b319fdd5dca919fcce0");

        assertEquals(20004, ex.getCode());
        assertEquals("019dfbe0dd317b319fdd5dca919fcce0", ex.getOpenObserveTraceId());
    }

    @Test
    void testMessageIncludesAllContext() {
        OpenObserveQueryException ex = new OpenObserveQueryException(
            20004, "Search field not found", "abc123");

        String msg = ex.getMessage();
        assertTrue(msg.contains("20004"),                 "Message should include code: " + msg);
        assertTrue(msg.contains("abc123"),                "Message should include OO trace_id: " + msg);
        assertTrue(msg.contains("Search field not found"), "Message should include cause: " + msg);
    }

    @Test
    void testIsInstanceOfObservabilityException() {
        OpenObserveQueryException ex = new OpenObserveQueryException(
            500, "Internal error", "trace-1");

        assertTrue(ex instanceof ObservabilityException,
            "OpenObserveQueryException must extend ObservabilityException for GlobalExceptionHandler to catch it");
    }

    @Test
    void testCauseConstructor() {
        Throwable cause = new RuntimeException("upstream timeout");
        OpenObserveQueryException ex = new OpenObserveQueryException(
            504, "Gateway timeout", "trace-2", cause);

        assertSame(cause, ex.getCause());
        assertNotNull(ex.getMessage());
    }
}
