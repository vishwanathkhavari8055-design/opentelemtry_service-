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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One alert episode — a problem that started, possibly got acknowledged, and
 * eventually ended.
 *
 * <p>Not one row per notification. A rule firing every minute for an hour is a
 * single row with {@code occurrences = 60}; sixty rows describing one ongoing
 * problem is how an alert screen becomes unreadable. When an episode resolves
 * and the same rule fires again, that is a genuinely new incident and gets a
 * new row, with {@link #episode} incremented.</p>
 *
 * <p>The partial unique index {@code uq_alerts_open_episode} enforces at most
 * one non-resolved row per fingerprint. That constraint is what makes
 * de-duplication correct when two webhook deliveries race: the database
 * rejects the second insert instead of both succeeding.</p>
 */
@Entity
@Table(name = "alerts")
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hash of org + name + stream. Excludes the observed value — see AlertPayloadNormalizer. */
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "org", nullable = false, length = 64)
    private String org;

    /**
     * The rule that produced this, when it is one this service manages.
     *
     * <p>Null is normal and must stay supported: alerts also arrive from rules
     * authored directly in OpenObserve's own UI, which have no row in
     * {@code alert_rules}. Refusing those would hide real firings over a
     * bookkeeping gap.</p>
     */
    @Column(name = "alert_rule_id")
    private Long alertRuleId;

    @Column(name = "alert_name", nullable = false)
    private String alertName;

    /**
     * What KIND of problem this episode is about, independent of its severity.
     *
     * <p>The rule name with a trailing severity token removed. Part of the incident
     * identity (see {@code alerts.ingest.identity-fields}), which is why a rule that
     * escalates from {@code …-warning} to {@code …-disaster} folds into ONE row that
     * changes severity rather than opening a second.</p>
     *
     * <p>Nullable: rows recorded before this column existed have it backfilled by
     * migration V9, but a firing whose template carried no usable name at all still
     * has nothing to derive it from.</p>
     */
    @Column(name = "incident_type")
    private String incidentType;

    @Column(name = "alert_type", length = 32)
    private String alertType;

    @Column(name = "stream_type", length = 32)
    private String streamType;

    @Column(name = "stream_name")
    private String streamName;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity = "warning";

    /**
     * How the severity above was decided — url-override | payload |
     * value-threshold | rule | name-guess | default.
     *
     * <p>Recorded because a bare "critical" is unexplainable after the fact:
     * nobody can tell whether it crossed a threshold or merely inherited a
     * label, and that is the first question asked when a page is disputed.</p>
     */
    @Column(name = "severity_source", length = 24)
    private String severitySource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AlertState status = AlertState.FIRING;

    @Column(name = "first_fired_at", nullable = false)
    private Instant firstFiredAt;

    @Column(name = "last_fired_at", nullable = false)
    private Instant lastFiredAt;

    @Column(name = "occurrences", nullable = false)
    private int occurrences = 1;

    @Column(name = "episode", nullable = false)
    private int episode = 1;

    @Column(name = "acknowledged_by", length = 128)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "occurrences_at_ack")
    private Integer occurrencesAtAck;

    @Column(name = "resolved_by", length = 128)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "auto_resolved", nullable = false)
    private boolean autoResolved = false;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "period_minutes")
    private Integer periodMinutes;

    @Column(name = "operator", length = 16)
    private String operator;

    @Column(name = "threshold")
    private Long threshold;

    @Column(name = "observed_count")
    private Long observedCount;

    @Column(name = "agg_value")
    private Double aggValue;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "alert_url", columnDefinition = "text")
    private String alertUrl;

    @Column(name = "parse_error", columnDefinition = "text")
    private String parseError;

    @Column(name = "raw_payload", columnDefinition = "text")
    private String rawPayload;

    // ── lifecycle beyond resolution (V3) ────────────────────────────────────

    @Column(name = "closed_by", length = 128)
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "reopen_count", nullable = false)
    private Integer reopenCount = 0;

    @Column(name = "last_reopened_by", length = 128)
    private String lastReopenedBy;

    @Column(name = "last_reopened_at")
    private Instant lastReopenedAt;

    // ── resource identity (V3) ──────────────────────────────────────────────
    //
    // Extracted at ingest from the OpenObserve notification payload. All
    // nullable: which of these a payload carries is decided by the deployment's
    // own notification template, not by this schema.

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "host_name")
    private String hostName;

    @Column(name = "namespace")
    private String namespace;

    @Column(name = "environment", length = 64)
    private String environment;

    /** Which ingest path produced this row: {@code stream} or {@code webhook}. */
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    /** Affected entities, exactly as the notification template rendered them. */
    @Column(name = "entities", columnDefinition = "text")
    private String entities;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── state transitions ───────────────────────────────────────────────────

    /**
     * Folds another firing of an already-open episode into this row.
     *
     * <p>Status is not reset to FIRING. An acknowledged alert that fires again
     * stays acknowledged — someone is already on it, and re-raising it would
     * undo their claim on every evaluation. {@link #getNewSinceAck()} is how
     * the UI still shows that it has not stopped.</p>
     */
    public void recordFiring(Instant firedAt, Long observedCount, Double aggValue,
                             Long threshold, String operator, Integer periodMinutes,
                             String alertUrl) {
        this.occurrences++;
        advanceLastFiredAt(firedAt);
        if (observedCount != null) this.observedCount = observedCount;
        if (aggValue != null)      this.aggValue = aggValue;
        if (threshold != null)     this.threshold = threshold;
        if (operator != null)      this.operator = operator;
        if (periodMinutes != null) this.periodMinutes = periodMinutes;
        if (alertUrl != null)      this.alertUrl = alertUrl;
    }

    /**
     * max(), not assignment: deliveries can arrive out of order and a late
     * one must not rewind "last seen" and make a live alert look stale.
     */
    private void advanceLastFiredAt(Instant firedAt) {
        if (firedAt != null && (this.lastFiredAt == null || firedAt.isAfter(this.lastFiredAt))) {
            this.lastFiredAt = firedAt;
        }
    }

    public void acknowledge(String actor, String note) {
        this.status = AlertState.ACKNOWLEDGED;
        this.acknowledgedBy = actor;
        this.acknowledgedAt = Instant.now();
        this.occurrencesAtAck = this.occurrences;
        if (note != null) this.note = note;
    }

    public void resolve(String actor, String note, boolean auto) {
        this.status = AlertState.RESOLVED;
        this.resolvedBy = actor;
        this.resolvedAt = Instant.now();
        this.autoResolved = auto;
        if (note != null) this.note = note;
    }

    /**
     * Closes the alert — a human is finished with it.
     *
     * <p>Does NOT touch {@code resolvedAt} / {@code autoResolved}. Closing an
     * alert that the sweeper had already auto-resolved must keep the record that
     * resolution was inferred rather than observed, because that is exactly what
     * someone reviewing the incident later needs to know.</p>
     */
    public void close(String actor, String note) {
        this.status = AlertState.CLOSED;
        this.closedBy = actor;
        this.closedAt = Instant.now();
        if (note != null) this.note = note;
    }

    /**
     * Reopens a resolved or closed alert, putting it back on the active list.
     *
     * <p>Returns to FIRING, not ACKNOWLEDGED: reopening means "this is not
     * actually over", and it should demand attention again rather than appear
     * already-handled. The previous acknowledgement is cleared for the same
     * reason — leaving a stale "acknowledged by" would credit someone with
     * owning a problem they had signed off.</p>
     *
     * <p>{@code resolvedAt} and {@code closedAt} are cleared so the row's state
     * is self-consistent, but the event trail keeps the history: this is why
     * {@code alert_events} exists and is never rewritten.</p>
     */
    public void reopen(String actor, String note) {
        this.status = AlertState.FIRING;
        this.reopenCount = (this.reopenCount == null ? 0 : this.reopenCount) + 1;
        this.lastReopenedBy = actor;
        this.lastReopenedAt = Instant.now();
        this.acknowledgedBy = null;
        this.acknowledgedAt = null;
        this.occurrencesAtAck = null;
        this.resolvedBy = null;
        this.resolvedAt = null;
        this.autoResolved = false;
        this.closedBy = null;
        this.closedAt = null;
        if (note != null) this.note = note;
    }

    /** Firings received since someone took ownership; zero unless acknowledged. */
    public int getNewSinceAck() {
        if (status != AlertState.ACKNOWLEDGED || occurrencesAtAck == null) return 0;
        return Math.max(0, occurrences - occurrencesAtAck);
    }

    /**
     * How long this alert has been going: first firing to resolution, or to NOW
     * while it is still open.
     *
     * <p>Measuring an open alert to {@code lastFiredAt} instead would report the
     * span between its firings, so an alert that opened twenty minutes ago and has
     * not re-fired since would read "0s" — actively misleading on the one screen
     * where "how long has this been broken" is the question being asked.</p>
     *
     * <p>A terminal alert measures to whichever ending it actually has. Resolution
     * wins over closure when both are set, because the condition clearing is the
     * event the duration is about; the closure came later and is somebody
     * finishing their paperwork.</p>
     */
    public long getDurationSeconds() {
        if (firstFiredAt == null) {
            return 0L;
        }
        Instant end;
        if (resolvedAt != null) {
            end = resolvedAt;
        } else if (closedAt != null) {
            end = closedAt;
        } else if (status != null && status.isTerminal()) {
            // Terminal but with no timestamp — a row from before those columns
            // existed. Its last firing is the best ending available.
            end = lastFiredAt;
        } else {
            end = Instant.now();
        }
        if (end == null) {
            return 0L;
        }
        return Math.max(0L, end.getEpochSecond() - firstFiredAt.getEpochSecond());
    }

    /** Whether this alert is off the active list. */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    // ── accessors ───────────────────────────────────────────────────────────

    public Long getId()                        { return id; }
    public void setId(Long v)                  { this.id = v; }
    public String getFingerprint()             { return fingerprint; }
    public void setFingerprint(String v)       { this.fingerprint = v; }
    public String getOrg()                     { return org; }
    public void setOrg(String v)               { this.org = v; }
    public Long getAlertRuleId()               { return alertRuleId; }
    public void setAlertRuleId(Long v)         { this.alertRuleId = v; }
    public String getAlertName()               { return alertName; }
    public void setAlertName(String v)         { this.alertName = v; }
    public String getIncidentType()            { return incidentType; }
    public void setIncidentType(String v)      { this.incidentType = v; }
    public String getAlertType()               { return alertType; }
    public void setAlertType(String v)         { this.alertType = v; }
    public String getStreamType()              { return streamType; }
    public void setStreamType(String v)        { this.streamType = v; }
    public String getStreamName()              { return streamName; }
    public void setStreamName(String v)        { this.streamName = v; }
    public String getSeverity()                { return severity; }
    public void setSeverity(String v)          { this.severity = v; }
    public String getSeveritySource()          { return severitySource; }
    public void setSeveritySource(String v)    { this.severitySource = v; }
    public AlertState getStatus()              { return status; }
    public void setStatus(AlertState v)        { this.status = v; }
    public Instant getFirstFiredAt()           { return firstFiredAt; }
    public void setFirstFiredAt(Instant v)     { this.firstFiredAt = v; }
    public Instant getLastFiredAt()            { return lastFiredAt; }
    public void setLastFiredAt(Instant v)      { this.lastFiredAt = v; }
    public int getOccurrences()                { return occurrences; }
    public void setOccurrences(int v)          { this.occurrences = v; }
    public int getEpisode()                    { return episode; }
    public void setEpisode(int v)              { this.episode = v; }
    public String getAcknowledgedBy()          { return acknowledgedBy; }
    public void setAcknowledgedBy(String v)    { this.acknowledgedBy = v; }
    public Instant getAcknowledgedAt()         { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant v)   { this.acknowledgedAt = v; }
    public Integer getOccurrencesAtAck()       { return occurrencesAtAck; }
    public void setOccurrencesAtAck(Integer v) { this.occurrencesAtAck = v; }
    public String getResolvedBy()              { return resolvedBy; }
    public void setResolvedBy(String v)        { this.resolvedBy = v; }
    public Instant getResolvedAt()             { return resolvedAt; }
    public void setResolvedAt(Instant v)       { this.resolvedAt = v; }
    public boolean isAutoResolved()            { return autoResolved; }
    public void setAutoResolved(boolean v)     { this.autoResolved = v; }
    public String getNote()                    { return note; }
    public void setNote(String v)              { this.note = v; }
    public Integer getPeriodMinutes()          { return periodMinutes; }
    public void setPeriodMinutes(Integer v)    { this.periodMinutes = v; }
    public String getOperator()                { return operator; }
    public void setOperator(String v)          { this.operator = v; }
    public Long getThreshold()                 { return threshold; }
    public void setThreshold(Long v)           { this.threshold = v; }
    public Long getObservedCount()             { return observedCount; }
    public void setObservedCount(Long v)       { this.observedCount = v; }
    public Double getAggValue()                { return aggValue; }
    public void setAggValue(Double v)          { this.aggValue = v; }
    public Instant getWindowStart()            { return windowStart; }
    public void setWindowStart(Instant v)      { this.windowStart = v; }
    public Instant getWindowEnd()              { return windowEnd; }
    public void setWindowEnd(Instant v)        { this.windowEnd = v; }
    public String getAlertUrl()                { return alertUrl; }
    public void setAlertUrl(String v)          { this.alertUrl = v; }
    public String getParseError()              { return parseError; }
    public void setParseError(String v)        { this.parseError = v; }
    public String getRawPayload()              { return rawPayload; }
    public void setRawPayload(String v)        { this.rawPayload = v; }
    public Instant getCreatedAt()              { return createdAt; }
    public void setCreatedAt(Instant v)        { this.createdAt = v; }
    public Instant getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(Instant v)        { this.updatedAt = v; }
    public Long getVersion()                   { return version; }
    public void setVersion(Long v)             { this.version = v; }

    // ── V3: lifecycle beyond resolution ─────────────────────────────────────

    public String getClosedBy()                { return closedBy; }
    public void setClosedBy(String v)          { this.closedBy = v; }
    public Instant getClosedAt()               { return closedAt; }
    public void setClosedAt(Instant v)         { this.closedAt = v; }
    public Integer getReopenCount()            { return reopenCount == null ? 0 : reopenCount; }
    public void setReopenCount(Integer v)      { this.reopenCount = v == null ? 0 : v; }
    public String getLastReopenedBy()          { return lastReopenedBy; }
    public void setLastReopenedBy(String v)    { this.lastReopenedBy = v; }
    public Instant getLastReopenedAt()         { return lastReopenedAt; }
    public void setLastReopenedAt(Instant v)   { this.lastReopenedAt = v; }

    // ── V3: resource identity ───────────────────────────────────────────────

    public String getServiceName()             { return serviceName; }
    public void setServiceName(String v)       { this.serviceName = v; }
    public String getHostName()                { return hostName; }
    public void setHostName(String v)          { this.hostName = v; }
    public String getNamespace()               { return namespace; }
    public void setNamespace(String v)         { this.namespace = v; }
    public String getEnvironment()             { return environment; }
    public void setEnvironment(String v)       { this.environment = v; }
    public String getSource()                  { return source; }
    public void setSource(String v)            { this.source = v; }
    public String getDescription()             { return description; }
    public void setDescription(String v)       { this.description = v; }
    public String getSummary()                 { return summary; }
    public void setSummary(String v)           { this.summary = v; }
    public String getEntities()                { return entities; }
    public void setEntities(String v)          { this.entities = v; }
    public String getTraceId()                 { return traceId; }
    public void setTraceId(String v)           { this.traceId = v; }
}
