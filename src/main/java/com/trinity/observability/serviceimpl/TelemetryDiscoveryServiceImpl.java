package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.service.TelemetryDiscoveryService;
import com.trinity.observability.service.TelemetryProbe;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeRangeParser;

import jakarta.annotation.PreDestroy;

/**
 * Runs the probe chain, and answers batched Last Seen lookups for the catalog
 * list.
 *
 * <h2>Where the chain comes from</h2>
 *
 * <p>Configured probes first, in declared order, then any {@link TelemetryProbe}
 * beans someone has contributed in code. Order is not cosmetic: the chain
 * short-circuits per signal, so the canonical {@code service_name} probes have to
 * come before the fallbacks or a resource that has a {@code service_name} would
 * be recorded against {@code k8s_namespace_name} instead — and a namespace
 * identifier filters far more than the operator asked for.</p>
 */
@Service
public class TelemetryDiscoveryServiceImpl implements TelemetryDiscoveryService {

    private static final Logger log = LogManager.getLogger(TelemetryDiscoveryServiceImpl.class);

    /** Token for "whatever {@code openobserve.logs-stream} currently is". */
    private static final String TOKEN_LOGS_STREAM = "@logs";

    /** Token for "whatever {@code openobserve.traces-stream} currently is". */
    private static final String TOKEN_TRACES_STREAM = "@traces";

    /**
     * Names per batched Last Seen query. The IN-list is the cost driver, and a
     * catalog larger than this is paged across several queries rather than
     * producing one enormous statement.
     */
    private static final int LAST_SEEN_BATCH = 200;

    /** The signals a resource can have, in the order results are reported. */
    private static final List<String> ALL_SIGNALS = List.of("logs", "traces", "metrics");

    /**
     * Reusable successful lookups held at once.
     *
     * <p>Only bounds memory. Each entry is a handful of strings, and the cache
     * exists to carry ONE result from a Validate click to the Register click
     * seconds later — not to serve a catalog-sized working set. Oldest entries are
     * dropped when the cap is reached, which at worst costs a re-query.</p>
     */
    private static final int DISCOVERY_CACHE_MAX = 500;

    private final OpenObserveClientService openObserve;
    private final ProductCatalogProperties properties;
    private final List<TelemetryProbe> probes;

    /**
     * Runs one window's probes together instead of one after another.
     *
     * <p>Daemon threads, so an OpenObserve call that never returns cannot hold JVM
     * shutdown open, and named so a thread dump attributes the work.</p>
     */
    private final ExecutorService probePool;

