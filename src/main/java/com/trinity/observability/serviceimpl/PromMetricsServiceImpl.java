package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricCatalogDTO;
import com.trinity.observability.dto.metrics.MetricLabelsDTO;
import com.trinity.observability.dto.metrics.PromResultDTO;
import com.trinity.observability.service.PromMetricsService;
import com.trinity.observability.util.MetricClassifier;
import com.trinity.observability.util.RefreshingCache;
import com.trinity.observability.util.RequestScopedFanout;
import com.trinity.observability.util.TimeRangeParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Thin translation layer over OpenObserve's Prometheus API.
 *
 * Deliberately thin: PromQL is evaluated by the engine, so there is no query
 * building to do here and no SQL-injection surface — the expression is opaque
 * and goes through URL-encoded. What this class DOES own is the two things the
 * frontend must not have to know:
 *
 *   1. Time. Every other endpoint in this service takes "now-1h"; Prometheus
 *      takes epoch SECONDS. TimeRangeParser is the single source of truth for
 *      relative expressions, so the conversion happens here rather than being
 *      reimplemented in JavaScript against a different clock.
 *   2. Step. An unbounded step is how a range query becomes an outage: a 7-day
 *      window at 15s is 40,320 points per series times however many series
 *      match. chooseStep() caps it at MAX_POINTS.
 */
@Service
public class PromMetricsServiceImpl implements PromMetricsService {

    private static final Logger log = LogManager.getLogger(PromMetricsServiceImpl.class);

    /** Per-series point cap. 1,500 is already more than any chart can resolve. */
    private static final int MAX_POINTS = 1_500;
    /** Prometheus rejects step=0; also the floor for a sane chart. */
    private static final long MIN_STEP_SECONDS = 1L;
    private static final long HOUR_MICROS = 3_600_000_000L;
    /** Catalogue type of a metric that declared no /metadata entry. */
    private static final String UNKNOWN_TYPE = "unknown";

    private final OpenObserveClientService client;
    private final OpenObserveConfig config;
    private final MetricClassifier classifier;
    private final RequestScopedFanout fanout;

    /**
     * The catalogue, per org. Building it reads ~20,000 names plus their metadata
     * (4-9 s measured on this deployment) and the set only moves when something is
     * deployed, so it is served from memory and rebuilt behind the request once it
     * is older than {@code metrics.catalog.cache-seconds}.
     */
    private final RefreshingCache<MetricCatalogDTO> catalogCache;

    /**
     * Label names and label values, per org + metric (+ label). Both are read on
     * every Metrics screen open and every metric switch; /series in particular
     * downloads the full label set of every series for the hour (1-2 s measured)
     * only to keep one column. The sets move when something is deployed, not
     * between two clicks, so they are served from memory for
     * {@code metrics.labels.cache-seconds} and refreshed behind the request.
     * Only the item list is cached — each response gets its own DTO — and a
     * failed read throws past the cache, so an error is never served from it.
     */
    private final RefreshingCache<List<String>> labelNamesCache;
    private final RefreshingCache<List<String>> labelValuesCache;

    public PromMetricsServiceImpl(OpenObserveClientService client, OpenObserveConfig config,
                                  MetricClassifier classifier, RequestScopedFanout fanout,
                                  @Value("${metrics.catalog.cache-seconds:300}") int catalogCacheSeconds,
                                  @Value("${metrics.labels.cache-seconds:60}") int labelsCacheSeconds) {
        this.client = client;
        this.config = config;
        this.classifier = classifier;
        this.fanout = fanout;
        this.catalogCache = new RefreshingCache<>("metric catalog",
                Duration.ofSeconds(Math.max(1, catalogCacheSeconds)), Duration.ofSeconds(30));
        Duration labelsTtl = Duration.ofSeconds(Math.max(1, labelsCacheSeconds));
        this.labelNamesCache = new RefreshingCache<>("metric label names", labelsTtl, Duration.ofSeconds(30));
        this.labelValuesCache = new RefreshingCache<>("metric label values", labelsTtl, Duration.ofSeconds(30));
    }

