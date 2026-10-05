package com.trinity.observability.dto.traces;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A severity-enriched log event attached to a {@link TraceTreeNode}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceLogEvent {

    private static final String TYPE = "LOG";

    @JsonProperty("id")
    private String id;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("message")
    private String message;

    @JsonProperty("messagePreview")
    private String messagePreview;

    @JsonProperty("fullMessage")
    private String fullMessage;

    @JsonProperty("isTruncated")
    private Boolean isTruncated;

    @JsonProperty("count")
    private Integer count = 1;

    @JsonProperty("serviceName")
    private String serviceName;

    /** Milliseconds elapsed since the parent span's startTime (decimal precision) */
    @JsonProperty("relativeDurationMs")
    private Double relativeDurationMs;

    /** Offset from request start (decimal precision) */
    @JsonProperty("relativeTimeMs")
    private Double relativeTimeMs;

    @JsonProperty("sequence")
    private Integer sequence;

    @JsonProperty("className")
    private String className;

    @JsonProperty("methodName")
    private String methodName;

    /** Source file the log was emitted from — OTel `code.filepath`. Lets the
     *  trace view jump straight to "where did this happen" without forcing the
     *  user to swap over to the logs tab. */
    @JsonProperty("filePath")
    private String filePath;

    /** Line number within {@link #filePath} — OTel `code.lineno`. */
    @JsonProperty("lineNumber")
    private Integer lineNumber;

    @JsonProperty("traceId")
    private String traceId;

    @JsonProperty("spanId")
    private String spanId;

    /** OTel `exception.type` — the throwable's class (error-class logs only). */
    @JsonProperty("exceptionType")
    private String exceptionType;

    /** OTel `exception.message` — the throwable's message. */
    @JsonProperty("exceptionMessage")
    private String exceptionMessage;

    /** OTel `exception.stacktrace` — full, secret-masked, untruncated. Populated
     *  only for error-class severities (gated in the correlator's SELECT). */
    @JsonProperty("stackTrace")
    private String stackTrace;

    /** Default constructor required by Jackson for deserialization. */
    public TraceLogEvent() {
        // Jackson deserialization
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    @JsonProperty("type")
    public String getType() { return TYPE; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getMessagePreview() { return messagePreview; }
    public void setMessagePreview(String messagePreview) { this.messagePreview = messagePreview; }

    public String getFullMessage() { return fullMessage; }
    public void setFullMessage(String fullMessage) { this.fullMessage = fullMessage; }

    public Boolean getIsTruncated() { return isTruncated; }
    public void setIsTruncated(Boolean isTruncated) { this.isTruncated = isTruncated; }

    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public Double getRelativeDurationMs() { return relativeDurationMs; }
    public void setRelativeDurationMs(Double relativeDurationMs) { this.relativeDurationMs = relativeDurationMs; }

    public Double getRelativeTimeMs() { return relativeTimeMs; }
    public void setRelativeTimeMs(Double relativeTimeMs) { this.relativeTimeMs = relativeTimeMs; }

    public Integer getSequence() { return sequence; }
    public void setSequence(Integer sequence) { this.sequence = sequence; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }

    public String getExceptionMessage() { return exceptionMessage; }
    public void setExceptionMessage(String exceptionMessage) { this.exceptionMessage = exceptionMessage; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
}
