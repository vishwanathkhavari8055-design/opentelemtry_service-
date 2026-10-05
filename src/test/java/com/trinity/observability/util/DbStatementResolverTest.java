package com.trinity.observability.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DbStatementResolverTest {

    @Test
    void deepScanFindsNestedStatementInMapsAndLists() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("other", "x");
        inner.put("My_Query", "?");
        inner.put("sqlStatement", "SELECT 1");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("irrelevant", "SELECT 0");
        source.put("items", List.of("str", List.of(Map.of("k", 1)), inner));
        assertEquals("SELECT 1", DbStatementResolver.find(source));
    }

    @Test
    void deepScanReturnsNullWhenNothingNonTrivial() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("operation", "ping");
        source.put("nested", Map.of("statement", "null"));
        source.put("num", 5);
        assertNull(DbStatementResolver.find(source));
    }

    @Test
    void deepScanSkipsNestedNullResultAndContinues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", Map.of("x", "y"));
        source.put("b", List.of());
        source.put("dbOperation", "HGETALL");
        assertEquals("HGETALL", DbStatementResolver.find(source));
    }
}