    private String base() {
        return "/api/" + config.getOrg() + "/prometheus/api/v1";
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    @Override
    public MetricCatalogDTO getCatalog() {
        try {
            return catalogCache.get(RefreshingCache.orgKey(config.getOrg()), this::loadCatalog);
        } catch (Exception e) {
            // Not cached: the next request tries again rather than pinning the error.
            log.warn("Metric catalog unavailable: {}", e.toString());
            MetricCatalogDTO out = new MetricCatalogDTO();
            out.setItems(List.of());
            out.setTotal(0);
            out.setGroups(List.of());
            out.setSupported(false);
            out.setError(e.getMessage());
            return out;
        }
    }

    /** Builds the catalogue from OpenObserve. Throws when it cannot be read. */
    private MetricCatalogDTO loadCatalog() {
        MetricCatalogDTO out = new MetricCatalogDTO();
        // Two calls, because neither endpoint is sufficient alone: /metadata
        // omits metrics that never declared a HELP line, and __name__ values
        // carries no type. The union is the honest catalogue. They do not feed
        // each other, so they run at the same time.
        String base = base();
        JsonNode[] fetched = new JsonNode[2];
        RuntimeException[] failure = new RuntimeException[1];
        fanout.runAll(List.of(
                fetchInto(fetched, 0, base + "/label/__name__/values", failure),
                fetchInto(fetched, 1, base + "/metadata", failure)));
        if (failure[0] != null) {
            throw failure[0];
        }
        JsonNode names = fetched[0];
        JsonNode meta = fetched[1];
        if (names == null || meta == null) {
            throw new IllegalStateException("metric catalog fetch did not complete");
        }

        Map<String, MetricCatalogDTO.Item> byName = new LinkedHashMap<>();
        addNames(byName, names);
        mergeMetadata(byName, meta);

        List<MetricCatalogDTO.Item> items = new ArrayList<>(byName.values());
        items.sort(Comparator.comparing(MetricCatalogDTO.Item::getName, String.CASE_INSENSITIVE_ORDER));

        // Sort every name into a group and a technology BEFORE the response
        // leaves here. Three thousand names in one flat list is the reason
        // the explorer was unreadable; see MetricClassifier.
        classifyItems(items);

        out.setItems(items);
        out.setTotal(items.size());
        out.setGroups(facets(items));
        log.info("Metric catalog: {} names ({} with metadata) in {} group(s)", items.size(),
                items.stream().filter(i -> !UNKNOWN_TYPE.equals(i.getType())).count(),
                out.getGroups().size());
        if (log.isDebugEnabled()) {
            out.getGroups().forEach(g ->
                    log.debug("  catalog group {}: {} metric(s), {} technolog(ies)",
                            g.getKey(), g.getCount(), g.getTechnologies().size()));
        }
        return out;
    }

    /** A fan-out task that stores {@code path}'s "data" node in {@code into[idx]},
     *  or records the failure. */
    private Runnable fetchInto(JsonNode[] into, int idx, String path, RuntimeException[] failure) {
        return () -> {
            try {
                into[idx] = client.getJson(path).path("data");
            } catch (RuntimeException e) {
                failure[0] = e;
            }
        };
    }

    /** Seeds the catalogue with every non-blank name from the __name__ values. */
    private static void addNames(Map<String, MetricCatalogDTO.Item> byName, JsonNode names) {
        if (!names.isArray()) return;
        for (JsonNode n : names) {
            String name = n.asText(null);
            if (name != null && !name.isBlank()) {
                byName.put(name, new MetricCatalogDTO.Item(name, UNKNOWN_TYPE, null, null));
            }
        }
    }

    /** Folds /metadata type, help and unit into the catalogue. */
    private static void mergeMetadata(Map<String, MetricCatalogDTO.Item> byName, JsonNode meta) {
        if (!meta.isObject()) return;
        Iterator<String> it = meta.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            mergeMetadataEntry(byName, name, meta.get(name));
        }
    }

    private static void mergeMetadataEntry(Map<String, MetricCatalogDTO.Item> byName, String name, JsonNode arr) {
        JsonNode first = arr != null && arr.isArray() && arr.size() > 0 ? arr.get(0) : arr;
        if (first == null || !first.isObject()) return;
        String type = first.path("type").asText(UNKNOWN_TYPE);
        String help = first.path("help").asText(null);
        String unit = first.path("unit").asText(null);
        // Metrics present in metadata but absent from __name__ are
        // still real; add rather than skip.
        MetricCatalogDTO.Item item = byName.get(name);
        if (item == null) {
            byName.put(name, new MetricCatalogDTO.Item(name, type, blankToNull(help), blankToNull(unit)));
        } else {
            item.setType(type);
            item.setHelp(blankToNull(help));
            item.setUnit(blankToNull(unit));
        }
    }

