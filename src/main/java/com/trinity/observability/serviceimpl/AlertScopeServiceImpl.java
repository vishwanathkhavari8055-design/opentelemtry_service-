package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertScopeResponseDTO;
import com.trinity.observability.dto.alerts.AlertsResponseDTO;
import com.trinity.observability.dto.metrics.MetricProducersDTO;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.AlertScopeService;
import com.trinity.observability.service.AlertsService;
import com.trinity.observability.service.MetricProducerService;
import com.trinity.observability.service.StreamCatalogService;
import com.trinity.observability.util.SqlSafety;

import jakarta.annotation.PreDestroy;

/**
 * Scoped choices for the alert editor.
 *
 * <h2>Where the answers come from</h2>
 *
 * <p>Two of the three are read off indexes that already exist. "Which metrics
 * does this service emit" is answered by {@link MetricProducerService}, which
 * maintains that index and pays a 3,000-probe sweep to build it; "which
 * resources exist and what telemetry were they proven against" is answered by
 * the Product Catalog registry.</p>
 *
 * <p>The third — "which LOG or TRACE streams carry this service" — has no index
 * behind it, and cannot have one built the same way. A metric stream is named
 * after its metric, so the producer index can enumerate; a log stream is named
 * after whoever created it and carries whatever was written to it, so the only
 * way to know is to look. {@link #probeStreams} asks each candidate for a single
 * row over a lookback window and caches the answer. It is the one thing here
 * that costs queries, and it is confined to logs and traces, where stream counts
 * are in the tens rather than the thousands.</p>
 *
 * <h2>The fallback rule, applied everywhere</h2>
 *
 * <p>Every method answers with the WIDE list and {@code scoped=false} when it
 * cannot narrow, never with an empty one. Three separate things can leave the
 * scope unresolvable — the producer index still sweeping, a catalog resource
 * registered while validation was off, OpenObserve unreachable — and all three
 * are transient. An empty dropdown in that moment is indistinguishable from a
 * broken screen and stops the operator dead; a full dropdown with a caveat lets
 * them finish the rule they came to write.</p>
 */
@Service
public class AlertScopeServiceImpl implements AlertScopeService {

    private static final Logger log = LogManager.getLogger(AlertScopeServiceImpl.class);

    /**
     * The column that carries service identity in every OTel signal.
     *
     * <p>One constant rather than a per-signal lookup because OpenTelemetry
     * defines {@code service.name} as a resource attribute, and the OpenObserve
     * exporter flattens it to the same {@code service_name} column in logs,
     * traces and metrics alike. A catalog resource that was proven against a
     * different column says so in its binding, and that wins — see
     * {@link #filterFor}.</p>
     */
    private static final String SERVICE_COLUMN = "service_name";

    /**
     * Other columns a stream may identify a service by, tried in this order once
     * {@code service_name} is not present.
     *
     * <p>These are what the Kubernetes log scraper writes. A pod's logs collected
     * by the collector rather than exported by an SDK carry no
     * {@code service.name} resource attribute at all — the identity is the
     * container or the workload — so a picker that only knew
     * {@code service_name} would declare that no stream carries the service and
     * fall back to showing all of them, which is the behaviour being fixed.</p>
     *
     * <p>Order is most-specific first: a container name identifies one process,
     * a deployment name a set of replicas, an app label whatever the chart chose
     * to call the thing.</p>
     */
    private static final List<String> FALLBACK_IDENTITY_COLUMNS = List.of(
            "k8s_container_name", "container_name", "k8s_deployment_name",
            "k8s_app", "app", "app_kubernetes_io_name", "kubernetes_container_name");

    /** Metric kinds, as {@link AlertScopeResponseDTO.Target#getKind()} spells them. */
    private static final String KIND_EMITTED = "emitted";
    private static final String KIND_OBSERVED = "observed";
    private static final String KIND_STREAM = "stream";

    private static final String STREAM_TYPE_METRICS = "metrics";
    private static final String SCOPE_SERVICE = "service";
    private static final String SCOPE_RESOURCE = "resource";
    private static final String SCOPE_PRODUCT = "product";

    /**
     * How long a DEFINITIVE stream probe is reused.
     *
     * <p>The picker is opened repeatedly while one rule is written, and the
     * answer changes when somebody deploys, not while somebody types. Matches
     * the stream catalog's own TTL so the two cannot disagree for long about
     * what a stream is.</p>
     */
    private static final long PROBE_TTL_MILLIS = 60_000L;

    /**
     * How long an UNKNOWN is reused — much less.
     *
     * <p>Caching a failure at all is deliberate: without it, an OpenObserve
     * outage turns every open dropdown into a retry loop. But an UNKNOWN is a
     * failure, not an answer, and holding one for a full minute means a single
     * slow query poisons the picker long after the instance recovered. Observed
     * exactly that here — one trace probe timed out and the next fourteen
     * requests were served "could not be determined" from cache while the
     * backend was perfectly healthy.</p>
     */
    private static final long PROBE_FAILURE_TTL_MILLIS = 5_000L;

    /**
     * Total wall-clock budget for one sweep of stream probes.
     *
     * <p>Separate from any per-query timeout, because the thing being protected
     * is different: a per-query timeout bounds one probe, this bounds what the
     * person staring at a dropdown waits. Anything unfinished is UNKNOWN, which
     * keeps the stream in the list rather than dropping it.</p>
     */
    private static final long PROBE_BUDGET_MILLIS = 6_000L;

