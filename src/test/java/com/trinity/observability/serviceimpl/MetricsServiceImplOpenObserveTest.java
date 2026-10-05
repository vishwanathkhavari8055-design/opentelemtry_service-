package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricRequestDTO;
import com.trinity.observability.dto.metrics.MetricResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsServiceImplOpenObserveTest {

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

        ElasticsearchClientService es = new EsClientThatNeverGetsCalled();
        service = new MetricsServiceImpl(es, client, config);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testMetricNameRequiredOnOpenObservePath() {
        MetricRequestDTO req = new MetricRequestDTO();
        req.setPage(0);
        req.setSize(50);
        // metricName not set → Decision D1(a): empty list, no client call

        List<MetricResponseDTO> out = service.getMetrics(req);
        assertNotNull(out);
        assertEquals(0, out.size());
        assertNull(client.lastSql.get(), "client must not be called when metricName is missing");
    }

    @Test
    void testSingleStreamSqlShape() {
        client.fixedResponse = List.of(metricHit(1778045072_000_000L, 256.5));

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("jvm_memory_used");
        req.setSize(20);
        service.getMetrics(req);

        String sql = client.lastSql.get();
        assertNotNull(sql);
        assertTrue(sql.contains("FROM \"jvm_memory_used\""), "uses metricName as stream: " + sql);
        assertTrue(sql.contains("SELECT _timestamp, value, service_name, host_name"), sql);
        assertTrue(sql.contains("ORDER BY _timestamp DESC"), sql);
        assertFalse(sql.contains("LIMIT"), "no SQL LIMIT — body from/size only: " + sql);
        assertEquals("metrics", client.lastStreamType.get());
        assertEquals(20, client.lastSize.get().intValue());
    }

    @Test
    void testServiceNameAndHostFiltersSerialised() {
        client.fixedResponse = List.of();

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("jvm_memory_used");
        req.setServiceName("order-service");
        req.setHostName("host-1");
        service.getMetrics(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("AND service_name = 'order-service'"), sql);
        assertTrue(sql.contains("AND host_name = 'host-1'"),            sql);
    }

    @Test
    void testServiceNameAllSkipsFilter() {
        client.fixedResponse = List.of();

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("jvm_memory_used");
        req.setServiceName("ALL");
        service.getMetrics(req);

        String sql = client.lastSql.get();
        assertFalse(sql.contains("AND service_name ="), "ALL must not produce a filter: " + sql);
    }

    @Test
    void testInjectionInMetricNameRejected() {
        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("evil'; DROP TABLE; --");
        try {
            service.getMetrics(req);
        } catch (IllegalArgumentException expected) {
            return;
        }
        // If it didn't throw, no SQL should have been issued
        assertNull(client.lastSql.get());
    }

    @Test
    void testMapperReadsValueAsDouble() {
        // Integer values — the (Number).doubleValue() defensive cast must not crash
        client.fixedResponse = List.of(metricHit(1778045072_000_000L, 42));

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("jvm_threads_live");

        List<MetricResponseDTO> out = service.getMetrics(req);
        assertEquals(1, out.size());
        assertEquals(Double.valueOf(42.0), out.get(0).getValue());
    }

    @Test
    void testMapperPreservesMicrosecondPrecision() {
        client.fixedResponse = List.of(metricHit(1778045072_123456L, 100.0));

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("disk_free");

        List<MetricResponseDTO> out = service.getMetrics(req);
        assertEquals("2026-05-06T05:24:32.123456Z", out.get(0).getTimestamp());
    }

    @Test
    void testMapperSetsNameFromStreamNotHit() {
        // Verify that even if the hit had something called metric_name, the mapper
        // ignores it and uses the FROM-clause stream name (V5 in r6 dissection).
        Map<String, Object> hit = metricHit(1L, 1.0);
        hit.put("metric_name", "WRONG");

        client.fixedResponse = List.of(hit);
        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("jvm_memory_used");

        List<MetricResponseDTO> out = service.getMetrics(req);
        assertEquals("jvm_memory_used", out.get(0).getName());
    }

    @Test
    void testListMetricStreams() {
        client.metricStreamsResponse = List.of("jvm_memory_used", "http_server_requests_seconds");

        List<String> streams = service.listMetricStreams();
        assertEquals(List.of("jvm_memory_used", "http_server_requests_seconds"), streams);
    }

    @Test
    void testNullValueSetsDtoValueNull() {
        Map<String, Object> hit = metricHit(1L, 1.0);
        hit.put("value", null);
        client.fixedResponse = List.of(hit);

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("disk_free");

        List<MetricResponseDTO> out = service.getMetrics(req);
        assertEquals(1, out.size());
        assertNull(out.get(0).getValue(), "null value column must produce null DTO field, not crash");
    }

    @Test
    void testNullTimestampLeavesIsoNull() {
        Map<String, Object> hit = new HashMap<>();
        hit.put("_timestamp", null);
        hit.put("value", 1.0);
        client.fixedResponse = List.of(hit);

        MetricRequestDTO req = new MetricRequestDTO();
        req.setMetricName("disk_free");

        List<MetricResponseDTO> out = service.getMetrics(req);
        assertEquals(1, out.size());
        assertNull(out.get(0).getTimestamp());
    }

    // ---------- helpers ----------

    private static Map<String, Object> metricHit(long ts, Number value) {
        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp",   ts);
        h.put("value",        value);
        h.put("service_name", "order-service");
        h.put("host_name",    "host-1");
        return h;
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

        final AtomicReference<String>  lastSql        = new AtomicReference<>();
        final AtomicReference<Integer> lastSize       = new AtomicReference<>();
        final AtomicReference<String>  lastStreamType = new AtomicReference<>();
        List<Map<String, Object>> fixedResponse = List.of();
        List<String> metricStreamsResponse = List.of();

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            lastSql.set(sql);
            lastSize.set(size);
            lastStreamType.set(streamType);
            return fixedResponse;
        }

        @Override
        public List<String> discoverServices() { return List.of(); }

        @Override
        public List<String> listMetricStreams() { return metricStreamsResponse; }

    }

    private static class EsClientThatNeverGetsCalled implements ElasticsearchClientService {
        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> search(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                int from, int size, String sortField,
                co.elastic.clients.elasticsearch._types.SortOrder order, Class<T> clazz) {
            throw new AssertionError("ES client must not be invoked on the OpenObserve path");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> searchWithAggregation(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                java.util.Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs,
                int size, Class<T> clazz) {
            throw new AssertionError("ES client must not be invoked on the OpenObserve path");
        }
    }
}
