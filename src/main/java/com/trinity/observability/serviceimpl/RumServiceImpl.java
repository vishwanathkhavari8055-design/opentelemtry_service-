package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.rum.RumResponseDTO;
import com.trinity.observability.service.RumService;
import com.trinity.observability.util.RequestScopedFanout;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;

/**
 * Real User Monitoring, read from the OpenObserve RUM streams.
 *
 * <h2>Where the data comes from</h2>
 *
 * <p>The browser SDK writes one row per event into a single stream (default
 * {@code _rumdata}), discriminated by a {@code type} column:
 * {@code view}, {@code resource}, {@code error}, {@code action}, {@code long_task}.
 * Every screen here is a different aggregation over that one stream, which is why
 * there is no per-signal stream configuration — there is no per-signal stream.</p>
 *
 * <p>Stream names are configuration. Nothing below names an application, service,
 * environment or version: the filter lists are {@code SELECT DISTINCT} results, so a
 * frontend becomes visible here the moment it reports its first event.</p>
 *
 * <h2>Nanoseconds in, milliseconds out</h2>
 *
 * <p>Every duration the SDK writes is in NANOSECONDS —
 * {@code view_largest_contentful_paint = 1820000000} is 1.82 s. All of them are
 * divided by {@link #NANOS_PER_MILLI} before leaving this class, so the DTO contract
 * is uniformly milliseconds and no consumer has to know the wire unit. CLS is left
 * alone: it is a dimensionless score.</p>
 *
 * <h2>Why p75 and not an average</h2>
 *
 * <p>Core Web Vitals are defined at the 75th percentile, and averaging them is not a
 * simplification but a different measurement — one slow cold load pulls a mean far
 * past what most visitors experienced, and a mean CLS of 0.05 can hide a quarter of
 * sessions above the 0.25 "poor" line entirely. {@code approx_percentile_cont} is
 * verified available on this deployment.</p>
 *
 * <h2>Emptiness is not a fact</h2>
 *
 * <p>Every query goes through {@code searchDetailed}, because this OpenObserve
 * answers a window it cannot scan with HTTP 200 and an empty {@code hits} array.
 * Any response built from a partial scan is flagged {@code partial} so the screen can
 * say "at least this many" instead of presenting a floor as a total.</p>
 */
@Service
public class RumServiceImpl implements RumService {

    private static final Logger log = LogManager.getLogger(RumServiceImpl.class);

    /** Matches the period the RUM UI defaults to, so a fresh screen shows something. */
    private static final String DEFAULT_WINDOW = "now-6h";

    private static final double NANOS_PER_MILLI = 1_000_000d;

    /** Rows read back per aggregate query. A GROUP BY over session or issue keys. */
    private static final int MAX_GROUPS = 10_000;

    /** Hard ceiling on a page, whatever the caller asks for. */
    private static final int MAX_PAGE_SIZE = 500;

    /** Error messages are free text and can run to kilobytes of serialised payload
     *  (observed: an entire HTTP response embedded in one). Truncated so one bad
     *  issue cannot dominate the response; the full text stays queryable in Logs. */
    private static final int MAX_MESSAGE_CHARS = 400;

    /** Buckets in the errors-over-time series. Fixed count rather than a fixed
     *  interval so the chart has the same shape at 15 minutes and at 30 days. */
    private static final int SERIES_BUCKETS = 48;

    /**
     * Published Core Web Vitals thresholds: {good, needs-improvement} upper bounds.
     *
     * <p>These are the only literals here that are not configuration, and
     * deliberately so — they are an external standard, not a property of this
     * deployment. Tuning them per environment would make "good" mean something
     * different on every screen. LCP/FCP/INP in ms; CLS is a score.</p>
     */
    private static final double LCP_GOOD = 2500;
    private static final double LCP_POOR = 4000;
    private static final double INP_GOOD = 200;
    private static final double INP_POOR = 500;
    private static final double CLS_GOOD = 0.1;
    private static final double CLS_POOR = 0.25;
    private static final double FCP_GOOD = 1800;
    private static final double FCP_POOR = 3000;

