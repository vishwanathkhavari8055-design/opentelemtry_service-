package com.trinity.observability.serviceimpl;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.services.ServiceVitalsRequestDTO;
import com.trinity.observability.dto.services.ServiceVitalsResponseDTO;
import com.trinity.observability.util.RequestScopedFanout;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-service "vital signs" roll-up.
 *
 * <p>
 * Owns the entire {@code GET /api/services/{name}/vitals} pipeline: SQL
 * queries against the Actuator-derived metric streams in OpenObserve, plus
 * the counter→rate / unit-conversion math that the UI needs. It exists as
 * its own bean so {@link ServicesServiceImpl} stays focused on uptime; this
 * class' single responsibility is "given a service name and a window, produce
 * a {@link ServiceVitalsResponseDTO}".
 * </p>
 *
 * <p>
 * Every tile is fetched in isolation — a missing or erroring stream
 * produces an absent tile (with a diagnostic note in
 * {@link ServiceVitalsResponseDTO#getNotes()})
 * rather than a 500. Counter streams (request count, error log count) are
 * converted to per-bucket rates here, not in the UI, so the front-end stays
 * dumb and the rate math lives in one place.
 * </p>
 *
 * <p>
 * Call order for the eight OpenObserve queries — tests rely on this:
 * </p>
 * <ol>
 * <li>{@code http_server_requests_seconds_count} (all statuses) — req rate</li>
 * <li>same stream, {@code status LIKE '5%'} — error rate</li>
 * <li>{@code http_server_requests_seconds_max} — latency</li>
 * <li>{@code hikaricp_connections_active} — DB pool</li>
 * <li>{@code jvm_memory_used} (area='heap') — heap</li>
 * <li>{@code logback_events_total} (level='ERROR') — error logs</li>
 * <li>{@code http_client_requests_seconds_count} — downstream calls</li>
 * <li>{@code http_client_requests_seconds_max} — downstream latency
 * (optional)</li>
 * </ol>
 */
@Service
public class ServiceVitalsAggregator {

    private static final Logger log = LogManager.getLogger(ServiceVitalsAggregator.class);
    private static final String STREAM_TYPE_METRICS = "metrics";

    /**
     * Streams whose per-timestamp samples are summed (across areas, pools,
     * levels...) before they are averaged into a bucket.
     */
    private static final Set<String> SUM_BY_TIMESTAMP_STREAMS = Set.of(
            "jvm_memory_used",
            "jvm_thread_count",
            "http_server_request_duration_count",
            "hikaricp_connections_active",
            "log4j2_events_total");

    private final OpenObserveClientService openObserveClientService;
    private final RequestScopedFanout fanout;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public ServiceVitalsAggregator(OpenObserveClientService openObserveClientService,
            RequestScopedFanout fanout) {
        this.openObserveClientService = openObserveClientService;
        this.fanout = fanout;
    }

    public ServiceVitalsResponseDTO aggregate(String serviceName, ServiceVitalsRequestDTO request) {
        long t0 = System.nanoTime();

        String windowExpr = windowExpr(request);
        int bucketSec = bucketSec(request);
        long endMicros = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(windowExpr, endMicros);
        long windowSec = Math.max(1L, (endMicros - startMicros) / 1_000_000L);

        ServiceVitalsResponseDTO out = newResponseShell(serviceName, startMicros, endMicros, bucketSec);

        if (!openObserveEnabled) {
            out.setSupported(false);
            log.info("aggregate() ES path: supported=false");
            return out;
        }
        if (serviceName == null || serviceName.isBlank()) {
            out.setSupported(true);
            out.getNotes().add("serviceName path variable was empty");
            return out;
        }
        out.setSupported(true);

        String escapedSvc = SqlSafety.escapeStringLiteral(serviceName);
        VitalsCtx ctx = new VitalsCtx(escapedSvc, bucketSec, windowSec, startMicros, endMicros);

        // Independent, best-effort, and run concurrently — none of these six reads
        // consumes another's output, so in series the request paid the SUM of eight
        // OpenObserve round trips instead of the slowest one.
        //
        // Each helper fills its OWN response shell rather than sharing `out`. The
        // tiles map is a LinkedHashMap whose insertion order IS the presentation
        // order in the UI (and the notes/downstream lists are plain ArrayLists), so
        // letting six threads write into one response would both corrupt the
        // collections and scramble the tile order. The shells are merged below on
        // this thread, in the original call order, which keeps the response
        // byte-identical to the sequential version.
        //
        // reqRate + errorRate share the total-req query, so they're filled
        // together (one query for total, one for 5xx subset). The rest are
        // standalone tile-per-query helpers.
        List<ServiceVitalsResponseDTO> shells = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (BiConsumer<ServiceVitalsResponseDTO, VitalsCtx> filler : List.<BiConsumer<ServiceVitalsResponseDTO, VitalsCtx>>of(
                this::fillReqAndErrorRateTiles,
                this::fillLatencyTile,
                this::fillPoolActiveTile,
                this::fillHeapUsedTile,
                this::fillErrorLogRateTile,
                this::fillDownstream)) {
            ServiceVitalsResponseDTO shell = newResponseShell(serviceName, startMicros, endMicros, bucketSec);
            shells.add(shell);
            tasks.add(() -> filler.accept(shell, ctx));
        }
        fanout.runAll(tasks);

        mergeShells(out, shells);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("aggregate({}) returned in {} ms (tiles={}, downstream={}, notes={})",
                serviceName, elapsedMs, out.getTiles().size(),
                out.getDownstream().size(), out.getNotes().size());
        return out;
    }

    /** The requested window expression, or the summary default when none was given. */
    private static String windowExpr(ServiceVitalsRequestDTO request) {
        return request != null && request.getWindow() != null
                ? request.getWindow()
                : AppConstants.DEFAULT_SUMMARY_WINDOW;
    }

    /** The requested bucket width, or the vitals default when none (or a non-positive one) was given. */
    private static int bucketSec(ServiceVitalsRequestDTO request) {
        return request != null && request.getBucketSec() != null && request.getBucketSec() > 0
                ? request.getBucketSec()
                : AppConstants.DEFAULT_VITALS_BUCKET_SEC;
    }

    /** Fold the per-helper shells into {@code out}, in call order (which is the UI's tile order). */
    private static void mergeShells(ServiceVitalsResponseDTO out, List<ServiceVitalsResponseDTO> shells) {
        for (ServiceVitalsResponseDTO shell : shells) {
            out.getTiles().putAll(shell.getTiles());
            out.getNotes().addAll(shell.getNotes());
            out.getDownstream().addAll(shell.getDownstream());
        }
    }

    private ServiceVitalsResponseDTO newResponseShell(String serviceName,
            long startMicros, long endMicros, int bucketSec) {
        ServiceVitalsResponseDTO out = new ServiceVitalsResponseDTO();
        out.setServiceName(serviceName);
        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));
        out.setBucketSec(bucketSec);
        out.setBackend(openObserveEnabled ? "openobserve" : "elasticsearch");
        out.setTiles(new LinkedHashMap<>());
        out.setDownstream(new ArrayList<>());
        out.setNotes(new ArrayList<>());
        return out;
    }

    /** Bundled query context — saves passing 5 args through every helper. */
    private static final class VitalsCtx {
        final String escapedSvc;
        final int bucketSec;
        final long windowSec;
        final long startMicros;
        final long endMicros;

        VitalsCtx(String escapedSvc, int bucketSec, long windowSec, long startMicros, long endMicros) {
            this.escapedSvc = escapedSvc;
            this.bucketSec = bucketSec;
            this.windowSec = windowSec;
            this.startMicros = startMicros;
            this.endMicros = endMicros;
        }
    }

    // ── tile builders ────────────────────────────────────────────────────────

    /**
     * Fill both {@code reqRate} (req/s) and {@code errorRate} (% 5xx). Shared
     * helper because both source the same
     * {@code http_server_requests_seconds_count}
     * stream, and re-querying it would double the OpenObserve traffic for no
     * gain. Total query fires first, then the 5xx-filtered subset; tests rely
     * on this call ordering.
     */
    private void fillReqAndErrorRateTiles(ServiceVitalsResponseDTO out, VitalsCtx ctx) {
        List<ServiceVitalsResponseDTO.Point> totalRaw = bucketedAvgValue(
                AppConstants.VITALS_HTTP_REQ_COUNT_STREAM, ctx, /* extraFilter */ null);
        if (totalRaw == null) {
            out.getNotes().add("reqRate: " + AppConstants.VITALS_HTTP_REQ_COUNT_STREAM + " stream unavailable");
            // 5xx subset still gets queried so call ordering stays stable for
            // tests and the err note can be distinct from the total note.
        }

        List<ServiceVitalsResponseDTO.Point> errRaw = bucketedAvgValue(
                AppConstants.VITALS_HTTP_REQ_COUNT_STREAM, ctx, "http_response_status_code LIKE '5%'");
        if (errRaw == null) {
            out.getNotes().add("errorRate: failed to query 5xx subset");
        }

        if (totalRaw != null) {
            List<ServiceVitalsResponseDTO.Point> totRate = toRateSparkline(totalRaw, ctx.bucketSec);
            out.getTiles().put("reqRate",
                    new ServiceVitalsResponseDTO.Tile("Requests", "req/s", lastValue(totRate), totRate));

            if (errRaw != null) {
                List<ServiceVitalsResponseDTO.Point> errRate = toRateSparkline(errRaw, ctx.bucketSec);
                List<ServiceVitalsResponseDTO.Point> pctSeries = ratioPercent(errRate, totRate);
                out.getTiles().put("errorRate",
                        new ServiceVitalsResponseDTO.Tile("Error rate", "%", lastValue(pctSeries), pctSeries));
            }
        }
    }

    /**
     * "Latency" tile sources {@code http_server_requests_seconds_max} — the
     * rolling-max latency gauge Micrometer exposes by default. Not strictly
     * p95 (it's the recent max over Micrometer's decay window), but a useful
     * tail-latency signal that's free for every Spring Boot service.
     */
    private void fillLatencyTile(ServiceVitalsResponseDTO out, VitalsCtx ctx) {
        List<ServiceVitalsResponseDTO.Point> sec = bucketedAvgValue(
                AppConstants.VITALS_HTTP_REQ_MAX_STREAM, ctx, null);
        if (sec == null) {
            out.getNotes().add("latency: " + AppConstants.VITALS_HTTP_REQ_MAX_STREAM
                    + " stream unavailable — Micrometer max-latency gauge missing");
            return;
        }
        List<ServiceVitalsResponseDTO.Point> ms = scaleSeries(sec, 1000.0);
        out.getTiles().put("latencyMs",
                new ServiceVitalsResponseDTO.Tile("Latency (max)", "ms", lastValue(ms), ms));
    }

    private void fillPoolActiveTile(ServiceVitalsResponseDTO out, VitalsCtx ctx) {
        List<ServiceVitalsResponseDTO.Point> pts = bucketedAvgValue(
                AppConstants.VITALS_HIKARI_ACTIVE_STREAM, ctx, null);
        if (pts == null) {
            out.getNotes().add("poolActive: " + AppConstants.VITALS_HIKARI_ACTIVE_STREAM
                    + " unavailable (Hikari may not be on the classpath)");
            return;
        }
        out.getTiles().put("poolActive",
                new ServiceVitalsResponseDTO.Tile("DB pool (active)", "", lastValue(pts), pts));
    }

    private void fillHeapUsedTile(ServiceVitalsResponseDTO out, VitalsCtx ctx) {
        // List<ServiceVitalsResponseDTO.Point> bytes = bucketedAvgValue(
        // AppConstants.VITALS_JVM_MEMORY_USED_STREAM, ctx, "area = 'heap'")
        List<ServiceVitalsResponseDTO.Point> bytes = bucketedAvgValue(
                AppConstants.VITALS_JVM_MEMORY_USED_STREAM, ctx, "jvm_memory_type = 'heap'");

        if (bytes == null) {
            out.getNotes().add("heapUsedMb: " + AppConstants.VITALS_JVM_MEMORY_USED_STREAM + " unavailable");
            return;
        }
        // Bytes → MB. Per-bucket AVG averages across heap regions; the trend
        // is identical to "total heap used" and that's what the tile shows.
        List<ServiceVitalsResponseDTO.Point> mb = scaleSeries(bytes, 1.0 / (1024.0 * 1024.0));
        out.getTiles().put("heapUsedMb",
                new ServiceVitalsResponseDTO.Tile("JVM heap", "MB", lastValue(mb), mb));
    }

    private void fillErrorLogRateTile(ServiceVitalsResponseDTO out, VitalsCtx ctx) {
        List<ServiceVitalsResponseDTO.Point> raw = bucketedAvgValue(
                AppConstants.VITALS_LOGBACK_EVENTS_STREAM, ctx, "level = 'error'");
        if (raw == null) {
            out.getNotes().add("errorLogRate: " + AppConstants.VITALS_LOGBACK_EVENTS_STREAM + " unavailable");
            return;
        }
        // toRateSparkline gives /sec; convert to /min for a more legible tile.
        List<ServiceVitalsResponseDTO.Point> perSec = toRateSparkline(raw, ctx.bucketSec);
        List<ServiceVitalsResponseDTO.Point> perMin = scaleSeries(perSec, 60.0);
        out.getTiles().put("errorLogRate",
                new ServiceVitalsResponseDTO.Tile("Error logs", "/min", lastValue(perMin), perMin));
    }

    private void fillDownstream(ServiceVitalsResponseDTO out, VitalsCtx ctx) {
        // Group by client_name; (MAX(value)-MIN(value))/windowSec → req/s.
        // We collapse across HTTP status, method, uri etc to keep one row per
        // dependency. Latency uses the http_client_requests_seconds_max stream
        // when available (one more query — same group-by).
        String safeStream = SqlSafety.validateIdentifier(AppConstants.VITALS_HTTP_CLIENT_COUNT_STREAM);

        String clientNameExpr = "COALESCE(NULLIF(peer_service, ''), CONCAT(server_address, COALESCE(url_path, '')))";

        String sql = "SELECT " + clientNameExpr + " AS client_name, MAX(value) AS end_v, MIN(value) AS start_v "
                + "FROM \"" + safeStream + "\" "
                + "WHERE _timestamp >= " + ctx.startMicros + " "
                + "AND _timestamp <= " + ctx.endMicros + " "
                + "AND service_name = '" + ctx.escapedSvc + "' "
                + "AND (peer_service IS NOT NULL OR server_address IS NOT NULL) "
                + "GROUP BY " + clientNameExpr + " "
                + "ORDER BY end_v DESC "
                + "LIMIT 20";

        List<Map<String, Object>> hits;
        try {
            hits = openObserveClientService.search(sql, ctx.startMicros, ctx.endMicros, 0, 50, STREAM_TYPE_METRICS);
        } catch (Exception e) {
            out.getNotes().add("downstream: " + safeStream + " unavailable ("
                    + e.getClass().getSimpleName() + ")");
            return;
        }

        // Optional: per-client latency. One more query, same key.
        Map<String, Double> latencyByClient = fetchClientLatencyMs(ctx);

        for (Map<String, Object> hit : hits) {
            ServiceVitalsResponseDTO.DownstreamCall row = downstreamRow(hit, latencyByClient, ctx.windowSec);
            if (row != null) {
                out.getDownstream().add(row);
            }
        }
    }

    /**
     * One downstream row from a grouped hit; null when the hit has no client
     * name or no end value.
     */
    private static ServiceVitalsResponseDTO.DownstreamCall downstreamRow(Map<String, Object> hit,
            Map<String, Double> latencyByClient, long windowSec) {
        String client = (String) hit.get("client_name");
        Number endV = (Number) hit.get("end_v");
        if (client == null || client.isEmpty() || endV == null) {
            return null;
        }
        Number startV = (Number) hit.get("start_v");
        double delta = endV.doubleValue() - (startV == null ? 0.0 : startV.doubleValue());
        double reqRate = Math.max(0.0, delta) / windowSec;

        ServiceVitalsResponseDTO.DownstreamCall row = new ServiceVitalsResponseDTO.DownstreamCall();
        row.setClientName(client);
        row.setReqRate(reqRate);
        if (latencyByClient != null) {
            row.setMaxLatencyMs(latencyByClient.get(client));
        }
        return row;
    }

    /**
     * Optional per-downstream max latency — returns null if the stream is missing.
     */
    @SuppressWarnings("java:S1168") // Returning null signals that the optional stream was missing or failed
    private Map<String, Double> fetchClientLatencyMs(VitalsCtx ctx) {
        String safeStream = SqlSafety.validateIdentifier(AppConstants.VITALS_HTTP_CLIENT_MAX_STREAM);
        String clientNameExpr = "COALESCE(NULLIF(peer_service, ''), CONCAT(server_address, COALESCE(url_path, '')))";

        String sql = "SELECT " + clientNameExpr + " AS client_name, AVG(value) AS v "
                + "FROM \"" + safeStream + "\" "
                + "WHERE _timestamp >= " + ctx.startMicros + " "
                + "AND _timestamp <= " + ctx.endMicros + " "
                + "AND service_name = '" + ctx.escapedSvc + "' "
                + "AND (peer_service IS NOT NULL OR server_address IS NOT NULL) "
                + "GROUP BY " + clientNameExpr;
        try {
            List<Map<String, Object>> hits = openObserveClientService.search(
                    sql, ctx.startMicros, ctx.endMicros, 0, 50, STREAM_TYPE_METRICS);
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map<String, Object> hit : hits) {
                String client = (String) hit.get("client_name");
                Number v = (Number) hit.get("v");
                if (client != null && v != null)
                    out.put(client, v.doubleValue() * 1000.0);
            }
            return out;
        } catch (Exception e) {
            // Missing _max stream is unremarkable for many setups — don't note.
            return null;
        }
    }

    // ── shared SQL + math helpers ────────────────────────────────────────────

    /**
     * Bucket the given stream by {@code DATE_BIN} and average {@code value}
     * within each bucket. Returns a list of points (bucket-start → avg), or
     * {@code null} when the underlying stream is missing/erroring.
     */
    @SuppressWarnings("java:S1168") // Returning null distinguishes missing/failing stream from a stream with 0 data points
    private List<ServiceVitalsResponseDTO.Point> bucketedAvgValue(String stream, VitalsCtx ctx, String extraFilter) {
        String safeStream = SqlSafety.validateIdentifier(stream);
        String filterClause = (extraFilter == null || extraFilter.isEmpty())
                ? ""
                : "AND " + extraFilter + " ";

        // stream is non-null here: validateIdentifier rejects null.
        boolean sumByTimestamp = SUM_BY_TIMESTAMP_STREAMS.contains(stream);

        String sql;
        if (sumByTimestamp) {
            sql = "SELECT "
                + "DATE_BIN(INTERVAL '" + ctx.bucketSec + " seconds', "
                + "to_timestamp_micros(_timestamp), "
                + "TIMESTAMP '1970-01-01') AS bucket, "
                + "AVG(ts_sum) AS v "
                + "FROM ("
                + "SELECT _timestamp, SUM(value) AS ts_sum "
                + "FROM \"" + safeStream + "\" "
                + "WHERE _timestamp >= " + ctx.startMicros + " "
                + "AND _timestamp <= " + ctx.endMicros + " "
                + "AND service_name = '" + ctx.escapedSvc + "' "
                + filterClause
                + "GROUP BY _timestamp"
                + ") "
                + "GROUP BY bucket "
                + "ORDER BY bucket";
        } else {
            sql = "SELECT "
                + "DATE_BIN(INTERVAL '" + ctx.bucketSec + " seconds', "
                + "to_timestamp_micros(_timestamp), "
                + "TIMESTAMP '1970-01-01') AS bucket, "
                + "AVG(value) AS v "
                + "FROM \"" + safeStream + "\" "
                + "WHERE _timestamp >= " + ctx.startMicros + " "
                + "AND _timestamp <= " + ctx.endMicros + " "
                + "AND service_name = '" + ctx.escapedSvc + "' "
                + filterClause
                + "GROUP BY bucket "
                + "ORDER BY bucket";
        }

        List<Map<String, Object>> hits;
        try {
            hits = openObserveClientService.search(sql, ctx.startMicros, ctx.endMicros, 0, 2000, STREAM_TYPE_METRICS);
        } catch (Exception e) {
            log.debug("vitals stream {} skipped: {}", stream, e.getMessage());
            return null;
        }
        return toPoints(hits);
    }

    /** Bucket rows as points, skipping any row without a timestamp or value. */
    private static List<ServiceVitalsResponseDTO.Point> toPoints(List<Map<String, Object>> hits) {
        List<ServiceVitalsResponseDTO.Point> points = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            String ts = String.valueOf(hit.get("bucket"));
            Number v = (Number) hit.get("v");
            if (ts == null || v == null)
                continue;
            points.add(new ServiceVitalsResponseDTO.Point(ts, v.doubleValue()));
        }
        return points;
    }

    /**
     * Convert a cumulative-counter bucketed series (AVG(value) per bucket) into
     * a per-bucket rate series. For each adjacent pair
     * {@code (bucket_i, bucket_{i+1})}
     * the rate is {@code (v_{i+1} - v_i) / bucketSec}. Negative deltas (caused
     * by a process restart resetting the counter) are clamped to 0. The first
     * bucket is dropped because there's no prior reference.
     */
    static List<ServiceVitalsResponseDTO.Point> toRateSparkline(
            List<ServiceVitalsResponseDTO.Point> raw, int bucketSec) {
        if (raw == null || raw.size() < 2)
            return new ArrayList<>();
        List<ServiceVitalsResponseDTO.Point> rate = new ArrayList<>(raw.size() - 1);
        for (int i = 1; i < raw.size(); i++) {
            double delta = raw.get(i).getValue() - raw.get(i - 1).getValue();
            double rps = Math.max(0.0, delta) / Math.max(1, bucketSec);
            rate.add(new ServiceVitalsResponseDTO.Point(raw.get(i).getTimestamp(), rps));
        }
        return rate;
    }

    /**
     * Element-wise {@code num / den * 100}. Aligns by index (assumes both
     * lists came from the same DATE_BIN grouping, which is true for us). When
     * the denominator is 0, emits 0 — that's how the UI should treat
     * "no traffic" rather than NaN.
     */
    static List<ServiceVitalsResponseDTO.Point> ratioPercent(
            List<ServiceVitalsResponseDTO.Point> num,
            List<ServiceVitalsResponseDTO.Point> den) {
        if (num == null || den == null)
            return new ArrayList<>();
        int n = Math.min(num.size(), den.size());
        List<ServiceVitalsResponseDTO.Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double d = den.get(i).getValue();
            double pct = (d <= 0.0) ? 0.0 : (num.get(i).getValue() / d) * 100.0;
            out.add(new ServiceVitalsResponseDTO.Point(num.get(i).getTimestamp(), pct));
        }
        return out;
    }

    static List<ServiceVitalsResponseDTO.Point> scaleSeries(
            List<ServiceVitalsResponseDTO.Point> src, double k) {
        if (src == null)
            return new ArrayList<>();
        List<ServiceVitalsResponseDTO.Point> out = new ArrayList<>(src.size());
        for (ServiceVitalsResponseDTO.Point p : src) {
            out.add(new ServiceVitalsResponseDTO.Point(p.getTimestamp(), p.getValue() * k));
        }
        return out;
    }

    static Double lastValue(List<ServiceVitalsResponseDTO.Point> pts) {
        if (pts == null || pts.isEmpty())
            return null;
        return pts.get(pts.size() - 1).getValue();
    }
}
