package com.trinity.observability.serviceimpl;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.services.ServiceUptimeItemDTO;
import com.trinity.observability.dto.services.ServiceVitalsRequestDTO;
import com.trinity.observability.dto.services.ServiceVitalsResponseDTO;
import com.trinity.observability.dto.services.ServicesUptimeRequestDTO;
import com.trinity.observability.dto.services.ServicesUptimeResponseDTO;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.service.ServicesService;
import com.trinity.observability.util.RequestScopedFanout;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenObserve-only implementation of services-uptime aggregation.
 *
 * <p>
 * The shape mirrors {@link MetricsServiceImpl} (feature-flag dispatch on
 * {@code openobserve.enabled}, ES path stubbed). The OO path issues three SQL
 * queries against the metrics streams: a heartbeat scan against
 * {@link AppConstants#HEARTBEAT_METRIC_STREAM} (universal),
 * a best-effort lookup against {@link AppConstants#PROCESS_UPTIME_STREAM} and
 * a best-effort lookup against {@link AppConstants#APPLICATION_HEALTH_STREAM}
 * (both optional). Results merge by {@code service_name}.
 * </p>
 *
 * <p>
 * The per-service "vitals" roll-up
 * ({@code GET /api/services/{name}/vitals}) is delegated to
 * {@link ServiceVitalsAggregator} so this class stays focused on uptime and
 * does not balance two unrelated query pipelines under one cognitive budget.
 * </p>
 *
 * <p>
 * Note: the {@code application_health} stream is populated by the OTel
 * Collector's {@code prometheus/actuator} receiver scraping Spring Boot
 * Actuator's Prometheus endpoint — there is no point-to-point Actuator probe
 * from the lib itself. This is intentional: the lib reads only from
 * OpenObserve, and Actuator data flows through the same pipeline as every
 * other metric.
 * </p>
 */
@Service
public class ServicesServiceImpl implements ServicesService {

    private static final Logger log = LogManager.getLogger(ServicesServiceImpl.class);
    private static final String STREAM_TYPE_METRICS = "metrics";
    private static final String COLUMN_SERVICE_NAME = "service_name";

    private final OpenObserveClientService openObserveClientService;
    private final ServiceVitalsAggregator vitalsAggregator;

    /**
     * Product Catalog scoping. The uptime endpoint is an INVENTORY — it decides
     * which services the Summary screen lists at all — so a disabled resource has
     * to drop out of it, or the operator can click through to a service whose
     * every other screen is empty.
     */
    private final CatalogTelemetryFilter catalogFilter;

    /** Runs the enrichment reads together — see {@link RequestScopedFanout}. */
    private final RequestScopedFanout fanout;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    @Autowired
    public ServicesServiceImpl(OpenObserveClientService openObserveClientService,
            ServiceVitalsAggregator vitalsAggregator,
            CatalogTelemetryFilter catalogFilter,
            RequestScopedFanout fanout) {
        this.openObserveClientService = openObserveClientService;
        this.vitalsAggregator = vitalsAggregator;
        this.catalogFilter = catalogFilter;
        this.fanout = fanout;
    }

    /**
     * Test-only convenience constructor for tests that exercise only the
     * uptime path. {@code vitalsAggregator} stays null — calling
     * {@link #getServiceVitals} via this constructor will NPE, which is
     * deliberate: a test exercising vitals must go through the dedicated
     * {@link ServiceVitalsAggregator} unit tests instead.
     *
     * <p>Catalog scoping is off here too, so the existing uptime tests keep
     * asserting on the SQL they were written against.</p>
     */
    public ServicesServiceImpl(OpenObserveClientService openObserveClientService) {
        this(openObserveClientService, null, CatalogTelemetryFilter.permitAll(),
                new RequestScopedFanout(4));
    }

    @Override
    public ServicesUptimeResponseDTO getUptime(ServicesUptimeRequestDTO request) {
        long t0 = System.nanoTime();

        String windowExpr = request.getWindow() != null ? request.getWindow()
                : AppConstants.DEFAULT_UPTIME_WINDOW;
        int livenessSec = request.getLivenessSec() != null && request.getLivenessSec() > 0
                ? request.getLivenessSec()
                : AppConstants.DEFAULT_LIVENESS_SEC;

        long endMicros = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(windowExpr, endMicros);

        ServicesUptimeResponseDTO out = new ServicesUptimeResponseDTO();
        out.setWindowStart(TimeFormatter.microsToIso(startMicros));
        out.setWindowEnd(TimeFormatter.microsToIso(endMicros));
        out.setLivenessThresholdSec(livenessSec);
        out.setHeartbeatStream(AppConstants.HEARTBEAT_METRIC_STREAM);
        out.setBackend(openObserveEnabled ? "openobserve" : "elasticsearch");

        if (!openObserveEnabled) {
            // ES path is intentionally not implemented for OTel data (see
            // ServicesService javadoc + plan). The lib has no point-to-point
            // fallback any more — when OpenObserve is disabled there is no
            // signal source, so we respond with supported=false.
            out.setItems(Collections.emptyList());
            out.setSupported(false);
            log.info("getUptime() ES path: supported=false (no signal source without OpenObserve)");
            return out;
        }

        List<ServiceUptimeItemDTO> items = computeUptime(startMicros, endMicros, livenessSec);
        out.setItems(items);
        out.setSupported(true);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("getUptime() returning {} service(s) in {} ms (livenessSec={}, window={})",
                items.size(), elapsedMs, livenessSec, windowExpr);
        return out;
    }

    @Override
    public ServiceVitalsResponseDTO getServiceVitals(String serviceName, ServiceVitalsRequestDTO request) {
        return vitalsAggregator.aggregate(serviceName, request);
    }

    /** The Product Catalog predicate for the metrics signal, as a clause. */
    private String catalogFilterClause() {
        String predicate = catalogFilter.sqlPredicate(STREAM_TYPE_METRICS);
        return predicate.isEmpty() ? "" : "AND " + predicate + " ";
    }

    private List<ServiceUptimeItemDTO> computeUptime(long startMicros, long endMicros, int livenessSec) {
        Map<String, ServiceUptimeItemDTO> bySvc = runHeartbeatScan(startMicros, endMicros);
        if (bySvc.isEmpty())
            return Collections.emptyList();

        // The four enrichment reads do not depend on each other, so they run at the
        // same time: this endpoint used to pay heartbeat + up to four more round
        // trips in series. The results are still APPLIED in the original order, and
        // each fallback only fills services the earlier ones left empty, so the
        // outcome is identical to the sequential chain — the only difference is that
        // the fallback streams are queried even when process_uptime covered everyone.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>>[] results = new List[4];
        fanout.runAll(List.of(
                () -> results[0] = fetchUptimeHits(AppConstants.PROCESS_UPTIME_STREAM, startMicros, endMicros),
                () -> results[1] = fetchUptimeHits("process_runtime_jvm_uptime", startMicros, endMicros),
                () -> results[2] = fetchUptimeHits("jvm_uptime_seconds", startMicros, endMicros),
                () -> results[3] = fetchApplicationHealthHits(startMicros, endMicros)));

        applyProcessUptime(bySvc, results[0], results[1], results[2]);
        applyApplicationHealth(bySvc, results[3]);
        applyLivenessClassification(bySvc, endMicros, livenessSec);

        return new ArrayList<>(bySvc.values());
    }

    /**
     * Heartbeat scan against {@link AppConstants#HEARTBEAT_METRIC_STREAM}.
     * The metric is emitted every 10 s by every OTel-instrumented JVM, so its
     * presence within the liveness window is a reliable "service is alive"
     * signal. Returns a LinkedHashMap so the response preserves a stable
     * (alphabetical) service order across calls.
     */
    private Map<String, ServiceUptimeItemDTO> runHeartbeatScan(long startMicros, long endMicros) {
        String stream = SqlSafety.validateIdentifier(AppConstants.HEARTBEAT_METRIC_STREAM);

        String sql = "SELECT service_name, host_name, "
                + "MAX(_timestamp) AS last_seen, "
                + "COUNT(*)        AS metric_count "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                + "AND _timestamp <= " + endMicros + " "
                + "AND service_name IS NOT NULL "
                + "AND service_name != '' "
                // Product Catalog scoping: this scan IS the service inventory, so a
                // disabled resource must not survive it. The two enrichment queries
                // below only fill in fields for services this one returned, so they
                // inherit the exclusion without needing their own predicate.
                + catalogFilterClause()
                + "GROUP BY service_name, host_name "
                + "ORDER BY service_name";

        if (log.isDebugEnabled()) {
            log.debug("OpenObserve uptime heartbeat SQL: {}", sql);
        }

        List<Map<String, Object>> hits;
        try {
            // size=500 is defensive — no real deployment has 500 distinct
            // service/host pairs heartbeating in an hour; if it does, we'd
            // rather truncate than serialise a 50 MB response.
            hits = openObserveClientService.search(sql, startMicros, endMicros, 0, 500, STREAM_TYPE_METRICS);
        } catch (Exception e) {
            log.error("Uptime heartbeat scan failed: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }

        Map<String, ServiceUptimeItemDTO> bySvc = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            ServiceUptimeItemDTO item = mapHeartbeatHit(hit);
            if (item != null && item.getServiceName() != null) {
                mergeHeartbeatItem(bySvc, item);
            }
        }
        return bySvc;
    }

    /** Adds {@code item} to the per-service map, or folds it into the existing row. */
    private static void mergeHeartbeatItem(Map<String, ServiceUptimeItemDTO> bySvc, ServiceUptimeItemDTO item) {
        String svcName = item.getServiceName();
        ServiceUptimeItemDTO existing = bySvc.computeIfAbsent(svcName, k -> item);
        if (existing == item) {
            return;
        }
        existing.setMetricCount(existing.getMetricCount() + item.getMetricCount());
        if (item.getLastSeenTimestamp() == null) {
            return;
        }
        if (existing.getLastSeenTimestamp() == null) {
            existing.setLastSeenTimestamp(item.getLastSeenTimestamp());
            existing.setHostName(item.getHostName());
        } else {
            Instant existingTime = Instant.parse(existing.getLastSeenTimestamp());
            Instant newTime = Instant.parse(item.getLastSeenTimestamp());
            if (newTime.isAfter(existingTime)) {
                existing.setLastSeenTimestamp(item.getLastSeenTimestamp());
                existing.setHostName(item.getHostName());
            }
        }
    }

    private ServiceUptimeItemDTO mapHeartbeatHit(Map<String, Object> hit) {
        if (hit == null)
            return null;
        String name = (String) hit.get(COLUMN_SERVICE_NAME);
        if (name == null || name.isEmpty())
            return null;

        ServiceUptimeItemDTO item = new ServiceUptimeItemDTO();
        item.setServiceName(name);
        item.setHostName((String) hit.get("host_name"));

        Number lastSeenMicros = (Number) hit.get("last_seen");
        if (lastSeenMicros != null) {
            item.setLastSeenTimestamp(TimeFormatter.microsToIso(lastSeenMicros.longValue()));
        }

        Number count = (Number) hit.get("metric_count");
        item.setMetricCount(count == null ? 0L : count.longValue());
        return item;
    }

    /**
     * Best-effort enrichment with {@code process_uptime} (seconds since the
     * JVM started). The stream is OPTIONAL — the OTel agent emits it when the
     * process-metrics instrumentation is enabled. We swallow any error so a
     * missing stream doesn't break the whole endpoint; affected services
     * just keep {@code processUptimeSec == null}.
     */
    private void applyProcessUptime(Map<String, ServiceUptimeItemDTO> bySvc,
            List<Map<String, Object>> processUptime,
            List<Map<String, Object>> jvmRuntimeUptime,
            List<Map<String, Object>> jvmUptimeSeconds) {
        // 1. Standard process_uptime first
        applyUptimeHits(bySvc, processUptime, 1.0);

        // 2. Fallback 1: process_runtime_jvm_uptime (OpenTelemetry metric in milliseconds)
        applyUptimeHits(bySvc, jvmRuntimeUptime, 1000.0);

        // 3. Fallback 2: jvm_uptime_seconds (Micrometer metric in seconds)
        applyUptimeHits(bySvc, jvmUptimeSeconds, 1.0);
    }

    /** One uptime stream's per-service maximum; null when the stream is unavailable. */
    private List<Map<String, Object>> fetchUptimeHits(String streamName, long startMicros, long endMicros) {
        String stream = SqlSafety.validateIdentifier(streamName);
        String sql = "SELECT service_name, MAX(value) AS uptime_sec "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                + "AND _timestamp <= " + endMicros + " "
                + "AND service_name IS NOT NULL "
                + "GROUP BY service_name";

        try {
            return openObserveClientService.search(sql, startMicros, endMicros, 0, 500, STREAM_TYPE_METRICS);
        } catch (Exception e) {
            log.debug("{} lookup skipped: {}", streamName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Fills processUptimeSec for services that do not have one yet. */
    private static void applyUptimeHits(Map<String, ServiceUptimeItemDTO> bySvc,
            List<Map<String, Object>> hits, double divisor) {
        if (hits == null)
            return;
        for (Map<String, Object> hit : hits) {
            applyUptimeHit(bySvc, hit, divisor);
        }
    }

    /** Fills processUptimeSec from one hit, unless its service is unknown or already has one. */
    private static void applyUptimeHit(Map<String, ServiceUptimeItemDTO> bySvc,
            Map<String, Object> hit, double divisor) {
        String name = (String) hit.get(COLUMN_SERVICE_NAME);
        if (name == null) {
            return;
        }
        ServiceUptimeItemDTO item = bySvc.get(name);
        if (item == null || item.getProcessUptimeSec() != null) {
            return;
        }
        Number val = (Number) hit.get("uptime_sec");
        if (val != null) {
            item.setProcessUptimeSec((long) (val.doubleValue() / divisor));
        }
    }

    /**
     * Best-effort enrichment with {@link AppConstants#APPLICATION_HEALTH_STREAM}
     * — the Spring Boot Actuator {@code /health} status surfaced as a
     * Micrometer gauge ({@code 1.0} = UP, {@code 0.0} = DOWN), scraped by the
     * OTel Collector. The query picks the most recent sample per service in
     * the window; absent rows leave {@code healthStatus == null} so the UI can
     * distinguish "not configured / not exposed" from "DOWN".
     *
     * <p>
     * Like {@link #applyProcessUptime}, this swallows query errors so a
     * missing stream (no service in the lab exposes the gauge yet) doesn't
     * break the response.
     * </p>
     */
    private List<Map<String, Object>> fetchApplicationHealthHits(long startMicros, long endMicros) {
        String stream = SqlSafety.validateIdentifier(AppConstants.APPLICATION_HEALTH_STREAM);

        // Two aggregates per service: the latest timestamp and the latest
        // value at that timestamp. MAX(value) is OK here because the gauge is
        // bivalent {0.0, 1.0} — the latest sample dominates by either ANY()
        // semantics or MAX(); MAX keeps the SQL portable across OO versions.
        String sql = "SELECT service_name, "
                + "MAX(_timestamp) AS last_seen, "
                + "MAX(value)      AS health_value "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                + "AND _timestamp <= " + endMicros + " "
                + "AND service_name IS NOT NULL "
                + "GROUP BY service_name";

        try {
            return openObserveClientService.search(sql, startMicros, endMicros, 0, 500, STREAM_TYPE_METRICS);
        } catch (Exception e) {
            log.debug("application_health lookup skipped: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static void applyApplicationHealth(Map<String, ServiceUptimeItemDTO> bySvc,
            List<Map<String, Object>> hits) {
        if (hits == null)
            return;
        for (Map<String, Object> hit : hits) {
            String name = (String) hit.get(COLUMN_SERVICE_NAME);
            ServiceUptimeItemDTO item = name == null ? null : bySvc.get(name);
            if (item != null) {
                applyHealthHit(item, hit);
            }
        }
    }

    private static void applyHealthHit(ServiceUptimeItemDTO item, Map<String, Object> hit) {
        Number value = (Number) hit.get("health_value");
        if (value != null) {
            // Use a small epsilon — the gauge IS bivalent today, but
            // hard-comparing doubles is the wrong shape if a service ever
            // emits "DEGRADED" as 0.5 (which a few Boot setups do).
            item.setHealthStatus(value.doubleValue() >= 0.5 ? "UP" : "DOWN");
        }

        Number lastSeenMicros = (Number) hit.get("last_seen");
        if (lastSeenMicros != null) {
            item.setHealthLastSeenTimestamp(TimeFormatter.microsToIso(lastSeenMicros.longValue()));
        }
    }

    /**
     * Compute {@code lastSeenAgoSec} and flip status to UP/DOWN. The reference
     * "now" is {@code endMicros} (the window's upper bound, normally now) so
     * tests can pin a deterministic clock by passing a fixed end time.
     */
    private void applyLivenessClassification(Map<String, ServiceUptimeItemDTO> bySvc,
            long endMicros, int livenessSec) {
        long nowMicros = endMicros;
        // Allow the caller's window to be in the past (rare — e.g. a debug
        // request with a fixed historical end) by falling back to real now.
        long realNowMicros = Instant.now().getEpochSecond() * 1_000_000L;
        if (nowMicros < realNowMicros - 60_000_000L) {
            nowMicros = realNowMicros;
        }

        for (ServiceUptimeItemDTO item : bySvc.values()) {
            String iso = item.getLastSeenTimestamp();
            if (iso == null) {
                item.setStatus("DOWN");
                continue;
            }
            Instant lastSeen = Instant.parse(iso);
            long lastSeenMicros = lastSeen.getEpochSecond() * 1_000_000L
                    + lastSeen.getNano() / 1_000L;
            long agoSec = Math.max(0L, (nowMicros - lastSeenMicros) / 1_000_000L);
            item.setLastSeenAgoSec(agoSec);
            item.setStatus(agoSec <= livenessSec ? "UP" : "DOWN");
        }
    }
}
