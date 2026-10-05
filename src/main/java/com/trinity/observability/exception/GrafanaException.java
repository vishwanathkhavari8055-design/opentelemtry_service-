package com.trinity.observability.exception;

/**
 * Grafana was reached and refused, or could not be reached at all.
 *
 * <p>Mapped to 502 by {@link GlobalExceptionHandler}, deliberately — the caller's
 * request was fine, an upstream this service depends on is not. Reporting a 500
 * would point an operator at this service's logs when the fix is a Grafana token,
 * a firewall rule or a certificate.</p>
 *
 * <p>{@link #getUpstreamStatus()} carries what Grafana actually answered (0 when
 * nothing answered), because the three failures a deployment hits — 401/403 for a
 * bad or under-privileged token, 404 for a stale UID, connection refused for the
 * wrong host — are told apart by exactly that number.</p>
 */
public class GrafanaException extends ObservabilityException {

    private static final long serialVersionUID = 1L;

    private final int upstreamStatus;

    public GrafanaException(String message, int upstreamStatus) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public GrafanaException(String message, int upstreamStatus, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
