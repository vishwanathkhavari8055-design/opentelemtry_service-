package com.trinity.observability.dto.alerts;



/**
 * One thing that happened to an alert: it fired, someone acknowledged it, or it
 * was closed.
 *
 * <p>This is the normalizer's output — the shape an inbound notification takes
 * once it has been made sense of, before it becomes an {@code AlertEntity} row.
 * It is not persisted directly; {@code AlertNotificationsServiceImpl} maps it
 * onto the entities.</p>
 */
public class AlertEvent {

    // ── identity ────────────────────────────────────────────────────────────

    /**
     * Stable hash of what makes this "the same alert" across firings — see
     * {@link com.trinity.observability.util.AlertPayloadNormalizer#fingerprint}.
     * Two firings of the same rule share it; two different rules never do.
     */
    private String fingerprint;

    private AlertState eventType = AlertState.FIRING;

    private String org;

    /** Epoch MICROSECONDS, matching {@code _timestamp} in every OpenObserve stream. */
    private long timestampMicros;

    // ── what fired ──────────────────────────────────────────────────────────

    private String alertName;

    /**
     * What KIND of problem this is, independent of how bad it currently is.
     *
     * <p>The rule name with a trailing severity token removed — see
     * {@code AlertTypeNormalizer}. Distinct from {@link #alertType}, which is
     * OpenObserve's own notion (realtime vs scheduled) and says nothing about the
     * condition being alerted on.</p>
     *
     * <p>This is the identity component that makes an escalation ONE incident: a
     * deployment expressing severity as one rule per level produces different
     * {@code alertName}s for the same condition, and grouping on those would split
     * a warning that became a disaster into two unrelated rows.</p>
     */
    private String incidentType;

    private String alertType;
    private String streamType;
    private String streamName;
    private String severity;

    /**
     * Whether {@link #severity} came from the payload (or the URL override)
     * rather than being guessed.
     *
     * <p>Needed because the guess and a real value are indistinguishable once
     * stored, and the two must be treated differently: a guessed severity should
     * defer to the severity recorded on the matching rule in PostgreSQL, while an
     * explicit one is the caller's decision and must not be overridden.</p>
     */
    private boolean severityExplicit;

    /** url-override | payload | name-guess | default — refined later by the service. */
    private String severitySource;

    // ── the numbers behind it ───────────────────────────────────────────────

    /** Evaluation window in minutes, as OpenObserve reports it. */
    private Integer period;
    private String operator;
    private Long threshold;
    private Long observedCount;
    private Double aggValue;

    private Long windowStartMicros;
    private Long windowEndMicros;

    /** Deep link back into OpenObserve for the query behind the alert. */
    private String alertUrl;

    // ── provenance ──────────────────────────────────────────────────────────

    /** Who caused this event: {@code "openobserve"}, a username, or {@code "auto:stale"}. */
    private String actor;

    private String note;

    /** Verbatim inbound body, truncated. Kept so an unmodelled template is still forensically useful. */
    private String rawPayload;

    /** Set when the body could not be parsed as JSON; the event is still recorded. */
    private String parseError;

    // ── resource identity and narrative (stream ingest) ─────────────────────
    //
    // Populated by AlertStreamRowMapper from the deployment's own notification
    // template. All optional: which of these a template renders is a per-install
    // fact, not something the model can require.

    private String description;
    private String summary;

    /** Affected entities as the template rendered them, e.g. "kubeworker11 : /". */
    private String entities;

    private String serviceName;
    private String hostName;
    private String namespace;
    private String environment;
    private String traceId;

    /**
     * Hash of the source stream row, for idempotent re-ingest.
     *
     * <p>Null for webhook-era events and for operator actions, which is why the
     * unique index backing it is partial.</p>
     */
    private String eventHash;

    // ── accessors ───────────────────────────────────────────────────────────

    public String getFingerprint()            { return fingerprint; }
    public void setFingerprint(String v)      { this.fingerprint = v; }

    public AlertState getEventType()          { return eventType; }
    public void setEventType(AlertState v)    { this.eventType = v; }

    public String getOrg()                    { return org; }
    public void setOrg(String v)              { this.org = v; }

    public long getTimestampMicros()          { return timestampMicros; }
    public void setTimestampMicros(long v)    { this.timestampMicros = v; }

    public String getAlertName()              { return alertName; }
    public void setAlertName(String v)        { this.alertName = v; }
    public String getIncidentType()           { return incidentType; }
    public void setIncidentType(String v)     { this.incidentType = v; }

    public String getAlertType()              { return alertType; }
    public void setAlertType(String v)        { this.alertType = v; }

    public String getStreamType()             { return streamType; }
    public void setStreamType(String v)       { this.streamType = v; }

    public String getStreamName()             { return streamName; }
    public void setStreamName(String v)       { this.streamName = v; }

    public String getSeverity()               { return severity; }
    public void setSeverity(String v)         { this.severity = v; }

    public boolean isSeverityExplicit()       { return severityExplicit; }
    public void setSeverityExplicit(boolean v){ this.severityExplicit = v; }

    public String getSeveritySource()         { return severitySource; }
    public void setSeveritySource(String v)   { this.severitySource = v; }

    public Integer getPeriod()                { return period; }
    public void setPeriod(Integer v)          { this.period = v; }

    public String getOperator()               { return operator; }
    public void setOperator(String v)         { this.operator = v; }

    public Long getThreshold()                { return threshold; }
    public void setThreshold(Long v)          { this.threshold = v; }

    public Long getObservedCount()            { return observedCount; }
    public void setObservedCount(Long v)      { this.observedCount = v; }

    public Double getAggValue()               { return aggValue; }
    public void setAggValue(Double v)         { this.aggValue = v; }

    public Long getWindowStartMicros()        { return windowStartMicros; }
    public void setWindowStartMicros(Long v)  { this.windowStartMicros = v; }

    public Long getWindowEndMicros()          { return windowEndMicros; }
    public void setWindowEndMicros(Long v)    { this.windowEndMicros = v; }

    public String getAlertUrl()               { return alertUrl; }
    public void setAlertUrl(String v)         { this.alertUrl = v; }

    public String getActor()                  { return actor; }
    public void setActor(String v)            { this.actor = v; }

    public String getNote()                   { return note; }
    public void setNote(String v)             { this.note = v; }

    public String getRawPayload()             { return rawPayload; }
    public void setRawPayload(String v)       { this.rawPayload = v; }

    public String getParseError()             { return parseError; }
    public void setParseError(String v)       { this.parseError = v; }

    public String getDescription()            { return description; }
    public void setDescription(String v)      { this.description = v; }

    public String getSummary()                { return summary; }
    public void setSummary(String v)          { this.summary = v; }

    public String getEntities()               { return entities; }
    public void setEntities(String v)         { this.entities = v; }

    public String getServiceName()            { return serviceName; }
    public void setServiceName(String v)      { this.serviceName = v; }

    public String getHostName()               { return hostName; }
    public void setHostName(String v)         { this.hostName = v; }

    public String getNamespace()              { return namespace; }
    public void setNamespace(String v)        { this.namespace = v; }

    public String getEnvironment()            { return environment; }
    public void setEnvironment(String v)      { this.environment = v; }

    public String getTraceId()                { return traceId; }
    public void setTraceId(String v)          { this.traceId = v; }

    public String getEventHash()              { return eventHash; }
    public void setEventHash(String v)        { this.eventHash = v; }
}
