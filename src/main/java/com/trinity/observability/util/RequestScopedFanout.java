package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Runs a request's independent sub-queries at the same time instead of one
 * after another.
 *
 * <p>Several endpoints here issue a handful of OpenObserve reads that do not
 * feed each other — the service vitals screen alone makes eight — and paid the
 * sum of their latencies rather than the largest. This runs them together and
 * waits for all of them.</p>
 *
 * <h2>Why this exists rather than a bare CompletableFuture.runAsync</h2>
 *
 * <p>The org a request is scoped to lives in a {@link OrgContext} ThreadLocal,
 * which {@code OpenObserveConfig.getOrg()} reads when it builds the search
 * path. A pool thread has none of the request thread's state, so a task handed
 * straight to an executor would query whichever org the static config names —
 * and under concurrent requests from two tenants, a pool thread left carrying
 * the previous request's value would serve one tenant's data to the other.
 * That is the failure this class exists to make impossible: the org is captured
 * on the calling thread in {@link #runAll}, re-established inside each task,
 * and restored afterwards so nothing leaks onto the thread's next task.</p>
 *
 * <p>The log4j {@link ThreadContext} org/requestId are carried across too, so a
 * query that runs on a pool thread is still attributable to its request in the
 * logs.</p>
 *
 * <p>This mirrors {@code InfrastructureMetricsServiceImpl}'s private
 * submit/await pair, which solved the same problem first; the logic is here so
 * the several endpoints that need it share one implementation rather than five
 * copies of a ThreadLocal dance that is silent when it is wrong.</p>
 */
@Component
public class RequestScopedFanout {

    private static final Logger log = LogManager.getLogger(RequestScopedFanout.class);

    /** Keys copied onto pool threads so fan-out queries stay attributable in logs. */
    private static final List<String> LOG_CONTEXT_KEYS = List.of("org", "requestId");

    private final ExecutorService pool;

    public RequestScopedFanout(
            @Value("${telemetry.fanout.threads:16}") int threads) {
        AtomicInteger seq = new AtomicInteger();
        // Daemon threads, matching InfrastructureMetricsServiceImpl: a query that
        // never returns must not hold JVM shutdown open.
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), runnable -> {
            Thread thread = new Thread(runnable, "fanout-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdownPool() {
        pool.shutdownNow();
    }

    /**
     * Runs every task concurrently and returns once all have finished.
     *
     * <p>Each task sees the caller's org. A task that throws is logged and
     * otherwise ignored, which matches the sequential behaviour these call sites
     * already had: every one of them is a best-effort tile that leaves its slot
     * empty and adds a note rather than failing the whole response.</p>
     *
     * <p>Tasks must not share mutable state. Each call site gives its task a
     * private result object and merges the results in a fixed order on the
     * calling thread afterwards — several of these responses key tiles by
     * insertion order, which concurrent writes would scramble even if the
     * collection were thread-safe.</p>
     */
    public void runAll(Collection<Runnable> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        // Captured HERE, on the request thread, while the ThreadLocal is still set.
        final String org = OrgContext.get();
        final List<String> logContext = currentLogContext();

        List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> scoped(org, logContext, task)));
        }

        for (Future<?> future : futures) {
            if (!awaitTask(future)) {
                return;
            }
        }
    }

    /** This thread's values for {@link #LOG_CONTEXT_KEYS}, in that order. */
    private static List<String> currentLogContext() {
        List<String> values = new ArrayList<>(LOG_CONTEXT_KEYS.size());
        for (String key : LOG_CONTEXT_KEYS) {
            values.add(ThreadContext.get(key));
        }
        return values;
    }

    /**
     * Waits for one task; its failure is logged and otherwise ignored.
     *
     * @return false when the wait was interrupted, so the caller stops waiting
     */
    private static boolean awaitTask(Future<?> future) {
        try {
            future.get();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Fan-out interrupted; returning a partial response");
            return false;
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Fan-out task failed: {}", cause.getMessage(), cause);
            return true;
        }
    }

    /** Establishes the caller's request scope for one task, then restores it. */
    private static void scoped(String org, List<String> logContext, Runnable task) {
        String previousOrg = OrgContext.get();
        List<String> previousLog = currentLogContext();
        try {
            apply(org, logContext);
            task.run();
        } finally {
            // Pool threads are reused. A value left set here is exactly the
            // cross-tenant leak this class exists to prevent.
            apply(previousOrg, previousLog);
        }
    }

    private static void apply(String org, List<String> logContext) {
        if (org == null) {
            OrgContext.clear();
        } else {
            OrgContext.set(org);
        }
        for (int i = 0; i < LOG_CONTEXT_KEYS.size(); i++) {
            String key = LOG_CONTEXT_KEYS.get(i);
            String value = logContext.get(i);
            if (value == null) {
                ThreadContext.remove(key);
            } else {
                ThreadContext.put(key, value);
            }
        }
    }
}
