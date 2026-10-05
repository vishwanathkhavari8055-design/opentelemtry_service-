package com.trinity.observability.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DashboardQueryCache}: how often Grafana is asked, and that every answer
 * is the one a direct call would have given.
 */
class DashboardQueryCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    private DashboardQueryCache cache;

    @AfterEach
    void tearDown() {
        if (cache != null) cache.shutdown();
    }

    /** Upstream that answers every query with one frame carrying the SQL it ran and the window. */
    private UnaryOperator<JsonNode> echo() {
        return payload -> {
            sent.add(payload);
            ObjectNode results = MAPPER.createObjectNode();
            for (JsonNode q : payload.path("queries")) {
                ObjectNode r = results.putObject(q.path("refId").asText());
                r.put("status", 200);
                ObjectNode frame = r.putArray("frames").addObject();
                frame.putObject("schema").put("refId", q.path("refId").asText()).put("name", q.path("sql").asText());
                frame.putObject("data").putArray("values").addArray().add(payload.path("to").asText());
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.set("results", results);
            return out;
        };
    }

    private DashboardQueryCache newCache(UnaryOperator<JsonNode> upstream) {
        cache = new DashboardQueryCache(upstream, true, 30, 120, 100, now::get);
        return cache;
    }

    private ObjectNode payload(long from, long to, ObjectNode... queries) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("from", String.valueOf(from));
        p.put("to", String.valueOf(to));
        ArrayNode qs = p.putArray("queries");
        for (ObjectNode q : queries) qs.add(q);
        return p;
    }

    private ObjectNode infinity(String refId, String sql) {
        ObjectNode q = MAPPER.createObjectNode();
        q.put("refId", refId);
        q.putObject("datasource").put("type", "yesoreyeram-infinity-datasource").put("uid", "inf");
        q.put("sql", sql);
        q.put("intervalMs", 15000);
        q.put("maxDataPoints", 800);
        return q;
    }

    private ObjectNode last15m() {
        return payload(now.get() - 900_000, now.get(), infinity("A", "status"));
    }

    @Test
    void theSameQueryFromTwoPanelsRunsOnceAndAnswersEachUnderItsOwnRefId() {
        newCache(echo());
        ObjectNode panel0 = payload(now.get() - 900_000, now.get(),
                infinity("A", "status"), infinity("F", "reachable"));
        ObjectNode panel2 = payload(now.get() - 900_000, now.get(), infinity("Z", "status"));
        // A narrower panel asks for fewer points; Infinity SQL cannot use them.
        ((ObjectNode) panel2.path("queries").get(0)).put("maxDataPoints", 300);

        JsonNode r0 = cache.query(panel0);
        JsonNode r2 = cache.query(panel2);

        assertEquals(2, sent.size(), "status once, reachable once; sent: " + sent);
        assertEquals("status", r2.at("/results/Z/frames/0/schema/name").asText());
        assertEquals("Z", r2.at("/results/Z/frames/0/schema/refId").asText());
        assertEquals("A", r0.at("/results/A/frames/0/schema/refId").asText());
        assertEquals("reachable", r0.at("/results/F/frames/0/schema/name").asText());
    }

    @Test
    void reopeningARelativeWindowIsServedFromMemory() {
        newCache(echo());
        cache.query(last15m());
        now.addAndGet(10_000);
        cache.query(last15m());
        assertEquals(1, sent.size());
    }

    @Test
    void aStaleEntryIsServedAndRefreshedEndingAtTheRefresh() {
        newCache(echo());
        cache.query(last15m());
        long firstTo = now.get();

        now.addAndGet(45_000);
        JsonNode stale = cache.query(last15m());
        assertEquals(String.valueOf(firstTo), stale.at("/results/A/frames/0/data/values/0/0").asText());

        waitFor(() -> sent.size() == 2);
        JsonNode refreshedPayload = sent.get(1);
        assertEquals(String.valueOf(now.get()), refreshedPayload.path("to").asText());
        assertEquals(String.valueOf(now.get() - 900_000), refreshedPayload.path("from").asText());
    }

    @Test
    void pastMaxStaleTheCallerWaitsForAFreshAnswer() {
        newCache(echo());
        cache.query(last15m());
        now.addAndGet(200_000);
        JsonNode fresh = cache.query(last15m());
        assertEquals(2, sent.size());
        assertEquals(String.valueOf(now.get()), fresh.at("/results/A/frames/0/data/values/0/0").asText());
    }

    @Test
    void concurrentCallersShareOneUpstreamRun() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        UnaryOperator<JsonNode> slow = p -> {
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return echo().apply(p);
        };
        newCache(slow);
        Thread a = new Thread(() -> cache.query(last15m()));
        Thread b = new Thread(() -> cache.query(last15m()));
        a.start();
        b.start();
        // Release only once both callers are parked: one on the upstream, one on its result.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> isParked(a) && isParked(b));
        release.countDown();
        a.join(5000);
        b.join(5000);
        assertEquals(1, sent.size());
    }

    @Test
    void anErrorIsReturnedButNotKept() {
        UnaryOperator<JsonNode> failing = payload -> {
            sent.add(payload);
            ObjectNode out = MAPPER.createObjectNode();
            out.putObject("results").putObject("A").put("status", 500).put("error", "boom");
            return out;
        };
        newCache(failing);
        JsonNode first = cache.query(last15m());
        assertEquals("boom", first.at("/results/A/error").asText());
        cache.query(last15m());
        assertEquals(2, sent.size(), "an error result must not be cached");
    }

    @Test
    void anUpstreamExceptionPropagates() {
        newCache(payload -> { throw new IllegalStateException("Grafana down"); });
        JsonNode request = last15m();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> cache.query(request));
        assertEquals("Grafana down", e.getMessage());
    }

    @Test
    void aRequestWithAnExpressionGoesUpstreamWholeAndUncached() {
        newCache(echo());
        ObjectNode expr = MAPPER.createObjectNode();
        expr.put("refId", "B");
        expr.putObject("datasource").put("type", "__expr__").put("uid", "__expr__");
        ObjectNode p = payload(now.get() - 900_000, now.get(), infinity("A", "status"), expr);

        cache.query(p);
        cache.query(p);

        assertEquals(2, sent.size());
        assertEquals(2, sent.get(0).path("queries").size(), "sent whole, not split");
    }

    @Test
    void aRangeQueryKeepsItsStepInTheKey() {
        ObjectNode range = MAPPER.createObjectNode();
        range.put("refId", "A");
        range.putObject("datasource").put("type", "prometheus").put("uid", "p");
        range.put("expr", "rate(x[5m])");
        range.put("intervalMs", 15000);
        ObjectNode wider = range.deepCopy();
        wider.put("intervalMs", 60000);
        assertNotEquals(DashboardQueryCache.keyOf(range), DashboardQueryCache.keyOf(wider));

        range.put("instant", true);
        wider.put("instant", true);
        assertEquals(DashboardQueryCache.keyOf(range), DashboardQueryCache.keyOf(wider));
    }

    @Test
    void anAbsoluteWindowIsKeyedExactly() {
        newCache(echo());
        long from = now.get() - 86_400_000L;
        long to = now.get() - 43_200_000L;
        cache.query(payload(from, to, infinity("A", "status")));
        cache.query(payload(from, to, infinity("A", "status")));
        cache.query(payload(from + 1000, to, infinity("A", "status")));
        assertEquals(2, sent.size());
    }

    /** An Infinity query whose body carries the window the way `${__from}000` renders it. */
    private ObjectNode last15mWithWindowInBody() {
        long to = now.get();
        long from = to - 900_000;
        ObjectNode q = infinity("A", "status");
        q.putObject("url_options").put("data",
                "{\"start_time\":" + from + "000,\"end_time\":" + to + "000,\"s\":" + (to / 1000) + "}");
        return payload(from, to, q);
    }

    @Test
    void aWindowWrittenIntoTheQueryStillHitsOnReopen() {
        newCache(echo());
        cache.query(last15mWithWindowInBody());
        now.addAndGet(10_000);
        cache.query(last15mWithWindowInBody());
        assertEquals(1, sent.size(), "the body's epochs moved with the window; it is the same question");
    }

    @Test
    void aBackgroundRefreshSendsTheNewWindowInsideTheBody() {
        newCache(echo());
        long firstTo = now.get();
        cache.query(last15mWithWindowInBody());
        String firstBody = sent.get(0).at("/queries/0/url_options/data").asText();
        assertEquals("{\"start_time\":" + (firstTo - 900_000) + "000,\"end_time\":" + firstTo
                + "000,\"s\":" + (firstTo / 1000) + "}", firstBody, "sent exactly as the browser wrote it");

        now.addAndGet(45_000);
        cache.query(last15mWithWindowInBody());
        waitFor(() -> sent.size() == 2);

        long to = now.get();
        assertEquals("{\"start_time\":" + (to - 900_000) + "000,\"end_time\":" + to
                + "000,\"s\":" + (to / 1000) + "}", sent.get(1).at("/queries/0/url_options/data").asText());
    }

    @Test
    void numbersThatAreNotTheWindowAreLeftAlone() {
        newCache(echo());
        long to = now.get();
        ObjectNode q = infinity("A", "SELECT * WHERE x > " + (to / 1000) + "7 AND y = 9" + to);
        cache.query(payload(to - 900_000, to, q));
        assertEquals("SELECT * WHERE x > " + (to / 1000) + "7 AND y = 9" + to,
                sent.get(0).at("/queries/0/sql").asText());
    }

    @Test
    void disabledPassesEverythingThrough() {
        cache = new DashboardQueryCache(echo(), false, 30, 120, 100, now::get);
        cache.query(last15m());
        cache.query(last15m());
        assertEquals(2, sent.size());
        assertEquals(1, sent.get(0).path("queries").size());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        await("condition not met in time").atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(condition::getAsBoolean);
    }

    private static boolean isParked(Thread t) {
        Thread.State state = t.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }
}
