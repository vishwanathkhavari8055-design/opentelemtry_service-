package com.trinity.observability.serviceimpl;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.TraceLookupProperties;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.dto.traces.TraceResponseDTO;
import com.trinity.observability.dto.traces.TraceTreeNode;
import com.trinity.observability.dto.traces.TraceTreeResponse;
import com.trinity.observability.dto.traces.TracesSummaryRequestDTO;
import com.trinity.observability.dto.traces.TracesSummaryResponseDTO;
import com.trinity.observability.registry.OpenObserveStreamBounds;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.service.TracesService;
import com.trinity.observability.util.MappingUtil;
import com.trinity.observability.util.OoTraceClassifier;
import com.trinity.observability.util.OoTraceInterpreter;
import com.trinity.observability.util.QueryBuilderUtil;
import com.trinity.observability.util.RequestScopedFanout;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;
import com.trinity.observability.util.TraceClassifier;
import com.trinity.observability.util.TraceInterpreter;
import com.trinity.observability.util.TraceLogCorrelator;
import com.trinity.observability.util.TraceLookupWindow;
import com.trinity.observability.util.TraceTreeBuilder;
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
import java.util.stream.Collectors;

/**
 * Thin orchestrator for trace retrieval.
 *
 * Responsibilities:
 *  1. Build Elasticsearch query from request filters
 *  2. Execute search
 *  3. For each hit: extract base fields, classify span, interpret into DTO
 *  4. Build hierarchical execution tree via {@link TraceTreeBuilder}
 *
 * Span classification  → {@link TraceClassifier}
 * Span interpretation  → {@link TraceInterpreter}
 * Tree building        → {@link TraceTreeBuilder}
 * MongoDB parsing      → {@link com.trinity.observability.util.MongoStatementParser}
 * DB statement lookup  → {@link com.trinity.observability.util.DbStatementResolver}
 */
@Service
public class TracesServiceImpl implements TracesService {

    private static final Logger log = LogManager.getLogger(TracesServiceImpl.class);

    /** OpenObserve stream type (and Product Catalog signal) the trace queries run against. */
    private static final String TRACES_STREAM_TYPE = "traces";

    /** Grouping key for spans that carry no trace id; never reported as a trace id. */
    private static final String UNKNOWN_TRACE_ID = "unknown";

    private final ElasticsearchClientService elasticsearchClientService;
    private final OpenObserveClientService   openObserveClientService;
    private final OpenObserveConfig          openObserveConfig;

    /** Product Catalog scoping — see the field note in {@link LogsServiceImpl}. */
    private final CatalogTelemetryFilter     catalogFilter;

    /** Rules for a request that names one trace — see {@link TraceLookupProperties}. */
    private final TraceLookupProperties      traceLookup;

    /** Live stream extent, so a by-id lookup needs no invented window. Nullable —
     *  the test-only constructor leaves it out and the lookup falls back to the
     *  configured expressions. */
    private final OpenObserveStreamBounds    streamBounds;

    /** Runs the summary's two independent reads together — see {@link RequestScopedFanout}. */
    private final RequestScopedFanout        fanout;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    // ES injection is nullable — see comment in LogsServiceImpl. Safe because
    // every elasticsearchClientService call sits inside `!openObserveEnabled`.
    // @Autowired is required to disambiguate from the test-only constructor.
    @Autowired
    public TracesServiceImpl(@Nullable ElasticsearchClientService elasticsearchClientService,
                             OpenObserveClientService openObserveClientService,
                             OpenObserveConfig openObserveConfig,
                             CatalogTelemetryFilter catalogFilter,
                             TraceLookupProperties traceLookup,
                             OpenObserveStreamBounds streamBounds,
                             RequestScopedFanout fanout) {
        this.elasticsearchClientService = elasticsearchClientService;
        this.openObserveClientService   = openObserveClientService;
        this.openObserveConfig          = openObserveConfig;
        this.catalogFilter              = catalogFilter;
        this.traceLookup                = traceLookup;
        this.streamBounds               = streamBounds;
        this.fanout                     = fanout;
    }

