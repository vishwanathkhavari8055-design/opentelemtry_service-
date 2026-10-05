package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.services.ServiceVitalsRequestDTO;
import com.trinity.observability.dto.services.ServiceVitalsResponseDTO;
import com.trinity.observability.util.RequestScopedFanout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ServiceVitalsAggregator}. Targets the aggregator
 * directly rather than going through {@link ServicesServiceImpl}, because the
 * aggregator owns the entire vitals pipeline — splitting the test to match
 * the production split keeps each class' tests aligned with its single
 * objective. The recording-client pattern is shared with
 * {@link ServicesServiceImplOpenObserveTest}.
 *
 * <p>Call order in {@code aggregate(serviceName, request)}:</p>
 * <ol>
 *   <li>{@code http_server_requests_seconds_count} (all statuses) — req rate</li>
 *   <li>same stream, {@code status LIKE '5%'} — error rate</li>
 *   <li>{@code http_server_requests_seconds_max} — latency</li>
 *   <li>{@code hikaricp_connections_active} — DB pool</li>
 *   <li>{@code jvm_memory_used} (area='heap') — heap</li>
 *   <li>{@code logback_events_total} (level='ERROR') — error logs</li>
 *   <li>{@code http_client_requests_seconds_count} — downstream calls</li>
 *   <li>{@code http_client_requests_seconds_max} — downstream latency (optional)</li>
 * </ol>
 */
class ServiceVitalsAggregatorTest {

    private RecordingClient client;
    private ServiceVitalsAggregator aggregator;

    @BeforeEach
    void setUp() throws Exception {
        client     = new RecordingClient();
        aggregator = new ServiceVitalsAggregator(client, new RequestScopedFanout(4));
        setField(aggregator, "openObserveEnabled", true);
    }

