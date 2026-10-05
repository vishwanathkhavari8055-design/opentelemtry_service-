package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.TraceLookupProperties;
import com.trinity.observability.dto.traces.ServiceCatalogDTO;
import com.trinity.observability.dto.traces.SpansPageDTO;
import com.trinity.observability.dto.traces.TraceHistogramDTO;
import com.trinity.observability.dto.traces.TraceListPageDTO;
import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.registry.OpenObserveStreamBounds;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.service.TraceAnalyticsService;
import com.trinity.observability.util.RequestScopedFanout;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;
import com.trinity.observability.util.TraceLookupWindow;

/**
 * Aggregate queries over the traces stream, answered by DataFusion inside
 * OpenObserve rather than by pulling rows into the JVM.
 *
 * <p>Two facts about the stream drive most of the code here:</p>
 * <ul>
 *   <li>{@code duration} is in MICROSECONDS. Every latency this class emits
 *       stays in microseconds and is named {@code …Us} so nobody has to guess.</li>
 *   <li>{@code span_status} is {@code UNSET} for healthy spans, not {@code OK}
 *       — so "not an error" cannot be written as {@code = 'OK'}.</li>
 * </ul>
 */
@Service
public class TraceAnalyticsServiceImpl implements TraceAnalyticsService {

    private static final Logger log = LogManager.getLogger(TraceAnalyticsServiceImpl.class);

    /** Hard ceiling on rows pulled back for any single aggregate query. */
    private static final int MAX_GROUPS = 10_000;

    /** Spans fetched to reconstruct one page of traces. A page of 25 traces
     *  with a pathological fan-out still fits comfortably. */
    private static final int PAGE_SPANS_CAP = 5_000;

    /** Error-rate thresholds for the catalog's Status column. */
    private static final double CRITICAL_RATE = 0.10;
    private static final double WARNING_RATE  = 0.05;
    private static final double DEGRADED_RATE = 0.01;

    /** OpenObserve stream type (and Product Catalog signal) this class queries. */
    private static final String TRACES_STREAM_TYPE = "traces";

    /** Span status of a failed span; also the trace-level status and outcome filter value. */
    private static final String STATUS_ERROR = "ERROR";
    /** Trace-level status / outcome filter value for a trace without a failed span. */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /** Columns and result aliases of the traces stream read back by several queries. */
    private static final String COL_TRACE_ID = "trace_id";
    private static final String COL_SERVICE_NAME = "service_name";
    private static final String COL_DURATION = "duration";
    private static final String COL_ERRORS = "errors";

    /** Histogram bucket widths keyed by their length in seconds, for {@link #bucketFor}. */
    private static final NavigableMap<Long, String> BUCKET_WIDTHS = Collections.unmodifiableNavigableMap(
            new TreeMap<>(Map.ofEntries(
                    Map.entry(1L, "1 second"),
                    Map.entry(5L, "5 seconds"),
                    Map.entry(10L, "10 seconds"),
                    Map.entry(30L, "30 seconds"),
                    Map.entry(60L, "1 minute"),
                    Map.entry(300L, "5 minutes"),
                    Map.entry(600L, "10 minutes"),
                    Map.entry(1800L, "30 minutes"),
                    Map.entry(3600L, "1 hour"),
                    Map.entry(21600L, "6 hours"),
                    Map.entry(86400L, "1 day"))));

    private final OpenObserveClientService oo;
    private final OpenObserveConfig config;

    /** Product Catalog scoping — see the field note in {@link LogsServiceImpl}. */
    private final CatalogTelemetryFilter catalogFilter;

    /** Rules for a request that names one trace — see {@link TraceLookupProperties}. */
    private final TraceLookupProperties traceLookup;

    /** Live stream extent, so a by-id lookup needs no invented window. Nullable —
     *  see the field note in {@code TracesServiceImpl}. */
    private final OpenObserveStreamBounds streamBounds;

    /** Runs a request's independent reads together — see {@link RequestScopedFanout}. */
    private final RequestScopedFanout fanout;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    // @Autowired is required to disambiguate from the test-only constructor.
    @Autowired
    public TraceAnalyticsServiceImpl(OpenObserveClientService oo, OpenObserveConfig config,
            CatalogTelemetryFilter catalogFilter, TraceLookupProperties traceLookup,
            OpenObserveStreamBounds streamBounds, RequestScopedFanout fanout) {
        this.oo = oo;
        this.config = config;
        this.catalogFilter = catalogFilter;
        this.traceLookup = traceLookup;
        this.streamBounds = streamBounds;
        this.fanout = fanout;
    }

