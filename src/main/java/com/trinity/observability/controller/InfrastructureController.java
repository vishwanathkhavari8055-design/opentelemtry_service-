package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.infrastructure.InfrastructureResponseDTO;
import com.trinity.observability.service.InfrastructureMetricsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Live CPU, Memory, Disk and NFS Storage for the Resources screen.
 *
 * <h2>Read-through on every call</h2>
 *
 * <p>Nothing here is stored or polled into a table on a timer. Each request goes
 * to the collector streams in OpenObserve and comes back with the newest sample
 * they hold, together with how old that sample is — so a screen polling this
 * endpoint shows the cluster as it is now, and can say so when the collector has
 * gone quiet instead of leaving a stale number on display.</p>
 *
 * <p>A short server-side cache ({@code infrastructure.cache-seconds}, 15s by
 * default) sits in front, so several viewers polling at once cost one query
 * rather than one each. {@code ?refresh=true} bypasses it.</p>
 *
 * <h2>The four tiles do not share a scope</h2>
 *
 * <p>CPU and Memory are POD-scoped — one percentage of the pods' configured limit,
 * defined by {@code infrastructure.cpu.sql} / {@code infrastructure.memory.sql}.
 * Disk and NFS Storage are CLUSTER-scoped, aggregated across nodes. Each tile
 * carries {@code scope} saying which, and a caller that presents the four as
 * comparable figures without showing it is misreporting them.</p>
 *
 * <p>{@code getNodes()} is therefore a DISK breakdown only, and {@code ?node=}
 * narrows Disk and NFS alone — a pod reading cannot be attributed to a node, so
 * CPU and Memory ignore it rather than pretend to honour it.</p>
 *
 * <h2>{@code ?org=} does not apply here</h2>
 *
 * <p>Unlike every other route in this service, these figures are NOT read from the
 * requesting tenant's org. They are cluster facts, ingested once into the org named
 * by {@code infrastructure.metrics-org} (default: {@code openobserve.org}), so a
 * tenant-scoped read pointed them at streams that do not exist there and blanked
 * all four cards on every tenant but one. {@code org} in the response reports the
 * org actually queried, which is that one — not the caller's.</p>
 *
 * <h2>Separate from the Product Catalog</h2>
 *
 * <p>{@code /api/product-catalog} is the registry of resources an operator has
 * declared — rows that are created, enabled and removed. These four are
 * measurements of the machines underneath, registered by nobody and stored
 * nowhere. They share a screen and nothing else.</p>
 */
@RestController
@RequestMapping("/api/infrastructure")
@Tag(name = "Infrastructure",
        description = "Live CPU, Memory, Disk and NFS Storage utilization from the collectors")
public class InfrastructureController {

    private static final Logger log = LogManager.getLogger(InfrastructureController.class);

    private final InfrastructureMetricsService service;

    public InfrastructureController(InfrastructureMetricsService service) {
        this.service = service;
    }

    @GetMapping("/utilization")
    @Operation(summary = "Current CPU, Memory, Disk and NFS Storage utilization",
            description = "All four tiles are always present. One whose source stream is missing "
                    + "comes back with available=false and a message saying why, so a dead "
                    + "collector never blanks the other three. Each tile reports lastSeenAgoSec, "
                    + "which is how the screen can tell a live reading from a stale one. "
                    + "Pass refresh=true to bypass the short server-side cache, and node=<name> "
                    + "to narrow everything to a single Kubernetes node.")
    public InfrastructureResponseDTO.Utilization utilization(
            @RequestParam(name = "node", required = false) String node,
            @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh) {

        log.info("GET /api/infrastructure/utilization received: node={}, refresh={}", node, refresh);
        InfrastructureResponseDTO.Utilization result = service.utilization(node, refresh);
        log.info("GET /api/infrastructure/utilization returned {} tile(s) over {} node(s) "
                + "(supported={}, cached={})",
                result.getItems().size(), result.getNodes().size(),
                result.isSupported(), result.isCached());
        return result;
    }
}
