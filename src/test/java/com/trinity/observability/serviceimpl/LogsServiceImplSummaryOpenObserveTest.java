package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.logs.LogsSummaryRequestDTO;
import com.trinity.observability.dto.logs.LogsSummaryResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Logs Summary aggregation path. Kept in its own class so the
 * pre-existing {@code LogsServiceImplOpenObserveTest} stays focused on the
 * paged-list and trace-group behaviour.
 */
class LogsServiceImplSummaryOpenObserveTest {

    private RecordingClient client;
    private LogsServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "host",         "http://localhost:15080");
        setField(config, "org",          "default");
        setField(config, "authToken",    "Basic test");
        setField(config, "logsStream",   "default");
        setField(config, "tracesStream", "default");

        service = new LogsServiceImpl(new EsClientNeverCalled(), client, config);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testSummarySqlShape() {
        client.fixedResponse = List.of();
        service.getLogsSummary(new LogsSummaryRequestDTO());

        String sql = client.lastSql.get();
        assertNotNull(sql);
        assertTrue(sql.contains("FROM \"default\""), "uses configured logs stream: " + sql);
        assertTrue(sql.contains("severity AS sev"), sql);
        assertTrue(sql.contains("GROUP BY 1, 2"), sql);
        assertEquals("logs", client.lastStreamType.get());
    }

    @Test
    void testPivotProducesPerSeverityAndTopErrorServices() {
        client.fixedResponse = List.of(
            row("ERROR", "order-service",  12L),
            row("ERROR", "api-gateway",    20L),
            row("WARN",  "order-service",   3L),
            row("INFO",  "order-service", 100L),
            row("FATAL", "api-gateway",     2L),
            row("DEBUG", "order-service",   1L)
        );

        LogsSummaryResponseDTO out = service.getLogsSummary(new LogsSummaryRequestDTO());
        assertEquals(138L, out.getTotalCount());

        Map<String, Long> bySev = out.getCountsBySeverity();
        assertEquals(Long.valueOf(32L), bySev.get("ERROR"));
        assertEquals(Long.valueOf(3L),  bySev.get("WARN"));
        assertEquals(Long.valueOf(100L), bySev.get("INFO"));
        assertEquals(Long.valueOf(2L),  bySev.get("FATAL"));
        assertEquals(Long.valueOf(1L),  bySev.get("DEBUG"));
        assertEquals(Long.valueOf(0L),  bySev.get("TRACE"));

        // Error totals: api-gateway = 20+2 = 22, order-service = 12 — api-gateway leads.
        assertEquals(2, out.getTopErrorServices().size());
        assertEquals("api-gateway",   out.getTopErrorServices().get(0).getServiceName());
        assertEquals(22L,             out.getTopErrorServices().get(0).getErrorCount());
        assertEquals("order-service", out.getTopErrorServices().get(1).getServiceName());
    }

    @Test
    void testTopErrorServicesCappedAtFive() {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hits.add(row("ERROR", "svc-" + i, (long) (10 + i)));
        }
        client.fixedResponse = hits;

        LogsSummaryResponseDTO out = service.getLogsSummary(new LogsSummaryRequestDTO());
        assertEquals(5, out.getTopErrorServices().size());
        // Top entry should be svc-9 with 19 errors.
        assertEquals("svc-9", out.getTopErrorServices().get(0).getServiceName());
        assertEquals(19L,     out.getTopErrorServices().get(0).getErrorCount());
    }

    @Test
    void testUnknownSeverityFoldedUnderUnknownBucket() {
        client.fixedResponse = List.of(row("notice", "svc-x", 7L));

        LogsSummaryResponseDTO out = service.getLogsSummary(new LogsSummaryRequestDTO());
        assertEquals(7L, out.getTotalCount());
        assertEquals(Long.valueOf(7L), out.getCountsBySeverity().get("UNKNOWN"));
    }

    @Test
    void testEsBackendReturnsSupportedFalse() throws Exception {
        setField(service, "openObserveEnabled", false);
        LogsSummaryResponseDTO out = service.getLogsSummary(new LogsSummaryRequestDTO());
        assertFalse(out.isSupported());
        assertEquals(0L, out.getTotalCount());
        assertEquals("elasticsearch", out.getBackend());
    }

    // ---------- helpers ----------

    private static Map<String, Object> row(String sev, String svc, long n) {
        Map<String, Object> m = new HashMap<>();
        m.put("sev",          sev);
        m.put("service_name", svc);
        m.put("n",            n);
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

        final AtomicReference<String> lastSql        = new AtomicReference<>();
        final AtomicReference<String> lastStreamType = new AtomicReference<>();
        List<Map<String, Object>> fixedResponse = List.of();

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            lastSql.set(sql);
            lastStreamType.set(streamType);
            return fixedResponse;
        }
        @Override public List<String> discoverServices()   { return List.of(); }
        @Override public List<String> listMetricStreams()  { return List.of(); }
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
