package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Which OpenObserve streams the CPU / Memory / Disk / NFS Storage tiles read.
 *
 * <h2>Why every stream and column is configuration</h2>
 *
 * <p>These four numbers come from the OpenTelemetry collector, and which streams
 * carry them depends on which receivers a deployment enables: {@code system_*}
 * exists only where the hostmetrics receiver runs, {@code k8s_*} only where the
 * kubeletstats receiver does. Hard-coding either would make the tiles blank on a
 * cluster that is scraping the other one — and blank is exactly the symptom this
 * class exists to prevent, so the mapping has to be changeable without a
 * rebuild.</p>
 *
 * <h2>Two shapes of tile, and why they differ</h2>
 *
 * <p>CPU and Memory are POD-scoped and defined by one statement each
 * ({@link Cpu#getSql()}, {@link Memory#getSql()}) which returns a single row of
 * {@link #COLUMN_PCT} / {@link #COLUMN_DETAIL}. Disk and NFS Storage are
 * CLUSTER-scoped and assembled in Java from grouped rows, configured stream and
 * column at a time.</p>
 *
 * <p>The split is not an inconsistency waiting to be tidied. Disk and NFS have to
 * be built up in Java because their arithmetic is not expressible as one
 * aggregate: a device bind-mounted twice has to be counted once, pseudo
 * filesystems have to be dropped by type, and claims sharing an export have to be
 * folded together. CPU and Memory have no such structure — they are one
 * percentage against one limit — so a statement says it more directly, and says
 * it in a place an operator can change.</p>
 *
 * <h2>The window ladder, and why NFS gets its own</h2>
 *
 * <p>Every tile reads the NEWEST data it can find by trying {@link #getWindows()}
 * narrowest first and stopping at the first rung that returns rows. A narrow
 * window is what makes the number current; the wider rungs exist because a
 * receiver that scrapes slowly would otherwise render as "no data".</p>
 *
 * <p>That is not hypothetical here. Measured on this deployment: {@code system_*}
 * lands roughly every two minutes, while {@code k8s_volume_*} was last written
 * nineteen minutes ago. A single five-minute window across all four tiles shows
 * live CPU, Memory and Disk next to an empty NFS Storage tile, which is why
 * {@link Nfs} carries its own wider ladder.</p>
 *
 * <p>Bound by the package-wide component scan rather than an
 * {@code @EnableConfigurationProperties} on a feature configuration, matching
 * {@code TraceLookupProperties}: there is no feature gate for this to inherit.</p>
 */
@Component
@ConfigurationProperties(prefix = "infrastructure")
public class InfrastructureProperties {

    /**
     * CPU: percentage of the pod's CPU limit, captioned with the cores.
     *
     * <h3>Three decisions here, each measured, each fixing a wrong screen</h3>
     *
     * <p><b>LAST_VALUE, not AVG.</b> These streams are written every few minutes,
     * so a window mean is a mean over the last N minutes of history and it barely
     * moves between polls — the screen looks frozen even though it is re-reading
     * every 20 seconds. Measured on the same data: the window mean over an hour
     * read 29.4% while the newest sample was 14.8%. The mean was not a smoothed
     * version of the truth, it was a different number describing the past.</p>
     *
     * <p><b>The newest pod only</b> ({@code ORDER BY MAX(_timestamp) DESC LIMIT 1}).
     * Measured: {@code tiotopenobserve} was replaced 18 times in 24 hours, each
     * time with a new ReplicaSet hash and therefore a new pod name. A window wide
     * enough to find the current pod also contains all its dead predecessors, and
     * aggregating across them mixes today's reading with pods that no longer
     * exist — summing gave 543%. Grouping by pod and keeping the freshest is what
     * makes a wide window safe.</p>
     *
     * <p><b>{@code k8s_pod_cpu_usage}, not {@code k8s_pod_cpu_limit_utilization}.</b>
     * The percentage is divided out here instead of read ready-made, because the
     * ready-made stream is the less reliable of the two — it stalls independently
     * of the usage stream, and when it does, a query needing both blanks the card
     * for no reason. One live measurement plus the configured limit yields BOTH the
     * percentage and the caption, so the card needs one fewer stream to be alive.</p>
     *
     * <p>Together these make the result INDEPENDENT of window width — verified
     * identical at 2h, 6h and 24h — so the ladder decides only whether data is
     * found, never what the number is.</p>
     *
     * <p>Verified against the target OpenObserve before being made the default;
     * {@code LAST_VALUE(... ORDER BY ...)} and a cross join of scalar aggregates
     * are not something every build will plan, which is why this is a property
     * rather than a constant in the service.</p>
     */
    private static final String DEFAULT_CPU_SQL = """
            SELECT ROUND(u.used_cores / l.lim_cores * 100, 1) AS pct, \
            CONCAT(CAST(ROUND(u.used_cores, 2) AS VARCHAR), ' / ', \
            CAST(CAST(l.lim_cores AS BIGINT) AS VARCHAR), ' cores') AS detail, \
            u.last_seen AS last_seen \
            FROM (SELECT LAST_VALUE(value ORDER BY _timestamp) AS used_cores, \
            MAX(_timestamp) AS last_seen \
            FROM "k8s_pod_cpu_usage" \
            WHERE k8s_pod_name LIKE 'tiotopenobserve%' \
            GROUP BY k8s_pod_name \
            ORDER BY MAX(_timestamp) DESC LIMIT 1) AS u, \
            (SELECT MAX(value) AS lim_cores FROM "kube_pod_container_resource_limits" \
            WHERE pod LIKE 'tiotopenobserve%' AND resource = 'cpu') AS l""";

    /**
     * Memory: percentage of the pods' memory limit, captioned with the gigabytes.
     *
     * <p>GiB throughout — {@code / 1073741824.0}, i.e. 1024-based — because that is
     * what OpenObserve's own "bytes" unit and the Disk/NFS cards use. Decimal GB
     * would print different numbers from the dashboards this screen sits next to,
     * and a reader comparing them would reasonably conclude one is broken.</p>
     *
     * <p>The limit is {@code ROUND(...) AS BIGINT} rather than {@code ROUND(..., 0)}:
     * both give 15, but the latter renders it {@code "15.0"}, which reads as a
     * measured quantity when it is a configured constant. Rounded BEFORE the cast,
     * because a cast alone truncates — a 15.9 GiB limit would print as 14.</p>
     *
     * <p>Same three decisions as {@link Cpu#getSql()} — newest sample, newest pod,
     * one live stream — but in the opposite direction: here the RATIO is the live
     * measurement and the gigabytes are derived from it
     * ({@code frac * lim_gb}), because {@code k8s_pod_memory_limit_utilization}
     * keeps being written while {@code container_memory_working_set_bytes} stalls.
     * That is not an approximation: the ratio IS working-set-over-limit, so
     * multiplying it back by the limit recovers the working set exactly — and, being
     * one reading rather than two, the caption cannot disagree with the percentage
     * above it.</p>
     */
    private static final String DEFAULT_MEMORY_SQL = """
            SELECT ROUND(u.frac * 100, 1) AS pct, \
            CONCAT(CAST(ROUND(u.frac * l.lim_gb, 2) AS VARCHAR), ' / ', \
            CAST(CAST(ROUND(l.lim_gb, 0) AS BIGINT) AS VARCHAR), ' GB') AS detail, \
            u.last_seen AS last_seen \
            FROM (SELECT LAST_VALUE(value ORDER BY _timestamp) AS frac, \
            MAX(_timestamp) AS last_seen \
            FROM "k8s_pod_memory_limit_utilization" \
            WHERE k8s_pod_name LIKE 'tiotopenobserve%' \
            GROUP BY k8s_pod_name \
            ORDER BY MAX(_timestamp) DESC LIMIT 1) AS u, \
            (SELECT MAX(value) / 1073741824.0 AS lim_gb \
            FROM "kube_pod_container_resource_limits" \
            WHERE pod LIKE 'tiotopenobserve%' AND resource = 'memory') AS l""";

    /**
     * The contract every {@code sql} property above has to meet.
     *
     * <p>CPU and Memory are read by running ONE statement and taking ONE row from
     * it, rather than by grouping rows in Java. The statement must project:</p>
     *
     * <ul>
     *   <li>{@link #COLUMN_PCT} — 0–100. The tile's whole reason for existing; a
     *       row whose {@code pct} is null counts as no reading at all, because a
     *       percentage is the one thing the card cannot render around.</li>
     *   <li>{@link #COLUMN_DETAIL} — the caption under the percentage, e.g.
     *       {@code "1.59 / 5 cores"}. Optional. Formatted in SQL on purpose: it
     *       carries the numerator and denominator the percentage came from, and
     *       keeping that wording next to the arithmetic that produced it is what
     *       stops the two drifting apart.</li>
     *   <li>{@link #COLUMN_LAST_SEEN} — {@code MAX(_timestamp)}, epoch micros.
     *       Optional but strongly wanted: these collectors stop for long stretches,
     *       and without it the screen cannot tell a live reading from an old
     *       one.</li>
     * </ul>
     *
     * <p>NO {@code _timestamp} predicate belongs in the statement. The window is
     * applied by OpenObserve's search API from {@code start_time}/{@code end_time},
     * which is what lets one statement be re-run over each rung of
     * {@link #getWindows()} without string surgery.</p>
     */
    public static final String COLUMN_PCT = "pct";

    /** @see #COLUMN_PCT */
    public static final String COLUMN_DETAIL = "detail";

    /** @see #COLUMN_PCT */
    public static final String COLUMN_LAST_SEEN = "last_seen";

    /**
     * CPU fallback for an OpenObserve that will not plan {@code LAST_VALUE}.
     *
     * <p>A window MEAN, i.e. what {@link #DEFAULT_CPU_SQL} deliberately is not. It
     * moves sluggishly and smears pod churn, so it is strictly the worse reading —
     * but a sluggish number beats a blank card, and this file already makes exactly
     * that trade for the grouped tiles ({@code value-aggregate=AVERAGE}).</p>
     *
     * <p>{@code LAST_VALUE(... ORDER BY ...)} is verified on the target deployment
     * but is version-dependent ({@code MAX_BY}, for instance, is absent there), and
     * the service cannot rewrite an opaque operator-supplied statement the way it
     * rewrites its own generated SQL. So the fallback has to be a whole statement.</p>
     */
    private static final String FALLBACK_CPU_SQL =
            "SELECT ROUND(c.used_cores / l.lim_cores * 100, 1) AS pct,             CONCAT(CAST(ROUND(c.used_cores, 2) AS VARCHAR), ' / ',             CAST(CAST(l.lim_cores AS BIGINT) AS VARCHAR), ' cores') AS detail,             c.last_seen AS last_seen             FROM (SELECT AVG(value) AS used_cores, MAX(_timestamp) AS last_seen             FROM \"k8s_pod_cpu_usage\"             WHERE k8s_pod_name LIKE 'tiotopenobserve%') AS c,             (SELECT MAX(value) AS lim_cores FROM \"kube_pod_container_resource_limits\"             WHERE pod LIKE 'tiotopenobserve%' AND resource = 'cpu') AS l";

    /** Memory fallback. See {@link #FALLBACK_CPU_SQL}. */
    private static final String FALLBACK_MEMORY_SQL =
            "SELECT ROUND(u.pct, 1) AS pct,             CONCAT(CAST(ROUND(u.pct / 100.0 * l.lim_gb, 2) AS VARCHAR), ' / ',             CAST(CAST(ROUND(l.lim_gb, 0) AS BIGINT) AS VARCHAR), ' GB') AS detail,             u.last_seen AS last_seen             FROM (SELECT AVG(value) * 100 AS pct, MAX(_timestamp) AS last_seen             FROM \"k8s_pod_memory_limit_utilization\"             WHERE k8s_pod_name LIKE 'tiotopenobserve%') AS u,             (SELECT MAX(value) / 1073741824.0 AS lim_gb             FROM \"kube_pod_container_resource_limits\"             WHERE pod LIKE 'tiotopenobserve%' AND resource = 'memory') AS l";

    private static final String WINDOW_NOW_30M = "now-30m";
    private static final String SCOPE_TIOTOPENOBSERVE_PODS = "tiotopenobserve pods";

    /**
     * Lookback ladder for the POD tiles (CPU, Memory).
     *
     * <p>Wide on purpose, and safe to be wide only because those statements return
     * the newest sample of the newest pod: the answer is identical at 2h, 6h and 24h
     * (verified), so widening cannot change the number — it can only find data that
     * a narrower window missed.</p>
     *
     * <p>It has to be wide. Measured on this deployment: the {@code tiotopenobserve}
     * pod's metrics went 44 minutes without a write while every other pod in the
     * cluster stayed 4 minutes fresh, and the pod itself was replaced 18 times in a
     * day. Against that, a 15-minute window is a coin flip, and losing it means a
     * blank card rather than a slightly older number.</p>
     */
    private static final List<String> POD_WINDOWS = List.of("now-2h", "now-6h", "now-24h");

    /** Master switch. False makes the endpoint answer with every tile unavailable. */
    private boolean enabled = true;

    /**
     * The OpenObserve org these metrics actually live in. Blank = {@code openobserve.org}.
     *
     * <p>Load-bearing, and the fix for a screen that went blank on every tenant but
     * one. Every other read in this service is scoped to the REQUESTING org, which
     * is right for logs and traces — those are the tenant's data. These four tiles
     * are not: a node's disk and the platform pods' CPU are facts about the cluster
     * that runs OpenObserve, ingested once, into one org.</p>
     *
     * <p>Measured on this deployment: all five streams behind CPU and Memory —
     * and {@code system_filesystem_usage} and {@code k8s_volume_capacity} behind
     * Disk and NFS — exist in {@code default} and in NO other org. Following the
     * request's org therefore produced four "the collector may not be running"
     * cards for anyone viewing the {@code DLH}, {@code DLHLnM} or {@code ollama}
     * tenants, while the collectors were in fact running perfectly.</p>
     *
     * <p>Set this only where the collector writes its infrastructure metrics to a
     * dedicated org rather than the default one.</p>
     */
    private String metricsOrg = "";

    /**
     * How long an answer is served from cache before OpenObserve is re-queried.
     *
     * <p>Short on purpose — the whole point of the tiles is that they are current.
     * It exists only so a screen polling every second cannot turn into a query per
     * second per viewer; {@code ?refresh=true} bypasses it. The collectors write
     * every couple of minutes, so nothing is gained by going below a few seconds.
     * 0 disables caching entirely.</p>
     */
    private int cacheSeconds = 15;

    /**
     * Lookback windows tried in order, NARROWEST first.
     *
     * <p>A tile keeps widening for as long as widening reveals more of the
     * cluster, and stops at the first rung that adds nothing. See
     * {@link #getStaleAfterSeconds()} for what stops that climb from running away.</p>
     *
     * <p>Starts at fifteen minutes rather than five because five is measurably too
     * narrow here: only one of the eight nodes reports that often. Keeping a
     * narrower first rung would just cost a query that never wins.</p>
     *
     * <p>Mutable and non-empty by default deliberately: Spring's relaxed binding
     * may populate a collection by mutating what the getter returns, so an
     * immutable {@code List.of(…)} default would break any deployment that
     * overrides it.</p>
     */
    private List<String> windows = new ArrayList<>(List.of("now-15m", WINDOW_NOW_30M, "now-1h"));

    /**
     * A series whose newest sample is older than this is dropped entirely.
     *
     * <p>This is what makes the coverage climb terminate, and it is the difference
     * between an aggregate about the cluster and one about its history. Measured
     * on this deployment: a one-hour window reveals a ninth node that last
     * reported 54 minutes ago, and a six-hour window reveals TEN more that last
     * reported up to 97 minutes ago — nodes that have been renamed or removed.
     * Averaging their final reading into today's CPU figure is not a stale number,
     * it is a number about machines that no longer exist.</p>
     *
     * <p>Generous rather than tight, because the collectors here are lumpy: one
     * node reports every twenty seconds while the other seven arrive together
     * roughly every twelve minutes. A cutoff near that cadence would drop seven
     * nodes out of the average whenever the burst drifted, which is the very
     * instability this exists to prevent. Thirty minutes clears the observed
     * cadence with room to spare and still excludes everything churned.</p>
     *
     * <p>0 disables the cutoff, which restores "average whatever the window
     * returned" — only sensible where every node reports on a strict interval.</p>
     */
    private int staleAfterSeconds = 1_800;

    /** Column carrying the node identity on the {@code system_*} streams. */
    private String nodeColumn = "k8s_node_name";

    /** How a series' many samples in the window collapse to one number. */
    public enum ValueAggregate {
        /**
         * The newest sample per series, via
         * {@code LAST_VALUE(value ORDER BY _timestamp)}.
         *
         * <p>The default, and the only one that makes the tiles move. The window
         * has to be wide enough to contain every node's slowest scrape or the
         * cluster average changes composition between polls — but a MEAN over a
         * window that wide barely budges, so the screen shows a number that is
         * stable because it is stale. Taking the newest sample per series
         * separates the two concerns: the window decides WHO is counted, this
         * decides WHEN each of them was measured.</p>
         */
        LATEST,
        /**
         * The mean over the whole window.
         *
         * <p>Kept because {@code LAST_VALUE(… ORDER BY …)} is not universally
         * supported, and a deployment whose OpenObserve rejects it needs a
         * working tile more than a responsive one. Selected automatically if the
         * {@code LATEST} form is refused — see the fallback in
         * {@code InfrastructureMetricsServiceImpl}.</p>
         */
        AVERAGE
    }

    private ValueAggregate valueAggregate = ValueAggregate.LATEST;

    private Cpu cpu = new Cpu();
    private Memory memory = new Memory();
    private Disk disk = new Disk();
    private Nfs nfs = new Nfs();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMetricsOrg() {
        return metricsOrg;
    }

    public void setMetricsOrg(String metricsOrg) {
        this.metricsOrg = metricsOrg == null ? "" : metricsOrg.trim();
    }

    public int getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(int cacheSeconds) {
        this.cacheSeconds = Math.max(0, cacheSeconds);
    }

    public List<String> getWindows() {
        return windows;
    }

    public void setWindows(List<String> windows) {
        // An empty ladder would mean "never query anything", which is never what
        // clearing this property is meant to express. Fall back to the defaults.
        this.windows = new ArrayList<>(
                (windows == null || windows.isEmpty())
                        ? List.of("now-15m", WINDOW_NOW_30M, "now-1h")
                        : windows);
    }

    public int getStaleAfterSeconds() {
        return staleAfterSeconds;
    }

    public void setStaleAfterSeconds(int staleAfterSeconds) {
        this.staleAfterSeconds = Math.max(0, staleAfterSeconds);
    }

    public ValueAggregate getValueAggregate() {
        return valueAggregate;
    }

    public void setValueAggregate(ValueAggregate valueAggregate) {
        this.valueAggregate = valueAggregate == null ? ValueAggregate.LATEST : valueAggregate;
    }

    public String getNodeColumn() {
        return nodeColumn;
    }

    public void setNodeColumn(String nodeColumn) {
        this.nodeColumn = (nodeColumn == null || nodeColumn.isBlank())
                ? "k8s_node_name" : nodeColumn.trim();
    }

    public Cpu getCpu() {
        return cpu;
    }

    public void setCpu(Cpu cpu) {
        this.cpu = cpu == null ? new Cpu() : cpu;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory == null ? new Memory() : memory;
    }

    public Disk getDisk() {
        return disk;
    }

    public void setDisk(Disk disk) {
        this.disk = disk == null ? new Disk() : disk;
    }

    public Nfs getNfs() {
        return nfs;
    }

    public void setNfs(Nfs nfs) {
        this.nfs = nfs == null ? new Nfs() : nfs;
    }

    /** Windows for one tile: its own ladder when set, otherwise the shared one. */
    public List<String> windowsFor(List<String> override) {
        return (override == null || override.isEmpty()) ? windows : override;
    }

    // ── CPU ──────────────────────────────────────────────────────────────────

    /**
     * {@code infrastructure.cpu.*} — CPU against the pods' configured limit.
     *
     * <p>The number is produced by ONE operator-supplied statement rather than by
     * Java arithmetic over grouped rows. That is the point of {@link #getSql()}:
     * what "CPU usage" means for this deployment is a question about which
     * receivers are running and which pods matter, and it changed twice already.
     * Expressing it as SQL puts the whole definition — streams, pod selection,
     * numerator, denominator and the wording of the caption — in one editable
     * place, instead of spread across four column properties and a method.</p>
     *
     * <p>See {@link Memory#getSql()} for the contract the statement has to meet.</p>
     */
    public static class Cpu {

        /**
         * Utilization of the CPU LIMIT, with the cores behind it.
         *
         * <p>{@code k8s_pod_cpu_limit_utilization} is already a fraction of the
         * limit, so the percentage is read straight off it rather than divided out
         * here — {@code k8s_pod_cpu_usage} and the limit are queried only to caption
         * it as "1.59 / 5 cores", which is what makes the percentage checkable.</p>
         *
         * <p>The limit uses {@code MAX}, not {@code AVG}: it is a constant per
         * container, and averaging it across the window would drift the moment a
         * scrape was missed.</p>
         */
        private String sql = DEFAULT_CPU_SQL;

        /** Tried only if {@link #getSql()} yields nothing. See {@link #FALLBACK_CPU_SQL}. */
        private String fallbackSql = FALLBACK_CPU_SQL;

        /** What the tile measures, in words, for the card's header. */
        private String scope = SCOPE_TIOTOPENOBSERVE_PODS;

        /**
         * Its own ladder, and a WIDE one — see {@link #POD_WINDOWS}.
         *
         * <p>Not {@code infrastructure.windows}, whose narrow first rungs exist to
         * keep a window MEAN current. There is no mean here, so a narrow rung buys
         * nothing and costs a query that usually returns nothing.</p>
         */
        private List<String> windows = new ArrayList<>(POD_WINDOWS);

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = blankTo(sql, DEFAULT_CPU_SQL);
        }

        public String getFallbackSql() {
            return fallbackSql;
        }

        /** Explicitly empty disables the fallback, leaving the tile on one statement. */
        public void setFallbackSql(String fallbackSql) {
            this.fallbackSql = fallbackSql == null ? FALLBACK_CPU_SQL : fallbackSql.trim();
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = blankTo(scope, SCOPE_TIOTOPENOBSERVE_PODS);
        }

        public List<String> getWindows() {
            return windows;
        }

        public void setWindows(List<String> windows) {
            this.windows = new ArrayList<>(
                    (windows == null || windows.isEmpty()) ? POD_WINDOWS : windows);
        }
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    /**
     * {@code infrastructure.memory.*} — memory against the pods' configured limit.
     */
    public static class Memory {

        /**
         * Utilization of the memory LIMIT, with the gigabytes behind it.
         *
         * <p>{@code container_memory_working_set_bytes} rather than
         * {@code container_memory_usage_bytes}: the working set is what the kubelet
         * compares against the limit when it decides to OOM-kill, so it is the
         * figure that matches the percentage sitting next to it. Usage includes
         * reclaimable page cache and would read alarmingly higher for no reason.</p>
         */
        private String sql = DEFAULT_MEMORY_SQL;

        /** Tried only if {@link #getSql()} yields nothing. See {@link #FALLBACK_CPU_SQL}. */
        private String fallbackSql = FALLBACK_MEMORY_SQL;

        private String scope = SCOPE_TIOTOPENOBSERVE_PODS;

        /** Wide, for the reasons in {@link Cpu#getWindows()}. */
        private List<String> windows = new ArrayList<>(POD_WINDOWS);

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = blankTo(sql, DEFAULT_MEMORY_SQL);
        }

        public String getFallbackSql() {
            return fallbackSql;
        }

        /** Explicitly empty disables the fallback, leaving the tile on one statement. */
        public void setFallbackSql(String fallbackSql) {
            this.fallbackSql = fallbackSql == null ? FALLBACK_MEMORY_SQL : fallbackSql.trim();
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = blankTo(scope, SCOPE_TIOTOPENOBSERVE_PODS);
        }

        public List<String> getWindows() {
            return windows;
        }

        public void setWindows(List<String> windows) {
            this.windows = new ArrayList<>(
                    (windows == null || windows.isEmpty()) ? POD_WINDOWS : windows);
        }
    }

    // ── Disk ─────────────────────────────────────────────────────────────────

    /**
     * {@code infrastructure.disk.*}
     *
     * <p>One row per (node, device, mountpoint, state). Capacity is
     * {@code used + free} per mount rather than a separate capacity metric,
     * because that is what the filesystem scraper actually emits.</p>
     */
    public static class Disk {

        private String stream = "system_filesystem_usage";
        private String stateColumn = "state";
        private String usedState = "used";
        private String freeState = "free";
        private String mountpointColumn = "mountpoint";
        private String deviceColumn = "device";
        private String typeColumn = "type";

        /**
         * Filesystem types left out of the total.
         *
         * <p>These are memory- or image-backed pseudo filesystems. A Kubernetes
         * node carries dozens of {@code tmpfs} and {@code overlay} mounts, and
         * counting them makes the Disk tile a number about container images
         * rather than about the disk anyone can run out of.</p>
         *
         * <p>Applied in Java rather than as a {@code NOT IN} predicate: the list
         * is operator-supplied, and keeping it out of the SQL string means a typo
         * costs a mis-filtered row instead of a malformed query.</p>
         */
        private List<String> excludeTypes = new ArrayList<>(List.of(
                "tmpfs", "devtmpfs", "overlay", "squashfs", "ramfs", "iso9660", "autofs"));

        private List<String> windows = new ArrayList<>();

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = blankTo(stream, "system_filesystem_usage");
        }

        public String getStateColumn() {
            return stateColumn;
        }

        public void setStateColumn(String stateColumn) {
            this.stateColumn = blankTo(stateColumn, "state");
        }

        public String getUsedState() {
            return usedState;
        }

        public void setUsedState(String usedState) {
            this.usedState = blankTo(usedState, "used");
        }

        public String getFreeState() {
            return freeState;
        }

        public void setFreeState(String freeState) {
            this.freeState = blankTo(freeState, "free");
        }

        public String getMountpointColumn() {
            return mountpointColumn;
        }

        public void setMountpointColumn(String mountpointColumn) {
            this.mountpointColumn = blankTo(mountpointColumn, "mountpoint");
        }

        public String getDeviceColumn() {
            return deviceColumn;
        }

        public void setDeviceColumn(String deviceColumn) {
            this.deviceColumn = blankTo(deviceColumn, "device");
        }

        public String getTypeColumn() {
            return typeColumn;
        }

        public void setTypeColumn(String typeColumn) {
            this.typeColumn = blankTo(typeColumn, "type");
        }

        public List<String> getExcludeTypes() {
            return excludeTypes;
        }

        public void setExcludeTypes(List<String> excludeTypes) {
            // An explicitly empty list is meaningful — "count every mount" — so
            // unlike the window ladder this does NOT revert to the defaults.
            this.excludeTypes = excludeTypes == null ? new ArrayList<>() : new ArrayList<>(excludeTypes);
        }

        /** Lower-cased exclusion set, for matching a row's reported type. */
        public Set<String> excludedTypeSet() {
            Set<String> out = new LinkedHashSet<>();
            for (String type : excludeTypes) {
                if (type != null && !type.isBlank()) {
                    out.add(type.trim().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }

        public List<String> getWindows() {
            return windows;
        }

        public void setWindows(List<String> windows) {
            this.windows = windows == null ? new ArrayList<>() : new ArrayList<>(windows);
        }
    }

    // ── NFS storage ──────────────────────────────────────────────────────────

    /**
     * {@code infrastructure.nfs.*} — the shared storage behind the cluster's
     * persistent volume claims.
     *
     * <p>Read from {@code k8s_volume_capacity} / {@code k8s_volume_available},
     * because a network share is not a mount on any one node and therefore does
     * not appear in the host filesystem scraper at all. Verified on this
     * deployment: {@code system_filesystem_usage} reports only {@code ext4} on
     * {@code /} and {@code /boot}, so a Disk-tile-style query can never produce
     * this number.</p>
     */
    public static class Nfs {

        private String capacityStream = "k8s_volume_capacity";
        private String availableStream = "k8s_volume_available";
        private String volumeColumn = "k8s_volume_name";
        private String namespaceColumn = "k8s_namespace_name";
        private String podColumn = "k8s_pod_name";
        private String volumeTypeColumn = "k8s_volume_type";

        /** Volume kinds counted as durable storage. */
        private List<String> volumeTypes = new ArrayList<>(List.of("persistentVolumeClaim"));

        /**
         * Fold claims that report an identical capacity into ONE backing store.
         *
         * <p>Load-bearing, not a tidy-up. The kubelet reports the size of the
         * underlying export against EVERY claim mounted from it, so on this
         * deployment twenty-eight PVCs each report the same 95.9 GiB. Summing
         * them announces 2.6 TiB of storage that does not exist, and the free
         * space would be wrong by the same factor.</p>
         *
         * <p>The cost is that two genuinely separate volumes of exactly equal
         * size are merged into one. That is the safer error — it understates
         * capacity rather than inventing it — and this switch turns it off for a
         * deployment where each claim really is its own volume.</p>
         */
        private boolean dedupeByCapacity = true;

        /**
         * Its own ladder, wider than the shared one.
         *
         * <p>The kubeletstats volume scraper runs far less often than the host
         * metrics receiver — observed both seconds and nineteen minutes behind
         * {@code system_*} on the same instance — so a narrow first rung would
         * report the tile empty on a perfectly healthy cluster.</p>
         *
         * <p>How far back this can actually reach is governed by
         * {@link InfrastructureProperties#getStaleAfterSeconds()}, not by the last
         * rung: a claim whose newest sample is past the cutoff is dropped whichever
         * window found it. Raise the cutoff, not just this list, for a deployment
         * whose volume scraper is slower than that.</p>
         */
        private List<String> windows = new ArrayList<>(List.of(WINDOW_NOW_30M, "now-2h"));

        public String getCapacityStream() {
            return capacityStream;
        }

        public void setCapacityStream(String capacityStream) {
            this.capacityStream = blankTo(capacityStream, "k8s_volume_capacity");
        }

        public String getAvailableStream() {
            return availableStream;
        }

        public void setAvailableStream(String availableStream) {
            this.availableStream = blankTo(availableStream, "k8s_volume_available");
        }

        public String getVolumeColumn() {
            return volumeColumn;
        }

        public void setVolumeColumn(String volumeColumn) {
            this.volumeColumn = blankTo(volumeColumn, "k8s_volume_name");
        }

        public String getNamespaceColumn() {
            return namespaceColumn;
        }

        public void setNamespaceColumn(String namespaceColumn) {
            this.namespaceColumn = blankTo(namespaceColumn, "k8s_namespace_name");
        }

        public String getPodColumn() {
            return podColumn;
        }

        public void setPodColumn(String podColumn) {
            this.podColumn = blankTo(podColumn, "k8s_pod_name");
        }

        public String getVolumeTypeColumn() {
            return volumeTypeColumn;
        }

        public void setVolumeTypeColumn(String volumeTypeColumn) {
            this.volumeTypeColumn = blankTo(volumeTypeColumn, "k8s_volume_type");
        }

        public List<String> getVolumeTypes() {
            return volumeTypes;
        }

        public void setVolumeTypes(List<String> volumeTypes) {
            // Empty means "every volume kind", which is a legitimate choice on a
            // cluster that does not use PVCs — so no revert to the default here.
            this.volumeTypes = volumeTypes == null ? new ArrayList<>() : new ArrayList<>(volumeTypes);
        }

        public boolean isDedupeByCapacity() {
            return dedupeByCapacity;
        }

        public void setDedupeByCapacity(boolean dedupeByCapacity) {
            this.dedupeByCapacity = dedupeByCapacity;
        }

        public List<String> getWindows() {
            return windows;
        }

        public void setWindows(List<String> windows) {
            this.windows = windows == null ? new ArrayList<>() : new ArrayList<>(windows);
        }
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
