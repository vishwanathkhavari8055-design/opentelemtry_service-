package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.InfrastructureProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.infrastructure.InfrastructureResponseDTO;
import com.trinity.observability.service.InfrastructureMetricsService;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;

import jakarta.annotation.PreDestroy;

/**
 * Reads CPU, Memory, Disk and NFS Storage out of the collector streams, on the
 * request that asks for them.
 *
 * <h2>Two kinds of tile</h2>
 *
 * <p>CPU and Memory are POD-scoped and each read by running ONE operator-supplied
 * statement ({@code infrastructure.cpu.sql}, {@code infrastructure.memory.sql})
 * and taking one row of {@code pct} / {@code detail} from it — see
 * {@link #scalarTile}. Disk and NFS Storage are CLUSTER-scoped and assembled here
 * from grouped rows, because their arithmetic is not one aggregate: a device
 * bind-mounted twice must count once, pseudo filesystems must be dropped by type,
 * and claims sharing an export must be folded together.</p>
 *
 * <p>The two halves therefore answer different questions, and the tiles say which
 * via {@code Item.scope}. A screen that presents them as one row of comparable
 * figures without that label is how this service previously came to show node
 * averages under pod headings.</p>
 *
 * <p>Everything from here down describes the GROUPED half only.</p>
 *
 * <h2>The window decides WHO is counted; the aggregate decides WHEN</h2>
 *
 * <p>Keeping these two apart is the whole design, and getting them tangled
 * produced two different wrong screens on the way here.</p>
 *
 * <p>The window has to be WIDE. These are cluster aggregates, so the denominator
 * is whichever nodes reported inside it, and the collectors here are lumpy — one
 * node every twenty seconds, the other seven together roughly every twelve
 * minutes. A five-minute window contains one node; a fifteen-minute window
 * contains all eight. Stopping at the first rung that returned any rows therefore
 * made the Disk tile swing between 62% and 72% between consecutive polls, because
 * the set of nodes being averaged changed. Nothing about the disks had changed:
 * that is a wrong number shaped like a trend. So each tile keeps widening for as
 * long as widening reveals more STILL-REPORTING nodes.</p>
 *
 * <p>But a mean over a window that wide does not move. Measured: a thirty-minute
 * mean gave CPU 11.3% on six consecutive polls across two and a half minutes —
 * stable because it was stale, which is the original complaint restored. So the
 * value is the NEWEST sample per series ({@link #LATEST_EXPRESSION}), not the
 * window mean. Wide window, latest sample: stable membership, live readings.</p>
 *
 * <p>The wider rungs also cover a separate hazard: this OpenObserve instance
 * answers a scan it cannot serve with HTTP 200 and zero hits, indistinguishable
 * from "the collector is down".</p>
 *
 * <h2>One failing tile never blanks the others</h2>
 *
 * <p>The four run concurrently and are collected independently. A stream that
 * does not exist, a query that errors and a receiver that has stopped all produce
 * the same thing — that one tile marked unavailable with a reason — because the
 * screen's job is to show what IS known about the cluster, and losing three good
 * numbers to protect one missing one gets that backwards.</p>
 */
@Service
public class InfrastructureMetricsServiceImpl implements InfrastructureMetricsService {

    private static final Logger log = LogManager.getLogger(InfrastructureMetricsServiceImpl.class);

    /** Every stream read here is a metric stream. */
    private static final String STREAM_TYPE = "metrics";

    /**
     * Row caps per query. Generous, because these are already GROUP BY results —
     * a cluster would need hundreds of nodes or thousands of claims to reach one,
     * and every query here is bounded by the cardinality of the grouping columns
     * rather than by the number of samples in the window.
     */
    private static final int MAX_GROUPED_ROWS = 5_000;

    /**
     * Row cap for the CPU / Memory statements, which return a single row.
     *
     * <p>Not 1. A misconfigured statement that grouped instead of aggregating would
     * silently have its rows truncated, and the first row of an unintended GROUP BY
     * looks exactly like a correct reading. A handful of rows costs nothing and
     * leaves the mistake visible in the query log.</p>
     */
    private static final int SCALAR_ROWS = 10;

    /** What Disk and NFS Storage measure, in words. See {@code Item.scope}. */
    private static final String SCOPE_DISK = "real filesystems, cluster-wide";
    private static final String SCOPE_NFS = "persistent volume claims";

    /**
     * The newest sample per series.
     *
     * <p>This, not {@code AVG}, is what makes the tiles move. The window has to be
     * wide enough to contain every node's slowest scrape — otherwise the set of
     * nodes being averaged changes between polls and the figure swings for reasons
     * that have nothing to do with the cluster — but a MEAN over a window that wide
     * barely budges. Measured: with a thirty-minute mean, CPU read 11.3% on six
     * consecutive polls over two and a half minutes. Taking the newest sample per
     * series separates the concerns: the window decides who is counted, this decides
     * when each of them was measured.</p>
     */
    private static final String LATEST_EXPRESSION = "LAST_VALUE(value ORDER BY _timestamp)";

    /** The fallback for an OpenObserve that will not serve the above. */
    private static final String AVERAGE_EXPRESSION = "AVG(value)";

    static final String KIND_CPU = "CPU";
    static final String KIND_MEMORY = "MEMORY";
    static final String KIND_DISK = "DISK";
    static final String KIND_NFS = "NFS_STORAGE";

    static final String LABEL_CPU = "CPU";
    static final String LABEL_MEMORY = "Memory";
    static final String LABEL_DISK = "Disk";
    static final String LABEL_NFS = "NFS Storage";

    private static final String COLUMN_AGG_VALUE = "agg_value";
    private static final String COLUMN_LAST_SEEN = "last_seen";

    private final OpenObserveClientService openObserve;
    private final InfrastructureProperties properties;
    private final OpenObserveConfig openObserveConfig;

    /**
     * The four tiles run together rather than one after another.
     *
     * <p>Each is at least one round trip to OpenObserve, and a tile that has to
     * climb its window ladder is several. In series that is the difference between
     * a screen that paints and one that looks hung. Daemon threads, so a call that
     * never returns cannot hold JVM shutdown open.</p>
     */
    private final ExecutorService pool;

    /** org|node → the last answer and when it stops being served. */
    private final Map<String, Cached> cache = new HashMap<>();

    /**
     * Set once this OpenObserve has been shown not to serve {@link #LATEST_EXPRESSION}.
     *
     * <p>Sticky rather than re-probed per request: the answer is a property of the
     * server build, and re-testing it would double every tile's query count for the
     * lifetime of the deployment. Volatile because the tiles run on pool threads.</p>
     */
    private volatile boolean latestUnsupported;

