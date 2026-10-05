package com.trinity.observability.exception;

public class ObservabilityException extends RuntimeException {
    public ObservabilityException(String message) {
        super(message);
    }

    public ObservabilityException(String message, Throwable cause) {
        super(message, cause);
    }
}
