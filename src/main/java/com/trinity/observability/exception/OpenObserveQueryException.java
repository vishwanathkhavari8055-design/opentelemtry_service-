package com.trinity.observability.exception;

public class OpenObserveQueryException extends ObservabilityException {

    private final int code;
    private final String openObserveTraceId;

    public OpenObserveQueryException(int code, String message, String openObserveTraceId) {
        super(buildMessage(code, message, openObserveTraceId));
        this.code = code;
        this.openObserveTraceId = openObserveTraceId;
    }

    public OpenObserveQueryException(int code, String message, String openObserveTraceId, Throwable cause) {
        super(buildMessage(code, message, openObserveTraceId), cause);
        this.code = code;
        this.openObserveTraceId = openObserveTraceId;
    }

    public int getCode() {
        return code;
    }

    public String getOpenObserveTraceId() {
        return openObserveTraceId;
    }

    private static String buildMessage(int code, String message, String openObserveTraceId) {
        return "OpenObserve query failed (code=" + code
            + ", oo_trace_id=" + openObserveTraceId + "): " + message;
    }
}
