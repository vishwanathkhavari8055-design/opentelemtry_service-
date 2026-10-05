package com.trinity.observability.dto.traces;

import java.util.List;

/**
 * Response for {@code GET /api/traces/summary}.
 *
 * <ul>
 *   <li>{@code traceCount} / {@code errorCount} / {@code errorRate}: counted
 *       over distinct trace_ids in the window.</li>
 *   <li>{@code avgLatencyMs} / {@code p95LatencyMs}: computed across the root
 *       span duration of each trace (MAX(duration) within trace, in µs →
 *       converted to ms for the response).</li>
 *   <li>{@code slowestOps}: top-5 operations by their max observed duration in
 *       the window. Useful for "what's slow right now" without drilling in.</li>
 * </ul>
 */
public class TracesSummaryResponseDTO {

    public static class SlowestOp {
        private String operationName;
        private double durationMs;

        public SlowestOp() {}
        public SlowestOp(String name, double ms) {
            this.operationName = name;
            this.durationMs    = ms;
        }
        public String getOperationName()             { return operationName; }
        public void   setOperationName(String s)     { this.operationName = s; }
        public double getDurationMs()                { return durationMs; }
        public void   setDurationMs(double v)        { this.durationMs = v; }
    }

    private long traceCount;
    private long errorCount;
    private double errorRate;          // [0..1]
    private double avgLatencyMs;
    private double p95LatencyMs;
    private List<SlowestOp> slowestOps;
    private String windowStart;
    private String windowEnd;
    private String backend;
    private boolean supported;

    public long   getTraceCount()                       { return traceCount; }
    public void   setTraceCount(long v)                 { this.traceCount = v; }

    public long   getErrorCount()                       { return errorCount; }
    public void   setErrorCount(long v)                 { this.errorCount = v; }

    public double getErrorRate()                        { return errorRate; }
    public void   setErrorRate(double v)                { this.errorRate = v; }

    public double getAvgLatencyMs()                     { return avgLatencyMs; }
    public void   setAvgLatencyMs(double v)             { this.avgLatencyMs = v; }

    public double getP95LatencyMs()                     { return p95LatencyMs; }
    public void   setP95LatencyMs(double v)             { this.p95LatencyMs = v; }

    public List<SlowestOp> getSlowestOps()              { return slowestOps; }
    public void            setSlowestOps(List<SlowestOp> v) { this.slowestOps = v; }

    public String getWindowStart()                      { return windowStart; }
    public void   setWindowStart(String v)              { this.windowStart = v; }

    public String getWindowEnd()                        { return windowEnd; }
    public void   setWindowEnd(String v)                { this.windowEnd = v; }

    public String getBackend()                          { return backend; }
    public void   setBackend(String v)                  { this.backend = v; }

    public boolean isSupported()                        { return supported; }
    public void    setSupported(boolean v)              { this.supported = v; }
}
