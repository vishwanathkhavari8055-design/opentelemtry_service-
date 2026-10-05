package com.trinity.observability.util;

import com.trinity.observability.dto.traces.TraceResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OoTraceInterpreterTest {

    @Test
    void testNullArgsAreNoOps() {
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(null, Map.of(), dto);
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.HTTP_INCOMING, null, dto);
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.HTTP_INCOMING, Map.of(), null);
        // Nothing was set
        assertNull(dto.getType());
        assertNull(dto.getDirection());
    }

    @Test
    void testHttpIncomingPopulatesAllFields() {
        Map<String, Object> hit = Map.of(
            "http_request_method",       "GET",
            "http_response_status_code", "200",
            "url_full",                  "http://localhost:8080/work",
            "span_status",               "OK"
        );
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.HTTP_INCOMING, hit, dto);

        assertEquals("HTTP",                          dto.getType());
        assertEquals("INCOMING",                       dto.getDirection());
        assertEquals("GET",                            dto.getHttpMethod());
        assertEquals(Integer.valueOf(200),             dto.getHttpStatus());
        assertEquals("http://localhost:8080/work",     dto.getEndpoint());
        assertEquals("OK",                             dto.getStatus());
    }

    @Test
    void testHttpOutgoingDirection() {
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.HTTP_OUTGOING,
            Map.of("http_request_method", "POST"), dto);

        assertEquals("HTTP",     dto.getType());
        assertEquals("OUTGOING", dto.getDirection());
    }

    @Test
    void testDatabaseSpanPopulatesDbFields() {
        Map<String, Object> hit = Map.of(
            "db_system",             "mongodb",
            "db_statement",          "{ find: 'cluster' }",
            "db_mongodb_collection", "cluster",
            "db_name",               "iotops_cim",
            "span_status",           "OK"
        );
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.DATABASE, hit, dto);

        assertEquals("DATABASE",            dto.getType());
        assertEquals("OUTGOING",             dto.getDirection());
        assertEquals("mongodb",              dto.getDatabaseType());
        assertEquals("{ find: 'cluster' }",  dto.getDbStatement());
        assertEquals("cluster",              dto.getTarget());
        assertEquals("OK",                   dto.getStatus());
    }

    @Test
    void testDatabaseTargetFallback() {
        // No mongo collection, no sql_table — fall back to db_name
        Map<String, Object> hit = Map.of(
            "db_system", "mysql",
            "db_name",   "users_db"
        );
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.DATABASE, hit, dto);

        assertEquals("sql",      dto.getDatabaseType(), "mysql normalises to 'sql'");
        assertEquals("users_db", dto.getTarget());
    }

    @Test
    void testStatusCoalesceWhenSpanStatusMissing() {
        Map<String, Object> hit = new HashMap<>();
        hit.put("status_code", 0);
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.INTERNAL, hit, dto);

        assertEquals("code 0", dto.getStatus(),
            "Decision D3(c): span_status > formatted status_code > null");
    }

    @Test
    void testHttpStatusNonNumericLeavesNull() {
        Map<String, Object> hit = Map.of(
            "http_request_method",       "GET",
            "http_response_status_code", "not-a-number"
        );
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.HTTP_INCOMING, hit, dto);

        assertEquals("GET", dto.getHttpMethod());
        assertNull(dto.getHttpStatus(), "non-numeric httpStatus must not crash, just leaves null");
    }

    @Test
    void testEndpointFallbackToUrlPath() {
        Map<String, Object> hit = Map.of(
            "http_request_method", "GET",
            "url_path",            "/api/v1/orders"
        );
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.HTTP_INCOMING, hit, dto);

        assertEquals("/api/v1/orders", dto.getEndpoint());
    }

    @Test
    void testGraphqlType() {
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.GRAPHQL,
            Map.of("operation_name", "graphql.query.getUser"), dto);

        assertEquals("GRAPHQL", dto.getType());
        assertNull(dto.getDirection(), "GRAPHQL spans don't have a fixed direction");
    }

    @Test
    void testInternalType() {
        TraceResponseDTO dto = new TraceResponseDTO();
        OoTraceInterpreter.interpret(TraceClassifier.SpanType.INTERNAL, Map.of(), dto);
        assertEquals("INTERNAL", dto.getType());
        assertNull(dto.getDirection());
    }
}
