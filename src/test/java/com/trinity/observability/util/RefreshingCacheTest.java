package com.trinity.observability.util;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefreshingCacheTest {

    private final RefreshingCache<String> cache =
            new RefreshingCache<>("test", Duration.ofMinutes(5), Duration.ofSeconds(30));

    private final AtomicInteger loads = new AtomicInteger();

    @AfterEach
    void clearOrg() {
        OrgContext.clear();
    }

    /** Pushes an entry's expiry into the past without waiting out a real TTL. */
    @SuppressWarnings("unchecked")
    private void expire(String key) throws Exception {
        Field f = RefreshingCache.class.getDeclaredField("entries");
        f.setAccessible(true);
        var entries = (ConcurrentHashMap<String, Object>) f.get(cache);
        Object entry = entries.get(key);
        Field value = entry.getClass().getDeclaredField("value");
        value.setAccessible(true);
        var ctor = entry.getClass().getDeclaredConstructor(long.class, Object.class);
        ctor.setAccessible(true);
        entries.put(key, ctor.newInstance(System.currentTimeMillis() - 1, value.get(entry)));
    }

    private void awaitLoads(int expected) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> loads.get() >= expected);
        assertEquals(expected, loads.get());
    }

    @Test
    void freshEntriesAreServedWithoutReloading() {
        for (int i = 0; i < 10; i++) {
            assertEquals("v1", cache.get("k", () -> "v" + loads.incrementAndGet()));
        }
        assertEquals(1, loads.get());
    }

    @Test
    void staleEntryIsServedAndRefreshedInTheBackground() throws Exception {
        assertEquals("v1", cache.get("k", () -> "v" + loads.incrementAndGet()));
        expire("k");
        // The caller gets the stale value immediately...
        assertEquals("v1", cache.get("k", () -> "v" + loads.incrementAndGet()));
        // ...and the refresh lands behind it.
        awaitLoads(2);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> "v2".equals(cache.get("k", () -> "unexpected")));
        assertEquals("v2", cache.get("k", () -> "unexpected"));
    }

    @Test
    void failedRefreshKeepsTheLastGoodValueAndBacksOff() throws Exception {
        cache.get("k", () -> "good");
        expire("k");
        cache.get("k", () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("upstream down");
        });
        awaitLoads(1);
        // Let the failed refresh settle, and prove it does not retry on its own meanwhile.
        await().during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1))
                .until(() -> loads.get() == 1);
        for (int i = 0; i < 10; i++) {
            assertEquals("good", cache.get("k", () -> {
                loads.incrementAndGet();
                throw new IllegalStateException("upstream down");
            }));
        }
        await("a failed refresh must be backed off, not retried per call")
                .during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1))
                .until(() -> loads.get() == 1);
    }

    @Test
    void coldFailureIsThrownAndNotCached() {
        assertThrows(IllegalStateException.class, () -> cache.get("k", () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals("ok", cache.get("k", () -> "ok"));
    }

    @Test
    void concurrentColdMissesShareOneLoad() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] futures = new Future[8];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(() -> cache.get("k", () -> {
                    loads.incrementAndGet();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "v";
                }));
            }
            // Hold the load open long enough for every caller to arrive, and prove
            // none of them starts a second one while it is in flight.
            await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5))
                    .until(() -> loads.get() == 1);
            release.countDown();
            for (Future<?> f : futures) {
                assertEquals("v", f.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get());
    }

    @Test
    void backgroundRefreshRunsUnderTheArmingRequestsOrg() throws Exception {
        String key = RefreshingCache.orgKey("tenant-a");
        OrgContext.set("tenant-a");
        cache.get(key, () -> "initial");
        expire(key);
        String[] seenOrg = new String[1];
        cache.get(key, () -> {
            seenOrg[0] = OrgContext.get();
            loads.incrementAndGet();
            return "refreshed";
        });
        awaitLoads(1);
        assertEquals("tenant-a", seenOrg[0]);
    }

    @Test
    void orgKeysKeepTenantsApart() {
        assertEquals("a", cache.get(RefreshingCache.orgKey("org-a"), () -> "a"));
        assertEquals("b", cache.get(RefreshingCache.orgKey("org-b"), () -> "b"));
        assertEquals("a", cache.get(RefreshingCache.orgKey("org-a"), () -> "wrong"));
    }
}