    /**
     * Probes run in parallel on this pool.
     *
     * <p>Small and fixed: the queries are cheap single-row reads, but OpenObserve
     * is also serving whatever screens people are looking at, and a picker is not
     * entitled to a burst. Daemon threads so an executor that outlives its use
     * cannot hold the JVM open.</p>
     */
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "alert-scope-probe");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdownProbeExecutor() {
        probeExecutor.shutdownNow();
    }

    private final MetricProducerService metricProducers;
    private final ProductCatalogRegistry catalog;
    private final AlertsService alerts;
    private final StreamCatalogService streamCatalog;
    private final OpenObserveClientService oo;
    private final OpenObserveConfig config;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /**
     * How far back a stream is checked for the scope, in minutes.
     *
     * <p>A day by default. Short enough that OpenObserve can scan it — a 72h
     * scan already fails on this instance where 48h succeeds — and long enough
     * that a service which was quiet over lunch does not vanish from the
     * picker.</p>
     */
    @Value("${alerts.scope.stream-lookback-minutes:1440}")
    private int lookbackMinutes = 1440;

    private final ConcurrentHashMap<String, CachedProbe> probeCache = new ConcurrentHashMap<>();

    public AlertScopeServiceImpl(MetricProducerService metricProducers,
                                 ProductCatalogRegistry catalog,
                                 AlertsService alerts,
                                 StreamCatalogService streamCatalog,
                                 OpenObserveClientService oo,
                                 OpenObserveConfig config) {
        this.metricProducers = metricProducers;
        this.catalog = catalog;
        this.alerts = alerts;
        this.streamCatalog = streamCatalog;
        this.oo = oo;
        this.config = config;
    }

    // ── scopes ──────────────────────────────────────────────────────────────

    @Override
    public AlertScopeResponseDTO.ScopeList listScopes() {
        String org = config.getOrg();
        AlertScopeResponseDTO.ScopeList out = new AlertScopeResponseDTO.ScopeList();
        out.setOrg(org);

        MetricProducersDTO producers = safeProducers();
        Map<String, MetricProducersDTO.Service> byName = indexByName(producers);

        out.setBuilding(producers.isBuilding());
        out.setPartial(producers.isPartial());
        out.setProgressDone(producers.getProgressDone());
        out.setProgressTotal(producers.getProgressTotal());

        List<AlertScopeResponseDTO.ScopeOption> items = new ArrayList<>();

        // Catalog first. A registered resource is something somebody deliberately
        // said they care about, whereas a producer-index service is merely a name
        // that appeared in telemetry — so the curated list leads.
        List<CatalogResource> resources = safeResources(org);
        addCatalogOptions(resources, byName, items);

        // Then services seen in telemetry that the catalog does not already
        // account for. Deliberately not filtered away: the catalog is what
        // somebody registered, and the gap between that and what is actually
        // running is exactly the thing an operator writing an alert needs to see.
        addUncoveredServices(resources, byName, items);

        items.sort(Comparator
                .comparing((AlertScopeResponseDTO.ScopeOption o) -> typeRank(o.getType()))
                .thenComparing(o -> o.getLabel() == null ? "" : o.getLabel(),
                        String.CASE_INSENSITIVE_ORDER));

        out.setItems(items);
        out.setTotal(items.size());
        out.setNotice(scopeNotice(producers, items.size()));

        log.info("listScopes() org={} returned {} scope(s) (producer index building={}, partial={})",
                org, items.size(), producers.isBuilding(), producers.isPartial());
        return out;
    }

    /**
     * One option per enabled catalog resource, then one per product those
     * resources belong to (in first-seen order).
     */
    private void addCatalogOptions(List<CatalogResource> resources,
            Map<String, MetricProducersDTO.Service> byName,
            List<AlertScopeResponseDTO.ScopeOption> items) {
        Map<String, List<CatalogResource>> byProduct = new LinkedHashMap<>();

        for (CatalogResource resource : resources) {
            if (!resource.isEnabled()) {
                // A disabled resource is hidden from every other screen in this
                // application; offering it here would let somebody write an alert
                // on telemetry the rest of the product is filtering out.
                continue;
            }
            items.add(resourceOption(resource, byName));
            if (resource.getProductCode() != null) {
                byProduct.computeIfAbsent(resource.getProductCode(), k -> new ArrayList<>())
                         .add(resource);
            }
        }

        byProduct.forEach((code, members) -> items.add(productOption(code, members, byName)));
    }

    /** One option per producer-index service that no enabled catalog resource names. */
    private void addUncoveredServices(List<CatalogResource> resources,
            Map<String, MetricProducersDTO.Service> byName,
            List<AlertScopeResponseDTO.ScopeOption> items) {
        Set<String> alreadyCovered = new LinkedHashSet<>();
        for (CatalogResource resource : resources) {
            if (resource.isEnabled()) {
                alreadyCovered.add(resource.effectiveName());
            }
        }
        for (MetricProducersDTO.Service service : byName.values()) {
            if (service.getName() != null && !alreadyCovered.contains(service.getName())) {
                items.add(serviceOption(service));
            }
        }
    }

    // ── targets ─────────────────────────────────────────────────────────────

    @Override
    public AlertScopeResponseDTO.TargetList listTargets(String scopeType, String scopeKey,
            String streamType, String include) {

        String org = config.getOrg();
        String type = normaliseStreamType(streamType);

        AlertScopeResponseDTO.TargetList out = new AlertScopeResponseDTO.TargetList();
        out.setOrg(org);
        out.setStreamType(type);
        out.setScopeType(blankToNull(scopeType));
        out.setScopeKey(blankToNull(scopeKey));

        Scope scope = resolve(org, scopeType, scopeKey);
        out.setScopeLabel(scope.label());
        out.setResolvedNames(new ArrayList<>(scope.names()));

        if (STREAM_TYPE_METRICS.equals(type)) {
            fillMetricTargets(out, scope, include);
        } else {
            fillStreamTargets(out, scope, type);
        }

        out.setTotal(out.getItems().size());
        log.info("listTargets(scope={}:{}, streamType={}) -> {} target(s), scoped={}",
                out.getScopeType(), out.getScopeKey(), type, out.getTotal(), out.isScoped());
        return out;
    }

    /**
     * Metric names the scope actually writes.
     *
     * <p>Read straight off the producer index rather than re-probed: that index
     * is the only thing on this deployment that knows the answer, because
     * OpenObserve has no query that returns "metrics where service_name = X"
     * across three thousand metric streams in one go.</p>
     */
    private void fillMetricTargets(AlertScopeResponseDTO.TargetList out, Scope scope,
            String include) {

        MetricProducersDTO producers = safeProducers();
        out.setBuilding(producers.isBuilding());
        out.setPartial(producers.isPartial());

        if (scope.names().isEmpty()) {
            wideMetricList(out, scope.unscopedReason());
            return;
        }
        if (!producers.isSupported()) {
            wideMetricList(out, producerIndexUnavailableNotice(scope, producers));
            return;
        }

        Map<String, MetricProducersDTO.Service> byName = indexByName(producers);

        // Keyed by metric name: a product spanning several services will list the
        // same platform metric for each of them, and offering it twice in a
        // dropdown reads as two different metrics.
        Map<String, AlertScopeResponseDTO.Target> found = new LinkedHashMap<>();
        boolean anyKnown = collectScopeMetrics(found, scope, byName, include);

        if (!anyKnown) {
            // The scope resolved to a name the index has never seen. That is a
            // real finding — most often a service registered in the catalog that
            // is not currently emitting — and saying so is more useful than
            // silently widening, so the notice names the situation exactly.
            wideMetricList(out, noMetricsSeenNotice(scope, producers));
            return;
        }

        List<AlertScopeResponseDTO.Target> items = new ArrayList<>(found.values());
        items.sort(Comparator
                // Emitted first: a service's own instrumentation is what somebody
                // scoping to it almost always means, and platform metrics about it
                // outnumber them several times over.
                .comparing((AlertScopeResponseDTO.Target t) -> KIND_EMITTED.equals(t.getKind()) ? 0 : 1)
                .thenComparing(AlertScopeResponseDTO.Target::getName, String.CASE_INSENSITIVE_ORDER));

        out.setItems(items);
        out.setScoped(true);
        // No filter for metrics: in OpenObserve a metric IS a stream, so choosing
        // one from this list has already confined the rule. Adding a redundant
        // service_name predicate would also break the multi-service product case,
        // where the metric legitimately carries several.
        out.setFilter(null);
        if (producers.isBuilding() || producers.isPartial()) {
            out.setNotice("The metric index is still building (" + producers.getProgressDone()
                    + "/" + producers.getProgressTotal() + "). This list is a lower bound and "
                    + "may grow.");
        }
    }

    private static String producerIndexUnavailableNotice(Scope scope, MetricProducersDTO producers) {
        return "Metrics could not be narrowed to " + scope.label()
                + " because the producer index is unavailable"
                + (producers.getError() == null ? "" : " (" + producers.getError() + ")")
                + ". Showing every metric — check that the one you pick is emitted by "
                + "this service, or a rule written on it will never fire.";
    }

    private static String noMetricsSeenNotice(Scope scope, MetricProducersDTO producers) {
        return (producers.isBuilding()
                ? "The metric index is still building, so "
                : "No metrics have been seen for ")
                + String.join(", ", scope.names())
                + (producers.isBuilding()
                    ? " has no metrics listed yet. Showing every metric meanwhile."
                    : " in the last " + producers.getWindowSeconds() + "s. Showing every "
                      + "metric — if this service should be emitting, the gap is in "
                      + "instrumentation, not in this list.");
    }

    /**
     * Collects the emitted and/or observed metrics of every scope name the index
     * knows, and reports whether any scope name was known at all.
     */
    private boolean collectScopeMetrics(Map<String, AlertScopeResponseDTO.Target> found, Scope scope,
            Map<String, MetricProducersDTO.Service> byName, String include) {
        boolean wantEmitted = !KIND_OBSERVED.equalsIgnoreCase(include);
        boolean wantObserved = !KIND_EMITTED.equalsIgnoreCase(include);
        boolean anyKnown = false;

        for (String name : scope.names()) {
            MetricProducersDTO.Service service = byName.get(name);
            if (service == null) {
                continue;
            }
            anyKnown = true;
            if (wantEmitted) {
                collect(found, service.getEmitted(), KIND_EMITTED);
            }
            if (wantObserved) {
                collect(found, service.getObserved(), KIND_OBSERVED);
            }
        }
        return anyKnown;
    }

    /**
     * Log and trace streams that actually carry the scope.
     *
     * <p>Unlike metrics, a log stream is not named after what it contains, so
     * there is no index to read the answer off — it has to be asked. Each
     * candidate stream is probed for a single row belonging to the scope over a
     * lookback window, and only the streams that answer are offered.</p>
     *
     * <p>Probing rather than guessing, because every guess is wrong here. A
     * tenant on this deployment has streams written by the OTel SDK, by the k8s
     * log scraper and by a RUM exporter; they carry different identity columns,
     * and which of them holds a given service is a fact about what is deployed,
     * not something derivable from a name. Listing all of them is the complaint
     * this method exists to answer; listing a hardcoded subset would be the same
     * bug with fewer rows.</p>
     *
     * <p>Cost is one cheap query per stream, cached — and only for logs and
     * traces, where stream counts are in the tens. Field sets come from
     * {@link com.trinity.observability.service.StreamCatalogService}, which
     * already caches them for the log reader, so a stream with no identity
     * column is skipped without a query at all.</p>
     */
    private void fillStreamTargets(AlertScopeResponseDTO.TargetList out, Scope scope, String type) {
        List<String> candidates = safeStreams(type);

        if (scope.names().isEmpty()) {
            // Nothing selected. The full list IS the right answer here, and it is
            // not a fallback — so it is not dressed up as one.
            for (String name : candidates) {
                out.getItems().add(new AlertScopeResponseDTO.Target(name, KIND_STREAM, null, null));
            }
            out.setScoped(false);
            out.setNotice(scope.unscopedReason());
            out.setFilter(null);
            return;
        }

        StreamProbe probe = probeStreams(scope, type, candidates);
        List<String> chosen;

        if (!probe.carrying().isEmpty()) {
            // Streams that answered with a row. The ordinary case, and the only
            // one where the list is evidence rather than inference.
            chosen = new ArrayList<>(probe.carrying().keySet());
            chosen.addAll(probe.unknown());
            out.setScoped(true);
            if (!probe.unknown().isEmpty()) {
                out.setNotice(probe.unknown().size() + " stream(s) could not be checked ("
                        + String.join(", ", probe.unknown()) + ") and are included in case they "
                        + "carry " + scope.label() + " too. The rest are confirmed.");
            }
        } else if (!probe.empty().isEmpty()) {
            // At least one stream was checked cleanly and had nothing. That is a
            // finding about the telemetry rather than about our ability to look,
            // so the list stays narrowed to the streams where such a rule is
            // expressible at all instead of reverting to everything.
            chosen = new ArrayList<>(probe.empty());
            chosen.addAll(probe.unknown());
            out.setScoped(true);
            out.setNotice("No " + type + " for " + scope.label() + " in the last "
                    + lookbackDescription() + ". These are the " + type + " streams that carry a "
                    + "service identity at all, so a rule here is still expressible — but check "
                    + "the service is emitting before relying on it.");
        } else {
            // Nothing was determined: every stream unreadable, every window
            // unscannable, or no stream carries a service identity at all. Wide
            // list with the reason, because an empty picker is indistinguishable
            // from a broken screen and stops the operator dead.
            chosen = candidates;
            out.setScoped(false);
            out.setNotice(undeterminedStreamsNotice(probe, scope, type));
        }

        for (String name : chosen) {
            AlertScopeResponseDTO.Target target =
                    new AlertScopeResponseDTO.Target(name, KIND_STREAM, null, null);
            // Per stream, because the streams disagree: the SDK writes
            // service_name where the k8s scraper writes k8s_container_name. One
            // predicate for the whole list would be wrong for half of it.
            target.setFilterColumn(probe.columns().get(name));
            out.getItems().add(target);
        }

        // The list-level filter is the best default for the scope, and correct
        // for any stream whose identity column matches it. For the rest, the
        // per-target filterColumn and /fields are authoritative.
        out.setFilter(filterFor(scope, type, null));
    }

    /** Why the stream list could not be narrowed: the probe's own reason when it has one. */
    private static String undeterminedStreamsNotice(StreamProbe probe, Scope scope, String type) {
        if (probe.reason() != null) {
            return probe.reason();
        }
        return probe.columns().isEmpty()
                ? "No " + type + " stream carries a service identity column, so this "
                  + "list could not be narrowed to " + scope.label() + "."
                : "Which " + type + " streams carry " + scope.label() + " could not be "
                  + "determined, so every stream is listed. The condition filter below "
                  + "still confines the rule to it.";
    }

    /**
     * What a sweep of the candidate streams found.
     *
     * <p>{@code empty} and {@code unknown} are kept apart, and that separation is
     * the whole reliability of this feature. "Checked, and it holds nothing" is a
     * fact about the telemetry that justifies narrowing the list; "could not
     * check" is a fact about us that does not. Merged into one bucket, an
     * OpenObserve hiccup would silently remove the operator's own stream from the
     * picker — which looks exactly like the narrowing working.</p>
     *
     * @param carrying stream to identity column, for streams holding the scope
     * @param empty    streams checked cleanly that held nothing in the window
     * @param unknown  streams whose answer could not be obtained
     * @param columns  stream to identity column, for every stream that has one
     * @param reason   why nothing could be determined, or null
     */
    private record StreamProbe(Map<String, String> carrying, Set<String> empty,
                               Set<String> unknown, Map<String, String> columns, String reason) { }

    private StreamProbe probeStreams(Scope scope, String type, List<String> candidates) {
        Map<String, String> carrying = new LinkedHashMap<>();
        Set<String> empty = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        Map<String, String> columns = new LinkedHashMap<>();

        // A proven binding is better evidence than any probe: it came from a query
        // that actually returned rows at registration time. Taken as read rather
        // than re-asked.
        addProvenBindings(scope, type, carrying, columns);

        if (!openObserveEnabled) {
            return new StreamProbe(carrying, empty, unknown, columns,
                    "OpenObserve is not enabled, so which streams carry " + scope.label()
                    + " cannot be checked.");
        }
        if (candidates.isEmpty()) {
            return new StreamProbe(carrying, empty, unknown, columns,
                    "No " + type + " streams were returned, so nothing could be checked.");
        }

        long endMicros = micros(Instant.now());
        long startMicros = endMicros - lookbackMinutes * 60L * 1_000_000L;

        // Which streams need asking, decided before any query is issued: a
        // stream with no identity column is excluded here and costs nothing.
        Map<String, String> toProbe = streamsToProbe(scope, type, candidates, carrying, columns);

        // In parallel, and this is not an optimisation. Seventeen log streams
        // asked one after another took 25 seconds against this instance — for a
        // DROPDOWN, on every cache miss. Nobody waits that long; they conclude
        // the editor is broken. The probes are independent single-row reads, so
        // there is nothing to serialise them for.
        Map<String, CompletableFuture<ProbeResult>> pending = new LinkedHashMap<>();
        toProbe.forEach((stream, column) -> pending.put(stream, CompletableFuture.supplyAsync(
                () -> cachedProbe(type, stream, column, scope.names(), startMicros, endMicros),
                probeExecutor)));

        // A hard ceiling on the whole sweep, separate from any per-query timeout.
        // Whatever has not answered by then is UNKNOWN, which keeps the stream in
        // the picker — the safe direction. A dropdown that is late is a dropdown
        // that is broken, and one slow stream must not hold up the other sixteen.
        long deadline = System.nanoTime() + PROBE_BUDGET_MILLIS * 1_000_000L;
        pending.forEach((stream, future) -> {
            ProbeResult result = awaitProbe(type, stream, future, deadline);
            switch (result) {
                case CARRIES -> carrying.put(stream, toProbe.get(stream));
                case EMPTY -> empty.add(stream);
                // NOT folded in with EMPTY. An unanswered question must never
                // remove somebody's stream from the picker, so an unknown stream
                // stays a candidate — but it must not be counted as evidence
                // that the narrowing worked either.
                case UNKNOWN -> unknown.add(stream);
            }
        });
        return new StreamProbe(carrying, empty, unknown, columns, null);
    }

    /**
     * A proven binding is better evidence than any probe: it came from a query
     * that actually returned rows at registration time. Taken as read rather
     * than re-asked.
     */
    private static void addProvenBindings(Scope scope, String type, Map<String, String> carrying,
            Map<String, String> columns) {
        for (TelemetryBinding binding : scope.bindings()) {
            if (type.equalsIgnoreCase(binding.signal()) && binding.stream() != null
                    && scope.names().contains(binding.value())) {
                carrying.put(binding.stream(), binding.column());
                columns.put(binding.stream(), binding.column());
            }
        }
    }

    /**
     * Which streams need asking, decided before any query is issued: a stream
     * with no identity column is excluded here and costs nothing.
     */
    private Map<String, String> streamsToProbe(Scope scope, String type, List<String> candidates,
            Map<String, String> carrying, Map<String, String> columns) {
        Map<String, String> toProbe = new LinkedHashMap<>();
        for (String stream : candidates) {
            if (!carrying.containsKey(stream)) {
                String column = identityColumn(type, stream, scope);
                if (column != null) {
                    columns.put(stream, column);
                    toProbe.put(stream, column);
                }
            }
        }
        return toProbe;
    }

    /** Waits for one probe within the sweep's deadline; anything but an answer is UNKNOWN. */
    private static ProbeResult awaitProbe(String type, String stream,
            CompletableFuture<ProbeResult> future, long deadline) {
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.UNKNOWN;
        } catch (Exception e) {
            log.debug("Stream probe did not complete for {}/{}: {}", type, stream,
                    e.toString());
            return ProbeResult.UNKNOWN;
        }
    }

    /** The outcome of asking one stream whether it holds the scope. */
    private enum ProbeResult { CARRIES, EMPTY, UNKNOWN }

    /**
     * A probe, reused for {@link #PROBE_TTL_MILLIS}.
     *
     * <p>The picker is opened repeatedly while a rule is written, and the answer
     * changes when somebody deploys, not while somebody types. Without this, one
     * editing session re-runs the same sweep every time the dropdown opens.</p>
     *
     * <p>Only the RESULT is cached, never the reason: an UNKNOWN is cached too,
     * and deliberately, so an OpenObserve outage does not turn one slow dropdown
     * into a stream of retries from every open editor.</p>
     */
    private ProbeResult cachedProbe(String type, String stream, String column,
            List<String> names, long startMicros, long endMicros) {

        String key = type + '|' + stream + '|' + column + '|' + String.join(",", names);
        CachedProbe cached = probeCache.get(key);
        if (cached != null && cached.fresh()) {
            return cached.result();
        }
        ProbeResult result = runProbe(stream, column, names, type, startMicros, endMicros);
        long ttl = result == ProbeResult.UNKNOWN ? PROBE_FAILURE_TTL_MILLIS : PROBE_TTL_MILLIS;
        probeCache.put(key, new CachedProbe(System.currentTimeMillis() + ttl, result));
        return result;
    }

    private record CachedProbe(long expiresAtMillis, ProbeResult result) {
        boolean fresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    /**
     * Asks one stream for a single row belonging to the scope.
     *
     * <p>{@code searchDetailed}, not {@code search}: OpenObserve answers a window
     * it cannot scan with HTTP 200 and no hits, which is indistinguishable from
     * "this stream does not carry the service". Read as the latter, it would drop
     * the operator's own stream out of the picker — the one failure this feature
     * must not have, because it looks exactly like the feature working.</p>
     */
    private ProbeResult runProbe(String stream, String column, List<String> values,
            String type, long startMicros, long endMicros) {

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(quote(column)).append(" FROM ").append(quote(stream))
                .append(" WHERE ").append(quote(column)).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "" : ", ")
               .append('\'').append(SqlSafety.escapeStringLiteral(values.get(i))).append('\'');
        }
        sql.append(')');

        try {
            // size=1: the question is "any row at all", and asking for more would
            // make a dropdown pay for data nobody reads.
            OpenObserveClientService.SearchOutcome outcome = oo.searchDetailed(
                    sql.toString(), startMicros, endMicros, 0, 1, type);
            if (!outcome.hits().isEmpty()) {
                return ProbeResult.CARRIES;
            }
            return outcome.isComplete() ? ProbeResult.EMPTY : ProbeResult.UNKNOWN;
        } catch (Exception e) {
            // A stream that rejects the query — a column dropped since the schema
            // was cached, a stream mid-rotation — is UNKNOWN, never absent.
            log.debug("Stream probe failed for {}/{} on {}: {}", type, stream, column,
                    e.getMessage());
            return ProbeResult.UNKNOWN;
        }
    }

    /**
     * The column that identifies the scope on this particular stream.
     *
     * <p>Order matters. A column proven by a catalog binding wins, then
     * {@code service_name}, then the k8s labels the scraper writes instead. The
     * first one the stream ACTUALLY HAS is chosen — asking for a column a stream
     * does not carry turns the probe into a DataFusion error, which then has to
     * be read as UNKNOWN and widens the list for no reason.</p>
     *
     * @return null when the stream carries no identity column at all
     */
    private String identityColumn(String type, String stream, Scope scope) {
        Set<String> fields = safeFields(type, stream);
        if (fields.isEmpty()) {
            // Unknown schema, NOT an empty one — the distinction the stream
            // catalog is explicit about. service_name is the overwhelming
            // majority case, so it is worth probing: a stream without it answers
            // UNKNOWN and the list widens rather than quietly dropping it.
            return SERVICE_COLUMN;
        }
        List<String> preferred = new ArrayList<>();
        for (TelemetryBinding binding : scope.bindings()) {
            if (type.equalsIgnoreCase(binding.signal()) && binding.column() != null) {
                preferred.add(binding.column());
            }
        }
        preferred.add(SERVICE_COLUMN);
        preferred.addAll(FALLBACK_IDENTITY_COLUMNS);

        for (String column : preferred) {
            // The stream catalog lower-cases its field names, so the comparison
            // must too: a projection decision must not depend on how OpenObserve
            // happened to spell a column.
            if (fields.contains(column.toLowerCase(Locale.ROOT))) {
                return column;
            }
        }
        return null;
    }

    private Set<String> safeFields(String type, String stream) {
        try {
            Set<String> fields = streamCatalog.fields(type, stream);
            return fields == null ? Set.of() : fields;
        } catch (Exception e) {
            log.debug("Field lookup failed for {}/{}: {}", type, stream, e.getMessage());
            return Set.of();
        }
    }

    private String lookbackDescription() {
        if (lookbackMinutes % 1440 == 0) {
            int days = lookbackMinutes / 1440;
            return days + (days == 1 ? " day" : " days");
        }
        if (lookbackMinutes % 60 == 0) {
            int hours = lookbackMinutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return lookbackMinutes + " minutes";
    }

    private static String quote(String identifier) {
        return '"' + SqlSafety.validateIdentifier(identifier) + '"';
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    // ── fields ──────────────────────────────────────────────────────────────

    @Override
    public AlertScopeResponseDTO.FieldList listFields(String scopeType, String scopeKey,
            String streamType, String streamName) {

        String org = config.getOrg();
        String type = normaliseStreamType(streamType);

        AlertScopeResponseDTO.FieldList out = new AlertScopeResponseDTO.FieldList();
        out.setOrg(org);
        out.setStreamType(type);
        out.setStreamName(streamName);

        List<AlertsResponseDTO.StreamField> fields;
        try {
            fields = alerts.listStreamSchema(type, streamName);
        } catch (Exception e) {
            // Same contract the editor already relies on: a stream with no schema
            // is normal, not an error, and the UI falls back to a free-text column.
            log.warn("Scoped field lookup failed for {}/{}: {}", type, streamName, e.getMessage());
            fields = List.of();
        }
        out.setItems(new ArrayList<>(fields));
        out.setTotal(out.getItems().size());

        Scope scope = resolve(org, scopeType, scopeKey);
        // The stream IS known here, so the predicate is resolved against its own
        // schema rather than against the scope's best guess. This is the call the
        // builder makes once a stream has been chosen, and it is the one that has
        // to be right.
        out.setFilter(STREAM_TYPE_METRICS.equals(type) ? null : filterFor(scope, type, streamName));

        if (out.getItems().isEmpty()) {
            out.setNotice("No schema is published for '" + streamName + "' yet, which is normal "
                    + "for a stream nothing has written to. Type the column name directly.");
        }
        return out;
    }

    // ── scope resolution ────────────────────────────────────────────────────

    /**
     * A scope, reduced to the only two things a query cares about: which
     * telemetry identities it covers, and which proven (signal, stream, column)
     * links it has.
     *
     * @param unscopedReason why {@link #names()} is empty, for the UI notice;
     *                       null when the scope resolved
     */
    private record Scope(String type, String key, String label, List<String> names,
                         List<TelemetryBinding> bindings, String unscopedReason) {

        static Scope none(String reason) {
            return new Scope(null, null, "all services", List.of(), List.of(), reason);
        }
    }

    private Scope resolve(String org, String scopeType, String scopeKey) {
        String type = blankToNull(scopeType);
        String key = blankToNull(scopeKey);

        if (type == null || key == null || "none".equalsIgnoreCase(type)) {
            return Scope.none("No service or product selected, so nothing is narrowed.");
        }

        return switch (type.toLowerCase(Locale.ROOT)) {
            case SCOPE_SERVICE -> new Scope(SCOPE_SERVICE, key, key, List.of(key), List.of(), null);
            case SCOPE_RESOURCE -> resourceScope(org, key);
            case SCOPE_PRODUCT -> productScope(org, key);
            default -> throw new IllegalArgumentException("Unknown scopeType '" + scopeType
                    + "' (expected service, product or resource)");
        };
    }

    /** The scope of one catalog resource, by id. */
    private Scope resourceScope(String org, String key) {
        CatalogResource resource = safeResources(org).stream()
                .filter(r -> key.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resource == null) {
            return Scope.none("No catalog resource with id '" + key + "'. Nothing narrowed.");
        }
        return new Scope(SCOPE_RESOURCE, key, resource.getResourceName(),
                List.of(resource.effectiveName()), resource.getBindings(), null);
    }

    /** The scope of a product: every enabled resource registered under its code. */
    private Scope productScope(String org, String key) {
        List<CatalogResource> members = safeResources(org).stream()
                .filter(CatalogResource::isEnabled)
                .filter(r -> key.equals(r.getProductCode()))
                .toList();
        if (members.isEmpty()) {
            return Scope.none("Product '" + key + "' has no enabled resources registered, "
                    + "so there is nothing to narrow to. Register its services in the "
                    + "Product Catalog first.");
        }
        List<String> names = new ArrayList<>(new LinkedHashSet<>(
                members.stream().map(CatalogResource::effectiveName).toList()));
        List<TelemetryBinding> bindings = new ArrayList<>();
        members.forEach(m -> bindings.addAll(m.getBindings()));
        String label = members.get(0).getProductLabel() == null
                ? key : members.get(0).getProductLabel();
        return new Scope(SCOPE_PRODUCT, key, label, names, bindings, null);
    }

    /**
     * The predicate that confines a query to the scope.
     *
     * <p>When a stream is named, the column is resolved against THAT STREAM's
     * actual schema, which is the only way to get it right: the SDK writes
     * {@code service_name} and the Kubernetes scraper writes
     * {@code k8s_container_name}, and a tenant has both. Assuming
     * {@code service_name} on a stream that does not carry it turns a working
     * query into a DataFusion {@code unknown field} error, which the operator
     * sees as the preview being broken rather than as a wrong column.</p>
     *
     * <p>With no stream — the list-level default — a proven catalog binding wins
     * and {@code service_name} is the fallback.</p>
     *
     * @param streamName the stream the predicate is for, or null for the default
     */
    private AlertScopeResponseDTO.ScopeFilter filterFor(Scope scope, String signal,
            String streamName) {

        if (scope.names().isEmpty()) {
            return null;
        }

        String column = filterColumn(scope, signal, streamName);

        AlertScopeResponseDTO.ScopeFilter filter = new AlertScopeResponseDTO.ScopeFilter();
        filter.setColumn(column);
        filter.setValues(new ArrayList<>(scope.names()));

        ArrayNode conditions = json.createArrayNode();
        List<String> quoted = new ArrayList<>();
        for (String value : scope.names()) {
            ObjectNode condition = json.createObjectNode();
            condition.put("column", column);
            condition.put("operator", "=");
            condition.put("value", value);
            // Case-sensitive on purpose. IoTOpsApiSvc and iotopsapisvc are two
            // different services on this deployment — the SDK-reported production
            // one and the k8s log scraper in dev — so an ignore_case match would
            // alert on both when the operator picked one.
            condition.put("ignore_case", false);
            conditions.add(condition);
            quoted.add("'" + SqlSafety.escapeStringLiteral(value) + "'");
        }

        if (scope.names().size() == 1) {
            filter.setOperator("=");
            filter.setValue(scope.names().get(0));
            filter.setCondition(conditions.get(0));
            filter.setSql("\"" + column + "\" = " + quoted.get(0));
        } else {
            // A product covers several services, so the predicate is a disjunction.
            // Wrapped in OpenObserve's own group shape rather than handed over as a
            // bare list, because a list spliced into an AND group would mean "this
            // row is every one of these services at once" — which matches nothing.
            filter.setOperator("or");
            ObjectNode group = json.createObjectNode();
            group.set("or", conditions);
            filter.setCondition(group);
            filter.setSql("\"" + column + "\" IN (" + String.join(", ", quoted) + ")");
        }
        return filter;
    }

    /**
     * The column the scope predicate is written against: the named stream's own
     * identity column when one resolves, otherwise the scope's proven binding for
     * the signal, otherwise {@link #SERVICE_COLUMN}.
     */
    private String filterColumn(Scope scope, String signal, String streamName) {
        String column = null;
        if (streamName != null && !streamName.isBlank()) {
            column = identityColumn(signal, streamName, scope);
        }
        return column == null ? boundColumn(scope, signal) : column;
    }

    /** The first proven catalog binding for the signal, or {@link #SERVICE_COLUMN}. */
    private static String boundColumn(Scope scope, String signal) {
        for (TelemetryBinding binding : scope.bindings()) {
            if (signal.equalsIgnoreCase(binding.signal()) && binding.column() != null) {
                return binding.column();
            }
        }
        return SERVICE_COLUMN;
    }

    // ── building scope options ──────────────────────────────────────────────

    private AlertScopeResponseDTO.ScopeOption resourceOption(CatalogResource resource,
            Map<String, MetricProducersDTO.Service> byName) {

        AlertScopeResponseDTO.ScopeOption option = new AlertScopeResponseDTO.ScopeOption();
        option.setType(SCOPE_RESOURCE);
        option.setKey(resource.getId());
        option.setLabel(resource.getResourceName());
        option.setCategory(resource.getCategory() == null
                ? null : resource.getCategory().getDisplayName());
        option.setProductCode(resource.getProductCode());
        option.setSignals(new ArrayList<>(resource.getSignals()));
        option.setFilterColumn(SERVICE_COLUMN);
        option.setFilterValues(List.of(resource.effectiveName()));
        option.setMetricCount(metricCount(byName, List.of(resource.effectiveName())));
        return option;
    }

    private AlertScopeResponseDTO.ScopeOption productOption(String code,
            List<CatalogResource> members, Map<String, MetricProducersDTO.Service> byName) {

        AlertScopeResponseDTO.ScopeOption option = new AlertScopeResponseDTO.ScopeOption();
        option.setType(SCOPE_PRODUCT);
        option.setKey(code);
        option.setLabel(members.get(0).getProductLabel() == null
                ? code : members.get(0).getProductLabel());
        option.setCategory(members.get(0).getCategory() == null
                ? null : members.get(0).getCategory().getDisplayName());
        option.setProductCode(code);

        Set<String> signals = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (CatalogResource member : members) {
            signals.addAll(member.getSignals());
            if (!names.contains(member.effectiveName())) {
                names.add(member.effectiveName());
            }
        }
        option.setSignals(new ArrayList<>(signals));
        option.setFilterColumn(SERVICE_COLUMN);
        option.setFilterValues(names);
        option.setMetricCount(metricCount(byName, names));
        return option;
    }

    private AlertScopeResponseDTO.ScopeOption serviceOption(MetricProducersDTO.Service service) {
        AlertScopeResponseDTO.ScopeOption option = new AlertScopeResponseDTO.ScopeOption();
        option.setType(SCOPE_SERVICE);
        option.setKey(service.getName());
        option.setLabel(service.getName());
        option.setCategory(service.getKindLabel() == null ? service.getKind() : service.getKindLabel());
        option.setSignals(List.of(STREAM_TYPE_METRICS));
        option.setFilterColumn(SERVICE_COLUMN);
        option.setFilterValues(List.of(service.getName()));
        option.setMetricCount(bucketCount(service.getEmitted()) + bucketCount(service.getObserved()));
        return option;
    }

    /** {@code -1} when the index has nothing for these names — see the DTO note. */
    private int metricCount(Map<String, MetricProducersDTO.Service> byName, List<String> names) {
        int total = 0;
        boolean known = false;
        for (String name : names) {
            MetricProducersDTO.Service service = byName.get(name);
            if (service == null) {
                continue;
            }
            known = true;
            total += bucketCount(service.getEmitted()) + bucketCount(service.getObserved());
        }
        return known ? total : -1;
    }

    private static int bucketCount(MetricProducersDTO.Bucket bucket) {
        return bucket == null ? 0 : bucket.getCount();
    }

    private static void collect(Map<String, AlertScopeResponseDTO.Target> into,
            MetricProducersDTO.Bucket bucket, String kind) {
        if (bucket == null || bucket.getMetrics() == null) {
            return;
        }
        for (MetricProducersDTO.Metric metric : bucket.getMetrics()) {
            if (metric == null || metric.getName() == null) {
                continue;
            }
            // putIfAbsent, so a metric that is both emitted here and observed
            // elsewhere keeps the emitted label — the stronger claim of the two.
            into.putIfAbsent(metric.getName(), new AlertScopeResponseDTO.Target(
                    metric.getName(), kind, metric.getTechnology(), metric.getType()));
        }
    }

    // ── wide fallbacks ──────────────────────────────────────────────────────

    /**
     * Every metric in the instance, with the reason it was not narrowed.
     *
     * <p>Built from the producer index's own service list rather than from a
     * stream listing, so the two paths cannot disagree about what a metric is
     * called. Falls back to the stream listing only when the index has nothing
     * at all.</p>
     */
    private void wideMetricList(AlertScopeResponseDTO.TargetList out, String reason) {
        Map<String, AlertScopeResponseDTO.Target> all = new LinkedHashMap<>();
        MetricProducersDTO producers = safeProducers();
        if (producers.getServices() != null) {
            for (MetricProducersDTO.Service service : producers.getServices()) {
                collect(all, service.getEmitted(), KIND_EMITTED);
                collect(all, service.getObserved(), KIND_OBSERVED);
            }
        }
        if (all.isEmpty()) {
            for (String name : safeStreams(STREAM_TYPE_METRICS)) {
                all.put(name, new AlertScopeResponseDTO.Target(name, KIND_STREAM, null, null));
            }
        }

        List<AlertScopeResponseDTO.Target> items = new ArrayList<>(all.values());
        items.sort(Comparator.comparing(AlertScopeResponseDTO.Target::getName,
                String.CASE_INSENSITIVE_ORDER));
        out.setItems(items);
        out.setScoped(false);
        out.setFilter(null);
        out.setNotice(reason);
    }

    // ── guarded reads ───────────────────────────────────────────────────────

    /**
     * The producer index, never throwing.
     *
     * <p>This endpoint feeds a dropdown. An exception from an index that is
     * allowed to be absent would blank the editor over something the operator
     * can neither see nor fix, so a failure becomes an unsupported index and the
     * wide list with a caveat.</p>
     */
    private MetricProducersDTO safeProducers() {
        try {
            MetricProducersDTO result = metricProducers.getProducers();
            return result == null ? unsupportedProducers("no answer") : result;
        } catch (Exception e) {
            log.error("Producer index unavailable for alert scoping: {}", e.getMessage());
            return unsupportedProducers(e.getMessage());
        }
    }

    private static MetricProducersDTO unsupportedProducers(String error) {
        MetricProducersDTO empty = new MetricProducersDTO();
        empty.setSupported(false);
        empty.setError(error);
        empty.setServices(List.of());
        return empty;
    }

    private static Map<String, MetricProducersDTO.Service> indexByName(MetricProducersDTO dto) {
        Map<String, MetricProducersDTO.Service> byName = new LinkedHashMap<>();
        if (dto.getServices() != null) {
            for (MetricProducersDTO.Service service : dto.getServices()) {
                if (service.getName() != null) {
                    byName.putIfAbsent(service.getName(), service);
                }
            }
        }
        return byName;
    }

    private List<CatalogResource> safeResources(String org) {
        try {
            List<CatalogResource> all = catalog.findAll(org);
            return all == null ? List.of() : all;
        } catch (Exception e) {
            log.error("Product Catalog unavailable for alert scoping: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> safeStreams(String streamType) {
        try {
            List<String> names = alerts.listStreams(streamType);
            return names == null ? List.of() : names;
        } catch (Exception e) {
            log.error("Stream listing unavailable for alert scoping ({}): {}",
                    streamType, e.getMessage());
            return List.of();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String scopeNotice(MetricProducersDTO producers, int count) {
        if (count == 0) {
            return "Nothing is registered in the Product Catalog and no services have been seen "
                    + "in metrics yet, so there is nothing to scope to. Alerts can still be "
                    + "written against a stream directly.";
        }
        if (producers.isBuilding() || producers.isPartial()) {
            return "The metric index is still building (" + producers.getProgressDone() + "/"
                    + producers.getProgressTotal() + "), so metric counts are lower bounds and "
                    + "more services may appear.";
        }
        if (!producers.isSupported()) {
            return "Metric counts are unavailable because the producer index could not be read"
                    + (producers.getError() == null ? "" : " (" + producers.getError() + ")")
                    + ". Scoping to logs and traces is unaffected.";
        }
        return null;
    }

    /** Catalog entries first, then products, then bare telemetry services. */
    private static int typeRank(String type) {
        if (SCOPE_RESOURCE.equals(type)) return 0;
        if (SCOPE_PRODUCT.equals(type)) return 1;
        return 2;
    }

    private static String normaliseStreamType(String raw) {
        if (raw == null) return "logs";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case STREAM_TYPE_METRICS, "traces", "logs" -> value;
            // Unknown types fall back to logs rather than erroring, matching what
            // the existing stream endpoints already do for the same input.
            default -> "logs";
        };
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
