package com.trinity.observability.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight health endpoint used by:
 *  - operations to verify the lib is up
 *  - the embedded Observability_UI Web Component to confirm at startup that
 *    its api-base-url is pointing at this lib (and not at a raw ES/OTLP source).
 *
 * <p>The response shape is a stable contract — the UI's wc-entry.jsx checks
 * for {@code service: "observability-lib"} on mount and warns loudly if it
 * doesn't see it. Don't rename those fields without updating the UI.</p>
 */
@RestController
@RequestMapping("/api/healthz")
@Tag(name = "Health", description = "Liveness probe and UI contract check")
public class HealthController {

    private static final Logger log = LogManager.getLogger(HealthController.class);

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    @GetMapping
    public Map<String, Object> health() {
        log.debug("GET /api/healthz probed (backend={})",
                openObserveEnabled ? "openobserve" : "elasticsearch");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",   "ok");
        body.put("service",  "observability-lib");
        body.put("backend",  openObserveEnabled ? "openobserve" : "elasticsearch");
        return body;
    }
}
