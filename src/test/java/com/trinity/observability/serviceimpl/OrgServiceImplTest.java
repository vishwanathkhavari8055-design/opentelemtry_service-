package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.org.OrgSummaryResponseDTO;
import com.trinity.observability.exception.OpenObserveQueryException;
import com.trinity.observability.util.RequestScopedFanout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OrgServiceImpl}, the Home screen's one call.
 *
 * <p>The behaviour under test is mostly about WHICH OpenObserve endpoints get
 * hit, not just what comes back. The five headline Streams figures used to be
 * summed from {@code GET /api/{org}/streams} — a 19 MB, ~10 second response on
 * an org with 19,735 streams, and the single reason the Home screen took 20+
 * seconds to paint. They now come from OpenObserve's own pre-aggregated
 * {@code /api/{org}/summary}, which this service was already calling for the
 * trigger-health bars. So "did not call /streams" is the assertion that keeps
 * the regression from coming back, and it is made explicitly below.</p>
 *
 * <p>The recording-client pattern (a hand-written double over the interface,
 * plus reflection for the {@code @Value} field) matches
 * {@link ServiceVitalsAggregatorTest}.</p>
 */
class OrgServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Everything the Home screen needs, in the shape the live instance returns. */
    private static final String FULL_SUMMARY = """
            {"streams":{"num_streams":19730,"total_records":495430420,
                        "total_storage_size":488729.94312000275,
                        "total_compressed_size":8298.444311141968,
                        "total_index_size":2887.0120096206665},
             "pipelines":{"num_realtime":0,"num_scheduled":138,
                          "trigger_status":{"healthy":133,"failed":0,"warning":138}},
             "alerts":{"num_realtime":306,"num_scheduled":0,
                       "trigger_status":{"healthy":7,"failed":1,"warning":2}},
             "total_functions":18,"total_dashboards":69}
            """;

    private RecordingClient client;
    private OrgServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "org", "default");
        service = new OrgServiceImpl(client, config, new RequestScopedFanout(8));
        setField(service, "openObserveEnabled", true);
    }

    // ── The fast path ────────────────────────────────────────────────────────

    @Test
    void testStreamFiguresComeFromTheOrgSummary() {
        client.on("/api/default/summary", FULL_SUMMARY);

        OrgSummaryResponseDTO out = service.getOrgSummary();
        OrgSummaryResponseDTO.Streams s = out.getStreams();

        assertEquals(19730, s.getCount());
        assertEquals(495430420L, s.getEvents());
        // Sizes arrive as MEGABYTES and go out as raw bytes.
        assertEquals(Math.round(488729.94312000275 * 1024 * 1024), s.getIngestedBytes());
        assertEquals(Math.round(8298.444311141968 * 1024 * 1024), s.getCompressedBytes());
        assertEquals(Math.round(2887.0120096206665 * 1024 * 1024), s.getIndexBytes());
    }

    /** The regression guard: the 19 MB walk must not be on the request path. */
    @Test
    void testStreamsEndpointIsNotCalledWhenTheSummaryAnswers() {
        client.on("/api/default/summary", FULL_SUMMARY);

        service.getOrgSummary();

        assertFalse(client.paths().contains("/api/default/streams"),
                "the per-stream walk must not run when /summary carried the totals; called: "
                        + client.paths());
    }

    @Test
    void testTriggerHealthStillComesBack() {
        client.on("/api/default/summary", FULL_SUMMARY);

        OrgSummaryResponseDTO out = service.getOrgSummary();

        OrgSummaryResponseDTO.Health alerts = out.getAlerts().getHealth();
        assertNotNull(alerts);
        assertEquals(7, alerts.getHealthy());
        assertEquals(1, alerts.getFailed());
        assertEquals(2, alerts.getWarning());
        assertEquals(133, out.getPipelines().getHealth().getHealthy());
    }

    /** Counts and `items` still come from the list endpoints, unchanged. */
    @Test
    void testCountsFromTheListEndpointsAreUntouched() {
        client.on("/api/default/summary", FULL_SUMMARY);
        client.on("/api/default/functions", "{\"list\":[{},{},{}]}");
        client.on("/api/default/dashboards", "{\"dashboards\":[{},{}]}");
        client.on("/api/v2/default/alerts",
                "{\"list\":[{\"name\":\"a\",\"is_real_time\":true},{\"name\":\"b\"}]}");
        client.on("/api/default/pipelines",
                "{\"list\":[{\"name\":\"p\",\"source\":{\"source_type\":\"realtime\"}}]}");

        OrgSummaryResponseDTO out = service.getOrgSummary();

        assertEquals(3, out.getFunctions().getCount());
        assertEquals(2, out.getDashboards().getCount());
        assertEquals(1, out.getAlerts().getRealTime());
        assertEquals(1, out.getAlerts().getScheduled());
        assertEquals(2, out.getAlerts().getItems().size());
        assertEquals(1, out.getPipelines().getRealTime());
        assertEquals(0, out.getPipelines().getScheduled());
    }

    // ── The degraded paths ───────────────────────────────────────────────────

    /** An OpenObserve whose summary has no stream block still gets its figures. */
    @Test
    void testFallsBackToSummingStreamsWhenTheSummaryHasNone() {
        client.on("/api/default/summary",
                "{\"alerts\":{\"trigger_status\":{\"healthy\":1,\"failed\":0,\"warning\":0}}}");
        client.on("/api/default/streams", """
                {"list":[{"stats":{"doc_num":100,"storage_size":1.5,
                                   "compressed_size":0.5,"index_size":0.25}},
                         {"stats":{"doc_num":200,"storage_size":2.5,
                                   "compressed_size":0.5,"index_size":0.25}}]}
                """);

        OrgSummaryResponseDTO out = service.getOrgSummary();

        assertTrue(client.paths().contains("/api/default/streams"),
                "the fallback walk must run when /summary carried no stream totals");
        assertEquals(2, out.getStreams().getCount());
        assertEquals(300L, out.getStreams().getEvents());
        assertEquals(Math.round(4.0 * 1024 * 1024), out.getStreams().getIngestedBytes());
    }

    /** Both reads failing leaves nulls, never zeros — "we don't know" ≠ "none". */
    @Test
    void testStreamFiguresStayNullWhenBothReadsFail() {
        // Nothing registered: every path throws.
        OrgSummaryResponseDTO out = service.getOrgSummary();

        assertTrue(out.isSupported());
        assertNull(out.getStreams().getCount());
        assertNull(out.getStreams().getEvents());
        assertNull(out.getStreams().getIngestedBytes());
        assertNull(out.getFunctions().getCount());
        assertNull(out.getAlerts().getHealth());
    }

    @Test
    void testEsBackendReturnsSupportedFalse() throws Exception {
        setField(service, "openObserveEnabled", false);

        OrgSummaryResponseDTO out = service.getOrgSummary();

        assertFalse(out.isSupported());
        assertEquals("elasticsearch", out.getBackend());
        assertTrue(client.paths().isEmpty(), "the ES path must not call OpenObserve");
    }

    // ── Doubles ──────────────────────────────────────────────────────────────

    // ── The summary cache ────────────────────────────────────────────────────

    /** A repeat visit inside the TTL is served from memory, with the same figures. */
    @Test
    void testRepeatVisitIsServedFromTheCache() throws Exception {
        setField(service, "cacheSeconds", 60);
        client.on("/api/default/summary", FULL_SUMMARY);

        OrgSummaryResponseDTO first = service.getOrgSummary();
        int callsAfterFirst = client.paths().size();
        OrgSummaryResponseDTO second = service.getOrgSummary();

        assertEquals(callsAfterFirst, client.paths().size(),
                "the second visit must not call OpenObserve again; called: " + client.paths());
        assertEquals(first.getStreams().getCount(), second.getStreams().getCount());
        assertEquals("default", second.getOrg());
    }

    /** An outage is not a summary: it is returned, and the next visit asks again. */
    @Test
    void testAllFailedSummaryIsNotCached() throws Exception {
        setField(service, "cacheSeconds", 60);

        OrgSummaryResponseDTO first = service.getOrgSummary();
        assertNull(first.getStreams().getCount());
        assertTrue(first.isSupported());

        client.on("/api/default/summary", FULL_SUMMARY);
        OrgSummaryResponseDTO second = service.getOrgSummary();
        assertEquals(19730, second.getStreams().getCount());
    }

    /**
     * Answers only the paths a test registered, and remembers every path asked
     * for. An unregistered path throws, which is what the live client does for a
     * non-2xx — and is how the "leave it null" branches get exercised.
     */
    private static final class RecordingClient implements OpenObserveClientService {

        private final Map<String, String> bodies = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> asked = new CopyOnWriteArrayList<>();

        void on(String path, String json) { bodies.put(path, json); }

        List<String> paths() { return asked; }

        @Override
        public JsonNode getJson(String path) {
            asked.add(path);
            String body = bodies.get(path);
            if (body == null) {
                throw new OpenObserveQueryException(404, "no stub for " + path, null);
            }
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public JsonNode sendJson(String method, String path, JsonNode body) {
            throw new UnsupportedOperationException();
        }

        @Override public JsonNode deleteJson(String path) {
            throw new UnsupportedOperationException();
        }

        @Override public JsonNode ingest(String org, String streamName, JsonNode records) {
            throw new UnsupportedOperationException();
        }

        @Override public List<Map<String, Object>> search(String sql, long startMicros,
                long endMicros, int from, int size, String streamType) {
            return Collections.emptyList();
        }

        @Override public List<String> discoverServices() { return Collections.emptyList(); }

        @Override public List<String> listMetricStreams() { return Collections.emptyList(); }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