    /** Result-column aliases read back by more than one query below. */
    private static final String COL_SESSIONS = "sessions";
    private static final String COL_FIRST_TS = "first_ts";
    private static final String COL_LAST_TS = "last_ts";

    private final OpenObserveClientService openObserve;
    private final OpenObserveConfig openObserveConfig;
    private final RequestScopedFanout fanout;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /** The RUM event stream. Configuration, so a deployment that renamed it (or
     *  routes RUM to a tenant-specific stream) needs no code change. */
    @Value("${openobserve.rum-data-stream:_rumdata}")
    private String rumDataStream;

    public RumServiceImpl(OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig,
            RequestScopedFanout fanout) {
        this.openObserve = openObserve;
        this.openObserveConfig = openObserveConfig;
        this.fanout = fanout;
    }

    // ── Window + filter plumbing ─────────────────────────────────────────────

    /** Resolved query bounds, in microseconds since epoch. */
    private record Window(String expr, long startMicros, long endMicros) {
    }

    private Window resolveWindow(String window) {
        String expr = (window == null || window.isBlank()) ? DEFAULT_WINDOW : window.trim();
        long end = TimeRangeParser.defaultEndMicros();
        return new Window(expr, TimeRangeParser.toMicros(expr, TimeRangeParser.defaultStartMicros()),
                end);
    }

    /**
     * The shared WHERE tail: the time bounds plus whichever of service/env/version
     * the caller pinned.
     *
     * <p>Every value goes through {@link SqlSafety#escapeStringLiteral}. These arrive
     * from query parameters, so they are untrusted regardless of how the UI populated
     * the dropdown that produced them.</p>
     */
    private String filterClause(Window w, String service, String env, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append(" WHERE _timestamp >= ").append(w.startMicros())
          .append(" AND _timestamp <= ").append(w.endMicros());
        appendEq(sb, "service", service);
        appendEq(sb, "env", env);
        appendEq(sb, "version", version);
        return sb.toString();
    }

    /** Appends {@code AND col = 'value'}, skipping blanks and the UI's "all" token. */
    private void appendEq(StringBuilder sb, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        // The dropdowns send an explicit all-marker rather than omitting the param,
        // so it is translated to "no filter" here instead of becoming a literal.
        if ("<ALL>".equalsIgnoreCase(v) || "all".equalsIgnoreCase(v)) {
            return;
        }
        sb.append(" AND \"").append(column).append("\" = '")
          .append(SqlSafety.escapeStringLiteral(v)).append('\'');
    }

    private String stream() {
        return "\"" + SqlSafety.validateIdentifier(rumDataStream) + "\"";
    }

    /**
     * Run one aggregate query.
     *
     * <p>Returns an empty list on failure rather than throwing: a RUM screen shows
     * several independent panels, and one failed aggregation should blank that panel,
     * not the page. The failure is logged and surfaces as {@code partial}.</p>
     */
    private List<Map<String, Object>> run(String sql, Window w, boolean[] partialFlag) {
        try {
            OpenObserveClientService.SearchOutcome out = openObserve.searchDetailed(
                    sql, w.startMicros(), w.endMicros(), 0, MAX_GROUPS, "logs");
            if (out.partial()) {
                partialFlag[0] = true;
                log.warn("RUM query served partially ({}): {}", out.error(), sql);
            }
            return out.hits();
        } catch (Exception e) {
            partialFlag[0] = true;
            log.warn("RUM query failed: {} — {}", e.getMessage(), sql);
            return List.of();
        }
    }

    private RumResponseDTO.Meta meta(Window w, boolean partial) {
        return new RumResponseDTO.Meta(true, null, openObserveConfig.getOrg(), w.expr(),
                TimeFormatter.microsToIso(w.startMicros()),
                TimeFormatter.microsToIso(w.endMicros()), partial);
    }

    private RumResponseDTO.Meta unsupported(Window w) {
        return new RumResponseDTO.Meta(false,
                "Real User Monitoring reads the browser SDK's RUM stream from OpenObserve, "
                        + "which is disabled for this deployment (openobserve.enabled=false).",
                openObserveConfig.getOrg(), w.expr(),
                TimeFormatter.microsToIso(w.startMicros()),
                TimeFormatter.microsToIso(w.endMicros()), false);
    }

