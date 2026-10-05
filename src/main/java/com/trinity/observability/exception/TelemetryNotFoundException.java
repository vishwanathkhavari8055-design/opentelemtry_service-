package com.trinity.observability.exception;

/**
 * A resource was offered for registration but OpenObserve holds no telemetry
 * for it.
 *
 * <p>Extends {@link IllegalArgumentException} so it maps to 400 through the
 * handler {@code GlobalExceptionHandler} already has — the submitted value is
 * the problem, and the caller's correct response is to fix it (start emitting
 * telemetry, or check the spelling) rather than to retry.</p>
 *
 * <p>404 was the other candidate and is wrong here: an API answering 404 to a
 * POST reads as "this endpoint does not exist", which is precisely the
 * misdiagnosis {@code GlobalExceptionHandler}'s own notes warn about.</p>
 *
 * <p>It exists as its own type rather than a bare {@code IllegalArgumentException}
 * so that this specific outcome stays greppable, and so a future handler can give
 * it a distinct status without touching any call site.</p>
 */
public class TelemetryNotFoundException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public TelemetryNotFoundException(String message) {
        super(message);
    }
}
