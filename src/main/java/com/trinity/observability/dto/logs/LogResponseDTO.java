package com.trinity.observability.dto.logs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogResponseDTO {

    private static final String UNKNOWN = "unknown";

    private String id;

    @JsonProperty("@timestamp")
    private String timestamp;

    @JsonProperty("severity_text")
    private String severity;

    private String message;

    @JsonProperty("service.name")
    private String serviceName;

    @JsonProperty("host.name")
    private String hostName;

    @JsonProperty("host.os.type")
    private String osType;

    @JsonProperty("scope.name")
    private String scopeName;

    @JsonProperty("traceId")
    private String traceId;

    @JsonProperty("code.namespace")
    private String codeNamespace = UNKNOWN;

    @JsonProperty("code.function")
    private String codeFunction = UNKNOWN;

    @JsonProperty("code.lineno")
    private Integer codeLineNo;

    @JsonProperty("code.filepath")
    private String codeFilepath;

    @JsonProperty("exception.type")
    private String exceptionType;

    @JsonProperty("exception.message")
    private String exceptionMessage;

    @JsonProperty("exception.stacktrace")
    private String stackTrace;

    public LogResponseDTO() {
    }

    @SuppressWarnings("java:S107") // DTO populated from external JSON schema; parameter count matches the shape
    public LogResponseDTO(String id, String timestamp, String severity, String message, String serviceName,
            String hostName, String osType, String scopeName, String traceId, String codeNamespace,
            String codeFunction) {
        this.id = id;
        this.timestamp = timestamp;
        this.severity = severity;
        this.message = message;
        this.serviceName = serviceName;
        this.hostName = hostName;
        this.osType = osType;
        this.scopeName = scopeName;
        this.traceId = traceId;
        this.codeNamespace = codeNamespace != null ? codeNamespace : UNKNOWN;
        this.codeFunction = codeFunction != null ? codeFunction : UNKNOWN;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getOsType() {
        return osType;
    }

    public void setOsType(String osType) {
        this.osType = osType;
    }

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getCodeNamespace() {
        return codeNamespace;
    }

    public void setCodeNamespace(String codeNamespace) {
        this.codeNamespace = codeNamespace;
    }

    public String getCodeFunction() {
        return codeFunction;
    }

    public void setCodeFunction(String codeFunction) {
        this.codeFunction = codeFunction;
    }

    public Integer getCodeLineNo() {
        return codeLineNo;
    }

    public void setCodeLineNo(Integer codeLineNo) {
        this.codeLineNo = codeLineNo;
    }

    public String getCodeFilepath() {
        return codeFilepath;
    }

    public void setCodeFilepath(String codeFilepath) {
        this.codeFilepath = codeFilepath;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public static class LogResponseDTOBuilder {
        private String id;

        public LogResponseDTOBuilder id(String id) {
            this.id = id;
            return this;
        }

        public LogResponseDTO build() {
            return new LogResponseDTO(id, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public static LogResponseDTOBuilder builder() {
        return new LogResponseDTOBuilder();
    }
}
