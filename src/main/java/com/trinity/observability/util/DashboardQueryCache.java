package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Reuses dashboard panel query results, per query, across panels and across opens.
 *
 * <h2>Why</h2>
 *
 * <p>A dashboard sends one {@code /api/ds/query} per panel, and panels repeat each
 * other: the OSS Engine Overview runs the same 21 KB status query from three panels,
 * each ~3 s on OpenObserve, all at once — and the three copies slow each other and
 * the 8 s topology query they share OpenObserve with. Opening the dashboard again a
 * minute later paid all of it again.</p>
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li><b>Per query, not per request.</b> A request is split into its queries, so
 *       the status query inside one panel's six-query request and the same query
 *       alone in another panel are the same entry. Concurrent callers of one entry
 *       wait on ONE upstream run. The split queries are sent together, so a panel's
 *       wall time is still its slowest query.</li>
 *   <li><b>Relative windows are keyed by their length.</b> "Last 15 minutes" arrives
 *       as two epoch millis that differ on every open. A window ending within
 *       {@link #RELATIVE_SLACK_MS} of now is treated as "the last N ms"; a refresh
 *       re-runs it ending at the refresh. Any other window is keyed exactly.</li>
 *   <li><b>So is a window written INTO the query.</b> An Infinity body carrying
 *       {@code ${__from}} / {@code ${__to}} arrives with the browser's epochs already
 *       substituted, so its text differs on every open and would never hit. Those
 *       epochs are swapped for placeholders before keying, and the window actually
 *       being run is written back whenever the query goes upstream — so a background
 *       refresh queries the new window, not the one first cached.</li>
 *   <li><b>Stale-while-revalidate, with a ceiling.</b> Younger than {@code fresh}: served.
 *       Younger than {@code maxStale}: served, and re-run in the background. Older:
 *       run while the caller waits — the pre-cache behaviour.</li>
 * </ul>
 *
 * <h2>What it will not do</h2>
 *
 * <ul>
 *   <li>Keep an error. A result carrying {@code error} or a non-2xx {@code status}
 *       is returned to its caller and dropped.</li>
 *   <li>Split a request holding a server-side expression ({@code __expr__}) or a
 *       frontend-only datasource ({@code -- Dashboard --}): those reference sibling
 *       queries by refId, so the request goes upstream whole and uncached.</li>
 *   <li>Ignore the step for a query that uses it. {@code intervalMs} and
 *       {@code maxDataPoints} are part of the key unless the query demonstrably
 *       cannot use them — an instant Prometheus query, or an Infinity query whose
 *       text never mentions an interval macro.</li>
 * </ul>
 */
public final class DashboardQueryCache {

    private static final Logger log = LogManager.getLogger(DashboardQueryCache.class);

    /** How close to "now" a window's end must be for the window to count as relative. */
    static final long RELATIVE_SLACK_MS = 60_000L;

    private static final String FIELD_REF_ID = "refId";
    private static final String FIELD_DATASOURCE = "datasource";
    private static final String NOT_PRECEDED_BY_DIGIT = "(?<![0-9])";

    /** Per-query fields that identify the panel asking, not the question asked. */
    private static final List<String> VOLATILE_FIELDS = List.of(FIELD_REF_ID, "key", "requestId");

    /** Stand-ins for the window's instants inside query text; private-use code points. */
    private static final char MARK = '';
    private static final String FROM_MS = MARK + "from_ms" + MARK;
    private static final String TO_MS = MARK + "to_ms" + MARK;
    private static final String FROM_S = MARK + "from_s" + MARK;
    private static final String TO_S = MARK + "to_s" + MARK;

    /**
     * Smallest window start treated as an epoch in millis (2001-09-09). Below it the
     * bounds are too short a digit string to find in query text without also matching
     * unrelated numbers, so such a query is keyed exactly as it stands.
     */
    private static final long MIN_EPOCH_MS = 1_000_000_000_000L;

    private record Entry(JsonNode result, long computedAt) {
    }

    /** The window a query runs over: a length ending now, or two fixed instants. */
    private record Window(boolean relative, long from, long to) {
        String key() {
            return relative ? "rel:" + (to - from) : "abs:" + from + ":" + to;
        }

        Window resolvedAt(long now) {
            return relative ? new Window(true, now - (to - from), now) : this;
        }
    }

    private final UnaryOperator<JsonNode> upstream;
    private final LongSupplier clock;
    private final boolean enabled;
    private final long freshMs;
    private final long maxStaleMs;
    private final int maxEntries;
    private final ExecutorService pool;

    /** Access-ordered, so eviction drops the least recently read entry. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> inFlight = new ConcurrentHashMap<>();

    public DashboardQueryCache(UnaryOperator<JsonNode> upstream, boolean enabled,
            int freshSeconds, int maxStaleSeconds, int maxEntries, LongSupplier clock) {
        this.upstream = upstream;
        this.enabled = enabled;
        this.freshMs = Math.max(0, freshSeconds) * 1000L;
        this.maxStaleMs = Math.max(this.freshMs, Math.max(0, maxStaleSeconds) * 1000L);
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
        AtomicInteger seq = new AtomicInteger();
        // Daemon, so a query that never returns cannot hold JVM shutdown open.
        this.pool = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "dashboard-query-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops the worker threads. */
    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * The {@code /api/ds/query} response for {@code payload}: {@code {"results": {refId: …}}}.
     * Upstream failures propagate exactly as a direct call's would.
     */
    public JsonNode query(JsonNode payload) {
        if (!enabled || freshMs == 0) {
            return upstream.apply(payload);
        }
        Window window = windowOf(payload);
        JsonNode queries = payload.path("queries");
        if (!splitsIntoCacheableQueries(window, queries)) {
            return upstream.apply(payload);
        }

        List<String> refIds = new ArrayList<>(queries.size());
        List<CompletableFuture<JsonNode>> pending = new ArrayList<>(queries.size());
        // The org is part of the key: whatever tenant separation the caller relies
        // on, a cached answer never crosses it.
        String org = String.valueOf(OrgContext.get());
        for (JsonNode query : queries) {
            refIds.add(query.path(FIELD_REF_ID).asText(""));
            JsonNode template = templated(query, window);
            pending.add(resolve(org + '\0' + window.key() + '\0' + keyOf(template), template, window));
        }

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("results", awaitResults(refIds, pending));
        return out;
    }

    /** Whether a request with this window and these queries may be split and cached per query. */
    private static boolean splitsIntoCacheableQueries(Window window, JsonNode queries) {
        return window != null && queries.isArray() && !queries.isEmpty() && splittable(queries);
    }

    /** Waits on each query in request order; a missing result is left out, as upstream would. */
    private static ObjectNode awaitResults(List<String> refIds, List<CompletableFuture<JsonNode>> pending) {
        ObjectNode results = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < pending.size(); i++) {
            JsonNode result = await(pending.get(i));
            if (result != null && !result.isMissingNode()) {
                results.set(refIds.get(i), withRefId(result, refIds.get(i)));
            }
        }
        return results;
    }

    // ── cache ───────────────────────────────────────────────────────────────

    private CompletableFuture<JsonNode> resolve(String key, JsonNode query, Window window) {
        Entry hit;
        synchronized (entries) {
            hit = entries.get(key);
        }
        long now = clock.getAsLong();
        if (hit != null) {
            long age = now - hit.computedAt();
            if (age < freshMs) {
                return CompletableFuture.completedFuture(hit.result());
            }
            if (age < maxStaleMs) {
                // Nobody waits on this run, so its failure is only logged; the entry
                // it would have replaced keeps being served until maxStale.
                load(key, query, window).exceptionally(t -> {
                    log.debug("Dashboard query background refresh failed: {}", t.toString());
                    return null;
                });
                return CompletableFuture.completedFuture(hit.result());
            }
        }
        return load(key, query, window);
    }

    /** One upstream run per key at a time; later callers join the running one. */
    private CompletableFuture<JsonNode> load(String key, JsonNode query, Window window) {
        CompletableFuture<JsonNode> mine = new CompletableFuture<>();
        CompletableFuture<JsonNode> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return running;
        }
        final String org = OrgContext.get();
        try {
            pool.execute(() -> {
                String previous = OrgContext.get();
                try {
                    applyOrg(org);
                    long startedAt = clock.getAsLong();
                    JsonNode result = run(query, window.resolvedAt(startedAt));
                    if (cacheable(result)) {
                        put(key, new Entry(result, startedAt));
                    }
                    // Unregister BEFORE completing. Completing wakes the caller, and
                    // a request it makes next would otherwise find this finished run
                    // still in flight and be handed its result, so an error that is
                    // deliberately not cached would be reused anyway. A cacheable
                    // result is already in entries, so nothing re-runs early.
                    inFlight.remove(key, mine);
                    mine.complete(result);
                } catch (Exception t) {
                    inFlight.remove(key, mine);
                    mine.completeExceptionally(t);
                } finally {
                    applyOrg(previous);
                    inFlight.remove(key, mine);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(e);
        }
        return mine;
    }

    /** Sets this thread's org to {@code org}, or clears it when {@code org} is null. */
    private static void applyOrg(String org) {
        if (org == null) {
            OrgContext.clear();
        } else {
            OrgContext.set(org);
        }
    }

    private void put(String key, Entry entry) {
        synchronized (entries) {
            entries.put(key, entry);
            Iterator<String> oldest = entries.keySet().iterator();
            while (entries.size() > maxEntries && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** One query, upstream, over {@code window}. Its result, or a missing node. */
    private JsonNode run(JsonNode query, Window window) {
        String refId = query.path(FIELD_REF_ID).asText("A");
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("from", String.valueOf(window.from()));
        payload.put("to", String.valueOf(window.to()));
        payload.putArray("queries").add(materialized(query, window));
        JsonNode response = upstream.apply(payload);
        return response == null ? JsonNodeFactory.instance.missingNode()
                : response.path("results").path(refId);
    }

    private static JsonNode await(CompletableFuture<JsonNode> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a dashboard query", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        }
    }

    // ── what may be cached, and under what key ──────────────────────────────

    /**
     * {@code query} with {@code window}'s instants in its text replaced by placeholders;
     * {@link #materialized} puts a window back.
     *
     * <p>Millis may run on into more digits — {@code ${__from}000} is how a body asks
     * for microseconds. Seconds must stand alone, because a seconds value is a prefix
     * of its own millis. A query with no bound in its text comes back as it was.</p>
     */
    static JsonNode templated(JsonNode query, Window window) {
        if (window.from() < MIN_EPOCH_MS) {
            return query;
        }
        String fromS = String.valueOf(window.from() / 1000);
        String toS = String.valueOf(window.to() / 1000);
        String text = query.toString();
        if (!text.contains(fromS) && !text.contains(toS)) {
            return query;
        }
        // Millis before seconds: once a millis value is a placeholder, its seconds
        // prefix is gone with it and cannot be matched a second time.
        Pattern[] patterns = {
            Pattern.compile(NOT_PRECEDED_BY_DIGIT + window.from()),
            Pattern.compile(NOT_PRECEDED_BY_DIGIT + window.to()),
            Pattern.compile(NOT_PRECEDED_BY_DIGIT + fromS + "(?![0-9])"),
            Pattern.compile(NOT_PRECEDED_BY_DIGIT + toS + "(?![0-9])"),
        };
        String[] tokens = {FROM_MS, TO_MS, FROM_S, TO_S};
        return mapText(query, value -> {
            String out = value;
            for (int i = 0; i < patterns.length; i++) {
                out = patterns[i].matcher(out).replaceAll(Matcher.quoteReplacement(tokens[i]));
            }
            return out;
        });
    }

    /** A copy of {@code query} with {@link #templated}'s placeholders set to {@code window}. */
    static JsonNode materialized(JsonNode query, Window window) {
        if (query.toString().indexOf(MARK) < 0) {
            return query.deepCopy();
        }
        return mapText(query, value -> value.indexOf(MARK) < 0 ? value : value
                .replace(FROM_MS, String.valueOf(window.from()))
                .replace(TO_MS, String.valueOf(window.to()))
                .replace(FROM_S, String.valueOf(window.from() / 1000))
                .replace(TO_S, String.valueOf(window.to() / 1000)));
    }

    /** A copy of {@code node} with {@code fn} applied to every string value (not to keys). */
    private static JsonNode mapText(JsonNode node, UnaryOperator<String> fn) {
        if (node.isTextual()) {
            return TextNode.valueOf(fn.apply(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), mapText(e.getValue(), fn)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(mapText(child, fn)));
            return out;
        }
        return node.deepCopy();
    }

    /** Null when the payload's range cannot be read — such a request is passed through. */
    private Window windowOf(JsonNode payload) {
        try {
            long from = Long.parseLong(payload.path("from").asText());
            long to = Long.parseLong(payload.path("to").asText());
            if (to < from) {
                return null;
            }
            boolean relative = Math.abs(clock.getAsLong() - to) <= RELATIVE_SLACK_MS;
            return new Window(relative, from, to);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** False when any query refers to a sibling or only exists in the browser. */
    static boolean splittable(JsonNode queries) {
        for (JsonNode query : queries) {
            JsonNode ds = query.path(FIELD_DATASOURCE);
            String type = ds.path("type").asText("");
            String uid = ds.isTextual() ? ds.asText() : ds.path("uid").asText("");
            if ("__expr__".equals(type) || "__expr__".equals(uid) || "-100".equals(uid)
                    || uid.startsWith("--") || type.equals(FIELD_DATASOURCE)) {
                return false;
            }
        }
        return true;
    }

    static String keyOf(JsonNode query) {
        ObjectNode normalized = query.deepCopy();
        for (String field : VOLATILE_FIELDS) {
            normalized.remove(field);
        }
        if (!usesStep(query)) {
            normalized.remove("intervalMs");
            normalized.remove("maxDataPoints");
        }
        // Jackson writes an ObjectNode's fields in insertion order, which is the
        // order the browser sent — stable for one dashboard definition.
        return normalized.toString();
    }

    /** Whether intervalMs / maxDataPoints can change this query's answer. */
    static boolean usesStep(JsonNode query) {
        String type = query.path(FIELD_DATASOURCE).path("type").asText("");
        if ("prometheus".equals(type)) {
            return !(query.path("instant").asBoolean(false) && !query.path("range").asBoolean(false));
        }
        if (type.contains("infinity")) {
            String text = query.toString();
            return text.contains("__interval") || text.contains("__rate_interval");
        }
        return true;
    }

    static boolean cacheable(JsonNode result) {
        if (result == null || !result.isObject()) {
            return false;
        }
        if (carriesError(result)) {
            return false;
        }
        JsonNode status = result.path("status");
        return status.isMissingNode() || status.isNull()
            || (status.asInt(200) >= 200 && status.asInt(200) < 300);
    }

    /** Whether {@code result} has a non-empty {@code error}. */
    private static boolean carriesError(JsonNode result) {
        JsonNode error = result.path("error");
        return !error.isMissingNode() && !error.isNull() && !error.asText("").isEmpty();
    }

    /** A copy of {@code result} whose frames name {@code refId}, the asker's own. */
    static JsonNode withRefId(JsonNode result, String refId) {
        JsonNode copy = result.deepCopy();
        for (JsonNode frame : copy.path("frames")) {
            JsonNode schema = frame.path("schema");
            if (schema.isObject()) {
                ((ObjectNode) schema).put(FIELD_REF_ID, refId);
            }
        }
        return copy;
    }

    /** For tests. */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    @Override
    public String toString() {
        return "DashboardQueryCache{fresh=" + freshMs + "ms, maxStale=" + maxStaleMs
                + "ms, entries=" + size() + ", inFlight=" + inFlight.size() + "}";
    }
}
