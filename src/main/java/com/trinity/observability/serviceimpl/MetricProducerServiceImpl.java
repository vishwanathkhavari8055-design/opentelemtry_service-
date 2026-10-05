package com.trinity.observability.serviceimpl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.MetricProducerProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricCatalogDTO;
import com.trinity.observability.dto.metrics.MetricProducersDTO;
import com.trinity.observability.service.MetricProducerService;
import com.trinity.observability.service.PromMetricsService;
import com.trinity.observability.util.MetricClassifier;
import com.trinity.observability.util.TimeRangeParser;

import jakarta.annotation.PreDestroy;

/**
 * Builds the service-to-metrics index by sweeping the catalogue.
 *
 * <h2>One probe per metric, because nothing else is answerable</h2>
 *
 * <p>{@link MetricProducerProperties} records the four reverse-index query
 * shapes this backend refuses. What is left is a probe per metric name:
 * {@code count by (service_name) (<metric>)} as a range query. The aggregation
 * is the whole trick — it makes the engine collapse the label sets server-side,
 * so the answer arrives in hundreds of bytes instead of the hundreds of
 * kilobytes {@code /series} would return for the same metric.</p>
 *
 * <h2>Asynchronous, and honest about it</h2>
 *
 * <p>A full sweep is 3,215 probes and 90 seconds. No request waits for that, so
 * {@link #getProducers()} always returns immediately: it starts a build if none
 * is cached and reports progress, and it serves a stale index while a refresh
 * runs behind it. A labelled ten-minute-old answer is more useful than a
 * spinner, and the age is in the response for the UI to show.</p>
 *
 * <h2>Emitted vs observed</h2>
 *
 * <p>{@code service_name} does not mean "this service exposed this metric" — it
 * means "this sample was attached to this OTel resource". kube-state-metrics
 * describes every pod in the cluster, so each pod's name turns up on 200
 * {@code kube_*} metrics it does not export. The split is therefore by
 * classified group: {@code platform} metrics are observations ABOUT a service,
 * everything else is the service's own. {@link #kindOf} then derives the
 * service's kind from the emitted set alone, so a pod that Kubernetes merely
 * talks about is never promoted to a microservice.</p>
 */
@Service
public class MetricProducerServiceImpl implements MetricProducerService {

    private static final Logger log = LogManager.getLogger(MetricProducerServiceImpl.class);

    /** Kinds that exist only once the catalogue is inverted. See the DTO. */
    private static final String KIND_SCRAPE_ONLY = "scrape-only";
    private static final String KIND_OBSERVED_ONLY = "observed-only";

    private static final Map<String, String> EXTRA_KIND_LABELS = Map.of(
            KIND_SCRAPE_ONLY, "Scraped, no telemetry of its own",
            KIND_OBSERVED_ONLY, "Described by the platform only");

    /**
     * Technologies that say nothing about what a service exposes.
     *
     * <p>{@code up} and {@code scrape_*} are produced BY the scrape, for every
     * target, whether or not the target exposes anything. Counting them as
     * emitted metrics would classify 44 bare scrape targets here as part of the
     * observability stack, which is backwards — they are the things being
     * watched, not the thing doing the watching.</p>
     */
    private static final Set<String> SCRAPE_ONLY_TECHS = Set.of("Scrape health", "Target health");

    /**
     * How often a running sweep republishes what it has so far.
     *
     * <p>Every 200 probes, so roughly a dozen times across a full build. The
     * reason to publish early at all: the first question anyone brings to this
     * screen is about ONE service, and that service's metrics are usually known
     * within the first few seconds. Making them wait out the remaining eighty
     * for services they did not ask about is a spinner where there could be an
     * answer.</p>
     *
     * <p>The reason not to publish on every probe is that each publish rebuilds
     * the whole service list; a dozen times is free, three thousand is not.</p>
     */
    private static final int PUBLISH_EVERY = 200;

    /** A built index, plus what it cost. Immutable; swapped in atomically. */
    private record Snapshot(List<MetricProducersDTO.Service> services,
                            Instant builtAt,
                            long buildMillis,
                            int windowSeconds,
                            int metricsProbed,
                            int metricsWithNoData,
                            int metricsUnscoped,
                            int probeFailures,
                            /**
                             * True while this came from a sweep still running,
                             * so counts are lower bounds and services may be
                             * missing. Surfaced, never smoothed over: a partial
                             * index that looked complete would read as "that
                             * service exposes nothing".
                             */
                            boolean partial) {}

