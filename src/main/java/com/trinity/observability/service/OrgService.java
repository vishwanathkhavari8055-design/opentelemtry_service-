package com.trinity.observability.service;

import com.trinity.observability.dto.org.OrgSummaryResponseDTO;

public interface OrgService {

    /**
     * Whole-org ingest posture: stream count and totals, plus function, alert,
     * pipeline and dashboard counts.
     *
     * <p>OpenObserve-only, in parity with {@code /api/logs/summary} — the
     * Elasticsearch path has no equivalent notion of streams-with-stats,
     * functions or pipelines, so it answers {@code supported=false} rather than
     * erroring.</p>
     *
     * <p>Never throws for a partial failure. Each figure comes from its own
     * OpenObserve call, and one of them being unavailable leaves that field
     * null while the rest of the response still returns — a Home screen that
     * shows four of five numbers is more useful than one that shows an error.</p>
     */
    OrgSummaryResponseDTO getOrgSummary();
}
