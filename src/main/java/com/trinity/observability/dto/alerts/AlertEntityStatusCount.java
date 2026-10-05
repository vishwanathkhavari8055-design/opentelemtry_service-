package com.trinity.observability.dto.alerts;

/**
 * One {@code (status, severity) → count} row from the summary aggregate.
 *
 * <p>A JPQL constructor projection rather than {@code Object[]}: the counts are
 * assembled into the badge in one round trip, and positional array access would
 * turn a column reorder into a ClassCastException at runtime instead of a
 * compile error.</p>
 */
public class AlertEntityStatusCount {

    private final AlertState status;
    private final String severity;
    private final long count;

    public AlertEntityStatusCount(AlertState status, String severity, long count) {
        this.status = status;
        this.severity = severity;
        this.count = count;
    }

    public AlertState getStatus() { return status; }
    public String getSeverity()   { return severity; }
    public long getCount()        { return count; }
}
