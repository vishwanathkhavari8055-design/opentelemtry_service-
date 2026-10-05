package com.trinity.observability.dto.traces;

import java.util.List;

/**
 * Response for {@code GET /api/traces/catalog} — per-service request volume,
 * error rate and latency distribution over the window. Backs the UI's Service
 * Catalog tab.
 *
 * <p>Percentiles come from DataFusion's {@code approx_percentile_cont} run
 * server-side over every matching span, NOT from a sampled page. A P99
 * computed from 25 rows would be a number-shaped guess, and the whole point of
 * this tab is spotting the service whose tail is bad.</p>
 *
 * <p>All latencies are MICROSECONDS, matching how the traces stream stores
 * {@code duration}.</p>
 */
public class ServiceCatalogDTO {

    public static class Row {
        private String serviceName;
        /** {@code CRITICAL} | {@code WARNING} | {@code DEGRADED} | {@code HEALTHY}. */
        private String status;
        private Long requests;
        private Long errors;
        /** Fraction, 0.0–1.0. The client renders the percentage. */
        private Double errorRate;
        private Long p50Us;
        private Long p95Us;
        private Long p99Us;
        private Long avgUs;
        private Long maxUs;

        public String getServiceName()             { return serviceName; }
        public void   setServiceName(String v)     { this.serviceName = v; }

        public String getStatus()                  { return status; }
        public void   setStatus(String v)          { this.status = v; }

        public Long getRequests()                  { return requests; }
        public void setRequests(Long v)            { this.requests = v; }

        public Long getErrors()                    { return errors; }
        public void setErrors(Long v)              { this.errors = v; }

        public Double getErrorRate()               { return errorRate; }
        public void   setErrorRate(Double v)       { this.errorRate = v; }

        public Long getP50Us()                     { return p50Us; }
        public void setP50Us(Long v)               { this.p50Us = v; }

        public Long getP95Us()                     { return p95Us; }
        public void setP95Us(Long v)               { this.p95Us = v; }

        public Long getP99Us()                     { return p99Us; }
        public void setP99Us(Long v)               { this.p99Us = v; }

        public Long getAvgUs()                     { return avgUs; }
        public void setAvgUs(Long v)               { this.avgUs = v; }

        public Long getMaxUs()                     { return maxUs; }
        public void setMaxUs(Long v)               { this.maxUs = v; }
    }

    private List<Row> items;
    private Integer criticalCount;
    private Integer warningCount;
    private Integer degradedCount;
    private Integer healthyCount;
    private boolean supported;
    private String backend;

    public List<Row> getItems()             { return items; }
    public void      setItems(List<Row> v)  { this.items = v; }

    public Integer getCriticalCount()          { return criticalCount; }
    public void    setCriticalCount(Integer v) { this.criticalCount = v; }

    public Integer getWarningCount()           { return warningCount; }
    public void    setWarningCount(Integer v)  { this.warningCount = v; }

    public Integer getDegradedCount()          { return degradedCount; }
    public void    setDegradedCount(Integer v) { this.degradedCount = v; }

    public Integer getHealthyCount()           { return healthyCount; }
    public void    setHealthyCount(Integer v)  { this.healthyCount = v; }

    public boolean isSupported()            { return supported; }
    public void    setSupported(boolean v)  { this.supported = v; }

    public String getBackend()              { return backend; }
    public void   setBackend(String v)      { this.backend = v; }
}
