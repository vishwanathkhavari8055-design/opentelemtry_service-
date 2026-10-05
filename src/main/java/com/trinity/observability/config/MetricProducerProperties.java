package com.trinity.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cost controls for the service-to-metrics index.
 *
 * <h2>Why this index has to be built rather than queried</h2>
 *
 * <p>"Which metrics does this service expose?" cannot be asked of this backend
 * directly. Verified against the live instance on 2026-09-03:</p>
 *
 * <ul>
 *   <li>{@code /series?match[]={service_name="tiotapi"}} → HTTP 400,
 *       "match[] argument must start with a metric name".</li>
 *   <li>{@code group by (__name__) ({service_name=~"tiotapi"})} → HTTP 400,
 *       "VectorSelector: metric name is required".</li>
 *   <li>{@code count by (service_name) ({__name__=~".+"})} → HTTP 200 with an
 *       empty result, the silent-failure shape this deployment specialises in.</li>
 *   <li>Repeating {@code match[]} to batch several metrics into one call → HTTP
 *       400, "duplicate field match[]".</li>
 * </ul>
 *
 * <p>Every reverse-index query shape is refused: the engine requires a literal
 * metric name. So the index is built the only way available — one query per
 * metric name, {@code count by (service_name) (<metric>)}, inverted in Java.</p>
 *
 * <h2>What that costs, measured</h2>
 *
 * <p>{@code count by (service_name)} rather than {@code /series} is what makes
 * this affordable: for {@code kube_pod_status_phase} the aggregate answers in
 * 2.4 KB where the raw series list is 622 KB, because the engine collapses the
 * label sets before serialising. Measured over all 3,215 metrics at ten threads:
 * 90 seconds, zero errors, a few megabytes total.</p>
 *
 * <p>Ninety seconds is far too long for a request to wait, so the build is
 * asynchronous: the first call starts it and reports progress, later calls get
 * the cached answer. {@link #getTtlSeconds()} decides when it is rebuilt.</p>
 */
@Component
@ConfigurationProperties(prefix = "metrics.producers")
public class MetricProducerProperties {

    /**
     * Master switch. Off leaves {@code /api/metrics/producers} answering
     * {@code supported: false} and never touches OpenObserve — for a deployment
     * where a 3,000-query sweep is unwelcome regardless of how it is paced.
     */
    private boolean enabled = true;

    /**
     * Window each probe looks back over, in seconds.
     *
     * <p>One hour, and NOT shorter. Cost is nearly flat in window width
     * (measured: 15m and 1h answer within 30ms of each other, because the
     * aggregate is collapsed either way), while completeness is not — the
     * collectors here are lumpy, some writing once every twelve minutes, and
     * one metric stream was observed 44 minutes behind. A narrow window
     * therefore buys nothing and silently drops slow producers, which in this
     * screen reads as "that service exposes no metrics".</p>
     */
    private int windowSeconds = 3600;

    /**
     * Step for the range query, in seconds.
     *
     * <p>Coarse on purpose: the values are thrown away and only the label sets
     * are kept, so this exists purely to keep the response small. It must stay
     * fine enough that a lumpy producer lands on at least one grid point.</p>
     */
    private int stepSeconds = 300;

    /**
     * Concurrent probes.
     *
     * <p>Ten was measured clean end-to-end (3,215 probes, no errors, no
     * throttling). Raising it shortens a build that already runs in the
     * background; the reason not to is that OpenObserve also has to serve the
     * screens someone is looking at while this runs.</p>
     */
    private int concurrency = 10;

    /**
     * How long a built index is served before a refresh is triggered.
     *
     * <p>What this index describes — which services exist and what they expose —
     * changes on deploys, not on the minute, and a stale answer here is labelled
     * with its age rather than hidden.</p>
     *
     * <p><b>It must comfortably exceed how long a sweep takes</b>, and that is
     * what raised it from the original ten minutes. The sweep was measured at
     * 3,215 metrics in 90 seconds; this deployment's catalogue has since grown
     * to ~19,500, so a sweep now runs about ten minutes — at a ten-minute TTL
     * the next rebuild begins the moment the last one ends, and the index is
     * never not being rebuilt. Half an hour leaves roughly twenty quiet minutes
     * between sweeps while keeping the answer well inside a deploy cycle.</p>
     */
    private int ttlSeconds = 1800;

    /** Per-probe HTTP timeout, seconds. A probe that trips it is retried once. */
    private int probeTimeoutSeconds = 30;

    /**
     * Whether to start the first build at boot instead of on the first request.
     *
     * <p>On, because "nobody opens this screen" stopped being true: the alert
     * editor reads this index every time somebody picks a service, to narrow the
     * metric picker to what that service actually emits. Without a prewarm the
     * first person to write an alert after a restart waits out the build, and
     * meanwhile is shown every metric in the instance — which is the exact
     * failure the narrowing exists to prevent.</p>
     *
     * <p>The cost is a boot that fires a probe per catalogue metric in the
     * background. Turn it off on a deployment that restarts constantly, or one
     * where nothing reads the index.</p>
     */
    private boolean prewarm = true;

    public boolean isEnabled()            { return enabled; }
    public int getWindowSeconds()         { return windowSeconds; }
    public int getStepSeconds()           { return stepSeconds; }
    public int getConcurrency()           { return concurrency; }
    public int getTtlSeconds()            { return ttlSeconds; }
    public int getProbeTimeoutSeconds()   { return probeTimeoutSeconds; }
    public boolean isPrewarm()            { return prewarm; }
    public void setEnabled(boolean v)          { this.enabled = v; }
    public void setWindowSeconds(int v)        { this.windowSeconds = v; }
    public void setStepSeconds(int v)          { this.stepSeconds = v; }
    public void setConcurrency(int v)          { this.concurrency = v; }
    public void setTtlSeconds(int v)           { this.ttlSeconds = v; }
    public void setProbeTimeoutSeconds(int v)  { this.probeTimeoutSeconds = v; }
    public void setPrewarm(boolean v)          { this.prewarm = v; }
}
