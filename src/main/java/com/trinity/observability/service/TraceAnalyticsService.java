package com.trinity.observability.service;

import com.trinity.observability.dto.traces.ServiceCatalogDTO;
import com.trinity.observability.dto.traces.SpansPageDTO;
import com.trinity.observability.dto.traces.TraceHistogramDTO;
import com.trinity.observability.dto.traces.TraceListPageDTO;
import com.trinity.observability.dto.traces.TraceRequestDTO;

/**
 * Aggregate views over the traces stream: the span list, the trace list, the
 * per-service catalog and the chart series.
 *
 * <p>Deliberately separate from {@link TracesService}, which is about
 * retrieving and assembling individual trace TREES for the drill-down view.
 * These are GROUP BY queries answered entirely inside OpenObserve; sharing an
 * implementation with the tree builder would put two very different jobs in
 * one already-large class.</p>
 *
 * <p>OpenObserve-only. On the Elasticsearch path every method answers
 * {@code supported=false} with empty results rather than throwing, matching
 * the convention of the other summary endpoints.</p>
 */
public interface TraceAnalyticsService {

    /** Flat, span-grain page plus whole-window span and error-span counts. */
    SpansPageDTO getSpans(TraceRequestDTO request);

    /** Trace-grain page plus whole-window trace and error-trace counts. */
    TraceListPageDTO getTraceList(TraceRequestDTO request);

    /** Per-service volume, error rate and latency percentiles for the window. */
    ServiceCatalogDTO getServiceCatalog(TraceRequestDTO request);

    /** Time-bucketed counts plus a capped duration scatter for the chart row. */
    TraceHistogramDTO getHistogram(TraceRequestDTO request);
}
