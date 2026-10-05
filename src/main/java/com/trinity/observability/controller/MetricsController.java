package com.trinity.observability.controller;

import com.trinity.observability.dto.metrics.MetricCatalogDTO;
import com.trinity.observability.dto.metrics.MetricLabelsDTO;
import com.trinity.observability.dto.metrics.MetricProducersDTO;
import com.trinity.observability.dto.metrics.MetricRequestDTO;
import com.trinity.observability.dto.metrics.MetricResponseDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryRequestDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryResponseDTO;
import com.trinity.observability.dto.metrics.PromResultDTO;
import com.trinity.observability.service.MetricProducerService;
import com.trinity.observability.service.MetricsService;
import com.trinity.observability.service.PromMetricsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/metrics")
@Tag(name = "Metrics API", description = "Query and filter application metrics")
public class MetricsController {

    private static final Logger log = LogManager.getLogger(MetricsController.class);

    private final MetricsService metricsService;
    private final PromMetricsService promMetricsService;
    private final MetricProducerService metricProducerService;

    public MetricsController(MetricsService metricsService,
                             PromMetricsService promMetricsService,
                             MetricProducerService metricProducerService) {
        this.metricsService = metricsService;
        this.promMetricsService = promMetricsService;
        this.metricProducerService = metricProducerService;
    }

    @GetMapping
    public List<MetricResponseDTO> getMetrics(MetricRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/metrics received: metricName={}, serviceName={}, hostName={}, page={}, size={}",
                request.getMetricName(), request.getServiceName(), request.getHostName(),
                request.getPage(), request.getSize());
        if (log.isDebugEnabled()) {
            log.debug("GET /api/metrics full request: {}", request);
        }

        List<MetricResponseDTO> result = metricsService.getMetrics(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/metrics returned {} point(s) in {} ms", result.size(), elapsedMs);
        return result;
    }

    /**
     * Bucketed sparkline data for the Summary screen's Metrics card. Returns
     * up to four canonical series (JVM heap, CPU, live threads, HTTP request
     * count). Streams that aren't being emitted are silently omitted from
     * the response. OpenObserve-only.
     */
    @GetMapping("/summary")
    public MetricsSummaryResponseDTO getMetricsSummary(MetricsSummaryRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/metrics/summary received: window={}, bucketSec={}, serviceName={}",
                request.getWindow(), request.getBucketSec(), request.getServiceName());

        MetricsSummaryResponseDTO result = metricsService.getMetricsSummary(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/metrics/summary returned {} series (supported={}) in {} ms",
                result.getSparklines() != null ? result.getSparklines().size() : 0,
                result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Lists the metric stream names available in the backend.
     *
     * <p>Used by the UI to populate its metric-name dropdown. With OpenObserve,
     * each metric is its own stream (~100+ in a typical OTel-instrumented JVM)
     * and {@code metricName} must be specified before fetching data points
     * (Decision D1(a) in MIGRATION-PLAN.md). Returns empty when running on the
     * Elasticsearch path — ES doesn't have the per-metric stream concept.</p>
     */
    @GetMapping("/streams")
    public List<String> listStreams() {
        long t0 = System.nanoTime();
        log.info("GET /api/metrics/streams received");

        List<String> result = metricsService.listMetricStreams();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/metrics/streams returned {} stream(s) in {} ms", result.size(), elapsedMs);
        return result;
    }

    // ─── PromQL surface, for the Metrics explorer ───────────────────────────
    //
    // Added alongside the SQL-backed endpoints above, not in place of them.
    // GET /api/metrics and /summary still serve the summary cards from
    // DataFusion; these speak to OpenObserve's Prometheus API and return time
    // series. Different questions, different code paths, no shared state.

    /**
     * Every metric name with its Prometheus type, help text and unit — the
     * left-hand catalogue in the explorer.
     */
    @GetMapping("/catalog")
    public MetricCatalogDTO getCatalog() {
        long t0 = System.nanoTime();
        log.info("GET /api/metrics/catalog received");

        MetricCatalogDTO result = promMetricsService.getCatalog();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/metrics/catalog returned {} metric(s) (supported={}) in {} ms",
                result.getTotal(), result.isSupported(), elapsedMs);
        return result;
    }

    /** Label names, optionally only those carried by one metric. */
    @GetMapping("/labels")
    public MetricLabelsDTO getLabels(@RequestParam(name = "metric", required = false) String metric) {
        log.info("GET /api/metrics/labels received: metric={}", metric);
        return promMetricsService.getLabels(metric);
    }

    /** Distinct values of one label, optionally scoped to one metric. */
    @GetMapping("/label-values")
    public MetricLabelsDTO getLabelValues(@RequestParam(name = "label") String label,
                                          @RequestParam(name = "metric", required = false) String metric) {
        log.info("GET /api/metrics/label-values received: label={}, metric={}", label, metric);
        return promMetricsService.getLabelValues(label, metric);
    }

    /**
     * PromQL range query — the chart.
     *
     * {@code startTime}/{@code endTime} take the same relative form as every
     * other endpoint here ("now-1h"); the seconds conversion Prometheus needs
     * happens server-side. {@code step} is in seconds and is clamped so a wide
     * window cannot request an unbounded number of points.
     */
    @GetMapping("/query-range")
    public PromResultDTO queryRange(@RequestParam(name = "query") String query,
                                    @RequestParam(name = "startTime", required = false) String startTime,
                                    @RequestParam(name = "endTime", required = false) String endTime,
                                    @RequestParam(name = "step", required = false, defaultValue = "0") long step) {
        long t0 = System.nanoTime();
        log.info("GET /api/metrics/query-range received: query={}, startTime={}, endTime={}, step={}",
                query, startTime, endTime, step);

        PromResultDTO result = promMetricsService.queryRange(query, startTime, endTime, step);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/metrics/query-range returned {} series (type={}, supported={}) in {} ms",
                result.getSeries() != null ? result.getSeries().size() : 0,
                result.getResultType(), result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Which service exposes which metrics — the catalogue inverted.
     *
     * <p>Returns immediately, always. The index behind it takes a 3,000-probe
     * sweep to build (see {@code MetricProducerProperties} for why no single
     * query can answer this), so a first call reports
     * {@code building: true} with progress and a cached one reports its age.
     * Poll while {@code building} is set.</p>
     */
    @GetMapping("/producers")
    public MetricProducersDTO getProducers() {
        long t0 = System.nanoTime();
        log.info("GET /api/metrics/producers received");

        MetricProducersDTO result = metricProducerService.getProducers();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/metrics/producers returned {} service(s) (building={}, {}/{}, age={}s) in {} ms",
                result.getServices() != null ? result.getServices().size() : 0,
                result.isBuilding(), result.getProgressDone(), result.getProgressTotal(),
                result.getAgeSeconds(), elapsedMs);
        return result;
    }

    /**
     * Rebuild the producer index now, without waiting out its TTL.
     *
     * <p>Also non-blocking, and it deliberately keeps serving the previous
     * answer while the new sweep runs — a refresh should not blank the screen
     * for ninety seconds.</p>
     */
    @PostMapping("/producers/refresh")
    public MetricProducersDTO refreshProducers() {
        log.info("POST /api/metrics/producers/refresh received");
        return metricProducerService.refresh();
    }

    /** PromQL instant query — one value per series, for the table. */
    @GetMapping("/query")
    public PromResultDTO query(@RequestParam(name = "query") String query,
                               @RequestParam(name = "time", required = false) String time) {
        log.info("GET /api/metrics/query received: query={}, time={}", query, time);
        return promMetricsService.query(query, time);
    }
}
