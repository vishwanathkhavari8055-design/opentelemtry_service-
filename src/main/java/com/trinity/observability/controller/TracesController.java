package com.trinity.observability.controller;

import com.trinity.observability.dto.traces.ServiceCatalogDTO;
import com.trinity.observability.dto.traces.SpansPageDTO;
import com.trinity.observability.dto.traces.TraceHistogramDTO;
import com.trinity.observability.dto.traces.TraceListPageDTO;
import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.dto.traces.TraceTreeResponse;
import com.trinity.observability.dto.traces.TracesSummaryRequestDTO;
import com.trinity.observability.dto.traces.TracesSummaryResponseDTO;
import com.trinity.observability.service.TraceAnalyticsService;
import com.trinity.observability.service.TracesService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/traces")
@Tag(name = "Traces API", description = "Query and filter application traces")
public class TracesController {

    private static final Logger log = LogManager.getLogger(TracesController.class);

    private final TracesService tracesService;
    private final TraceAnalyticsService traceAnalyticsService;

    public TracesController(TracesService tracesService,
                            TraceAnalyticsService traceAnalyticsService) {
        this.tracesService = tracesService;
        this.traceAnalyticsService = traceAnalyticsService;
    }

    @GetMapping
    public List<TraceTreeResponse> getTraces(TraceRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/traces received: traceId={}, serviceName={}, spanId={}, page={}, size={}",
                request.getTraceId(), request.getServiceName(), request.getSpanId(),
                request.getPage(), request.getSize());
        if (log.isDebugEnabled()) {
            log.debug("GET /api/traces full request: {}", request);
        }

        List<TraceTreeResponse> result = tracesService.getTraces(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/traces returned {} trace(s) in {} ms", result.size(), elapsedMs);
        return result;
    }

    /**
     * Aggregate per-trace stats for the Summary screen's Traces card.
     * {@code window=now-1h} default; the response carries trace count, error
     * rate, avg/p95 latency, and the five slowest operations. OpenObserve-only.
     */
    @GetMapping("/summary")
    public TracesSummaryResponseDTO getTracesSummary(TracesSummaryRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/traces/summary received: window={}", request.getWindow());

        TracesSummaryResponseDTO result = tracesService.getTracesSummary(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/traces/summary returned traceCount={} (supported={}) in {} ms",
                result.getTraceCount(), result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Flat, span-grain listing for the UI's Spans tab, with whole-window span
     * and error-span counts. Paginates by SPAN, unlike {@code GET /api/traces}
     * which paginates by trace and returns assembled trees.
     */
    @GetMapping("/spans")
    public SpansPageDTO getSpans(TraceRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/traces/spans received: serviceName={}, outcome={}, page={}, size={}",
                request.getServiceName(), request.getOutcome(), request.getPage(), request.getSize());

        SpansPageDTO result = traceAnalyticsService.getSpans(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/traces/spans returned {} span(s) of total={} (supported={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.getTotal(), result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Trace-grain listing for the UI's Traces tab: one row per trace with its
     * root operation, span count, status and per-service latency breakdown,
     * plus whole-window trace and error-trace counts.
     */
    @GetMapping("/list")
    public TraceListPageDTO getTraceList(TraceRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/traces/list received: serviceName={}, outcome={}, page={}, size={}",
                request.getServiceName(), request.getOutcome(), request.getPage(), request.getSize());

        TraceListPageDTO result = traceAnalyticsService.getTraceList(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/traces/list returned {} trace(s) of total={} (supported={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.getTotal(), result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Per-service request volume, error rate and latency percentiles for the
     * window — the UI's Service Catalog tab. Percentiles are computed by
     * DataFusion over every matching span, not sampled client-side.
     */
    @GetMapping("/catalog")
    public ServiceCatalogDTO getServiceCatalog(TraceRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/traces/catalog received: startTime={}, endTime={}",
                request.getStartTime(), request.getEndTime());

        ServiceCatalogDTO result = traceAnalyticsService.getServiceCatalog(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/traces/catalog returned {} service(s) (supported={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Time-bucketed span and error counts plus per-bucket duration percentiles
     * — the Rate / Errors / Duration panels above the Spans and Traces tables.
     * One series drives all three, so their x axes cannot drift apart.
     */
    @GetMapping("/histogram")
    public TraceHistogramDTO getHistogram(TraceRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/traces/histogram received: startTime={}, endTime={}",
                request.getStartTime(), request.getEndTime());

        TraceHistogramDTO result = traceAnalyticsService.getHistogram(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/traces/histogram returned {} bucket(s) at {} (supported={}) in {} ms",
                result.getBuckets() != null ? result.getBuckets().size() : 0,
                result.getInterval(), result.isSupported(), elapsedMs);
        return result;
    }
}