    /**
     * Tile kinds that have already logged a fallback warning.
     *
     * <p>Warn-once per kind: the condition is a property of the deployment, and at a
     * 20-second poll a per-request warning would be several thousand identical lines
     * a day — which is how a real signal gets filtered out as noise.</p>
     */
    private final Set<String> fallbackWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public InfrastructureMetricsServiceImpl(OpenObserveClientService openObserve,
            InfrastructureProperties properties,
            OpenObserveConfig openObserveConfig) {
        this.openObserve = openObserve;
        this.properties = properties;
        this.openObserveConfig = openObserveConfig;

        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "infra-metrics-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdownPool() {
        pool.shutdownNow();
    }

    private record Cached(InfrastructureResponseDTO.Utilization value, long computedAtNanos,
            long expiresAtNanos) {
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    @Override
    public InfrastructureResponseDTO.Utilization utilization(String rawNode, boolean refresh) {
        long t0 = System.nanoTime();
        String org = metricsOrg();
        String node = normalizeNode(rawNode);
        String key = org + '|' + (node == null ? "" : node);

        int ttlSeconds = properties.getCacheSeconds();
        if (!refresh && ttlSeconds > 0) {
            InfrastructureResponseDTO.Utilization hit = fromCache(key, t0);
            if (hit != null) {
                log.debug("Infrastructure utilization served from cache (org={}, node={})", org, node);
                return hit;
            }
        }

        InfrastructureResponseDTO.Utilization out = compute(org, node);

        if (ttlSeconds > 0) {
            long now = System.nanoTime();
            synchronized (cache) {
                cache.put(key, new Cached(out, now, now + ttlSeconds * 1_000_000_000L));
            }
        }

        if (log.isInfoEnabled()) {
            log.info("Infrastructure utilization computed in {} ms (org={}, node={}, tiles available: {})",
                    (System.nanoTime() - t0) / 1_000_000L, org, node, availableKinds(out));
        }
        return out;
    }

    /** The requested node, trimmed; null when none was given (blank counts as none). */
    private static String normalizeNode(String rawNode) {
        return (rawNode == null || rawNode.isBlank()) ? null : rawNode.trim();
    }

    /**
     * A cached answer, re-stamped so the caller can see how old it is.
     *
     * <p>Copied rather than returned directly: the stored object is shared with
     * every concurrent caller, and mutating its {@code cacheAgeSec} in place would
     * let one request's staleness stamp appear on another's response.</p>
     */
    private InfrastructureResponseDTO.Utilization fromCache(String key, long nowNanos) {
        Cached cached;
        synchronized (cache) {
            cached = cache.get(key);
            if (cached != null && cached.expiresAtNanos() <= nowNanos) {
                cache.remove(key);
                cached = null;
            }
        }
        if (cached == null) {
            return null;
        }

        InfrastructureResponseDTO.Utilization source = cached.value();
        InfrastructureResponseDTO.Utilization copy = new InfrastructureResponseDTO.Utilization();
        copy.setOrg(source.getOrg());
        copy.setCapturedAt(source.getCapturedAt());
        copy.setSupported(source.isSupported());
        copy.setItems(source.getItems());
        copy.setNodes(source.getNodes());
        copy.setCached(true);
        copy.setCacheAgeSec((nowNanos - cached.computedAtNanos()) / 1_000_000_000L);
        return copy;
    }

    private static String availableKinds(InfrastructureResponseDTO.Utilization out) {
        List<String> kinds = new ArrayList<>();
        for (InfrastructureResponseDTO.Item item : out.getItems()) {
            if (item.isAvailable()) {
                kinds.add(item.getKind());
            }
        }
        return kinds.isEmpty() ? "none" : String.join(", ", kinds);
    }

    // ── Computation ──────────────────────────────────────────────────────────

    private InfrastructureResponseDTO.Utilization compute(String org, String node) {
        long nowMicros = nowMicros();

        InfrastructureResponseDTO.Utilization out = new InfrastructureResponseDTO.Utilization();
        out.setOrg(org);
        out.setCapturedAt(TimeFormatter.microsToIso(nowMicros));
        out.setCached(false);

        if (!properties.isEnabled()) {
            out.setSupported(false);
            out.setItems(allUnavailable("Infrastructure metrics are switched off "
                    + "(infrastructure.enabled=false)."));
            return out;
        }
        if (!openObserveEnabled) {
            // OpenObserve is the only source for these numbers. Reporting 0%
            // would be a claim about the cluster we are in no position to make.
            out.setSupported(false);
            out.setItems(allUnavailable("These figures come from OpenObserve, which is disabled. "
                    + "Set openobserve.enabled=true."));
            return out;
        }
        out.setSupported(true);

        // The org lives in a ThreadLocal that OpenObserveClientImpl reads through
        // OpenObserveConfig.getOrg() when it builds the search path, and a pool
        // thread has none of the request's state — so it has to be set explicitly
        // here or every tile would query the configured default.
        //
        // What is set is the METRICS org, NOT OrgContext.get(). That is the whole
        // fix for a screen that was blank on three of four tenants: these are
        // cluster facts ingested into one org, so propagating the caller's tenant
        // sent every query somewhere the streams do not exist. See
        // InfrastructureProperties.getMetricsOrg().
        String orgContext = org;

        InfrastructureProperties.Cpu cpuConfig = properties.getCpu();
        InfrastructureProperties.Memory memoryConfig = properties.getMemory();

        // CPU and Memory ignore `node` — they are scoped to a set of pods by their
        // own statement, and a pod does not have a node column to narrow on. Only
        // Disk and NFS, which really are per-node reads, receive it.
        Future<Tile> cpu = submit(orgContext, () -> scalarTile(KIND_CPU, LABEL_CPU,
                cpuConfig.getSql(), cpuConfig.getFallbackSql(), cpuConfig.getScope(),
                "infrastructure.cpu.sql",
                properties.windowsFor(cpuConfig.getWindows()), nowMicros));
        Future<Tile> memory = submit(orgContext, () -> scalarTile(KIND_MEMORY, LABEL_MEMORY,
                memoryConfig.getSql(), memoryConfig.getFallbackSql(), memoryConfig.getScope(),
                "infrastructure.memory.sql",
                properties.windowsFor(memoryConfig.getWindows()), nowMicros));
        Future<Tile> disk = submit(orgContext, () -> diskTile(node, nowMicros));
        Future<Tile> nfs = submit(orgContext, () -> nfsTile(node, nowMicros));

        List<Tile> tiles = List.of(
                await(cpu, KIND_CPU, LABEL_CPU),
                await(memory, KIND_MEMORY, LABEL_MEMORY),
                await(disk, KIND_DISK, LABEL_DISK),
                await(nfs, KIND_NFS, LABEL_NFS));

        List<InfrastructureResponseDTO.Item> items = new ArrayList<>(tiles.size());
        Map<String, InfrastructureResponseDTO.Node> nodes = new LinkedHashMap<>();
        for (Tile tile : tiles) {
            items.add(tile.item());
            mergeNodes(nodes, tile, nowMicros);
        }

        out.setItems(items);
        out.setNodes(new ArrayList<>(nodes.values()));
        return out;
    }

    private Future<Tile> submit(String org, java.util.concurrent.Callable<Tile> task) {
        return pool.submit(() -> {
            String previous = OrgContext.get();
            try {
                if (org == null) {
                    OrgContext.clear();
                } else {
                    OrgContext.set(org);
                }
                return task.call();
            } finally {
                // Pool threads are reused, so a value left set here would leak this
                // request's tenant onto the next one.
                if (previous == null) {
                    OrgContext.clear();
                } else {
                    OrgContext.set(previous);
                }
            }
        });
    }

    /** A tile that throws is one unavailable tile, never a failed response. */
    private Tile await(Future<Tile> future, String kind, String label) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Tile.empty(kind, label, scopeFor(kind),
                    "Interrupted while reading " + label + ".");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Infrastructure {} tile failed: {}", kind, cause.getMessage());
            return Tile.empty(kind, label, scopeFor(kind),
                    label + " could not be read: " + cause.getMessage());
        }
    }

    private void mergeNodes(Map<String, InfrastructureResponseDTO.Node> nodes, Tile tile, long nowMicros) {
        for (Map.Entry<String, NodeStat> entry : tile.byNode().entrySet()) {
            InfrastructureResponseDTO.Node dto =
                    nodes.computeIfAbsent(entry.getKey(), name -> {
                        InfrastructureResponseDTO.Node fresh = new InfrastructureResponseDTO.Node();
                        fresh.setNode(name);
                        return fresh;
                    });
            mergeNode(dto, entry.getValue(), nowMicros);
        }
    }

    private static void mergeNode(InfrastructureResponseDTO.Node dto, NodeStat stat, long nowMicros) {
        if (stat.diskPercent != null) {
            dto.setDiskPercent(stat.diskPercent);
        }
        if (stat.diskUsedBytes != null) {
            dto.setDiskUsedBytes(stat.diskUsedBytes);
        }
        if (stat.diskTotalBytes != null) {
            dto.setDiskTotalBytes(stat.diskTotalBytes);
        }
        // Newest across the tiles that mention this node — the row's age should
        // reflect the most recent thing known about it, not whichever tile
        // happened to be merged last.
        if (stat.lastSeenMicros != null) {
            mergeLastSeen(dto, stat.lastSeenMicros, nowMicros);
        }
    }

    private static void mergeLastSeen(InfrastructureResponseDTO.Node dto, Long lastSeenMicros, long nowMicros) {
        Long existing = dto.getLastSeenAgoSec() == null ? null
                : nowMicros - dto.getLastSeenAgoSec() * 1_000_000L;
        if (existing == null || lastSeenMicros > existing) {
            dto.setLastSeen(TimeFormatter.microsToIso(lastSeenMicros));
            dto.setLastSeenAgoSec(agoSeconds(nowMicros, lastSeenMicros));
        }
    }

    // ── CPU and Memory: one statement, one row ───────────────────────────────

    /**
     * A tile whose whole reading is one row of one operator-supplied statement.
     *
     * <p>This is how CPU and Memory are read. Both are a single percentage against
     * a single configured limit, so there is nothing for Java to combine — the
     * statement in {@code infrastructure.cpu.sql} / {@code infrastructure.memory.sql}
     * selects the pods, divides by the limit and formats the caption, and this
     * method's only job is to run it, pick the row and stamp its age.</p>
     *
     * <h3>Why the caption is built in SQL and not here</h3>
     *
     * <p>{@code detail} arrives pre-formatted — {@code "1.59 / 5 cores"} — from the
     * same statement that produced the percentage. Reformatting it here would mean
     * re-deriving the numerator and denominator from separate queries, and the
     * failure mode of that is a caption that no longer matches the number above it:
     * the two would be read from different windows and would disagree during
     * exactly the spikes an operator is looking at.</p>
     *
     * <h3>The ladder, and what it does NOT do here</h3>
     *
     * <p>The rungs are climbed narrowest first and the FIRST rung that yields a row
     * with a numeric {@code pct} wins — unlike the grouped tiles, which keep
     * widening in search of coverage. There is no coverage to find: the statement
     * returns one row however wide the window is, so a wider rung would only make
     * the average staler. Widening happens solely because the pod collectors go
     * quiet for stretches longer than the first rung.</p>
     *
     * <p>{@code stale-after-seconds} is deliberately NOT applied. It exists to keep
     * a decommissioned node out of a cluster average, and there is no average to
     * poison here — one pod's own reading is worth showing at whatever age it has,
     * with {@code lastSeenAgoSec} saying what that age is. Dropping it would blank
     * the card and leave the screen unable to distinguish "the pods stopped
     * reporting twenty minutes ago" from "these streams do not exist".</p>
     */
    @SuppressWarnings("java:S107")
    private Tile scalarTile(String kind, String label, String sql, String fallbackSql,
            String scope, String propertyKey, List<String> windows, long nowMicros) {

        InfrastructureResponseDTO.Item item = scalarClimb(kind, sql, scope, label, windows, nowMicros);
        if (item != null) {
            return new Tile(item, Map.of());
        }

        // Nothing at any rung. Before calling the tile dead, try the fallback: the
        // primary statement uses LAST_VALUE, which is version-dependent, and the
        // service cannot rewrite an opaque operator-supplied statement the way it
        // rewrites its own generated SQL for the grouped tiles. A window mean is a
        // worse reading than the newest sample, but it is a reading.
        if (fallbackSql != null && !fallbackSql.isBlank() && !fallbackSql.equals(sql)) {
            InfrastructureResponseDTO.Item fallback =
                    scalarClimb(kind, fallbackSql, scope, label, windows, nowMicros);
            if (fallback != null) {
                if (fallbackWarned.add(kind) && log.isWarnEnabled()) {
                    log.warn("Infrastructure {} fell back to {}.fallback-sql — the primary "
                            + "statement returned nothing at any window. If this persists, this "
                            + "OpenObserve may not plan LAST_VALUE(value ORDER BY _timestamp); "
                            + "the reading is now a window mean and will move more slowly.",
                            kind, propertyKey.replace(".sql", ""));
                }
                return new Tile(fallback, Map.of());
            }
        }

        return Tile.empty(kind, label, scope,
                noScalarDataMessage(label, propertyKey, lastRung(windows)));
    }

    /** The widest rung that was actually attempted, for the "no data" message. */
    private static String lastRung(List<String> windows) {
        String widest = null;
        for (String window : windows) {
            if (window != null && !window.isBlank()) {
                widest = window.trim();
            }
        }
        return widest;
    }

    /**
     * Run one statement down the ladder; the first rung yielding a numeric
     * {@code pct} wins.
     *
     * @return the tile item, or null when no rung produced a reading
     */
    private InfrastructureResponseDTO.Item scalarClimb(String kind, String sql, String scope,
            String label, List<String> windows, long nowMicros) {
        long end = TimeRangeParser.defaultEndMicros();

        for (String window : windows) {
            String rung = usableRung(window);
            Long start = (rung != null) ? rungStart(kind, rung, rung, end) : null;
            List<Map<String, Object>> rows = (start != null) ? searchRung(kind, sql, rung, start, end) : null;
            if (rows != null) {
                InfrastructureResponseDTO.Item item =
                        firstScalarReading(kind, label, scope, rung, rows, nowMicros);
                if (item != null) {
                    return item;
                }
            }
        }

        return null;
    }

    /** One rung's rows; empty when the statement failed over that rung. */
    private List<Map<String, Object>> searchRung(String kind, String sql, String rung, long start, long end) {
        try {
            return openObserve.search(sql, start, end, 0, SCALAR_ROWS, STREAM_TYPE);
        } catch (Exception e) {
            // A stream the statement names does not exist on this deployment,
            // or this OpenObserve will not plan the join. Either way it is
            // "this tile has no source", not a failed request.
            log.debug("Infrastructure {} statement failed over {}: {}", kind, rung, e.getMessage());
            return List.of();
        }
    }

    /** The first row with a numeric {@code pct}, as a tile item; null when none has one. */
    private static InfrastructureResponseDTO.Item firstScalarReading(String kind, String label,
            String scope, String rung, List<Map<String, Object>> rows, long nowMicros) {
        for (Map<String, Object> row : rows) {
            Double pct = number(row.get(InfrastructureProperties.COLUMN_PCT));
            if (pct == null) {
                // A row whose pct is null is what a cross join against an empty
                // aggregate produces. It is not a reading of 0%.
                continue;
            }

            InfrastructureResponseDTO.Item item = new InfrastructureResponseDTO.Item();
            item.setKind(kind);
            item.setLabel(label);
            item.setScope(scope);
            // No byte counts on this path — declaring BYTES with null sizes
            // would have the caller render "used 0 of 0".
            item.setUnit("PERCENT");
            item.setAvailable(true);
            // Lower bound only. A negative percentage is bad data, but a pod
            // burst above its CPU limit genuinely exceeds 100% and clamping
            // that would hide the one reading worth acting on.
            item.setUsedPercent(round1(Math.max(0d, pct)));
            item.setDetail(usableDetail(row.get(InfrastructureProperties.COLUMN_DETAIL)));
            item.setWindow(rung);

            Double lastSeen = number(row.get(InfrastructureProperties.COLUMN_LAST_SEEN));
            stampLastSeen(item, lastSeen == null ? null : lastSeen.longValue(), nowMicros);

            log.debug("Infrastructure {} read {}% ({}) over {}", kind,
                    item.getUsedPercent(), item.getDetail(), rung);
            return item;
        }
        return null;
    }

    /** The trimmed rung label, or null for a blank entry in the ladder. */
    private static String usableRung(String window) {
        if (window == null || window.isBlank()) {
            return null;
        }
        return window.trim();
    }

    /**
     * Start of one rung, anchored to {@code end}; null (after a warning naming
     * {@code logged}) when the rung does not parse.
     */
    private static Long rungStart(String kind, String rung, String logged, long end) {
        try {
            return TimeRangeParser.toMicros(rung, end, end);
        } catch (RuntimeException e) {
            log.warn("Ignoring infrastructure window '{}' for {}: {}", logged, kind, e.getMessage());
            return null;
        }
    }

    /**
     * A caption only if it actually carries numbers.
     *
     * <p>Observed on this deployment, and the reason this exists: when the limits
     * stream had not been written inside the window, {@code CONCAT} of NULLs did NOT
     * eliminate the row — it produced {@code " /  cores"} and {@code "/  GB"}, which
     * the tile then published as its headline. A caption with no digits in it is
     * punctuation, and printing it is worse than printing nothing: an em dash reads
     * as "not known", whereas {@code " /  cores"} reads as a broken page.</p>
     *
     * <p>Note this is a SECOND line of defence. The shipped statements divide by the
     * limit to get {@code pct}, so a missing limit nulls the percentage and the row
     * is rejected outright a few lines above. This catches the same failure in a
     * statement an operator wrote differently.</p>
     */
    private static String usableDetail(Object raw) {
        String detail = text(raw);
        if (detail == null) {
            return null;
        }
        for (int i = 0; i < detail.length(); i++) {
            if (Character.isDigit(detail.charAt(i))) {
                return detail;
            }
        }
        return null;
    }

    private static String noScalarDataMessage(String label, String propertyKey, String widestWindow) {
        StringBuilder message = new StringBuilder("No ")
                .append(label.toLowerCase(Locale.ROOT))
                .append(" reading");
        if (widestWindow != null) {
            message.append(" as far back as ").append(widestWindow);
        }
        message.append(". The pods it selects may have stopped reporting, or the metric "
                + "streams it reads may not exist on this deployment (see ")
                .append(propertyKey)
                .append(" in application.properties).");
        return message.toString();
    }

    // ── Disk ─────────────────────────────────────────────────────────────────

    /**
     * Real filesystems only, one entry per (node, device).
     *
     * <p>Keyed by device rather than by mountpoint because a device bind-mounted
     * in two places reports its full size under both, and summing those counts the
     * same disk twice. Pseudo filesystems are dropped entirely — a Kubernetes node
     * carries dozens of {@code tmpfs} and {@code overlay} mounts, and including
     * them turns the Disk tile into a statement about container images rather than
     * about the disk that can fill up.</p>
     */
    private Tile diskTile(String node, long nowMicros) {
        InfrastructureProperties.Disk config = properties.getDisk();
        String stream = SqlSafety.validateIdentifier(config.getStream());
        String nodeColumn = SqlSafety.validateIdentifier(properties.getNodeColumn());
        String stateColumn = SqlSafety.validateIdentifier(config.getStateColumn());
        String mountColumn = SqlSafety.validateIdentifier(config.getMountpointColumn());
        String deviceColumn = SqlSafety.validateIdentifier(config.getDeviceColumn());
        String typeColumn = SqlSafety.validateIdentifier(config.getTypeColumn());
        String nodeFilter = nodePredicate(nodeColumn, node);

        Answer answer = searchLadder(KIND_DISK, properties.windowsFor(config.getWindows()),
                valueExpr -> (start, end) -> "SELECT " + nodeColumn + " AS node_name, "
                        + deviceColumn + " AS device_name, "
                        + mountColumn + " AS mount_point, "
                        + typeColumn + " AS fs_type, "
                        + stateColumn + " AS state_name, "
                        + valueExpr + " AS agg_value, MAX(_timestamp) AS last_seen "
                        + "FROM \"" + stream + "\" "
                        + "WHERE _timestamp >= " + start + " AND _timestamp <= " + end
                        + nodeFilter + " "
                        + "GROUP BY " + nodeColumn + ", " + deviceColumn + ", "
                        + mountColumn + ", " + typeColumn + ", " + stateColumn);

        if (answer.rows().isEmpty()) {
            return Tile.empty(KIND_DISK, "Disk", SCOPE_DISK, noDataMessage(stream, answer));
        }

        Set<String> excluded = config.excludedTypeSet();
        String usedState = config.getUsedState().toLowerCase(Locale.ROOT);
        String freeState = config.getFreeState().toLowerCase(Locale.ROOT);

        DiskMounts mounts = new DiskMounts();
        for (Map<String, Object> row : answer.rows()) {
            addDiskRow(row, excluded, usedState, freeState, mounts);
        }

        // Collapse bind mounts: the largest reading for a device is the device.
        Map<String, double[]> byDevice = new LinkedHashMap<>();
        int mountCount = collapseBindMounts(mounts, byDevice);

        Map<String, NodeStat> stats = new LinkedHashMap<>();
        DiskSums sums = sumDevices(byDevice, mounts.lastSeenByNode, stats);
        applyDiskPercents(stats);
        double usedSum = sums.used;
        double totalSum = sums.total;
        Long newest = sums.newest;

        if (totalSum <= 0d) {
            return Tile.empty(KIND_DISK, "Disk", SCOPE_DISK, "No real filesystem reported any capacity. "
                    + "Every mount in the window was one of the excluded types "
                    + "(infrastructure.disk.exclude-types).");
        }

        InfrastructureResponseDTO.Item item = new InfrastructureResponseDTO.Item();
        item.setKind(KIND_DISK);
        item.setLabel("Disk");
        item.setScope(SCOPE_DISK);
        item.setUnit("BYTES");
        item.setAvailable(true);
        item.setUsedBytes(Math.round(usedSum));
        item.setTotalBytes(Math.round(totalSum));
        item.setFreeBytes(Math.round(Math.max(0d, totalSum - usedSum)));
        item.setUsedPercent(round1(clamp(usedSum / totalSum, 0d, 1d) * 100d));
        item.setWindow(answer.window());
        item.setDetail(mountCount + (mountCount == 1 ? " mount" : " mounts")
                + " on " + stats.size() + (stats.size() == 1 ? " node" : " nodes"));
        stampLastSeen(item, newest, nowMicros);
        return new Tile(item, stats);
    }

    /** Per-mount Disk readings, before bind mounts are collapsed. */
    private static final class DiskMounts {
        // (node, device, mountpoint) → [used, free]; reduced to (node, device) below.
        private final Map<String, double[]> byMount = new LinkedHashMap<>();
        private final Map<String, String> mountToDevice = new LinkedHashMap<>();
        private final Map<String, Long> lastSeenByNode = new LinkedHashMap<>();
    }

    /** Disk totals across every device, and the newest sample among their nodes. */
    private static final class DiskSums {
        private double used;
        private double total;
        private Long newest;
    }

    /** Fold one (node, device, mountpoint, type, state) row into {@code mounts}. */
    private static void addDiskRow(Map<String, Object> row, Set<String> excluded,
            String usedState, String freeState, DiskMounts mounts) {
        String nodeName = text(row.get("node_name"));
        String device = text(row.get("device_name"));
        String mount = text(row.get("mount_point"));
        String type = text(row.get("fs_type"));
        String state = text(row.get("state_name"));
        Double value = number(row.get(COLUMN_AGG_VALUE));
        if (isSkippedDiskRow(nodeName, state, value, type, excluded)) {
            return;
        }

        String deviceKey = nodeName + '\0' + emptyIfNull(device);
        String mountKey = deviceKey + '\0' + emptyIfNull(mount);
        double[] slot = mounts.byMount.computeIfAbsent(mountKey, unused -> new double[2]);
        slot[diskSlot(state.toLowerCase(Locale.ROOT), usedState, freeState)] += value;
        mounts.mountToDevice.put(mountKey, deviceKey);

        Double seen = number(row.get(COLUMN_LAST_SEEN));
        if (seen != null) {
            mounts.lastSeenByNode.merge(nodeName, seen.longValue(), Math::max);
        }
    }

    /** True for a row with no usable reading, or one on an excluded filesystem type. */
    private static boolean isSkippedDiskRow(String nodeName, String state, Double value, String type,
            Set<String> excluded) {
        if (nodeName == null || state == null || value == null || value < 0) {
            return true;
        }
        return type != null && excluded.contains(type.toLowerCase(Locale.ROOT));
    }

    /** {@code value}, or the empty string when it is null. */
    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    /** The {@code [used, free]} slot a (lower-cased) state folds into. */
    private static int diskSlot(String lowerState, String usedState, String freeState) {
        if (lowerState.equals(usedState)) {
            return 0;
        }
        if (lowerState.equals(freeState)) {
            return 1;
        }
        // Reserved blocks and anything else the scraper reports: real
        // capacity, but not free space anyone can use.
        return 0;
    }

    /**
     * Keep the largest reading per device in {@code byDevice}.
     *
     * @return how many mounts reported any capacity at all
     */
    private static int collapseBindMounts(DiskMounts mounts, Map<String, double[]> byDevice) {
        int mountCount = 0;
        for (Map.Entry<String, double[]> entry : mounts.byMount.entrySet()) {
            double[] slot = entry.getValue();
            if (slot[0] + slot[1] <= 0d) {
                continue;
            }
            mountCount++;
            String deviceKey = mounts.mountToDevice.get(entry.getKey());
            double[] existing = byDevice.get(deviceKey);
            if (existing == null || slot[0] + slot[1] > existing[0] + existing[1]) {
                byDevice.put(deviceKey, slot);
            }
        }
        return mountCount;
    }

    /** Fill {@code stats} per node from the per-device readings, and total them. */
    private static DiskSums sumDevices(Map<String, double[]> byDevice,
            Map<String, Long> lastSeenByNode, Map<String, NodeStat> stats) {
        DiskSums sums = new DiskSums();
        for (Map.Entry<String, double[]> entry : byDevice.entrySet()) {
            String nodeName = entry.getKey().substring(0, entry.getKey().indexOf('\0'));
            double used = entry.getValue()[0];
            double total = entry.getValue()[0] + entry.getValue()[1];

            NodeStat stat = stats.computeIfAbsent(nodeName, unused -> new NodeStat());
            stat.diskUsedBytes = (stat.diskUsedBytes == null ? 0L : stat.diskUsedBytes)
                    + Math.round(used);
            stat.diskTotalBytes = (stat.diskTotalBytes == null ? 0L : stat.diskTotalBytes)
                    + Math.round(total);
            stat.lastSeenMicros = lastSeenByNode.get(nodeName);

            sums.used += used;
            sums.total += total;
            if (stat.lastSeenMicros != null && (sums.newest == null || stat.lastSeenMicros > sums.newest)) {
                sums.newest = stat.lastSeenMicros;
            }
        }
        return sums;
    }

    private static void applyDiskPercents(Map<String, NodeStat> stats) {
        for (NodeStat stat : stats.values()) {
            if (stat.diskTotalBytes != null && stat.diskTotalBytes > 0) {
                stat.diskPercent = round1(
                        clamp(stat.diskUsedBytes / (double) stat.diskTotalBytes, 0d, 1d) * 100d);
            }
        }
    }

    // ── NFS storage ──────────────────────────────────────────────────────────

    /**
     * The shared storage behind the cluster's persistent volume claims.
     *
     * <p>Claims reporting an identical capacity are folded into one backing store
     * when {@code infrastructure.nfs.dedupe-by-capacity} is on, because the kubelet
     * reports the size of the export against every claim mounted from it. Measured
     * on this deployment: twenty-eight claims each reporting the same 95.9 GiB —
     * summed, that is 2.6 TiB of storage the cluster does not have.</p>
     *
     * <p>Contributes no per-node rows. A network share is not owned by any one
     * node, and attributing it to whichever node happened to mount it would put a
     * cluster-wide number in a per-node column.</p>
     */
    private Tile nfsTile(String node, long nowMicros) {
        InfrastructureProperties.Nfs config = properties.getNfs();
        String capacityStream = SqlSafety.validateIdentifier(config.getCapacityStream());
        String availableStream = SqlSafety.validateIdentifier(config.getAvailableStream());
        String volumeColumn = SqlSafety.validateIdentifier(config.getVolumeColumn());
        String namespaceColumn = SqlSafety.validateIdentifier(config.getNamespaceColumn());
        String podColumn = SqlSafety.validateIdentifier(config.getPodColumn());
        String nodeColumn = SqlSafety.validateIdentifier(properties.getNodeColumn());
        String nodeFilter = nodePredicate(nodeColumn, node);
        String typeFilter = volumeTypePredicate(config);
        List<String> windows = properties.windowsFor(config.getWindows());

        Function<String, Function<String, BiFunction<Long, Long, String>>> sqlFor = stream ->
                valueExpr -> (start, end) -> "SELECT " + namespaceColumn + " AS ns_name, "
                        + podColumn + " AS pod_name, "
                        + volumeColumn + " AS vol_name, "
                        + valueExpr + " AS agg_value, MAX(_timestamp) AS last_seen "
                        + "FROM \"" + stream + "\" "
                        + "WHERE _timestamp >= " + start + " AND _timestamp <= " + end
                        + nodeFilter + typeFilter + " "
                        + "GROUP BY " + namespaceColumn + ", " + podColumn + ", " + volumeColumn;

        Answer capacity = searchLadder(KIND_NFS, windows, sqlFor.apply(capacityStream));
        if (capacity.rows().isEmpty()) {
            return Tile.empty(KIND_NFS, LABEL_NFS, SCOPE_NFS, noDataMessage(capacityStream, capacity));
        }

        // Pinned to the window that answered for capacity, so the two halves
        // describe the same moment. Letting availability climb its own ladder
        // could pair a current capacity with an hour-old free figure.
        Answer available = searchLadder(KIND_NFS, List.of(capacity.window()),
                sqlFor.apply(availableStream));

        Map<String, Double> availableByKey = availableByVolume(available.rows());

        NfsStores nfs = new NfsStores();
        for (Map<String, Object> row : capacity.rows()) {
            addCapacityRow(row, availableByKey, config, nfs);
        }

        if (nfs.stores.isEmpty()) {
            return Tile.empty(KIND_NFS, LABEL_NFS, SCOPE_NFS, noDataMessage(capacityStream, capacity));
        }

        return new Tile(nfsItem(nfs, capacity.window(), availableStream, nowMicros), Map.of());
    }

    /** Backing stores found so far, and what the NFS tile reports alongside them. */
    private static final class NfsStores {
        // capacity → smallest free space seen on any claim reporting it.
        private final Map<Long, double[]> stores = new LinkedHashMap<>();
        private int claims;
        private Long newest;
    }

    private static Map<String, Double> availableByVolume(List<Map<String, Object>> rows) {
        Map<String, Double> availableByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = volumeKey(row);
            Double value = number(row.get(COLUMN_AGG_VALUE));
            if (key != null && value != null && value >= 0) {
                availableByKey.put(key, value);
            }
        }
        return availableByKey;
    }

    /** Fold one claim's capacity row into {@code nfs}. */
    private static void addCapacityRow(Map<String, Object> row, Map<String, Double> availableByKey,
            InfrastructureProperties.Nfs config, NfsStores nfs) {
        String key = volumeKey(row);
        Double cap = number(row.get(COLUMN_AGG_VALUE));
        if (key == null || cap == null || cap <= 0) {
            return;
        }
        nfs.claims++;
        Double free = availableByKey.get(key);

        // Without dedupe every claim is its own store, so the key has to be
        // unique per claim rather than per capacity.
        Long storeKey = config.isDedupeByCapacity() ? Math.round(cap) : (long) nfs.claims;
        double[] slot = nfs.stores.computeIfAbsent(storeKey,
                unused -> new double[] {Math.round(cap), free == null ? -1d : free});
        slot[0] = Math.max(slot[0], Math.round(cap));
        if (free != null) {
            slot[1] = slot[1] < 0 ? free : Math.min(slot[1], free);
        }

        nfs.newest = newerOf(nfs.newest, number(row.get(COLUMN_LAST_SEEN)));
    }

    /** {@code newest}, or {@code seen} when that is later. */
    private static Long newerOf(Long newest, Double seen) {
        if (seen != null && (newest == null || seen.longValue() > newest)) {
            return seen.longValue();
        }
        return newest;
    }

    private static InfrastructureResponseDTO.Item nfsItem(NfsStores nfs, String window,
            String availableStream, long nowMicros) {
        double totalSum = 0d;
        double freeSum = 0d;
        boolean anyFree = false;
        for (double[] slot : nfs.stores.values()) {
            totalSum += slot[0];
            if (slot[1] >= 0) {
                freeSum += slot[1];
                anyFree = true;
            }
        }

        InfrastructureResponseDTO.Item item = new InfrastructureResponseDTO.Item();
        item.setKind(KIND_NFS);
        item.setLabel(LABEL_NFS);
        item.setScope(SCOPE_NFS);
        item.setUnit("BYTES");
        item.setAvailable(true);
        item.setTotalBytes(Math.round(totalSum));
        item.setWindow(window);
        if (anyFree) {
            double used = Math.max(0d, totalSum - freeSum);
            item.setFreeBytes(Math.round(freeSum));
            item.setUsedBytes(Math.round(used));
            item.setUsedPercent(totalSum > 0 ? round1(clamp(used / totalSum, 0d, 1d) * 100d) : null);
        } else {
            // Capacity without availability is a size, not a utilization. Saying
            // 0% used would be a claim, and an alarming one to get wrong.
            item.setMessage("Capacity is known but " + availableStream
                    + " returned nothing, so used space cannot be computed.");
        }
        item.setDetail(nfs.claims + (nfs.claims == 1 ? " claim" : " claims")
                + " on " + nfs.stores.size()
                + (nfs.stores.size() == 1 ? " backing store" : " backing stores"));
        stampLastSeen(item, nfs.newest, nowMicros);
        return item;
    }

    private static String volumeKey(Map<String, Object> row) {
        String namespace = text(row.get("ns_name"));
        String pod = text(row.get("pod_name"));
        String volume = text(row.get("vol_name"));
        if (volume == null) {
            return null;
        }
        return (namespace == null ? "" : namespace) + '\0'
                + (pod == null ? "" : pod) + '\0' + volume;
    }

    private static String volumeTypePredicate(InfrastructureProperties.Nfs config) {
        List<String> types = config.getVolumeTypes();
        if (types == null || types.isEmpty()) {
            return "";
        }
        StringBuilder inList = new StringBuilder();
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            if (!inList.isEmpty()) {
                inList.append(", ");
            }
            inList.append('\'').append(SqlSafety.escapeStringLiteral(type.trim())).append('\'');
        }
        if (inList.isEmpty()) {
            return "";
        }
        return " AND " + SqlSafety.validateIdentifier(config.getVolumeTypeColumn())
                + " IN (" + inList + ")";
    }

    // ── Query plumbing ───────────────────────────────────────────────────────

    /** Rows from whichever rung of the ladder answered, and which rung that was. */
    private record Answer(List<Map<String, Object>> rows, String window) {
    }

    /**
     * Widen through the ladder for as long as widening reveals more of the
     * cluster, and keep the widest answer.
     *
     * <h3>Why "first rung with any rows" was wrong</h3>
     *
     * <p>These are cluster aggregates, so the denominator is whichever nodes
     * appear in the window — and the collectors do not all report at the same
     * rate. Measured on this deployment: inside five minutes the filesystem
     * scraper returns only {@code kubemaster}, while fifteen minutes returns all
     * eight nodes. Stopping at the first rung that had ANY rows therefore made
     * the Disk tile swing between 62% and 72% between consecutive polls, purely
     * because the set of nodes being averaged changed. Nothing about the disks
     * had changed at all, which is the worst kind of wrong number: it looks like
     * a trend.</p>
     *
     * <p>Every query here is a {@code GROUP BY}, so the row count IS the number
     * of series covered — which makes "did widening reveal more of the cluster"
     * a straight comparison rather than a heuristic. Climbing stops at the first
     * rung that adds nothing, so a healthy cluster pays two queries and the
     * answer is stable poll to poll.</p>
     *
     * <h3>What stops the climb from running away</h3>
     *
     * <p>Coverage alone would keep growing forever, because a wide enough window
     * reveals every node the cluster has ever had. Measured here: one hour adds a
     * node last seen 54 minutes ago, six hours adds ten more last seen up to 97
     * minutes ago, and the tile settles on an average over eighteen nodes of which
     * seven still exist. So each rung's rows are filtered by
     * {@code infrastructure.stale-after-seconds} BEFORE coverage is compared —
     * widening only counts when it finds a node that is actually still reporting,
     * which is what makes the climb terminate on its own.</p>
     *
     * <p>An empty result is NOT distinguishable from an unservable scan on this
     * OpenObserve instance — it answers HTTP 200 with zero hits either way — so
     * climbing past empty rungs is also the only available response to that.</p>
     */
    private Answer searchLadder(String kind, List<String> windows,
            Function<String, BiFunction<Long, Long, String>> sqlWith) {
        Answer answer = climb(kind, windows, sqlWith.apply(valueExpression()));
        if (!answer.rows().isEmpty() || latestUnsupported) {
            return answer;
        }

        // Empty AND we were using LAST_VALUE: it may simply not exist on this
        // OpenObserve build (verified supported on this deployment, but the
        // function is version-dependent, and MAX_BY for instance is absent here).
        // Prove it before concluding the tile has no data, and remember the answer
        // so every later tile skips straight to the form that works.
        if (properties.getValueAggregate() == InfrastructureProperties.ValueAggregate.LATEST) {
            Answer averaged = climb(kind, windows, sqlWith.apply(AVERAGE_EXPRESSION));
            if (!averaged.rows().isEmpty()) {
                latestUnsupported = true;
                log.warn("Infrastructure tiles are falling back to {} — this OpenObserve does not "
                        + "serve {}. Readings become window means, so they will move more slowly. "
                        + "Set infrastructure.value-aggregate=AVERAGE to make this explicit.",
                        AVERAGE_EXPRESSION, LATEST_EXPRESSION);
                return averaged;
            }
        }
        return answer;
    }

    /** The value expression in force, honouring a discovered lack of support. */
    private String valueExpression() {
        if (latestUnsupported
                || properties.getValueAggregate() == InfrastructureProperties.ValueAggregate.AVERAGE) {
            return AVERAGE_EXPRESSION;
        }
        return LATEST_EXPRESSION;
    }

    private Answer climb(String kind, List<String> windows, BiFunction<Long, Long, String> sqlFor) {
        long end = TimeRangeParser.defaultEndMicros();
        Answer best = new Answer(List.of(), null);
        boolean done = false;

        for (int i = 0; i < windows.size() && !done; i++) {
            String window = windows.get(i);
            String label = usableRung(window);
            Long start = (label != null) ? rungStart(kind, label, window, end) : null;
            if (start != null) {
                RungOutcome outcome = runRung(kind, label, best, sqlFor, start, end);
                best = outcome.best();
                done = outcome.done();
            }
        }
        return best;
    }

    /** The answer after one rung, and whether climbing further can still help. */
    private record RungOutcome(Answer best, boolean done) {
    }

    private RungOutcome runRung(String kind, String label, Answer best,
            BiFunction<Long, Long, String> sqlFor, long start, long end) {
        List<Map<String, Object>> rows;
        try {
            rows = openObserve.search(sqlFor.apply(start, end), start, end, 0,
                    MAX_GROUPED_ROWS, STREAM_TYPE);
        } catch (Exception e) {
            return new RungOutcome(afterFailedRung(kind, label, best, e), false);
        }
        if (rows == null) {
            return new RungOutcome(best, false);
        }

        List<Map<String, Object>> fresh = freshSeries(kind, label, rows, end);
        if (fresh.size() > best.rows().size()) {
            logWidened(kind, label, best, fresh);
            return new RungOutcome(new Answer(fresh, label), false);
        }
        if (!best.rows().isEmpty()) {
            // Widening revealed nothing still-reporting, and a wider window
            // never reveals less — so no further rung can either.
            return new RungOutcome(best, true);
        }
        return new RungOutcome(new Answer(List.of(), label), false);
    }

    /**
     * The answer to carry on with after a rung's query threw: a stream that does
     * not exist on this deployment throws here, and that is "this tile has no
     * source", not a failed request.
     */
    private static Answer afterFailedRung(String kind, String label, Answer best, Exception e) {
        log.debug("Infrastructure {} query failed over {}: {}", kind, label, e.getMessage());
        if (best.window() == null) {
            return new Answer(List.of(), label);
        }
        return best;
    }

    /** {@link #dropStaleSeries}, logging how many series it dropped. */
    private List<Map<String, Object>> freshSeries(String kind, String label,
            List<Map<String, Object>> rows, long end) {
        List<Map<String, Object>> fresh = dropStaleSeries(rows, end);
        if (fresh.size() < rows.size()) {
            log.debug("Infrastructure {} dropped {} series over {} as older than {}s",
                    kind, rows.size() - fresh.size(), label, properties.getStaleAfterSeconds());
        }
        return fresh;
    }

    private static void logWidened(String kind, String label, Answer best,
            List<Map<String, Object>> fresh) {
        if (!best.rows().isEmpty()) {
            log.debug("Infrastructure {} widened to {}: coverage {} -> {} series",
                    kind, label, best.rows().size(), fresh.size());
        }
    }

    /**
     * Rows whose newest sample is recent enough to describe the cluster now.
     *
     * <p>A row with no readable {@code last_seen} is KEPT: it came back from a
     * window that was asked for, and discarding data because its timestamp could
     * not be parsed would silently shrink the average.</p>
     */
    private List<Map<String, Object>> dropStaleSeries(List<Map<String, Object>> rows, long nowMicros) {
        int cutoffSeconds = properties.getStaleAfterSeconds();
        if (cutoffSeconds <= 0 || rows.isEmpty()) {
            return rows;
        }
        long cutoffMicros = nowMicros - cutoffSeconds * 1_000_000L;

        List<Map<String, Object>> fresh = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Double lastSeen = number(row.get(COLUMN_LAST_SEEN));
            if (lastSeen == null || lastSeen.longValue() >= cutoffMicros) {
                fresh.add(row);
            }
        }
        return fresh;
    }

    private static String nodePredicate(String nodeColumn, String node) {
        if (node == null || node.isBlank()) {
            return "";
        }
        return " AND " + nodeColumn + " = '" + SqlSafety.escapeStringLiteral(node.trim()) + "'";
    }

    private static String noDataMessage(String stream, Answer answer) {
        StringBuilder message = new StringBuilder("No data");
        if (stream != null) {
            message.append(" in '").append(stream).append('\'');
        }
        if (answer != null && answer.window() != null) {
            message.append(" as far back as ").append(answer.window());
        }
        message.append(". The collector that writes it may not be running, "
                + "or the stream name may differ on this deployment "
                + "(see infrastructure.* in application.properties).");
        return message.toString();
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /** One tile's answer: the item the UI renders, plus its per-node rows. */
    private record Tile(InfrastructureResponseDTO.Item item, Map<String, NodeStat> byNode) {

        static Tile empty(String kind, String label, String scope, String message) {
            InfrastructureResponseDTO.Item item = new InfrastructureResponseDTO.Item();
            item.setKind(kind);
            item.setLabel(label);
            item.setScope(scope);
            item.setUnit("PERCENT");
            item.setAvailable(false);
            item.setMessage(message);
            return new Tile(item, Map.of());
        }
    }

    /**
     * Mutable accumulator for one node.
     *
     * <p>Disk only, since CPU and Memory became pod-scoped: a pod's reading cannot
     * be attributed to a node, and carrying null CPU and Memory columns on every
     * node row would advertise a breakdown this service can no longer produce.</p>
     */
    private static final class NodeStat {
        private Double diskPercent;
        private Long diskUsedBytes;
        private Long diskTotalBytes;
        private Long lastSeenMicros;
    }

    private List<InfrastructureResponseDTO.Item> allUnavailable(String message) {
        List<InfrastructureResponseDTO.Item> items = new ArrayList<>(4);
        items.add(Tile.empty(KIND_CPU, LABEL_CPU, scopeFor(KIND_CPU), message).item());
        items.add(Tile.empty(KIND_MEMORY, LABEL_MEMORY, scopeFor(KIND_MEMORY), message).item());
        items.add(Tile.empty(KIND_DISK, LABEL_DISK, SCOPE_DISK, message).item());
        items.add(Tile.empty(KIND_NFS, LABEL_NFS, SCOPE_NFS, message).item());
        return items;
    }

    /**
     * The org the infrastructure streams live in — deliberately NOT the caller's.
     *
     * <p>Every other read in this service is tenant-scoped, and rightly so. These
     * four tiles are cluster facts, ingested once into one org, so a tenant-scoped
     * read points them at streams that do not exist there. Measured: the tiles were
     * blank on the {@code DLH}, {@code DLHLnM} and {@code ollama} orgs while all
     * seven source streams were live in {@code default}.</p>
     *
     * <p>A pleasant side effect: the cache key stops varying by tenant, so N
     * tenants watching the screen share one entry instead of holding N copies of
     * the same numbers.</p>
     */
    private String metricsOrg() {
        String configured = properties.getMetricsOrg();
        return (configured == null || configured.isBlank())
                ? openObserveConfig.getDefaultOrg()
                : configured.trim();
    }

    /**
     * What a tile of this kind measures, for the cases where no reading was
     * obtained and so nothing else can say.
     *
     * <p>A card whose numbers are missing still has a heading, and the heading is
     * the only thing telling the reader whether "no reading" means the pods or the
     * cluster went quiet.</p>
     */
    private String scopeFor(String kind) {
        return switch (kind) {
            case KIND_CPU -> properties.getCpu().getScope();
            case KIND_MEMORY -> properties.getMemory().getScope();
            case KIND_DISK -> SCOPE_DISK;
            case KIND_NFS -> SCOPE_NFS;
            default -> null;
        };
    }

    private static void stampLastSeen(InfrastructureResponseDTO.Item item, Long lastSeenMicros,
            long nowMicros) {
        if (lastSeenMicros == null || lastSeenMicros <= 0) {
            return;
        }
        item.setLastSeen(TimeFormatter.microsToIso(lastSeenMicros));
        item.setLastSeenAgoSec(agoSeconds(nowMicros, lastSeenMicros));
    }

    private static long agoSeconds(long nowMicros, long thenMicros) {
        return Math.max(0L, (nowMicros - thenMicros) / 1_000_000L);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    /**
     * OpenObserve returns a numeric column as a Double, a Long or a String
     * depending on the aggregate and the column's inferred type, so every read
     * goes through here rather than casting.
     */
    private static Double number(Object raw) {
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