    @Test
    void testEsBackendReturnsSupportedFalse() throws Exception {
        setField(aggregator, "openObserveEnabled", false);
        ServiceVitalsResponseDTO out = aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO());
        assertFalse(out.isSupported());
        assertEquals(0, out.getTiles().size());
        assertEquals(0, client.callCount(), "ES path must not query OpenObserve");
        assertEquals("elasticsearch", out.getBackend());
    }

    @Test
    void testEmptyServiceNameYieldsNoTilesWithNote() {
        ServiceVitalsResponseDTO out = aggregator.aggregate("  ", new ServiceVitalsRequestDTO());
        assertTrue(out.isSupported());
        assertEquals(0, out.getTiles().size());
        assertFalse(out.getNotes().isEmpty(), "must surface a diagnostic note");
        assertEquals(0, client.callCount(), "no queries should fire for blank service name");
    }

    @Test
    void testReqRateSqlTargetsCountStreamAndFiltersService() {
        seedAllEmpty();
        ServiceVitalsRequestDTO req = new ServiceVitalsRequestDTO();
        req.setBucketSec(30);
        aggregator.aggregate("order-service", req);

        String sql = client.call(0).sql;
        assertTrue(sql.contains("FROM \"http_server_request_duration_count\""), sql);
        assertTrue(sql.contains("service_name = 'order-service'"), sql);
        assertTrue(sql.contains("DATE_BIN(INTERVAL '30 seconds'"), sql);
        assertEquals("metrics", client.call(0).streamType);
    }

    @Test
    void testErrorRateUses5xxStatusFilter() {
        seedAllEmpty();
        aggregator.aggregate("order-service", new ServiceVitalsRequestDTO());

        // call 0 = total req (no extra filter), call 1 = 5xx-filtered.
        String sqlTotal = client.call(0).sql;
        String sqlErr   = client.call(1).sql;
        assertFalse(sqlTotal.contains("http_response_status_code LIKE"), "total req query must not constrain status: " + sqlTotal);
        assertTrue(sqlErr.contains("http_response_status_code LIKE '5%'"),
                "5xx subset query must constrain status: " + sqlErr);
    }

    @Test
    void testLatencyTileSourcesMaxStreamAndConvertsToMs() {
        client.responses.add(List.of()); // reqRate total
        client.responses.add(List.of()); // reqRate err
        client.responses.add(bucketed("2026-05-11T12:00:00Z", 0.020));   // 20 ms in seconds
        client.responses.add(List.of()); // pool
        client.responses.add(List.of()); // heap
        client.responses.add(List.of()); // err log
        client.responses.add(List.of()); // downstream
        client.responses.add(List.of()); // downstream latency

        ServiceVitalsResponseDTO out = aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO());

        ServiceVitalsResponseDTO.Tile latency = out.getTiles().get("latencyMs");
        assertNotNull(latency);
        assertEquals("ms", latency.getUnit());
        assertEquals(Double.valueOf(20.0), latency.getLatest(),
                "0.020s should be converted to 20ms");

        String latencySql = client.call(2).sql;
        assertTrue(latencySql.contains("FROM \"http_server_request_duration_max\""), latencySql);
    }

    @Test
    void testReqRateComputesPerBucketDeltaRate() {
        // Three buckets, cumulative counter going 100 → 130 → 220. At bucketSec=60:
        //   bucket1 rate = (130-100)/60 = 0.5 req/s
        //   bucket2 rate = (220-130)/60 = 1.5 req/s
        client.responses.add(bucketed(
            "2026-05-11T12:00:00Z", 100.0,
            "2026-05-11T12:01:00Z", 130.0,
            "2026-05-11T12:02:00Z", 220.0
        ));
        seedRemainingEmpty(1);

        ServiceVitalsResponseDTO.Tile req =
            aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO()).getTiles().get("reqRate");

        assertNotNull(req);
        assertEquals("req/s", req.getUnit());
        assertEquals(2, req.getSparkline().size(),
            "3 cumulative buckets → 2 rate points (first dropped)");
        assertEquals(0.5, req.getSparkline().get(0).getValue(), 1e-6);
        assertEquals(1.5, req.getSparkline().get(1).getValue(), 1e-6);
        assertEquals(Double.valueOf(1.5), req.getLatest(),
            "latest should be the rate of the most recent pair");
    }

    @Test
    void testReqRateCounterResetIsClampedToZero() {
        // Counter goes backwards (process restart). The clamp prevents the
        // negative spike from showing as a fake DOWN signal.
        client.responses.add(bucketed(
            "2026-05-11T12:00:00Z", 500.0,
            "2026-05-11T12:01:00Z",   3.0
        ));
        seedRemainingEmpty(1);

        ServiceVitalsResponseDTO.Tile req =
            aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO()).getTiles().get("reqRate");

        assertEquals(0.0, req.getSparkline().get(0).getValue(), 1e-9);
    }

    @Test
    void testErrorRatePercentage() {
        // Total: 0 → 100 → 250 (rates 100/60, 150/60 req/s in raw count/sec)
        // Errors: 0 → 5 → 20  (rates 5/60, 15/60)
        // Per-bucket pct: 5/100*100 = 5%, 15/150*100 = 10%
        client.responses.add(bucketed(
            "2026-05-11T12:00:00Z", 0.0,
            "2026-05-11T12:01:00Z", 100.0,
            "2026-05-11T12:02:00Z", 250.0
        ));
        client.responses.add(bucketed(
            "2026-05-11T12:00:00Z",  0.0,
            "2026-05-11T12:01:00Z",  5.0,
            "2026-05-11T12:02:00Z", 20.0
        ));
        seedRemainingEmpty(2);

        ServiceVitalsResponseDTO.Tile err =
            aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO()).getTiles().get("errorRate");

        assertNotNull(err);
        assertEquals("%", err.getUnit());
        assertEquals(2, err.getSparkline().size());
        assertEquals(5.0,  err.getSparkline().get(0).getValue(), 1e-6);
        assertEquals(10.0, err.getSparkline().get(1).getValue(), 1e-6);
    }

    @Test
    void testHeapUsedConvertedBytesToMb() {
        // 50 MB in bytes
        double fiftyMb = 50.0 * 1024.0 * 1024.0;
        client.responses.add(List.of()); // reqRate
        client.responses.add(List.of()); // err
        client.responses.add(List.of()); // latency
        client.responses.add(List.of()); // pool
        client.responses.add(bucketed("2026-05-11T12:00:00Z", fiftyMb));
        client.responses.add(List.of()); // err log
        client.responses.add(List.of()); // ds
        client.responses.add(List.of()); // ds-lat

        ServiceVitalsResponseDTO.Tile heap =
            aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO()).getTiles().get("heapUsedMb");

        assertNotNull(heap);
        assertEquals("MB", heap.getUnit());
        assertEquals(50.0, heap.getLatest(), 1e-6);

        // Filter should be jvm_memory_type='heap'
        String heapSql = client.call(4).sql;
        assertTrue(heapSql.contains("jvm_memory_type = 'heap'"), heapSql);
    }

    @Test
    void testErrorLogRateInPerMinute() {
        // 0 → 6 over a 60s bucket = 0.1/s = 6/min
        client.responses.add(List.of()); // reqRate
        client.responses.add(List.of()); // err
        client.responses.add(List.of()); // latency
        client.responses.add(List.of()); // pool
        client.responses.add(List.of()); // heap
        client.responses.add(bucketed(
            "2026-05-11T12:00:00Z", 0.0,
            "2026-05-11T12:01:00Z", 6.0
        ));
        client.responses.add(List.of()); // ds
        client.responses.add(List.of()); // ds-lat

        ServiceVitalsResponseDTO.Tile elog =
            aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO()).getTiles().get("errorLogRate");

        assertNotNull(elog);
        assertEquals("/min", elog.getUnit());
        assertEquals(6.0, elog.getLatest(), 1e-6);

        String elogSql = client.call(5).sql;
        assertTrue(elogSql.contains("level = 'error'"), elogSql);
        assertTrue(elogSql.contains("FROM \"log4j2_events_total\""), elogSql);
    }

    @Test
    void testDownstreamAggregatedByClientName() {
        seedTilesEmpty();

        Map<String, Object> dsA = new HashMap<>();
        dsA.put("client_name", "billing-api"); dsA.put("start_v", 100.0); dsA.put("end_v", 700.0);
        Map<String, Object> dsB = new HashMap<>();
        dsB.put("client_name", "payments");    dsB.put("start_v", 50.0);  dsB.put("end_v",  50.0);
        client.responses.add(List.of(dsA, dsB));

        Map<String, Object> latA = new HashMap<>();
        latA.put("client_name", "billing-api"); latA.put("v", 0.045); // 45 ms
        client.responses.add(List.of(latA));

        ServiceVitalsResponseDTO out = aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO());
        
        assertTrue(client.call(6).sql.contains("COALESCE(NULLIF(peer_service, ''), CONCAT(server_address, COALESCE(url_path, '')))") && client.call(6).sql.contains("AS client_name"), client.call(6).sql);
        assertTrue(client.call(6).sql.contains("GROUP BY COALESCE(NULLIF(peer_service, ''), CONCAT(server_address, COALESCE(url_path, '')))" ), client.call(6).sql);
        assertTrue(client.call(7).sql.contains("COALESCE(NULLIF(peer_service, ''), CONCAT(server_address, COALESCE(url_path, '')))") && client.call(7).sql.contains("AS client_name"), client.call(7).sql);
        assertTrue(client.call(7).sql.contains("GROUP BY COALESCE(NULLIF(peer_service, ''), CONCAT(server_address, COALESCE(url_path, '')))" ), client.call(7).sql);

        assertEquals(2, out.getDownstream().size());
        // window=1h=3600s. billing rate = 600/3600 = 0.1667 req/s; payments = 0/3600 = 0.
        ServiceVitalsResponseDTO.DownstreamCall billing = out.getDownstream().get(0);
        assertEquals("billing-api", billing.getClientName());
        assertEquals(600.0 / 3600.0, billing.getReqRate(), 1e-4);
        assertEquals(45.0, billing.getMaxLatencyMs(), 1e-6);

        ServiceVitalsResponseDTO.DownstreamCall pay = out.getDownstream().get(1);
        assertEquals("payments", pay.getClientName());
        assertEquals(0.0, pay.getReqRate(), 1e-9);
        assertNull(pay.getMaxLatencyMs(), "no latency hit for payments → null");
    }

    @Test
    void testMissingStreamProducesNoteAndAbsentTile() {
        // reqRate query throws — expect a note and that downstream queries
        // continue (no crash).
        client.failOnSlot = 0;             // reqRate
        client.responses.add(List.of());   // err (call #2)
        client.responses.add(List.of());   // latency
        client.responses.add(List.of());   // pool
        client.responses.add(List.of());   // heap
        client.responses.add(List.of());   // err log
        client.responses.add(List.of());   // ds
        client.responses.add(List.of());   // ds-lat

        ServiceVitalsResponseDTO out = aggregator.aggregate("svc-x", new ServiceVitalsRequestDTO());
        assertNull(out.getTiles().get("reqRate"));
        assertTrue(out.getNotes().stream().anyMatch(n -> n.contains("reqRate")),
                "missing reqRate stream should leave an explanatory note");
        // The follow-on tiles should still try (8 attempts total despite the first failing).
        assertTrue(client.callCount() >= 7, "remaining tiles must still fire");
    }

    @Test
    void testWindowMetadataEchoedBack() {
        seedAllEmpty();
        ServiceVitalsRequestDTO req = new ServiceVitalsRequestDTO();
        req.setWindow("now-15m");
        req.setBucketSec(30);

        ServiceVitalsResponseDTO out = aggregator.aggregate("svc-x", req);
        assertNotNull(out.getWindowStart());
        assertNotNull(out.getWindowEnd());
        assertEquals(30, out.getBucketSec());
        assertEquals("openobserve", out.getBackend());
        assertEquals("svc-x", out.getServiceName());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Seed responses for all 8 expected queries with empty lists. */
    private void seedAllEmpty() {
        for (int i = 0; i < 8; i++) client.responses.add(List.of());
    }

    private void seedTilesEmpty() {
        for (int i = 0; i < 6; i++) client.responses.add(List.of());
    }

    /** Pad remaining slots so the rest of the pipeline executes cleanly. */
    private void seedRemainingEmpty(int alreadySeeded) {
        for (int i = alreadySeeded; i < 8; i++) client.responses.add(List.of());
    }

    /** Build a bucketed-AVG result row list from alternating (bucketISO, value). */
    private static List<Map<String, Object>> bucketed(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("pairs must be (iso, val)+");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Map<String, Object> row = new HashMap<>();
            row.put("bucket", pairs[i]);
            row.put("v",      pairs[i + 1]);
            out.add(row);
        }
        return out;
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

    /** Records every call; replays {@code responses} in order; can be told to throw on the Nth call. */
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

        static class Call { String sql; int from; int size; String streamType; }

        /**
         * Queries are addressed by SLOT — which of the eight vitals reads this is —
         * rather than by arrival order.
         *
         * <p>The aggregator now issues these concurrently, so arrival order is not
         * deterministic and an index into a growing list would hand a test another
         * query's canned response. The slot is derived from the stream the SQL
         * targets, which is fixed per tile, so {@code call(2)} still means exactly
         * what {@code calls.get(2)} used to mean: the latency query. Slots keep the
         * documented call order of the class javadoc:</p>
         *
         * <ol start="0">
         *   <li>request count — total</li>
         *   <li>request count — 5xx subset</li>
         *   <li>request duration max — latency</li>
         *   <li>hikari active — pool</li>
         *   <li>jvm memory used — heap</li>
         *   <li>log4j2 events — error logs</li>
         *   <li>client request count — downstream</li>
         *   <li>client request max — downstream latency</li>
         * </ol>
         */
        static final int SLOTS = 8;

        private final Map<Integer, Call> bySlot = new ConcurrentHashMap<>();
        final List<List<Map<String, Object>>> responses =
                Collections.synchronizedList(new ArrayList<>());
        /** Slot that should throw; -1 disables. */
        int failOnSlot = -1;

        /** The query recorded for a slot, or null if that read never fired. */
        Call call(int slot) {
            return bySlot.get(slot);
        }

        /** How many of the eight reads actually fired. */
        int callCount() {
            return bySlot.size();
        }

        private static int slotFor(String sql) {
            if (sql.contains(AppConstants.VITALS_HTTP_REQ_COUNT_STREAM)) {
                return sql.contains("http_response_status_code LIKE '5%'") ? 1 : 0;
            }
            if (sql.contains(AppConstants.VITALS_HTTP_REQ_MAX_STREAM))      return 2;
            if (sql.contains(AppConstants.VITALS_HIKARI_ACTIVE_STREAM))     return 3;
            if (sql.contains(AppConstants.VITALS_JVM_MEMORY_USED_STREAM))   return 4;
            if (sql.contains(AppConstants.VITALS_LOGBACK_EVENTS_STREAM))    return 5;
            if (sql.contains(AppConstants.VITALS_HTTP_CLIENT_COUNT_STREAM)) return 6;
            if (sql.contains(AppConstants.VITALS_HTTP_CLIENT_MAX_STREAM))   return 7;
            throw new AssertionError("unrecognised vitals query, no slot for: " + sql);
        }

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            int slot = slotFor(sql);
            Call c = new Call();
            c.sql = sql; c.from = from; c.size = size; c.streamType = streamType;
            if (bySlot.putIfAbsent(slot, c) != null) {
                throw new AssertionError("slot " + slot + " queried twice: " + sql);
            }
            if (failOnSlot == slot) {
                throw new RuntimeException("simulated stream-missing error");
            }
            synchronized (responses) {
                return slot < responses.size() ? responses.get(slot) : List.of();
            }
        }

        @Override
        public List<String> discoverServices() { return List.of(); }

        @Override
        public List<String> listMetricStreams() { return List.of(); }

    }
}
