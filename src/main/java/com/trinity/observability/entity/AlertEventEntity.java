package com.trinity.observability.entity;

import java.time.Instant;

import com.trinity.observability.dto.alerts.AlertState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One immutable thing that happened to an alert.
 *
 * <p>{@link AlertEntity} answers "what is happening now" and is mutated in
 * place; this answers "what happened", and is never updated or deleted except
 * by cascade. The separation matters because acknowledgement and resolution are
 * decisions a person is accountable for — that record has to survive the alert
 * row being overwritten by the next firing, and has to remain readable after
 * the episode closes.</p>
 */
@Entity
@Table(name = "alert_events")
public class AlertEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "org", nullable = false, length = 64)
    private String org;

    // Stays 16 to match the column V1 created. The two values V3 adds fit:
    // CLOSED is 6 and REOPENED is 8, against ACKNOWLEDGED's existing 12 — so
    // widening it would only risk an ddl-auto=validate mismatch for nothing.
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private AlertState eventType;

    /**
     * Hash of the source stream row this event came from, for idempotent
     * re-ingest. Null for operator actions and for the legacy webhook path.
     *
     * <p>The watermark alone cannot prevent a double-count: several alerts can
     * share one microsecond, so the poll bound has to be inclusive somewhere, and
     * a poll that dies after writing some rows will re-read them. The unique index
     * on {@code (org, event_hash)} is what makes re-seeing a firing a no-op rather
     * than another occurrence on the episode — which is what keeps the occurrence
     * count and this audit trail worth trusting.</p>
     */
    @Column(name = "event_hash", length = 64)
    private String eventHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** {@code openobserve}, a username, or {@code auto:stale}. */
    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "observed_count")
    private Long observedCount;

    @Column(name = "agg_value")
    private Double aggValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (occurredAt == null) occurredAt = createdAt;
    }

    /** Convenience factory — every call site sets the same six fields. */
    public static AlertEventEntity of(AlertEntity alert, AlertState type, Instant occurredAt,
                                      String actor, String note) {
        AlertEventEntity e = new AlertEventEntity();
        e.alertId = alert.getId();
        e.fingerprint = alert.getFingerprint();
        e.org = alert.getOrg();
        e.eventType = type;
        e.occurredAt = occurredAt;
        e.actor = actor;
        e.note = note;
        e.observedCount = alert.getObservedCount();
        e.aggValue = alert.getAggValue();
        return e;
    }

    /**
     * A firing ingested from a stream row, carrying that row's hash so a re-read
     * is rejected by {@code uq_alert_events_hash} instead of being counted twice.
     */
    public static AlertEventEntity ofStreamFiring(AlertEntity alert, AlertState type,
            Instant occurredAt, String actor, String note, String eventHash) {
        AlertEventEntity e = of(alert, type, occurredAt, actor, note);
        e.eventHash = eventHash;
        return e;
    }

    public Long getId()                     { return id; }
    public void setId(Long v)               { this.id = v; }
    public Long getAlertId()                { return alertId; }
    public void setAlertId(Long v)          { this.alertId = v; }
    public String getFingerprint()          { return fingerprint; }
    public void setFingerprint(String v)    { this.fingerprint = v; }
    public String getOrg()                  { return org; }
    public void setOrg(String v)            { this.org = v; }
    public AlertState getEventType()        { return eventType; }
    public void setEventType(AlertState v)  { this.eventType = v; }
    public Instant getOccurredAt()          { return occurredAt; }
    public void setOccurredAt(Instant v)    { this.occurredAt = v; }
    public String getActor()                { return actor; }
    public void setActor(String v)          { this.actor = v; }
    public String getNote()                 { return note; }
    public void setNote(String v)           { this.note = v; }
    public Long getObservedCount()          { return observedCount; }
    public void setObservedCount(Long v)    { this.observedCount = v; }
    public Double getAggValue()             { return aggValue; }
    public void setAggValue(Double v)       { this.aggValue = v; }
    public Instant getCreatedAt()           { return createdAt; }
    public void setCreatedAt(Instant v)     { this.createdAt = v; }
    public String getEventHash()            { return eventHash; }
    public void setEventHash(String v)      { this.eventHash = v; }
}
