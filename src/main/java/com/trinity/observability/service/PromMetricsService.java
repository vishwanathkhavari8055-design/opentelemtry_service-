package com.trinity.observability.service;

import com.trinity.observability.dto.metrics.MetricCatalogDTO;
import com.trinity.observability.dto.metrics.MetricLabelsDTO;
import com.trinity.observability.dto.metrics.PromResultDTO;

/**
 * PromQL access to OpenObserve's Prometheus-compatible API.
 *
 * Separate from {@link MetricsService}, which lists metric ROWS via DataFusion
 * SQL for the summary cards. The two answer different questions and share no
 * code path: this one speaks PromQL and returns time series.
 */
public interface PromMetricsService {

    /** Every metric name with its type/help/unit. */
    MetricCatalogDTO getCatalog();

    /** Label names — across everything, or only those present on one metric. */
    MetricLabelsDTO getLabels(String metric);

    /** The distinct values of one label, optionally scoped to one metric. */
    MetricLabelsDTO getLabelValues(String label, String metric);

    /**
     * Range query.
     *
     * @param query     PromQL
     * @param startTime relative ("now-1h") or ISO instant; null = 1h ago
     * @param endTime   same; null = now
     * @param step      seconds between points; <=0 picks one from the span
     */
    PromResultDTO queryRange(String query, String startTime, String endTime, long step);

    /** Instant query at a single moment. */
    PromResultDTO query(String query, String time);
}
