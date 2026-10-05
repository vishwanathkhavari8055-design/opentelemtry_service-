package com.trinity.observability.dto.traces;

import java.util.List;

/**
 * Response for {@code GET /api/traces/list} — a trace-grain page for the UI's
 * Traces tab.
 *
 * <p>Complements the existing {@code GET /api/traces}, which assembles full
 * span TREES for the drill-down view. This one carries only what a list row
 * renders, so a page of 25 traces costs one small payload instead of 25 span
 * trees.</p>
 */
public class TraceListPageDTO {

    /** Per-service slice of a trace's work, for the Service Latency bar. */
    public static class ServiceSlice {
        private String serviceName;
        /** Summed span duration for this service within the trace, MICROSECONDS. */
        private Long durationUs;
        private Integer spanCount;

        public ServiceSlice() {}
        public ServiceSlice(String serviceName, Long durationUs, Integer spanCount) {
            this.serviceName = serviceName;
            this.durationUs = durationUs;
            this.spanCount = spanCount;
        }

        public String getServiceName()             { return serviceName; }
        public void   setServiceName(String v)     { this.serviceName = v; }

        public Long getDurationUs()                { return durationUs; }
        public void setDurationUs(Long v)          { this.durationUs = v; }

        public Integer getSpanCount()              { return spanCount; }
        public void    setSpanCount(Integer v)     { this.spanCount = v; }
    }

    public static class Row {
        private String traceId;
        /** Earliest span timestamp in the trace — when the trace started. */
        private String timestamp;
        /** Service of the root span (the one with no parent), else the earliest. */
        private String serviceName;
        /** Operation of that same root span. */
        private String operationName;
        /**
         * Wall-clock span of the trace in MICROSECONDS. Taken as the root
         * span's own duration when a root is identifiable, because a root
         * subsumes its children; summing every span would double-count nested
         * work and report a trace as slower than it was.
         */
        private Long durationUs;
        private Integer spanCount;
        /** {@code SUCCESS} | {@code ERROR} — ERROR if ANY span in the trace errored. */
        private String status;
        /** Per-service breakdown, descending by duration. */
        private List<ServiceSlice> services;

        public String getTraceId()                 { return traceId; }
        public void   setTraceId(String v)         { this.traceId = v; }

        public String getTimestamp()               { return timestamp; }
        public void   setTimestamp(String v)       { this.timestamp = v; }

        public String getServiceName()             { return serviceName; }
        public void   setServiceName(String v)     { this.serviceName = v; }

        public String getOperationName()           { return operationName; }
        public void   setOperationName(String v)   { this.operationName = v; }

        public Long getDurationUs()                { return durationUs; }
        public void setDurationUs(Long v)          { this.durationUs = v; }

        public Integer getSpanCount()              { return spanCount; }
        public void    setSpanCount(Integer v)     { this.spanCount = v; }

        public String getStatus()                  { return status; }
        public void   setStatus(String v)          { this.status = v; }

        public List<ServiceSlice> getServices()               { return services; }
        public void setServices(List<ServiceSlice> v)         { this.services = v; }
    }

    private List<Row> items;
    private Long total;
    private Long errorTotal;
    private boolean hasMore;
    private boolean supported;
    private String backend;

    public List<Row> getItems()             { return items; }
    public void      setItems(List<Row> v)  { this.items = v; }

    public Long getTotal()                  { return total; }
    public void setTotal(Long v)            { this.total = v; }

    public Long getErrorTotal()             { return errorTotal; }
    public void setErrorTotal(Long v)       { this.errorTotal = v; }

    public boolean isHasMore()              { return hasMore; }
    public void    setHasMore(boolean v)    { this.hasMore = v; }

    public boolean isSupported()            { return supported; }
    public void    setSupported(boolean v)  { this.supported = v; }

    public String getBackend()              { return backend; }
    public void   setBackend(String v)      { this.backend = v; }
}
