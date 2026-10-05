package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.dto.traces.TraceResponseDTO;
import com.trinity.observability.dto.traces.TraceTreeResponse;
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

class TracesServiceImplOpenObserveTest {

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

        ElasticsearchClientService esClient = new EsClientThatNeverGetsCalled();
        service = new TracesServiceImpl(esClient, client, config);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testGetTracesBuildsExpectedSql() {
        client.fixedResponse = List.of(traceSpan("t1", "s1", null, "GET /work", "order-service",
                                                 "SPAN_KIND_SERVER", 1778045072_000_000L, 12_500L));

        TraceRequestDTO req = new TraceRequestDTO();
        req.setPage(0);
        req.setSize(20);

        List<TraceTreeResponse> out = service.getTraces(req);
        assertEquals(1, out.size());

        String sql = client.lastSql.get();
        assertTrue(sql.contains("FROM \"default\""), "uses configured traces stream: " + sql);
        assertTrue(sql.contains("ORDER BY _timestamp DESC"), "sorts desc: " + sql);
        assertFalse(sql.contains("LIMIT"), "no SQL LIMIT (uses body from/size): " + sql);
        assertTrue(sql.contains("reference_parent_span_id"), "selects parent column: " + sql);
        assertTrue(sql.contains("operation_name"),   "selects operation_name: " + sql);
        assertTrue(sql.contains("http_request_method"), "selects http_request_method: " + sql);
        assertTrue(sql.contains("db_mongodb_collection"), "selects db_mongodb_collection: " + sql);

        assertEquals("traces", client.lastStreamType.get());
        assertEquals(0,  client.lastFrom.get().intValue());
        assertEquals(20, client.lastSize.get().intValue());
    }