    // ── Value coercion ───────────────────────────────────────────────────────
    // OpenObserve types a column from what it first saw, so the same field comes
    // back as Integer, Long, Double or a numeric String depending on the row. These
    // read whatever arrived rather than casting and risking a ClassCastException
    // that would blank a panel.

    private static Double num(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long lng(Object v) {
        Double d = num(v);
        return d == null ? 0L : d.longValue();
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** Nanoseconds to milliseconds, preserving null so "not reported" stays distinct
     *  from "zero" — an unreported INP is not an instant one. */
    private static Double nanosToMillis(Object v) {
        Double d = num(v);
        return d == null ? null : d / NANOS_PER_MILLI;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_MESSAGE_CHARS ? flat
                : flat.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    private static String rating(Double value, double good, double poor) {
        if (value == null) {
            return null;
        }
        if (value <= good) {
            return "good";
        }
        return value <= poor ? "needs-improvement" : "poor";
    }

    // ── /scope ───────────────────────────────────────────────────────────────

    @Override
    public RumResponseDTO.Scope scope(String window) {
        Window w = resolveWindow(window);
        if (!openObserveEnabled) {
            return new RumResponseDTO.Scope(unsupported(w), List.of(), List.of(), List.of(),
                    List.of());
        }
        boolean[] partial = { false };
        // Four independent reads, run together rather than one after another. Each
        // writes its own slot; `partial` is only ever set to true, so the shared flag
        // cannot lose an update that matters.
        @SuppressWarnings("unchecked")
        List<String>[] values = new List[4];
        fanout.runAll(List.of(
                () -> values[0] = distinct("service", w, partial),
                () -> values[1] = distinct("env", w, partial),
                () -> values[2] = distinct("version", w, partial),
                () -> values[3] = distinct("application_id", w, partial)));
        // meta() is built AFTER the reads: it used to be evaluated first, so it
        // always reported partial=false whatever the queries came back with.
        return new RumResponseDTO.Scope(meta(w, partial[0]),
                orEmpty(values[0]), orEmpty(values[1]), orEmpty(values[2]), orEmpty(values[3]));
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** Distinct non-null values of one column over the window, sorted. */
    private List<String> distinct(String column, Window w, boolean[] partial) {
        String col = SqlSafety.validateIdentifier(column);
        String sql = "SELECT DISTINCT \"" + col + "\" AS v FROM " + stream()
                + filterClause(w, null, null, null)
                + " AND \"" + col + "\" IS NOT NULL ORDER BY 1";
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : run(sql, w, partial)) {
            String v = str(row.get("v"));
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    // ── /performance ─────────────────────────────────────────────────────────

    @Override
    public RumResponseDTO.Performance performance(String window, String service, String env,
            String version) {
        Window w = resolveWindow(window);
        if (!openObserveEnabled) {
            return new RumResponseDTO.Performance(unsupported(w), List.of(),
                    new RumResponseDTO.PerfCounts(0, 0, 0, 0), List.of());
        }
        String base = filterClause(w, service, env, version);

        // Three independent reads, run together rather than one after another —
        // none of them consumes another's output. Each gets its OWN partial flag
        // and result holder so the tasks share no mutable state; they are combined
        // below, after runAll has joined every task (which also publishes the
        // writes to this thread).
        boolean[] vitalsPartial = { false };
        boolean[] countsPartial = { false };
        boolean[] apiPartial = { false };

        @SuppressWarnings("unchecked")
        List<RumResponseDTO.Vital>[] vitalsOut = new List[1];
        RumResponseDTO.PerfCounts[] countsOut = new RumResponseDTO.PerfCounts[1];
        @SuppressWarnings("unchecked")
        List<RumResponseDTO.ApiCall>[] apiOut = new List[1];

        fanout.runAll(List.of(
                () -> vitalsOut[0] = readVitals(w, base, vitalsPartial),
                () -> countsOut[0] = readPerfCounts(w, base, countsPartial),
                () -> apiOut[0] = readApi(w, base, apiPartial)));

        boolean partial = vitalsPartial[0] || countsPartial[0] || apiPartial[0];

        // A task that threw leaves its holder null; fall back to the same empty
        // shapes the unsupported path returns rather than NPEing the response.
        List<RumResponseDTO.Vital> vitals = vitalsOut[0] == null ? List.of() : vitalsOut[0];
        RumResponseDTO.PerfCounts counts = countsOut[0] == null
                ? new RumResponseDTO.PerfCounts(0, 0, 0, 0) : countsOut[0];
        List<RumResponseDTO.ApiCall> api = apiOut[0] == null ? List.of() : apiOut[0];

        return new RumResponseDTO.Performance(meta(w, partial), vitals, counts, api);
    }

    /**
     * The Core Web Vitals, as p75 over {@code view} events.
     *
     * <p>One query, five percentiles. Each vital also carries its own sample count,
     * because they are not populated together: LCP exists on every view, INP only
     * once someone has interacted, so a single "views in window" number would
     * overstate how much evidence an INP figure rests on.</p>
     */
    private List<RumResponseDTO.Vital> readVitals(Window w, String base, boolean[] partial) {
        String sql = "SELECT "
                + "approx_percentile_cont(view_largest_contentful_paint, 0.75) AS lcp, "
                + "count(view_largest_contentful_paint) AS lcp_n, "
                + "approx_percentile_cont(view_first_contentful_paint, 0.75) AS fcp, "
                + "count(view_first_contentful_paint) AS fcp_n, "
                + "approx_percentile_cont(view_interaction_to_next_paint, 0.75) AS inp, "
                + "count(view_interaction_to_next_paint) AS inp_n, "
                + "approx_percentile_cont(view_cumulative_layout_shift, 0.75) AS cls, "
                + "count(view_cumulative_layout_shift) AS cls_n, "
                + "approx_percentile_cont(view_loading_time, 0.75) AS load, "
                + "count(view_loading_time) AS load_n, "
                + "approx_percentile_cont(view_first_byte, 0.75) AS ttfb, "
                + "count(view_first_byte) AS ttfb_n "
                + "FROM " + stream() + base + " AND type = 'view'";

        List<Map<String, Object>> rows = run(sql, w, partial);
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);

        List<RumResponseDTO.Vital> out = new ArrayList<>();
        // Order is the order the UI renders them in: the three Core Web Vitals
        // first, then the supporting load metrics.
        Double lcp = nanosToMillis(r.get("lcp"));
        out.add(new RumResponseDTO.Vital("lcp", "Largest Contentful Paint", lcp, "ms",
                rating(lcp, LCP_GOOD, LCP_POOR), lng(r.get("lcp_n"))));
        Double inp = nanosToMillis(r.get("inp"));
        out.add(new RumResponseDTO.Vital("inp", "Interaction to Next Paint", inp, "ms",
                rating(inp, INP_GOOD, INP_POOR), lng(r.get("inp_n"))));
        // CLS is a score, so it is NOT converted — dividing it by 1e6 would report
        // every page as perfect.
        Double cls = num(r.get("cls"));
        out.add(new RumResponseDTO.Vital("cls", "Cumulative Layout Shift", cls, "score",
                rating(cls, CLS_GOOD, CLS_POOR), lng(r.get("cls_n"))));
        Double fcp = nanosToMillis(r.get("fcp"));
        out.add(new RumResponseDTO.Vital("fcp", "First Contentful Paint", fcp, "ms",
                rating(fcp, FCP_GOOD, FCP_POOR), lng(r.get("fcp_n"))));
        // No published threshold for these two, so no rating rather than an invented one.
        out.add(new RumResponseDTO.Vital("loadingTime", "Page Load",
                nanosToMillis(r.get("load")), "ms", null, lng(r.get("load_n"))));
        out.add(new RumResponseDTO.Vital("ttfb", "Time to First Byte",
                nanosToMillis(r.get("ttfb")), "ms", null, lng(r.get("ttfb_n"))));
        return out;
    }

    /**
     * Error and session counters.
     *
     * <p>Two queries, not one. Errors are counted over {@code type='error'} rows and
     * sessions over every row, and a single query filtered to errors would report
     * "total sessions" as "sessions that errored" — which is exactly the pair of
     * numbers this panel exists to compare.</p>
     */
    private RumResponseDTO.PerfCounts readPerfCounts(Window w, String base, boolean[] partial) {
        String errSql = "SELECT count(*) AS total, "
                + "count(distinct session_id) AS sessions_with_errors, "
                + "sum(CASE WHEN error_handling = 'unhandled' THEN 1 ELSE 0 END) AS unhandled "
                + "FROM " + stream() + base + " AND type = 'error'";
        Map<String, Object> e = first(run(errSql, w, partial));

        String sesSql = "SELECT count(distinct session_id) AS sessions FROM " + stream() + base;
        Map<String, Object> s = first(run(sesSql, w, partial));

        return new RumResponseDTO.PerfCounts(lng(e.get("total")), lng(e.get("unhandled")),
                lng(e.get("sessions_with_errors")), lng(s.get(COL_SESSIONS)));
    }

    /**
     * Per-endpoint API timings, from the browser's point of view.
     *
     * <p>Restricted to {@code xhr} and {@code fetch} resources. Including images,
     * CSS and script resources would bury the API calls under static assets, which
     * is a different question and one the Web Vitals already answer.</p>
     */
    private List<RumResponseDTO.ApiCall> readApi(Window w, String base, boolean[] partial) {
        String sql = "SELECT resource_url AS url, resource_method AS method, "
                + "count(*) AS calls, avg(resource_duration) AS avg_d, "
                + "approx_percentile_cont(resource_duration, 0.95) AS p95_d, "
                + "sum(CASE WHEN resource_status_code >= 400 THEN 1 ELSE 0 END) AS errs "
                + "FROM " + stream() + base
                + " AND type = 'resource' AND resource_type IN ('xhr', 'fetch') "
                + "AND resource_url IS NOT NULL "
                + "GROUP BY 1, 2 ORDER BY calls DESC";

        List<RumResponseDTO.ApiCall> out = new ArrayList<>();
        for (Map<String, Object> row : run(sql, w, partial)) {
            out.add(new RumResponseDTO.ApiCall(str(row.get("url")), str(row.get("method")),
                    lng(row.get("calls")), nanosToMillis(row.get("avg_d")),
                    nanosToMillis(row.get("p95_d")), lng(row.get("errs"))));
        }
        return out;
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    // ── /sessions ────────────────────────────────────────────────────────────

    @Override
    public RumResponseDTO.Sessions sessions(String window, String service, String env,
            String version, int page, int size) {
        Window w = resolveWindow(window);
        if (!openObserveEnabled) {
            return new RumResponseDTO.Sessions(unsupported(w),
                    new RumResponseDTO.SessionSummary(0, 0, 0, null, null), List.of(), 0);
        }
        boolean[] partial = { false };
        String base = filterClause(w, service, env, version);

        // One GROUP BY session_id carries everything both the list and the summary
        // need. Paging is applied AFTER, in Java, because the summary has to describe
        // every session in the window and not just the page being shown — a bounce
        // rate computed over 50 of 4,000 sessions is not a bounce rate.
        String sql = "SELECT session_id AS sid, "
                + "min(_timestamp) AS first_ts, max(_timestamp) AS last_ts, "
                + "count(*) AS events, "
                + "sum(CASE WHEN type = 'view' THEN 1 ELSE 0 END) AS views, "
                + "sum(CASE WHEN type = 'error' THEN 1 ELSE 0 END) AS errors, "
                + "sum(CASE WHEN action_frustration_type IS NOT NULL THEN 1 ELSE 0 END) "
                + "AS frustrations, "
                + "max(usr_anonymous_id) AS usr, max(session_type) AS stype, "
                + "max(source) AS src, max(user_agent_user_agent_family) AS browser, "
                + "max(user_agent_os_family) AS os, max(user_agent_device_family) AS device, "
                + "max(view_url) AS last_url "
                + "FROM " + stream() + base + " AND session_id IS NOT NULL "
                + "GROUP BY 1 ORDER BY last_ts DESC";

        List<Map<String, Object>> rows = run(sql, w, partial);

        long withErrors = 0;
        long frustrated = 0;
        long bounced = 0;
        List<Double> durations = new ArrayList<>(rows.size());
        List<RumResponseDTO.SessionRow> all = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            long errors = lng(row.get("errors"));
            long frustrations = lng(row.get("frustrations"));
            long views = lng(row.get("views"));
            // _timestamp is microseconds; the span between first and last event is
            // the session length. Divided by 1000 for ms — NOT the nanosecond
            // divisor used for SDK durations, which is a different unit entirely.
            double durationMs = (num(row.get(COL_LAST_TS)) - num(row.get(COL_FIRST_TS))) / 1000d;

            if (errors > 0) {
                withErrors++;
            }
            if (frustrations > 0) {
                frustrated++;
            }
            if (views <= 1) {
                bounced++;
            }
            durations.add(durationMs);

            all.add(new RumResponseDTO.SessionRow(str(row.get("sid")), str(row.get("usr")),
                    str(row.get("stype")), str(row.get("src")), str(row.get("browser")),
                    str(row.get("os")), str(row.get("device")), views, lng(row.get("events")),
                    errors, frustrations, durationMs,
                    TimeFormatter.microsToIso(lng(row.get(COL_FIRST_TS))),
                    TimeFormatter.microsToIso(lng(row.get(COL_LAST_TS))),
                    str(row.get("last_url"))));
        }

        long total = all.size();
        RumResponseDTO.SessionSummary summary = new RumResponseDTO.SessionSummary(
                total, withErrors, frustrated, median(durations),
                total == 0 ? null : (bounced * 100d) / total);

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int from = Math.max(page, 0) * pageSize;
        List<RumResponseDTO.SessionRow> items = from >= all.size() ? List.of()
                : all.subList(from, Math.min(from + pageSize, all.size()));

        return new RumResponseDTO.Sessions(meta(w, partial[0]), summary,
                new ArrayList<>(items), total);
    }

    /** Median of an unsorted list, or null when empty. Sorts a copy — the caller's
     *  list is parallel to the rows it built and must keep its order. */
    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2d;
    }

    // ── /errors ──────────────────────────────────────────────────────────────

    @Override
    public RumResponseDTO.Errors errors(String window, String service, String env, String version,
            String handling, int page, int size) {
        Window w = resolveWindow(window);
        if (!openObserveEnabled) {
            return new RumResponseDTO.Errors(unsupported(w),
                    new RumResponseDTO.ErrorSummary(0, 0, 0, 0, 0, null), List.of(), List.of(), 0);
        }
        boolean[] partial = { false };
        String base = filterClause(w, service, env, version);
        StringBuilder errWhere = new StringBuilder(base).append(" AND type = 'error'");
        if (handling != null && !handling.isBlank()
                && !"all".equalsIgnoreCase(handling.trim())) {
            errWhere.append(" AND error_handling = '")
                    .append(SqlSafety.escapeStringLiteral(handling.trim().toLowerCase(Locale.ROOT)))
                    .append('\'');
        }
        String errFilter = errWhere.toString();

        List<RumResponseDTO.ErrorIssue> issues = readIssues(w, errFilter, partial);
        RumResponseDTO.ErrorSummary summary = readErrorSummary(w, base, errFilter, issues.size(),
                partial);
        List<RumResponseDTO.ErrorBucket> series = readErrorSeries(w, errFilter, partial);

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int from = Math.max(page, 0) * pageSize;
        List<RumResponseDTO.ErrorIssue> items = from >= issues.size() ? List.of()
                : issues.subList(from, Math.min(from + pageSize, issues.size()));

        return new RumResponseDTO.Errors(meta(w, partial[0]), summary, series,
                new ArrayList<>(items), issues.size());
    }

    /**
     * Distinct issues, grouped by what makes two occurrences "the same problem".
     *
     * <p>Grouped on (type, message, source): the same message thrown from two files
     * is two bugs, and the same file raising two messages is also two bugs. Grouping
     * on the message alone would merge them and report one issue that cannot be
     * fixed in one place.</p>
     */
    private List<RumResponseDTO.ErrorIssue> readIssues(Window w, String errFilter,
            boolean[] partial) {
        String sql = "SELECT error_type AS etype, error_message AS emsg, error_source AS esrc, "
                + "max(error_handling) AS handling, max(error_source_type) AS stype, "
                + "max(service) AS svc, count(*) AS events, "
                + "count(distinct session_id) AS sessions, "
                + "count(distinct usr_anonymous_id) AS users, "
                + "min(_timestamp) AS first_ts, max(_timestamp) AS last_ts "
                + "FROM " + stream() + errFilter
                + " GROUP BY 1, 2, 3 ORDER BY events DESC";

        List<RumResponseDTO.ErrorIssue> out = new ArrayList<>();
        for (Map<String, Object> row : run(sql, w, partial)) {
            out.add(new RumResponseDTO.ErrorIssue(str(row.get("etype")),
                    truncate(str(row.get("emsg"))), str(row.get("handling")),
                    str(row.get("esrc")), str(row.get("stype")), str(row.get("svc")),
                    lng(row.get("events")), lng(row.get("users")), lng(row.get(COL_SESSIONS)),
                    TimeFormatter.microsToIso(lng(row.get(COL_FIRST_TS))),
                    TimeFormatter.microsToIso(lng(row.get(COL_LAST_TS)))));
        }
        return out;
    }

    /**
     * Headline error numbers, including the crash-free rate.
     *
     * <p>The session denominator is counted over ALL RUM rows, not over error rows —
     * "crash-free sessions" is errored-sessions against every session, and using the
     * error-filtered count would make it 0% whenever any error existed.</p>
     */
    private RumResponseDTO.ErrorSummary readErrorSummary(Window w, String base, String errFilter,
            int uniqueIssues, boolean[] partial) {
        String sql = "SELECT count(*) AS total, count(distinct session_id) AS sessions, "
                + "count(distinct usr_anonymous_id) AS users "
                + "FROM " + stream() + errFilter;
        Map<String, Object> e = first(run(sql, w, partial));

        String allSessions = "SELECT count(distinct session_id) AS sessions FROM "
                + stream() + base;
        long totalSessions = lng(first(run(allSessions, w, partial)).get(COL_SESSIONS));

        long affected = lng(e.get(COL_SESSIONS));
        Double crashFree = totalSessions == 0 ? null
                : ((totalSessions - affected) * 100d) / totalSessions;

        return new RumResponseDTO.ErrorSummary(lng(e.get("total")), uniqueIssues,
                lng(e.get("users")), affected, totalSessions, crashFree);
    }

    /**
     * Errors over time, split by whether the page caught them.
     *
     * <p>The bucket width is derived from the window so the series always has about
     * {@link #SERIES_BUCKETS} points — a fixed "5 minute" interval would return six
     * points for a 30-minute window and eight thousand for a month.</p>
     */
    private List<RumResponseDTO.ErrorBucket> readErrorSeries(Window w, String errFilter,
            boolean[] partial) {
        long spanSeconds = Math.max((w.endMicros() - w.startMicros()) / 1_000_000L, 1);
        long bucketSeconds = Math.max(spanSeconds / SERIES_BUCKETS, 1);

        String sql = "SELECT histogram(_timestamp, '" + bucketSeconds + " second') AS bucket, "
                + "sum(CASE WHEN error_handling = 'unhandled' THEN 1 ELSE 0 END) AS unhandled, "
                + "sum(CASE WHEN error_handling = 'unhandled' THEN 0 ELSE 1 END) AS handled "
                + "FROM " + stream() + errFilter + " GROUP BY 1 ORDER BY 1";

        // LinkedHashMap: histogram() already returns ordered buckets and the chart
        // draws them left to right, so insertion order is the contract.
        Map<String, RumResponseDTO.ErrorBucket> byBucket = new LinkedHashMap<>();
        for (Map<String, Object> row : run(sql, w, partial)) {
            String bucket = str(row.get("bucket"));
            if (bucket == null) {
                continue;
            }
            byBucket.put(bucket, new RumResponseDTO.ErrorBucket(bucket,
                    lng(row.get("handled")), lng(row.get("unhandled"))));
        }
        return new ArrayList<>(byBucket.values());
    }
}
