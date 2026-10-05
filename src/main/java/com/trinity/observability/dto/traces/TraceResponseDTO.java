package com.trinity.observability.dto.traces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceResponseDTO {

    // ── Original fields (unchanged — no breaking changes) ──────────────────────
    @JsonProperty("traceId")
    private String traceId;

    @JsonProperty("spanId")
    private String spanId;

    /** OpenObserve `reference_parent_span_id` / ES `parent.id`. Carries the
     *  natural parent→child relationship from the underlying trace store
     *  so the UI can reconstruct a real span tree (TraceTreeBuilder's
     *  category groups discard this — they only sort by type). */
    @JsonProperty("parentSpanId")
    private String parentSpanId;

    /** OTel `thread.name` — useful for the UI's logs-by-span view to
     *  segregate parallel/async spans (e.g. concurrent Mongo finds on
     *  different executor threads) so they don't render as a confusing
     *  time-interleaved flat list. */
    @JsonProperty("threadName")
    private String threadName;

    @JsonProperty("spanName")
    private String spanName;

    @JsonProperty("actualOperation")
    private String actualOperation;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("serviceName")
    private String serviceName;

    @JsonProperty("hostName")
    private String hostName;

    @JsonProperty("duration")
    private Long duration;

    // ── NEW enriched fields (additive — backward compatible) ───────────────────

    /** Span classification: HTTP | GRAPHQL | DATABASE | INTERNAL */
    @JsonProperty("type")
    private String type;

    /** Connection direction: INCOMING (service entry) | OUTGOING (external call) */
    @JsonProperty("direction")
    private String direction;

    /** Outcome: success | failure, or HTTP status string like "200 OK" */
    @JsonProperty("status")
    private String status;

    /** Primary target (collection name, GraphQL operation, URL path, etc.) */
    @JsonProperty("target")
    private String target;

    /** Database technology: mongodb | neo4j | redis | sql */
    @JsonProperty("databaseType")
    private String databaseType;

    /** Human-readable DB statement or query for display */
    @JsonProperty("dbStatement")
    private String dbStatement;

    /** Full URL endpoint path */
    @JsonProperty("endpoint")
    private String endpoint;

    /** HTTP method: GET | POST | PUT | DELETE */
    @JsonProperty("httpMethod")
    private String httpMethod;

    /** HTTP response status code */
    @JsonProperty("httpStatus")
    private Integer httpStatus;

    /** HTTP request body payload (raw string, may be JSON). Captured by the
     *  OTel agent when capture-headers/body is enabled. */
    @JsonProperty("httpRequestBody")
    private String httpRequestBody;

    /** HTTP response body payload (raw string, may be JSON). */
    @JsonProperty("httpResponseBody")
    private String httpResponseBody;

    // ── Constructors ───────────────────────────────────────────────────────────

    public TraceResponseDTO() {}

    @SuppressWarnings("java:S107") // DTO populated from external JSON schema; parameter count matches the shape
    public TraceResponseDTO(String traceId, String spanId, String spanName, String actualOperation,
                            String timestamp, String serviceName, String hostName, Long duration) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.spanName = spanName;
        this.actualOperation = actualOperation;
        this.timestamp = timestamp;
        this.serviceName = serviceName;
        this.hostName = hostName;
        this.duration = duration;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public String getSpanName() { return spanName; }
    public void setSpanName(String spanName) { this.spanName = spanName; }

    public String getActualOperation() { return actualOperation; }
    public void setActualOperation(String actualOperation) { this.actualOperation = actualOperation; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    /** Backward-compatible alias — delegates to {@link #getHostName()}. */
    public String getHost() { return getHostName(); }

    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public String getDbStatement() { return dbStatement; }
    public void setDbStatement(String dbStatement) { this.dbStatement = dbStatement; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public String getHttpRequestBody() { return httpRequestBody; }
    public void setHttpRequestBody(String httpRequestBody) { this.httpRequestBody = httpRequestBody; }

    public String getHttpResponseBody() { return httpResponseBody; }
    public void setHttpResponseBody(String httpResponseBody) { this.httpResponseBody = httpResponseBody; }

    // ── Builder (backward compatible) ─────────────────────────────────────────

    public static class TraceResponseDTOBuilder {
        private String traceId;
        public TraceResponseDTOBuilder traceId(String traceId) { this.traceId = traceId; return this; }
        public TraceResponseDTO build() { return new TraceResponseDTO(traceId, null, null, null, null, null, null, null); }
    }
    public static TraceResponseDTOBuilder builder() { return new TraceResponseDTOBuilder(); }
}