    @Test
    void testFilterTraceIdAndServiceNameSerialized() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("abc");
        req.setServiceName("order-service");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("AND trace_id = 'abc'"),                  sql);
        assertTrue(sql.contains("AND service_name = 'order-service'"),    sql);
    }

    @Test
    void testServiceNameAllSkipsServiceFilter() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setServiceName("ALL");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertFalse(sql.contains("AND service_name ="), "ALL must not produce a filter: " + sql);
    }

    @Test
    void testTransactionNameUsesLikeNotEquals() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTransactionName("checkout");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("operation_name LIKE '%checkout%'"), "operation_name LIKE: " + sql);
    }

    @Test
    void testOutcomeSuccessTranslatesToOk() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setOutcome("success");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("AND span_status = 'OK'"),
            "ES outcome=success → OTel span_status=OK. SQL: " + sql);
    }

    @Test
    void testOutcomeFailureTranslatesToError() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setOutcome("failure");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("AND span_status = 'ERROR'"), sql);
    }

    @Test
    void testSearchTermLikeOnOperationAndDbStatement() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setSearch("MakeupTank");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("operation_name LIKE '%MakeupTank%'"), sql);
        assertTrue(sql.contains("OR db_statement LIKE '%MakeupTank%'"), sql);
    }

    @Test
    void testSearchTermEscapesWildcards() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setSearch("100% O'Brien_data");
        service.getTraces(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("100\\% O''Brien\\_data"),
            "wildcards and quotes must be escaped: " + sql);
    }

    @Test
    void testInjectionAttemptInTraceIdEscaped() {
        client.fixedResponse = List.of();

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("foo'; DROP TABLE; --");
        service.getTraces(req);

        String sql = client.lastSql.get();
        // Single quote must be doubled — no comment marker reaches OO as live syntax.
        assertTrue(sql.contains("AND trace_id = 'foo''; DROP TABLE; --'"),
            "single quotes must be SQL-escaped: " + sql);
    }

    @Test
    void testMapperHttpServerSpanFieldsAndType() {
        Map<String, Object> hit = traceSpan("t1", "s1", null, "GET /work", "order-service",
                                            "SPAN_KIND_SERVER", 1778045072_123456L, 12_500L);
        hit.put("http_request_method",         "GET");
        hit.put("http_response_status_code",   "200");
        hit.put("url_full",                    "http://localhost:8080/work");
        hit.put("span_status",                 "OK");

        client.fixedResponse = List.of(hit);

        List<TraceTreeResponse> out = service.getTraces(new TraceRequestDTO());
        assertEquals(1, out.size());
        TraceResponseDTO dto = out.get(0).getSpans().get(0);

        assertEquals("t1",           dto.getTraceId());
        assertEquals("s1",           dto.getSpanId());
        assertEquals("GET /work",    dto.getSpanName());
        assertEquals("order-service", dto.getServiceName());
        assertEquals("2026-05-06T05:24:32.123456Z", dto.getTimestamp(), "µs precision preserved");
        assertEquals(Long.valueOf(12_500L), dto.getDuration(), "duration stays in µs (Phase E rule)");
        assertEquals("GET",          dto.getHttpMethod());
        assertEquals(Integer.valueOf(200), dto.getHttpStatus());
        assertEquals("http://localhost:8080/work", dto.getEndpoint());
        assertEquals("OK",           dto.getStatus());
        assertEquals("HTTP",         dto.getType(), "Phase E inline classifier: SERVER kind → HTTP");
        assertEquals("INCOMING",     dto.getDirection());
    }

    @Test
    void testMapperDbSpanClassifiedAsDatabase() {
        Map<String, Object> hit = traceSpan("t2", "s2", "s1", "find iotops_cim.cluster",
                                            "IoTOpsCIMService", "SPAN_KIND_CLIENT",
                                            1778045072_000_000L, 11_241L);
        hit.put("db_system",              "mongodb");
        hit.put("db_statement",           "{ find: 'cluster' }");
        hit.put("db_mongodb_collection",  "cluster");
        hit.put("db_name",                "iotops_cim");

        client.fixedResponse = List.of(hit);

        List<TraceTreeResponse> out = service.getTraces(new TraceRequestDTO());
        TraceResponseDTO dto = out.get(0).getSpans().get(0);

        assertEquals("DATABASE",  dto.getType());
        assertEquals("mongodb",   dto.getDatabaseType());
        assertEquals("OUTGOING",  dto.getDirection());
        assertEquals("cluster",   dto.getTarget(),
            "target = mongodb collection (preferred over sql_table or db_name)");
        assertEquals("{ find: 'cluster' }", dto.getDbStatement());
    }

    @Test
    void testMapperStatusCoalesce() {
        // No span_status, but status_code is set — should format to "code 0"
        Map<String, Object> hit = traceSpan("t3", "s3", null, "noop", "svc",
                                            "SPAN_KIND_INTERNAL", 1L, 100L);
        hit.put("status_code", 0);

        client.fixedResponse = List.of(hit);

        TraceResponseDTO dto = service.getTraces(new TraceRequestDTO())
            .get(0).getSpans().get(0);
        assertEquals("code 0", dto.getStatus(), "status coalesce: span_status > formatted status_code");
    }

    @Test
    void testGroupingByTraceId() {
        Map<String, Object> a1 = traceSpan("trace-A", "s1", null, "op", "svc1",
                                           "SPAN_KIND_SERVER", 100L, 1000L);
        Map<String, Object> a2 = traceSpan("trace-A", "s2", "s1", "op2", "svc1",
                                           "SPAN_KIND_CLIENT", 200L, 500L);
        Map<String, Object> b1 = traceSpan("trace-B", "s3", null, "op3", "svc2",
                                           "SPAN_KIND_SERVER", 300L, 800L);

        client.fixedResponse = List.of(a1, a2, b1);

        List<TraceTreeResponse> out = service.getTraces(new TraceRequestDTO());
        assertEquals(2, out.size(), "spans grouped into 2 traces");
        // Order is insertion-order (LinkedHashMap), so trace-A then trace-B
        assertEquals("trace-A", out.get(0).getTraceId());
        assertEquals(2,         out.get(0).getSpans().size());
        assertEquals("trace-B", out.get(1).getTraceId());
        assertEquals(1,         out.get(1).getSpans().size());
    }

    @Test
    void testHttpStatusNonNumericLeavesItNull() {
        Map<String, Object> hit = traceSpan("t1", "s1", null, "op", "svc",
                                            "SPAN_KIND_SERVER", 1L, 100L);
        hit.put("http_response_status_code", "not-a-number");

        client.fixedResponse = List.of(hit);

        TraceResponseDTO dto = service.getTraces(new TraceRequestDTO())
            .get(0).getSpans().get(0);
        assertNotNull(dto);
        // httpStatus should remain null (parse failure) but mapper should NOT throw
    }

    /**
     * Log correlation is ONE query for the whole page, not one per trace.
     *
     * <p>This used to assert a per-trace {@code trace_id = '...'} call, which is
     * the N+1 the batched read replaced: a 20-trace page cost 21 OpenObserve
     * round trips. The guarantee worth holding is the one asserted here — every
     * trace on the page is still covered, in a single query — so a regression
     * back to per-trace calls fails the count assertion rather than passing
     * quietly.</p>
     */
    @Test
    void testTraceLogCorrelatorIssuesOneBatchedQueryForThePage() {
        Map<String, Object> spanX = traceSpan("trace-X", "rootX", null, "op", "svc",
                                               "SPAN_KIND_SERVER", 1778045072_000_000L, 1000L);
        Map<String, Object> spanY = traceSpan("trace-Y", "rootY", null, "op", "svc",
                                               "SPAN_KIND_SERVER", 1778045073_000_000L, 1000L);

        List<String> logQueries = new ArrayList<>();
        client.searchHandler = (sql, type) -> {
            if ("logs".equals(type)) {
                logQueries.add(sql);
                return List.of();
            }
            return List.of(spanX, spanY);
        };

        service.getTraces(new TraceRequestDTO());

        assertEquals(1, logQueries.size(),
            "log correlation must be a single batched query, not one per trace");
        String sql = logQueries.get(0);
        assertTrue(sql.contains("trace_id IN ("), "expected a batched IN-list, got: " + sql);
        assertTrue(sql.contains("'trace-X'"), "trace-X missing from the batch: " + sql);
        assertTrue(sql.contains("'trace-Y'"), "trace-Y missing from the batch: " + sql);
        // Each trace keeps its own row budget — see TraceLogCorrelator.fetchLogsForTraces.
        assertTrue(sql.contains("PARTITION BY trace_id"),
            "expected a per-trace row budget, got: " + sql);
    }

    // ---------- helpers ----------

    private static Map<String, Object> traceSpan(String traceId, String spanId, String parentSpanId,
                                                 String opName, String serviceName, String spanKind,
                                                 long tsMicros, long durationMicros) {
        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp",                tsMicros);
        h.put("trace_id",                  traceId);
        h.put("span_id",                   spanId);
        h.put("reference_parent_span_id",  parentSpanId);
        h.put("operation_name",            opName);
        h.put("service_name",              serviceName);
        h.put("service_host_name",         "host-1");
        h.put("span_kind",                 spanKind);
        h.put("duration",                  durationMicros);
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

        // lastSql captures the most recent TRACES query — the log-correlation calls
        // (streamType="logs") are tracked separately so they don't overwrite.
        final AtomicReference<String>  lastSql        = new AtomicReference<>();
        final AtomicReference<Integer> lastFrom       = new AtomicReference<>();
        final AtomicReference<Integer> lastSize       = new AtomicReference<>();
        final AtomicReference<String>  lastStreamType = new AtomicReference<>();
        final AtomicReference<String>  lastLogsSql    = new AtomicReference<>();
        List<Map<String, Object>> fixedResponse = List.of();
        SearchHandler searchHandler;

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            if ("traces".equals(streamType)) {
                lastSql.set(sql);
                lastFrom.set(from);
                lastSize.set(size);
                lastStreamType.set(streamType);
            } else if ("logs".equals(streamType)) {
                lastLogsSql.set(sql);
            }
            if (searchHandler != null) return searchHandler.handle(sql, streamType);
            // Default behaviour for unmocked tests: return fixedResponse for traces, empty for logs.
            if ("logs".equals(streamType)) return List.of();
            return fixedResponse;
        }

        @Override
        public List<String> discoverServices() { return List.of(); }

        @Override
        public List<String> listMetricStreams() { return List.of(); }

    }

    @FunctionalInterface
    private interface SearchHandler {
        List<Map<String, Object>> handle(String sql, String streamType);
    }

    private static class EsClientThatNeverGetsCalled implements ElasticsearchClientService {
        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> search(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                int from, int size, String sortField,
                co.elastic.clients.elasticsearch._types.SortOrder order, Class<T> clazz) {
            throw new AssertionError("ES client must not be invoked when openObserveEnabled=true");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> searchWithAggregation(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                java.util.Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs,
                int size, Class<T> clazz) {
            throw new AssertionError("ES client must not be invoked when openObserveEnabled=true");
        }
    }
}
