package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricLabelsDTO;
import com.trinity.observability.exception.OpenObserveQueryException;
import com.trinity.observability.util.RequestScopedFanout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The label-name and label-value caches in {@link PromMetricsServiceImpl}.
 *
 * <p>What matters is how often OpenObserve is asked, and that caching changes
 * nothing about the answer: same items, a fresh DTO per call, and a failure that
 * is reported, never remembered.</p>
 */
class PromMetricsLabelCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrefixClient client;
    private PromMetricsServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new PrefixClient();
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "org", "default");
        service = new PromMetricsServiceImpl(client, config, null, new RequestScopedFanout(2), 300, 60);
    }

    @Test
    void labelValuesAreProjectedOnceAndThenServedFromMemory() {
        client.on("/api/default/prometheus/api/v1/series?match[]=up",
                "{\"data\":[{\"service_name\":\"b\"},{\"service_name\":\"a\"},{\"service_name\":\"b\"},{}]}");

        MetricLabelsDTO first = service.getLabelValues("service_name", "up");
        MetricLabelsDTO second = service.getLabelValues("service_name", "up");

        assertEquals(List.of("a", "b"), first.getItems());
        assertEquals(first.getItems(), second.getItems());
        assertNotSame(first, second, "each response gets its own DTO");
        assertEquals(1, client.asked().size(), "called: " + client.asked());
    }

    @Test
    void labelValuesAreKeyedByMetricAndLabel() {
        client.on("/api/default/prometheus/api/v1/series?match[]=up",
                "{\"data\":[{\"service_name\":\"a\",\"job\":\"j\"}]}");
        client.on("/api/default/prometheus/api/v1/series?match[]=down",
                "{\"data\":[{\"service_name\":\"z\"}]}");

        assertEquals(List.of("a"), service.getLabelValues("service_name", "up").getItems());
        assertEquals(List.of("j"), service.getLabelValues("job", "up").getItems());
        assertEquals(List.of("z"), service.getLabelValues("service_name", "down").getItems());
        assertEquals(3, client.asked().size());
    }

    @Test
    void aFailedLabelValuesReadIsReportedAndNotCached() {
        MetricLabelsDTO failed = service.getLabelValues("service_name", "up");
        assertFalse(failed.isSupported());
        assertTrue(failed.getItems().isEmpty());

        client.on("/api/default/prometheus/api/v1/series?match[]=up",
                "{\"data\":[{\"service_name\":\"a\"}]}");
        MetricLabelsDTO recovered = service.getLabelValues("service_name", "up");
        assertTrue(recovered.isSupported());
        assertEquals(List.of("a"), recovered.getItems());
    }

    @Test
    void labelNamesAreSortedAndCachedPerMetric() {
        client.on("/api/default/prometheus/api/v1/labels?match[]=up",
                "{\"data\":[\"job\",\"__name__\",\"Instance\"]}");

        MetricLabelsDTO first = service.getLabels("up");
        MetricLabelsDTO second = service.getLabels("up");

        assertEquals(List.of("__name__", "Instance", "job"), first.getItems());
        assertEquals(first.getItems(), second.getItems());
        assertEquals(1, client.asked().size());
    }

    @Test
    void aFailedLabelNamesReadIsReportedAndNotCached() {
        assertFalse(service.getLabels("up").isSupported());

        client.on("/api/default/prometheus/api/v1/labels?match[]=up", "{\"data\":[\"job\"]}");
        assertEquals(List.of("job"), service.getLabels("up").getItems());
    }

    /** Answers any path that STARTS with a stubbed prefix — /series carries a
     *  start/end derived from the clock, which a test cannot spell exactly. */
    private static final class PrefixClient implements OpenObserveClientService {

        private final Map<String, String> bodies = new ConcurrentHashMap<>();
        private final List<String> asked = new CopyOnWriteArrayList<>();

        void on(String prefix, String json) { bodies.put(prefix, json); }

        List<String> asked() { return asked; }

        @Override
        public JsonNode getJson(String path) {
            asked.add(path);
            for (Map.Entry<String, String> e : bodies.entrySet()) {
                if (path.equals(e.getKey()) || path.startsWith(e.getKey() + "&")) {
                    try {
                        return MAPPER.readTree(e.getValue());
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
            throw new OpenObserveQueryException(404, "no stub for " + path, null);
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