    /** Test-only convenience constructor — no catalog scoping, default lookup rules. */
    public TracesServiceImpl(@Nullable ElasticsearchClientService elasticsearchClientService,
                             OpenObserveClientService openObserveClientService,
                             OpenObserveConfig openObserveConfig) {
        this(elasticsearchClientService, openObserveClientService, openObserveConfig,
                CatalogTelemetryFilter.permitAll(), new TraceLookupProperties(), null,
                new RequestScopedFanout(2));
    }

    /** The Product Catalog predicate for the traces signal, as an appendable clause. */
    private String catalogFilterClause() {
        String predicate = catalogFilter.sqlPredicate(TRACES_STREAM_TYPE);
        return predicate.isEmpty() ? "" : "AND " + predicate + " ";
    }

    @Override
    public List<TraceTreeResponse> getTraces(TraceRequestDTO request) {
        long t0 = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("getTraces() invoked: {}", request);
        }
        List<TraceTreeResponse> result = openObserveEnabled
                ? getTracesViaOpenObserve(request)
                : getTracesViaElasticsearch(request);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getTraces() returning {} trace(s) in {} ms (path={})",
                result.size(), elapsedMs, openObserveEnabled ? "openobserve" : "elasticsearch");
        return result;
    }

    @Override
    public TracesSummaryResponseDTO getTracesSummary(TracesSummaryRequestDTO request) {
        long t0 = System.nanoTime();

        String windowExpr = request.getWindow() != null
                ? request.getWindow()
                : com.trinity.observability.constants.AppConstants.DEFAULT_SUMMARY_WINDOW;
        long endMicros   = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(windowExpr, endMicros);

        TracesSummaryResponseDTO out = new TracesSummaryResponseDTO();
        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));
        out.setBackend(openObserveEnabled ? "openobserve" : "elasticsearch");

        if (!openObserveEnabled) {
            out.setSupported(false);
            out.setSlowestOps(Collections.emptyList());
            log.info("getTracesSummary() returning supported=false on ES path");
            return out;
        }

        aggregateTracesSummary(out, startMicros, endMicros);
        out.setSupported(true);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getTracesSummary() traceCount={} errorRate={} p95Ms={} in {} ms",
                out.getTraceCount(), out.getErrorRate(), out.getP95LatencyMs(), elapsedMs);
        return out;
    }

    /**
     * Per-trace_id aggregate. The root span's duration is approximated by
     * {@code MAX(duration)} within a trace; for >99% of traces this matches
     * the actual root because the parent span subsumes its children. Spans
     * are stored in microseconds — we convert to ms when projecting.
     */
    private void aggregateTracesSummary(TracesSummaryResponseDTO out,
                                        long startMicros, long endMicros) {
        String stream = SqlSafety.validateIdentifier(openObserveConfig.getTracesStream());
        String sql = "SELECT trace_id, "
                +    "MAX(duration) AS root_duration_us, "
                +    "MAX(CASE WHEN span_status = 'ERROR' OR status_code = 'ERROR' "
                +              "OR span_status LIKE '%FAIL%' THEN 1 ELSE 0 END) AS is_error "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                +   "AND _timestamp <= " + endMicros + " "
                +   "AND trace_id IS NOT NULL AND trace_id != '' "
                // Product Catalog scoping inside the aggregate: the trace count,
                // error rate and p95 are all derived from these rows, so filtering
                // afterwards would report percentiles over a population the caller
                // is not being shown.
                +   catalogFilterClause()
                + "GROUP BY trace_id";

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve traces-summary SQL: {}", sql);
        }

        // The slowest-ops query does not depend on the aggregate, so the two run
        // together: this endpoint used to pay aggregate + slowest-ops in series.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>>[] hits = new List[1];
        Exception[] failure = new Exception[1];
        @SuppressWarnings("unchecked")
        List<TracesSummaryResponseDTO.SlowestOp>[] slowest = new List[1];
        fanout.runAll(List.of(
                () -> {
                    try {
                        hits[0] = openObserveClientService.search(sql, startMicros, endMicros, 0, 10000, TRACES_STREAM_TYPE);
                    } catch (Exception e) {
                        failure[0] = e;
                    }
                },
                () -> slowest[0] = fetchSlowestOps(stream, startMicros, endMicros)));

        if (failure[0] != null || hits[0] == null) {
            Exception e = failure[0];
            log.error("Traces summary query failed: {}", e == null ? "no result" : e.getMessage(), e);
            applyEmptySummary(out);
            return;
        }

        applyTraceStatistics(out, hits[0]);
        out.setSlowestOps(slowest[0] == null ? Collections.emptyList() : slowest[0]);
    }

    private void applyTraceStatistics(TracesSummaryResponseDTO out, List<Map<String, Object>> hits) {
        long traceCount = 0L;
        long errorCount = 0L;
        double totalMs  = 0.0;
        List<Double> latencies = new ArrayList<>(hits.size());

        for (Map<String, Object> hit : hits) {
            Number dur = (Number) hit.get("root_duration_us");
            Number err = (Number) hit.get("is_error");
            double ms  = dur == null ? 0.0 : dur.doubleValue() / 1000.0;

            traceCount++;
            totalMs += ms;
            latencies.add(ms);
            if (err != null && err.intValue() > 0) errorCount++;
        }

        out.setTraceCount(traceCount);
        out.setErrorCount(errorCount);
        out.setErrorRate(traceCount == 0 ? 0.0 : (double) errorCount / traceCount);
        out.setAvgLatencyMs(traceCount == 0 ? 0.0 : totalMs / traceCount);
        out.setP95LatencyMs(percentile(latencies, 0.95));
    }

    /**
     * Top-5 operations by max observed duration in the window. A separate
     * query (not derivable from the per-trace aggregate above because the
     * "slowest op" is generally NOT the root span of its trace).
     */
    private List<TracesSummaryResponseDTO.SlowestOp> fetchSlowestOps(
            String stream, long startMicros, long endMicros) {
        String sql = "SELECT operation_name, MAX(duration) AS dur_us "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                +   "AND _timestamp <= " + endMicros + " "
                +   "AND operation_name IS NOT NULL "
                +   catalogFilterClause()
                + "GROUP BY operation_name "
                + "ORDER BY MAX(duration) DESC "
                + "LIMIT 5";

        List<Map<String, Object>> hits;
        try {
            hits = openObserveClientService.search(sql, startMicros, endMicros, 0, 5, TRACES_STREAM_TYPE);
        } catch (Exception e) {
            log.debug("slowestOps lookup skipped: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<TracesSummaryResponseDTO.SlowestOp> out = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            String name = (String) hit.get("operation_name");
            Number durUs = (Number) hit.get("dur_us");
            if (name == null) continue;
            double ms = durUs == null ? 0.0 : durUs.doubleValue() / 1000.0;
            out.add(new TracesSummaryResponseDTO.SlowestOp(name, ms));
        }
        return out;
    }

    /**
     * Nearest-rank percentile over the sorted sample. Cheap O(n log n) sort
     * + O(1) lookup; fine for the <10k traces we'd ever aggregate here.
     */
    private static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    private static void applyEmptySummary(TracesSummaryResponseDTO out) {
        out.setTraceCount(0L);
        out.setErrorCount(0L);
        out.setErrorRate(0.0);
        out.setAvgLatencyMs(0.0);
        out.setP95LatencyMs(0.0);
        out.setSlowestOps(Collections.emptyList());
    }

    // -----------------------------------------------------------------------------------
    // OpenObserve path (Phase E — D8(a) feature flag)
    // -----------------------------------------------------------------------------------

    private List<TraceTreeResponse> getTracesViaOpenObserve(TraceRequestDTO request) {
        log.info("Fetching traces via OpenObserve: traceId={}, serviceName={}, spanId={}, page={}, size={}",
                request.getTraceId(), request.getServiceName(), request.getSpanId(),
                request.getPage(), request.getSize());

        final String filterClause = buildTraceFilterClause(request);

        SpanHits found;
        try {
            found = isWholeTraceLookup(request)
                    ? lookupWholeTrace(request, filterClause)
                    : searchRequestedWindow(request, filterClause);
        } catch (Exception e) {
            log.error("OpenObserve trace search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Error searching traces in OpenObserve", e);
        }
        long startMicros = found.startMicros();
        long endMicros = found.endMicros();
        List<Map<String, Object>> hits = found.hits();

        log.trace("Resolved time range: startMicros={}, endMicros={}", startMicros, endMicros);
        log.debug("OpenObserve traces returned {} span hit(s)", hits.size());

        // Map flat hits → DTOs
        List<TraceResponseDTO> allSpans = hits.stream()
            .map(this::mapOpenObserveHitToDTO)
            .filter(Objects::nonNull)
            .toList();

        // Group by traceId — same as ES path
        Map<String, List<TraceResponseDTO>> spansByTrace = allSpans.stream()
            .collect(Collectors.groupingBy(
                s -> s.getTraceId() != null ? s.getTraceId() : UNKNOWN_TRACE_ID,
                LinkedHashMap::new,
                Collectors.toList()
            ));

        // ONE log read for the whole page, not one per trace. Per-trace this was
        // 1 + N OpenObserve round trips (21 for a 20-trace page, ~19.6 s measured)
        // for data a single trace_id IN (...) query returns in one. Same batching
        // the alert measurement lookup already uses.
        Map<String, List<Map<String, Object>>> logsByTrace =
            TraceLogCorrelator.fetchLogsForTraces(
                spansByTrace.keySet().stream()
                    .filter(id -> !UNKNOWN_TRACE_ID.equals(id))
                    .toList(),
                openObserveClientService, openObserveConfig.getLogsStream(),
                startMicros, endMicros);

        List<TraceTreeResponse> result = new ArrayList<>();
        for (Map.Entry<String, List<TraceResponseDTO>> entry : spansByTrace.entrySet()) {
            String traceId = entry.getKey();
            List<TraceResponseDTO> traceSpans = entry.getValue();

            TraceTreeNode rootOperation = TraceTreeBuilder.build(traceSpans);

            // Severity-Aware enrichment: attach log events using the OO correlator overload (Phase C)
            if (rootOperation != null && !UNKNOWN_TRACE_ID.equals(traceId)) {
                TraceLogCorrelator.correlate(traceId, rootOperation,
                    logsByTrace.get(traceId));
            }

            result.add(toTreeResponse(traceId, traceSpans, rootOperation));
        }

        return result;
    }

    /** The span hits of one traces search, plus the range they were searched over. */
    private record SpanHits(long startMicros, long endMicros, List<Map<String, Object>> hits) {}

    /**
     * ONE named trace and no caller window. Two things must not happen
     * here, and both used to: clamping to the default 12h range (a log
     * older than that opened an empty trace), and paginating by span.
     * This endpoint assembles a TREE, so a page of spans is not a
     * smaller trace — spans come back newest-first, so the slice that
     * gets dropped contains the ROOT. Measured before this change: a
     * 232-span trace answered as 100 spans rooted at a `SELECT`,
     * reporting a total duration of 1.8 ms.
     */
    private SpanHits lookupWholeTrace(TraceRequestDTO request, String filterClause) {
        final int cap = traceLookup.getMaxSpans();
        TraceLookupWindow.Resolved resolved = TraceLookupWindow.narrowestComplete(
                tracesStreamMinMicros(),
                traceLookup.getWindows(),
                traceLookup.rootMarginMicros(),
                (s, e) -> openObserveClientService.search(
                        composeTraceSql(filterClause, s, e), s, e, 0, cap, TRACES_STREAM_TYPE));

        List<Map<String, Object>> hits = resolved.hits();
        if (hits.size() == cap) {
            log.warn("Trace {} hit the traces.lookup.max-spans cap of {} — the tree may be "
                    + "incomplete", request.getTraceId(), cap);
        }
        return new SpanHits(resolved.startMicros(), resolved.endMicros(), hits);
    }

    /** The caller's window (or the default one), paginated by span. */
    private SpanHits searchRequestedWindow(TraceRequestDTO request, String filterClause) {
        long startMicros = TimeRangeParser.toMicros(request.getStartTime(),
                TimeRangeParser.defaultStartMicros());
        long endMicros   = TimeRangeParser.toMicros(request.getEndTime(),
                TimeRangeParser.defaultEndMicros());
        int from = request.getPage() * request.getSize();
        List<Map<String, Object>> hits = openObserveClientService.search(
                composeTraceSql(filterClause, startMicros, endMicros),
                startMicros, endMicros, from, request.getSize(), TRACES_STREAM_TYPE);
        return new SpanHits(startMicros, endMicros, hits);
    }

    /**
     * Finalizes one trace's tree (root events → Internal, aggregate statuses,
     * mask secrets) and wraps it with its spans, id, service name and summary.
     */
    private static TraceTreeResponse toTreeResponse(String traceId, List<TraceResponseDTO> traceSpans,
                                                    TraceTreeNode rootOperation) {
        if (rootOperation != null) {
            TraceTreeBuilder.finalizeGroups(rootOperation);
        }

        // Determine service name from root node or first span
        String svcName;
        if (rootOperation != null && rootOperation.getServiceName() != null) {
            svcName = rootOperation.getServiceName();
        } else if (!traceSpans.isEmpty()) {
            svcName = traceSpans.get(0).getServiceName();
        } else {
            svcName = null;
        }

        TraceTreeResponse treeResponse = new TraceTreeResponse(traceSpans, rootOperation);
        treeResponse.setTraceId(UNKNOWN_TRACE_ID.equals(traceId) ? null : traceId);
        treeResponse.setServiceName(svcName);
        treeResponse.setSummary(TraceTreeBuilder.buildSummary(rootOperation));
        return treeResponse;
    }

    /** SELECT … WHERE &lt;request filters&gt; — everything except the time bounds and ORDER BY. */
    private String buildTraceFilterClause(TraceRequestDTO request) {
        String safeStream = SqlSafety.validateIdentifier(openObserveConfig.getTracesStream());

        StringBuilder sql = new StringBuilder()
            .append("SELECT _timestamp, trace_id, span_id, reference_parent_span_id, ")
            .append("operation_name, span_kind, span_status, status_code, status_message, ")
            .append("service_name, service_host_name, duration, thread_name, ")
            .append("http_request_method, http_response_status_code, url_full, url_path, http_route, ")
            // Captured by the OTel agent when capture-bodies/capture-headers
            // is enabled; surfaced in the UI's inspector pane via
            // OoTraceInterpreter.applyHttpFields.
            .append("http_request_body, http_response_body, ")
            .append("db_system, db_name, db_statement, db_operation, db_mongodb_collection, db_sql_table ")
            .append("FROM \"").append(safeStream).append("\" WHERE 1=1 ");

        appendOptionalEqFilter(sql, "trace_id",     request.getTraceId());
        appendOptionalEqFilter(sql, "span_id",      request.getSpanId());

        String serviceName = request.getServiceName();
        if (isPresent(serviceName) && !"ALL".equalsIgnoreCase(serviceName.trim())) {
            sql.append("AND service_name = '")
               .append(SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(serviceName.trim())))
               .append("' ");
        }

        // transaction.name (ES) → operation_name (OTel). Use LIKE for substring match.
        if (isPresent(request.getTransactionName())) {
            String escaped = SqlSafety.escapeLikePattern(request.getTransactionName().trim());
            sql.append("AND operation_name LIKE '%").append(escaped).append("%' ESCAPE '\\' ");
        }

        // event.outcome (ES) → span_status (OTel). Translate values.
        String outcome = request.getOutcome();
        if (isPresent(outcome)) {
            sql.append("AND span_status = '")
               .append(SqlSafety.escapeStringLiteral(mapOutcomeToSpanStatus(outcome)))
               .append("' ");
        }

        appendOptionalEqFilter(sql, "db_name", request.getDbName());

        String type = request.getType();
        if (isPresent(type) && !"ALL".equalsIgnoreCase(type.trim())) {
            appendTypeFilter(sql, type.trim().toLowerCase());
        }


        // Free-text search across operation_name + db_statement (closest analog to ES query_string).
        if (isPresent(request.getSearch())) {
            String escaped = SqlSafety.escapeLikePattern(request.getSearch().trim());
            sql.append("AND (operation_name LIKE '%").append(escaped)
               .append("%' ESCAPE '\\' OR db_statement LIKE '%").append(escaped)
               .append("%' ESCAPE '\\') ");
        }

        // Everything above is filter predicates; the time bounds and ORDER BY are
        // appended per attempt below, because a widening lookup issues the same
        // filters against more than one range.
        return sql.toString();
    }

    /** event.outcome (ES) value → span_status (OTel) value. */
    private static String mapOutcomeToSpanStatus(String outcome) {
        switch (outcome.trim().toLowerCase()) {
            case "success": return "OK";
            case "failure": return "ERROR";
            default:        return outcome.trim();
        }
    }

    /** Span-type filter on db_system; {@code trimmedType} is already trimmed and lower-cased. */
    private static void appendTypeFilter(StringBuilder sql, String trimmedType) {
        switch (trimmedType) {
            case "http":
                sql.append("AND db_system IS NULL ");
                break;
            case "sql":
                sql.append("AND db_system IN ('mysql', 'postgresql', 'mariadb', 'h2', 'sqlite', 'db2', 'mssql', 'oracle', 'sqlserver') ");
                break;
            case "redis":
                sql.append("AND db_system = 'redis' ");
                break;
            case "neo4j":
                sql.append("AND db_system = 'neo4j' ");
                break;
            case "mongodb":
                sql.append("AND db_system = 'mongodb' ");
                break;
            default:
                sql.append("AND db_system = '")
                   .append(SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(trimmedType)))
                   .append("' ");
                break;
        }
    }

    /**
     * Map a flat OpenObserve hit to a TraceResponseDTO.
     *
     * <p>Base fields come from direct column reads (Phase E); enrichment
     * (type / direction / status / target / databaseType / dbStatement / endpoint /
     * httpMethod / httpStatus) is delegated to {@link OoTraceInterpreter} (Phase F).</p>
     *
     * <p>Cyclomatic complexity: 2.</p>
     */
    private TraceResponseDTO mapOpenObserveHitToDTO(Map<String, Object> hit) {
        if (hit == null) return null;

        TraceResponseDTO dto = new TraceResponseDTO();
        populateBaseFields(hit, dto);

        TraceClassifier.SpanType type = OoTraceClassifier.classify(hit);
        OoTraceInterpreter.interpret(type, hit, dto);

        return dto;
    }

    /**
     * Set the fields that come directly from columns — IDs, timestamps, names, durations.
     * <p>Cyclomatic complexity: 3.</p>
     */
    private static void populateBaseFields(Map<String, Object> hit, TraceResponseDTO dto) {
        // Defensive Number cast for Int64 columns (r7) — _timestamp and duration both µs.
        Number tsRaw = (Number) hit.get("_timestamp");
        dto.setTimestamp(tsRaw == null ? null : TimeFormatter.microsToIso(tsRaw.longValue()));

        // Duration stays in microseconds end-to-end (r3): TraceTreeBuilder owns the µs→ms divide.
        Number durRaw = (Number) hit.get("duration");
        dto.setDuration(durRaw == null ? null : durRaw.longValue());

        dto.setTraceId(    (String) hit.get("trace_id"));
        dto.setSpanId(     (String) hit.get("span_id"));
        dto.setParentSpanId((String) hit.get("reference_parent_span_id"));

        String opName = (String) hit.get("operation_name");
        dto.setSpanName(opName);
        dto.setActualOperation(opName);

        dto.setServiceName((String) hit.get("service_name"));
        dto.setHostName(   (String) hit.get("service_host_name"));
        dto.setThreadName( (String) hit.get("thread_name"));
    }

    private static void appendOptionalEqFilter(StringBuilder sql, String column, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sql.append("AND ").append(column).append(" = '")
           .append(SqlSafety.escapeStringLiteral(value.trim()))
           .append("' ");
    }

    /**
     * Finish a traces query by bolting the time bounds, the Product Catalog
     * predicate and the ordering onto the filter clause.
     *
     * <p>The catalog filter stays BEFORE the ordering and the client-side
     * pagination for the reason it always did: OpenObserve pages server-side, so
     * excluding spans afterwards would give short pages and — because spans are
     * grouped into traces here — incomplete trees.</p>
     */
    private String composeTraceSql(String filterClause, long startMicros, long endMicros) {
        String composed = filterClause
                + "AND _timestamp >= " + startMicros + ' '
                + "AND _timestamp <= " + endMicros + ' '
                + catalogFilterClause()
                + "ORDER BY _timestamp DESC";
        if (log.isDebugEnabled()) {
            log.debug("OpenObserve traces SQL: {}", composed);
        }
        return composed;
    }

    /**
     * True when the request identifies ONE trace and leaves the range open.
     *
     * <p>The second half is what keeps this from changing any existing caller. A
     * trace id combined with an explicit {@code startTime}/{@code endTime} is
     * still a range query — that is the Traces screen filtering its own table by a
     * pasted id, and it keeps its window and its pagination. Only a caller that
     * asks for a trace and says nothing about time is asking "open this trace",
     * and that is the request that was being answered with a truncated tree.</p>
     */
    private static boolean isWholeTraceLookup(TraceRequestDTO request) {
        return isPresent(request.getTraceId()) && !hasExplicitWindow(request);
    }

    private static boolean hasExplicitWindow(TraceRequestDTO request) {
        return (request.getStartTime() != null && !request.getStartTime().isBlank())
                || (request.getEndTime() != null && !request.getEndTime().isBlank());
    }

    /**
     * Oldest timestamp the traces stream actually holds, or {@code null} if
     * OpenObserve does not report one — see {@link OpenObserveStreamBounds}. Lets a
     * by-id lookup search the real extent of the data instead of a window this
     * service picked.
     */
    private Long tracesStreamMinMicros() {
        if (streamBounds == null) return null;
        OpenObserveStreamBounds.Bounds bounds =
                streamBounds.forStream(openObserveConfig.getTracesStream(), TRACES_STREAM_TYPE);
        return bounds == null ? null : bounds.minMicros();
    }

    // -----------------------------------------------------------------------------------
    // Elasticsearch path (legacy — kept for D8(a) rollback)
    // -----------------------------------------------------------------------------------

    private List<TraceTreeResponse> getTracesViaElasticsearch(TraceRequestDTO request) {
        log.info("Fetching traces via Elasticsearch (legacy path): traceId={}, serviceName={}, spanId={}, page={}, size={}",
                request.getTraceId(), request.getServiceName(), request.getSpanId(),
                request.getPage(), request.getSize());

        Query query = QueryBuilderUtil.buildBoolQuery(
                buildFilters(request),
                request.getSearch(),
                request.getStartTime(),
                request.getEndTime(),
                AppConstants.TIMESTAMP_FIELD);

        if (log.isDebugEnabled()) {
            log.debug("Elasticsearch traces query: {}", query);
        }

        try {
            int from = request.getPage() * request.getSize();
            @SuppressWarnings("rawtypes")
            SearchResponse<Map> response = elasticsearchClientService.search(
                    AppConstants.TRACES_INDEX, query, from, request.getSize(),
                    AppConstants.TIMESTAMP_FIELD, co.elastic.clients.elasticsearch._types.SortOrder.Desc, Map.class);

            // 1. Build flat span list (existing behavior — unchanged)
            List<TraceResponseDTO> allSpans = response.hits().hits().stream()
                    .map(hit -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> source = hit.source();
                        return source != null ? toDTO(source) : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();

            // 2. Group spans by traceId
            Map<String, List<TraceResponseDTO>> spansByTrace = allSpans.stream()
                    .collect(Collectors.groupingBy(
                            s -> s.getTraceId() != null ? s.getTraceId() : UNKNOWN_TRACE_ID,
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));

            List<TraceTreeResponse> result = new ArrayList<>();
            for (Map.Entry<String, List<TraceResponseDTO>> entry : spansByTrace.entrySet()) {
                String traceId = entry.getKey();
                List<TraceResponseDTO> traceSpans = entry.getValue();

                // Build hierarchical execution tree for this trace
                TraceTreeNode rootOperation = TraceTreeBuilder.build(traceSpans);

                // ── Severity-Aware enrichment: attach log events to tree nodes ──
                if (rootOperation != null && !UNKNOWN_TRACE_ID.equals(traceId)) {
                    TraceLogCorrelator.correlate(traceId, rootOperation, elasticsearchClientService);
                }

                // ── Finalize groups, service name, summary block ──
                result.add(toTreeResponse(traceId, traceSpans, rootOperation));
            }

            return result;

        } catch (IOException e) {
            log.error("Error searching traces in Elasticsearch", e);
            throw new RuntimeException("Error searching traces", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build filter map from request fields */
    private Map<String, String> buildFilters(TraceRequestDTO request) {
        Map<String, String> filters = new HashMap<>();
        if (isPresent(request.getTraceId()))        filters.put("trace.id",          request.getTraceId());
        if (isPresent(request.getSpanId()))          filters.put("span.id",           request.getSpanId());
        String serviceName = request.getServiceName();
        if (isPresent(serviceName) && !"ALL".equalsIgnoreCase(serviceName.trim())) {
            filters.put("service.name", serviceName.trim());
        }
        if (isPresent(request.getTransactionName())) filters.put("transaction.name",  request.getTransactionName());
        if (isPresent(request.getOutcome()))         filters.put("event.outcome",     request.getOutcome());
        if (isPresent(request.getDbName()))          filters.put("db.name",           request.getDbName());
        return filters;
    }

    /** Map a single raw Elasticsearch hit to a fully-enriched TraceResponseDTO */
    private TraceResponseDTO toDTO(Map<String, Object> source) {
        TraceResponseDTO dto = new TraceResponseDTO();

        // ── Base fields (always populated) ───────────────────────────────────
        dto.setTraceId(MappingUtil.getStringValue(source, "trace.id"));
        dto.setSpanId(MappingUtil.getStringValue(source, "span.id"));
        // The ES OTel mapping stores the parent span under the parent id field,
        // while some shippers nest it under the span instead, so both are tried.
        dto.setParentSpanId(MappingUtil.getStringValue(source, "parent.id", "span.parent.id"));
        dto.setTimestamp(MappingUtil.getStringValue(source, "@timestamp"));
        dto.setServiceName(MappingUtil.getStringValue(source,
                "service.name", "resource.attributes.service.name"));
        dto.setHostName(MappingUtil.getStringValue(source,
                "host.name", "host.hostname", "resource.attributes.host.name"));
        dto.setThreadName(MappingUtil.getStringValue(source,
                "thread.name", "process.thread.name"));
        dto.setDuration(MappingUtil.getLongValue(source,
                "span.duration.us", "transaction.duration.us", "duration"));

        // ── Classify → Interpret ──────────────────────────────────────────────
        TraceClassifier.SpanType type = TraceClassifier.classify(source);
        TraceInterpreter.interpret(type, source, dto);

        return dto;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isEmpty();
    }
}
