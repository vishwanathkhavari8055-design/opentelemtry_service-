package com.trinity.observability.registry;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;

/**
 * The time range a stream actually holds data for, read from OpenObserve.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Opening one trace by id needs a time range, because OpenObserve's search API
 * requires {@code start_time} / {@code end_time} and rejects an unbounded one. Any
 * range this service invents is a guess that decides whether a trace can be found
 * — pick 12h and a day-old trace is unopenable; pick 90d and every lookup scans
 * far beyond the retention.</p>
 *
 * <p>There is no need to guess. OpenObserve publishes each stream's real extent on
 * {@code GET /api/{org}/streams}: {@code stats.doc_time_min} and
 * {@code stats.doc_time_max}, both in epoch MICROSECONDS — the same unit the
 * search API takes, so they are used as-is. Asking for "the whole stream" is then
 * a fact about the deployment rather than a constant in this codebase, and it
 * follows retention automatically as data ages out.</p>
 *
 * <p>Cached behind a short TTL: the bounds move continuously as data arrives, but
 * a value a few seconds old is still a strictly wider net than any fixed window,
 * and the trace view would otherwise pay a metadata round trip on every open.</p>
 */
@Component
public class OpenObserveStreamBounds {

    private static final Logger log = LogManager.getLogger(OpenObserveStreamBounds.class);

    private final OpenObserveClientService client;
    private final OpenObserveConfig config;

    /**
     * How long a reading is reused, in seconds.
     *
     * <p>Only ever makes the window slightly STALE, never wrong in a way that
     * hides data: {@code doc_time_max} is extended by the caller to "now" anyway,
     * and {@code doc_time_min} only moves forward as retention drops old data, so
     * a cached lower bound is if anything wider than the current one.</p>
     */
    @Value("${traces.lookup.stream-bounds-cache-seconds:60}")
    private long cacheSeconds;

    /** Cached per (org, stream, type) — an org switch must not inherit another's extent. */
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public OpenObserveStreamBounds(OpenObserveClientService client, OpenObserveConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Epoch-microsecond extent of a stream, as OpenObserve reports it. */
    public record Bounds(long minMicros, long maxMicros) {
        public boolean usable() {
            return minMicros > 0L && maxMicros > minMicros;
        }
    }

    private record Cached(String key, long readAtNanos, Bounds bounds) {}

    /**
     * The extent of {@code streamName}, or {@code null} when OpenObserve does not
     * report a usable one.
     *
     * <p>Null rather than a fallback range on purpose: the caller knows what to do
     * without it, and inventing a window here would put the guess straight back in.
     * Never throws — a metadata call that fails must not fail the trace view.</p>
     *
     * @param streamType {@code "traces"}, {@code "logs"}, … as the streams API spells it
     */
    public Bounds forStream(String streamName, String streamType) {
        if (streamName == null || streamName.isBlank()) return null;

        String key = config.getOrg() + '/' + streamType + '/' + streamName;
        Cached hit = cache.get();
        if (hit != null && hit.key().equals(key)
                && System.nanoTime() - hit.readAtNanos() < cacheSeconds * 1_000_000_000L) {
            return hit.bounds();
        }

        Bounds bounds = read(streamName, streamType);
        cache.set(new Cached(key, System.nanoTime(), bounds));
        return bounds;
    }

    private Bounds read(String streamName, String streamType) {
        try {
            JsonNode root = client.getJson(
                    "/api/" + config.getOrg() + "/streams?type=" + streamType);
            JsonNode list = root.path("list");
            if (!list.isArray()) return null;

            for (JsonNode entry : list) {
                if (!streamName.equals(entry.path("name").asText(null))) continue;

                JsonNode stats = entry.path("stats");
                long min = stats.path("doc_time_min").asLong(0L);
                long max = stats.path("doc_time_max").asLong(0L);
                Bounds bounds = new Bounds(min, max);

                if (!bounds.usable()) {
                    log.debug("Stream {} ({}) reports no usable doc_time bounds "
                            + "(min={}, max={})", streamName, streamType, min, max);
                    return null;
                }
                log.debug("Stream {} ({}) extent: {} .. {} (µs)",
                        streamName, streamType, min, max);
                return bounds;
            }
            log.debug("Stream {} not present in the {} stream list", streamName, streamType);
            return null;
        } catch (Exception e) {
            // Metadata is an optimisation over a fixed window, never a requirement.
            log.debug("Could not read stream bounds for {} ({}): {}",
                    streamName, streamType, e.getMessage());
            return null;
        }
    }
}
