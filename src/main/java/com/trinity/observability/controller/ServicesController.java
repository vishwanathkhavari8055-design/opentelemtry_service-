package com.trinity.observability.controller;

import com.trinity.observability.dto.services.ServiceVitalsRequestDTO;
import com.trinity.observability.dto.services.ServiceVitalsResponseDTO;
import com.trinity.observability.dto.services.ServicesUptimeRequestDTO;
import com.trinity.observability.dto.services.ServicesUptimeResponseDTO;
import com.trinity.observability.service.ServicesService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
@Tag(name = "Services API", description = "Cross-service uptime and liveness aggregation")
public class ServicesController {

    private static final Logger log = LogManager.getLogger(ServicesController.class);

    private final ServicesService servicesService;

    public ServicesController(ServicesService servicesService) {
        this.servicesService = servicesService;
    }

    /**
     * Per-service uptime: status (UP/DOWN), last-seen, optional process
     * uptime. See {@link ServicesService#getUptime} for the data-model
     * notes. OpenObserve-only; the ES path returns
     * {@code supported=false} with an empty list.
     */
    @GetMapping("/uptime")
    public ServicesUptimeResponseDTO getUptime(ServicesUptimeRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/services/uptime received: window={}, livenessSec={}",
                request.getWindow(), request.getLivenessSec());

        ServicesUptimeResponseDTO result = servicesService.getUptime(request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/services/uptime returned {} item(s) (supported={}) in {} ms",
                result.getItems() != null ? result.getItems().size() : 0,
                result.isSupported(), elapsedMs);
        return result;
    }

    /**
     * Per-service vital-signs roll-up: REQ rate, error %, latency, DB pool,
     * JVM heap, error log rate, plus downstream callees. Sourced from the
     * Actuator/Prometheus streams the OTel Collector lands in OpenObserve.
     * Each tile is best-effort and absent on missing streams.
     */
    @GetMapping("/{name}/vitals")
    public ServiceVitalsResponseDTO getServiceVitals(@PathVariable("name") String name,
                                                     ServiceVitalsRequestDTO request) {
        long t0 = System.nanoTime();
        log.info("GET /api/services/{}/vitals received: window={}, bucketSec={}",
                name, request.getWindow(), request.getBucketSec());

        ServiceVitalsResponseDTO result = servicesService.getServiceVitals(name, request);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/services/{}/vitals returned {} tile(s), {} downstream call(s) (supported={}) in {} ms",
                name,
                result.getTiles() != null ? result.getTiles().size() : 0,
                result.getDownstream() != null ? result.getDownstream().size() : 0,
                result.isSupported(), elapsedMs);
        return result;
    }
}
