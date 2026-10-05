package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.service.StreamCatalogService;
import com.trinity.observability.util.SqlSafety;

import jakarta.annotation.PreDestroy;

/**
 * Stream and column discovery, read from OpenObserve's own {@code /streams}
 * endpoint.
 *
 * <p>Nothing here enumerates a stream name. The list is whatever the tenant has,
 * so a stream created by a new exporter shows up in the pickers on its own — which
 * is the whole point: the UI used to offer a hardcoded {@code ['default']}.</p>
 *
 * <h2>Why the name list is served stale rather than re-fetched</h2>
 *
 * <p>{@code /streams?type=metrics} is not a cheap call on this deployment. Measured
 * against the live instance: 19,567 metric streams, a <b>19.2 MB</b> response, and
 * 1.4–7 s of wall clock — because OpenObserve returns every stream's full stats and
 * settings and offers no way to trim them (verified: {@code fetchSchema=false}
 * changes the payload by one byte). All of it is parsed into a Jackson tree so that
 * one {@code name} field can be read off each entry, and the rest is discarded.</p>
 *
 * <p>A plain TTL cache is not enough for that. It makes one caller in every TTL pay
 * the full cost, and in the alert editor that caller is somebody who has just opened
 * a dropdown. So an expired entry is <b>served immediately and refreshed behind the
 * request</b>: the list answers which streams exist, which changes on a deploy, so a
 * few-minute-old answer is correct in every way a picker cares about — and being
 * right a little late beats being right after a seven-second stall.</p>
 *
 * <p>Only the first caller for a given stream type can ever block, and
 * {@link #prewarm()} removes even that on a normal boot.</p>
 */
@Service
public class StreamCatalogServiceImpl implements StreamCatalogService {

    private static final Logger log = LogManager.getLogger(StreamCatalogServiceImpl.class);

    private static final Set<String> KNOWN_TYPES = Set.of("logs", "traces", "metrics");

    /**
     * How long a column lookup is reused.
     *
     * <p>Short enough that a newly-written column appears without a restart, long
     * enough that paging through logs does not add a round-trip per page. Stream
     * schemas change when somebody deploys, not while somebody reads.</p>
     */
    private static final long TTL_MILLIS = 60_000L;

    /**
     * How long a FAILED name fetch is left alone before another is armed.
     *
     * <p>Without it, a refresh that keeps failing is re-armed by every request that
     * sees the stale entry, so an OpenObserve outage turns an idle screen into a
     * retry storm against an instance that is already unwell.</p>
     */
    private static final long REFRESH_BACKOFF_MILLIS = 10_000L;

    private final OpenObserveClientService oo;
    private final OpenObserveConfig config;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /**
     * How long a logs or traces name list stays fresh, in seconds.
     *
     * <p>Unchanged from the original minute. Those lists are tens of streams and a
     * few kilobytes, so there is nothing worth economising on.</p>
     */
    @Value("${alerts.stream-catalog.ttl-seconds:60}")
    private int ttlSeconds = 60;

    /**
     * How long the METRICS name list stays fresh, in seconds.
     *
     * <p>Ten minutes, far longer than the others, because this is the 19 MB fetch and
     * the set of metric names a cluster exposes moves on deploys rather than on the
     * minute. The refresh is off the request path either way; the longer interval
     * simply stops that fetch running sixty times an hour for an answer that had not
     * changed.</p>
     */
    @Value("${alerts.stream-catalog.metrics-ttl-seconds:600}")
    private int metricsTtlSeconds = 600;

    /**
     * Whether to fetch the name lists once at boot.
     *
     * <p>On, so the first person to open the alert editor after a restart does not
     * pay for the cold metrics fetch. Turn it off on a deployment that restarts
     * constantly, or one that never opens a stream picker.</p>
     */
    @Value("${alerts.stream-catalog.prewarm:true}")
    private boolean prewarmEnabled = true;

