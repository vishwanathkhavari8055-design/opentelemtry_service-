package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves the time range for a lookup keyed by trace id, by trying a ladder of
 * lookback windows until one returns rows.
 *
 * <p>Lives here, shared, because two endpoints answer "open this trace" —
 * {@code GET /api/traces/spans} (the current UI) and {@code GET /api/traces} (the
 * packaged web-component build) — and a trace that opens from one and not the
 * other is the bug this is fixing. The reasoning for the ladder itself is on
 * {@link com.trinity.observability.config.TraceLookupProperties#getWindows()}.</p>
 *
 * <p>Stateless and side-effect free apart from logging: the caller supplies the
 * search as a lambda, so this class knows nothing about SQL, streams or
 * pagination.</p>
 */
public final class TraceLookupWindow {

    private static final Logger log = LogManager.getLogger(TraceLookupWindow.class);

    /** OpenObserve span columns {@link #narrowestComplete} reads to recognise a root span. */
    private static final String PARENT_SPAN_COLUMN = "reference_parent_span_id";
    private static final String TIMESTAMP_COLUMN = "_timestamp";

    private TraceLookupWindow() {}

    /** One windowed search attempt. */
    @FunctionalInterface
    public interface WindowedSearch {
        List<Map<String, Object>> run(long startMicros, long endMicros);
    }

    /**
     * The window that answered, and what it returned.
     *
     * <p>The window is part of the result because callers need it afterwards: the
     * assembled-tree endpoint correlates log events over the SAME range it found
     * the spans in, and the spans endpoint counts its whole-window totals over it.
     * Recomputing it independently is how the two drift apart.</p>
     */
    public record Resolved(long startMicros, long endMicros, List<Map<String, Object>> hits) {}

    /**
     * Run {@code search} against each window expression in turn and return the
     * first attempt that produced any row.
     *
     * <p>All attempts share ONE end instant. Re-reading the clock per attempt would
     * let the window creep between retries, which is unnoticeable in a list query
     * and precisely wrong when the point is to decide whether a specific trace is
     * inside the range.</p>
     *
     * <p>When every window comes back empty the FIRST (widest) attempt is returned,
     * so an empty answer is reported against the widest range actually tried rather
     * than the narrowest. A malformed expression is logged and skipped instead of
     * failing the request — a typo in configuration should not turn the trace view
     * into a 500. If no expression is usable at all, the shared
     * {@link TimeRangeParser} defaults apply, so the behaviour degrades to what it
     * was before this class existed rather than to something undefined.</p>
     */
    public static Resolved firstNonEmpty(List<String> windowExprs, WindowedSearch search) {
        return firstNonEmpty(null, windowExprs, search);
    }

    /**
     * As {@link #firstNonEmpty(List, WindowedSearch)}, but tries the stream's OWN
     * reported extent first.
     *
     * <p>This is the window that involves no guessing at all: {@code streamMin} is
     * OpenObserve's {@code stats.doc_time_min} for the stream being queried, so the
     * first attempt covers exactly the data that exists — it follows retention as
     * data ages out, and no constant in this codebase decides whether a trace is
     * old enough to be unopenable. The configured expressions remain as fallbacks
     * for the case the derived window cannot be served, and for a deployment whose
     * metadata reports nothing usable ({@code streamMin} null).</p>
     *
     * <p>The lower bound is nudged back by one second: {@code doc_time_min} is the
     * oldest row's timestamp, and an inclusive bound exactly equal to it has no
     * margin for the µs/ms rounding between the metadata and search APIs.</p>
     */
    public static Resolved firstNonEmpty(Long streamMin, List<String> windowExprs,
                                         WindowedSearch search) {
        long endMicros = TimeRangeParser.defaultEndMicros();

        Resolved widestEmpty = null;

        if (streamMin != null && streamMin > 0L) {
            Resolved fromExtent = attempt(streamMin - 1_000_000L, endMicros, search);
            if (!fromExtent.hits().isEmpty()) {
                return fromExtent;
            }
            widestEmpty = fromExtent;
        }

        Resolved resolved = firstNonEmptyConfigured(windowExprs, endMicros, search, widestEmpty);
        if (resolved != null) return resolved;

        log.warn("traces.lookup.windows produced no usable window — falling back to the "
                + "default range");
        return new Resolved(TimeRangeParser.defaultStartMicros(), endMicros, Collections.emptyList());
    }

    /**
     * Narrowest window first, widening only while the trace is not yet known to be
     * whole — the fast path for "Open trace".
     *
     * <h3>Why not widest first</h3>
     *
     * <p>{@link #firstNonEmpty(Long, List, WindowedSearch)} starts from the stream's
     * whole extent. On this deployment that scan covers months, cannot be served
     * (200, zero hits, {@code "Search parquet file not found"}) and still costs about
     * 4 s before the ladder moves on — so EVERY trace open paid that, plus a failing
     * 7d attempt, before reaching a window that answered in 40 ms. Almost every
     * trace anyone opens is recent, so the narrow window is the one that answers.</p>
     *
     * <h3>Why a narrow answer can be trusted</h3>
     *
     * <p>A narrow window could cut a trace in half, which is exactly what a by-id
     * lookup must never do. It cannot when the window holds the trace's ROOT span
     * (no parent): every other span starts after the root, and every window here
     * ends now, so all of them are inside. The root must also sit at least
     * {@code rootMarginMicros} after the window's start, to absorb clock skew
     * between services that would otherwise put a child just before its root.
     * Without such a root — a trace whose root was never ingested — the ladder keeps
     * widening and the attempt with the most spans wins (the wider one on a tie),
     * which is what the widest-first ladder returned whenever its wide scans could
     * be served. All empty: the widest empty attempt, as before.</p>
     *
     * <p>The stream's extent ({@code streamMin}, may be null) joins the configured
     * expressions as just another window; all of them are ordered by width, so the
     * configuration's own order no longer matters.</p>
     */
    public static Resolved narrowestComplete(Long streamMin, List<String> windowExprs,
                                             long rootMarginMicros, WindowedSearch search) {
        long endMicros = TimeRangeParser.defaultEndMicros();
        List<Long> starts = narrowestFirst(streamMin, windowExprs);
        if (starts.isEmpty()) {
            log.warn("traces.lookup.windows produced no usable window — falling back to the "
                    + "default range");
            return new Resolved(TimeRangeParser.defaultStartMicros(), endMicros, Collections.emptyList());
        }

        Resolved mostSpans = null;
        Resolved widest = null;
        for (long startMicros : starts) {
            Resolved result = attempt(startMicros, endMicros, search);
            if (holdsRoot(result, rootMarginMicros)) {
                return result;
            }
            widest = result;
            mostSpans = moreSpans(mostSpans, result);
        }
        log.info("Trace lookup: no window held the trace's root span after {} attempt(s)",
                starts.size());
        return mostSpans != null ? mostSpans : widest;
    }

    /**
     * Window starts, newest (narrowest) first. A repeated expression is tried once —
     * de-duplicated by its text, because each parse reads the clock and so two
     * parses of {@code now-1h} differ by microseconds.
     */
    private static List<Long> narrowestFirst(Long streamMin, List<String> windowExprs) {
        List<Long> starts = new ArrayList<>();
        if (streamMin != null && streamMin > 0L) {
            starts.add(streamMin - 1_000_000L);
        }
        Set<String> seen = new HashSet<>();
        for (String expr : orEmpty(windowExprs)) {
            Long startMicros = firstSightingStart(expr, seen);
            if (startMicros != null) {
                starts.add(startMicros);
            }
        }
        starts.sort(Comparator.reverseOrder());
        return starts;
    }

    /**
     * Start of {@code expr}'s window the first time its trimmed text is seen; null for a
     * blank, repeated or unparseable expression. {@code seen} records the text.
     */
    private static Long firstSightingStart(String expr, Set<String> seen) {
        return expr == null || expr.isBlank() || !seen.add(expr.trim())
                ? null : parseWindowStart(expr);
    }

    /** Start of {@code expr}'s window; null for a blank or unparseable expression. */
    private static Long configuredStart(String expr) {
        return expr == null || expr.isBlank() ? null : parseWindowStart(expr);
    }

    private static List<String> orEmpty(List<String> windowExprs) {
        return windowExprs == null ? List.of() : windowExprs;
    }

    /**
     * True when the attempt returned a root span (blank parent id) that started at
     * least {@code marginMicros} after the attempt's window opened.
     */
    private static boolean holdsRoot(Resolved result, long marginMicros) {
        long notBefore = result.startMicros() + marginMicros;
        for (Map<String, Object> hit : result.hits()) {
            Object parent = hit.get(PARENT_SPAN_COLUMN);
            if ((parent == null || parent.toString().isBlank())
                    && hit.get(TIMESTAMP_COLUMN) instanceof Number ts
                    && ts.longValue() >= notBefore) {
                return true;
            }
        }
        return false;
    }

    /** The non-empty attempt with more spans; {@code candidate} (the wider) wins a tie. */
    private static Resolved moreSpans(Resolved best, Resolved candidate) {
        if (candidate.hits().isEmpty()) return best;
        return best == null || candidate.hits().size() >= best.hits().size() ? candidate : best;
    }

    /**
     * Walks the configured ladder. Returns the first attempt with rows, otherwise
     * the widest empty attempt (which is {@code widestEmpty} when the caller already
     * has one), or null when there was no attempt at all.
     */
    private static Resolved firstNonEmptyConfigured(List<String> windowExprs, long endMicros,
                                                    WindowedSearch search, Resolved widestEmpty) {
        for (String expr : orEmpty(windowExprs)) {
            Long startMicros = configuredStart(expr);
            if (startMicros == null) continue;

            Resolved result = attempt(startMicros, endMicros, search);
            if (!result.hits().isEmpty()) {
                logIfLateAnswer(widestEmpty, windowExprs, expr);
                return result;
            }

            if (widestEmpty == null) {
                widestEmpty = result;
            }
        }
        return widestEmpty;
    }

    /**
     * Only interesting when an earlier, WIDER window came back empty —
     * that is the signature of a scan the instance could not serve, and
     * it is otherwise invisible.
     */
    private static void logIfLateAnswer(Resolved widestEmpty, List<String> windowExprs, String expr) {
        if (widestEmpty != null) {
            log.info("Trace lookup: window '{}' answered after {} wider window(s) "
                    + "returned nothing", expr, countedAttempts(windowExprs, expr));
        }
    }

    /** Start of the window {@code expr} describes, or null (logged) when it cannot be parsed. */
    private static Long parseWindowStart(String expr) {
        try {
            return TimeRangeParser.toMicros(expr.trim(), TimeRangeParser.defaultStartMicros());
        } catch (RuntimeException e) {
            log.warn("traces.lookup.windows: ignoring unparseable window '{}': {}",
                    expr, e.getMessage());
            return null;
        }
    }

    /** One search over [start, end]; a null or empty answer is normalised to an empty list. */
    private static Resolved attempt(long startMicros, long endMicros, WindowedSearch search) {
        List<Map<String, Object>> hits = search.run(startMicros, endMicros);
        if (hits != null && !hits.isEmpty()) {
            return new Resolved(startMicros, endMicros, hits);
        }
        return new Resolved(startMicros, endMicros, Collections.emptyList());
    }

    /** Position of {@code expr} in the ladder, for the log line above. */
    private static int countedAttempts(List<String> windowExprs, String expr) {
        int i = windowExprs.indexOf(expr);
        return i < 0 ? 0 : i;
    }
}
