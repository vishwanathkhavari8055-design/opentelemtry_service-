package com.trinity.observability.serviceimpl;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.MetricClassificationProperties;
import com.trinity.observability.config.MetricProducerProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricCatalogDTO;
import com.trinity.observability.dto.metrics.MetricProducersDTO;
import com.trinity.observability.service.PromMetricsService;
import com.trinity.observability.util.MetricClassifier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the reading of {@code service_name} that the raw counts get wrong.
 *
 * <p>The sweep itself is plumbing; what needs pinning down is the
 * interpretation. Three behaviours here were each derived from something the
 * live data made obvious and a naive implementation gets backwards:</p>
 *
 * <ul>
 *   <li>kube-state-metrics describes every pod, so a pod's name appears on 200
 *       {@code kube_*} metrics it does not export — those must land in
 *       "observed", never in "exposes".</li>
 *   <li>44 producers on this instance emit nothing but {@code up} and
 *       {@code scrape_*}. Counting those as telemetry classifies a bare scrape
 *       target as part of the observability stack.</li>
 *   <li>A Go exporter emits ~150 {@code go_*} metrics against ~25 of the thing
 *       it exports, so counting shared runtime metrics toward the dominant
 *       group labels every exporter a "runtime".</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricProducerServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The metric name inside `count by (service_name) (<metric>)`. */
    private static final Pattern QUERY_METRIC =
            Pattern.compile("count by \\(service_name\\) \\((.+)\\)");

    @Mock
    private OpenObserveClientService client;
    @Mock
    private PromMetricsService promMetricsService;

    private MetricProducerProperties props;
    private MetricProducerServiceImpl service;

    /** metric name -> the service_name values the probe should report. */
    private final Map<String, List<String>> probeAnswers = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        // Real config objects: Mockito cannot mock them on JDK 26+ (Byte Buddy),
        // the same reason MetricsServiceImplTest constructs its own.
        OpenObserveConfig ooConfig = new OpenObserveConfig();
        props = new MetricProducerProperties();
        props.setConcurrency(4);
        props.setTtlSeconds(3600);

        service = new MetricProducerServiceImpl(client, ooConfig, promMetricsService, props);

        when(client.getJson(anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
            Matcher m = QUERY_METRIC.matcher(decoded);
            String metric = m.find() ? m.group(1) : "";
            return promResponse(probeAnswers.getOrDefault(metric, List.of()));
        });
    }

    /** A Prometheus range response carrying one series per service_name. */
    private static JsonNode promResponse(List<String> services) throws Exception {
        StringBuilder sb = new StringBuilder("{\"status\":\"success\",\"data\":"
                + "{\"resultType\":\"matrix\",\"result\":[");
        for (int i = 0; i < services.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"metric\":{\"service_name\":\"").append(services.get(i))
                    .append("\"},\"values\":[[1,\"1\"]]}");
        }
        return MAPPER.readTree(sb.append("]}}").toString());
    }

    /** Catalogue of the given metric names, classified as the real one would be. */
    private void catalog(String... names) {
        MetricClassifier classifier = new MetricClassifier(new MetricClassificationProperties());
        List<MetricCatalogDTO.Item> items = new ArrayList<>();
        for (String n : names) {
            MetricCatalogDTO.Item item = new MetricCatalogDTO.Item(n, "gauge", null, null);
            MetricClassifier.Classification c = classifier.classify(n);
            item.setGroup(c.getGroup());
            item.setTechnology(c.getTechnology());
            item.setShared(c.isSharedAcrossProducers());
            items.add(item);
        }
        MetricCatalogDTO dto = new MetricCatalogDTO();
        dto.setItems(items);
        dto.setTotal(items.size());
        when(promMetricsService.getCatalog()).thenReturn(dto);
    }

    /** Poll until the asynchronous build finishes, then return the index. */
    private MetricProducersDTO awaitIndex() {
        return await("build did not complete in time")
                .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .until(service::getProducers, out -> !out.isBuilding() && out.getBuiltAt() != null);
    }

    private static MetricProducersDTO.Service find(MetricProducersDTO dto, String name) {
        return dto.getServices().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("service not in index: " + name));
    }

    private static List<String> names(MetricProducersDTO.Bucket b) {
        return b.getMetrics().stream().map(MetricProducersDTO.Metric::getName).toList();
    }

    /**
     * The kube-state-metrics trap: a pod named in {@code kube_*} samples has not
     * exposed them.
     */
    @Test
    void platformMetricsAboutAServiceAreObservedNotExposed() {
        catalog("jvm_memory_used_bytes", "kube_pod_status_phase");
        probeAnswers.put("jvm_memory_used_bytes", List.of("IoTOpsSvc"));
        probeAnswers.put("kube_pod_status_phase", List.of("IoTOpsSvc"));

        MetricProducersDTO.Service svc = find(awaitIndex(), "IoTOpsSvc");

        assertEquals(MetricClassifier.GROUP_MICROSERVICE, svc.getKind());
        assertEquals(List.of("jvm_memory_used_bytes"), names(svc.getEmitted()));
        assertEquals(List.of("kube_pod_status_phase"), names(svc.getObserved()));
    }

    /** Only kube_* mentions it: it emits nothing at all. */
    @Test
    void aServiceOnlyDescribedByThePlatformIsObservedOnly() {
        catalog("kube_pod_status_phase", "k8s_pod_cpu_utilization");
        probeAnswers.put("kube_pod_status_phase", List.of("tiotmlinferencesvc"));
        probeAnswers.put("k8s_pod_cpu_utilization", List.of("tiotmlinferencesvc"));

        MetricProducersDTO.Service svc = find(awaitIndex(), "tiotmlinferencesvc");

        assertEquals("observed-only", svc.getKind());
        assertEquals(0, svc.getEmitted().getCount());
        assertEquals(2, svc.getObserved().getCount());
    }

    /**
     * {@code up} and {@code scrape_*} are produced BY the scrape for every
     * target, so a producer with nothing else exposes nothing of its own — and
     * must not be filed under the observability stack for carrying them.
     */
    @Test
    void aTargetWithOnlyScrapeMetricsIsScrapeOnly() {
        catalog("up", "scrape_duration_seconds", "scrape_samples_scraped");
        probeAnswers.put("up", List.of("redis-master"));
        probeAnswers.put("scrape_duration_seconds", List.of("redis-master"));
        probeAnswers.put("scrape_samples_scraped", List.of("redis-master"));

        MetricProducersDTO.Service svc = find(awaitIndex(), "redis-master");

        assertEquals("scrape-only", svc.getKind());
        assertEquals("Scraped, no telemetry of its own", svc.getKindLabel());
        // They are still listed — the point is the kind, not hiding the rows.
        assertEquals(3, svc.getEmitted().getCount());
    }

    /** A Go exporter is what it exports, not the language it is written in. */
    @Test
    void sharedRuntimeMetricsDoNotDecideTheKind() {
        catalog("mssql_connections", "mssql_deadlocks",
                "go_gc_duration_seconds", "go_goroutines", "go_memstats_alloc_bytes",
                "process_cpu_seconds_total", "up");
        probeAnswers.put("mssql_connections", List.of("MsSqlExporter"));
        probeAnswers.put("mssql_deadlocks", List.of("MsSqlExporter"));
        probeAnswers.put("go_gc_duration_seconds", List.of("MsSqlExporter"));
        probeAnswers.put("go_goroutines", List.of("MsSqlExporter"));
        probeAnswers.put("go_memstats_alloc_bytes", List.of("MsSqlExporter"));
        probeAnswers.put("process_cpu_seconds_total", List.of("MsSqlExporter"));
        probeAnswers.put("up", List.of("MsSqlExporter"));

        MetricProducersDTO.Service svc = find(awaitIndex(), "MsSqlExporter");

        // Three go_* plus a process_* outnumber the two mssql_*, and the kind is
        // still SQL Server's.
        assertEquals(MetricClassifier.GROUP_MIDDLEWARE, svc.getKind());
    }

    /** One metric, many producers — each gets its own row. */
    @Test
    void aSharedMetricIsAttributedToEveryProducer() {
        catalog("process_cpu_seconds_total", "jvm_memory_used_bytes", "zo_compact_pending_jobs");
        probeAnswers.put("process_cpu_seconds_total",
                List.of("openobserve", "tiotapi", "DLHAppService"));
        probeAnswers.put("jvm_memory_used_bytes", List.of("tiotapi"));
        probeAnswers.put("zo_compact_pending_jobs", List.of("openobserve"));

        MetricProducersDTO dto = awaitIndex();

        assertEquals(3, dto.getServices().size());
        assertTrue(names(find(dto, "openobserve").getEmitted()).contains("process_cpu_seconds_total"));
        assertTrue(names(find(dto, "tiotapi").getEmitted()).contains("process_cpu_seconds_total"));
        assertEquals(MetricClassifier.GROUP_OBSERVABILITY, find(dto, "openobserve").getKind());
        assertEquals(MetricClassifier.GROUP_MICROSERVICE, find(dto, "tiotapi").getKind());
        // Only runtime metrics reached DLHAppService here.
        assertEquals(MetricClassifier.GROUP_RUNTIME, find(dto, "DLHAppService").getKind());
    }

    /**
     * The three "nothing came back" cases mean different things and are counted
     * apart: no data in the window, present but unscoped, and a failed probe.
     */
    @Test
    void reconcilesMetricsThatYieldNoService() {
        catalog("jvm_memory_used_bytes", "windows_cpu_dpcs_total", "zo_compact_pending_jobs");
        probeAnswers.put("jvm_memory_used_bytes", List.of("IoTOpsSvc"));
        // Empty result -> wrote nothing inside the window.
        probeAnswers.put("windows_cpu_dpcs_total", List.of());
        // Present, but the samples carry no service_name.
        probeAnswers.put("zo_compact_pending_jobs", List.of(""));

        MetricProducersDTO dto = awaitIndex();

        assertEquals(3, dto.getMetricsProbed());
        assertEquals(1, dto.getMetricsWithNoData());
        assertEquals(1, dto.getMetricsUnscoped());
        assertEquals(0, dto.getProbeFailures());
        assertEquals(1, dto.getServices().size());
    }

    /** A probe that throws makes the index partial, never absent. */
    @Test
    void aFailedProbeIsCountedAndTheRestStillBuilds() {
        catalog("jvm_memory_used_bytes", "kafka_consumer_commit_rate");
        probeAnswers.put("jvm_memory_used_bytes", List.of("IoTOpsSvc"));
        when(client.getJson(anyString())).thenAnswer(inv -> {
            String decoded = URLDecoder.decode(inv.getArgument(0), StandardCharsets.UTF_8);
            if (decoded.contains("kafka_consumer_commit_rate")) {
                throw new RuntimeException("connection reset");
            }
            return promResponse(List.of("IoTOpsSvc"));
        });

        MetricProducersDTO dto = awaitIndex();

        assertEquals(1, dto.getProbeFailures());
        assertEquals(1, dto.getServices().size());
        assertEquals(List.of("jvm_memory_used_bytes"), names(find(dto, "IoTOpsSvc").getEmitted()));
    }

    /** Microservices sort above the long tail that exposes nothing. */
    @Test
    void ourServicesSortAboveTheObservedOnlyTail() {
        catalog("jvm_memory_used_bytes", "kube_pod_status_phase");
        probeAnswers.put("jvm_memory_used_bytes", List.of("IoTOpsSvc"));
        probeAnswers.put("kube_pod_status_phase", List.of("aaa-observed-pod", "IoTOpsSvc"));

        MetricProducersDTO dto = awaitIndex();

        assertEquals("IoTOpsSvc", dto.getServices().get(0).getName());
        assertEquals("observed-only", dto.getServices().get(1).getKind());
    }

    /**
     * A sweep publishes as it goes, so one service's metrics are readable long
     * before the other three thousand probes finish — and the intermediate
     * answer is flagged partial so its counts are not read as final.
     *
     * <p>Driven by a latch rather than a sleep: the probes block until the test
     * has seen a partial publish, which makes the assertion deterministic
     * instead of a race against the sweep finishing first.</p>
     */
    @Test
    void publishesPartialResultsWhileStillSweeping() {
        // 250 metrics, so the publish-every-200 boundary is crossed once before
        // the end. The first is the one we care about reading early.
        List<String> names = new ArrayList<>();
        names.add("jvm_memory_used_bytes");
        for (int i = 0; i < 249; i++) names.add("kafka_probe_filler_" + i);
        catalog(names.toArray(new String[0]));

        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger answered =
                new java.util.concurrent.atomic.AtomicInteger();
        // The jvm probe must be RECORDED before the 200th probe completes, or the
        // partial publish is built without it. Nothing guarantees that on its own:
        // probes run on four threads, and if the jvm probe's thread stalls (cold
        // class loading on the first call) the other three can finish 200 first.
        // A pool thread runs its tasks one after another, so its next getJson
        // call proves the jvm task has finished; fillers late in the first 200
        // wait for exactly that.
        java.util.concurrent.atomic.AtomicReference<Thread> jvmThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch jvmRecorded = new CountDownLatch(1);
        when(client.getJson(anyString())).thenAnswer(inv -> {
            String decoded = URLDecoder.decode(inv.getArgument(0), StandardCharsets.UTF_8);
            boolean isJvm = decoded.contains("jvm_memory_used_bytes");
            if (isJvm) {
                jvmThread.set(Thread.currentThread());
            } else if (Thread.currentThread() == jvmThread.get()) {
                jvmRecorded.countDown();
            }
            int n = answered.incrementAndGet();
            if (!isJvm && n > 150) {
                jvmRecorded.await(5, TimeUnit.SECONDS);
            }
            // Hold the tail of the sweep open until the test has looked.
            if (n > 210) {
                release.await(5, TimeUnit.SECONDS);
            }
            return promResponse(List.of(isJvm ? "dlhbackendservice" : "filler-svc"));
        });

        try {
            MetricProducersDTO dto = await()
                    .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10))
                    .until(service::getProducers, d -> !d.getServices().isEmpty());

            assertTrue(dto.isBuilding(), "sweep should still be running");
            assertTrue(dto.isPartial(), "an index published mid-sweep must say so");
            assertEquals(List.of("jvm_memory_used_bytes"),
                    names(find(dto, "dlhbackendservice").getEmitted()));
        } finally {
            release.countDown();
        }

        // And the finished index is no longer flagged partial.
        MetricProducersDTO done = awaitIndex();
        assertTrue(!done.isPartial(), "a finished index must not be flagged partial");
        assertEquals(250, done.getMetricsProbed());
    }

    @Test
    void disabledReportsUnsupportedAndNeverQueries() {
        props.setEnabled(false);
        MetricProducersDTO dto = service.getProducers();

        assertTrue(dto.getServices().isEmpty());
        assertEquals("metrics.producers.enabled=false", dto.getError());
        verify(client, never()).getJson(anyString());
    }

    /** The window is reported so a "no metrics" answer can be read. */
    @Test
    void reportsTheWindowItProbed() {
        props.setWindowSeconds(1800);
        catalog("jvm_memory_used_bytes");
        probeAnswers.put("jvm_memory_used_bytes", List.of("IoTOpsSvc"));

        assertEquals(1800, awaitIndex().getWindowSeconds());
    }

    /**
     * A rebuild must not replace a complete index with a prefix of the new
     * sweep.
     *
     * <p>This is the failure that made the alert editor's metric picker look
     * broken. Mid-sweep publishing is there for the FIRST build, where a partial
     * answer beats a spinner. On a rebuild it discards a complete index and
     * republishes the first couple of hundred probes, so every service not yet
     * re-swept reads as exposing nothing — and the picker, which asks this index
     * what a service emits, falls back to offering every metric in the
     * instance.</p>
     *
     * <p>It is not a narrow window either. The sweep takes about as long as the
     * TTL that starts it on this deployment's catalogue, so the index would be
     * partial almost all of the time.</p>
     */
    @Test
    void aRebuildKeepsServingTheCompleteIndexUntilTheNewOneIsFinished() {
        // 250 metrics, so the publish-every-200 boundary is crossed mid-sweep.
        String[] names = new String[250];
        for (int i = 0; i < names.length; i++) {
            names[i] = "metric_" + i;
        }
        catalog(names);
        // Only the LAST metric belongs to late_svc, so it can only be present in
        // an index whose sweep ran to the end.
        probeAnswers.put("metric_249", List.of("late_svc"));

        MetricProducersDTO complete = awaitIndex();
        assertFalse(complete.isPartial(), "precondition: the first build finished");
        assertNotNull(find(complete, "late_svc"));

        // Expire it so the next read starts a rebuild, then hold every probe so
        // the rebuild cannot get past its first partial publish.
        props.setTtlSeconds(0);
        CountDownLatch release = new CountDownLatch(1);
        when(client.getJson(anyString())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return promResponse(List.of());
        });

        try {
            await("precondition: a rebuild is running")
                    .atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(25))
                    .until(() -> service.getProducers().isBuilding());

            // Give the rebuild every chance to publish over the good index.
            await("a rebuild in flight must keep serving the complete index")
                    .during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                    .until(() -> !service.getProducers().isPartial());
            MetricProducersDTO during = service.getProducers();

            assertFalse(during.isPartial(),
                    "a rebuild in flight must keep serving the complete index");
            assertNotNull(find(during, "late_svc"),
                    "the service found at the END of the last sweep must not disappear "
                    + "while the next sweep is still near its start");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aPartialPublishStillFillsAnEmptyCacheOnTheFirstBuild() {
        // The other half of the rule: with nothing cached there is no better
        // answer to protect, and a prefix beats an empty screen.
        String[] names = new String[250];
        for (int i = 0; i < names.length; i++) {
            names[i] = "metric_" + i;
        }
        catalog(names);
        probeAnswers.put("metric_0", List.of("early_svc"));

        CountDownLatch release = new CountDownLatch(1);
        when(client.getJson(anyString())).thenAnswer(inv -> {
            String decoded = URLDecoder.decode(inv.getArgument(0), StandardCharsets.UTF_8);
            Matcher m = QUERY_METRIC.matcher(decoded);
            String metric = m.find() ? m.group(1) : "";
            // The first 220 answer freely so the sweep crosses the
            // publish-every-200 boundary; everything after that blocks, so the
            // sweep cannot finish and only the PARTIAL publish is observable.
            int index = metric.startsWith("metric_")
                    ? Integer.parseInt(metric.substring("metric_".length())) : 0;
            if (index >= 220) {
                release.await(5, TimeUnit.SECONDS);
            }
            return promResponse(probeAnswers.getOrDefault(metric, List.of()));
        });

        try {
            MetricProducersDTO dto = await("a partial publish must reach an empty cache")
                    .atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25))
                    .until(service::getProducers, d -> d.getBuiltAt() != null);
            assertTrue(dto.isPartial(), "and must say that it is partial");
        } finally {
            release.countDown();
        }
    }
}
