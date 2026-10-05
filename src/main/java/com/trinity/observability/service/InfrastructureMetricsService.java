package com.trinity.observability.service;

import com.trinity.observability.dto.infrastructure.InfrastructureResponseDTO;

/**
 * The CPU / Memory / Disk / NFS Storage figures behind the Resources screen.
 *
 * <h2>Why this is not part of the Product Catalog</h2>
 *
 * <p>They share a screen and nothing else. The Product Catalog is a registry of
 * things an operator has declared — it has rows that are created, enabled and
 * removed, and it is stored in PostgreSQL. These four numbers are not registered
 * by anybody and are never stored: they are read out of OpenObserve on the
 * request that asks for them, which is the only way a utilization figure can be
 * correct. Folding them into the catalog would give it a second kind of row with
 * none of the lifecycle the first kind has.</p>
 *
 * <h2>Read-through, not ingested</h2>
 *
 * <p>Nothing here is polled into a table on a timer. A stored copy is a number
 * that was true when the timer last ran, and the failure mode is silent: the
 * screen shows a plausible reading of a cluster that has since filled its disk.
 * Every call queries the collectors' streams directly and reports how old the
 * newest sample it found is, so "the data is stale" is something the screen can
 * say rather than something it hides.</p>
 */
public interface InfrastructureMetricsService {

    /**
     * Current utilization across the cluster, or for one node.
     *
     * <p>Never throws for an unreachable OpenObserve. A tile that cannot be
     * answered comes back {@code available=false} with a message; the other three
     * still render, because one dead receiver should not blank the screen.</p>
     *
     * @param node    restrict to a single {@code k8s_node_name}; null for all
     * @param refresh bypass the short-lived cache and re-query now
     */
    InfrastructureResponseDTO.Utilization utilization(String node, boolean refresh);
}
