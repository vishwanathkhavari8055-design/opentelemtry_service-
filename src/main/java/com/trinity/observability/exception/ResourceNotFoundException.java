package com.trinity.observability.exception;

/**
 * A resource addressed by the caller does not exist.
 *
 * <p>Mapped to 404 by {@link GlobalExceptionHandler}. Distinct from a generic
 * failure because "the rule you asked for is gone" is a normal outcome a client
 * must be able to handle — retrying it will never help, whereas retrying a 500
 * might.</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String what, Object id) {
        return new ResourceNotFoundException(what + " not found: " + id);
    }
}
