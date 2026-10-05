package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.service.TelemetryDiscoveryService.Discovery;
import com.trinity.observability.util.OrgContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discovery's concurrency, window escalation and reuse.
 *
 * <p>The OpenObserve client is faked with a scripted table of
 * {@code (stream, column, window) → hit}, because every behaviour here is about
 * WHICH queries get issued and how their answers are combined — not about SQL.
 * The fake also records the thread each query ran on and can sleep, which is how
 * the concurrency and ordering guarantees are asserted rather than assumed.</p>
 */
class TelemetryDiscoveryServiceImplTest {

    private static final long MICROS_PER_HOUR = 3_600L * 1_000_000L;

    @AfterEach
    void clearOrg() {
        OrgContext.clear();
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a window's probes run concurrently, so a pass costs one probe's latency")
    void probesRunConcurrently() {
        ScriptedClient client = new ScriptedClient();
        // Nothing matches, so every probe in the chain has to run.
        client.delayMillis = 200;

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        long t0 = System.nanoTime();
        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "absent");
        long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;

        assertFalse(result.found());
        assertEquals(9, client.queries.size(), "the whole chain should have been tried");
        // Sequentially this is 9 × 200 ms. Concurrently it is one probe plus
        // scheduling. The bound is loose enough not to be flaky on a busy machine
        // but far below the 1800 ms a serial chain cannot beat.
        assertTrue(elapsedMillis < 1_200,
                "expected a concurrent pass, took " + elapsedMillis + " ms");
        assertTrue(client.threads.size() > 1,
                "expected more than one thread, saw " + client.threads);
    }

    @Test
    @DisplayName("probe-concurrency=1 keeps the old sequential behaviour")
    void concurrencyOfOneIsSequential() {
        ScriptedClient client = new ScriptedClient();
        client.delayMillis = 50;

        TelemetryDiscoveryServiceImpl service = service(client, props(1, "now-24h", List.of()));
        service.discover(ResourceCategory.APPLICATIONS, "absent");

        assertEquals(1, client.threads.size(), "one worker only, saw " + client.threads);
    }

    // ── Per-signal window escalation ─────────────────────────────────────────

    @Test
    @DisplayName("signals missing from the primary window are retried in a narrower one")
    void narrowerWindowFillsInTheMissingSignals() {
        ScriptedClient client = new ScriptedClient();
        // Exactly the shape this deployment produces: the metric stream is small
        // enough to answer a 24h scan, while the large logs/traces stream only
        // answers a 6h one. Before per-signal retry this resource was reported as
        // having metrics ONLY, because any hit at all ended the search.
        client.hit("jvm_memory_used", "service_name", 24, "Svc");
        client.hit("default", "service_name", 6, "Svc");

        TelemetryDiscoveryServiceImpl service =
                service(client, props(9, "now-24h", List.of("now-6h", "now-1h")));

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "Svc");

