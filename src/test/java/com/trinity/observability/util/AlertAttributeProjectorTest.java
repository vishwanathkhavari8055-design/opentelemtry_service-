package com.trinity.observability.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class AlertAttributeProjectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void nullOrNonObjectYieldsEmpty() throws Exception {
        assertTrue(AlertAttributeProjector.project(null, null, 0).isEmpty());
        assertTrue(AlertAttributeProjector.project(json("[1,2]"), null, 0).isEmpty());
    }

    @Test
    void splitsValuesAndLabelsAndSkipsNoise() throws Exception {
        JsonNode payload = json("{"
                + "\"_timestamp\": 123,"
                + "\"\": \"blank\","
                + "\"  CPU \": \"82.5\","
                + "\"mem\": 40,"
                + "\"pod\": \"svc-1\","
                + "\"Description\": \"long text\","
                + "\"nested\": {\"a\": 1},"
                + "\"arr\": [1],"
                + "\"nul\": null,"
                + "\"nulltext\": \"NULL\","
                + "\"empty\": \"  \","
                + "\"inf\": \"Infinity\","
                + "\"list\": \"82.7, 81.7\""
                + "}");
        var attrs = AlertAttributeProjector.project(payload, List.of(" description ", ""), 0);
        assertEquals(Map.of("cpu", 82.5, "mem", 40.0), attrs.currentValues());
        assertEquals(Map.of("pod", "svc-1", "inf", "Infinity", "list", "82.7, 81.7"),
                attrs.labels());
        assertEquals(List.of("cpu", "mem"), List.copyOf(attrs.currentValues().keySet()));
        assertEquals(List.of("inf", "list", "pod"), List.copyOf(attrs.labels().keySet()));
    }

    @Test
    void capAppliesAcrossBothMaps() throws Exception {
        JsonNode payload = json("{\"a\": 1, \"b\": \"x\", \"c\": 2, \"d\": \"y\"}");
        var attrs = AlertAttributeProjector.project(payload, null, 3);
        assertEquals(Map.of("a", 1.0, "c", 2.0), attrs.currentValues());
        assertEquals(Map.of("b", "x"), attrs.labels());
    }

    @Test
    void longKeysAndValuesAreTrimmed() throws Exception {
        String longKey = "k".repeat(200);
        String longVal = "v".repeat(600);
        JsonNode payload = json("{\"" + longKey + "\": \"" + longVal + "\"}");
        var attrs = AlertAttributeProjector.project(payload, null, 0);
        var e = attrs.labels().entrySet().iterator().next();
        assertEquals(128, e.getKey().length());
        assertEquals(512, e.getValue().length());
    }
}