    /**
     * Runs the background refreshes. Small and daemon: the work is one HTTP call per
     * stream type, and an executor that outlives its use must not hold the JVM open.
     */
    private final ExecutorService refresher = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "stream-catalog-refresh");
        thread.setDaemon(true);
        return thread;
    });

    /** Cache keys (org + type) with a refresh in flight, so a burst of requests arms only one. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    /** key -> earliest millis at which another refresh may be armed after a failure. */
    private final ConcurrentHashMap<String, Long> refreshNotBefore = new ConcurrentHashMap<>();

    @PreDestroy
    void shutdownRefresher() {
        refresher.shutdownNow();
    }

    /** key -> (expiry, value). One map for both lookups; the key carries the kind so
     *  a stream called "logs" cannot collide with the logs type listing.
     *
     *  <p>Every key starts with the ORG. These lists used to be keyed by type alone,
     *  so whichever tenant asked first (normally the prewarm, i.e. the default org)
     *  answered for every other tenant until the entry expired.</p> */
    private final ConcurrentHashMap<String, Cached<List<String>>> nameCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cached<Set<String>>> fieldCache =
            new ConcurrentHashMap<>();

    private record Cached<T>(long expiresAtMillis, T value) {
        boolean fresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    public StreamCatalogServiceImpl(OpenObserveClientService oo, OpenObserveConfig config) {
        this.oo = oo;
        this.config = config;
    }

    /**
     * Fill the name caches once the context is up, off the request path.
     *
     * <p>Failures are swallowed inside {@link #fetchNames}: a prewarm that cannot
     * reach OpenObserve must not fail the boot, and the first real request then
     * simply fetches as it did before.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    void prewarm() {
        if (!prewarmEnabled || !openObserveEnabled) {
            return;
        }
        log.info("Stream catalog: prewarming stream name lists at startup");
        String org = config.getOrg();
        for (String type : KNOWN_TYPES) {
            armRefresh(org, type);
        }
    }

    /** Unknown types are normalised rather than rejected: this feeds a dropdown, and
     *  a typo in a query parameter should not 500 a screen. */
    private static String normaliseType(String streamType) {
        if (streamType == null) {
            return "logs";
        }
        String t = streamType.trim().toLowerCase(Locale.ROOT);
        return KNOWN_TYPES.contains(t) ? t : "logs";
    }

    /** Metrics get their own, much longer TTL — see {@link #metricsTtlSeconds}. */
    private long nameTtlMillis(String type) {
        int seconds = "metrics".equals(type) ? metricsTtlSeconds : ttlSeconds;
        return Math.max(1, seconds) * 1000L;
    }

    @Override
    public List<String> list(String streamType) {
        String type = normaliseType(streamType);
        if (!openObserveEnabled) {
            return List.of();
        }
        String org = config.getOrg();
        Cached<List<String>> hit = nameCache.get(nameKey(org, type));
        if (hit != null) {
            if (!hit.fresh()) {
                // Stale, not absent. Hand back what we have and re-fetch behind the
                // caller — the point of this cache is that nobody waits out a 19 MB
                // metrics listing merely because a timer expired.
                armRefresh(org, type);
            }
            return hit.value();
        }

        // Nothing cached at all, so this one caller blocks as it always did. With
        // prewarm on, that caller is normally the prewarm thread rather than a person.
        // Empty rather than thrown: the picker falls back to the configured stream,
        // which is still queryable. A 500 here would take out the whole Logs screen
        // because its sidebar could not populate a dropdown.
        return fetchNames(org, type);
    }

    private static String nameKey(String org, String type) {
        return org + '\0' + type;
    }

    private void armRefresh(String org, String type) {
        String key = nameKey(org, type);
        Long notBefore = refreshNotBefore.get(key);
        if (notBefore != null && System.currentTimeMillis() < notBefore) {
            return;
        }
        // add() is the guard: it answers false when a refresh is already in flight,
        // so concurrent requests against an expired entry arm exactly one fetch.
        if (!refreshing.add(key)) {
            return;
        }
        try {
            refresher.execute(() -> {
                try {
                    fetchNames(org, type);
                } finally {
                    refreshing.remove(key);
                }
            });
        } catch (RuntimeException e) {
            // Rejected because the executor is shutting down. Release the guard, or
            // this type could never refresh again in a context that is still serving.
            refreshing.remove(key);
            log.debug("Stream name refresh ({}) not scheduled: {}", type, e.getMessage());
        }
    }

    /**
     * Fetch one stream type's names and publish them.
     *
     * @return the names, or an empty list when the fetch failed
     */
    private List<String> fetchNames(String org, String type) {
        String key = nameKey(org, type);
        long startedAt = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        try {
            // The org is passed in rather than read here: a background refresh runs
            // on a pool thread that has no request-scoped OrgContext.
            JsonNode list = oo.getJson("/api/" + org + "/streams?type=" + type)
                    .path("list");
            if (list.isArray()) {
                for (JsonNode n : list) {
                    String name = n.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Stream list ({}) failed: {}", type, e.getMessage());
            // A previously good list is NOT replaced by the failure. Blanking it would
            // empty the picker on a transient error, which is the one outcome worse
            // than a slightly old list.
            refreshNotBefore.put(key, System.currentTimeMillis() + REFRESH_BACKOFF_MILLIS);
            return List.of();
        }
        names.sort(String::compareToIgnoreCase);
        nameCache.put(key, new Cached<>(System.currentTimeMillis() + nameTtlMillis(type), names));
        refreshNotBefore.remove(key);
        log.debug("listStreams({}, {}) -> {} stream(s) in {}ms", org, type, names.size(),
                System.currentTimeMillis() - startedAt);
        return names;
    }

    @Override
    public Set<String> fields(String streamType, String streamName) {
        String type = normaliseType(streamType);
        if (!openObserveEnabled || streamName == null || streamName.isBlank()) {
            return Set.of();
        }
        // Validated before it reaches a URL. The stream name arrives from a query
        // parameter, so it is untrusted however the dropdown was populated.
        String safe;
        try {
            safe = SqlSafety.validateIdentifier(streamName.trim());
        } catch (RuntimeException e) {
            log.warn("Rejected stream name for field lookup: {}", streamName);
            return Set.of();
        }

        String org = config.getOrg();
        String key = org + '\0' + type + '\0' + safe;
        Cached<Set<String>> hit = fieldCache.get(key);
        if (hit != null && hit.fresh()) {
            return hit.value();
        }

        Set<String> out = new LinkedHashSet<>();
        try {
            JsonNode schema = oo.getJson("/api/" + org + "/streams/" + safe
                    + "/schema?type=" + type);
            // OpenObserve has used both shapes across versions; read either rather
            // than pinning to one and silently returning nothing after an upgrade.
            for (JsonNode holder : List.of(schema.path("schema"), schema.path("fields"))) {
                addFieldNames(holder, out);
            }
        } catch (Exception e) {
            log.warn("Field lookup for stream '{}' ({}) failed: {}", safe, type, e.getMessage());
            // NOT cached. An unknown schema must be re-asked, or one transient
            // failure would pin a stream to "adapt to nothing" for the whole TTL.
            return Set.of();
        }

        fieldCache.put(key, new Cached<>(System.currentTimeMillis() + TTL_MILLIS, out));
        log.debug("fields({}, {}) -> {} column(s)", type, safe, out.size());
        return out;
    }

    /** Adds the lower-cased, non-blank "name" of every entry in {@code holder} when it is an array. */
    private static void addFieldNames(JsonNode holder, Set<String> out) {
        if (!holder.isArray()) {
            return;
        }
        for (JsonNode f : holder) {
            String name = f.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                out.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Override
    public boolean hasAll(String streamType, String streamName, String... required) {
        Set<String> present = fields(streamType, streamName);
        if (present.isEmpty()) {
            // Unknown schema. False is the safe answer — see the interface note.
            return false;
        }
        for (String r : required) {
            if (!present.contains(r.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
