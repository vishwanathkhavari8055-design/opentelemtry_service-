package com.trinity.observability.service;

import com.trinity.observability.dto.metrics.MetricRequestDTO;
import com.trinity.observability.dto.metrics.MetricResponseDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryRequestDTO;
import com.trinity.observability.dto.metrics.MetricsSummaryResponseDTO;
import java.util.List;

public interface MetricsService {
    List<MetricResponseDTO> getMetrics(MetricRequestDTO request);

    /**
     * Enumerate metric stream names — used to populate the metric-name dropdown
     * in the UI. With OpenObserve there is one stream per metric (~100+ in a
     * typical OTel-instrumented JVM); the UI must pick one before requesting
     * data points (Decision D1(a)).
     *
     * <p>Returns an empty list when the underlying backend is Elasticsearch
     * (Phase G is OO-only — ES doesn't have the per-metric stream concept).</p>
     */
    List<String> listMetricStreams();

    /**
     * Bucketed sparkline data for the Summary screen's Metrics card. Hits four
     * canonical streams (JVM heap, CPU usage, live threads, HTTP request
     * count) in parallel and returns each as a {t,v} series. Missing streams
     * are silently omitted. OpenObserve-only.
     */
    MetricsSummaryResponseDTO getMetricsSummary(MetricsSummaryRequestDTO request);
}
