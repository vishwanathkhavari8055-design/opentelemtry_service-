package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.org.OrgSummaryResponseDTO;
import com.trinity.observability.service.OrgService;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/org")
@Tag(name = "Org API", description = "Whole-org ingest posture")
public class OrgController {

    private static final Logger log = LogManager.getLogger(OrgController.class);

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * Stream count and totals, plus function / alert / pipeline / dashboard
     * counts — everything the UI's Home screen renders, in one call.
     *
     * <p>No parameters: these are whole-org totals, not windowed aggregates.
     * Sizes are raw bytes; a null figure means that sub-query failed and is
     * distinct from a zero. OpenObserve-only (parity with
     * {@code /api/logs/summary}).</p>
     */
    @GetMapping("/summary")
    public OrgSummaryResponseDTO getOrgSummary() {
        long t0 = System.nanoTime();
        log.info("GET /api/org/summary received");

        OrgSummaryResponseDTO result = orgService.getOrgSummary();

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("GET /api/org/summary returned streams={} (supported={}) in {} ms",
                result.getStreams() != null ? result.getStreams().getCount() : null,
                result.isSupported(), elapsedMs);
        return result;
    }
}