    /** Test-only convenience constructor — no catalog scoping, default lookup rules. */
    public TraceAnalyticsServiceImpl(OpenObserveClientService oo, OpenObserveConfig config) {
        this(oo, config, CatalogTelemetryFilter.permitAll(), new TraceLookupProperties(), null,
                new RequestScopedFanout(4));
    }

    /**
     * Oldest timestamp the traces stream actually holds, or {@code null} if
     * OpenObserve reports none — see {@link OpenObserveStreamBounds}.
     */
    private Long tracesStreamMinMicros() {
        if (streamBounds == null) return null;
        OpenObserveStreamBounds.Bounds bounds =
                streamBounds.forStream(config.getTracesStream(), TRACES_STREAM_TYPE);
        return bounds == null ? null : bounds.minMicros();
    }

    // ── Spans tab ────────────────────────────────────────────────────────────

    @Override
    public SpansPageDTO getSpans(TraceRequestDTO request) {
        SpansPageDTO out = new SpansPageDTO();
        out.setItems(new ArrayList<>());
        out.setBackend(backendName());
        if (!openObserveEnabled) {
            log.info("getSpans() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        String stream = stream();
        String where = whereClause(request);

        // SELECT * rather than a projection: the typed fields below drive the
        // table, but the UI's field explorer needs every attribute the span
        // carries and there is no schema endpoint to enumerate them from. One
        // page of rows is a small payload; the aggregate queries elsewhere in
        // this class stay projected because they scan the whole window.
        String sql = "SELECT * FROM \"" + stream + "\" " + where
                + " ORDER BY _timestamp DESC";

        // The trace view fetches a whole trace through here and deliberately sends
        // NO time range, because a trace is identified by its id and a window can
        // only lose spans from it. window() answers that with the default now-12h,
        // so a trace older than twelve hours opened completely empty — and the Logs
        // screen this is reached from offers windows up to six months. A named
        // trace with no caller window therefore widens until it finds the trace,
        // and is not paginated. Anything else — including the Traces screen
        // filtering its own table by a pasted trace id, which always sends its
        // toolbar range — keeps the window and pagination it had.
        Window w;
        // Set when the page branch already ran the totals query alongside the page.
        boolean totalsApplied = false;
        int size;
        List<Map<String, Object>> hits;

        if (isWholeTraceLookup(request)) {
            size = traceLookup.getMaxSpans();
            final String byIdSql = sql;
            final int cap = size;
            TraceLookupWindow.Resolved resolved = TraceLookupWindow.narrowestComplete(
                    tracesStreamMinMicros(),
                    traceLookup.getWindows(),
                    traceLookup.rootMarginMicros(),
                    (s, e) -> query(byIdSql, new Window(s, e), 0, cap, "spans by trace id"));
            w = new Window(resolved.startMicros(), resolved.endMicros());
            hits = resolved.hits();
            if (hits.size() == cap) {
                log.warn("Trace {} hit the traces.lookup.max-spans cap of {} — the waterfall may be "
                        + "incomplete", request.getTraceId(), cap);
            }
        } else {
            w = window(request);
            size = pageSize(request);
            int from = request.getPage() * size;
            // The window is known up front here, so the totals query does not have
            // to wait for the page query — unlike the whole-trace-lookup branch
            // above, where `w` is only settled by probing windows until one returns
            // rows, and the totals MUST use that resolved window.
            final Window pageWindow = w;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>>[] pageHits = new List[1];
            fanout.runAll(List.of(
                    () -> pageHits[0] = query(sql, pageWindow, from, size, "spans page"),
                    () -> applySpanTotals(out, stream, where, pageWindow)));
            hits = pageHits[0] == null ? List.of() : pageHits[0];
            totalsApplied = true;
        }
        for (Map<String, Object> h : hits) {
            SpansPageDTO.Row row = new SpansPageDTO.Row();
            row.setTimestamp(isoOf(h.get("_timestamp")));
            row.setTraceId(str(h.get(COL_TRACE_ID)));
            row.setSpanId(str(h.get("span_id")));
            row.setServiceName(str(h.get(COL_SERVICE_NAME)));
            row.setOperationName(str(h.get("operation_name")));
            row.setDurationUs(lng(h.get(COL_DURATION)));
            row.setSpanStatus(str(h.get("span_status")));
            row.setRaw(h);
            out.getItems().add(row);
        }
        out.setHasMore(hits.size() == size);

        if (!totalsApplied) {
            applySpanTotals(out, stream, where, w);
        }
        return out;
    }

    /**
     * Whole-window totals, not page totals — these feed the "N Spans Found /
     * N Error Spans" chips.
     */
    private void applySpanTotals(SpansPageDTO out, String stream, String where, Window w) {
        String countSql = "SELECT COUNT(*) AS total, "
                + "SUM(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS errors "
                + "FROM \"" + stream + "\" " + where;
        List<Map<String, Object>> counts = query(countSql, w, 0, 1, "spans totals");
        if (!counts.isEmpty()) {
            out.setTotal(lng(counts.get(0).get("total")));
            out.setErrorTotal(lng(counts.get(0).get(COL_ERRORS)));
        }
    }

    // ── Traces tab ───────────────────────────────────────────────────────────

    @Override
    public TraceListPageDTO getTraceList(TraceRequestDTO request) {
        TraceListPageDTO out = new TraceListPageDTO();
        out.setItems(new ArrayList<>());
        out.setBackend(backendName());
        if (!openObserveEnabled) {
            log.info("getTraceList() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        Window w = window(request);
        String stream = stream();
        String where = whereClause(request);
        int size = pageSize(request);
        int from = request.getPage() * size;

        // Four reads, but they are NOT all independent: the spans query needs the
        // trace ids the first query returns, so that pair stays sequential. The two
        // totals are whole-window figures that do not depend on which page is being
        // shown, so they run alongside the pair instead of after it — turning
        // 4 sequential round trips into max(ids+spans, total, errors). They write
        // separate fields of `out` (total / errorTotal / items+hasMore), so there is
        // no shared state between the three branches.
        fanout.runAll(List.of(
                () -> applyTraceTotal(out, stream, where, w),
                () -> applyTraceErrorTotal(out, stream, where, w),
                () -> fillTraceListPage(out, stream, where, w, from, size)));
        return out;
    }

    /** The visible page: trace ids, then every span of just those traces. */
    private void fillTraceListPage(TraceListPageDTO out, String stream, String where,
            Window w, int from, int size) {
        // Step 1 — the page of trace ids, newest first. Grouping is what makes
        // this trace-grain; ordering by the trace's first span keeps the list
        // stable as later spans arrive.
        String idSql = "SELECT trace_id, MIN(_timestamp) AS ts FROM \"" + stream + "\" " + where
                + (where.isEmpty() ? " WHERE" : " AND") + " trace_id IS NOT NULL AND trace_id != ''"
                + " GROUP BY trace_id ORDER BY ts DESC";
        List<Map<String, Object>> idHits = query(idSql, w, from, size, "trace ids");
        if (idHits.isEmpty()) {
            return;
        }
        out.setHasMore(idHits.size() == size);

        List<String> ids = new ArrayList<>(idHits.size());
        Long pageEarliest = collectTraceIds(idHits, ids);

        // Step 2 — every span of just those traces. Cheaper than it looks: the
        // IN list is bounded by the page size, and it lets the per-service
        // breakdown, the root span and the span count all come from one pass
        // instead of three more GROUP BYs.
        String in = quotedList(ids);
        String spanSql = "SELECT trace_id, span_id, service_name, operation_name, duration, "
                + "_timestamp, span_status, reference_parent_span_id FROM \"" + stream + "\""
                + " WHERE trace_id IN (" + in + ") ORDER BY _timestamp ASC";
        Window spanWindow = spanWindowFrom(pageEarliest, w);
        List<Map<String, Object>> spans = query(spanSql, spanWindow, 0, PAGE_SPANS_CAP, "trace page spans");

        Map<String, List<Map<String, Object>>> byTrace = groupSpansByTrace(ids, spans);

        for (String id : ids) {
            out.getItems().add(buildTraceRow(id, byTrace.get(id)));
        }
    }

    /** Adds each non-empty trace id to {@code ids}; returns the earliest {@code ts} seen (or null). */
    private static Long collectTraceIds(List<Map<String, Object>> idHits, List<String> ids) {
        Long pageEarliest = null;
        for (Map<String, Object> h : idHits) {
            String id = str(h.get(COL_TRACE_ID));
            if (id != null && !id.isEmpty()) ids.add(id);
            Long ts = lng(h.get("ts"));
            if (ts != null && (pageEarliest == null || ts < pageEarliest)) pageEarliest = ts;
        }
        return pageEarliest;
    }

    /** {@code 'a', 'b', 'c'} — each value escaped as a SQL string literal. */
    private static String quotedList(List<String> values) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) in.append(", ");
            in.append('\'').append(SqlSafety.escapeStringLiteral(values.get(i))).append('\'');
        }
        return in.toString();
    }

    /**
     * Scanned from the page's earliest trace start, not the whole window. `ts` is
     * each trace's MIN(_timestamp) within the window, so no span of these traces
     * inside the window can be older than the smallest of them — the result is
     * identical, but the first page of a 24h window scans minutes, not a day.
     */
    private static Window spanWindowFrom(Long pageEarliest, Window w) {
        return (pageEarliest != null && pageEarliest > w.start() && pageEarliest <= w.end())
                // One microsecond earlier so the bound is safe whether OpenObserve
                // treats start_time as inclusive or exclusive.
                ? new Window(pageEarliest - 1, w.end())
                : w;
    }

    /** Buckets spans by trace id, keeping only (and every one of) the given ids, in order. */
    private static Map<String, List<Map<String, Object>>> groupSpansByTrace(
            List<String> ids, List<Map<String, Object>> spans) {
        Map<String, List<Map<String, Object>>> byTrace = new LinkedHashMap<>();
        for (String id : ids) byTrace.put(id, new ArrayList<>());
        for (Map<String, Object> s : spans) {
            List<Map<String, Object>> bucket = byTrace.get(str(s.get(COL_TRACE_ID)));
            if (bucket != null) bucket.add(s);
        }
        return byTrace;
    }

    /** Folds one trace's spans into the row the list renders. */
    private TraceListPageDTO.Row buildTraceRow(String traceId, List<Map<String, Object>> spans) {
        TraceListPageDTO.Row row = new TraceListPageDTO.Row();
        row.setTraceId(traceId);
        row.setSpanCount(spans.size());

        if (spans.isEmpty()) {
            row.setStatus(STATUS_SUCCESS);
            row.setServices(new ArrayList<>());
            return row;
        }

        // The root is the span with no parent. Traces can arrive partial (the
        // parent may be outside the window, or simply not ingested yet), so the
        // earliest span is the fallback — it is the best available stand-in for
        // "where this trace started".
        Map<String, Object> root = findRootSpan(spans);

        row.setTimestamp(isoOf(spans.get(0).get("_timestamp")));
        row.setServiceName(str(root.get(COL_SERVICE_NAME)));
        row.setOperationName(str(root.get("operation_name")));
        // The root subsumes its children, so its own duration is the trace's
        // wall clock. Summing every span would double-count nested work.
        row.setDurationUs(lng(root.get(COL_DURATION)));

        Map<String, long[]> perService = new LinkedHashMap<>();
        boolean errored = accumulatePerService(spans, perService);
        row.setStatus(errored ? STATUS_ERROR : STATUS_SUCCESS);
        row.setServices(toServiceSlices(perService));
        return row;
    }

    /** First span with no parent; the earliest span when none qualifies. */
    private static Map<String, Object> findRootSpan(List<Map<String, Object>> spans) {
        for (Map<String, Object> s : spans) {
            String parent = str(s.get("reference_parent_span_id"));
            if (parent == null || parent.isEmpty()) return s;
        }
        return spans.get(0);
    }

    /**
     * Sums duration and span count per service into {@code perService}
     * ([0] = duration, [1] = count). Returns whether any span errored.
     */
    private static boolean accumulatePerService(List<Map<String, Object>> spans, Map<String, long[]> perService) {
        boolean errored = false;
        for (Map<String, Object> s : spans) {
            if (STATUS_ERROR.equalsIgnoreCase(str(s.get("span_status")))) errored = true;
            String svc = str(s.get(COL_SERVICE_NAME));
            if (svc == null || svc.isEmpty()) continue;
            long[] acc = perService.computeIfAbsent(svc, k -> new long[2]);
            Long d = lng(s.get(COL_DURATION));
            acc[0] += d == null ? 0L : d;
            acc[1] += 1L;
        }
        return errored;
    }

    /** Service slices, longest total duration first. */
    private static List<TraceListPageDTO.ServiceSlice> toServiceSlices(Map<String, long[]> perService) {
        List<TraceListPageDTO.ServiceSlice> slices = new ArrayList<>();
        perService.forEach((name, acc) ->
                slices.add(new TraceListPageDTO.ServiceSlice(name, acc[0], (int) acc[1])));
        slices.sort(Comparator.comparingLong(
                (TraceListPageDTO.ServiceSlice s) -> s.getDurationUs() == null ? 0L : s.getDurationUs()).reversed());
        return slices;
    }

    private void applyTraceTotal(TraceListPageDTO out, String stream, String where, Window w) {
        String sql = "SELECT COUNT(DISTINCT trace_id) AS total FROM \"" + stream + "\" " + where;
        List<Map<String, Object>> hits = query(sql, w, 0, 1, "trace totals");
        if (!hits.isEmpty()) out.setTotal(lng(hits.get(0).get("total")));
    }

    /**
     * Error traces need their own query: a trace counts once no matter how many
     * of its spans failed, so this cannot be summed from the total above. Split
     * from {@link #applyTraceTotal} so the two can run concurrently — they touch
     * different fields and neither reads the other's.
     */
    private void applyTraceErrorTotal(TraceListPageDTO out, String stream, String where, Window w) {
        String errSql = "SELECT COUNT(DISTINCT trace_id) AS errors FROM \"" + stream + "\" "
                + (where.isEmpty() ? "WHERE" : where + " AND") + " span_status = 'ERROR'";
        List<Map<String, Object>> errHits = query(errSql, w, 0, 1, "error trace totals");
        if (!errHits.isEmpty()) out.setErrorTotal(lng(errHits.get(0).get(COL_ERRORS)));
    }

    // ── Service Catalog tab ──────────────────────────────────────────────────

    @Override
    public ServiceCatalogDTO getServiceCatalog(TraceRequestDTO request) {
        ServiceCatalogDTO out = new ServiceCatalogDTO();
        out.setItems(new ArrayList<>());
        out.setBackend(backendName());
        if (!openObserveEnabled) {
            log.info("getServiceCatalog() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        Window w = window(request);
        String sql = "SELECT service_name, COUNT(*) AS requests, "
                + "SUM(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS errors, "
                + "approx_percentile_cont(duration, 0.5) AS p50, "
                + "approx_percentile_cont(duration, 0.95) AS p95, "
                + "approx_percentile_cont(duration, 0.99) AS p99, "
                + "AVG(duration) AS avg_d, MAX(duration) AS max_d "
                + "FROM \"" + stream() + "\" " + whereClause(request)
                + (whereClause(request).isEmpty() ? " WHERE" : " AND")
                + " service_name IS NOT NULL AND service_name != ''"
                + " GROUP BY service_name ORDER BY requests DESC";

        int critical = 0;
        int warning = 0;
        int degraded = 0;
        int healthy = 0;

        for (Map<String, Object> h : query(sql, w, 0, MAX_GROUPS, "service catalog")) {
            ServiceCatalogDTO.Row row = toCatalogRow(h);
            switch (row.getStatus()) {
                case "CRITICAL" -> critical++;
                case "WARNING"  -> warning++;
                case "DEGRADED" -> degraded++;
                default         -> healthy++;
            }
            out.getItems().add(row);
        }

        out.setCriticalCount(critical);
        out.setWarningCount(warning);
        out.setDegradedCount(degraded);
        out.setHealthyCount(healthy);
        return out;
    }

    /** One service-catalog row from its aggregate hit, with error rate and health status filled in. */
    private static ServiceCatalogDTO.Row toCatalogRow(Map<String, Object> h) {
        ServiceCatalogDTO.Row row = new ServiceCatalogDTO.Row();
        row.setServiceName(str(h.get(COL_SERVICE_NAME)));
        Long requests = lng(h.get("requests"));
        Long errors = lng(h.get(COL_ERRORS));
        row.setRequests(requests);
        row.setErrors(errors);

        double rate = (requests == null || requests == 0L || errors == null)
                ? 0.0 : errors.doubleValue() / requests.doubleValue();
        row.setErrorRate(rate);

        row.setP50Us(lng(h.get("p50")));
        row.setP95Us(lng(h.get("p95")));
        row.setP99Us(lng(h.get("p99")));
        row.setAvgUs(lng(h.get("avg_d")));
        row.setMaxUs(lng(h.get("max_d")));

        row.setStatus(statusFor(rate));
        return row;
    }

    /** Error-rate banding. Thresholds are the constants above so the UI's
     *  legend and this classification can't drift apart. */
    private static String statusFor(double rate) {
        if (rate >= CRITICAL_RATE) return "CRITICAL";
        if (rate >= WARNING_RATE)  return "WARNING";
        if (rate >= DEGRADED_RATE) return "DEGRADED";
        return "HEALTHY";
    }

    // ── Chart row ────────────────────────────────────────────────────────────

    @Override
    public TraceHistogramDTO getHistogram(TraceRequestDTO request) {
        TraceHistogramDTO out = new TraceHistogramDTO();
        out.setBuckets(new ArrayList<>());
        out.setBackend(backendName());
        if (!openObserveEnabled) {
            log.info("getHistogram() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        Window w = window(request);
        String interval = bucketFor(w.end - w.start);
        out.setInterval(interval);

        // ONE query for all three panels. Percentiles come from the same
        // GROUP BY as the counts, so every panel is plotted against an
        // identical set of bucket timestamps and the x axes cannot drift.
        String bucketSql = "SELECT histogram(_timestamp, '" + interval + "') AS ts, COUNT(*) AS n, "
                + "SUM(CASE WHEN span_status = 'ERROR' THEN 1 ELSE 0 END) AS errs, "
                + "approx_percentile_cont(duration, 0.5) AS p50, "
                + "approx_percentile_cont(duration, 0.95) AS p95, "
                + "MAX(duration) AS pmax "
                + "FROM \"" + stream() + "\" " + whereClause(request)
                + " GROUP BY ts ORDER BY ts";
        for (Map<String, Object> h : query(bucketSql, w, 0, MAX_GROUPS, "histogram")) {
            out.getBuckets().add(new TraceHistogramDTO.Bucket(
                    str(h.get("ts")), lng(h.get("n")), lng(h.get("errs")),
                    lng(h.get("p50")), lng(h.get("p95")), lng(h.get("pmax"))));
        }

        return out;
    }

    /**
     * Bucket width for a window, aiming for roughly 60 buckets. Hard-coding
     * "1 minute" would give one bucket for a 1-minute window and 43,200 for a
     * month — the first unreadable, the second a payload nobody wants.
     */
    private static String bucketFor(long spanMicros) {
        long seconds = spanMicros / 1_000_000L;
        long target = Math.max(1L, seconds / 60L);
        // Smallest listed width (in seconds) that is at least the target; wider than a day → a week.
        Map.Entry<Long, String> width = BUCKET_WIDTHS.ceilingEntry(target);
        return width != null ? width.getValue() : "1 week";
    }

    // ── Shared plumbing ──────────────────────────────────────────────────────

    private record Window(long start, long end) {}

    /**
     * True when the request identifies ONE trace and leaves the range open — the
     * "open this trace" request, as opposed to a range query that happens to carry
     * a trace id. See the longer note on the equivalent check in
     * {@code TracesServiceImpl} for why the second half is what makes this
     * change invisible to every existing caller.
     */
    private static boolean isWholeTraceLookup(TraceRequestDTO r) {
        return notBlank(r.getTraceId())
                && (r.getStartTime() == null || r.getStartTime().isBlank())
                && (r.getEndTime() == null || r.getEndTime().isBlank());
    }

    private Window window(TraceRequestDTO r) {
        long end = (r.getEndTime() == null || r.getEndTime().isBlank())
                ? TimeRangeParser.defaultEndMicros()
                : TimeRangeParser.toMicros(r.getEndTime(), TimeRangeParser.defaultEndMicros());
        long start = (r.getStartTime() == null || r.getStartTime().isBlank())
                ? TimeRangeParser.defaultStartMicros()
                : TimeRangeParser.toMicros(r.getStartTime(), end);
        return new Window(start, end);
    }

    private String stream() {
        return SqlSafety.validateIdentifier(config.getTracesStream());
    }

    private String backendName() {
        return openObserveEnabled ? "openobserve" : "elasticsearch";
    }

    private static int pageSize(TraceRequestDTO r) {
        int s = r.getSize();
        return s <= 0 ? 25 : Math.min(s, 1000);
    }

    /**
     * WHERE clause shared by every query here, so the table, the totals and the
     * charts can never describe different row sets. Returns "" when nothing is
     * filtered — callers that need to append their own predicate check for that.
     *
     * <p>The time bounds are NOT in here: OpenObserve takes them as
     * {@code start_time} / {@code end_time} on the request body, and repeating
     * them in SQL would just be a second place to get them wrong.</p>
     *
     * <p>The Product Catalog predicate joins the same list, which is the whole
     * reason this method is the only place it needs to be added: the spans table,
     * the trace list, the service catalog, the totals and the histogram all build
     * from here, so none of them can disagree with the others about which
     * resources are in scope. (No longer static for that reason — it needs the
     * injected filter.)</p>
     */
    private String whereClause(TraceRequestDTO r) {
        List<String> preds = new ArrayList<>();

        String catalogPredicate = catalogFilter.sqlPredicate(TRACES_STREAM_TYPE);
        if (!catalogPredicate.isEmpty()) {
            preds.add(catalogPredicate);
        }

        // Multi-select: OR across the chosen services (an IN list), which is
        // what "show me these three services" means on a span list. Note this
        // differs from the LOGS multi-service filter, which ANDs at trace grain
        // — there the question is "traces that touch all of these", which only
        // makes sense because a log row belongs to a trace that spans services.
        addServicePredicate(preds, selectedServices(r));
        if (notBlank(r.getTraceId())) {
            preds.add("trace_id = '" + SqlSafety.escapeStringLiteral(r.getTraceId().trim()) + "'");
        }
        if (notBlank(r.getSpanName())) {
            preds.add("operation_name = '" + SqlSafety.escapeStringLiteral(r.getSpanName().trim()) + "'");
        }
        if (notBlank(r.getOutcome())) {
            addOutcomePredicate(preds, r.getOutcome());
        }
        if (notBlank(r.getSearch())) {
            String term = SqlSafety.escapeLikePattern(r.getSearch().trim());
            preds.add("(operation_name LIKE '%" + term + "%'"
                    + " OR service_name LIKE '%" + term + "%'"
                    + " OR trace_id LIKE '%" + term + "%')");
        }

        return preds.isEmpty() ? "" : "WHERE " + String.join(" AND ", preds);
    }

    /** The multi-select services, falling back to the single-service field when none are chosen. */
    private static List<String> selectedServices(TraceRequestDTO r) {
        List<String> services = new ArrayList<>();
        for (String s : r.getServiceNames()) {
            if (notBlank(s)) services.add(s.trim());
        }
        if (services.isEmpty() && notBlank(r.getServiceName())) {
            services.add(r.getServiceName().trim());
        }
        return services;
    }

    /** {@code =} for one service, an IN list for several, nothing for none. */
    private static void addServicePredicate(List<String> preds, List<String> services) {
        if (services.size() == 1) {
            preds.add("service_name = '" + SqlSafety.escapeStringLiteral(services.get(0)) + "'");
        } else if (services.size() > 1) {
            preds.add("service_name IN (" + quotedList(services) + ")");
        }
    }

    private static void addOutcomePredicate(List<String> preds, String outcome) {
        String o = outcome.trim().toUpperCase();
        if (STATUS_ERROR.equals(o)) {
            preds.add("span_status = 'ERROR'");
        } else if (STATUS_SUCCESS.equals(o)) {
            // Healthy spans are UNSET, not OK — see the class comment.
            preds.add("span_status != 'ERROR'");
        }
    }

    private List<Map<String, Object>> query(String sql, Window w, int from, int size, String what) {
        if (log.isDebugEnabled()) log.debug("traces {} SQL: {}", what, sql);
        try {
            return oo.search(sql, w.start(), w.end(), from, size, TRACES_STREAM_TYPE);
        } catch (Exception e) {
            log.error("Traces {} query failed: {}", what, e.getMessage(), e);
            return List.of();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank() && !"ALL".equalsIgnoreCase(s.trim());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return (long) Double.parseDouble(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /**
     * {@code _timestamp} comes back as epoch microseconds from a plain SELECT
     * but as an ISO string from {@code histogram()}, so both are handled.
     */
    private static String isoOf(Object o) {
        if (o instanceof Number n) return TimeFormatter.microsToIso(n.longValue());
        return str(o);
    }
}
