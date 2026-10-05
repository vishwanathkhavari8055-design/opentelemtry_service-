package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricsSummaryRequestDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryResponseDTO;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Metrics Summary sparkline aggregation path.
 */
class MetricsServiceImplSummaryOpenObserveTest {

    private RecordingClient client;
    private MetricsServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "host",         "http://localhost:15080");
        setField(config, "org",          "default");
        setField(config, "authToken",    "Basic test");
        setField(config, "logsStream",   "default");
        setField(config, "tracesStream", "default");

        service = new MetricsServiceImpl(new EsClientNeverCalled(), client, config);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testFourSparklineQueriesIssued() {
        // All four sparkline streams should be queried in order.
        for (int i = 0; i < 4; i++) client.responses.add(List.of());

        service.getMetricsSummary(reqFor("IoTOpsService"));

        assertEquals(4, client.calls.size());
        assertTrue(client.calls.get(0).sql.contains("FROM \"jvm_memory_used\""));
        assertTrue(client.calls.get(0).sql.contains("jvm_memory_type = 'heap'"),
                   "jvmHeap series filters to heap memory type");
        assertTrue(client.calls.get(1).sql.contains("FROM \"jvm_cpu_recent_utilization\""));
        assertTrue(client.calls.get(2).sql.contains("FROM \"jvm_thread_count\""));
        assertTrue(client.calls.get(3).sql.contains("FROM \"http_server_request_duration_count\""));
        for (RecordingClient.Call c : client.calls) {
            assertTrue(c.sql.contains("DATE_BIN(INTERVAL '60 seconds'"),
                       "default bucket is 60s: " + c.sql);
            assertEquals("metrics", c.streamType);
        }
    }

    @Test
    void testCustomBucketSecAppliedToAllQueries() {
        for (int i = 0; i < 4; i++) client.responses.add(List.of());

        MetricsSummaryRequestDTO req = reqFor("IoTOpsService");
        req.setBucketSec(15);
        service.getMetricsSummary(req);

        for (RecordingClient.Call c : client.calls) {
            assertTrue(c.sql.contains("DATE_BIN(INTERVAL '15 seconds'"),
                       "custom bucket propagated: " + c.sql);
        }
    }

    @Test
    void testSparklinesPopulatedFromHits() {
        client.responses.add(List.of(
            bucketHit("2026-05-11T10:00:00Z", 100.0),
            bucketHit("2026-05-11T10:01:00Z", 120.0),
            bucketHit("2026-05-11T10:02:00Z", 110.0)
        ));
        // The other three sparklines have no data.
        for (int i = 0; i < 3; i++) client.responses.add(List.of());

        MetricsSummaryResponseDTO out = service.getMetricsSummary(reqFor("IoTOpsService"));

        assertTrue(out.getSparklines().containsKey("jvmHeap"));
        assertEquals(3, out.getSparklines().get("jvmHeap").size());
        assertEquals(120.0, out.getSparklines().get("jvmHeap").get(1).getValue(), 0.001);

        // Latest sample picks up the last point in order.
        assertEquals(Double.valueOf(110.0), out.getLatestValues().get("jvmHeap"));

        // Series with no data must not appear (per response contract).
        assertNull(out.getSparklines().get("cpuUsage"));
        assertNull(out.getLatestValues().get("cpuUsage"));
    }

    @Test
    void testFailedStreamSilentlySkipped() {
        // Fail the first call (jvmHeap); the remaining three return data.
        // RecordingClient indexes responses by call number — the failed call
        // still consumes its slot, so we feed a placeholder at index 0.
        client.failOnCall = 1;
        client.responses.add(List.of()); // index 0 — never returned (throws)
        for (int i = 0; i < 3; i++) {
            client.responses.add(List.of(bucketHit("2026-05-11T10:00:00Z", 1.0)));
        }

        MetricsSummaryResponseDTO out = service.getMetricsSummary(reqFor("IoTOpsService"));
        // jvmHeap absent, other three present.
        assertNull(out.getSparklines().get("jvmHeap"));
        assertEquals(3, out.getSparklines().size());
    }

    @Test
    void testEsBackendReturnsSupportedFalse() throws Exception {
        setField(service, "openObserveEnabled", false);
        MetricsSummaryResponseDTO out = service.getMetricsSummary(reqFor("IoTOpsService"));

        assertFalse(out.isSupported());
        assertEquals(0, out.getSparklines().size());
        assertEquals("elasticsearch", out.getBackend());
        assertEquals(0, client.calls.size(), "ES path must not query OpenObserve");
    }

    @Test
    void testWindowAndBucketMetadataEchoed() {
        for (int i = 0; i < 4; i++) client.responses.add(List.of());

        MetricsSummaryRequestDTO req = reqFor("IoTOpsService");
        req.setWindow("now-15m");
        req.setBucketSec(30);
        MetricsSummaryResponseDTO out = service.getMetricsSummary(req);

        assertNotNull(out.getWindowStart());
        assertNotNull(out.getWindowEnd());
        assertEquals(30, out.getBucketSec());
        assertEquals("openobserve", out.getBackend());
    }

    @Test
    void testServiceNameAppliedAsFilterToEveryQuery() {
        for (int i = 0; i < 4; i++) client.responses.add(List.of());

        service.getMetricsSummary(reqFor("IoTOpsService"));

        // service_name (underscore — the metrics-stream column name written by
        // OpenObserve from the OTel `service.name` resource attribute) must
        // appear in the WHERE clause of every sparkline query.
        for (RecordingClient.Call c : client.calls) {
            assertTrue(c.sql.contains("service_name = 'IoTOpsService'"),
                       "per-service filter present in: " + c.sql);
        }
        // jvmHeap still keeps its jvm_memory_type=heap filter alongside the service filter.
        assertTrue(client.calls.get(0).sql.contains("jvm_memory_type = 'heap'"),
                   "jvmHeap retains memory-type filter: " + client.calls.get(0).sql);
    }

    @Test
    void testServiceNameSingleQuoteEscaped() {
        // SqlSafety.escapeStringLiteral doubles single quotes — confirm the
        // request can't inject SQL via the service name.
        for (int i = 0; i < 4; i++) client.responses.add(List.of());

        service.getMetricsSummary(reqFor("Bobby'Tables"));

        for (RecordingClient.Call c : client.calls) {
            assertTrue(c.sql.contains("service_name = 'Bobby''Tables'"),
                       "single quote escaped: " + c.sql);
        }
    }

    @Test
    void testMissingServiceNameReturnsEmptyWithoutQuerying() {
        // No serviceName → bail out with empty payload, no queries issued
        // (avoids the fleet-wide average that's operationally meaningless).
        MetricsSummaryResponseDTO out = service.getMetricsSummary(new MetricsSummaryRequestDTO());

        assertTrue(out.isSupported(), "supported stays true — feature is available, just gated on input");
        assertEquals(0, out.getSparklines().size());
        assertEquals(0, out.getLatestValues().size());
        assertEquals(0, client.calls.size(),
                     "no SQL queries issued when serviceName is missing");
    }

    @Test
    void testBlankServiceNameTreatedAsMissing() {
        MetricsSummaryResponseDTO out = service.getMetricsSummary(reqFor("   "));

        assertTrue(out.isSupported());
        assertEquals(0, out.getSparklines().size());
        assertEquals(0, client.calls.size());
    }

    // ---------- helpers ----------

    private static MetricsSummaryRequestDTO reqFor(String serviceName) {
        MetricsSummaryRequestDTO r = new MetricsSummaryRequestDTO();
        r.setServiceName(serviceName);
        return r;
    }

    private static Map<String, Object> bucketHit(String iso, double v) {
        Map<String, Object> m = new HashMap<>();
        m.put("bucket", iso);
        m.put("v",      v);
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
        final List<Call> calls = new ArrayList<>();
        final List<List<Map<String, Object>>> responses = new ArrayList<>();
        int failOnCall = 0;

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            Call c = new Call();
            c.sql = sql; c.streamType = streamType;
            calls.add(c);
            if (failOnCall == calls.size()) {
                throw new RuntimeException("simulated stream missing");
            }
            int idx = calls.size() - 1;
            return idx < responses.size() ? responses.get(idx) : List.of();
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