    /**
     * Recent SUCCESSFUL discoveries, so Register does not repeat the chain Validate
     * just ran. Access-ordered and size-capped under its own lock; see
     * {@link #discoverCached}.
     */
    private final Map<String, CachedDiscovery> discoveryCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedDiscovery> eldest) {
                    return size() > DISCOVERY_CACHE_MAX;
                }
            };

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public TelemetryDiscoveryServiceImpl(OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig,
            ProductCatalogProperties properties,
            ObjectProvider<TelemetryProbe> contributedProbes) {
        this.openObserve = openObserve;
        this.properties = properties;
        this.probes = buildChain(openObserve, openObserveConfig, properties, contributedProbes);

        int concurrency = properties.getValidation().getProbeConcurrency();
        AtomicInteger seq = new AtomicInteger();
        this.probePool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "catalog-probe-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        if (log.isInfoEnabled()) {
            log.info("Product Catalog discovery chain ({} probe(s), concurrency {}): {}",
                    probes.size(), concurrency, describeChain());
        }
    }

    @PreDestroy
    void shutdownProbePool() {
        probePool.shutdownNow();
    }

    private static List<TelemetryProbe> buildChain(OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig,
            ProductCatalogProperties properties,
            ObjectProvider<TelemetryProbe> contributedProbes) {
        List<TelemetryProbe> chain = new ArrayList<>();
        for (ProductCatalogProperties.Probe spec : properties.getValidation().getProbes()) {
            TelemetryProbe probe = fromSpec(openObserve, openObserveConfig, spec);
            if (probe != null) {
                chain.add(probe);
            }
        }
        // Code-contributed probes run after the configured ones, so a deployment
        // can still reorder the cheap canonical lookups purely in configuration.
        contributedProbes.orderedStream().forEach(chain::add);
        return List.copyOf(chain);
    }

    /**
     * Turn one configuration entry into a probe, or drop it with a warning.
     *
     * <p>A malformed entry must NOT prevent startup: the catalog is a feature, and
     * a typo in one probe should cost that identifier, not the whole
     * application.</p>
     */
    private static TelemetryProbe fromSpec(OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig,
            ProductCatalogProperties.Probe spec) {
        String signal = spec.getSignal() == null ? "" : spec.getSignal().trim().toLowerCase(Locale.ROOT);
        if (!List.of("logs", "traces", "metrics").contains(signal)) {
            log.warn("Ignoring product-catalog probe '{}' — signal must be logs, traces or metrics", spec);
            return null;
        }
        String stream = spec.getStream() == null ? "" : spec.getStream().trim();
        if (stream.isEmpty() || spec.getColumn() == null || spec.getColumn().isBlank()) {
            log.warn("Ignoring product-catalog probe '{}' — stream and column are both required", spec);
            return null;
        }
        try {
            return new ColumnTelemetryProbe(openObserve, signal,
                    streamSupplier(openObserveConfig, stream), spec.getColumn().trim());
        } catch (RuntimeException e) {
            log.warn("Ignoring product-catalog probe '{}' — {}", spec, e.getMessage());
            return null;
        }
    }

    private static java.util.function.Supplier<String> streamSupplier(OpenObserveConfig config,
            String configured) {
        if (TOKEN_LOGS_STREAM.equalsIgnoreCase(configured)) {
            return config::getLogsStream;
        }
        if (TOKEN_TRACES_STREAM.equalsIgnoreCase(configured)) {
            return config::getTracesStream;
        }
        return () -> configured;
    }

    private String describeChain() {
        List<String> labels = new ArrayList<>(probes.size());
        for (TelemetryProbe probe : probes) {
            labels.add(probe.describe());
        }
        return String.join(" → ", labels);
    }

    @Override
    public boolean isSupported() {
        return openObserveEnabled;
    }

    @Override
    public String windowExpression() {
        return properties.getValidation().getWindow();
    }

    @Override
    public Discovery discover(ResourceCategory category, String resourceName) {
        long startedAt = System.nanoTime();
        long endMicros = TimeRangeParser.defaultEndMicros();
        String primaryWindow = properties.getValidation().getWindow();
        long primaryStart = TimeRangeParser.toMicros(primaryWindow, endMicros, endMicros);

        if (!openObserveEnabled) {
            // No source of truth. Reporting "not found" would be a lie about
            // telemetry; the caller checks isSupported() and says so instead.
            log.info("Product Catalog discovery skipped for '{}' — openobserve.enabled=false", resourceName);
            return new Discovery(false, null, List.of(), List.of(), null, primaryStart, endMicros);
        }

        // Accumulated ACROSS windows, keyed by signal, so a signal found in a
        // narrow window is kept alongside one only the primary window reaches.
        Map<String, TelemetryProbe.Hit> hitsBySignal = new LinkedHashMap<>();
        String foundIn = primaryWindow;

        List<WindowAttempt> attempts = windowAttempts(primaryWindow, primaryStart, endMicros);
        for (int i = 0; i < attempts.size(); i++) {
            WindowAttempt attempt = attempts.get(i);

            // Only the signals still missing. Previously the chain stopped as soon
            // as ANY signal was found, so a resource whose metrics hit in one
            // window never had its logs and traces looked for in another —
            // DataFlowSvc reported "metrics only" while actually having all three.
            Set<String> missing = new LinkedHashSet<>(ALL_SIGNALS);
            missing.removeAll(hitsBySignal.keySet());
            if (missing.isEmpty()) {
                break;
            }

            int before = hitsBySignal.size();
            // The first attempt is the NARROWEST window, and the only one where a
            // probe is allowed to pay for a full-column scan — see probePass.
            probePass(category, resourceName, attempt, missing, hitsBySignal, i == 0);

            if (hitsBySignal.size() > before) {
                foundIn = attempt.label();
            }
        }

        Discovery result = assemble(hitsBySignal, primaryStart, endMicros);
        log.info("Product Catalog discovery for '{}' (category={}): found={}, signals={}, window={}, "
                + "took {} ms", resourceName, category, result.found(), result.signals(),
                result.found() ? foundIn : primaryWindow + " or any narrower window",
                (System.nanoTime() - startedAt) / 1_000_000L);

        if (result.found()) {
            remember(category, resourceName, result);
        }
        return result;
    }

    @Override
    public Discovery discoverCached(ResourceCategory category, String resourceName) {
        int ttlSeconds = properties.getValidation().getDiscoveryCacheSeconds();
        if (ttlSeconds <= 0) {
            return discover(category, resourceName);
        }

        String key = cacheKey(category, resourceName);
        long now = System.nanoTime();
        CachedDiscovery cached;
        synchronized (discoveryCache) {
            cached = discoveryCache.get(key);
            if (cached != null && cached.expiresAtNanos() <= now) {
                discoveryCache.remove(key);
                cached = null;
            }
        }

        if (cached == null) {
            return discover(category, resourceName);
        }
        log.info("Product Catalog reusing the validation of '{}' (category={}) from {} s ago — "
                + "signals={}", resourceName, category,
                (ttlSeconds * 1_000_000_000L - (cached.expiresAtNanos() - now)) / 1_000_000_000L,
                cached.discovery().signals());
        return cached.discovery();
    }

    /**
     * Records a successful lookup for the registration path to reuse.
     *
     * <p>Deliberately not called for a miss — see
     * {@link TelemetryDiscoveryService#discoverCached}.</p>
     */
    private void remember(ResourceCategory category, String resourceName, Discovery result) {
        int ttlSeconds = properties.getValidation().getDiscoveryCacheSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        CachedDiscovery entry = new CachedDiscovery(result,
                System.nanoTime() + ttlSeconds * 1_000_000_000L);
        synchronized (discoveryCache) {
            discoveryCache.put(cacheKey(category, resourceName), entry);
        }
    }

    /**
     * Scoped to the org and folded for case, matching how a lookup is performed.
     *
     * <p>The org has to be part of the key: the same resource name means different
     * telemetry in different orgs, and a key without it would let one tenant's
     * validation satisfy another's registration.</p>
     */
    private static String cacheKey(ResourceCategory category, String resourceName) {
        String org = OrgContext.get();
        return (org == null ? "" : org) + '\0' + category.name() + '\0'
                + resourceName.trim().toLowerCase(Locale.ROOT);
    }

    /** A successful discovery and the moment it stops being reusable. */
    private record CachedDiscovery(Discovery discovery, long expiresAtNanos) {
    }

    /**
     * Every window worth searching, NARROWEST FIRST.
     *
     * <p>The set is unchanged — the primary plus each configured fallback that is
     * genuinely narrower — but the order is reversed, and that is most of why
     * registering a resource went from over a minute to a few seconds.</p>
     *
     * <p>A query's cost is dominated by how much of the stream it has to touch,
     * and these windows differ by two orders of magnitude. The same case-folding
     * lookup against this deployment's logs stream measured 0.3 s over
     * {@code now-1h}, 3.6 s over {@code now-6h} and 19-23 s over {@code now-24h}.
     * Starting wide meant every registration paid the 24h scan before anything
     * else could happen, even for a service that had emitted a log line seconds
     * earlier — which is the normal case, because an operator registers a
     * resource precisely when it has just started reporting.</p>
     *
     * <p>Starting narrow costs nothing in coverage. A signal missing from the
     * narrow window is still looked for in the wider ones ({@link #discover} only
     * ever drops the signals it has already found), and the WIDEST window is
     * still what a "not found" is reported against. What changes is that the
     * expensive scan is skipped entirely once the cheap one has answered.</p>
     */
    private List<WindowAttempt> windowAttempts(String primaryWindow, long primaryStart, long endMicros) {
        List<WindowAttempt> attempts = new ArrayList<>();
        attempts.add(new WindowAttempt(primaryWindow, primaryStart, endMicros));

        for (String fallback : properties.getValidation().getFallbackWindows()) {
            if (fallback != null && !fallback.isBlank()) {
                try {
                    long start = TimeRangeParser.toMicros(fallback.trim(), endMicros, endMicros);
                    if (start > primaryStart) {
                        attempts.add(new WindowAttempt(fallback.trim(), start, endMicros));
                    }
                } catch (RuntimeException e) {
                    log.warn("Ignoring product-catalog fallback window '{}': {}", fallback, e.getMessage());
                }
            }
        }

        // Latest start = narrowest window. A stable sort, so two windows
        // configured to the same span keep their declared order instead of
        // swapping between calls.
        attempts.sort(Comparator.comparingLong(WindowAttempt::start).reversed());
        return attempts;
    }

    /**
     * Runs every applicable probe for one window CONCURRENTLY, then resolves each
     * signal in configured probe order.
     *
     * <p>Concurrency is the speed half of the change. Each probe is a round trip to
     * OpenObserve of roughly three quarters of a second; issued in series, nine of
     * them across three windows measured twenty seconds for a resource that does not
     * exist. Issued together, a window costs about one round trip.</p>
     *
     * <p>Resolving in CONFIGURED order rather than completion order is what keeps
     * the answer deterministic: the canonical {@code service_name} probe must win
     * over the {@code k8s_namespace_name} fallback for the same signal every time,
     * or the recorded binding — and therefore what gets filtered out of Logs, Traces
     * and Metrics later — would depend on which query happened to return first.</p>
     *
     * <p>{@code exhaustive} is passed straight through to the probes and is true
     * for the narrowest window only. A probe that folds case has an indexed
     * lookup and a full-column scan available to it, and the scan is affordable
     * over an hour of the large stream (0.3 s) but not over a day (23 s) — so the
     * wide windows get the indexed lookup and the narrow one gets both. See
     * {@link com.trinity.observability.service.TelemetryProbe#probe(String, long, long, boolean)}.</p>
     */
    private void probePass(ResourceCategory category, String resourceName, WindowAttempt window,
            Set<String> missing, Map<String, TelemetryProbe.Hit> hitsBySignal,
            boolean exhaustive) {

        List<TelemetryProbe> applicable = applicableProbes(category, missing);
        if (applicable.isEmpty()) {
            return;
        }

        // The org lives in a ThreadLocal that OpenObserveConfig.getOrg() reads, and
        // a pool thread has none of the request's state — so without carrying it
        // across, every parallel probe would silently query the DEFAULT org instead
        // of the one the request is scoped to. On this instance `default` has 638
        // streams and `DLH` has 12,655, so that is a wrong answer, not a slow one.
        String org = OrgContext.get();

        List<Future<TelemetryProbe.Hit>> futures =
                submitProbes(applicable, org, resourceName, window, exhaustive);

        List<TelemetryProbe.Hit> results = awaitProbeResults(futures, applicable, resourceName);
        if (results.isEmpty()) {
            return;
        }

        // Configured order, not completion order — see the note above.
        for (int i = 0; i < applicable.size(); i++) {
            TelemetryProbe.Hit hit = results.get(i);
            if (hit != null) {
                hitsBySignal.putIfAbsent(hit.signal(), hit);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Product Catalog discovery pass for '{}' (category={}) over window {} with "
                    + "{} probe(s) for {}: signals now {}", resourceName, category, window.label(),
                    applicable.size(), missing, hitsBySignal.keySet());
        }
    }

    /** The configured probes, in order, that cover a still-missing signal for the category. */
    private List<TelemetryProbe> applicableProbes(ResourceCategory category, Set<String> missing) {
        List<TelemetryProbe> applicable = new ArrayList<>();
        for (TelemetryProbe probe : probes) {
            if (missing.contains(probe.signal()) && probe.supports(category)) {
                applicable.add(probe);
            }
        }
        return applicable;
    }

    /** Submits one probe per entry to the pool, each run under {@code org}; futures in input order. */
    private List<Future<TelemetryProbe.Hit>> submitProbes(List<TelemetryProbe> applicable,
            String org, String resourceName, WindowAttempt window, boolean exhaustive) {
        List<Future<TelemetryProbe.Hit>> futures = new ArrayList<>(applicable.size());
        for (TelemetryProbe probe : applicable) {
            // withOrg restores the previous value afterwards: pool threads are
            // reused, so a value left set here would leak this request's tenant
            // onto the next resource validated.
            futures.add(probePool.submit(() -> withOrg(org,
                    () -> runSafely(probe, resourceName, window.start(), window.end(), exhaustive))));
        }
        return futures;
    }

    /**
     * Waits for every probe future, in submission order.
     *
     * @return one slot per probe (null for a miss or a failure), or empty list when the
     *         wait was interrupted and the pass should be abandoned
     */
    private List<TelemetryProbe.Hit> awaitProbeResults(List<Future<TelemetryProbe.Hit>> futures,
            List<TelemetryProbe> applicable, String resourceName) {
        List<TelemetryProbe.Hit> results = new ArrayList<>(Collections.nCopies(applicable.size(), null));
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.set(i, futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Product Catalog discovery interrupted for '{}'", resourceName);
                futures.forEach(future -> future.cancel(true));
                return Collections.emptyList();
            } catch (ExecutionException e) {
                // runSafely already absorbs probe failures, so arriving here means
                // something unexpected. One bad probe must not lose the whole pass.
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("Product Catalog probe {} failed for '{}': {}",
                        applicable.get(i).describe(), resourceName, cause.getMessage());
            }
        }
        return results;
    }

    /** Builds the Discovery from the per-signal hits accumulated across windows. */
    private Discovery assemble(Map<String, TelemetryProbe.Hit> hitsBySignal,
            long startMicros, long endMicros) {
        if (hitsBySignal.isEmpty()) {
            return new Discovery(false, null, List.of(), List.of(), null, startMicros, endMicros);
        }
        List<TelemetryBinding> bindings = new ArrayList<>(hitsBySignal.size());
        List<String> signals = new ArrayList<>(hitsBySignal.size());
        Long newest = null;
        String resolvedName = null;

        // ALL_SIGNALS order, so the response always reads logs, traces, metrics
        // regardless of which window each signal was actually found in.
        for (String signal : ALL_SIGNALS) {
            TelemetryProbe.Hit hit = hitsBySignal.get(signal);
            if (hit == null) {
                continue;
            }
            bindings.add(new TelemetryBinding(hit.signal(), hit.stream(), hit.column(), hit.matchedValue()));
            signals.add(hit.signal());
            if (hit.lastSeenMicros() > 0 && (newest == null || hit.lastSeenMicros() > newest)) {
                newest = hit.lastSeenMicros();
            }
            // The first signal in canonical order decides the recorded spelling.
            // Later signals can legitimately disagree (a Kubernetes label vs. an SDK
            // service name), and letting that vary would make the stored identifier
            // depend on probe ordering in a way nobody could predict.
            if (resolvedName == null) {
                resolvedName = hit.matchedValue();
            }
        }
        return new Discovery(true, resolvedName, List.copyOf(bindings), List.copyOf(signals),
                newest, startMicros, endMicros);
    }

    /** One window to try, with the human label used in logs. */
    private record WindowAttempt(String label, long start, long end) {
    }

    /**
     * A probe that throws despite its contract must not abort the chain.
     *
     * @return the hit, or null for a miss or a failure
     */
    private TelemetryProbe.Hit runSafely(TelemetryProbe probe, String resourceName,
            long startMicros, long endMicros, boolean exhaustive) {
        try {
            Optional<TelemetryProbe.Hit> hit =
                    probe.probe(resourceName, startMicros, endMicros, exhaustive);
            return hit.orElse(null);
        } catch (Exception e) {
            log.warn("Product Catalog probe {} failed for '{}': {}",
                    probe.describe(), resourceName, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Long> lastSeen(Map<String, List<TelemetryBinding>> bindingsByResourceId) {
        if (!openObserveEnabled || bindingsByResourceId == null || bindingsByResourceId.isEmpty()) {
            return Map.of();
        }
        long endMicros = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(properties.getValidation().getWindow(), endMicros, endMicros);

        // Invert the input: one query per (signal, stream, column), each carrying
        // every value that needs checking against it. This is what makes the cost
        // independent of catalog size.
        Map<ProbeKey, Map<String, List<String>>> byProbe = groupByProbe(bindingsByResourceId);

        // Concurrent: written by the pool threads below, which fold several
        // (stream, column) groups onto the same resource id when a resource emits
        // more than one signal. merge() on this map is atomic; on a HashMap it
        // would lose a signal's timestamp under exactly that overlap.
        Map<String, Long> newestById = new ConcurrentHashMap<>();
        Map<ProbeKey, Set<String>> pending = new LinkedHashMap<>();
        for (Map.Entry<ProbeKey, Map<String, List<String>>> group : byProbe.entrySet()) {
            pending.put(group.getKey(), new LinkedHashSet<>(group.getValue().keySet()));
        }

        // The org lives in a ThreadLocal a pool thread does not inherit, and every
        // query below builds its URL from it — same hazard, and same fix, as the
        // parallel probe pass. Read once here, on the calling thread.
        String org = OrgContext.get();

        // Narrowest window first, for the same reason discovery does it: the cost
        // of a query is set by how much of the stream it touches, and a registered
        // resource is normally still emitting — so the cheap window answers for
        // almost every row, and only the genuinely quiet ones make it as far as
        // the expensive one.
        for (WindowAttempt attempt : windowAttempts(properties.getValidation().getWindow(),
                startMicros, endMicros)) {
            if (!lastSeenWindow(attempt, byProbe, pending, org, newestById)) {
                break;
            }
        }
        return newestById;
    }

    /**
     * Inverts resource id -> bindings into (signal, stream, column) -> value ->
     * the resource ids that claimed that value.
     */
    private static Map<ProbeKey, Map<String, List<String>>> groupByProbe(
            Map<String, List<TelemetryBinding>> bindingsByResourceId) {
        Map<ProbeKey, Map<String, List<String>>> byProbe = new LinkedHashMap<>();
        for (Map.Entry<String, List<TelemetryBinding>> entry : bindingsByResourceId.entrySet()) {
            for (TelemetryBinding binding : entry.getValue()) {
                ProbeKey key = new ProbeKey(binding.signal(), binding.stream(), binding.column());
                byProbe.computeIfAbsent(key, unused -> new LinkedHashMap<>())
                        .computeIfAbsent(binding.value(), unused -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }
        return byProbe;
    }

    /**
     * One window of the last-seen refresh.
     *
     * @return true when some value is still unresolved and a wider window should
     *         be tried; false when there is nothing left to ask, or the wait was
     *         interrupted
     */
    private boolean lastSeenWindow(WindowAttempt attempt, Map<ProbeKey, Map<String, List<String>>> byProbe,
            Map<ProbeKey, Set<String>> pending, String org, Map<String, Long> newestById) {
        // One query per (signal, stream, column), ISSUED TOGETHER rather than
        // one after another. The groups are independent — a logs lookup tells
        // a traces lookup nothing — and each is a full round trip to
        // OpenObserve, so in series a window cost the SUM of them. The catalog
        // list is polled and this is the whole of its latency.
        List<ProbeKey> active = new ArrayList<>();
        List<Future<Set<String>>> futures = new ArrayList<>();
        for (Map.Entry<ProbeKey, Map<String, List<String>>> group : byProbe.entrySet()) {
            Set<String> unresolved = pending.get(group.getKey());
            if (unresolved.isEmpty()) {
                continue;
            }
            // Only the values still without a timestamp. A resource answered by
            // the narrow window is not asked about again, which is what keeps
            // the widest query small even on a catalog with a few dead rows.
            ProbeKey key = group.getKey();
            Map<String, List<String>> idsByValue = group.getValue();
            Set<String> wanted = new LinkedHashSet<>(unresolved);
            active.add(key);
            futures.add(probePool.submit(() -> withOrg(org,
                    () -> queryLastSeen(key, idsByValue, wanted,
                            attempt.start(), attempt.end(), newestById))));
        }
        if (active.isEmpty()) {
            return false;
        }
        return awaitLastSeen(futures, active, pending);
    }

    /**
     * Waits for one window's queries and drops every answered value from
     * {@code pending}.
     *
     * @return true when some group still has unresolved values; false otherwise,
     *         or when the wait was interrupted
     */
    private boolean awaitLastSeen(List<Future<Set<String>>> futures, List<ProbeKey> active,
            Map<ProbeKey, Set<String>> pending) {
        boolean stillLooking = false;
        for (int i = 0; i < futures.size(); i++) {
            Set<String> answered;
            try {
                answered = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Last-seen refresh interrupted");
                futures.forEach(future -> future.cancel(true));
                return false;
            } catch (ExecutionException e) {
                // queryLastSeen already absorbs query failures, so arriving here
                // means something unexpected. One bad group must not lose the
                // Last Seen of every other resource in the catalog.
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("Last-seen refresh failed for {}: {}", active.get(i), cause.getMessage());
                answered = Set.of();
            }
            Set<String> unresolved = pending.get(active.get(i));
            unresolved.removeAll(answered);
            if (!unresolved.isEmpty()) {
                stillLooking = true;
            }
        }
        return stillLooking;
    }

    /**
     * Run {@code body} on a pool thread under the caller's organization.
     *
     * <p>Pool threads are reused, so the previous value is restored rather than
     * cleared — leaving this request's tenant set would scope the next task to the
     * wrong org.</p>
     */
    private static <T> T withOrg(String org, java.util.function.Supplier<T> body) {
        String previous = OrgContext.get();
        try {
            if (org == null) {
                OrgContext.clear();
            } else {
                OrgContext.set(org);
            }
            return body.get();
        } finally {
            if (previous == null) {
                OrgContext.clear();
            } else {
                OrgContext.set(previous);
            }
        }
    }

    /**
     * One batched {@code GROUP BY} over a single (stream, column), folding the
     * result back onto whichever resource ids claimed each value.
     *
     * @param wanted the identifier values to ask about — a subset of
     *               {@code idsByValue}, so a caller escalating through windows can
     *               drop the ones already answered
     * @return the values that came back with a timestamp
     */
    private Set<String> queryLastSeen(ProbeKey key, Map<String, List<String>> idsByValue,
            Set<String> wanted, long startMicros, long endMicros, Map<String, Long> newestById) {
        Set<String> matchedValues = new LinkedHashSet<>();
        String stream;
        String column;
        try {
            stream = SqlSafety.validateIdentifier(key.stream());
            column = SqlSafety.validateIdentifier(key.column());
        } catch (RuntimeException e) {
            log.debug("Last-seen refresh skipped for {}: {}", key, e.getMessage());
            return matchedValues;
        }

        List<String> values = new ArrayList<>(wanted);
        for (int offset = 0; offset < values.size(); offset += LAST_SEEN_BATCH) {
            List<String> batch = values.subList(offset, Math.min(values.size(), offset + LAST_SEEN_BATCH));
            StringBuilder inList = buildInList(batch);

            String sql = "SELECT " + column + " AS matched_value, MAX(_timestamp) AS last_seen "
                    + "FROM \"" + stream + "\" "
                    + "WHERE _timestamp >= " + startMicros + " "
                    + "AND _timestamp <= " + endMicros + " "
                    + "AND " + column + " IN (" + inList + ") "
                    + "GROUP BY " + column;

            List<Map<String, Object>> hits;
            try {
                hits = openObserve.search(sql, startMicros, endMicros, 0, batch.size(), key.signal());
            } catch (Exception e) {
                // A missing stream means "no recent telemetry", which the caller
                // renders as a stale Last Seen — not as an error.
                log.debug("Last-seen refresh miss for {}: {}", key, e.getMessage());
                continue;
            }

            foldLastSeenHits(hits, idsByValue, matchedValues, newestById);
        }
        return matchedValues;
    }

    /** The batch's values as a quoted, escaped SQL IN list body. */
    private static StringBuilder buildInList(List<String> batch) {
        StringBuilder inList = new StringBuilder();
        for (String value : batch) {
            if (!inList.isEmpty()) {
                inList.append(", ");
            }
            inList.append('\'').append(SqlSafety.escapeStringLiteral(value)).append('\'');
        }
        return inList;
    }

    /** Folds one batch's rows onto the resource ids that claimed each matched value. */
    private static void foldLastSeenHits(List<Map<String, Object>> hits, Map<String, List<String>> idsByValue,
            Set<String> matchedValues, Map<String, Long> newestById) {
        for (Map<String, Object> hit : hits) {
            foldLastSeenHit(hit, idsByValue, matchedValues, newestById);
        }
    }

    /** Folds one row onto the resource ids that claimed its matched value. */
    private static void foldLastSeenHit(Map<String, Object> hit, Map<String, List<String>> idsByValue,
            Set<String> matchedValues, Map<String, Long> newestById) {
        Object value = hit.get("matched_value");
        Number lastSeen = (Number) hit.get("last_seen");
        if (value instanceof String matched && lastSeen != null) {
            List<String> ids = idsByValue.get(matched);
            if (ids != null) {
                matchedValues.add(matched);
                for (String id : ids) {
                    newestById.merge(id, lastSeen.longValue(), Math::max);
                }
            }
        }
    }

    /** Grouping key for the batched refresh. */
    private record ProbeKey(String signal, String stream, String column) {
        @Override
        public String toString() {
            return signal + ':' + stream + ':' + column;
        }
    }
}
