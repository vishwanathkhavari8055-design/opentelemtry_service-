package com.trinity.observability.exception;

/**
 * The request cannot be applied because it conflicts with current state —
 * a duplicate rule name, or a concurrent edit that already moved the row on.
 *
 * <p>Mapped to 409. Separate from a validation error because the request was
 * well-formed; it is the world that disagrees with it.</p>
 */
public class ResourceConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceConflictException(String message) {
        super(message);
    }

    public ResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