        assertTrue(result.found());
        assertEquals(List.of("logs", "traces", "metrics"), result.signals(),
                "all three signals should be reported, not just the one the wide window served");
        assertEquals(3, result.bindings().size());
    }

    @Test
    @DisplayName("the reported window is the primary one, whichever window answered")
    void windowReportedIsThePrimary() {
        ScriptedClient client = new ScriptedClient();
        client.hit("default", "service_name", 1, "Svc");

        TelemetryDiscoveryServiceImpl service =
                service(client, props(9, "now-24h", List.of("now-1h")));

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "Svc");

        // The operator is told what was searched, and the widest window is what
        // makes a "not found" falsifiable — so it must not shrink to whichever
        // fallback happened to answer.
        long spanHours = (result.windowEndMicros() - result.windowStartMicros()) / MICROS_PER_HOUR;

        assertEquals(24, spanHours, "the primary (widest) window must be reported");
    }

    @Test
    @DisplayName("the narrowest window is searched first, and a full answer there ends the search")
    void theNarrowWindowAnswersWithoutTouchingTheWideOne() {
        ScriptedClient client = new ScriptedClient();
        // The ordinary case: an operator registers a resource that is emitting
        // right now, so the cheapest window already has all three signals.
        client.hit("default", "service_name", 1, "Live");
        client.hit("jvm_memory_used", "service_name", 1, "Live");

        TelemetryDiscoveryServiceImpl service =
                service(client, props(9, "now-24h", List.of("now-6h", "now-1h")));

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "Live");

        assertEquals(List.of("logs", "traces", "metrics"), result.signals());
        // This is the whole point of the ordering. On this deployment the same
        // lookup costs 0.3 s over an hour and 19-23 s over a day, so a registration
        // that can be answered by the hour must never reach the day.
        assertEquals(List.of(1L), client.distinctWindowHoursInOrder(),
                "now-6h and now-24h should not have been attempted");
    }

    @Test
    @DisplayName("the window widens until the remaining signals are found")
    void escalationWidensUntilTheSignalsAreFound() {
        ScriptedClient client = new ScriptedClient();
        // A resource quiet for hours: nothing at all in the narrow windows, and
        // logs only once the search reaches the widest one.
        client.hit("default", "service_name", 24, "LogsOnly");

        TelemetryDiscoveryServiceImpl service =
                service(client, props(9, "now-24h", List.of("now-6h", "now-1h")));

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "LogsOnly");

        assertEquals(List.of("logs", "traces"), result.signals(),
                "the traces probe on the same stream/column also matches this fixture");
        assertEquals(List.of(1L, 6L, 24L), client.distinctWindowHoursInOrder(),
                "narrowest first, widening only for what is still missing");
    }

    @Test
    @DisplayName("the exhaustive case-fold predicate is used only in the narrowest window")
    void theExpensivePredicateIsConfinedToTheNarrowestWindow() {
        ScriptedClient client = new ScriptedClient();

        TelemetryDiscoveryServiceImpl service =
                service(client, props(9, "now-24h", List.of("now-6h", "now-1h")));
        service.discover(ResourceCategory.APPLICATIONS, "absent");

        // Still exactly one query per probe per window — the windows differ in
        // which predicate they carry, not in how many round trips they cost. A
        // probe reports upstream failure as a miss, so extra queries would make a
        // saturated OpenObserve answer wrong rather than slowly.
        assertEquals(9, client.queriesForWindow(1L));
        assertEquals(9, client.queriesForWindow(6L));
        assertEquals(9, client.queriesForWindow(24L));

        // LOWER() reads the whole column. Over an hour of the large stream that
        // measured 0.3 s and over a day 19-23 s, which is why only the narrow
        // window may spend it.
        assertTrue(client.foldingQueriesForWindow(1L) > 0,
                "the narrow window should fold case exhaustively");
        assertEquals(0, client.foldingQueriesForWindow(6L));
        assertEquals(0, client.foldingQueriesForWindow(24L));
    }

    @Test
    @DisplayName("a fallback window that is not narrower is skipped")
    void nonNarrowerFallbackIsSkipped() {
        ScriptedClient client = new ScriptedClient();

        TelemetryDiscoveryServiceImpl service =
                service(client, props(9, "now-6h", List.of("now-24h", "now-6h", "now-1h")));
        service.discover(ResourceCategory.APPLICATIONS, "absent");

        // now-24h is wider than the primary and now-6h is identical to it,
        // repeating either would only re-ask a question already answered.
        assertEquals(List.of(1L, 6L), client.distinctWindowHoursInOrder());
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the configured probe order decides a signal, not which query returns first")
    void configuredOrderWinsOverCompletionOrder() {
        ScriptedClient client = new ScriptedClient();
        // Both of these answer for the logs signal. The canonical service_name
        // probe is configured first but made SLOW; the namespace fallback returns
        // immediately. Resolving by completion order would record the namespace —
        // and a namespace identifier filters far more than the operator asked for.
        client.hit("default", "service_name", 24, "Svc");
        client.hit("default", "k8s_namespace_name", 24, "some-namespace");
        client.delayFor("default", "service_name", 250);

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "Svc");

        TelemetryBinding logs = result.bindings().stream()
                .filter(b -> "logs".equals(b.signal())).findFirst().orElseThrow();
        assertEquals("service_name", logs.column());
        assertEquals("Svc", result.resolvedName());
    }

    // ── Reuse by the registration path ───────────────────────────────────────

    @Test
    @DisplayName("a successful validation is reused by the registration path")
    void successfulDiscoveryIsReused() {
        ScriptedClient client = new ScriptedClient();
        client.hit("default", "service_name", 24, "Svc");

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        Discovery first = service.discover(ResourceCategory.APPLICATIONS, "Svc");
        int afterValidate = client.queries.size();

        Discovery reused = service.discoverCached(ResourceCategory.APPLICATIONS, "Svc");

        assertSame(first, reused, "Register should reuse what Validate just proved");
        assertEquals(afterValidate, client.queries.size(), "no further queries expected");
    }

    @Test
    @DisplayName("a miss is never reused, so retrying after 'not found' re-checks")
    void missIsNotReused() {
        ScriptedClient client = new ScriptedClient();

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        service.discover(ResourceCategory.APPLICATIONS, "absent");
        int afterValidate = client.queries.size();

        // The operator starts the agent and clicks Register. Telemetry now exists.
        client.hit("default", "service_name", 24, "absent");
        Discovery second = service.discoverCached(ResourceCategory.APPLICATIONS, "absent");

        assertTrue(second.found(), "a cached refusal would reject a resource that now exists");
        assertTrue(client.queries.size() > afterValidate, "expected a fresh look");
    }

    @Test
    @DisplayName("reuse is scoped to the org, so one tenant cannot satisfy another")
    void reuseIsOrgScoped() {
        ScriptedClient client = new ScriptedClient();
        client.hit("default", "service_name", 24, "Svc");

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        OrgContext.set("orgA");
        service.discover(ResourceCategory.APPLICATIONS, "Svc");
        int afterOrgA = client.queries.size();

        OrgContext.set("orgB");
        service.discoverCached(ResourceCategory.APPLICATIONS, "Svc");

        assertTrue(client.queries.size() > afterOrgA,
                "the same name means different telemetry in a different org");
    }

    @Test
    @DisplayName("reuse is also scoped to the category")
    void reuseIsCategoryScoped() {
        ScriptedClient client = new ScriptedClient();
        client.hit("default", "service_name", 24, "Svc");

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        service.discover(ResourceCategory.APPLICATIONS, "Svc");
        int afterApplications = client.queries.size();

        service.discoverCached(ResourceCategory.TOOLS, "Svc");

        assertTrue(client.queries.size() > afterApplications);
    }

    @Test
    @DisplayName("discovery-cache-seconds=0 makes the registration path always re-query")
    void zeroTtlDisablesReuse() {
        ScriptedClient client = new ScriptedClient();
        client.hit("default", "service_name", 24, "Svc");

        ProductCatalogProperties properties = props(9, "now-24h", List.of());
        properties.getValidation().setDiscoveryCacheSeconds(0);
        TelemetryDiscoveryServiceImpl service = service(client, properties);

        service.discover(ResourceCategory.APPLICATIONS, "Svc");
        int afterValidate = client.queries.size();

        service.discoverCached(ResourceCategory.APPLICATIONS, "Svc");

        assertTrue(client.queries.size() > afterValidate);
    }

    // ── Failure handling ─────────────────────────────────────────────────────

    @Test
    @DisplayName("one probe throwing does not lose the rest of the pass")
    void throwingProbeDoesNotLoseThePass() {
        ScriptedClient client = new ScriptedClient();
        client.hit("jvm_memory_used", "service_name", 24, "Svc");
        client.failFor("default", "service_name");

        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "Svc");

        assertTrue(result.found());
        assertEquals(List.of("metrics"), result.signals());
    }

    @Test
    @DisplayName("with OpenObserve disabled nothing is queried and nothing is claimed")
    void disabledBackendQueriesNothing() {
        ScriptedClient client = new ScriptedClient();
        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));
        ReflectionTestUtils.setField(service, "openObserveEnabled", false);

        Discovery result = service.discover(ResourceCategory.APPLICATIONS, "Svc");

        assertFalse(result.found());
        assertNull(result.resolvedName());
        assertTrue(client.queries.isEmpty());
    }

    @Test
    @DisplayName("the org of the calling request is what the parallel probes query")
    void orgIsCarriedIntoTheProbeThreads() {
        ScriptedClient client = new ScriptedClient();
        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        OrgContext.set("tenant-b");
        service.discover(ResourceCategory.APPLICATIONS, "Svc");

        // Each probe runs on a pool thread with none of the request's state. Without
        // the org being carried across, they would all query the configured default
        // instead — a wrong answer, not a slow one.
        assertEquals(Collections.singleton("tenant-b"), client.orgsSeen,
                "probes queried " + client.orgsSeen);
        assertEquals("tenant-b", OrgContext.get(), "the caller's own context must survive");
    }

    // ── Last seen ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("last seen is folded onto every resource that claimed the matched value")
    void lastSeenFoldsOntoClaimingResources() {
        ScriptedClient client = new ScriptedClient();
        client.hit("default", "service_name", 24, "Svc");
        TelemetryDiscoveryServiceImpl service = service(client, props(9, "now-24h", List.of()));

        TelemetryBinding svc = new TelemetryBinding("logs", "default", "service_name", "Svc");
        TelemetryBinding gone = new TelemetryBinding("logs", "default", "service_name", "Gone");
        Map<String, List<TelemetryBinding>> bindings = new LinkedHashMap<>();
        bindings.put("r1", List.of(svc));
        bindings.put("r2", List.of(svc));
        bindings.put("r3", List.of(gone));

        Map<String, Long> lastSeen = service.lastSeen(bindings);

        // One grouped query for the shared (stream, column), and the one value it
        // returned credited to both resources that registered it.
        assertEquals(1, client.queries.size());
        assertEquals(Map.of("r1", 1_700_000_000_000_000L, "r2", 1_700_000_000_000_000L), lastSeen);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static TelemetryDiscoveryServiceImpl service(ScriptedClient client,
            ProductCatalogProperties properties) {
        TelemetryDiscoveryServiceImpl service = new TelemetryDiscoveryServiceImpl(
                client, openObserveConfig(), properties, emptyProvider());
        ReflectionTestUtils.setField(service, "openObserveEnabled", true);
        return service;
    }

    /** The production probe chain, so the tests exercise the real default order. */
    private static ProductCatalogProperties props(int concurrency, String window,
            List<String> fallbacks) {
        ProductCatalogProperties properties = new ProductCatalogProperties();
        properties.getValidation().setProbeConcurrency(concurrency);
        properties.getValidation().setWindow(window);
        properties.getValidation().setFallbackWindows(fallbacks);
        return properties;
    }

    private static OpenObserveConfig openObserveConfig() {
        OpenObserveConfig config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", "default");
        ReflectionTestUtils.setField(config, "logsStream", "default");
        ReflectionTestUtils.setField(config, "tracesStream", "default");
        return config;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.trinity.observability.service.TelemetryProbe> emptyProvider() {
        return (ObjectProvider<com.trinity.observability.service.TelemetryProbe>)
                java.lang.reflect.Proxy.newProxyInstance(
                        TelemetryDiscoveryServiceImplTest.class.getClassLoader(),
                        new Class<?>[] { ObjectProvider.class },
                        (proxy, method, args) -> "orderedStream".equals(method.getName())
                                ? java.util.stream.Stream.empty()
                                : null);
    }

    /**
     * An OpenObserve stand-in scripted by {@code (stream, column, windowHours)}.
     *
     * <p>Parses the probe's SQL rather than being handed the parameters, so the
     * tests fail if the probe stops filtering on what it claims to.</p>
     */
    private static final class ScriptedClient implements OpenObserveClientService {

        /** (stream, column, windowHours) → the value telemetry reports. */
        private final Map<String, String> hits = new LinkedHashMap<>();

        /** (stream, column) → artificial latency, on top of {@link #delayMillis}. */
        private final Map<String, Long> delays = new LinkedHashMap<>();

        /** (stream, column) pairs that throw instead of answering. */
        private final List<String> failures = new ArrayList<>();

        private final List<Query> queries = new CopyOnWriteArrayList<>();
        private final Map<String, Boolean> threadSet = new ConcurrentHashMap<>();
        private final java.util.Set<String> orgsSeen = ConcurrentHashMap.newKeySet();
        private final AtomicInteger sequence = new AtomicInteger();

        private long delayMillis;

        java.util.Set<String> threads = threadSet.keySet();

        void hit(String stream, String column, long windowHours, String value) {
            hits.put(stream + '|' + column + '|' + windowHours, value);
        }

        void delayFor(String stream, String column, long millis) {
            delays.put(stream + '|' + column, millis);
        }

        void failFor(String stream, String column) {
            failures.add(stream + '|' + column);
        }

        /** Queries against a window of this size that used the full-scan fold. */
        int foldingQueriesForWindow(long windowHours) {
            int count = 0;
            for (Query query : queries) {
                if (query.windowHours() == windowHours && query.foldsCase()) {
                    count++;
                }
            }
            return count;
        }

        /** How many queries were issued against a window of this size. */
        int queriesForWindow(long windowHours) {
            int count = 0;
            for (Query query : queries) {
                if (query.windowHours() == windowHours) {
                    count++;
                }
            }
            return count;
        }

        /** Window sizes actually queried, deduplicated, in the order first seen. */
        List<Long> distinctWindowHoursInOrder() {
            List<Long> out = new ArrayList<>();
            for (Query query : queries) {
                if (!out.contains(query.windowHours())) {
                    out.add(query.windowHours());
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("java:S2925")
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            String stream = between(sql, "FROM \"", "\"");
            String column = between(sql, "SELECT ", " AS matched_value");
            long windowHours = Math.round((double) (endMicros - startMicros) / MICROS_PER_HOUR);

            threadSet.put(Thread.currentThread().getName(), Boolean.TRUE);
            orgsSeen.add(String.valueOf(OrgContext.get()));
            queries.add(new Query(stream, column, windowHours, sequence.incrementAndGet(),
                    sql.contains("LOWER(")));

            String pair = stream + '|' + column;
            long sleep = delayMillis + delays.getOrDefault(pair, 0L);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
            if (failures.contains(pair)) {
                throw new IllegalStateException("simulated upstream failure for " + pair);
            }

            String value = hits.get(pair + '|' + windowHours);
            if (value == null) {
                return List.of();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("matched_value", value);
            row.put("last_seen", 1_700_000_000_000_000L);
            row.put("sample_count", 42L);
            return List.of(row);
        }

        private static String between(String text, String open, String close) {
            int start = text.indexOf(open);
            if (start < 0) {
                return "";
            }
            start += open.length();
            int end = text.indexOf(close, start);
            return end < 0 ? "" : text.substring(start, end);
        }

        @Override public JsonNode getJson(String path) { throw unsupported(); }
        @Override public JsonNode sendJson(String m, String p, JsonNode b) { throw unsupported(); }
        @Override public JsonNode deleteJson(String path) { throw unsupported(); }
        @Override public JsonNode ingest(String o, String s, JsonNode r) { throw unsupported(); }
        @Override public List<String> discoverServices() { throw unsupported(); }
        @Override public List<String> listMetricStreams() { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("discovery must only call search()");
        }

        private record Query(String stream, String column, long windowHours, int order,
                boolean foldsCase) {
        }
    }
}
