package com.trinity.observability.controller;

import com.trinity.observability.dto.logs.LogPageResponseDTO;
import com.trinity.observability.dto.logs.LogRequestDTO;
import com.trinity.observability.dto.logs.LogServicesResponseDTO;
import com.trinity.observability.dto.logs.LogTraceSummaryPageDTO;
import com.trinity.observability.dto.logs.LogsSummaryRequestDTO;
import com.trinity.observability.dto.logs.LogsSummaryResponseDTO;
import com.trinity.observability.service.LogsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/logs")
@Tag(name = "Logs API", description = "Query and filter application logs")
public class LogsController {

    private static final Logger log = LogManager.getLogger(LogsController.class);

    private final LogsService logsService;

    public LogsController(LogsService logsService) {
        this.logsService = logsService;
    }

    @GetMapping
    public LogPageResponseDTO getLogs(LogRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/logs received: serviceName={}, severity={}, traceId={}, page={}, size={}",
                request.getServiceName(), request.getSeverity(), request.getTraceId(),
                request.getPage(), request.getSize());
        if (log.isDebugEnabled()) {
            log.debug("GET /api/logs full request: {}", request);
        }

        LogPageResponseDTO result = logsService.getLogs(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/logs returned {} items (hasMore={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.isHasMore(), elapsedMs);
        return result;
    }

    /**
     * Aggregate counts feeding the Summary screen's Logs card. Single window
     * input ({@code window=now-1h}); the response carries total count,
     * per-severity counts, and the top-5 services emitting ERROR/FATAL logs.
     * OpenObserve-only.
     */
    @GetMapping("/summary")
    public LogsSummaryResponseDTO getLogsSummary(LogsSummaryRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/logs/summary received: window={}", request.getWindow());

        LogsSummaryResponseDTO result = logsService.getLogsSummary(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/logs/summary returned total={} (supported={}) in {} ms",
                result.getTotalCount(), result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Logs grouped by trace ID (issue #2). Same query string as
     * {@code GET /api/logs} (severity, time range, search, service, etc.)
     * but the response is one row per distinct trace within the window,
     * with a server-computed log count and worst-case severity. The UI
     * uses this for the "Grouped" view; expanding a trace then fetches
     * its individual logs via the existing {@code traceId=} filter.
     */
    @GetMapping("/groups")
    public LogTraceSummaryPageDTO getTraceGroups(LogRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/logs/groups received: serviceName={}, severity={}, page={}, size={}",
                request.getServiceName(), request.getSeverity(),
                request.getPage(), request.getSize());

        LogTraceSummaryPageDTO result = logsService.getTraceGroups(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/logs/groups returned {} traces (hasMore={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.isHasMore(), elapsedMs);
        return result;
    }

    /**
     * Distinct {@code service_name} values present in the logs stream within
     * the requested window. Feeds the UI's logs service-filter dropdown so it
     * shows every service that produced logs in the window — not just the
     * names that happened to fall inside a paginated row fetch.
     *
     * <p>Default window is {@code now-7d}; OpenObserve-only (parity with
     * {@code /api/logs/summary}).</p>
     */
    @GetMapping("/services")
    public LogServicesResponseDTO listServices(
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime) {
        long t0 = System.nanoTime();
        log.info("GET /api/logs/services received: window={}, startTime={}, endTime={}", window, startTime, endTime);

        LogServicesResponseDTO result = logsService.listServices(window, startTime, endTime);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/logs/services returned {} service(s) (supported={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.isSupported(), elapsedMs);
        return result;
    }
}
