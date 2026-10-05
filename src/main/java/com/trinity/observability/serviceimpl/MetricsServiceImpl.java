package com.trinity.observability.serviceimpl;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.metrics.MetricRequestDTO;
import com.trinity.observability.dto.metrics.MetricResponseDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryRequestDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryResponseDTO;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.service.MetricsService;
import com.trinity.observability.service.StreamCatalogService;
import com.trinity.observability.util.QueryBuilderUtil;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MetricsServiceImpl implements MetricsService {

    private static final Logger log = LogManager.getLogger(MetricsServiceImpl.class);

    /** OpenObserve stream type / Product Catalog signal the metric queries run against. */
    private static final String METRICS_STREAM_TYPE = "metrics";
    /** The field holding a metric sample's value, on both the OpenObserve and ES paths. */
    private static final String VALUE_FIELD = "value";

    private final ElasticsearchClientService elasticsearchClientService;
    private final OpenObserveClientService   openObserveClientService;

    /**
     * Product Catalog scoping. For metrics the filter is restricted to
     * {@code service_name} by {@code product-catalog.filter.columns.metrics},
     * because OpenObserve keeps one stream per metric name and that is the only
     * identifier column reliably present across all of them — a predicate naming
     * an absent column would turn a chart into a DataFusion error.
     */
    private final CatalogTelemetryFilter     catalogFilter;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /**
     * Shared, per-org, stale-while-revalidate cache of stream names. Optional so the
     * test constructors keep working; without it the list is fetched directly.
     */
    private StreamCatalogService streamCatalog;

    @Autowired(required = false)
    void setStreamCatalog(StreamCatalogService streamCatalog) {
        this.streamCatalog = streamCatalog;
    }

    // ES injection is nullable — see comment in LogsServiceImpl. Safe because
    // every elasticsearchClientService call sits inside `!openObserveEnabled`.
    // @Autowired is required to disambiguate from the test-only constructor.
    /**
     * @param openObserveConfig not read by this class; kept in the signature so the
     *                          Spring wiring and the existing callers stay unchanged
     */
    @Autowired
    public MetricsServiceImpl(@Nullable ElasticsearchClientService elasticsearchClientService,
                              OpenObserveClientService openObserveClientService,
                              OpenObserveConfig openObserveConfig,
                              CatalogTelemetryFilter catalogFilter) {
        this.elasticsearchClientService = elasticsearchClientService;
        this.openObserveClientService   = openObserveClientService;
        this.catalogFilter              = catalogFilter;
    }

    /** Test-only convenience constructor — no catalog scoping. */
    public MetricsServiceImpl(@Nullable ElasticsearchClientService elasticsearchClientService,
                              OpenObserveClientService openObserveClientService,
                              OpenObserveConfig openObserveConfig) {
        this(elasticsearchClientService, openObserveClientService, openObserveConfig,
                CatalogTelemetryFilter.permitAll());
    }

    /** The Product Catalog predicate for metrics, as an appendable clause. */
    private String catalogFilterClause() {
        String predicate = catalogFilter.sqlPredicate(METRICS_STREAM_TYPE);
        return predicate.isEmpty() ? "" : "AND " + predicate + " ";
    }

    @Override
    public List<MetricResponseDTO> getMetrics(MetricRequestDTO request) {
        long t0 = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("getMetrics() invoked: {}", request);
        }
        List<MetricResponseDTO> result = openObserveEnabled
                ? getMetricsViaOpenObserve(request)
                : getMetricsViaElasticsearch(request);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getMetrics() returning {} point(s) in {} ms (path={})",
                result.size(), elapsedMs, openObserveEnabled ? "openobserve" : "elasticsearch");
        return result;
    }

    @Override
    public MetricsSummaryResponseDTO getMetricsSummary(MetricsSummaryRequestDTO request) {
        long t0 = System.nanoTime();

        String windowExpr = request.getWindow() != null
                ? request.getWindow()
                : com.trinity.observability.constants.AppConstants.DEFAULT_SUMMARY_WINDOW;
        int bucketSec = request.getBucketSec() != null && request.getBucketSec() > 0
                ? request.getBucketSec()
                : 60;
        long endMicros   = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(windowExpr, endMicros);

        MetricsSummaryResponseDTO out = new MetricsSummaryResponseDTO();
        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));
        out.setBucketSec(bucketSec);
        out.setBackend(openObserveEnabled ? "openobserve" : "elasticsearch");

        if (!openObserveEnabled) {
            out.setSupported(false);
            out.setSparklines(Collections.emptyMap());
            out.setLatestValues(Collections.emptyMap());
            log.info("getMetricsSummary() returning supported=false on ES path");
            return out;
        }

        // Per-service is the only meaningful aggregation here — averaging JVM
        // heap / CPU / threads across multiple processes produces a number
        // that doesn't correspond to any real workload. When serviceName is
        // missing we bail with an empty payload so the UI's service picker is
        // the single source of truth for which process the user is viewing.
        String serviceName = request.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            out.setSupported(true);
            out.setSparklines(Collections.emptyMap());
            out.setLatestValues(Collections.emptyMap());
            log.info("getMetricsSummary() called without serviceName — returning empty payload");
            return out;
        }
        String escapedSvc = SqlSafety.escapeStringLiteral(serviceName);
        String serviceFilter = "service_name = '" + escapedSvc + "'";

        Map<String, List<MetricsSummaryResponseDTO.Point>> sparklines = new LinkedHashMap<>();
        Map<String, Double> latest = new LinkedHashMap<>();

        // Four streams, each a separate query. Could be parallelised — for now
        // they're sequential because (a) two-digit-ms each, (b) keeping the
        // implementation single-threaded sidesteps OpenObserve's per-org
        // connection limit. Revisit if Summary load time becomes a concern.
        SparklineWindow window = new SparklineWindow(bucketSec, startMicros, endMicros);
        fetchSparkline(sparklines, latest, "jvmHeap",
            com.trinity.observability.constants.AppConstants.SPARK_JVM_HEAP_STREAM,
            serviceFilter + " AND jvm_memory_type = 'heap'", window);
        fetchSparkline(sparklines, latest, "cpuUsage",
            com.trinity.observability.constants.AppConstants.SPARK_CPU_USAGE_STREAM,
            serviceFilter, window);
        fetchSparkline(sparklines, latest, "threadsLive",
            com.trinity.observability.constants.AppConstants.SPARK_THREADS_LIVE_STREAM,
            serviceFilter, window);
        fetchSparkline(sparklines, latest, "httpReqsCount",
            com.trinity.observability.constants.AppConstants.SPARK_HTTP_REQS_COUNT_STREAM,
            serviceFilter, window);

        out.setSparklines(sparklines);
        out.setLatestValues(latest);
        out.setSupported(true);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getMetricsSummary() returning {} series in {} ms",
                sparklines.size(), elapsedMs);
        return out;
    }

    /** Bucket width and time bounds shared by every sparkline of one summary request. */
    private record SparklineWindow(int bucketSec, long startMicros, long endMicros) {
    }

    /**
     * Run one bucketed query and merge its results into the response maps. A
     * missing stream (OpenObserve raises a parse error if it doesn't exist)
     * is treated as "no data" — the key is simply not added.
     */
    private void fetchSparkline(Map<String, List<MetricsSummaryResponseDTO.Point>> sparklines,
                                Map<String, Double> latest,
                                String seriesKey, String stream, String extraFilter,
                                SparklineWindow window) {
        long startMicros = window.startMicros();
        long endMicros = window.endMicros();
        String safeStream = SqlSafety.validateIdentifier(stream);
        // Product Catalog scoping rides along with the caller's filter, so it lands
        // in the INNER WHERE of the sum-by-timestamp shape below as well as the
        // plain one — the inner query is where the rows are selected.
        String filterClause = ((extraFilter == null || extraFilter.isEmpty())
                ? ""
                : "AND " + extraFilter + " ")
                + catalogFilterClause();

        String sql = sparklineSql(stream, safeStream, filterClause, window);

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve metrics-summary SQL ({}): {}", seriesKey, sql);
        }

        List<Map<String, Object>> hits;
        try {
            hits = openObserveClientService.search(sql, startMicros, endMicros, 0, 1000, METRICS_STREAM_TYPE);
        } catch (Exception e) {
            log.debug("Sparkline {} skipped (stream {} unavailable): {}", seriesKey, stream, e.getMessage());
            return;
        }

        mergeSparkline(sparklines, latest, seriesKey, hits);
    }

    /**
     * The bucketed-average statement for one sparkline. Gauges reported once per
     * pool/area/state are summed per timestamp first, so the line is the total.
     */
    private static String sparklineSql(String stream, String safeStream, String filterClause,
                                       SparklineWindow window) {
        int bucketSec = window.bucketSec();
        long startMicros = window.startMicros();
        long endMicros = window.endMicros();
        boolean sumByTimestamp = "jvm_memory_used".equals(stream)
                || "jvm_thread_count".equals(stream)
                || "http_server_request_duration_count".equals(stream);

        String sql;
        if (sumByTimestamp) {
            sql = "SELECT "
                +    "DATE_BIN(INTERVAL '" + bucketSec + " seconds', "
                +              "to_timestamp_micros(_timestamp), "
                +              "TIMESTAMP '1970-01-01') AS bucket, "
                +    "AVG(ts_sum) AS v "
                + "FROM ("
                +    "SELECT _timestamp, SUM(value) AS ts_sum "
                +    "FROM \"" + safeStream + "\" "
                +    "WHERE _timestamp >= " + startMicros + " "
                +      "AND _timestamp <= " + endMicros + " "
                +      filterClause
                +    "GROUP BY _timestamp"
                + ") "
                + "GROUP BY bucket "
                + "ORDER BY bucket";
        } else {
            sql = "SELECT "
                +    "DATE_BIN(INTERVAL '" + bucketSec + " seconds', "
                +              "to_timestamp_micros(_timestamp), "
                +              "TIMESTAMP '1970-01-01') AS bucket, "
                +    "AVG(value) AS v "
                + "FROM \"" + safeStream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                +   "AND _timestamp <= " + endMicros + " "
                +   filterClause
                + "GROUP BY bucket "
                + "ORDER BY bucket";
        }
        return sql;
    }

    /** Add one sparkline's points (and its last value) to the response maps; nothing when it has none. */
    private static void mergeSparkline(Map<String, List<MetricsSummaryResponseDTO.Point>> sparklines,
                                       Map<String, Double> latest, String seriesKey,
                                       List<Map<String, Object>> hits) {
        List<MetricsSummaryResponseDTO.Point> points = new ArrayList<>(hits.size());
        Double last = null;
        for (Map<String, Object> hit : hits) {
            String ts = String.valueOf(hit.get("bucket"));
            Number v  = (Number) hit.get("v");
            if (ts == null || v == null) continue;
            double value = v.doubleValue();
            points.add(new MetricsSummaryResponseDTO.Point(ts, value));
            last = value;
        }
        if (!points.isEmpty()) {
            sparklines.put(seriesKey, points);
            if (last != null) latest.put(seriesKey, last);
        }
    }

    @Override
    public List<String> listMetricStreams() {
        if (!openObserveEnabled) {
            log.trace("listMetricStreams: openobserve.enabled=false → returning empty");
            return Collections.emptyList();
        }
        long t0 = System.nanoTime();
        try {
            // Through the stream catalog when there is one. /streams?type=metrics is
            // ~19,500 names and a multi-megabyte response on this deployment, and it
            // used to be fetched afresh on every open of the Metrics dropdown — 11-19 s
            // measured. The catalog serves it from memory and refreshes it behind the
            // request; it is the same list the alert editor's picker already uses.
            List<String> streams = streamCatalog != null
                    ? streamCatalog.list(METRICS_STREAM_TYPE)
                    : openObserveClientService.listMetricStreams();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            log.info("listMetricStreams() returning {} stream(s) in {} ms", streams.size(), elapsedMs);
            return streams;
        } catch (Exception e) {
            log.warn("listMetricStreams failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------------------
    // OpenObserve path (Phase G — D8(a) feature flag)
    // -----------------------------------------------------------------------------------

    /**
     * OpenObserve has ONE STREAM PER METRIC NAME (Step 1.6, MIGRATION-PLAN.md).
     * Per Decision D1(a), {@code metricName} is required on this path:
     * if absent, return empty rather than fanning out across 100+ streams.
     * The UI populates its metric-name dropdown via {@link #listMetricStreams()}.
     */
    private List<MetricResponseDTO> getMetricsViaOpenObserve(MetricRequestDTO request) {
        log.info("Fetching metrics via OpenObserve: metricName={}, serviceName={}, hostName={}, page={}, size={}",
                request.getMetricName(), request.getServiceName(), request.getHostName(),
                request.getPage(), request.getSize());

        String metricName = request.getMetricName();
        if (metricName == null || metricName.trim().isEmpty()) {
            log.info("metricName not provided — returning empty (Decision D1(a) requires it on the OO path).");
            return Collections.emptyList();
        }

        String safeStream = SqlSafety.validateIdentifier(metricName.trim());
        long startMicros = TimeRangeParser.toMicros(request.getStartTime(), TimeRangeParser.defaultStartMicros());
        long endMicros   = TimeRangeParser.toMicros(request.getEndTime(),   TimeRangeParser.defaultEndMicros());
        log.trace("Resolved time range: startMicros={}, endMicros={}, stream={}",
                startMicros, endMicros, safeStream);

        StringBuilder sql = new StringBuilder()
            .append("SELECT _timestamp, value, service_name, host_name ")
            .append("FROM \"").append(safeStream).append("\" WHERE 1=1 ");

        appendOptionalEqFilter(sql, "service_name", request.getServiceName());
        appendOptionalEqFilter(sql, "host_name",    request.getHostName());

        sql.append("AND _timestamp >= ").append(startMicros).append(' ');
        sql.append("AND _timestamp <= ").append(endMicros).append(' ');
        // Product Catalog scoping, before ORDER BY / pagination.
        sql.append(catalogFilterClause());
        sql.append("ORDER BY _timestamp DESC");

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve metrics SQL (stream={}): {}", safeStream, sql);
        }

        int from = request.getPage() * request.getSize();
        List<Map<String, Object>> hits;
        try {
            hits = openObserveClientService.search(
                sql.toString(), startMicros, endMicros, from, request.getSize(), METRICS_STREAM_TYPE);
        } catch (Exception e) {
            log.error("OpenObserve metric search failed for stream '{}': {}", safeStream, e.getMessage(), e);
            return Collections.emptyList();
        }

        log.debug("OpenObserve metrics returned {} hit(s) for stream={}", hits.size(), safeStream);

        List<MetricResponseDTO> out = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            MetricResponseDTO dto = mapOpenObserveHitToDTO(hit, safeStream);
            if (dto != null) out.add(dto);
        }
        return out;
    }

    /**
     * Map a flat OpenObserve metric hit to a MetricResponseDTO.
     *
     * <p>Notes per MIGRATION-PLAN.md:</p>
     * <ul>
     *   <li>{@code name} comes from the FROM-clause stream name, not from any column —
     *       OO has no metric_name column (r6 Z-batch).</li>
     *   <li>{@code value} read with {@code (Number).doubleValue()} — setter is Double,
     *       a raw {@code (Number)} cast won't compile (V1 in r6 dissection).</li>
     *   <li>{@code _timestamp} read with defensive {@code (Number).longValue()} +
     *       null guard (r7).</li>
     *   <li>{@code threadState} / {@code threadType} stay null (Decision D6 (a) — no
     *       canonical OTel column for these on the JVM metric streams).</li>
     * </ul>
     */
    private MetricResponseDTO mapOpenObserveHitToDTO(Map<String, Object> hit, String streamName) {
        if (hit == null) return null;

        MetricResponseDTO dto = new MetricResponseDTO();
        dto.setName(streamName);

        Number rawValue = (Number) hit.get(VALUE_FIELD);
        dto.setValue(rawValue == null ? null : rawValue.doubleValue());

        Number tsRaw = (Number) hit.get("_timestamp");
        dto.setTimestamp(tsRaw == null ? null : TimeFormatter.microsToIso(tsRaw.longValue()));

        dto.setServiceName((String) hit.get("service_name"));
        dto.setHostName(   (String) hit.get("host_name"));
        return dto;
    }

    private static void appendOptionalEqFilter(StringBuilder sql, String column, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if ("ALL".equalsIgnoreCase(value.trim())) return;
        sql.append("AND ").append(column).append(" = '")
           .append(SqlSafety.escapeStringLiteral(value.trim()))
           .append("' ");
    }

    // -----------------------------------------------------------------------------------
    // Elasticsearch path (legacy — kept for D8(a) feature-flag rollback)
    // -----------------------------------------------------------------------------------

    private List<MetricResponseDTO> getMetricsViaElasticsearch(MetricRequestDTO request) {
        log.info("Fetching metrics via Elasticsearch (legacy path): metricName={}, serviceName={}, hostName={}, page={}, size={}",
                request.getMetricName(), request.getServiceName(), request.getHostName(),
                request.getPage(), request.getSize());
        Map<String, String> filters = esFilters(request);

        Query query = QueryBuilderUtil.buildBoolQuery(filters, request.getSearch(),
                request.getStartTime(), request.getEndTime(),
                AppConstants.TIMESTAMP_FIELD, VALUE_FIELD, "jvm", "transaction.duration");

        if (log.isDebugEnabled()) {
            log.debug("Elasticsearch metrics query: {}", query);
        }

        try {
            int from = request.getPage() * request.getSize();
            @SuppressWarnings("rawtypes")
            SearchResponse<Map> response = elasticsearchClientService.search(
                    AppConstants.METRICS_INDEX, query, from, request.getSize(),
                    AppConstants.TIMESTAMP_FIELD,
                    co.elastic.clients.elasticsearch._types.SortOrder.Desc, Map.class);

            return response.hits().hits().stream()
                    .map(this::mapEsHit)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.error("Error searching metrics in Elasticsearch", e);
            throw new RuntimeException("Error searching metrics", e);
        }
    }

    /** Term filters for the legacy path; a blank field, or service "ALL", adds none. */
    private static Map<String, String> esFilters(MetricRequestDTO request) {
        Map<String, String> filters = new HashMap<>();
        if (request.getMetricName() != null && !request.getMetricName().isEmpty())
            filters.put("metric.name", request.getMetricName());
        String serviceName = request.getServiceName();
        if (serviceName != null && !serviceName.trim().isEmpty()
                && !"ALL".equalsIgnoreCase(serviceName.trim())) {
            filters.put("service.name", serviceName.trim());
        }
        if (request.getHostName() != null && !request.getHostName().isEmpty())
            filters.put("host.name", request.getHostName());
        return filters;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MetricResponseDTO mapEsHit(co.elastic.clients.elasticsearch.core.search.Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        if (source == null) return null;

        MetricResponseDTO dto = new MetricResponseDTO();
        dto.setName(com.trinity.observability.util.MappingUtil.getStringValue(source,
                "metric.name", "metricset.name", "event.dataset"));
        dto.setValue(com.trinity.observability.util.MappingUtil.getDoubleValue(source,
                VALUE_FIELD, "jvm.thread.count", "jvm.memory.used", "jvm.gc.time"));
        dto.setTimestamp(com.trinity.observability.util.MappingUtil.getStringValue(source, "@timestamp"));
        dto.setServiceName(com.trinity.observability.util.MappingUtil.getStringValue(source,
                "service.name", "resource.attributes.service.name"));
        dto.setHostName(com.trinity.observability.util.MappingUtil.getStringValue(source,
                "host.name", "resource.attributes.host.name"));
        dto.setThreadState(com.trinity.observability.util.MappingUtil.getStringValue(source,
                "jvm.thread.state", "labels.jvm_thread_state", "thread.state"));
        dto.setThreadType(com.trinity.observability.util.MappingUtil.getStringValue(source,
                "jvm.thread.daemon", "labels.jvm_thread_daemon", "thread.type"));
        dto.setDuration(com.trinity.observability.util.MappingUtil.getLongValue(source,
                "transaction.duration.summary.sum", "jvm.gc.time", "duration"));
        return dto;
    }
}