    private void classifyItems(List<MetricCatalogDTO.Item> items) {
        for (MetricCatalogDTO.Item item : items) {
            MetricClassifier.Classification c = classifier.classify(item.getName());
            item.setGroup(c.getGroup());
            item.setTechnology(c.getTechnology());
            item.setShared(c.isSharedAcrossProducers());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * Facet counts for the catalogue sidebar.
     *
     * <p>Groups come back in {@link MetricClassifier#groupOrder()} — a fixed
     * editorial order, ours first — and only those that actually contain
     * something are emitted: a deployment with no Kubernetes should not be
     * offered an empty "Kubernetes &amp; hosts" filter. Technologies inside a
     * group are sorted by count descending, so the big exporter that is drowning
     * out everything else is the first thing named.</p>
     */
    private static List<MetricCatalogDTO.Group> facets(List<MetricCatalogDTO.Item> items) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (MetricCatalogDTO.Item item : items) {
            counts.computeIfAbsent(item.getGroup(), k -> new LinkedHashMap<>())
                    .merge(item.getTechnology(), 1, Integer::sum);
        }

        List<MetricCatalogDTO.Group> out = new ArrayList<>();
        for (String key : MetricClassifier.groupOrder()) {
            Map<String, Integer> techs = counts.get(key);
            if (techs == null || techs.isEmpty()) continue;

            List<MetricCatalogDTO.Technology> techList = techs.entrySet().stream()
                    .map(e -> new MetricCatalogDTO.Technology(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingInt(MetricCatalogDTO.Technology::getCount).reversed()
                            .thenComparing(MetricCatalogDTO.Technology::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            int total = techs.values().stream().mapToInt(Integer::intValue).sum();
            out.add(new MetricCatalogDTO.Group(key, MetricClassifier.groupLabel(key), total, techList));
        }
        return out;
    }

    @Override
    public MetricLabelsDTO getLabels(String metric) {
        String path = base() + "/labels";
        if (metric != null && !metric.isBlank()) {
            path += "?match[]=" + enc(metric);
        }
        final String upstream = path;
        MetricLabelsDTO out = new MetricLabelsDTO();
        try {
            out.setItems(new ArrayList<>(labelNamesCache.get(
                    RefreshingCache.orgKey(config.getOrg(), metric == null ? "" : metric),
                    () -> readStringList(upstream))));
        } catch (Exception e) {
            log.warn("Label lookup failed for {}: {}", upstream, e.toString());
            out.setItems(List.of());
            out.setSupported(false);
            out.setError(e.getMessage());
        }
        return out;
    }

    /**
     * Label values, read out of /series rather than /label/{name}/values.
     *
     * That endpoint is unusable on this OpenObserve build, verified against the
     * live instance: bare it answers 200 with an EMPTY list, and with a match[]
     * selector it answers 500. The empty case is the dangerous one — it looks
     * like "this label has no values" rather than "this endpoint doesn't work",
     * so a dropdown built on it would silently render nothing.
     *
     * /series returns the full label set of every matching series and is
     * correct on the same instance, so the distinct values are projected out of
     * it here. It costs more on the wire than a dedicated endpoint would, which
     * is why it is scoped to one metric and one hour by default.
     */
    @Override
    public MetricLabelsDTO getLabelValues(String label, String metric) {
        MetricLabelsDTO out = new MetricLabelsDTO();
        if (label == null || label.isBlank()) {
            out.setItems(List.of());
            out.setSupported(false);
            out.setError("label is required");
            return out;
        }
        // Unscoped would mean every series in the instance; require a metric.
        if (metric == null || metric.isBlank()) {
            out.setItems(List.of());
            out.setSupported(false);
            out.setError("metric is required to list label values on this backend");
            return out;
        }

        try {
            out.setItems(new ArrayList<>(labelValuesCache.get(
                    RefreshingCache.orgKey(config.getOrg(), metric, label),
                    () -> loadLabelValues(label, metric))));
        } catch (Exception e) {
            log.warn("Label values failed for {} on {}: {}", label, metric, e.toString());
            out.setItems(List.of());
            out.setSupported(false);
            out.setError(e.getMessage());
        }
        return out;
    }

    /** The distinct values of {@code label} over the last hour of {@code metric}'s
     *  series. Throws when OpenObserve cannot be read, so the failure is not cached. */
    private List<String> loadLabelValues(String label, String metric) {
        long endSec = TimeRangeParser.defaultEndMicros() / 1_000_000L;
        long startSec = endSec - 3600;
        String path = base() + "/series?match[]=" + enc(metric)
                + "&start=" + startSec + "&end=" + endSec;
        JsonNode data = client.getJson(path).path("data");
        Set<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (data.isArray()) {
            for (JsonNode s : data) {
                String v = s.path(label).asText(null);
                if (v != null && !v.isBlank()) values.add(v);
            }
        }
        log.info("Label values for {}{{{}}} -> {}", metric, label, values.size());
        return List.copyOf(values);
    }

    /** {"data": ["a","b"]} as a sorted, immutable list. Throws on upstream failure. */
    private List<String> readStringList(String path) {
        JsonNode data = client.getJson(path).path("data");
        List<String> items = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) items.add(v);
            }
        }
        items.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(items);
    }

    @Override
    public PromResultDTO queryRange(String query, String startTime, String endTime, long step) {
        PromResultDTO out = new PromResultDTO();
        if (query == null || query.isBlank()) {
            out.setSupported(false);
            out.setError("query is required");
            out.setSeries(List.of());
            return out;
        }

        long endMicros = TimeRangeParser.toMicros(endTime, TimeRangeParser.defaultEndMicros());
        long startMicros = TimeRangeParser.toMicros(startTime, endMicros - HOUR_MICROS);
        if (startMicros >= endMicros) startMicros = endMicros - HOUR_MICROS;

        long startSec = startMicros / 1_000_000L;
        long endSec = endMicros / 1_000_000L;
        long chosen = chooseStep(step, endSec - startSec);

        String path = base() + "/query_range"
                + "?query=" + enc(query)
                + "&start=" + startSec
                + "&end=" + endSec
                + "&step=" + chosen;
        return read(path, out, query);
    }

    @Override
    public PromResultDTO query(String query, String time) {
        PromResultDTO out = new PromResultDTO();
        if (query == null || query.isBlank()) {
            out.setSupported(false);
            out.setError("query is required");
            out.setSeries(List.of());
            return out;
        }
        long atMicros = TimeRangeParser.toMicros(time, TimeRangeParser.defaultEndMicros());
        String path = base() + "/query?query=" + enc(query) + "&time=" + (atMicros / 1_000_000L);
        return read(path, out, query);
    }

    /**
     * Step that keeps a range query bounded.
     *
     * An explicit step is honoured but still capped — a user asking for 1s over
     * 30 days is asking for 2.6M points per series, which serialises to more
     * memory than the JVM has before the browser ever sees it.
     */
    private static long chooseStep(long requested, long spanSeconds) {
        long span = Math.max(1, spanSeconds);
        long floor = Math.max(MIN_STEP_SECONDS, (span + MAX_POINTS - 1) / MAX_POINTS);
        return requested > 0 ? Math.max(requested, floor) : floor;
    }

    /** Parse a Prometheus query response into the flat DTO. */
    private PromResultDTO read(String path, PromResultDTO out, String query) {
        try {
            JsonNode root = client.getJson(path);

            // Prometheus signals failure in the BODY with HTTP 200 in some
            // deployments, so status has to be checked explicitly — the client
            // only throws on a non-2xx code.
            String status = root.path("status").asText("success");
            if (!"success".equals(status)) {
                String err = root.path("error").asText("query failed");
                log.warn("PromQL rejected [{}]: {}", query, err);
                out.setSupported(false);
                out.setError(err);
                out.setSeries(List.of());
                return out;
            }

            JsonNode data = root.path("data");
            out.setResultType(data.path("resultType").asText(null));

            List<PromResultDTO.Series> series = new ArrayList<>();
            JsonNode result = data.path("result");
            if (result.isArray()) {
                for (JsonNode s : result) {
                    series.add(toSeries(s));
                }
            }
            out.setSeries(series);
            log.info("PromQL [{}] -> {} series", query, series.size());
        } catch (Exception e) {
            log.warn("PromQL failed [{}]: {}", query, e.toString());
            out.setSupported(false);
            out.setError(e.getMessage());
            out.setSeries(List.of());
        }
        return out;
    }

    /** One entry of a Prometheus "result" array as a flat series. */
    private static PromResultDTO.Series toSeries(JsonNode s) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode m = s.path("metric");
        if (m.isObject()) {
            Iterator<String> it = m.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                labels.put(k, m.path(k).asText(""));
            }
        }

        List<List<Object>> points = new ArrayList<>();
        // A range query returns "values" (many points); an instant
        // query returns "value" (one). Normalise to a list so the
        // frontend has a single shape to render.
        JsonNode values = s.has("values") ? s.path("values") : null;
        if (values != null && values.isArray()) {
            for (JsonNode p : values) addPoint(points, p);
        } else if (s.has("value")) {
            addPoint(points, s.path("value"));
        }
        return new PromResultDTO.Series(labels, points);
    }

    private static void addPoint(List<List<Object>> into, JsonNode pair) {
        if (pair == null || !pair.isArray() || pair.size() < 2) return;
        // Timestamp as a number, value left as the string Prometheus sent.
        into.add(List.of(pair.get(0).asDouble(), pair.get(1).asText("")));
    }
}
