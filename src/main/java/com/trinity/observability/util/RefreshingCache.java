package com.trinity.observability.util;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A small stale-while-revalidate cache for slow, rarely-changing reads.
 *
 * <p>The same shape as {@code StreamCatalogServiceImpl}'s name cache, pulled out
 * so other slow lookups can share it:</p>
 * <ul>
 *   <li><b>Fresh</b> entry: served from memory.</li>
 *   <li><b>Stale</b> entry: served from memory immediately, and ONE background
 *       refresh is armed. Nobody waits out a slow upstream merely because a timer
 *       expired.</li>
 *   <li><b>No</b> entry: the caller loads it. Concurrent callers for the same key
 *       wait on that one load instead of each issuing their own.</li>
 *   <li>A <b>failed</b> refresh keeps the last good value and is not re-armed for
 *       {@code failureBackoff}, so an upstream outage does not become a retry storm.
 *       A failed cold load is thrown to the caller and NOT cached.</li>
 * </ul>
 *
 * <p><b>Keys must include the org</b> whenever the value depends on it — see
 * {@link #orgKey}. A background refresh runs with the org of the request that
 * armed it re-established in {@link OrgContext}, so a loader that builds its
 * OpenObserve path from {@code config.getOrg()} still queries the right tenant.</p>
 */
public final class RefreshingCache<V> {

    private static final Logger log = LogManager.getLogger(RefreshingCache.class);

    /** Shared by every instance: refreshes are occasional single HTTP calls. Daemon,
     *  so a refresh that never returns cannot hold JVM shutdown open. */
    private static final ExecutorService REFRESHER;
    static {
        AtomicInteger seq = new AtomicInteger();
        REFRESHER = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "refreshing-cache-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private record Entry<V>(long expiresAtMillis, V value) {
        boolean fresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    private final String name;
    private final long ttlMillis;
    private final long failureBackoffMillis;

    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<V>> coldLoads = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> refreshNotBefore = new ConcurrentHashMap<>();

    public RefreshingCache(String name, Duration ttl, Duration failureBackoff) {
        this.name = name;
        this.ttlMillis = Math.max(1L, ttl.toMillis());
        this.failureBackoffMillis = Math.max(0L, failureBackoff.toMillis());
    }

    /** {@code org + '\0' + parts...}; the org is validated to [A-Za-z0-9_-], so the
     *  separator cannot occur inside it. */
    public static String orgKey(String org, String... parts) {
        StringBuilder sb = new StringBuilder(String.valueOf(org));
        for (String p : parts) {
            sb.append('\0').append(p);
        }
        return sb.toString();
    }

    /**
     * The cached value for {@code key}, loading it with {@code loader} if there is
     * none. Exceptions from a cold load propagate (unwrapped when unchecked).
     */
    public V get(String key, Supplier<V> loader) {
        Entry<V> hit = entries.get(key);
        if (hit != null) {
            if (!hit.fresh()) {
                armRefresh(key, loader);
            }
            return hit.value();
        }
        return loadCold(key, loader);
    }

    /** Drops every entry, e.g. after a write that invalidates what was read. */
    public void clear() {
        entries.clear();
    }

    private V loadCold(String key, Supplier<V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = coldLoads.putIfAbsent(key, mine);
        if (existing != null) {
            return await(existing);
        }
        try {
            V value = loader.get();
            entries.put(key, new Entry<>(System.currentTimeMillis() + ttlMillis, value));
            mine.complete(value);
            return value;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        } finally {
            coldLoads.remove(key, mine);
        }
    }

    private static <V> V await(CompletableFuture<V> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a cache load", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        }
    }

    private void armRefresh(String key, Supplier<V> loader) {
        Long notBefore = refreshNotBefore.get(key);
        if (notBefore != null && System.currentTimeMillis() < notBefore) {
            return;
        }
        if (!refreshing.add(key)) {
            return;
        }
        // Captured on the request thread; the pool thread has no OrgContext of its own.
        final String org = OrgContext.get();
        try {
            REFRESHER.execute(() -> {
                String previous = OrgContext.get();
                try {
                    applyOrg(org);
                    long t0 = System.currentTimeMillis();
                    V value = loader.get();
                    entries.put(key, new Entry<>(System.currentTimeMillis() + ttlMillis, value));
                    refreshNotBefore.remove(key);
                    log.debug("{} cache: refreshed in {} ms", name, System.currentTimeMillis() - t0);
                } catch (Exception t) {
                    // The last good value stays. Blanking it on a transient failure is
                    // worse than serving it a little longer.
                    refreshNotBefore.put(key, System.currentTimeMillis() + failureBackoffMillis);
                    log.warn("{} cache: background refresh failed, keeping the previous value: {}",
                            name, t.toString());
                } finally {
                    applyOrg(previous);
                    refreshing.remove(key);
                }
            });
        } catch (RuntimeException e) {
            refreshing.remove(key);
            log.debug("{} cache: refresh not scheduled: {}", name, e.getMessage());
        }
    }

    /** Sets this thread's org to {@code org}, or clears it when {@code org} is null. */
    private static void applyOrg(String org) {
        if (org == null) {
            OrgContext.clear();
        } else {
            OrgContext.set(org);
        }
    }
}
