package com.trinity.observability.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.dto.traces.TraceLogEvent;
import com.trinity.observability.dto.traces.TraceTreeNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceLogCorrelatorOpenObserveTest {

    /** Minimal stub that implements all 3 interface methods. Tests pass a lambda for `search`. */
    private static OpenObserveClientService stub(
            Function<SearchArgs, List<Map<String, Object>>> searchFn) {
        return new OpenObserveClientService() {
            // getJson was added to OpenObserveClientService for the org, IAM,
            // trace-analytics and PromQL endpoints. These doubles exercise the
            // search() paths only, so an empty object satisfies the contract
            // without handing callers a null to dereference.
            @Override
            public JsonNode getJson(String path) {
                return JsonNodeFactory.instance.objectNode();
            }

            // Write-path methods, added to OpenObserveClientService for the alerts API
            // and the alert-event stream. Every double here exercises READ paths only,
            // so a write arriving at one means the code under test took a path it was
            // not meant to. Throwing surfaces that; returning an empty node would let
            // the test pass while hiding it.
            @Override
            public JsonNode sendJson(String method, String path, JsonNode body) {
                throw new UnsupportedOperationException("sendJson is not expected on this path");
            }

            @Override
            public JsonNode deleteJson(String path) {
                throw new UnsupportedOperationException("deleteJson is not expected on this path");
            }

            @Override
            public JsonNode ingest(String org, String streamName, JsonNode records) {
                throw new UnsupportedOperationException("ingest is not expected on this path");
            }

            @Override
            public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                    int from, int size, String streamType) {
                return searchFn.apply(new SearchArgs(sql, startMicros, endMicros, from, size, streamType));
            }
            @Override public List<String> discoverServices() { return List.of(); }
            @Override public List<String> listMetricStreams() { return List.of(); }
        };
    }

    private static final class SearchArgs {
        final String sql;
        final long startMicros, endMicros;
        final int from, size;
        final String streamType;
        SearchArgs(String sql, long s, long e, int f, int sz, String t) {
            this.sql = sql; this.startMicros = s; this.endMicros = e;
            this.from = f; this.size = sz; this.streamType = t;
        }
    }

    @Test
    void testCorrelateAttachesEventsToRoot() {
        TraceTreeNode root = newNode("root-span", "order-service", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");

        OpenObserveClientService client = stub(args -> List.of(
            Map.of(
                "_timestamp",   1778045072_100_000L,
                "body",         "Processed order",
                "severity",     "INFO",
                "trace_id",     "abc",
                "span_id",      "root-span",
                "service_name", "order-service"
            ),
            Map.of(
                "_timestamp",   1778045072_200_000L,
                "body",         "Order failed",
                "severity",     "ERROR",
                "trace_id",     "abc",
                "span_id",      "root-span",
                "service_name", "order-service"
            )
        ));

        TraceLogCorrelator.correlate("abc", root, client, "default", 0L, 9_000_000_000_000_000L);

        assertNotNull(root.getEvents());
        assertEquals(2, root.getEvents().size());
        assertEquals("INFO",  root.getEvents().get(0).getSeverity());
        assertEquals("ERROR", root.getEvents().get(1).getSeverity());
        assertEquals("Processed order", root.getEvents().get(0).getMessage());
    }

    @Test
    void testSqlIncludesSeverityColumn() {
        TraceTreeNode root = newNode("root", "svc", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");
        AtomicReference<String> capturedSql = new AtomicReference<>();

        OpenObserveClientService client = stub(args -> {
            capturedSql.set(args.sql);
            return List.of();
        });

        TraceLogCorrelator.correlate("trace-1", root, client, "default", 100L, 200L);

        String sql = capturedSql.get();
        assertNotNull(sql);
        assertTrue(sql.contains("severity"), "SQL must select severity: " + sql);
        assertTrue(!sql.contains("level"),   "SQL must not select level: "  + sql);
        assertTrue(sql.contains("trace_id = 'trace-1'"), "SQL must filter by trace_id: " + sql);
        assertTrue(sql.contains("FROM \"default\""),     "SQL must use the configured stream: " + sql);
        assertTrue(sql.contains("ORDER BY _timestamp ASC"), "SQL must sort ascending: " + sql);
        assertTrue(sql.contains("_timestamp >= 100"),    "SQL must include lower bound: " + sql);
        assertTrue(sql.contains("_timestamp <= 200"),    "SQL must include upper bound: " + sql);
    }

    @Test
    void testEmptyResultSetEnsuresEventsListExists() {
        TraceTreeNode root = newNode("root", "svc", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");

        OpenObserveClientService client = stub(args -> List.of());

        TraceLogCorrelator.correlate("trace-empty", root, client, "default", 0L, 1L);

        assertNotNull(root.getEvents(), "Events list should be initialised even on empty result");
        assertEquals(0, root.getEvents().size());
    }

    @Test
    void testNullTraceIdIsNoOp() {
        TraceTreeNode root = newNode("root", "svc", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        OpenObserveClientService client = stub(args -> {
            called.set(true);
            return List.of();
        });

        TraceLogCorrelator.correlate(null, root, client, "default", 0L, 1L);
        TraceLogCorrelator.correlate("",   root, client, "default", 0L, 1L);

        assertEquals(false, called.get(), "Client should not be called for null/empty traceId");
    }

    @Test
    void testEmptyBodyHitIsSkipped() {
        TraceTreeNode root = newNode("root", "svc", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");

        OpenObserveClientService client = stub(args -> {
            List<Map<String, Object>> hits = new ArrayList<>();
            Map<String, Object> emptyBody = new java.util.HashMap<>();
            emptyBody.put("_timestamp", 1778045072_000_000L);
            emptyBody.put("body",       "");
            emptyBody.put("severity",   "INFO");
            emptyBody.put("trace_id",   "abc");
            emptyBody.put("span_id",    "root");
            hits.add(emptyBody);

            Map<String, Object> nullBody = new java.util.HashMap<>();
            nullBody.put("_timestamp", 1778045072_100_000L);
            nullBody.put("body",       null);
            nullBody.put("severity",   "INFO");
            nullBody.put("trace_id",   "abc");
            nullBody.put("span_id",    "root");
            hits.add(nullBody);

            Map<String, Object> good = new java.util.HashMap<>();
            good.put("_timestamp", 1778045072_200_000L);
            good.put("body",       "real message");
            good.put("severity",   "INFO");
            good.put("trace_id",   "abc");
            good.put("span_id",    "root");
            hits.add(good);

            return hits;
        });

        TraceLogCorrelator.correlate("abc", root, client, "default", 0L, 9_000_000_000_000_000L);

        assertEquals(1, root.getEvents().size());
        assertEquals("real message", root.getEvents().get(0).getMessage());
    }

    @Test
    void testInvalidStreamNameRejected() {
        TraceTreeNode root = newNode("root", "svc", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");

        OpenObserveClientService client = stub(args -> List.of());

        // SqlSafety.validateIdentifier rejects this — should be caught and logged, not propagate
        TraceLogCorrelator.correlate("trace-1", root, client, "evil'; DROP TABLE; --", 0L, 1L);

        // Still ensures events list (caught exception path)
        assertNotNull(root.getEvents());
        assertEquals(0, root.getEvents().size());
    }

    @Test
    void testTimestampConversionPreservesPrecision() {
        TraceTreeNode root = newNode("root", "svc", "2026-05-06T05:24:00Z", "2026-05-06T05:24:01Z");

        OpenObserveClientService client = stub(args -> List.of(
            Map.of(
                "_timestamp",   1778045072_123456L,
                "body",         "precision-check",
                "severity",     "INFO",
                "trace_id",     "abc",
                "span_id",      "root",
                "service_name", "svc"
            )
        ));

        TraceLogCorrelator.correlate("abc", root, client, "default", 0L, 9_000_000_000_000_000L);

        assertEquals(1, root.getEvents().size());
        TraceLogEvent e = root.getEvents().get(0);
        assertEquals("2026-05-06T05:24:32.123456Z", e.getTimestamp());
    }

    private TraceTreeNode newNode(String spanId, String serviceName, String startTime, String endTime) {
        TraceTreeNode n = new TraceTreeNode();
        n.setSpanId(spanId);
        n.setServiceName(serviceName);
        n.setStartTime(startTime);
        n.setEndTime(endTime);
        n.setTimestamp(startTime);
        return n;
    }
}
