package com.trinity.observability.serviceimpl;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.logs.LogPageResponseDTO;
import com.trinity.observability.dto.logs.LogRequestDTO;
import com.trinity.observability.dto.logs.LogResponseDTO;
import com.trinity.observability.dto.logs.LogServicesResponseDTO;
import com.trinity.observability.dto.logs.LogTraceSummaryDTO;
import com.trinity.observability.dto.logs.LogTraceSummaryPageDTO;
import com.trinity.observability.dto.logs.LogsSummaryRequestDTO;
import com.trinity.observability.dto.logs.LogsSummaryResponseDTO;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.service.LogsService;
import com.trinity.observability.util.LogNormalizer;
import com.trinity.observability.util.MappingUtil;
import com.trinity.observability.util.QueryBuilderUtil;
import com.trinity.observability.util.SeverityRank;
import com.trinity.observability.service.StreamCatalogService;
import com.trinity.observability.util.SortSpec;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LogsServiceImpl implements LogsService {

    private static final Logger log = LogManager.getLogger(LogsServiceImpl.class);

    /** One quote at either end of a value, so "\"svc\"" and "'svc'" both read as svc. */
    private static final java.util.regex.Pattern SURROUNDING_QUOTE =
            java.util.regex.Pattern.compile("(^[\"'])|([\"']$)");

    /** Backend names reported in the {@code backend} field of the responses. */
    private static final String BACKEND_OPENOBSERVE = "openobserve";
    private static final String BACKEND_ELASTICSEARCH = "elasticsearch";

    /** Canonical severity levels (the values OpenObserve stores and the UI buckets on). */
    private static final String SEV_FATAL = "FATAL";
    private static final String SEV_ERROR = "ERROR";
    private static final String SEV_WARN = "WARN";
    private static final String SEV_INFO = "INFO";
    private static final String SEV_DEBUG = "DEBUG";
    private static final String SEV_TRACE = "TRACE";
    private static final Set<String> CANONICAL_SEVERITIES =
            Set.of(SEV_ERROR, SEV_WARN, SEV_INFO, SEV_DEBUG, SEV_TRACE, SEV_FATAL);

    /** Column / field names read from OpenObserve rows (and, where noted, ES documents). */
    private static final String COL_TIMESTAMP = "_timestamp";
    private static final String COL_TRACE_ID = "trace_id";
    private static final String COL_SERVICE_NAME = "service_name";
    private static final String COL_SEVERITY = "severity";
    private static final String COL_HOST_NAME = "host_name";
    private static final String COL_OS_TYPE = "os_type";
    private static final String COL_SCOPE_NAME = "instrumentation_library_name";
    /** Free-text message column of foreign streams; also the ES message fallback field. */
    private static final String COL_MESSAGE = "message";
    /** Severity text column of foreign streams; also the ES severity field. */
    private static final String COL_SEVERITY_TEXT = "severity_text";

    /** SQL fragments repeated across the query builders below. */
    private static final String SQL_FROM_QUOTED = "FROM \"";
    private static final String SQL_AND_TS_GTE = "AND _timestamp >= ";
    private static final String SQL_AND_TS_LTE = "AND _timestamp <= ";
    /** {@code LIKE '%<escaped term>%' ESCAPE '\\'}: the opening and closing halves. */
    private static final String SQL_LIKE_PREFIX = "LIKE '%";
    private static final String SQL_LIKE_ESCAPE_SUFFIX = "%' ESCAPE '\\'";

    private final ElasticsearchClientService elasticsearchClientService;
    private final OpenObserveClientService openObserveClientService;
    private final OpenObserveConfig openObserveConfig;

    /**
     * Product Catalog scoping. Contributes one predicate to every SQL query below
     * so a resource an operator disabled stops appearing here — without this class
     * gaining a parameter, and without the {@code /api/logs} contract changing.
     * Answers {@code ""} on an empty registry, which is the state a fresh
     * deployment is in, so behaviour is unchanged until something is registered.
     */
    private final CatalogTelemetryFilter catalogFilter;

    /**
     * Stream/column discovery, for the per-request stream selection.
     *
     * <p>Nullable, and null means "assume every stream is OTel-shaped" — exactly
     * the behaviour before this existed. That is what lets the test-only
     * constructor below keep its signature: a test asserting on a severity clause
     * has no business constructing a stream catalogue.</p>
     */
    private final StreamCatalogService streamCatalog;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    // Elasticsearch is an optional backend (see ElasticsearchConfig's
    // @ConditionalOnExpression). When `elasticsearch.host` is unset the bean
    // isn't created, and Spring injects null here. Every ES code path already
    // runs only when OpenObserve is disabled, so a null client is safe as long
    // as that guard stays intact.
    //
    // @Autowired is REQUIRED, not decorative: with two public constructors and
    // neither marked, Spring cannot choose and falls back to a no-arg one that
    // does not exist — which fails at startup, not at compile time.
    @Autowired
    public LogsServiceImpl(@Nullable ElasticsearchClientService elasticsearchClientService,
            OpenObserveClientService openObserveClientService,
            OpenObserveConfig openObserveConfig,
            CatalogTelemetryFilter catalogFilter,
            StreamCatalogService streamCatalog) {
        this.elasticsearchClientService = elasticsearchClientService;
        this.openObserveClientService = openObserveClientService;
        this.openObserveConfig = openObserveConfig;
        this.catalogFilter = catalogFilter;
        this.streamCatalog = streamCatalog;
    }

    /**
     * Convenience constructor without catalog scoping.
     *
     * <p>Kept so the existing unit tests keep asserting on exactly the SQL they
     * always did — a test that had to construct a registry to check a severity
     * clause would be testing the wrong thing. Same pattern as
     * {@link ServicesServiceImpl}'s test-only constructor.</p>
     */
    public LogsServiceImpl(@Nullable ElasticsearchClientService elasticsearchClientService,
            OpenObserveClientService openObserveClientService,
            OpenObserveConfig openObserveConfig) {
        this(elasticsearchClientService, openObserveClientService, openObserveConfig,
                CatalogTelemetryFilter.permitAll(), null);
    }

    /**
     * Append the Product Catalog predicate for a signal, if there is one.
     *
     * <p>One helper rather than the same three lines at six call sites: every
     * query in this class has to carry the same scoping, and a query that
     * silently forgot it would show a disabled resource's logs in one view while
     * hiding them in another.</p>
     */
    private void appendCatalogFilter(StringBuilder sql, String signal) {
        String predicate = catalogFilter.sqlPredicate(signal);
        if (!predicate.isEmpty()) {
            sql.append("AND ").append(predicate).append(' ');
        }
    }

    /** {@link #appendCatalogFilter} for the string-concatenation query builders. */
    private String catalogFilterClause(String signal) {
        String predicate = catalogFilter.sqlPredicate(signal);
        return predicate.isEmpty() ? "" : "AND " + predicate + " ";
    }

    @Override
    public LogPageResponseDTO getLogs(LogRequestDTO request) {
        long t0 = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("getLogs() invoked: {}", request);
        }
        // Phase 2 (issue #8): inner methods fetch size+1 rows so we can tell
        // whether there's a next page. If the backend returned all size+1, we
        // know more pages exist (hasMore=true) and slice to the requested size.
        int requestedSize = request.getSize();
        List<LogResponseDTO> raw = openObserveEnabled
                ? getLogsViaOpenObserve(request)
                : getLogsViaElasticsearch(request);
        // Note on edge case: LogNormalizer drops INFO-noise rows after fetch,
        // so raw.size() can be less than (size+1) even when more pages exist.
        // Treat hasMore as a best-effort signal — false negatives are rare
        // (filtering removes a small minority of rows) and only mean the user
        // sees one extra "Next" click before the empty page tells them they're
        // done. The previous "items.size() < pageSize" heuristic was strictly
        // worse: with backend-side LIMIT that always returned exactly pageSize,
        // it produced false negatives every time.
        boolean hasMore = raw.size() > requestedSize;
        List<LogResponseDTO> items = hasMore
                ? new ArrayList<>(raw.subList(0, requestedSize))
                : raw;
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getLogs() returning {} row(s), hasMore={} in {} ms (path={})",
                items.size(), hasMore, elapsedMs, openObserveEnabled ? BACKEND_OPENOBSERVE : BACKEND_ELASTICSEARCH);
        return new LogPageResponseDTO(items, hasMore, null);
    }

    @Override
    public LogTraceSummaryPageDTO getTraceGroups(LogRequestDTO request) {
        long t0 = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("getTraceGroups() invoked: {}", request);
        }
        LogTraceSummaryPageDTO result = openObserveEnabled
                ? getTraceGroupsViaOpenObserve(request)
                : getTraceGroupsViaElasticsearch();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getTraceGroups() returning {} trace(s), hasMore={} in {} ms (path={})",
                result.getItems() != null ? result.getItems().size() : 0,
                result.isHasMore(), elapsedMs,
                openObserveEnabled ? BACKEND_OPENOBSERVE : BACKEND_ELASTICSEARCH);
        return result;
    }

    @Override
    public LogsSummaryResponseDTO getLogsSummary(LogsSummaryRequestDTO request) {
        long t0 = System.nanoTime();

        String windowExpr = request.getWindow() != null
                ? request.getWindow()
                : com.trinity.observability.constants.AppConstants.DEFAULT_SUMMARY_WINDOW;
        long endMicros = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(windowExpr, endMicros);

        LogsSummaryResponseDTO out = new LogsSummaryResponseDTO();
        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));
        out.setBackend(openObserveEnabled ? BACKEND_OPENOBSERVE : BACKEND_ELASTICSEARCH);

        if (!openObserveEnabled) {
            // Out-of-scope per the plan — ES path returns empty so the UI's
            // Summary card can render a "not supported on ES backend" message.
            out.setSupported(false);
            out.setTotalCount(0L);
            out.setCountsBySeverity(Collections.emptyMap());
            out.setTopErrorServices(Collections.emptyList());
            log.info("getLogsSummary() returning supported=false on ES path");
            return out;
        }

        aggregateLogsSummary(out, startMicros, endMicros);
        out.setSupported(true);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getLogsSummary() returning total={} severities={} topErrors={} in {} ms",
                out.getTotalCount(), out.getCountsBySeverity().size(),
                out.getTopErrorServices().size(), elapsedMs);
        return out;
    }

    @Override
    public LogServicesResponseDTO listServices(String window, String startTime, String endTime) {
        long t0 = System.nanoTime();
        LogServicesResponseDTO out = new LogServicesResponseDTO();
        out.setBackend(openObserveEnabled ? BACKEND_OPENOBSERVE : BACKEND_ELASTICSEARCH);

        if (!openObserveEnabled) {
            // Parity with getLogsSummary: ES path returns supported=false so the
            // UI can fall back to whatever discovery dataset it already has.
            out.setSupported(false);
            out.setItems(Collections.emptyList());
            log.info("listServices() returning supported=false on ES path");
            return out;
        }

        ListServicesWindow range = resolveListServicesWindow(window, startTime, endTime);
        long startMicros = range.startMicros();
        long endMicros = range.endMicros();
        String windowExpr = range.label();

        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));

        String safeStream = SqlSafety.validateIdentifier(openObserveConfig.getLogsStream());
        // COUNT(*) alongside the name so the UI can show "OrderService (18.2K)".
        // Same GROUP BY, so the name list and the counts are guaranteed
        // consistent with each other and with the window.
        String sql = "SELECT service_name, COUNT(*) AS log_count "
                + SQL_FROM_QUOTED + safeStream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                + SQL_AND_TS_LTE + endMicros + " "
                + "AND service_name IS NOT NULL "
                + "AND service_name != '' "
                // Product Catalog scoping: a disabled resource must not appear in
                // the service picker either. Offering a name whose logs are then
                // filtered out would look like a broken filter.
                + catalogFilterClause("logs")
                + "GROUP BY service_name "
                + "ORDER BY service_name";

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve logs-services SQL: {}", sql);
        }

        List<String> items = Collections.emptyList();
        List<LogServicesResponseDTO.ServiceCount> services = Collections.emptyList();
        try {
            // size=1000 covers the observed cardinality (106 on this
            // deployment) with headroom. Aggregation, not a row fetch, so this
            // is one bucket per service regardless of log volume.
            List<Map<String, Object>> hits = openObserveClientService.search(
                    sql, startMicros, endMicros, 0, 1000, "logs");

            Map<String, Long> byName = countLogsByServiceName(hits);

            services = byName.entrySet().stream()
                    .map(e -> new LogServicesResponseDTO.ServiceCount(e.getKey(), e.getValue()))
                    .toList();
            items = new ArrayList<>(byName.keySet());
        } catch (Exception e) {
            log.error("listServices query failed: {}", e.getMessage(), e);
        }

        out.setItems(items);
        out.setServices(services);
        out.setSupported(true);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("listServices() returning {} service(s) in {} ms (window={})",
                items.size(), elapsedMs, windowExpr);
        return out;
    }

    /**
     * The window listServices counts over: an explicit {@code startTime}/{@code endTime}
     * when given, otherwise {@code window} (default 24 hours) ending now.
     */
    private static ListServicesWindow resolveListServicesWindow(String window, String startTime,
            String endTime) {
        // Default window is 24 hours.
        //
        // It used to be 7 days, on the reasoning that a wider window surfaces
        // low-volume services. In practice OpenObserve cannot serve a 7d scan of
        // this stream: it answers HTTP 200 with `hits: []` plus a function_error
        // of {"code":20006,"message":"Search parquet file not found"}. With no
        // exception raised, this method returned an EMPTY list and still set
        // supported=true — which reads as "there are no services" rather than
        // "the query failed". The UI then fell back to the heartbeat inventory
        // and offered 18 services when 106 were actually logging.
        //
        // 24h is what OpenObserveClientImpl.discoverServices() has always used
        // for the same job, and it returns the full set. Callers may still pass
        // an explicit window; parseHits now logs the function_error so this
        // class of silent-empty failure is visible rather than invisible.
        String windowExpr = (window == null || window.trim().isEmpty())
                ? "now-24h"
                : window.trim();

        // An explicit range wins over `window`, so the caller can ask about
        // exactly the window its table is showing and get counts that match.
        boolean hasExplicit = startTime != null && !startTime.trim().isEmpty();
        long endMicros = resolveListServicesEndMicros(endTime);
        long startMicros = TimeRangeParser.toMicros(hasExplicit ? startTime.trim() : windowExpr, endMicros);
        if (hasExplicit) windowExpr = startTime.trim() + " → " + (endTime == null ? "now" : endTime.trim());
        return new ListServicesWindow(startMicros, endMicros, windowExpr);
    }

    /** The listServices window bounds plus the label logged for it. */
    private record ListServicesWindow(long startMicros, long endMicros, String label) {
    }

    /** End of the listServices window: the explicit endTime when given, otherwise now. */
    private static long resolveListServicesEndMicros(String endTime) {
        return (endTime != null && !endTime.trim().isEmpty())
                ? TimeRangeParser.toMicros(endTime.trim(), TimeRangeParser.defaultEndMicros())
                : TimeRangeParser.defaultEndMicros();
    }

    /**
     * De-duplicate on the EXACT name. Case is deliberately significant:
     * this deployment really does run both "IoTOpsApiSvc" and
     * "iotopsapisvc" as separate services, and folding them together
     * would silently hide one behind the other.
     */
    private static Map<String, Long> countLogsByServiceName(List<Map<String, Object>> hits) {
        Map<String, Long> byName = new LinkedHashMap<>();
        for (Map<String, Object> h : hits) {
            Object name = h.get(COL_SERVICE_NAME);
            if (!(name instanceof String s) || s.isEmpty()) continue;
            Object c = h.get("log_count");
            long n = c instanceof Number num ? num.longValue() : 0L;
            byName.merge(s, n, Long::sum);
        }
        return byName;
    }

    /**
     * Single GROUP BY query against the logs stream, then pivot in Java to
     * produce total count, per-severity counts (severity coalesced with
     * level, since the OTel logs schema uses both), and top-5 error services.
     */
    private void aggregateLogsSummary(LogsSummaryResponseDTO out,
            long startMicros, long endMicros) {
        String safeStream = SqlSafety.validateIdentifier(openObserveConfig.getLogsStream());
        String sql = "SELECT severity AS sev, "
                + "service_name, "
                + "COUNT(*) AS n "
                + SQL_FROM_QUOTED + safeStream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                + SQL_AND_TS_LTE + endMicros + " "
                // Inside the WHERE, not applied to the result: the summary's totals
                // and per-severity counts are computed by OpenObserve, so dropping
                // rows afterwards would leave the counts describing a row set the
                // caller never sees.
                + catalogFilterClause("logs")
                + "GROUP BY 1, 2";

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve logs-summary SQL: {}", sql);
        }

        List<Map<String, Object>> hits;
        try {
            // Size cap of 5000 (severity × service permutations) is far above
            // any realistic deployment but defends against schema corruption.
            hits = openObserveClientService.search(sql, startMicros, endMicros, 0, 5000, "logs");
        } catch (Exception e) {
            log.error("Logs summary query failed: {}", e.getMessage(), e);
            out.setTotalCount(0L);
            out.setCountsBySeverity(Collections.emptyMap());
            out.setTopErrorServices(Collections.emptyList());
            return;
        }

        Map<String, Long> bySev = newSeverityBuckets();
        Map<String, Long> errorBySvc = new HashMap<>();
        long total = 0L;

        for (Map<String, Object> hit : hits) {
            Number n = (Number) hit.get("n");
            long count = n == null ? 0L : n.longValue();
            total += count;

            String sev = canonicalSeverity((String) hit.get("sev"));
            bySev.merge(sev, count, Long::sum);

            addErrorServiceCount(errorBySvc, hit, sev, count);
        }

        out.setTotalCount(total);
        out.setCountsBySeverity(bySev);
        out.setTopErrorServices(buildTopErrorList(errorBySvc, 5));
    }

    /**
     * Initialise canonical severity buckets so the UI can render zero rows
     * without special-casing "missing key vs zero".
     */
    private static Map<String, Long> newSeverityBuckets() {
        Map<String, Long> bySev = new LinkedHashMap<>();
        for (String k : new String[] { SEV_ERROR, SEV_WARN, SEV_INFO, SEV_DEBUG, SEV_TRACE, SEV_FATAL }) {
            bySev.put(k, 0L);
        }
        return bySev;
    }

    /** Adds an ERROR/FATAL row's count to its (non-empty) service's error total. */
    private static void addErrorServiceCount(Map<String, Long> errorBySvc, Map<String, Object> hit,
            String sev, long count) {
        if (SEV_ERROR.equals(sev) || SEV_FATAL.equals(sev)) {
            String svc = (String) hit.get(COL_SERVICE_NAME);
            if (svc != null && !svc.isEmpty()) {
                errorBySvc.merge(svc, count, Long::sum);
            }
        }
    }

    private static String canonicalSeverity(String raw) {
        if (raw == null || raw.isEmpty())
            return "UNKNOWN";
        String upper = raw.trim().toUpperCase();
        return CANONICAL_SEVERITIES.contains(upper) ? upper : "UNKNOWN";
    }

    private static List<LogsSummaryResponseDTO.TopErrorService> buildTopErrorList(
            Map<String, Long> errorBySvc, int limit) {
        return errorBySvc.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new LogsSummaryResponseDTO.TopErrorService(e.getKey(), e.getValue()))
                .toList();
    }

    // -----------------------------------------------------------------------------------
    // OpenObserve path (Phase D — D8(a) feature flag)
    // -----------------------------------------------------------------------------------

    private List<LogResponseDTO> getLogsViaOpenObserve(LogRequestDTO request) {
        List<String> services = resolveSelectedServices(request);
        log.info("Fetching logs via OpenObserve: services={}, severity={}, traceId={}, page={}, size={}",
                services, request.getSeverity(), request.getTraceId(),
                request.getPage(), request.getSize());

        long startMicros = TimeRangeParser.toMicros(request.getStartTime(), TimeRangeParser.defaultStartMicros());
        long endMicros = TimeRangeParser.toMicros(request.getEndTime(), TimeRangeParser.defaultEndMicros());
        log.trace("Resolved time range: startMicros={}, endMicros={}", startMicros, endMicros);

        int from = request.getPage() * request.getSize();
        // Fetch one extra row so the upstream getLogs() can derive hasMore (issue #8).
        int fetchSize = request.getSize() + 1;

        // Phase 1 (issue #3 + #4): single SQL query for both ALL-services and
        // specific-service paths. OpenObserve handles pagination natively
        // (LIMIT/OFFSET), so we fetch exactly `size` rows regardless of page.
        //
        // Multi-service (cross-service correlation): when two or more services
        // are selected we want logs whose trace_id touches EVERY selected
        // service — service_name IN (selected) AND trace_id IN (qualifying
        // traces). Resolve the qualifying trace set first, then run the main
        // logs query restricted to it. A two-step is unavoidable because
        // GROUP-BY-trace can't co-exist with the row-shaped logs SELECT.
        // Default is now ANY: `service_name IN (selected)`, i.e. "show me logs
        // from these services". That is what picking several boxes in a filter
        // list means everywhere else in the industry, and what users expect.
        //
        // The previous default was ALL — logs whose trace_id touches EVERY
        // selected service. That is a genuinely useful cross-service
        // correlation view, but as a *default* it reads as broken: selecting
        // two ordinary services almost always yields an empty table, because
        // few traces happen to span exactly that pair.
        //
        // The behaviour is kept, not deleted — pass `serviceMatch=ALL` to get
        // it back. Nothing that relied on it has been removed.
        List<String> qualifyingTraces = null;
        if (services.size() >= 2 && request.isServiceMatchAll()) {
            qualifyingTraces = findTracesCrossingAllServices(services, startMicros, endMicros);
            if (qualifyingTraces.isEmpty()) {
                log.info("Multi-service ALL filter matched zero qualifying traces for {}", services);
                return Collections.emptyList();
            }
        }

        return queryLogsCore(services, qualifyingTraces, request, startMicros, endMicros, from, fetchSize);
    }

    /**
     * Step 1 of the multi-service filter: return trace IDs whose distinct
     * service set ⊇ the selected set, inside the requested time window.
     *
     * <p>
     * Returns at most {@link #MAX_QUALIFYING_TRACES} IDs — beyond that the
     * downstream {@code trace_id IN (...)} clause becomes prohibitive. The
     * upstream view is "show me logs that cross these services", so capping
     * at recent activity is the right user-visible degradation.
     * </p>
     */
    private List<String> findTracesCrossingAllServices(List<String> services,
            long startMicros, long endMicros) {
        String safeStream = SqlSafety.validateIdentifier(openObserveConfig.getLogsStream());
        String inList = services.stream()
                .map(s -> "'" + SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(s)) + "'")
                .collect(Collectors.joining(", "));

        String sql = "SELECT trace_id "
                + SQL_FROM_QUOTED + safeStream + "\" "
                + "WHERE trace_id IS NOT NULL AND trace_id != '' "
                + "AND service_name IN (" + inList + ") "
                + SQL_AND_TS_GTE + startMicros + " "
                + SQL_AND_TS_LTE + endMicros + " "
                // Same scoping as the main logs query, so the HAVING COUNT(DISTINCT
                // service_name) below counts the same services the row query will.
                + catalogFilterClause("logs")
                + "GROUP BY trace_id "
                + "HAVING COUNT(DISTINCT service_name) = " + services.size() + " "
                + "ORDER BY MAX(_timestamp) DESC";

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve qualifying-traces SQL: {}", sql);
        }

        List<Map<String, Object>> hits = openObserveClientService.search(
                sql, startMicros, endMicros, 0, MAX_QUALIFYING_TRACES, "logs");

        return hits.stream()
                .map(h -> (String) h.get(COL_TRACE_ID))
                .filter(t -> t != null && !t.isEmpty())
                .distinct()
                .toList();
    }

    private static final int MAX_QUALIFYING_TRACES = 2000;

    /**
     * Normalise the request's service filter into a list of distinct, non-blank
     * names. An empty/ALL/blank value collapses to an empty list — the
     * "no service filter" case the rest of the code treats as ALL services.
     */
    private static List<String> resolveSelectedServices(LogRequestDTO request) {
        LinkedHashMap<String, String> uniq = new LinkedHashMap<>();
        List<String> raw = request.getServiceNames();
        if (raw != null) {
            for (String s : raw)
                addServiceIfMeaningful(s, uniq);
        }
        // Legacy single-service field — already mirrored into serviceNames by
        // setServiceName, but guard against direct field writes in tests.
        addServiceIfMeaningful(request.getServiceName(), uniq);
        return new ArrayList<>(uniq.values());
    }

    private static void addServiceIfMeaningful(String s, LinkedHashMap<String, String> uniq) {
        if (s == null)
            return;
        String t = s.trim();
        if (t.isEmpty() || "ALL".equalsIgnoreCase(t))
            return;
        uniq.putIfAbsent(t.toLowerCase(), t);
    }

    private List<LogResponseDTO> queryLogsCore(
            List<String> servicesOrEmpty, List<String> qualifyingTraceIdsOrNull,
            LogRequestDTO request,
            long startMicros, long endMicros, int from, int size) {

        // Which stream, and can the fixed projection below read it? For the
        // configured default this is a constant-time yes and the SQL that follows is
        // byte-for-byte what it always was. Anything else is checked, and a stream
        // without severity/service_name/body goes to the adaptive reader instead of
        // failing with `unknown field`.
        String stream = resolveLogsStream(request);
        if (!isOtelLogsShaped(stream)) {
            return queryForeignStream(stream, request, startMicros, endMicros, from, size);
        }

        String safeStream = SqlSafety.validateIdentifier(stream);

        StringBuilder sql = new StringBuilder()
                .append("SELECT _timestamp, trace_id, span_id, service_name, severity, ")
                .append("body, host_name, os_type, instrumentation_library_name, ")
                // Code attributes — populated by the OTel Java agent when
                // -Dotel.instrumentation.log4j-appender.experimental.capture-code-attributes=true
                // is set. Surfaced via LogNormalizer.extractCodeMetadataOo so the
                // UI shows real class.method:line instead of "unknown".
                .append("code_namespace, code_function, code_lineno, code_filepath, ")
                // Exception attributes — populated by the OTel logback/log4j
                // appender's throwable capture (verified present in the OO logs
                // stream). type/message are short and returned for every row.
                // The stacktrace is ~19 KB/row and present across ALL severities
                // (TRACE/INFO included), so returning it everywhere would bloat
                // the response. Gate it to error-class rows via CASE so OO never
                // ships the big column for non-error logs. Surfaced raw
                // (secret-masked, untruncated) so the UI can show the full trace.
                .append("exception_type, exception_message, ")
                .append("CASE WHEN severity IN ('ERROR', 'FATAL', 'SEVERE') ")
                .append("THEN exception_stacktrace ELSE NULL END AS exception_stacktrace ")
                .append(SQL_FROM_QUOTED).append(safeStream).append("\" ")
                // 1=1 lets every subsequent filter use AND uniformly, regardless
                // of whether a service filter is present (ALL-services case omits it).
                .append("WHERE 1=1 ");

        if (servicesOrEmpty != null && !servicesOrEmpty.isEmpty()) {
            String inList = servicesOrEmpty.stream()
                    .map(s -> "'" + SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(s)) + "'")
                    .collect(Collectors.joining(", "));
            // Single-service stays semantically equivalent to `= 'X'`; using IN
            // keeps the branch uniform and avoids dual code paths.
            sql.append("AND service_name IN (").append(inList).append(") ");
        }

        // Multi-service "trace-grain" restriction: the caller has already
        // resolved the set of trace IDs whose service set ⊇ the selected
        // services. Limiting log rows to that set is what turns the OR-shaped
        // service-name list filter into the AND-at-trace-grain the user asked
        // for: e.g. "only logs from traces crossing both services".
        if (qualifyingTraceIdsOrNull != null && !qualifyingTraceIdsOrNull.isEmpty()) {
            String traceInList = qualifyingTraceIdsOrNull.stream()
                    .map(t -> "'" + SqlSafety.escapeStringLiteral(t) + "'")
                    .collect(Collectors.joining(", "));
            sql.append("AND trace_id IN (").append(traceInList).append(") ");
        }

        // Severity: bind the same value to BOTH severity AND level columns (Z2 fix).
        // When severityMode=AT_LEAST, expand to all severities ≥ the requested level
        // via SeverityRank (issue #14). The expanded values are whitelisted, so the
        // SQL builder still escapes them defensively even though they're known-safe.
        appendSeverityFilter(sql, request);

        appendOptionalEqFilter(sql, COL_HOST_NAME, request.getHostName());
        appendOptionalEqFilter(sql, COL_OS_TYPE, request.getOsType());
        appendOptionalEqFilter(sql, COL_SCOPE_NAME, request.getScopeName());
        appendOptionalEqFilter(sql, COL_TRACE_ID, request.getTraceId());

        // Phase 3 (issue #10): match across the same columns the UI used to
        // re-filter client-side, so a single term can hit body, severity,
        // service, trace ids, scope, and OTel code attributes.
        appendLogsSearchFilter(sql, request.getSearch());

        sql.append(SQL_AND_TS_GTE).append(startMicros).append(' ');
        sql.append(SQL_AND_TS_LTE).append(endMicros).append(' ');
        // Product Catalog scoping, before ORDER BY / pagination — OpenObserve
        // paginates server-side, so excluding rows afterwards would produce short
        // pages and a hasMore that lies.
        appendCatalogFilter(sql, "logs");
        sql.append(SortSpec.parseLogs(request.getSort()).toSqlClause());

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve logs SQL (services={}): {}",
                    servicesLogLabel(servicesOrEmpty), sql);
        }

        List<Map<String, Object>> hits = openObserveClientService.search(
                sql.toString(), startMicros, endMicros, from, size, "logs");

        log.debug("OpenObserve logs returned {} hit(s) for services={}",
                hits.size(), servicesLogLabel(servicesOrEmpty));

        return hits.stream()
                .map(this::mapOpenObserveHitToDTO)
                .filter(dto -> dto != null && dto.getMessage() != null)
                .toList();
    }

    /** "ALL" when no service filter is present, otherwise the list itself (for logging). */
    private static Object servicesLogLabel(List<String> servicesOrEmpty) {
        return (servicesOrEmpty == null || servicesOrEmpty.isEmpty()) ? "ALL" : servicesOrEmpty;
    }

    /**
     * Severity: bind the requested value, exact or AT_LEAST per
     * request.severityMode (issue #14). No-op for blank / ALL.
     */
    private static void appendSeverityFilter(StringBuilder sql, LogRequestDTO request) {
        String severity = request.getSeverity();
        if (severity != null && !severity.trim().isEmpty()
                && !"ALL".equalsIgnoreCase(severity.trim())) {
            boolean atLeast = "AT_LEAST".equalsIgnoreCase(request.getSeverityMode());
            List<String> levels = atLeast
                    ? SeverityRank.atLeast(severity)
                    : SeverityRank.exact(severity);
            String inList = levels.stream()
                    .map(s -> "'" + SqlSafety.escapeStringLiteral(s) + "'")
                    .collect(Collectors.joining(", "));
            sql.append("AND severity IN (").append(inList).append(") ");
        }
    }

    /** Free-text LIKE across the logs columns queryLogsCore projects. No-op for blank. */
    private static void appendLogsSearchFilter(StringBuilder sql, String search) {
        if (search == null || search.trim().isEmpty())
            return;
        String escaped = SqlSafety.escapeLikePattern(search.trim());
        String like = SQL_LIKE_PREFIX + escaped + SQL_LIKE_ESCAPE_SUFFIX;
        sql.append("AND (")
                .append("body ").append(like)
                .append(" OR service_name ").append(like)
                .append(" OR trace_id ").append(like)
                .append(" OR span_id ").append(like)
                .append(" OR severity ").append(like)
                .append(" OR code_namespace ").append(like)
                .append(" OR code_function ").append(like)
                .append(" OR instrumentation_library_name ").append(like)
                // exception_type/message are short; including them lets users
                // search by exception class. exception_stacktrace is left out
                // of LIKE deliberately — it's ~19 KB/row and scanning it would
                // be expensive.
                .append(" OR exception_type ").append(like)
                .append(" OR exception_message ").append(like)
                .append(") ");
    }

    /**
     * Issue #2: server-side trace grouping. {@code GROUP BY trace_id} so the
     * counts reflect the full filter window, not just the page of logs the
     * UI happens to have loaded. Pagination here is by trace count, not log
     * count — page N is the (N+1)th batch of {@code size} traces ordered by
     * latest activity.
     */
    private LogTraceSummaryPageDTO getTraceGroupsViaOpenObserve(LogRequestDTO request) {
        long startMicros = TimeRangeParser.toMicros(request.getStartTime(), TimeRangeParser.defaultStartMicros());
        long endMicros = TimeRangeParser.toMicros(request.getEndTime(), TimeRangeParser.defaultEndMicros());

        // This query is GROUP BY trace_id with a severity ladder in a CASE. A stream
        // carrying neither cannot be grouped by trace, so it returns nothing rather
        // than an `unknown field` error: the view mode simply has no groups to show
        // for that stream, which is the truth.
        String stream = resolveLogsStream(request);
        if (!isOtelLogsShaped(stream)) {
            log.debug("Trace grouping unavailable for stream '{}' (not OTel logs-shaped)", stream);
            return new LogTraceSummaryPageDTO();
        }

        String safeStream = SqlSafety.validateIdentifier(stream);

        // Build the search predicate up-front so we can use it both as a
        // FILTER modifier on the matching count AND as a HAVING clause.
        // Keeping it OUT of the outer WHERE means the other aggregates
        // (total count, services, top severity, latest_ts) reflect the WHOLE
        // trace within the non-search filters — so the UI can render
        // "5 matching of 190 logs" without lying about either side.
        String search = request.getSearch();
        boolean hasSearch = search != null && !search.trim().isEmpty();
        String searchPredicate = hasSearch ? traceGroupSearchPredicate(search) : "";

        StringBuilder sql = new StringBuilder()
                .append("SELECT trace_id, ");

        if (hasSearch) {
            // FILTER (WHERE …) is standard SQL aggregate-filter syntax,
            // supported by DataFusion (OpenObserve's engine).
            sql.append("COUNT(*) FILTER (WHERE ").append(searchPredicate).append(") AS log_count, ");
        } else {
            sql.append("COUNT(*) AS log_count, ");
        }
        sql.append("COUNT(*) AS total_log_count, ")
                .append("MAX(_timestamp) AS latest_ts, ")
                .append("MIN(_timestamp) AS earliest_ts, ")
                // Distinct services that emitted any log in this trace. The UI
                // renders these comma-joined in the group header so the user can
                // see at a glance whether a trace crossed service boundaries.
                // DataFusion (OpenObserve's SQL engine) supports `array_agg(DISTINCT …)`.
                .append("array_agg(DISTINCT service_name) AS services, ")
                // CASE-rank trick: a single MAX() gets us the worst severity per
                // trace without needing window functions or a sub-aggregation.
                // The rank values are arbitrary — just monotonic in OTel order.
                .append("MAX(CASE ")
                .append("WHEN severity = 'FATAL' THEN 5 ")
                .append("WHEN severity = 'ERROR' THEN 4 ")
                .append("WHEN severity = 'WARN'  THEN 3 ")
                .append("WHEN severity = 'INFO'  THEN 2 ")
                .append("WHEN severity = 'DEBUG' THEN 1 ")
                .append("WHEN severity = 'TRACE' THEN 0 ")
                .append("ELSE 2 ")
                .append("END) AS top_rank ")
                .append(SQL_FROM_QUOTED).append(safeStream).append("\" ")
                // Skip rows with no trace_id — they can't appear in a "by trace"
                // view. Also gives the GROUP BY a clean key.
                .append("WHERE trace_id IS NOT NULL AND trace_id != '' ");

        // Multi-service (issue: cross-service log filtering). Apply the
        // selection as an OR-shaped WHERE; the AND-at-trace-grain is enforced
        // below by HAVING COUNT(DISTINCT service_name) = N. For the single
        // service case this is functionally identical to `=` and saves a
        // separate code path.
        List<String> services = resolveSelectedServices(request);
        if (!services.isEmpty()) {
            String inList = services.stream()
                    .map(s -> "'" + SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(s)) + "'")
                    .collect(Collectors.joining(", "));
            sql.append("AND service_name IN (").append(inList).append(") ");
        }

        // Same severity filter shape as queryLogsCore — exact or AT_LEAST per
        // request.severityMode (issue #14).
        appendSeverityFilter(sql, request);

        appendOptionalEqFilter(sql, COL_HOST_NAME, request.getHostName());
        appendOptionalEqFilter(sql, COL_OS_TYPE, request.getOsType());
        appendOptionalEqFilter(sql, COL_SCOPE_NAME, request.getScopeName());

        // Search predicate is NOT in the outer WHERE — it's applied via
        // FILTER on log_count (above) and HAVING below. This way the other
        // aggregates summarize the whole trace within the non-search filters.

        sql.append(SQL_AND_TS_GTE).append(startMicros).append(' ');
        sql.append(SQL_AND_TS_LTE).append(endMicros).append(' ');
        // Before the GROUP BY, so a disabled resource's rows are excluded from the
        // per-trace aggregates too — otherwise its logs would still be counted in
        // "190 logs" and still set the trace's worst severity.
        appendCatalogFilter(sql, "logs");
        sql.append("GROUP BY trace_id ");

        // Compose HAVING clauses. Two independent constraints can fire:
        // 1) search predicate (only traces with at least one matching log)
        // 2) cross-service constraint (only traces whose distinct service
        // set ⊇ the selected services, i.e. N distinct services within
        // the IN-filtered rows).
        // Both join with AND; either may be absent.
        appendTraceGroupHaving(sql, hasSearch, searchPredicate, services.size());

        // Repeat the MAX() in ORDER BY — DataFusion doesn't always honour
        // alias references in ORDER BY across all versions, but the same
        // expression is always safe.
        sql.append("ORDER BY MAX(_timestamp) DESC");

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve trace-groups SQL: {}", sql);
        }

        int from = request.getPage() * request.getSize();
        // size+1 to derive hasMore (same trick as getLogs, issue #8).
        int fetchSize = request.getSize() + 1;

        List<Map<String, Object>> hits = openObserveClientService.search(
                sql.toString(), startMicros, endMicros, from, fetchSize, "logs");

        List<LogTraceSummaryDTO> all = hits.stream()
                .map(this::mapTraceGroupHit)
                .filter(dto -> dto != null && dto.getTraceId() != null && !dto.getTraceId().isEmpty())
                .toList();

        boolean hasMore = all.size() > request.getSize();
        List<LogTraceSummaryDTO> items = hasMore
                ? new ArrayList<>(all.subList(0, request.getSize()))
                : all;

        // Anchor the enrichment window on the page's own data instead of the
        // request window. earliest_ts (MIN(_timestamp) per trace) already comes
        // back from the group-by above, so this costs no extra query. Read it
        // off the raw hits — mapTraceGroupHit stringifies it to ISO, and we
        // need the micros. Restricted to the page's traces because `hits` may
        // carry one extra row (the size+1 hasMore probe).
        Set<String> pageTraceIds = items.stream()
                .map(LogTraceSummaryDTO::getTraceId)
                .collect(Collectors.toSet());
        long earliestGroupTs = hits.stream()
                .filter(h -> pageTraceIds.contains(h.get(COL_TRACE_ID)))
                .map(h -> (Number) h.get("earliest_ts"))
                .filter(Objects::nonNull)
                .mapToLong(Number::longValue)
                .min()
                .orElse(startMicros);

        // Enrich the page with each trace's entry operation (root span name).
        // One extra query, batched on trace_id — bounded by page size, ~50.
        enrichWithEntryOperations(items, earliestGroupTs, endMicros);

        return new LogTraceSummaryPageDTO(items, hasMore, null);
    }

    /** LIKE predicate over the trace-group columns, used both in FILTER and HAVING. */
    private static String traceGroupSearchPredicate(String search) {
        String escaped = SqlSafety.escapeLikePattern(search.trim());
        String like = SQL_LIKE_PREFIX + escaped + SQL_LIKE_ESCAPE_SUFFIX;
        return "(body " + like
                + " OR service_name " + like
                + " OR trace_id " + like
                + " OR severity " + like
                + " OR code_namespace " + like
                + " OR code_function " + like
                + " OR instrumentation_library_name " + like
                + ")";
    }

    /** HAVING for the trace-group query: search match and/or cross-service constraint. */
    private static void appendTraceGroupHaving(StringBuilder sql, boolean hasSearch,
            String searchPredicate, int serviceCount) {
        List<String> havingClauses = new ArrayList<>();
        if (hasSearch) {
            havingClauses.add("COUNT(*) FILTER (WHERE " + searchPredicate + ") > 0");
        }
        if (serviceCount >= 2) {
            havingClauses.add("COUNT(DISTINCT service_name) = " + serviceCount);
        }
        if (!havingClauses.isEmpty()) {
            sql.append("HAVING ").append(String.join(" AND ", havingClauses)).append(' ');
        }
    }

    /**
     * How far before a trace group's earliest log the entry-span lookup starts.
     * Sized to absorb clock skew between services, not to catch long-running
     * traces — see {@link #enrichWithEntryOperations}.
     */
    private static final long SAFETY_MARGIN_MICROS = 15L * 60L * 1_000_000L;

    /**
     * Fill in {@link LogTraceSummaryDTO#getEntryOperation()} for the visible
     * page by looking up each trace's entry span in the traces stream. The
     * resulting label is what gets surfaced in the grouped LogsView so the
     * user can identify a trace by what it was actually doing (e.g.
     * "GET /IoTOpsService/alarmType/getAllAlarmIndexDataOfCompany") rather
     * than by its hex ID alone.
     *
     * <p>
     * "Entry" is resolved with a three-tier preference:
     * <ol>
     * <li>{@code span_kind = 'SERVER'} — an incoming HTTP/RPC handler.
     * This is the most semantically meaningful "what was this trace
     * doing" answer.</li>
     * <li>Parent-less span (root by trace topology) — covers non-server
     * entry points like message consumers or batch jobs.</li>
     * <li>Earliest span overall — last-resort best effort when neither
     * marker is set (partial data, agent missing span_kind).</li>
     * </ol>
     * </p>
     *
     * <p>
     * Time-window note: OpenObserve passes {@code start_time}/{@code end_time}
     * into the HTTP body as separate storage-layer bounds — they decide which
     * Parquet partitions to scan <em>before</em> SQL runs, so the highly
     * selective {@code trace_id IN (...)} filter does nothing to make a wide
     * window cheap. The window is therefore anchored on {@code earliestGroupTs}
     * — the minimum {@code MIN(_timestamp)} across the page's traces, already
     * computed by the group-by — minus {@link #SAFETY_MARGIN_MICROS}, rather
     * than on the request window.
     * </p>
     *
     * <p>
     * The margin covers clock skew between the service that emitted the log
     * and the one that emitted the root span. It does <strong>not</strong>
     * cover a trace whose root span started more than the margin before that
     * trace's first log line (a long-running trace that stays silent for a
     * while after entry) — such a trace's entryOperation will now come back
     * null where the previous 24 h buffer would have found it. That trade is
     * deliberate: the wide window cost ~20-44 s per request, and the two
     * windows returned identical results on the measured data.
     * </p>
     *
     * <p>
     * Failures are intentionally non-fatal — if the traces stream is
     * missing, gated by a separate org, or just empty, the groups still
     * render; entryOperation stays null and the UI omits the slot.
     * </p>
     */
    private void enrichWithEntryOperations(List<LogTraceSummaryDTO> items, long earliestGroupTs, long endMicros) {
        if (items == null || items.isEmpty())
            return;
        String tracesStream = openObserveConfig.getTracesStream();
        if (tracesStream == null || tracesStream.isBlank())
            return;

        // Build a deduplicated, SQL-escaped IN-list from the page's trace IDs.
        List<String> traceIds = distinctTraceIds(items);
        if (traceIds.isEmpty())
            return;

        String inList = traceIds.stream()
                .map(id -> "'" + SqlSafety.escapeStringLiteral(id) + "'")
                .collect(Collectors.joining(", "));

        String safeStream = SqlSafety.validateIdentifier(tracesStream);

        // Anchor on the earliest log actually seen in this page's traces rather
        // than on the request window, then step back by a small safety margin.
        // Clamp at 0 to avoid underflow (effectively impossible in practice,
        // but the SQL would be ill-formed if we ever produced a negative
        // literal).
        long enrichStart = Math.max(0L, earliestGroupTs - SAFETY_MARGIN_MICROS);

        // Pull every candidate row for the page's traces. We include span_kind
        // and reference_parent_span_id so Java-side we can apply the
        // SERVER → root → earliest preference per trace.
        String sql = "SELECT trace_id, operation_name, span_kind, "
                + "reference_parent_span_id, _timestamp "
                + SQL_FROM_QUOTED + safeStream + "\" "
                + "WHERE trace_id IN (" + inList + ") "
                // Traces signal, not logs: this reads the traces stream. Without it
                // a disabled service could still supply the entry operation label
                // shown against a trace.
                + catalogFilterClause("traces")
                + "ORDER BY _timestamp ASC";

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve trace-group entry-op SQL: {}", sql);
        }

        // Bound the result size so a pathological trace with thousands of
        // spans doesn't blow up the response. 32 candidates per trace is
        // ample headroom — the actual selection criteria narrow this to
        // a handful in nearly every case.
        int cap = traceIds.size() * 32;

        List<Map<String, Object>> hits;
        try {
            hits = openObserveClientService.search(sql, enrichStart, endMicros, 0, cap, "traces");
        } catch (Exception e) {
            log.debug("Trace entry-op enrichment skipped: {}", e.getMessage());
            return;
        }

        applyEntryOperations(items, selectEntryOperations(hits));
    }

    /** The page's non-empty trace IDs, de-duplicated in first-seen order. */
    private static List<String> distinctTraceIds(List<LogTraceSummaryDTO> items) {
        return items.stream()
                .map(LogTraceSummaryDTO::getTraceId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .toList();
    }

    /** Sets each item's entry operation where one was found for its trace. */
    private static void applyEntryOperations(List<LogTraceSummaryDTO> items, Map<String, String> opByTrace) {
        for (LogTraceSummaryDTO dto : items) {
            String op = opByTrace.get(dto.getTraceId());
            if (op != null)
                dto.setEntryOperation(op);
        }
    }

    /**
     * Per-trace three-tier selection. Pre-order ASC means the first row
     * we see for each tier is the earliest one — exactly what we want.
     * Track which tier each trace is currently filled at so a later
     * higher-tier candidate can upgrade it (e.g. a SERVER span seen
     * after we already picked a parent-less candidate wins).
     */
    private static Map<String, String> selectEntryOperations(List<Map<String, Object>> hits) {
        Map<String, String> opByTrace = new HashMap<>();
        Map<String, Integer> tierByTrace = new HashMap<>();

        for (Map<String, Object> hit : hits) {
            String tid = (String) hit.get(COL_TRACE_ID);
            String op = (String) hit.get("operation_name");
            if (tid == null || tid.isEmpty() || op == null || op.isEmpty())
                continue;

            int candidateTier = entrySpanTier(hit);
            Integer currentTier = tierByTrace.get(tid);
            if (currentTier == null || candidateTier < currentTier) {
                opByTrace.put(tid, op);
                tierByTrace.put(tid, candidateTier);
            }
        }
        return opByTrace;
    }

    /** Tier ranks: 0=SERVER, 1=root (parent-less), 2=earliest fallback. */
    private static int entrySpanTier(Map<String, Object> hit) {
        final int TIER_SERVER = 0;
        final int TIER_ROOT = 1;
        final int TIER_ANY = 2;
        String kind = String.valueOf(hit.get("span_kind"));
        String parent = (String) hit.get("reference_parent_span_id");
        boolean isServer = "SERVER".equalsIgnoreCase(kind);
        boolean isRoot = (parent == null || parent.isEmpty());
        if (isServer) {
            return TIER_SERVER;
        }
        return isRoot ? TIER_ROOT : TIER_ANY;
    }

    private LogTraceSummaryDTO mapTraceGroupHit(Map<String, Object> hit) {
        if (hit == null)
            return null;
        LogTraceSummaryDTO dto = new LogTraceSummaryDTO();
        dto.setTraceId((String) hit.get(COL_TRACE_ID));

        Number cnt = (Number) hit.get("log_count");
        dto.setLogCount(cnt == null ? 0L : cnt.longValue());

        // total_log_count comes from the same query (ungrouped COUNT(*)).
        // When no search is active, it's identical to log_count — the UI
        // detects equality and renders the simpler "X logs" form.
        Number total = (Number) hit.get("total_log_count");
        dto.setTotalLogCount(total == null ? dto.getLogCount() : total.longValue());

        Number latest = (Number) hit.get("latest_ts");
        if (latest != null)
            dto.setLatestTimestamp(TimeFormatter.microsToIso(latest.longValue()));

        Number earliest = (Number) hit.get("earliest_ts");
        if (earliest != null)
            dto.setEarliestTimestamp(TimeFormatter.microsToIso(earliest.longValue()));

        Number rank = (Number) hit.get("top_rank");
        dto.setTopSeverity(severityFromRank(rank == null ? 2 : rank.intValue()));

        // services comes back as an array via array_agg(DISTINCT service_name).
        // OpenObserve serialises arrays as JSON arrays, which the client
        // deserialises into either a List<?> or a String depending on
        // configuration. Handle both shapes defensively.
        dto.setServices(extractServicesList(hit.get("services")));
        return dto;
    }

    /**
     * Convert whatever the OpenObserve client returned for the {@code services}
     * column into a sorted List&lt;String&gt;. Defensive against:
     * - null (no service column at all)
     * - List&lt;?&gt; (the typical Jackson decoding of a JSON array)
     * - Object[]
     * - String like "[svc-a, svc-b]" (some serialisers fall back to a string repr)
     * Returns an empty list rather than null so the UI can render unconditionally.
     */
    private static List<String> extractServicesList(Object raw) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object s : list) {
                addTrimmedService(s, out);
            }
        } else if (raw instanceof Object[] array) {
            for (Object s : array) {
                addTrimmedService(s, out);
            }
        } else if (raw instanceof String str) {
            addStringifiedServices(str, out);
        }
        List<String> list = new ArrayList<>(out);
        java.util.Collections.sort(list);
        return list;
    }

    /** Adds {@code s.toString().trim()} to {@code out} when non-null and non-empty. */
    private static void addTrimmedService(Object s, java.util.Set<String> out) {
        if (s != null) {
            String v = s.toString().trim();
            if (!v.isEmpty())
                out.add(v);
        }
    }

    /** Stringified array fallback: "[svc-a, svc-b]" or "svc-a, svc-b". */
    private static void addStringifiedServices(String raw, java.util.Set<String> out) {
        String s = raw.trim();
        if (s.startsWith("["))
            s = s.substring(1);
        if (s.endsWith("]"))
            s = s.substring(0, s.length() - 1);
        for (String part : s.split(",")) {
            String v = SURROUNDING_QUOTE.matcher(part.trim()).replaceAll("");
            if (!v.isEmpty())
                out.add(v);
        }
    }

    private static String severityFromRank(int rank) {
        switch (rank) {
            case 5:
                return SEV_FATAL;
            case 4:
                return SEV_ERROR;
            case 3:
                return SEV_WARN;
            case 2:
                return SEV_INFO;
            case 1:
                return SEV_DEBUG;
            case 0:
                return SEV_TRACE;
            default:
                return SEV_INFO;
        }
    }

    /**
     * ES path is the legacy rollback per MIGRATION-PLAN.md. Implementing the
     * GROUP BY equivalent (terms aggregation with sub-aggs and a script for
     * top severity) is non-trivial and would only be exercised if the user
     * rolls D8(a) back. Returns empty + warning; the UI detects and shows a
     * "grouped view requires OpenObserve" hint.
     */
    private LogTraceSummaryPageDTO getTraceGroupsViaElasticsearch() {
        log.warn("Trace grouping not implemented for the Elasticsearch path; "
                + "set openobserve.enabled=true to use the grouped view.");
        return new LogTraceSummaryPageDTO(java.util.Collections.emptyList(), false, null);
    }

    private static void appendOptionalEqFilter(StringBuilder sql, String column, String value) {
        if (value == null || value.trim().isEmpty())
            return;
        sql.append("AND ").append(column).append(" = '")
                .append(SqlSafety.escapeStringLiteral(value.trim()))
                .append("' ");
    }

    // ── Which stream a request reads ─────────────────────────────────────────

    /**
     * The stream this request targets.
     *
     * <p>Blank means the configured default, which is what every caller sent before
     * the picker became real — so an unchanged client keeps reading exactly the
     * stream it always did. {@code @logs} is accepted as the same token the Product
     * Catalog probe config uses, so the two spellings do not diverge.</p>
     */
    private String resolveLogsStream(LogRequestDTO request) {
        String requested = request == null ? null : request.getStream();
        if (requested == null || requested.isBlank() || "@logs".equals(requested.trim())) {
            return openObserveConfig.getLogsStream();
        }
        return requested.trim();
    }

    /**
     * Whether a stream can be read with the fixed OTel projection.
     *
     * <p>True for the configured default WITHOUT asking OpenObserve: that stream is
     * the one this service was built around, it is on the critical path of the main
     * Logs screen, and making it depend on a discovery call would put a new failure
     * mode in front of the screen that worked yesterday.</p>
     *
     * <p>For anything else the three columns the projection cannot do without are
     * checked. {@code severity}, {@code service_name} and {@code body} are the ones
     * that appear in the SELECT list, the severity filter, the search predicate and
     * the message mapping — a stream missing any of them fails the query outright
     * with {@code unknown field}, which is what selecting {@code _rumdata} used to
     * do.</p>
     */
    private boolean isOtelLogsShaped(String stream) {
        if (stream == null || stream.equals(openObserveConfig.getLogsStream())) {
            return true;
        }
        if (streamCatalog == null) {
            // No catalogue wired (test construction). Preserve the old behaviour
            // rather than silently routing to the adaptive path.
            return true;
        }
        return streamCatalog.hasAll("logs", stream, COL_SEVERITY, COL_SERVICE_NAME, "body");
    }

    /** Columns present on a stream, or an empty set when unknown. */
    private java.util.Set<String> streamFields(String stream) {
        return streamCatalog == null ? java.util.Set.of()
                : streamCatalog.fields("logs", stream);
    }

    /**
     * Read a stream that is NOT the OTel logs schema.
     *
     * <p>{@code SELECT *} rather than a per-column projection with {@code NULL AS}
     * fillers. Both put the same thing on screen, but the star keeps every field the
     * stream actually has — which for {@code _rumdata} is the entire document, and
     * the document is the only useful thing there. A hand-built projection would
     * also need extending every time a stream gained a column.</p>
     *
     * <p>Filters are applied only where the column exists. The severity/service/
     * trace filters and the Product Catalog predicate all name OTel columns, so on a
     * foreign stream they are skipped rather than translated: a service filter that
     * silently matched nothing would look like "this stream is empty".</p>
     */
    private List<LogResponseDTO> queryForeignStream(String stream, LogRequestDTO request,
            long startMicros, long endMicros, int from, int size) {
        String safeStream = SqlSafety.validateIdentifier(stream);
        java.util.Set<String> fields = streamFields(stream);

        // No schema means we cannot establish that this stream EXISTS, and
        // `SELECT * FROM "<missing>"` is a DataFusion error that surfaces as a 502 —
        // a bad way to say "that stream is gone". Returning no rows is right for both
        // cases that land here: a stream deleted while the picker was open, and a
        // real stream that has never been written to (which has no rows anyway).
        if (fields.isEmpty()) {
            log.debug("Stream '{}' has no discoverable schema; returning no rows", safeStream);
            return List.of();
        }

        StringBuilder sql = new StringBuilder()
                .append("SELECT * FROM \"").append(safeStream).append("\" ")
                .append("WHERE _timestamp >= ").append(startMicros).append(' ')
                .append(SQL_AND_TS_LTE).append(endMicros).append(' ');

        // Free-text search over whatever text-ish columns this stream has. Built
        // from the stream's OWN field list, so it neither names a column that is
        // absent nor misses one that is present.
        String search = request.getSearch();
        if (search != null && !search.trim().isEmpty() && !fields.isEmpty()) {
            appendForeignSearchFilter(sql, search, fields);
        }

        sql.append(SortSpec.parseLogs(request.getSort()).toSqlClause());

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve foreign-stream logs SQL ({}): {}", safeStream, sql);
        }

        List<Map<String, Object>> hits = openObserveClientService.search(
                sql.toString(), startMicros, endMicros, from, size, "logs");
        log.debug("Foreign stream '{}' returned {} hit(s)", safeStream, hits.size());

        List<LogResponseDTO> out = new java.util.ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            LogResponseDTO dto = mapForeignHitToDTO(hit);
            if (dto != null) {
                out.add(dto);
            }
        }
        return out;
    }

    /**
     * {@code AND (c1 LIKE … OR c2 LIKE …)} over the text-ish columns this foreign
     * stream actually has. Appends nothing when none of them is present.
     */
    private static void appendForeignSearchFilter(StringBuilder sql, String search,
            java.util.Set<String> fields) {
        String like = SQL_LIKE_PREFIX + SqlSafety.escapeLikePattern(search.trim())
                + SQL_LIKE_ESCAPE_SUFFIX;
        List<String> candidates = new java.util.ArrayList<>();
        for (String c : List.of(COL_MESSAGE, "body", "error_message", "error_stack",
                "view_url", "resource_url", "service", COL_SERVICE_NAME, "status",
                "error_type", "type", "session_id", "http_url")) {
            if (fields.contains(c)) {
                candidates.add(c);
            }
        }
        if (!candidates.isEmpty()) {
            sql.append("AND (");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append(candidates.get(i)).append(' ').append(like);
            }
            sql.append(") ");
        }
    }

    /**
     * Map a row from a foreign stream onto the log DTO.
     *
     * <p>Separate from {@link #mapOpenObserveHitToDTO} for two reasons. That mapper
     * reads {@code body} for the message and DROPS the row when the noise gate
     * returns null — a gate tuned for OTel INFO chatter, which on a stream with no
     * {@code body} would discard every row and present a populated stream as empty.
     * And it hard-casts columns that do not exist here.</p>
     *
     * <p>The message falls back to the whole row as JSON. That is deliberate: for
     * {@code _rumdata} there is no single message field, and the document IS the
     * record. It keeps the row visible and readable in the Source view instead of
     * rendering a blank line.</p>
     */
    private LogResponseDTO mapForeignHitToDTO(Map<String, Object> hit) {
        if (hit == null || hit.isEmpty()) {
            return null;
        }
        LogResponseDTO dto = new LogResponseDTO();

        Object tsRaw = hit.get(COL_TIMESTAMP);
        String iso = (tsRaw instanceof Number n) ? TimeFormatter.microsToIso(n.longValue()) : null;
        dto.setTimestamp(iso);

        // First non-blank wins, in decreasing specificity. Nothing is invented: a
        // stream without any of these simply has no severity, and the column renders
        // empty rather than being guessed at.
        dto.setSeverity(firstText(hit, COL_SEVERITY, "level", COL_SEVERITY_TEXT, "status"));
        dto.setServiceName(firstText(hit, COL_SERVICE_NAME, "service", "application_id"));
        dto.setTraceId(firstText(hit, COL_TRACE_ID, "traceid"));
        dto.setHostName(firstText(hit, COL_HOST_NAME, "ip"));
        dto.setScopeName(firstText(hit, COL_SCOPE_NAME, "source", "o2source"));

        String message = firstText(hit, COL_MESSAGE, "body", "error_message");
        if (message == null) {
            message = compactJson(hit);
        }
        dto.setMessage(message);

        long hash = nonNegativeHash(message);
        dto.setId((iso != null ? iso : "no-ts") + "-" + hash);
        return dto;
    }

    /**
     * The magnitude of {@code s.hashCode()}, or 0 for null, for the synthetic row id.
     *
     * <p>Widened to {@code long} before taking the absolute value: {@code Math.abs}
     * of {@code Integer.MIN_VALUE} is still negative in {@code int}, which would put
     * a stray minus sign into the id. Every other hash prints exactly as before.</p>
     */
    private static long nonNegativeHash(String s) {
        if (s == null) {
            return 0L;
        }
        int h = s.hashCode();
        // Widened before abs so Integer.MIN_VALUE maps to 2147483648 rather than overflowing.
        return Math.abs((long) h);
    }

    /** The first of {@code keys} present as a non-blank string. */
    private static String firstText(Map<String, Object> hit, String... keys) {
        for (String k : keys) {
            Object v = hit.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * The row as compact JSON, for a stream with no message column.
     *
     * <p>Hand-built rather than via an ObjectMapper so this class gains no
     * dependency for one fallback string. Values are quoted with their quotes and
     * backslashes escaped, which is all that is needed to keep the result parseable
     * by the UI's Source view.</p>
     */
    private static String compactJson(Map<String, Object> hit) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : hit.entrySet()) {
            if (e.getValue() == null || COL_TIMESTAMP.equals(e.getKey())) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private LogResponseDTO mapOpenObserveHitToDTO(Map<String, Object> hit) {
        if (hit == null)
            return null;

        String severity = (String) hit.get(COL_SEVERITY);

        // _timestamp Int64 µs → ISO via TimeFormatter, with defensive Number cast (r7).
        Number tsRaw = (Number) hit.get(COL_TIMESTAMP);
        String iso = tsRaw == null ? null : TimeFormatter.microsToIso(tsRaw.longValue());

        String rawMessage = (String) hit.get("body");
        // normalizeMessage still acts as the NOISE GATE — it returns null for INFO
        // noise (cookie dumps, etc.) which we drop here. We no longer use its
        // return value as the displayed message: it truncated long bodies to 150
        // chars and applied canned cosmetic rewrites, both of which hid the real
        // log. The displayed message is now the full body, secret-masked but
        // untruncated; the UI clamps it visually.
        if (LogNormalizer.normalizeMessage(rawMessage, severity) == null)
            return null;

        LogResponseDTO dto = new LogResponseDTO();
        // ID is synthetic — OpenObserve doesn't return _id; build from timestamp + body
        // hash.
        long msgHash = nonNegativeHash(rawMessage);
        dto.setId((iso != null ? iso : "no-ts") + "-" + msgHash);
        dto.setTimestamp(iso);
        dto.setSeverity(severity);
        dto.setMessage(displayMessage(rawMessage));
        dto.setServiceName((String) hit.get(COL_SERVICE_NAME));
        dto.setHostName((String) hit.get(COL_HOST_NAME));
        dto.setOsType((String) hit.get(COL_OS_TYPE));
        dto.setScopeName((String) hit.get(COL_SCOPE_NAME));
        dto.setTraceId((String) hit.get(COL_TRACE_ID));

        // Phase F: code metadata via the OO-flavoured extractor (reads log_logger).
        LogNormalizer.extractCodeMetadataOo(hit, dto);

        // Exception attributes. The OTel logs schema DOES carry separate
        // exception_type / exception_message / exception_stacktrace columns
        // (populated by the appender's throwable capture). type/message are
        // returned for every severity; the stacktrace is gated to error-class
        // rows in the SELECT (CASE), so it's null here for non-error logs.
        // Secret-mask the stacktrace but do not truncate it — the full trace is
        // the whole point.
        dto.setExceptionType((String) hit.get("exception_type"));
        dto.setExceptionMessage((String) hit.get("exception_message"));
        String stack = (String) hit.get("exception_stacktrace");
        if (stack != null && !stack.isEmpty()) {
            dto.setStackTrace(LogNormalizer.sanitizeSensitiveData(stack));
        }

        return dto;
    }

    /** The full body, secret-masked but untruncated; a placeholder when there is none. */
    private static String displayMessage(String rawMessage) {
        return (rawMessage == null || rawMessage.isEmpty())
                ? "Unexpected error occurred in the system"
                : LogNormalizer.sanitizeSensitiveData(rawMessage);
    }

    // -----------------------------------------------------------------------------------
    // Elasticsearch path (legacy — kept for D8(a) feature flag rollback)
    // -----------------------------------------------------------------------------------

    private List<LogResponseDTO> getLogsViaElasticsearch(LogRequestDTO request) {
        log.info(
                "Fetching logs via Elasticsearch (legacy path): serviceName={}, severity={}, traceId={}, page={}, size={}",
                request.getServiceName(), request.getSeverity(), request.getTraceId(),
                request.getPage(), request.getSize());

        // Phase 1 (issue #3 + #4): single ES query for both ALL-services and
        // specific-service paths. The previous fan-out fetched per-service then
        // merged client-side, which made deeper pages slower and required a
        // separate discovery step. ES handles the union natively when we omit
        // the service.name filter — it already indexes by service via the
        // bool/should branches built below for the specific-service case.
        String svc = request.getServiceName();
        boolean allServices = isAllServices(svc);

        Map<String, String> filters = buildEsFilters(request);

        Query baseQuery = QueryBuilderUtil.buildBoolQuery(filters, request.getSearch(), request.getStartTime(),
                request.getEndTime(), AppConstants.TIMESTAMP_FIELD);
        Query withSeverity = wrapWithSeverityFilter(baseQuery, request);

        Query query = allServices ? withSeverity : withServiceFilter(withSeverity, svc);

        if (log.isDebugEnabled()) {
            log.debug("Elasticsearch logs query (service={}): {}", svc, query);
        }

        try {
            int from = request.getPage() * request.getSize();
            // Fetch one extra row so getLogs() can derive hasMore (issue #8).
            int fetchSize = request.getSize() + 1;
            SortSpec sortSpec = SortSpec.parseLogs(request.getSort());
            SortOrder esOrder = sortSpec.direction() == SortSpec.Direction.ASC
                    ? SortOrder.Asc
                    : SortOrder.Desc;
            @SuppressWarnings("rawtypes")
            SearchResponse<Map> response = elasticsearchClientService.search(
                    AppConstants.LOGS_INDEX, query, from, fetchSize,
                    AppConstants.TIMESTAMP_FIELD, esOrder, Map.class);

            return response.hits().hits().stream()
                    .map(this::mapHitToDTO)
                    .filter(dto -> dto != null && dto.getMessage() != null)
                    .toList();
        } catch (IOException e) {
            log.error("Error searching logs in Elasticsearch", e);
            throw new RuntimeException("Error searching logs", e);
        }
    }

    private static boolean isAllServices(String svc) {
        return svc == null || svc.trim().isEmpty()
                || "ALL".equalsIgnoreCase(svc.trim());
    }

    /** Exact-match ES filters; severity is handled separately so we can support AT_LEAST mode (issue #14). */
    private static Map<String, String> buildEsFilters(LogRequestDTO request) {
        Map<String, String> filters = new HashMap<>();
        putIfNotEmpty(filters, "host.name", request.getHostName());
        putIfNotEmpty(filters, "host.os.type", request.getOsType());
        putIfNotEmpty(filters, "scope.name", request.getScopeName());
        putIfNotEmpty(filters, "trace.id", request.getTraceId());
        return filters;
    }

    private static void putIfNotEmpty(Map<String, String> filters, String field, String value) {
        if (value != null && !value.isEmpty())
            filters.put(field, value);
    }

    /**
     * Narrows {@code withSeverity} to one service. Matches on either ES field shape
     * (some indexes write the dotted resource.attributes.service.name path, others
     * flatten to service.name).
     */
    private static Query withServiceFilter(Query withSeverity, String svc) {
        String trimmed = svc.trim();
        Query serviceQuery = Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field("service.name").query(trimmed)))
                .should(s -> s.match(m -> m.field("resource.attributes.service.name").query(trimmed)))
                .minimumShouldMatch("1")));
        return Query.of(q -> q.bool(b -> b.must(withSeverity).must(serviceQuery)));
    }

    /**
     * Combine the base bool query with a severity filter that honours
     * {@code severityMode} (issue #14). EXACT mode matches one value; AT_LEAST
     * expands to all severities ≥ the requested one. Returns the original
     * query unchanged when no severity is specified or severity=ALL.
     *
     * Uses {@code bool/should + match} (rather than {@code terms}) so the same
     * analyzer the index was built with applies to each candidate severity —
     * matches the pre-issue-14 behavior of the single-severity path that used
     * {@link QueryBuilderUtil#buildBoolQuery}'s {@code match} clause.
     */
    private Query wrapWithSeverityFilter(Query base, LogRequestDTO request) {
        String severity = request.getSeverity();
        if (severity == null || severity.trim().isEmpty()
                || "ALL".equalsIgnoreCase(severity.trim())) {
            return base;
        }
        boolean atLeast = "AT_LEAST".equalsIgnoreCase(request.getSeverityMode());
        List<String> levels = atLeast
                ? SeverityRank.atLeast(severity)
                : SeverityRank.exact(severity);
        Query severityQuery = Query.of(q -> q.bool(b -> {
            for (String lvl : levels) {
                b.should(s -> s.match(m -> m.field(COL_SEVERITY_TEXT).query(lvl)));
            }
            return b.minimumShouldMatch("1");
        }));
        return Query.of(q -> q.bool(b -> b.must(base).must(severityQuery)));
    }

    private LogResponseDTO mapHitToDTO(@SuppressWarnings("rawtypes") Hit<Map> hit) {
        @SuppressWarnings("unchecked")
        Map<String, Object> source = hit.source();
        if (source == null)
            return null;

        LogResponseDTO dto = new LogResponseDTO();
        dto.setId(hit.id());
        dto.setTimestamp(MappingUtil.getStringValue(source, "@timestamp"));

        String hitSeverity = MappingUtil.getStringValue(source, COL_SEVERITY_TEXT);
        dto.setSeverity(hitSeverity);

        String rawMessage = MappingUtil.getStringValue(source, "body.text", COL_MESSAGE);
        dto.setMessage(LogNormalizer.normalizeMessage(rawMessage, hitSeverity));

        if (SEV_ERROR.equalsIgnoreCase(hitSeverity)) {
            dto.setMessage(LogNormalizer.enrichMessageWithStackTrace(dto.getMessage(), source));
        }

        dto.setServiceName(MappingUtil.getStringValue(source, "resource.attributes.service.name", "service.name"));
        dto.setHostName(
                MappingUtil.getStringValue(source, "resource.attributes.host.name", "host.name", "host.hostname"));
        dto.setOsType(MappingUtil.getStringValue(source, "resource.attributes.os.type", "host.os.type"));
        dto.setScopeName(MappingUtil.getStringValue(source, "scope.name", "resource.attributes.scope.name"));
        dto.setTraceId(MappingUtil.getStringValue(source, "trace.id", COL_TRACE_ID));

        LogNormalizer.extractCodeMetadata(source, dto);

        return dto;
    }

}
