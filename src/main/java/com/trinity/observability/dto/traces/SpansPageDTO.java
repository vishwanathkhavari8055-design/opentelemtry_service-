package com.trinity.observability.dto.traces;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/traces/spans} — a flat, span-grain page of the
 * traces stream, which is what the UI's Spans tab lists.
 *
 * <p>Distinct from {@code GET /api/traces}: that returns assembled trace TREES
 * and paginates by trace. This paginates by span, so "page 2" means spans
 * 26–50, not the spans of traces 26–50.</p>
 *
 * <p>{@code total} and {@code errorTotal} are real COUNT queries over the whole
 * window, not the size of {@code items} — they feed the "25.0K Spans Found /
 * 1.7K Error Spans" chips, which would be meaningless if they only described
 * the current page.</p>
 */
public class SpansPageDTO {

    /** One span, projected to just the columns the table renders. */
    public static class Row {
        private String timestamp;
        private String traceId;
        private String spanId;
        private String serviceName;
        private String operationName;
        /** Span duration in MICROSECONDS, as stored. Formatting is the client's. */
        private Long durationUs;
        /** {@code UNSET} | {@code ERROR} | {@code OK}, as emitted by the agent. */
        private String spanStatus;

        /**
         * The whole span document as stored, alongside the typed fields above.
         *
         * <p>The table renders the typed fields; the UI's field explorer needs
         * the rest — {@code span_kind}, {@code code_*}, {@code db_*} and every
         * other attribute the agent captured — and there is no schema endpoint
         * to enumerate them from. Passing the raw row through means the field
         * list reflects exactly what the matching spans actually carry, rather
         * than a hard-coded guess that goes stale when instrumentation changes.</p>
         */
        private Map<String, Object> raw;

        public String getTimestamp()               { return timestamp; }
        public void   setTimestamp(String v)       { this.timestamp = v; }

        public String getTraceId()                 { return traceId; }
        public void   setTraceId(String v)         { this.traceId = v; }

        public String getSpanId()                  { return spanId; }
        public void   setSpanId(String v)          { this.spanId = v; }

        public String getServiceName()             { return serviceName; }
        public void   setServiceName(String v)     { this.serviceName = v; }

        public String getOperationName()           { return operationName; }
        public void   setOperationName(String v)   { this.operationName = v; }

        public Long getDurationUs()                { return durationUs; }
        public void setDurationUs(Long v)          { this.durationUs = v; }

        public String getSpanStatus()              { return spanStatus; }
        public void   setSpanStatus(String v)      { this.spanStatus = v; }

        public Map<String, Object> getRaw()            { return raw; }
        public void setRaw(Map<String, Object> v)      { this.raw = v; }
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
