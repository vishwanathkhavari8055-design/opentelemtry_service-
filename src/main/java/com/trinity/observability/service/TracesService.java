package com.trinity.observability.service;

import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.dto.traces.TraceTreeResponse;
import com.trinity.observability.dto.traces.TracesSummaryRequestDTO;
import com.trinity.observability.dto.traces.TracesSummaryResponseDTO;

import java.util.List;

public interface TracesService {
    List<TraceTreeResponse> getTraces(TraceRequestDTO request);

    /**
     * Per-trace aggregate over the requested window — feeds the Traces card
     * on the Summary screen. Returns count, error rate, avg / p95 latency,
     * and the five slowest operations. OpenObserve-only.
     */
    TracesSummaryResponseDTO getTracesSummary(TracesSummaryRequestDTO request);
}
