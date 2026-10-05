package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How a request that names ONE trace id is answered — the "Open trace" drill-down
 * from a log row, and the Traces screen's row click.
 *
 * <h2>Why a trace-id lookup needs its own rules</h2>
 *
 * <p>Every other traces query is a question about a time range: "which spans erred
 * in the last hour". A trace id is not — it is a primary key, and the caller
 * already knows the trace exists because it read the id off a log line. So the two
 * defaults that are right for a range query are both wrong here:</p>
 *
 * <ul>
 *   <li><b>The default window.</b> With no {@code startTime} the traces endpoints
 *       fall back to now-12h. The UI deliberately sends no window for a by-id
 *       lookup (see the comment in the frontend's TraceDetail) precisely so that
 *       nothing is dropped — and got a silent 12h clamp anyway. A log older than
 *       12 hours therefore opened an EMPTY trace view, and the Logs screen's own
 *       time picker offers windows up to six months.</li>
 *   <li><b>Page size.</b> {@code GET /api/traces} paginates by SPAN and then
 *       assembles the surviving spans into a tree. Asking for one trace with the
 *       default page therefore returned an arbitrary slice of it: spans are ordered
 *       newest-first, so the slice that gets dropped is the OLDEST — which is the
 *       root span. Measured on this deployment: a 232-span trace came back as 100
 *       spans whose root was a {@code SELECT} and whose total duration read
 *       1.8 ms.</li>
 * </ul>
 *
 * <p>Both are fixed by treating "one named trace" as its own kind of request. The
 * numbers live here rather than in the query builders so neither is a literal
 * buried in a SQL string, and so a deployment whose retention or trace sizes
 * differ can change them without a rebuild.</p>
 *
 * <p>Bound by the package-wide component scan ({@code ObservabilityAutoConfig})
 * rather than an {@code @EnableConfigurationProperties} on a feature
 * configuration: traces are always on, so there is no feature gate for this to
 * inherit.</p>
 */
@Component
@ConfigurationProperties(prefix = "traces.lookup")
public class TraceLookupProperties {

    /**
     * Lookback windows tried when a request names a trace id and the caller gave no
     * explicit time range, together with the stream's own extent. They are tried
     * NARROWEST first, whatever order they are listed in, and the first window that
     * holds the trace's root span wins (see {@link #getRootMarginSeconds()}); an
     * explicit {@code startTime}/{@code endTime} from the caller always takes
     * precedence over this list entirely.
     *
     * <h3>Why a ladder and not one wide window</h3>
     *
     * <p>Widest-first is what makes an old trace openable at all. The narrowing
     * retries exist because this OpenObserve instance cannot always serve a wide
     * scan of the large stream: it answers HTTP 200 with zero hits and a
     * {@code {"code":20006,"message":"Search parquet file not found"}}
     * function_error, which is indistinguishable from "that trace does not exist".
     * The same measured behaviour is why Product Catalog validation carries
     * {@code product-catalog.validation.fallback-windows}; this is that idiom
     * pointed the other way. Without the retries, a deployment that fails a 7d
     * scan would fail to open even a trace from a minute ago.</p>
     *
     * <p>The cost of the ladder is bounded and only paid on a miss: the first
     * window that holds the root ends it, so opening a recent trace is one narrow
     * query. Widest-first used to make every open pay for the full-extent scan,
     * which this instance fails after about 4 s. Set to a single entry to disable
     * retrying.</p>
     *
     * <p>Mutable and non-empty by default for the reason spelled out on
     * {@link AlertDisplayProperties#getLimitFields()} — Spring's relaxed binding
     * may populate a collection by mutating what the getter returns, so an
     * immutable {@code List.of(…)} default would break any deployment that
     * overrides it.</p>
     */
    private List<String> windows = new ArrayList<>(List.of(
            "now-7d",
            "now-24h",
            "now-6h",
            "now-1h"));

    /**
     * Safety ceiling on spans pulled back for ONE named trace.
     *
     * <p>Not a page size — a by-id lookup is not paginated, because half a trace is
     * not a smaller trace, it is a wrong one (see the class note). This is the
     * backstop that stops a pathological trace from becoming an unbounded read.
     * 10,000 is the same ceiling the aggregate queries in
     * {@code TraceAnalyticsServiceImpl} use; the largest trace measured on this
     * deployment is 232 spans.</p>
     */
    private int maxSpans = 10_000;

    /**
     * How far inside a window a trace's root span must start before that window's
     * answer is accepted as the whole trace, in seconds.
     *
     * <p>Lookups try the windows above narrowest first (see
     * {@link com.trinity.observability.util.TraceLookupWindow#narrowestComplete}).
     * A window holding the root holds every span, because children start after
     * their root — except when service clocks disagree, which can put a child a
     * little before it. This margin absorbs that skew; a root closer than this to
     * the window's start sends the lookup on to the next, wider window.</p>
     */
    private int rootMarginSeconds = 300;

    public List<String> getWindows() {
        return windows;
    }

    public void setWindows(List<String> windows) {
        this.windows = windows == null ? new ArrayList<>() : new ArrayList<>(windows);
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = Math.max(1, maxSpans);
    }

    public int getRootMarginSeconds() {
        return rootMarginSeconds;
    }

    public void setRootMarginSeconds(int rootMarginSeconds) {
        this.rootMarginSeconds = Math.max(0, rootMarginSeconds);
    }

    /** {@link #getRootMarginSeconds()} in the microseconds the trace queries use. */
    public long rootMarginMicros() {
        return rootMarginSeconds * 1_000_000L;
    }
}
