package com.trinity.observability.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cross-tenant leak these tests guard against is silent when it happens —
 * a response simply contains another org's data — so it is asserted directly
 * rather than inferred from a passing endpoint.
 */
class RequestScopedFanoutTest {

    private RequestScopedFanout fanout;

    @BeforeEach
    void setUp() {
        fanout = new RequestScopedFanout(4);
    }

    @AfterEach
    void tearDown() {
        OrgContext.clear();
        fanout.shutdownPool();
    }

    @Test
    void propagatesCallersOrgToEveryTask() {
        OrgContext.set("tenantA");
        List<String> seen = new CopyOnWriteArrayList<>();

        fanout.runAll(List.of(
                () -> seen.add(String.valueOf(OrgContext.get())),
                () -> seen.add(String.valueOf(OrgContext.get())),
                () -> seen.add(String.valueOf(OrgContext.get()))));

        assertEquals(3, seen.size());
        assertTrue(seen.stream().allMatch("tenantA"::equals),
                "every task must see the caller's org, saw: " + seen);
    }

    /**
     * The real failure mode: two orgs in flight at once over a pool small enough
     * that they must share threads.
     */
    @Test
    void concurrentOrgsNeverSeeEachOther() throws Exception {
        int orgs = 8;
        int tasksPerOrg = 6;
        // One thread, so every task is forced onto the same reused pool thread —
        // the worst case for a leaked ThreadLocal.
        RequestScopedFanout single = new RequestScopedFanout(1);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(orgs);
            Set<String> mismatches = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < orgs; i++) {
                final String org = "tenant" + i;
                Thread caller = new Thread(() -> {
                    OrgContext.set(org);
                    try {
                        start.await();
                        List<Runnable> tasks = new ArrayList<>();
                        for (int t = 0; t < tasksPerOrg; t++) {
                            tasks.add(() -> {
                                String seen = OrgContext.get();
                                if (!org.equals(seen)) {
                                    mismatches.add(org + " saw " + seen);
                                }
                            });
                        }
                        single.runAll(tasks);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        OrgContext.clear();
                        done.countDown();
                    }
                });
                caller.start();
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "callers did not finish");
            assertEquals(Set.of(), mismatches, "cross-org leak detected");
        } finally {
            single.shutdownPool();
        }
    }

    @Test
    void restoresPoolThreadStateSoNothingLeaksToTheNextTask() {
        RequestScopedFanout single = new RequestScopedFanout(1);
        try {
            OrgContext.set("tenantA");
            single.runAll(List.of(() -> { }));

            // A caller with NO org must not inherit the previous caller's value.
            OrgContext.clear();
            List<String> seen = new CopyOnWriteArrayList<>();
            single.runAll(List.of(() -> seen.add(String.valueOf(OrgContext.get()))));
            assertEquals(List.of("null"), seen);
        } finally {
            single.shutdownPool();
        }
    }

    @Test
    void callerOrgSurvivesTheFanout() {
        OrgContext.set("tenantA");
        fanout.runAll(List.of(() -> OrgContext.set("tenantB")));
        assertEquals("tenantA", OrgContext.get(),
                "a task must not be able to change the caller's org");
    }

    @Test
    void oneFailingTaskDoesNotStopTheOthers() {
        AtomicInteger ran = new AtomicInteger();
        fanout.runAll(List.of(
                ran::incrementAndGet,
                () -> { throw new IllegalStateException("boom"); },
                ran::incrementAndGet));
        assertEquals(2, ran.get(), "a throwing task must not cancel its siblings");
    }

    @Test
    void runsTasksConcurrentlyNotSequentially() {
        // Each task waits for all three to have started. Run in parallel they all
        // meet; run in series the first one would wait alone and time out.
        CountDownLatch allStarted = new CountDownLatch(3);
        AtomicInteger met = new AtomicInteger();
        fanout.runAll(List.of(rendezvous(allStarted, met), rendezvous(allStarted, met),
                rendezvous(allStarted, met)));
        assertEquals(3, met.get(), "expected the tasks to run concurrently, not one after another");
    }

    @Test
    void emptyAndNullTaskListsAreNoOps() {
        assertDoesNotThrow(() -> fanout.runAll(Collections.emptyList()));
        assertDoesNotThrow(() -> fanout.runAll(null));
    }

    @Test
    void nullOrgStaysNull() {
        OrgContext.clear();
        List<String> seen = new CopyOnWriteArrayList<>();
        fanout.runAll(List.of(() -> seen.add(String.valueOf(OrgContext.get()))));
        assertEquals(List.of("null"), seen);
        assertNull(OrgContext.get());
    }

    private static Runnable rendezvous(CountDownLatch allStarted, AtomicInteger met) {
        return () -> {
            allStarted.countDown();
            try {
                if (allStarted.await(2, TimeUnit.SECONDS)) {
                    met.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
