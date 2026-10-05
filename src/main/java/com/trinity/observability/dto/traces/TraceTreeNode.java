package com.trinity.observability.dto.traces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A node in the hierarchical execution tree view of a trace.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceTreeNode {

    @JsonProperty("spanId")
    private String spanId;

    @JsonProperty("parentId")
    private String parentId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("status")
    private String status;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("operation")
    private String operation;

    @JsonProperty("target")
    private String target;

    @JsonProperty("serviceName")
    private String serviceName;

    @JsonProperty("startTime")
    private String startTime;

    @JsonProperty("endTime")
    private String endTime;

    /** Duration in milliseconds (preserved decimal precision from microseconds) */
    @JsonProperty("durationMs")
    private Double durationMs;

    @JsonProperty("duration")
    private Long duration;

    @JsonProperty("isSlow")
    private Boolean isSlow;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("count")
    private Integer count;

    @JsonProperty("totalDuration")
    private Long totalDuration;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    @JsonProperty("children")
    private List<TraceTreeNode> children = new ArrayList<>();

    @JsonProperty("events")
    private List<TraceLogEvent> events = new ArrayList<>();

    // ── Additive Log Fields (for mirrored nodes) ──────────────────────────────

    @JsonProperty("relativeTimeMs")
    private Double relativeTimeMs;

    @JsonProperty("sequence")
    private Integer sequence;

    @JsonProperty("messagePreview")
    private String messagePreview;

    @JsonProperty("fullMessage")
    private String fullMessage;

    @JsonProperty("isTruncated")
    private Boolean isTruncated;

    // ── Root Header Fields ────────────────────────────────────────────────────

    @JsonProperty("requestMethod")
    private String requestMethod;

    @JsonProperty("requestPath")
    private String requestPath;

    @JsonProperty("requestLabel")
    private String requestLabel;

    @JsonProperty("totalDurationMs")
    private Double totalDurationMs;

    /** Classification: BUSINESS_REQUEST | INFRASTRUCTURE */
    @JsonProperty("rootCategory")
    private String rootCategory;

    // ── Type-specific fields (mirrored from TraceResponseDTO via toNode) ─────
    // The UI's TraceDetailPane and TraceTreeNode read these directly from the
    // tree node — without them populated here, the inspector pane shows
    // nothing for HTTP/DB/Redis spans even though OoTraceInterpreter has
    // already extracted them from the OO record.

    @JsonProperty("httpMethod")
    private String httpMethod;

    @JsonProperty("httpStatus")
    private Integer httpStatus;

    @JsonProperty("httpRequestBody")
    private String httpRequestBody;

    @JsonProperty("httpResponseBody")
    private String httpResponseBody;

    @JsonProperty("endpoint")
    private String endpoint;

    @JsonProperty("dbStatement")
    private String dbStatement;

    @JsonProperty("databaseType")
    private String databaseType;

    /** Connection direction — INCOMING (server-side, this service is being
     *  called) or OUTGOING (client-side, this service is calling someone).
     *  Derived from OTel span_kind in OoTraceInterpreter. */
    @JsonProperty("direction")
    private String direction;

    /** OTel `thread.name` — surfaced so the UI's logs-by-span view can
     *  group parallel/async spans by their owning thread. */
    @JsonProperty("threadName")
    private String threadName;

    // ── Log source-location fields (populated on LOG nodes via toLogNode) ────
    // The UI renders these as the "logger.method():line" suffix in the trace
    // log row's meta line. Without them, the trace inspector only carries the
    // log's message and severity — to find where a line of code emitted the
    // log the user has to switch to the logs tab and search manually.

    /** OTel `code.namespace` — fully-qualified class / logger name. */
    @JsonProperty("className")
    private String className;

    /** OTel `code.function` — method name within {@link #className}. */
    @JsonProperty("methodName")
    private String methodName;

    /** OTel `code.filepath` — source file the log was emitted from. */
    @JsonProperty("filePath")
    private String filePath;

    /** OTel `code.lineno` — line number within {@link #filePath}. */
    @JsonProperty("lineNumber")
    private Integer lineNumber;

    /** OTel `exception.type` — throwable class on an error-class LOG node. */
    @JsonProperty("exceptionType")
    private String exceptionType;

    /** OTel `exception.message` — throwable message on an error-class LOG node. */
    @JsonProperty("exceptionMessage")
    private String exceptionMessage;

    /** OTel `exception.stacktrace` — full, secret-masked, untruncated stack trace
     *  carried onto error-class LOG nodes so the trace inspector can show it. */
    @JsonProperty("stackTrace")
    private String stackTrace;

    public TraceTreeNode() {
        // Jackson deserialization
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public Double getDurationMs() { return durationMs; }
    public void setDurationMs(Double durationMs) { this.durationMs = durationMs; }

    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }

    public Boolean getIsSlow() { return isSlow; }
    public void setIsSlow(Boolean isSlow) { this.isSlow = isSlow; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }

    public Long getTotalDuration() { return totalDuration; }
    public void setTotalDuration(Long totalDuration) { this.totalDuration = totalDuration; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> m) { this.metadata = m; }

    public List<TraceTreeNode> getChildren() { return children; }
    public void setChildren(List<TraceTreeNode> c) { this.children = c != null ? c : new ArrayList<>(); }

    public List<TraceLogEvent> getEvents() { return events; }
    public void setEvents(List<TraceLogEvent> e) { this.events = e != null ? e : new ArrayList<>(); }

    public Double getRelativeTimeMs() { return relativeTimeMs; }
    public void setRelativeTimeMs(Double relativeTimeMs) { this.relativeTimeMs = relativeTimeMs; }

    public Integer getSequence() { return sequence; }
    public void setSequence(Integer sequence) { this.sequence = sequence; }

    public String getMessagePreview() { return messagePreview; }
    public void setMessagePreview(String messagePreview) { this.messagePreview = messagePreview; }

    public String getFullMessage() { return fullMessage; }
    public void setFullMessage(String fullMessage) { this.fullMessage = fullMessage; }

    public Boolean getIsTruncated() { return isTruncated; }
    public void setIsTruncated(Boolean isTruncated) { this.isTruncated = isTruncated; }

    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }

    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String requestPath) { this.requestPath = requestPath; }

    public String getRequestLabel() { return requestLabel; }
    public void setRequestLabel(String requestLabel) { this.requestLabel = requestLabel; }

    public Double getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Double totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public String getRootCategory() { return rootCategory; }
    public void setRootCategory(String rootCategory) { this.rootCategory = rootCategory; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public String getHttpRequestBody() { return httpRequestBody; }
    public void setHttpRequestBody(String httpRequestBody) { this.httpRequestBody = httpRequestBody; }

    public String getHttpResponseBody() { return httpResponseBody; }
    public void setHttpResponseBody(String httpResponseBody) { this.httpResponseBody = httpResponseBody; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getDbStatement() { return dbStatement; }
    public void setDbStatement(String dbStatement) { this.dbStatement = dbStatement; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }

    public String getExceptionMessage() { return exceptionMessage; }
    public void setExceptionMessage(String exceptionMessage) { this.exceptionMessage = exceptionMessage; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
}
