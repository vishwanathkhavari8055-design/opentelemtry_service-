package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.dto.services.ServiceUptimeItemDTO;
import com.trinity.observability.dto.services.ServicesUptimeRequestDTO;
import com.trinity.observability.dto.services.ServicesUptimeResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
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
 * Unit tests for {@link ServicesServiceImpl} on the OpenObserve path. Mirrors
 * the shape of {@link MetricsServiceImplOpenObserveTest}: a recording fake
 * client captures SQL + size + streamType, fixed responses drive each test.
 *
 * <p>
 * Three queries fire per {@code getUptime()} call: heartbeat,
 * {@code process_uptime}, {@code application_health}. Tests seed the fake
 * client with one response list per expected call, in order.
 * </p>
 */
class ServicesServiceImplOpenObserveTest {

    private RecordingClient client;
    private ServicesServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        service = new ServicesServiceImpl(client);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testHeartbeatSqlShape() {
        client.responses.add(List.of()); // heartbeat
        // process_uptime + application_health are skipped when heartbeat is
        // empty, so the test only needs the one response.

        service.getUptime(new ServicesUptimeRequestDTO());

        String sql = client.calls.get(0).sql;
        assertNotNull(sql);
        assertTrue(sql.contains("FROM \"jvm_memory_used\""), "heartbeat queries jvm_memory_used: " + sql);
        assertTrue(sql.contains("MAX(_timestamp) AS last_seen"), sql);
        assertTrue(sql.contains("COUNT(*)        AS metric_count"), sql);
        assertTrue(sql.contains("GROUP BY service_name, host_name"), sql);
        assertEquals("metrics", client.calls.get(0).streamType);
    }

    @Test
    void testProcessUptimeSqlShape() {
        // Heartbeat must return at least one service for the follow-up
        // lookups to fire — empty heartbeat short-circuits the whole pipeline.
        long ts = Instant.now().getEpochSecond() * 1_000_000L - 3_000_000L;
        client.responses.add(List.of(heartbeatHit("svc-x", "host-x", ts, 6)));
        client.responses.add(List.of()); // process_uptime
        client.responses.add(List.of()); // application_health

        service.getUptime(new ServicesUptimeRequestDTO());

        RecordingClient.Call uptimeCall = client.findCall("process_uptime");
        assertNotNull(uptimeCall, "process_uptime must be queried");
        String sql = uptimeCall.sql;
        assertTrue(sql.contains("FROM \"process_uptime\""), sql);
        assertTrue(sql.contains("MAX(value) AS uptime_sec"), sql);
        assertTrue(sql.contains("GROUP BY service_name"), sql);
    }

    @Test
    void testApplicationHealthSqlShape() {
        long ts = Instant.now().getEpochSecond() * 1_000_000L - 3_000_000L;
        client.responses.add(List.of(heartbeatHit("svc-x", "host-x", ts, 6)));
        client.responses.add(List.of()); // process_uptime
        client.responses.add(List.of()); // application_health

        service.getUptime(new ServicesUptimeRequestDTO());

        RecordingClient.Call healthCall = client.findCall("application_health");
        assertNotNull(healthCall, "application_health must be queried");
        String sql = healthCall.sql;
        assertTrue(sql.contains("FROM \"application_health\""), sql);
        assertTrue(sql.contains("MAX(_timestamp) AS last_seen"), sql);
        assertTrue(sql.contains("MAX(value)      AS health_value"), sql);
        assertTrue(sql.contains("GROUP BY service_name"), sql);
    }

    @Test
    void testNoServicesWhenHeartbeatEmpty() {
        client.responses.add(List.of()); // heartbeat
        // process_uptime / application_health call should NOT be issued — no
        // services to enrich.

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        assertEquals(0, out.getItems().size());
        assertTrue(out.isSupported());
        assertEquals(1, client.calls.size(),
                "follow-up enrichment queries must be skipped when there are no services");
    }

    @Test
    void testUpClassificationWhenRecentHeartbeat() {
        // last_seen 5 s ago — well within the default 60s threshold.
        long endMicros = Instant.now().getEpochSecond() * 1_000_000L;
        long fiveSecAgo = endMicros - 5_000_000L;
        client.responses.add(List.of(heartbeatHit("order-service", "host-1", fiveSecAgo, 6)));
        client.responses.add(List.of()); // process_uptime
        client.responses.add(List.of()); // application_health

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());

