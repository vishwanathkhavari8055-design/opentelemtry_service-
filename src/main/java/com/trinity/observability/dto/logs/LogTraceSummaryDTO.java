package com.trinity.observability.dto.logs;

import java.util.List;

/**
 * One row of the LogsView grouped-by-trace view (issue #2).
 *
 * Returned by {@code GET /api/logs/groups}. Built from a backend aggregation
 * (GROUP BY trace_id) so the counts and severity reflect the entire filter
 * window — not just the page of logs the UI currently has loaded. The
 * previous frontend-only grouping bucketed the current page, which lied
 * about counts whenever a trace's logs straddled page boundaries.
 *
 * {@code topSeverity} is the worst severity any log in this trace carries
 * (FATAL &gt; ERROR &gt; WARN &gt; INFO &gt; DEBUG &gt; TRACE), so the user can
 * triage at a glance.
 */
public class LogTraceSummaryDTO {
    private String traceId;
    /** Logs in this trace that matched the active search predicate (if any). */
    private long logCount;
    /**
     * Total logs in this trace within the non-search filters (service,
     * severity, time, …). Equals {@link #logCount} when no search is active.
     * Surfaced so the UI can render "5 matching of 190 logs" — making it
     * clear that the {@link #logCount} drop is search-driven, not a missing
     * page of data.
     */
    private long totalLogCount;
    private String latestTimestamp;
    private String earliestTimestamp;
    private String topSeverity;
    private List<String> services;
    /**
     * Operation name of the trace's root span — typically the incoming HTTP
     * call (e.g. "GET /IoTOpsService/alarmType/getAllAlarmIndexDataOfCompany"),
     * but it could be a message-consumer or batch-job name for non-HTTP entry
     * points. Resolved from the traces stream in a single batched lookup
     * after the per-trace log aggregation. Null when the traces stream isn't
     * reachable, the trace has no root span in window, or the backend is ES.
     * Surfaced in the LogsView's grouped row so the user can identify a trace
     * by what it was actually doing, not just its hex ID.
     */
    private String entryOperation;

    /** Default constructor required by Jackson for deserialization. */
    public LogTraceSummaryDTO() {
        // Jackson deserialization
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public long getLogCount() { return logCount; }
    public void setLogCount(long logCount) { this.logCount = logCount; }

    public long getTotalLogCount() { return totalLogCount; }
    public void setTotalLogCount(long totalLogCount) { this.totalLogCount = totalLogCount; }

    public String getLatestTimestamp() { return latestTimestamp; }
    public void setLatestTimestamp(String latestTimestamp) { this.latestTimestamp = latestTimestamp; }

    public String getEarliestTimestamp() { return earliestTimestamp; }
    public void setEarliestTimestamp(String earliestTimestamp) { this.earliestTimestamp = earliestTimestamp; }

    public String getTopSeverity() { return topSeverity; }
    public void setTopSeverity(String topSeverity) { this.topSeverity = topSeverity; }

    public List<String> getServices() { return services; }
    public void setServices(List<String> services) { this.services = services; }

    public String getEntryOperation() { return entryOperation; }
    public void setEntryOperation(String entryOperation) { this.entryOperation = entryOperation; }
}
