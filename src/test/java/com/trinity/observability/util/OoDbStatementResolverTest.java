package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OoDbStatementResolverTest {

    @Test
    void testNullHitReturnsNull() {
        assertNull(OoDbStatementResolver.find(null));
    }

    @Test
    void testEmptyHitReturnsNull() {
        assertNull(OoDbStatementResolver.find(new HashMap<>()));
    }

    @Test
    void testDbStatementPreferred() {
        Map<String, Object> hit = Map.of(
            "db_statement",   "{ find: 'cluster' }",
            "db_operation",   "find",
            "operation_name", "find iotops_cim.cluster"
        );
        assertEquals("{ find: 'cluster' }", OoDbStatementResolver.find(hit));
    }

    @Test
    void testFallbackToDbOperation() {
        Map<String, Object> hit = Map.of(
            "db_operation",   "find",
            "operation_name", "find iotops_cim.cluster"
        );
        assertEquals("find", OoDbStatementResolver.find(hit));
    }

    @Test
    void testFallbackToOperationNameIfQueryShaped() {
        // operation_name starts with "find" — query-like
        Map<String, Object> hit = Map.of("operation_name", "find iotops_cim.cluster");
        assertEquals("find iotops_cim.cluster", OoDbStatementResolver.find(hit));
    }

    @Test
    void testOperationNameWithSelect() {
        Map<String, Object> hit = Map.of("operation_name", "SELECT id FROM users");
        assertEquals("SELECT id FROM users", OoDbStatementResolver.find(hit));
    }

    @Test
    void testOperationNameNotQueryShapeReturnsNull() {
        // "GET /work" doesn't match the query keyword set
        Map<String, Object> hit = Map.of("operation_name", "POST /api/v1/users");
        assertNull(OoDbStatementResolver.find(hit));
    }

    @Test
    void testCypherMatchClause() {
        Map<String, Object> hit = Map.of("operation_name", "MATCH (n:User) RETURN n");
        assertEquals("MATCH (n:User) RETURN n", OoDbStatementResolver.find(hit));
    }

    @Test
    void testEmptyDbStatementFallsThrough() {
        Map<String, Object> hit = new HashMap<>();
        hit.put("db_statement", "   ");
        hit.put("db_operation", "find");
        assertEquals("find", OoDbStatementResolver.find(hit));
    }

    @Test
    void testNonStringDbStatementFallsThrough() {
        Map<String, Object> hit = new HashMap<>();
        hit.put("db_statement", 12345);   // unexpected non-string value
        hit.put("db_operation", "exec");
        assertEquals("exec", OoDbStatementResolver.find(hit));
    }
}
