package com.trinity.observability.entity;

import java.time.Instant;


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
 * Append-only audit trail for alert rule changes.
 *
 * <p>{@code alert_rules} carries {@code updated_by} / {@code updated_at}, which
 * answers "who touched it last" but destroys the answer to "what happened to it".
 * Enabling, disabling, cloning and a failed synchronisation are each things
 * somebody later has to account for — "why did this stop paging us on Tuesday" is
 * not answerable from a single overwritten column.</p>
 *
 * <p>Never updated, only inserted. Same reasoning as {@code alert_events}: a
 * record of a decision has to outlive the next change overwriting the row it was
 * made about.</p>
 */
@Entity
@Table(name = "alert_rule_events")
public class AlertRuleEventEntity {

    /** What happened to a rule. Mirrors {@code ck_alert_rule_events_type}. */
    public enum Type {
        CREATED,
        UPDATED,
        DELETED,
        ENABLED,
        DISABLED,
        CLONED,
        /** The OpenObserve push for this change succeeded. */
        SYNCED,
        /** The OpenObserve push failed — the rule is stored but may not be evaluating. */
        SYNC_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "alert_rule_id", nullable = false)
    private Long alertRuleId;

    @Column(name = "org", nullable = false, length = 64)
    private String org;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private Type eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", length = 24)
    private SyncStatus syncStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (occurredAt == null) {
            occurredAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    public static AlertRuleEventEntity of(Long ruleId, String org, Type type,
            String actor, String detail, SyncStatus syncStatus) {
        AlertRuleEventEntity event = new AlertRuleEventEntity();
        event.alertRuleId = ruleId;
        event.org = org;
        event.eventType = type;
        event.actor = actor;
        event.detail = detail;
        event.syncStatus = syncStatus;
        event.occurredAt = Instant.now();
        return event;
    }

    public Long getId()                   { return id; }
    public Long getAlertRuleId()          { return alertRuleId; }
    public void setAlertRuleId(Long v)    { this.alertRuleId = v; }
    public String getOrg()                { return org; }
    public void setOrg(String v)          { this.org = v; }
    public Type getEventType()            { return eventType; }
    public void setEventType(Type v)      { this.eventType = v; }
    public Instant getOccurredAt()        { return occurredAt; }
    public void setOccurredAt(Instant v)  { this.occurredAt = v; }
    public String getActor()              { return actor; }
    public void setActor(String v)        { this.actor = v; }
    public String getDetail()             { return detail; }
    public void setDetail(String v)       { this.detail = v; }
    public SyncStatus getSyncStatus()     { return syncStatus; }
    public void setSyncStatus(SyncStatus v) { this.syncStatus = v; }
    public Instant getCreatedAt()         { return createdAt; }
}
