package com.trinity.observability.serviceimpl;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.trinity.observability.config.OpenObserveConfig;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pins the stream-name cache down, because the thing it protects is invisible
 * from the outside.
 *
 * <p>{@code /streams?type=metrics} answers with 19.2 MB and 19,567 names on this
 * deployment, and the alert editor asks for it every time somebody opens the
 * stream dropdown. The list looks identical whether it came from memory or from
 * a fresh 7-second fetch, so nothing but a test counting the calls can stop a
 * later edit from quietly putting the fetch back on the request path.</p>
 *
 * <p>Each behaviour below was a real failure mode of the naive version: no cache
 * at all, a cache that made one caller in sixty pay the full cost, and a cache
 * that emptied the dropdown the moment OpenObserve hiccuped.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamCatalogServiceImplCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private OpenObserveClientService oo;

    private StreamCatalogServiceImpl service;

    /** How many times OpenObserve was actually asked. The whole point of the test. */
    private final AtomicInteger fetches = new AtomicInteger();

    /** Flipped to make the next fetch fail, standing in for an OpenObserve outage. */
    private volatile boolean failing = false;

    /** Swapped to prove a refresh really did replace the cached list. */
    private volatile List<String> upstream = List.of("beta", "alpha");

    @BeforeEach
    void setUp() throws Exception {
        // A real config object: Mockito cannot mock it on JDK 26+ (Byte Buddy),
        // the same reason MetricProducerServiceImplTest constructs its own.
        service = new StreamCatalogServiceImpl(oo, new OpenObserveConfig());
        setField(service, "openObserveEnabled", true);
        setField(service, "ttlSeconds", 60);
        setField(service, "metricsTtlSeconds", 600);
        // Prewarm is an @EventListener, so it never runs without a Spring context.
        // Set explicitly anyway, so the test states what it assumes.
        setField(service, "prewarmEnabled", false);

        when(oo.getJson(anyString())).thenAnswer(inv -> {
            fetches.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("simulated OpenObserve outage");
            }
            return streamList(upstream);
        });
    }

    /** The shape OpenObserve's /streams endpoint answers with. */
    private static JsonNode streamList(List<String> names) throws Exception {
        StringBuilder sb = new StringBuilder("{\"list\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(names.get(i)).append("\"}");
        }
        return MAPPER.readTree(sb.append("]}").toString());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Waits for the background refresh to land rather than sleeping a fixed time. */
    private void awaitFetches(int expected) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> fetches.get() >= expected);
        assertEquals(expected, fetches.get(), "expected exactly " + expected + " upstream fetch(es)");
    }

    /** Expires the cached entry for a type without waiting out a real TTL. */
    @SuppressWarnings("unchecked")
    private void expire(String type) throws Exception {
        Field f = StreamCatalogServiceImpl.class.getDeclaredField("nameCache");
        f.setAccessible(true);
        var cache = (java.util.concurrent.ConcurrentHashMap<String, Object>) f.get(service);
        // Keys are org + '\0' + type; the test's config has no org, so match on type.
        String key = cache.keySet().stream()
                .filter(k -> k.endsWith('\0' + type))
                .findFirst().orElseThrow();
        Object entry = cache.get(key);
        Class<?> cached = entry.getClass();
        Field value = cached.getDeclaredField("value");
        value.setAccessible(true);
        var ctor = cached.getDeclaredConstructor(long.class, Object.class);
        ctor.setAccessible(true);
        // Same value, an expiry already in the past.
        cache.put(key, ctor.newInstance(System.currentTimeMillis() - 1, value.get(entry)));
    }

    @Test
    void repeatedListsHitOpenObserveOnce() {
        // The regression this whole change exists for: the alert editor's dropdown
        // used to re-fetch 19 MB on every single open.
        for (int i = 0; i < 20; i++) {
            assertEquals(List.of("alpha", "beta"), service.list("metrics"));
        }
        assertEquals(1, fetches.get(), "20 lookups must cost exactly one upstream fetch");
    }

    @Test
    void namesAreSortedCaseInsensitivelyAsBefore() {
        upstream = List.of("Zebra", "apple", "Banana");
        assertEquals(List.of("apple", "Banana", "Zebra"), service.list("logs"));
    }

    @Test
    void eachStreamTypeIsCachedSeparately() {
        service.list("logs");
        service.list("metrics");
        service.list("traces");
        service.list("logs");
        service.list("metrics");
        assertEquals(3, fetches.get(), "one fetch per type, not per call");
    }

    @Test
    void expiredEntryIsServedImmediatelyAndRefreshedBehindTheRequest() throws Exception {
        assertEquals(List.of("alpha", "beta"), service.list("metrics"));
        assertEquals(1, fetches.get());

        upstream = List.of("gamma");
        expire("metrics");

        // The caller gets the STALE list with no upstream call in its own path.
        // That is the point: nobody waits out a 19 MB fetch because a timer expired.
        assertEquals(List.of("alpha", "beta"), service.list("metrics"));

        // ...and the refresh lands behind them, so the next caller sees the new list.
        awaitFetches(2);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> List.of("gamma").equals(service.list("metrics")));
        assertEquals(List.of("gamma"), service.list("metrics"));
    }

    @Test
    void aFailedRefreshKeepsTheLastGoodListRatherThanEmptyingThePicker() throws Exception {
        assertEquals(List.of("alpha", "beta"), service.list("metrics"));

        failing = true;
        expire("metrics");

        service.list("metrics");
        awaitFetches(2);

        // An outage must not blank the dropdown — that is worse than a stale list,
        // because an empty picker is indistinguishable from a broken screen.
        assertEquals(List.of("alpha", "beta"), service.list("metrics"));
    }

    @Test
    void aFailedRefreshIsNotRearmedByEveryRequest() throws Exception {
        service.list("metrics");
        failing = true;
        expire("metrics");

        service.list("metrics");
        awaitFetches(2);

        // Backoff: a burst of requests against a failing upstream must not become a
        // burst of retries against an instance that is already unwell.
        for (int i = 0; i < 20; i++) {
            service.list("metrics");
        }
        await("the failed refresh must be backed off, not retried per call")
                .during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> fetches.get() == 2);
    }

    @Test
    void aColdMissStillFetchesSynchronously() {
        // The first caller after a restart has nothing to serve, so it blocks — the
        // behaviour that existed before, kept deliberately. Prewarm normally means
        // that caller is the prewarm thread rather than a person.
        assertEquals(List.of("alpha", "beta"), service.list("metrics"));
        assertEquals(1, fetches.get());
    }

    @Test
    void aColdFailureStillAnswersEmptyRatherThanThrowing() {
        failing = true;
        // A 500 here would take out the whole screen because its sidebar could not
        // populate a dropdown.
        assertTrue(service.list("metrics").isEmpty());
    }

    @Test
    void eachOrgIsCachedSeparately() {
        // The cache used to be keyed by type alone, so the first tenant to ask
        // answered for every other tenant until the entry expired.
        List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(oo.getJson(anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            paths.add(path);
            return streamList(List.of(path.contains("/tenant-b/") ? "b-stream" : "a-stream"));
        });
        try {
            com.trinity.observability.util.OrgContext.set("tenant-a");
            assertEquals(List.of("a-stream"), service.list("metrics"));
            com.trinity.observability.util.OrgContext.set("tenant-b");
            assertEquals(List.of("b-stream"), service.list("metrics"));
            com.trinity.observability.util.OrgContext.set("tenant-a");
            assertEquals(List.of("a-stream"), service.list("metrics"));
        } finally {
            com.trinity.observability.util.OrgContext.clear();
        }
        assertEquals(List.of("/api/tenant-a/streams?type=metrics", "/api/tenant-b/streams?type=metrics"),
                paths);
    }

    @Test
    void openObserveDisabledNeverFetches() throws Exception {
        setField(service, "openObserveEnabled", false);
        assertTrue(service.list("metrics").isEmpty());
        assertEquals(0, fetches.get());
    }
}