    /** How the probes of one sweep turned out so far; see {@link #recordProbe}. */
    private record ProbeCounts(int probed, int noData, int unscoped, int failures) {}

    private final OpenObserveClientService client;
    private final OpenObserveConfig config;
    private final PromMetricsService promMetricsService;
    private final MetricProducerProperties props;

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();
    private final AtomicBoolean building = new AtomicBoolean(false);
    private final AtomicInteger progressDone = new AtomicInteger();
    private final AtomicInteger progressTotal = new AtomicInteger();

    /** Runs the build itself. One thread: two concurrent sweeps are never wanted. */
    private final ExecutorService builder =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "metric-producer-index");
                t.setDaemon(true);
                return t;
            });

    public MetricProducerServiceImpl(OpenObserveClientService client,
                                     OpenObserveConfig config,
                                     PromMetricsService promMetricsService,
                                     MetricProducerProperties props) {
        this.client = client;
        this.config = config;
        this.promMetricsService = promMetricsService;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        builder.shutdownNow();
    }

    /** Optional boot-time build, so the first visitor does not wait 90 seconds. */
    @EventListener(ApplicationReadyEvent.class)
    void prewarm() {
        if (props.isEnabled() && props.isPrewarm()) {
            log.info("Metric producer index: prewarming at startup");
            startBuild();
        }
    }

    @Override
    public MetricProducersDTO getProducers() {
        if (!props.isEnabled()) {
            MetricProducersDTO out = new MetricProducersDTO();
            out.setSupported(false);
            out.setError("metrics.producers.enabled=false");
            out.setServices(List.of());
            return out;
        }

        Snapshot snap = cache.get();
        // A partial snapshot counts as stale: it was published mid-sweep and
        // carries a fresh timestamp, so without this a build that died would
        // leave a half-swept index looking current until restart. Harmless
        // while the sweep is alive, because startBuild() no-ops then.
        boolean stale = snap == null
                || snap.partial()
                || Duration.between(snap.builtAt(), Instant.now()).toSeconds() >= props.getTtlSeconds();
        if (stale) startBuild();

        return respond(snap);
    }

    @Override
    public MetricProducersDTO refresh() {
        if (!props.isEnabled()) return getProducers();
        // The cache is NOT cleared: a running build should not blank the screen
        // for 90 seconds. The stale answer stays visible, labelled with its age,
        // until the new one replaces it.
        startBuild();
        return respond(cache.get());
    }

    /** Shape whatever is known right now into a response. Never blocks. */
    private MetricProducersDTO respond(Snapshot snap) {
        MetricProducersDTO out = new MetricProducersDTO();
        out.setBuilding(building.get());
        out.setProgressDone(progressDone.get());
        out.setProgressTotal(progressTotal.get());
        out.setWindowSeconds(props.getWindowSeconds());

        if (snap == null) {
            out.setServices(List.of());
            return out;
        }
        out.setServices(snap.services());
        out.setPartial(snap.partial());
        out.setBuiltAt(snap.builtAt().toString());
        out.setAgeSeconds(Duration.between(snap.builtAt(), Instant.now()).toSeconds());
        out.setBuildMillis(snap.buildMillis());
        out.setWindowSeconds(snap.windowSeconds());
        out.setMetricsProbed(snap.metricsProbed());
        out.setMetricsWithNoData(snap.metricsWithNoData());
        out.setMetricsUnscoped(snap.metricsUnscoped());
        out.setProbeFailures(snap.probeFailures());
        return out;
    }

    /** Start a sweep unless one is already running. */
    private void startBuild() {
        if (!building.compareAndSet(false, true)) return;
        try {
            builder.submit(() -> {
                try {
                    build();
                } catch (Exception e) {
                    log.warn("Metric producer index build failed: {}", e.toString());
                } finally {
                    building.set(false);
                }
            });
        } catch (Exception e) {
            // Rejected because the pool is shutting down. Release the flag, or
            // the index would stay permanently "building" until restart.
            building.set(false);
            log.warn("Metric producer index build could not be scheduled: {}", e.toString());
        }
    }

    private void build() {
        long t0 = System.nanoTime();

        MetricCatalogDTO catalog = promMetricsService.getCatalog();
        if (!catalog.isSupported() || catalog.getItems() == null || catalog.getItems().isEmpty()) {
            log.warn("Metric producer index: catalogue unavailable, nothing to sweep");
            return;
        }
        List<MetricCatalogDTO.Item> items = catalog.getItems();
        progressTotal.set(items.size());
        progressDone.set(0);

        long endSec = TimeRangeParser.defaultEndMicros() / 1_000_000L;
        long startSec = endSec - props.getWindowSeconds();

        // Classification is looked up rather than recomputed: the catalogue
        // already carries the group and technology this service must agree with.
        Map<String, MetricCatalogDTO.Item> byName = new HashMap<>(items.size());
        items.forEach(i -> byName.put(i.getName(), i));

        // service_name -> metric names. Concurrent because the probes below run
        // in parallel and every one of them writes here.
        Map<String, Set<String>> byService = new ConcurrentHashMap<>();
        AtomicInteger noData = new AtomicInteger();
        AtomicInteger unscoped = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        int threads = Math.max(1, props.getConcurrency());
        // Built for Java 18, where ExecutorService is not AutoCloseable; the pool is
        // shut down in the finally block below.
        @SuppressWarnings("java:S2095")
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "metric-producer-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (MetricCatalogDTO.Item item : items) {
                futures.add(pool.submit(() -> {
                    try {
                        Optional<List<String>> services = probe(item.getName(), startSec, endSec);
                        recordProbe(services, item.getName(), byService, noData, unscoped, failures);
                    } finally {
                        int done = progressDone.incrementAndGet();
                        // Publish what is known so far, so the screen fills in
                        // while the sweep runs instead of after it.
                        if (done % PUBLISH_EVERY == 0) {
                            publish(byService, byName,
                                    new ProbeCounts(items.size(), noData.get(), unscoped.get(), failures.get()),
                                    (System.nanoTime() - t0) / 1_000_000L, true);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // Already counted by the task itself; a failed probe makes
                    // the index partial, never absent.
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        long millis = (System.nanoTime() - t0) / 1_000_000L;
        List<MetricProducersDTO.Service> services = publish(byService, byName,
                new ProbeCounts(items.size(), noData.get(), unscoped.get(), failures.get()), millis, false);

        Map<String, Long> kinds = services.stream()
                .collect(Collectors.groupingBy(MetricProducersDTO.Service::getKind, Collectors.counting()));
        log.info("Metric producer index built: {} service(s) from {} metric(s) in {} ms "
                        + "(no data in window: {}, unscoped: {}, failed probes: {}) kinds={}",
                services.size(), items.size(), millis,
                noData.get(), unscoped.get(), failures.get(), kinds);
    }

    /** Count one probe's outcome and index the services it named. */
    private static void recordProbe(Optional<List<String>> probed, String metricName,
                                    Map<String, Set<String>> byService, AtomicInteger noData,
                                    AtomicInteger unscoped, AtomicInteger failures) {
        if (probed.isEmpty()) {
            failures.incrementAndGet();
            return;
        }
        List<String> services = probed.get();
        if (services.isEmpty()) {
            noData.incrementAndGet();
        } else if (!indexNamedServices(services, metricName, byService)) {
            unscoped.incrementAndGet();
        }
    }

    /** @return whether any of {@code services} carried a name */
    private static boolean indexNamedServices(List<String> services, String metricName,
                                              Map<String, Set<String>> byService) {
        boolean anyNamed = false;
        for (String s : services) {
            if (s == null || s.isBlank()) continue;
            anyNamed = true;
            byService.computeIfAbsent(s, k -> ConcurrentHashMap.newKeySet())
                    .add(metricName);
        }
        return anyNamed;
    }

    /**
     * Build the service list from what has been collected and swap it in.
     *
     * <p>Called a dozen times mid-sweep with {@code partial=true} and once at
     * the end with {@code partial=false}. Reading {@code byService} while probes
     * are still writing to it is safe and deliberately unsynchronised: it is a
     * {@link ConcurrentHashMap} of concurrent sets, so a partial publish sees a
     * consistent-enough prefix of the sweep. A metric that lands a millisecond
     * later simply appears in the next publish.</p>
     *
     * <h2>A partial publish never replaces a complete index</h2>
     *
     * <p>Mid-sweep publishing exists for the FIRST build, where a partial answer
     * beats a spinner. On a REBUILD it is strictly a regression, and a damaging
     * one: the complete index is thrown away and replaced by a prefix of the new
     * sweep, so every service not yet re-probed reads as having no metrics until
     * the sweep reaches it.</p>
     *
     * <p>That is not theoretical on this deployment. The catalogue here holds
     * ~19,500 metrics and a full sweep takes far longer than the TTL that starts
     * the next one, so the index would spend almost all of its life partial —
     * and the alert editor's metric picker, which asks this index which metrics
     * a service emits, would fall back to offering all 19,500 for most of the
     * day. The symptom looks exactly like the scoping never having worked.</p>
     *
     * <p>So a partial publish is skipped while a COMPLETE snapshot is held. The
     * stale-but-complete answer keeps being served, labelled with its age, which
     * is the behaviour this class already promises for a refresh in flight.</p>
     */
    private List<MetricProducersDTO.Service> publish(Map<String, Set<String>> byService,
                                                     Map<String, MetricCatalogDTO.Item> byName,
                                                     ProbeCounts counts, long millis, boolean partial) {
        List<MetricProducersDTO.Service> services = byService.entrySet().stream()
                // Copy the metric set: toService sorts and stores it, and the
                // live set is still being written to by the running probes.
                .map(e -> toService(e.getKey(), Set.copyOf(e.getValue()), byName))
                .sorted(serviceOrder())
                .toList();

        Snapshot next = new Snapshot(List.copyOf(services), Instant.now(), millis,
                props.getWindowSeconds(), counts.probed(), counts.noData(), counts.unscoped(),
                counts.failures(), partial);

        if (partial) {
            // Only fills a gap; never overwrites a better answer. A complete
            // snapshot loses only to the complete snapshot that replaces it.
            cache.compareAndSet(null, next);
            Snapshot held = cache.get();
            if (held != null && held.partial() && held != next) {
                // Progress within the same first build: a longer prefix of the
                // sweep is strictly better than a shorter one.
                cache.compareAndSet(held, next);
            }
        } else {
            cache.set(next);
        }
        return services;
    }

    /**
     * Distinct {@code service_name} values for one metric.
     *
     * @return the values (possibly containing one blank, meaning "present but
     *         unscoped"), an empty list when the metric wrote nothing in the
     *         window, or an empty {@code Optional} when the probe itself failed —
     *         the three cases are counted separately because they mean different things.
     */
    private Optional<List<String>> probe(String metric, long startSec, long endSec) {
        String query = "count by (service_name) (" + metric + ")";
        String path = "/api/" + config.getOrg() + "/prometheus/api/v1/query_range"
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=" + startSec
                + "&end=" + endSec
                + "&step=" + Math.max(1, props.getStepSeconds());

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return serviceNames(client.getJson(path));
            } catch (Exception e) {
                if (attempt == 1) {
                    log.debug("Producer probe failed for {}: {}", metric, e.toString());
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The distinct {@code service_name} values in one probe answer; empty
     * {@code Optional} when the answer reports a failure.
     */
    private static Optional<List<String>> serviceNames(JsonNode root) {
        // Prometheus reports failure in the body with HTTP 200 here, so
        // status has to be read explicitly.
        if (!"success".equals(root.path("status").asText("success"))) {
            return Optional.empty();
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) return Optional.of(List.of());
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode s : result) {
            out.add(s.path("metric").path("service_name").asText(""));
        }
        return Optional.of(new ArrayList<>(out));
    }

    /** Split one service's metrics into emitted vs observed and derive its kind. */
    private MetricProducersDTO.Service toService(String name, Set<String> metrics,
                                                 Map<String, MetricCatalogDTO.Item> byName) {
        List<String> emitted = new ArrayList<>();
        List<String> observed = new ArrayList<>();
        for (String m : metrics) {
            MetricCatalogDTO.Item item = byName.get(m);
            String group = item == null ? MetricClassifier.GROUP_OTHER : item.getGroup();
            if (MetricClassifier.GROUP_PLATFORM.equals(group)) observed.add(m);
            else emitted.add(m);
        }
        emitted.sort(String.CASE_INSENSITIVE_ORDER);
        observed.sort(String.CASE_INSENSITIVE_ORDER);

        String kind = kindOf(emitted, byName);
        String label = EXTRA_KIND_LABELS.containsKey(kind)
                ? EXTRA_KIND_LABELS.get(kind)
                : MetricClassifier.groupLabel(kind);

        return new MetricProducersDTO.Service(name, kind, label,
                bucket(emitted, byName), bucket(observed, byName));
    }

    /**
     * What kind of thing this producer is, from what it emits.
     *
     * <p>Three rules, each answering a distinct question the raw counts get
     * wrong:</p>
     *
     * <ol>
     *   <li>Nothing emitted → {@code observed-only}. The name is known only
     *       because platform telemetry mentions it.</li>
     *   <li>Only {@code up} / {@code scrape_*} → {@code scrape-only}. We scrape
     *       it; it exposes nothing itself.</li>
     *   <li>Otherwise the dominant group, ignoring {@code runtime}. Ignoring it
     *       matters: an exporter written in Go emits 151 {@code go_*} metrics
     *       against 25 of the thing it actually exports, so counting shared
     *       runtime metrics would label every Go exporter a "runtime".</li>
     * </ol>
     */
    private static String kindOf(List<String> emitted, Map<String, MetricCatalogDTO.Item> byName) {
        if (emitted.isEmpty()) return KIND_OBSERVED_ONLY;

        Map<String, Integer> groups = new LinkedHashMap<>();
        boolean anyRealTelemetry = false;
        for (String m : emitted) {
            MetricCatalogDTO.Item item = byName.get(m);
            if (!isRealTelemetry(item)) continue;
            anyRealTelemetry = true;
            if (!MetricClassifier.GROUP_RUNTIME.equals(item.getGroup())) {
                groups.merge(item.getGroup(), 1, Integer::sum);
            }
        }
        if (!anyRealTelemetry) return KIND_SCRAPE_ONLY;
        if (groups.isEmpty()) return MetricClassifier.GROUP_RUNTIME;
        return groups.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(MetricClassifier.GROUP_OTHER);
    }

    /** A classified metric whose technology says something about its producer
     *  (so not {@code up} / {@code scrape_*}). */
    private static boolean isRealTelemetry(MetricCatalogDTO.Item item) {
        return item != null && !SCRAPE_ONLY_TECHS.contains(item.getTechnology());
    }

    /**
     * Turn a list of metric names into a bucket, carrying each metric's
     * technology and type alongside its name.
     *
     * <p>Carrying the technology per metric — not only the aggregate counts —
     * is what lets the caller render the list GROUPED. A flat list of 268 names
     * under one heading would reproduce, inside a single service, the same
     * undifferentiated wall this whole feature exists to break up.</p>
     */
    private static MetricProducersDTO.Bucket bucket(List<String> metrics,
                                                    Map<String, MetricCatalogDTO.Item> byName) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<MetricProducersDTO.Metric> out = new ArrayList<>(metrics.size());
        for (String m : metrics) {
            MetricCatalogDTO.Item item = byName.get(m);
            String tech = item == null ? "Unclassified" : item.getTechnology();
            String type = item == null ? "unknown" : item.getType();
            counts.merge(tech, 1, Integer::sum);
            out.add(new MetricProducersDTO.Metric(m, tech, type));
        }
        List<MetricCatalogDTO.Technology> techs = counts.entrySet().stream()
                .map(e -> new MetricCatalogDTO.Technology(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(MetricCatalogDTO.Technology::getCount).reversed()
                        .thenComparing(MetricCatalogDTO.Technology::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        // Ordered by technology (biggest first, matching the chips above the
        // list) then by name, so the grouping the caller renders needs no sort.
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < techs.size(); i++) rank.put(techs.get(i).getName(), i);
        out.sort(Comparator
                .comparingInt((MetricProducersDTO.Metric m) -> rank.getOrDefault(m.getTechnology(), Integer.MAX_VALUE))
                .thenComparing(MetricProducersDTO.Metric::getName, String.CASE_INSENSITIVE_ORDER));

        return new MetricProducersDTO.Bucket(metrics.size(), techs, List.copyOf(out));
    }

    /**
     * Display order: our microservices first, then the kinds in the classifier's
     * order, then the two "exposes nothing" kinds last — they are a long tail
     * (213 of the 363 here) and burying the 20 interesting rows under them would
     * undo the point of the screen. Within a kind, most metrics first.
     */
    private static Comparator<MetricProducersDTO.Service> serviceOrder() {
        List<String> order = new ArrayList<>(MetricClassifier.groupOrder());
        order.add(KIND_SCRAPE_ONLY);
        order.add(KIND_OBSERVED_ONLY);
        return Comparator
                .comparingInt((MetricProducersDTO.Service s) -> {
                    int i = order.indexOf(s.getKind());
                    return i < 0 ? order.size() : i;
                })
                .thenComparing(s -> -s.getEmitted().getCount())
                .thenComparing(MetricProducersDTO.Service::getName, String.CASE_INSENSITIVE_ORDER);
    }
}
