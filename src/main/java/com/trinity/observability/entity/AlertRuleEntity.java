package com.trinity.observability.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * An alert rule, as this service defines it.
 *
 * <p>This row is the definition. OpenObserve holds a copy that it evaluates,
 * addressed by {@link #openObserveId}; that copy is derived and disposable. If
 * OpenObserve loses everything, {@code POST /api/alert-rules/sync} rebuilds it
 * from these rows.</p>
 *
 * <p>The condition trees are stored as JSON strings rather than being shredded
 * into columns — see the note in {@code V1__alert_rules_and_alerts.sql}. They
 * are handled as text here and only parsed when a document is assembled for
 * OpenObserve, so an unrecognised field survives the round trip untouched.</p>
 */
@Entity
@Table(name = "alert_rules")
public class AlertRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The identifier clients use.
     *
     * <p>Exposed instead of {@link #id} so a rule keeps its identity across a
     * dump/restore into fresh sequences, and so an external reference cannot be
     * guessed by counting.</p>
     */
    @Column(name = "uuid", nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "org", nullable = false, length = 64)
    private String org;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "folder_id", nullable = false, length = 64)
    private String folderId = "default";

    @Column(name = "stream_type", nullable = false, length = 32)
    private String streamType;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    @Column(name = "alert_type", nullable = false, length = 32)
    private String alertType = "scheduled";

    @Column(name = "severity", nullable = false, length = 16)
    private String severity = "warning";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Why the rule sits at its current {@link #enabled} value.
     *
     * <p>{@code enabled} alone cannot distinguish a rule staged on purpose from
     * one switched off because it was misfiring, and the two want opposite
     * treatment — so the intent is recorded rather than guessed at. Defaults to
     * {@link ActivationMode#NOW}, which is what every rule written before this
     * existed already is.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "activation_mode", nullable = false, length = 16)
    private ActivationMode activationMode = ActivationMode.NOW;

    /**
     * When a {@link ActivationMode#SCHEDULED} rule turns itself on. Null in
     * every other mode, and required in that one — a SCHEDULED rule with no time
     * would wait forever while looking perfectly healthy.
     */
    @Column(name = "activate_at")
    private Instant activateAt;

    /** When the rule actually went live, by timer or by hand. Null while staged. */
    @Column(name = "activated_at")
    private Instant activatedAt;

    /**
     * Inclusive lower bound of each severity band, graded against the observed
     * value at firing time. All null (the default) means the ladder is off and
     * {@link #severity} stands as a static label — which is how every rule
     * created before this feature behaves.
     *
     * <p>Per rule rather than global because the metrics are on different
     * scales; see {@link com.trinity.observability.util.SeverityLadder}.</p>
     */
    @Column(name = "severity_disaster_at")
    private Double severityDisasterAt;

    @Column(name = "severity_critical_at")
    private Double severityCriticalAt;

    @Column(name = "severity_warning_at")
    private Double severityWarningAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_condition", nullable = false, columnDefinition = "jsonb")
    private String queryCondition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_condition", nullable = false, columnDefinition = "jsonb")
    private String triggerCondition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "destinations", nullable = false, columnDefinition = "jsonb")
    private String destinations = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_attributes", columnDefinition = "jsonb")
    private String contextAttributes;

    /**
     * The last known FULL OpenObserve document for this rule, stored verbatim.
     *
     * <p>The columns above model what this service reasons about. An OpenObserve
     * alert carries more than that — {@code row_template}, {@code priority},
     * {@code tags}, {@code deduplication}, {@code creates_incident},
     * {@code composite_condition}, {@code anomaly_config} — and the product keeps
     * adding to the list. Rebuilding the document from the modelled columns alone
     * drops every one of them, and the save succeeds while doing it: the alert
     * simply loses its priority, its tags, or (for a composite) the expression
     * that is the entire rule.</p>
     *
     * <p>So the push starts from THIS and overlays the modelled columns. Null for
     * rows written before V11, which fall back to the old rebuild — no worse than
     * they were, and repaired the next time the rule is saved or imported.</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_document", columnDefinition = "jsonb")
    private String sourceDocument;

    // ── mapping to the evaluation engine ────────────────────────────────────

    @Column(name = "openobserve_id", length = 64)
    private String openObserveId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 24)
    private SyncStatus syncStatus = SyncStatus.PENDING;

    @Column(name = "sync_error", columnDefinition = "text")
    private String syncError;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    // ── audit ───────────────────────────────────────────────────────────────

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft delete.
     *
     * <p>Not a hard delete, for two reasons: the rule may still exist in
     * OpenObserve and need removing on a later sync, and an audit trail of what
     * used to page people is worth keeping.</p>
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (uuid == null) uuid = UUID.randomUUID();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * True while the rule is deliberately held back rather than simply off.
     *
     * <p>A staged rule is stored, valid and synced to OpenObserve as disabled —
     * so it is NOT evaluating, and the UI has to say why rather than showing it
     * as an ordinary switched-off rule.</p>
     */
    public boolean isStaged() {
        return activationMode != null && activationMode.isStaged() && !enabled;
    }

    /**
     * Marks the rule live, clearing whatever was holding it back.
     *
     * <p>The mode is reset to {@link ActivationMode#NOW} on purpose: once a rule
     * has gone live, "scheduled for Monday" is history, not pending work, and
     * leaving it set would make the activation sweep pick the rule up again
     * every time somebody disabled it by hand.</p>
     */
    public void markActivated(Instant when) {
        this.enabled = true;
        this.activationMode = ActivationMode.NOW;
        this.activateAt = null;
        this.activatedAt = when;
    }

    /** True once this rule has an accepted counterpart in OpenObserve. */
    public boolean isLinked() {
        return openObserveId != null && !openObserveId.isBlank();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Records a successful push, clearing any previous failure. */
    public void markSynced(String openObserveId) {
        this.openObserveId = openObserveId;
        this.syncStatus = SyncStatus.SYNCED;
        this.syncError = null;
        this.lastSyncedAt = Instant.now();
    }

    /**
     * Records a rejected push.
     *
     * <p>{@code openObserveId} is deliberately left as it was: a failed UPDATE
     * does not mean the remote rule stopped existing, and clearing the mapping
     * would orphan it — the next sync would create a second copy and the alert
     * would fire twice.</p>
     */
    public void markSyncFailed(String reason) {
        this.syncStatus = SyncStatus.FAILED;
        this.syncError = reason;
    }

    // ── accessors ───────────────────────────────────────────────────────────

    public Long getId()                       { return id; }
    public void setId(Long v)                 { this.id = v; }
    public UUID getUuid()                     { return uuid; }
    public void setUuid(UUID v)               { this.uuid = v; }
    public String getOrg()                    { return org; }
    public void setOrg(String v)              { this.org = v; }
    public String getName()                   { return name; }
    public void setName(String v)             { this.name = v; }
    public String getDescription()            { return description; }
    public void setDescription(String v)      { this.description = v; }
    public String getFolderId()               { return folderId; }
    public void setFolderId(String v)         { this.folderId = v; }
    public String getStreamType()             { return streamType; }
    public void setStreamType(String v)       { this.streamType = v; }
    public String getStreamName()             { return streamName; }
    public void setStreamName(String v)       { this.streamName = v; }
    public String getAlertType()              { return alertType; }
    public void setAlertType(String v)        { this.alertType = v; }
    public String getSeverity()               { return severity; }
    public void setSeverity(String v)         { this.severity = v; }
    public boolean isEnabled()                { return enabled; }
    public void setEnabled(boolean v)         { this.enabled = v; }
    public ActivationMode getActivationMode()       { return activationMode; }
    public void setActivationMode(ActivationMode v) { this.activationMode = v; }
    public Instant getActivateAt()            { return activateAt; }
    public void setActivateAt(Instant v)      { this.activateAt = v; }
    public Instant getActivatedAt()           { return activatedAt; }
    public void setActivatedAt(Instant v)     { this.activatedAt = v; }
    public String getQueryCondition()         { return queryCondition; }
    public void setQueryCondition(String v)   { this.queryCondition = v; }
    public String getTriggerCondition()       { return triggerCondition; }
    public void setTriggerCondition(String v) { this.triggerCondition = v; }
    public String getDestinations()           { return destinations; }
    public void setDestinations(String v)     { this.destinations = v; }
    public String getContextAttributes()      { return contextAttributes; }
    public void setContextAttributes(String v){ this.contextAttributes = v; }
    public String getOpenObserveId()          { return openObserveId; }
    public String getSourceDocument()             { return sourceDocument; }
    public void   setSourceDocument(String v)     { this.sourceDocument = v; }

    public void setOpenObserveId(String v)    { this.openObserveId = v; }
    public SyncStatus getSyncStatus()         { return syncStatus; }
    public void setSyncStatus(SyncStatus v)   { this.syncStatus = v; }
    public String getSyncError()              { return syncError; }
    public void setSyncError(String v)        { this.syncError = v; }
    public Instant getLastSyncedAt()          { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v)    { this.lastSyncedAt = v; }
    public String getCreatedBy()              { return createdBy; }
    public void setCreatedBy(String v)        { this.createdBy = v; }
    public String getUpdatedBy()              { return updatedBy; }
    public void setUpdatedBy(String v)        { this.updatedBy = v; }
    public Instant getCreatedAt()             { return createdAt; }
    public void setCreatedAt(Instant v)       { this.createdAt = v; }
    public Instant getUpdatedAt()             { return updatedAt; }
    public void setUpdatedAt(Instant v)       { this.updatedAt = v; }
    public Instant getDeletedAt()             { return deletedAt; }
    public void setDeletedAt(Instant v)       { this.deletedAt = v; }
    public Long getVersion()                  { return version; }
    public void setVersion(Long v)            { this.version = v; }

    public Double getSeverityDisasterAt()       { return severityDisasterAt; }
    public void setSeverityDisasterAt(Double v) { this.severityDisasterAt = v; }
    public Double getSeverityCriticalAt()       { return severityCriticalAt; }
    public void setSeverityCriticalAt(Double v) { this.severityCriticalAt = v; }
    public Double getSeverityWarningAt()        { return severityWarningAt; }
    public void setSeverityWarningAt(Double v)  { this.severityWarningAt = v; }
}
