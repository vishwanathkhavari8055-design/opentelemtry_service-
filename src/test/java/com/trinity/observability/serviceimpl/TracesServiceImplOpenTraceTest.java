package com.trinity.observability.serviceimpl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.TraceLookupProperties;
import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.dto.traces.TraceTreeResponse;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.util.TimeRangeParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Open trace" drill-down: {@code GET /api/traces?traceId=…} with no time
 * range, which is what the packaged web-component build sends when a log row's
 * "Open trace" button is clicked.
 *
 * <p>Two regressions are pinned here, both of which produced a trace view that
 * rendered without erroring and was simply wrong:</p>
 *
 * <ul>
 *   <li>the request was paginated by SPAN, so a trace larger than one page lost
 *       its oldest spans — the root among them;</li>
 *   <li>the range defaulted to now-12h, so a trace referenced by an older log
 *       came back empty.</li>
 * </ul>
 */
class TracesServiceImplOpenTraceTest {

    private RecordingClient client;
    private TracesServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "host", "http://localhost:15080");
        setField(config, "org", "default");
        setField(config, "authToken", "Basic test");
        setField(config, "logsStream", "default");
        setField(config, "tracesStream", "default");

        // streamBounds null: these tests pin the CONFIGURED fallback behaviour. The
        // derived-window path has no OpenObserve metadata to read from here, and is
        // covered directly in TraceLookupWindowTest.
        service = new TracesServiceImpl(null, client, config,
                CatalogTelemetryFilter.permitAll(), new TraceLookupProperties(), null,
                new com.trinity.observability.util.RequestScopedFanout(2));
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void openingATraceFetchesItWholeRatherThanOnePageOfSpans() {
        client.tracesResponse = List.of(span("t1", "root", null), span("t1", "kid", "root"));

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("t1");
        // The UI's default page size. Before the fix this became the span cap, so a
        // trace with more spans than this was assembled from a slice of itself.
        req.setSize(20);

        List<TraceTreeResponse> out = service.getTraces(req);

        assertEquals(1, out.size());
        assertEquals(0, client.lastFrom.intValue(), "a named trace is never offset into");
        assertEquals(new TraceLookupProperties().getMaxSpans(), client.lastSize.intValue(),
                "the whole trace is requested, up to the configured safety cap — not req.getSize()");
    }

    @Test
    void openingATraceLooksFurtherBackThanTheDefaultTwelveHourWindow() {
        client.tracesResponse = List.of(span("t1", "root", null));

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("t1");
        service.getTraces(req);

        long twelveHoursAgo = TimeRangeParser.toMicros("now-12h", 0L);
        assertTrue(client.lastStart < twelveHoursAgo,
                "a log older than the default window must still be able to open its trace");
        assertTrue(client.lastSql.contains("AND trace_id = 't1'"), client.lastSql);
        assertTrue(client.lastSql.contains("AND _timestamp >= " + client.lastStart),
                "the SQL bounds must match the window sent on the request body: " + client.lastSql);
    }

    @Test
    void aRecentTraceOpensWithOneNarrowQuery() {
        // The slow path this replaces: the full-extent and 7d scans were tried first,
        // and this instance fails those after seconds, on every single open.
        Map<String, Object> root = span("t1", "root", null);
        root.put("_timestamp", TimeRangeParser.toMicros("now-10m", 0L));
        client.tracesResponse = List.of(root, span("t1", "kid", "root"));

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("t1");
        List<TraceTreeResponse> out = service.getTraces(req);

        assertEquals(1, out.size());
        assertEquals(1, client.traceQueryCount, "the root is in the narrowest window, so nothing wider is scanned");
        assertTrue(client.lastStart >= TimeRangeParser.toMicros("now-61m", 0L),
                "searched the narrowest configured window: " + client.lastStart);
    }

    @Test
    void emptyWiderWindowFallsBackToANarrowerOne() {
        // Mirrors this deployment's measured behaviour: a scan OpenObserve cannot
        // serve answers 200 with zero hits, indistinguishable from "no such trace".
        long cutoff = TimeRangeParser.toMicros("now-2h", 0L);
        client.searchHandler = (sql, type, start, size) -> {
            if (!"traces".equals(type)) return List.of();
            return start >= cutoff ? List.of(span("t1", "root", null)) : List.of();
        };

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("t1");
        List<TraceTreeResponse> out = service.getTraces(req);

        assertEquals(1, out.size(), "the trace is found once a window the instance can serve is tried");
        assertTrue(client.traceQueryCount > 1, "a root older than the window keeps the lookup widening");
    }

    @Test
    void anExplicitWindowKeepsPaginationAndTheCallersRange() {
        // The Traces screen filtering its own span table by a pasted trace id. It
        // always sends its toolbar range, and it must keep paging normally.
        client.tracesResponse = List.of(span("t1", "root", null));

        TraceRequestDTO req = new TraceRequestDTO();
        req.setTraceId("t1");
        req.setStartTime("now-30m");
        req.setPage(2);
        req.setSize(20);

        service.getTraces(req);

        assertEquals(40, client.lastFrom.intValue(), "page*size offset preserved");
        assertEquals(20, client.lastSize.intValue(), "caller's page size preserved");
        long thirtyOneMinutesAgo = TimeRangeParser.toMicros("now-31m", 0L);
        assertTrue(client.lastStart > thirtyOneMinutesAgo,
                "the caller's own window must win over the lookup ladder");
    }

    @Test
    void aListRequestWithNoTraceIdIsUnaffected() {
        client.tracesResponse = List.of(span("t1", "root", null));

        TraceRequestDTO req = new TraceRequestDTO();
        req.setPage(1);
        req.setSize(20);

        assertNotNull(service.getTraces(req));
        assertEquals(20, client.lastFrom.intValue(), "range queries keep paging by span");
        assertEquals(20, client.lastSize.intValue());
    }

    // ---------- helpers ----------

    private static Map<String, Object> span(String traceId, String spanId, String parentSpanId) {
        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp", 1778045072_000_000L);
        h.put("trace_id", traceId);
        h.put("span_id", spanId);
        h.put("reference_parent_span_id", parentSpanId);
        h.put("operation_name", "GET /work");
        h.put("service_name", "order-service");
        h.put("span_kind", "SPAN_KIND_SERVER");
        h.put("duration", 1_000L);
        return h;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @FunctionalInterface
    private interface SearchHandler {
        List<Map<String, Object>> handle(String sql, String streamType, long startMicros, int size);
    }

    /** Records the last TRACES search; log-correlation calls are ignored. */
    private static class RecordingClient implements OpenObserveClientService {
        String lastSql;
        Integer lastFrom;
        Integer lastSize;
        long lastStart;
        int traceQueryCount;
        List<Map<String, Object>> tracesResponse = new ArrayList<>();
        SearchHandler searchHandler;

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            if ("traces".equals(streamType)) {
                lastSql = sql;
                lastFrom = from;
                lastSize = size;
                lastStart = startMicros;
                traceQueryCount++;
            }
            if (searchHandler != null) return searchHandler.handle(sql, streamType, startMicros, size);
            return "traces".equals(streamType) ? tracesResponse : List.of();
        }

        @Override
        public JsonNode getJson(String path) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode sendJson(String method, String path, JsonNode body) {
            throw new UnsupportedOperationException("read-path test");
        }

        @Override
        public JsonNode deleteJson(String path) {
            throw new UnsupportedOperationException("read-path test");
        }

        @Override
        public JsonNode ingest(String org, String streamName, JsonNode records) {
            throw new UnsupportedOperationException("read-path test");
        }

        @Override
        public List<String> discoverServices() {
            return List.of();
        }

        @Override
        public List<String> listMetricStreams() {
            return List.of();
        }
    }

    /** Present only to document that the ES client must never be reached here. */
    @SuppressWarnings("unused")
    private static class EsClientNeverCalled implements ElasticsearchClientService {
        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> search(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                int from, int size, String sortField,
                co.elastic.clients.elasticsearch._types.SortOrder order, Class<T> clazz) {
            throw new AssertionError("ES must not be used when openObserveEnabled=true");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> searchWithAggregation(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs,
                int size, Class<T> clazz) {
            throw new AssertionError("ES must not be used when openObserveEnabled=true");
        }
    }
}