        assertEquals(1, out.getItems().size());
        ServiceUptimeItemDTO item = out.getItems().get(0);
        assertEquals("order-service", item.getServiceName());
        assertEquals("UP", item.getStatus());
        assertTrue(item.getLastSeenAgoSec() <= 10, "lastSeenAgoSec roughly 5: " + item.getLastSeenAgoSec());
        assertEquals(Long.valueOf(6L), item.getMetricCount());
        assertNull(item.getProcessUptimeSec(), "no process_uptime hit → null");
        assertNull(item.getHealthStatus(), "no application_health hit → null");
    }

    @Test
    void testDownClassificationWhenStaleHeartbeat() {
        // last_seen 5 min ago — far outside the default 60s threshold.
        long endMicros = Instant.now().getEpochSecond() * 1_000_000L;
        long fiveMinAgo = endMicros - 300_000_000L;
        client.responses.add(List.of(heartbeatHit("legacy-batch", "host-9", fiveMinAgo, 2)));
        client.responses.add(List.of()); // process_uptime
        client.responses.add(List.of()); // application_health

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        assertEquals("DOWN", out.getItems().get(0).getStatus());
        assertTrue(out.getItems().get(0).getLastSeenAgoSec() >= 60,
                "stale heartbeat → lastSeenAgoSec well above threshold");
    }

    @Test
    void testHeartbeatScanPrefersMostRecentHost() {
        long endMicros = Instant.now().getEpochSecond() * 1_000_000L;
        long fiveSecAgo = endMicros - 5_000_000L;
        long fiveHoursAgo = endMicros - 5L * 3600_000_000L;

        // Seed with two host hits: one stale, one recent. Stale hit is added first
        // or second; let's verify both scenarios by verifying it parses recent.
        client.responses.add(List.of(
                heartbeatHit("svc-x", "host-stale", fiveHoursAgo, 10),
                heartbeatHit("svc-x", "host-recent", fiveSecAgo, 5)));
        client.responses.add(List.of()); // process_uptime
        client.responses.add(List.of()); // application_health

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        assertEquals(1, out.getItems().size());
        ServiceUptimeItemDTO item = out.getItems().get(0);
        assertEquals("UP", item.getStatus(), "should be UP due to the recent host hit");
        assertEquals("host-recent", item.getHostName(), "should keep host-recent");
        assertEquals(Long.valueOf(15L), item.getMetricCount(), "metric counts must be accumulated");
    }

    @Test
    void testProcessUptimeMergedByServiceName() {
        long ts = Instant.now().getEpochSecond() * 1_000_000L - 3_000_000L;
        client.responses.add(List.of(
                heartbeatHit("svc-a", "host-1", ts, 6),
                heartbeatHit("svc-b", "host-2", ts, 6)));
        // Only svc-a emits process_uptime.
        Map<String, Object> uHit = new HashMap<>();
        uHit.put("service_name", "svc-a");
        uHit.put("uptime_sec", 8042);
        client.responses.add(List.of(uHit));
        client.responses.add(List.of()); // application_health

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        Map<String, ServiceUptimeItemDTO> bySvc = new HashMap<>();
        for (ServiceUptimeItemDTO i : out.getItems())
            bySvc.put(i.getServiceName(), i);

        assertEquals(Long.valueOf(8042L), bySvc.get("svc-a").getProcessUptimeSec());
        assertNull(bySvc.get("svc-b").getProcessUptimeSec(),
                "service not in process_uptime result must stay null");
    }

    @Test
    void testProcessUptimeFailureDoesNotBreakResponse() {
        long ts = Instant.now().getEpochSecond() * 1_000_000L - 3_000_000L;
        client.responses.add(List.of(heartbeatHit("svc-a", "host-1", ts, 6)));
        client.failOnStream = "process_uptime"; // process_uptime call throws
        client.responses.add(List.of()); // application_health (call #3)

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        assertEquals(1, out.getItems().size());
        assertEquals("UP", out.getItems().get(0).getStatus());
        assertNull(out.getItems().get(0).getProcessUptimeSec());
    }

    @Test
    void testApplicationHealthMappedToHealthStatus() {
        long ts = Instant.now().getEpochSecond() * 1_000_000L - 3_000_000L;
        client.responses.add(List.of(
                heartbeatHit("svc-a", "host-1", ts, 6),
                heartbeatHit("svc-b", "host-2", ts, 6),
                heartbeatHit("svc-c", "host-3", ts, 6)));
        client.responses.add(List.of()); // process_uptime

        // application_health: svc-a → 1.0 (UP), svc-b → 0.0 (DOWN),
        // svc-c → no row (null healthStatus).
        long healthTs = Instant.now().getEpochSecond() * 1_000_000L - 1_000_000L;
        client.responses.add(List.of(
                healthHit("svc-a", healthTs, 1.0),
                healthHit("svc-b", healthTs, 0.0)));

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        Map<String, ServiceUptimeItemDTO> bySvc = new HashMap<>();
        for (ServiceUptimeItemDTO i : out.getItems())
            bySvc.put(i.getServiceName(), i);

        assertEquals("UP", bySvc.get("svc-a").getHealthStatus());
        assertEquals("DOWN", bySvc.get("svc-b").getHealthStatus());
        assertNull(bySvc.get("svc-c").getHealthStatus(),
                "service absent from application_health result keeps healthStatus null");
        assertNotNull(bySvc.get("svc-a").getHealthLastSeenTimestamp(),
                "healthLastSeenTimestamp populated when a sample exists");
    }

    @Test
    void testApplicationHealthFailureDoesNotBreakResponse() {
        long ts = Instant.now().getEpochSecond() * 1_000_000L - 3_000_000L;
        client.responses.add(List.of(heartbeatHit("svc-a", "host-1", ts, 6)));
        client.responses.add(List.of()); // process_uptime succeeds (empty)
        client.failOnStream = "application_health"; // application_health call throws

        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        assertEquals(1, out.getItems().size());
        assertEquals("UP", out.getItems().get(0).getStatus());
        assertNull(out.getItems().get(0).getHealthStatus(),
                "application_health failure leaves healthStatus null, doesn't crash");
    }

    @Test
    void testCustomLivenessThresholdRespected() {
        // last_seen 90s ago. Default threshold (60s) → DOWN; threshold 120 → UP.
        long endMicros = Instant.now().getEpochSecond() * 1_000_000L;
        long ninetySecAgo = endMicros - 90_000_000L;
        client.responses.add(List.of(heartbeatHit("svc-a", "host-1", ninetySecAgo, 9)));
        client.responses.add(List.of()); // process_uptime
        client.responses.add(List.of()); // application_health

        ServicesUptimeRequestDTO req = new ServicesUptimeRequestDTO();
        req.setLivenessSec(120);
        ServicesUptimeResponseDTO out = service.getUptime(req);
        assertEquals("UP", out.getItems().get(0).getStatus());
        assertEquals(120, out.getLivenessThresholdSec());
    }

    @Test
    void testEsBackendReturnsSupportedFalse() throws Exception {
        setField(service, "openObserveEnabled", false);
        ServicesUptimeResponseDTO out = service.getUptime(new ServicesUptimeRequestDTO());
        assertFalse(out.isSupported());
        assertEquals(0, out.getItems().size());
        assertEquals("elasticsearch", out.getBackend());
        assertEquals(0, client.calls.size(), "ES path must not query OpenObserve");
    }

    @Test
    void testWindowMetadataEchoedBack() {
        client.responses.add(List.of());

        ServicesUptimeRequestDTO req = new ServicesUptimeRequestDTO();
        req.setWindow("now-15m");
        ServicesUptimeResponseDTO out = service.getUptime(req);

        assertNotNull(out.getWindowStart());
        assertNotNull(out.getWindowEnd());
        assertEquals("jvm_memory_used", out.getHeartbeatStream());
        assertEquals(60, out.getLivenessThresholdSec());
        assertEquals("openobserve", out.getBackend());
    }

    // ---------- helpers ----------

    private static Map<String, Object> heartbeatHit(String svc, String host, long lastSeenMicros, long count) {
        Map<String, Object> h = new HashMap<>();
        h.put("service_name", svc);
        h.put("host_name", host);
        h.put("last_seen", lastSeenMicros);
        h.put("metric_count", count);
        return h;
    }

    private static Map<String, Object> healthHit(String svc, long lastSeenMicros, double value) {
        Map<String, Object> h = new HashMap<>();
        h.put("service_name", svc);
        h.put("last_seen", lastSeenMicros);
        h.put("health_value", value);
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

    /**
     * Records every call; replays {@code responses} in order; can be told to throw
     * on the Nth call.
     */
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

        static class Call {
            String sql;
            int from;
            int size;
            String streamType;
        }

        // Synchronized: the enrichment queries now run concurrently.
        final List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<List<Map<String, Object>>> responses = new ArrayList<>();
        /** 1-based index of the call that should throw; 0 disables. */
        int failOnCall = 0;
        /** Stream name whose query should throw; null disables. */
        String failOnStream = null;

        public Call findCall(String streamName) {
            for (Call c : calls) {
                if (c.sql != null && c.sql.contains("\"" + streamName + "\"")) {
                    return c;
                }
            }
            return null;
        }

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            Call c = new Call();
            c.sql = sql;
            c.from = from;
            c.size = size;
            c.streamType = streamType;
            calls.add(c);
            
            boolean shouldFail = (failOnCall == calls.size()) || 
                    (failOnStream != null && sql.contains("\"" + failOnStream + "\""));
            
            if (shouldFail) {
                throw new RuntimeException("simulated stream-missing error");
            }

            // Map SQL target stream to the test's seeded index
            int responseIdx = -1;
            if (sql.contains("\"jvm_memory_used\"")) {
                responseIdx = 0;
            } else if (sql.contains("\"process_uptime\"") 
                    || sql.contains("\"process_runtime_jvm_uptime\"") 
                    || sql.contains("\"jvm_uptime_seconds\"")) {
                responseIdx = 1;
            } else if (sql.contains("\"application_health\"")) {
                responseIdx = 2;
            }

            if (responseIdx != -1 && responseIdx < responses.size()) {
                return responses.get(responseIdx);
            }
            return List.of();
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
}
