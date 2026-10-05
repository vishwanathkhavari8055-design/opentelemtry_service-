package com.trinity.observability.dto.traces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wrapper response that preserves the flat span list (backward-compatible)
 * and adds a hierarchical {@code rootOperation} tree for debugging.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceTreeResponse {

    /** Represents the unique trace identifier */
    @JsonProperty("traceId")
    private String traceId;

    /** Represents the top-level service name for this trace */
    @JsonProperty("serviceName")
    private String serviceName;

    /** Original flat span list — unchanged, existing UI continues to work */
    @JsonProperty("spans")
    private List<TraceResponseDTO> spans;

    /** NEW: hierarchical execution tree for structured debugging view */
    @JsonProperty("rootOperation")
    private TraceTreeNode rootOperation;

    /** NEW: aggregated summary for the frontend summary bar — additive only */
    @JsonProperty("summary")
    private TraceSummary summary;

    public TraceTreeResponse() {}

    public TraceTreeResponse(List<TraceResponseDTO> spans, TraceTreeNode rootOperation) {
        this.spans = spans;
        this.rootOperation = rootOperation;
    }

    public List<TraceResponseDTO> getSpans()                              { return spans; }
    public void                   setSpans(List<TraceResponseDTO> spans)  { this.spans = spans; }

    public TraceTreeNode getRootOperation()                                    { return rootOperation; }
    public void          setRootOperation(TraceTreeNode rootOperation)         { this.rootOperation = rootOperation; }

    public String getTraceId()                          { return traceId; }
    public void   setTraceId(String traceId)            { this.traceId = traceId; }

    public String getServiceName()                      { return serviceName; }
    public void   setServiceName(String serviceName)    { this.serviceName = serviceName; }

    public TraceSummary getSummary()                    { return summary; }
    public void         setSummary(TraceSummary summary){ this.summary = summary; }
}

