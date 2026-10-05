package com.trinity.observability.dto.traces;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Aggregated summary block returned at the trace-response root level.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceSummary {

    @JsonProperty("totalDurationMs")
    private Double totalDurationMs;

    @JsonProperty("totalGroups")
    private int totalGroups;

    @JsonProperty("totalLogs")
    private int totalLogs;

    @JsonProperty("totalDbCalls")
    private int totalDbCalls;

    @JsonProperty("warnings")
    private int warnings;

    @JsonProperty("errors")
    private int errors;

    /** Span counts keyed by normalized type */
    @JsonProperty("countsByType")
    private Map<String, Integer> countsByType;

    /** Log event counts keyed by severity */
    @JsonProperty("countsBySeverity")
    private Map<String, Integer> countsBySeverity;

    public TraceSummary() {
        // Jackson deserialization
    }

    public Double getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Double totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public int getTotalGroups() { return totalGroups; }
    public void setTotalGroups(int totalGroups) { this.totalGroups = totalGroups; }

    public int getTotalLogs() { return totalLogs; }
    public void setTotalLogs(int totalLogs) { this.totalLogs = totalLogs; }

    public int getTotalDbCalls() { return totalDbCalls; }
    public void setTotalDbCalls(int totalDbCalls) { this.totalDbCalls = totalDbCalls; }

    public int getWarnings() { return warnings; }
    public void setWarnings(int warnings) { this.warnings = warnings; }

    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }

    public Map<String, Integer> getCountsByType() { return countsByType; }
    public void setCountsByType(Map<String, Integer> m) { this.countsByType = m; }

    public Map<String, Integer> getCountsBySeverity() { return countsBySeverity; }
    public void setCountsBySeverity(Map<String, Integer> m) { this.countsBySeverity = m; }
}
