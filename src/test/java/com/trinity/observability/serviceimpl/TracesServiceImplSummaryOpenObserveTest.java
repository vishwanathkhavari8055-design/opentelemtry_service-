package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.traces.TracesSummaryRequestDTO;
import com.trinity.observability.dto.traces.TracesSummaryResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Traces Summary aggregation path. Mirrors the structure of
 * {@link LogsServiceImplSummaryOpenObserveTest}.
 */
class TracesServiceImplSummaryOpenObserveTest {

    private RecordingClient client;
    private TracesServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "host",         "http://localhost:15080");
        setField(config, "org",          "default");
        setField(config, "authToken",    "Basic test");
        setField(config, "logsStream",   "default");
        setField(config, "tracesStream", "default");

        service = new TracesServiceImpl(new EsClientNeverCalled(), client, config);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testFirstSqlIsPerTraceAggregate() {
        client.responses.add(List.of());
        client.responses.add(List.of());
        service.getTracesSummary(new TracesSummaryRequestDTO());

        String sql = client.aggregateCall().sql;
        assertNotNull(sql);
        assertTrue(sql.contains("FROM \"default\""), sql);
        assertTrue(sql.contains("MAX(duration) AS root_duration_us"), sql);
        assertTrue(sql.contains("GROUP BY trace_id"), sql);
        assertTrue(sql.contains("span_status = 'ERROR'") || sql.contains("status_code = 'ERROR'"),
                "error detection in SQL: " + sql);
        assertEquals("traces", client.aggregateCall().streamType);
    }

    @Test
    void testSecondSqlIsSlowestOps() {
        client.responses.add(List.of());
        client.responses.add(List.of());
        service.getTracesSummary(new TracesSummaryRequestDTO());

        assertTrue(client.calls.size() >= 2);
        String sql = client.slowestOpsCall().sql;
        assertTrue(sql.contains("GROUP BY operation_name"), sql);
        assertTrue(sql.contains("ORDER BY MAX(duration) DESC"), sql);
        assertTrue(sql.contains("LIMIT 5"), sql);
    }

    @Test
    void testTraceStatsComputed() {
        // 5 traces; root_duration in µs → ms divisor 1000.
        // Durations: 100ms, 200ms, 300ms, 400ms, 500ms. Errors: 1 out of 5.
        client.responses.add(List.of(
            traceHit("t1", 100_000L, 0),
            traceHit("t2", 200_000L, 0),
            traceHit("t3", 300_000L, 1),
            traceHit("t4", 400_000L, 0),
            traceHit("t5", 500_000L, 0)
        ));
        client.responses.add(List.of());

        TracesSummaryResponseDTO out = service.getTracesSummary(new TracesSummaryRequestDTO());

        assertEquals(5L, out.getTraceCount());
        assertEquals(1L, out.getErrorCount());
        assertEquals(0.20, out.getErrorRate(), 0.001);
        assertEquals(300.0, out.getAvgLatencyMs(), 0.001);
        // Nearest-rank P95 of [100..500]: ceil(0.95*5)=5 → index 4 → 500 ms
        assertEquals(500.0, out.getP95LatencyMs(), 0.001);
    }

    @Test
    void testSlowestOpsMapped() {
        client.responses.add(List.of()); // empty trace aggregate
        client.responses.add(List.of(
            opHit("GET /work",       2_500_000L),
            opHit("find iotops_cim", 1_800_000L)
        ));

        TracesSummaryResponseDTO out = service.getTracesSummary(new TracesSummaryRequestDTO());
        assertEquals(2, out.getSlowestOps().size());
        assertEquals("GET /work",        out.getSlowestOps().get(0).getOperationName());
        assertEquals(2500.0,             out.getSlowestOps().get(0).getDurationMs(), 0.001);
        assertEquals("find iotops_cim",  out.getSlowestOps().get(1).getOperationName());
        assertEquals(1800.0,             out.getSlowestOps().get(1).getDurationMs(), 0.001);
    }

    @Test
    void testEmptyAggregateProducesZeros() {
        client.responses.add(List.of());
        client.responses.add(List.of());

        TracesSummaryResponseDTO out = service.getTracesSummary(new TracesSummaryRequestDTO());
        assertEquals(0L,  out.getTraceCount());
        assertEquals(0.0, out.getErrorRate(),    0.001);
        assertEquals(0.0, out.getAvgLatencyMs(), 0.001);
        assertEquals(0.0, out.getP95LatencyMs(), 0.001);
        assertTrue(out.getSlowestOps().isEmpty());
    }

    @Test
    void testSlowestOpsFailureDoesNotBreakResponse() {
        client.responses.add(List.of(traceHit("t1", 100_000L, 0)));
        client.failOnCall = 2;

        TracesSummaryResponseDTO out = service.getTracesSummary(new TracesSummaryRequestDTO());
        assertEquals(1L, out.getTraceCount());
        assertTrue(out.getSlowestOps().isEmpty(), "failed slowestOps query → empty list, not exception");
    }

    @Test
    void testEsBackendReturnsSupportedFalse() throws Exception {
        setField(service, "openObserveEnabled", false);
        TracesSummaryResponseDTO out = service.getTracesSummary(new TracesSummaryRequestDTO());
        assertFalse(out.isSupported());
        assertEquals(0L, out.getTraceCount());
        assertEquals("elasticsearch", out.getBackend());
        assertEquals(0, client.calls.size(), "ES path must not query OpenObserve");
    }

    // ---------- helpers ----------

    private static Map<String, Object> traceHit(String traceId, long durationUs, int isError) {
        Map<String, Object> m = new HashMap<>();
        m.put("trace_id",          traceId);
        m.put("root_duration_us",  durationUs);
        m.put("is_error",          isError);
        return m;
    }

    private static Map<String, Object> opHit(String name, long durUs) {
        Map<String, Object> m = new HashMap<>();
        m.put("operation_name", name);
        m.put("dur_us",         durUs);
        return m;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static class RecordingClient implements OpenObserveClientService {
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

        static class Call { String sql; String streamType; }
        final List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        /** Slot 0 answers the per-trace aggregate, slot 1 the slowest-ops query. The
         *  two run concurrently, so they are told apart by their SQL, not by order. */
        final List<List<Map<String, Object>>> responses = new ArrayList<>();
        /** 1 fails the aggregate, 2 fails the slowest-ops query. */
        int failOnCall = 0;

        private static int slotOf(String sql) {
            return sql.contains("GROUP BY operation_name") ? 1 : 0;
        }

        Call aggregateCall()   { return callInSlot(0); }
        Call slowestOpsCall()  { return callInSlot(1); }

        private Call callInSlot(int slot) {
            synchronized (calls) {
                return calls.stream().filter(c -> slotOf(c.sql) == slot).findFirst().orElse(null);
            }
        }

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            Call c = new Call();
            c.sql = sql; c.streamType = streamType;
            calls.add(c);
            int slot = slotOf(sql);
            if (failOnCall == slot + 1) {
                throw new RuntimeException("simulated stream error");
            }
            return slot < responses.size() ? responses.get(slot) : List.of();
        }
        @Override public List<String> discoverServices()  { return List.of(); }
        @Override public List<String> listMetricStreams() { return List.of(); }
    }

    private static class EsClientNeverCalled implements ElasticsearchClientService {
        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> search(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                int from, int size, String sortField,
                co.elastic.clients.elasticsearch._types.SortOrder order, Class<T> clazz) {
            throw new AssertionError("ES client must not be invoked");
        }
        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> searchWithAggregation(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                java.util.Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs,
                int size, Class<T> clazz) {
            throw new AssertionError("ES client must not be invoked");
        }
    }
}
