package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OoTraceClassifierTest {

    @Test
    void testNullHitReturnsInternal() {
        assertEquals(TraceClassifier.SpanType.INTERNAL, OoTraceClassifier.classify(null));
    }

    @Test
    void testEmptyHitReturnsInternal() {
        assertEquals(TraceClassifier.SpanType.INTERNAL, OoTraceClassifier.classify(new HashMap<>()));
    }

    @Test
    void testDbSystemPresentReturnsDatabase() {
        Map<String, Object> hit = Map.of("db_system", "mongodb",
                                         "span_kind", "SPAN_KIND_CLIENT");
        // db_system wins over span_kind
        assertEquals(TraceClassifier.SpanType.DATABASE, OoTraceClassifier.classify(hit));
    }

    @Test
    void testServerSpanKindReturnsHttpIncoming() {
        Map<String, Object> hit = Map.of("span_kind", "SPAN_KIND_SERVER");
        assertEquals(TraceClassifier.SpanType.HTTP_INCOMING, OoTraceClassifier.classify(hit));
    }

    @Test
    void testClientSpanKindWithHttpMethodReturnsHttpOutgoing() {
        Map<String, Object> hit = Map.of("span_kind",          "SPAN_KIND_CLIENT",
                                         "http_request_method", "GET");
        assertEquals(TraceClassifier.SpanType.HTTP_OUTGOING, OoTraceClassifier.classify(hit));
    }

    @Test
    void testClientSpanKindWithoutHttpMethodReturnsInternal() {
        // CLIENT but no HTTP signal — could be a non-HTTP outbound (gRPC, queue, etc.)
        Map<String, Object> hit = Map.of("span_kind", "SPAN_KIND_CLIENT");
        assertEquals(TraceClassifier.SpanType.INTERNAL, OoTraceClassifier.classify(hit));
    }

    @Test
    void testGraphQlOperationName() {
        Map<String, Object> hit = Map.of("operation_name", "graphql.query.getUser",
                                         "span_kind",      "SPAN_KIND_INTERNAL");
        assertEquals(TraceClassifier.SpanType.GRAPHQL, OoTraceClassifier.classify(hit));
    }

    @Test
    void testGraphQlPathInOperationName() {
        Map<String, Object> hit = Map.of("operation_name", "POST /graphql");
        assertEquals(TraceClassifier.SpanType.GRAPHQL, OoTraceClassifier.classify(hit));
    }

    @Test
    void testInternalSpanKind() {
        Map<String, Object> hit = Map.of("span_kind", "SPAN_KIND_INTERNAL",
                                         "operation_name", "doStuff");
        assertEquals(TraceClassifier.SpanType.INTERNAL, OoTraceClassifier.classify(hit));
    }

    // ---------- resolveDbTechnology ----------

    @Test
    void testResolveDbTechnologyMongo() {
        assertEquals("mongodb", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "mongodb")));
        assertEquals("mongodb", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "MongoDB")));
    }

    @Test
    void testResolveDbTechnologyRedis() {
        assertEquals("redis", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "redis")));
    }

    @Test
    void testResolveDbTechnologyNeo4j() {
        assertEquals("neo4j", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "neo4j")));
    }

    @Test
    void testResolveDbTechnologyMssql() {
        assertEquals("mssql", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "mssql")));
        assertEquals("mssql", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "sqlserver")));
    }

    @Test
    void testResolveDbTechnologyOracle() {
        assertEquals("oracle", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "oracle")));
        assertEquals("oracle", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "oracledb")));
    }

    @Test
    void testResolveDbTechnologySqlFamily() {
        assertEquals("sql", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "mysql")));
        assertEquals("sql", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "postgresql")));
        assertEquals("sql", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "mariadb")));
        assertEquals("sql", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "h2")));
    }

    @Test
    void testResolveDbTechnologyUnknownPasses() {
        // Unknown DBs pass through lowercased — Phase F is forward-compatible.
        assertEquals("cassandra", OoTraceClassifier.resolveDbTechnology(Map.of("db_system", "Cassandra")));
    }

    @Test
    void testResolveDbTechnologyMissingReturnsNull() {
        assertNull(OoTraceClassifier.resolveDbTechnology(Map.of()));
        assertNull(OoTraceClassifier.resolveDbTechnology(null));
    }
}
