package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes returned by the alert notification feed — the "what is firing right
 * now" screen, as opposed to {@link AlertsResponseDTO}, which is about the
 * alert definitions that produce it.
 */
public class AlertNotificationsResponseDTO {

    private AlertNotificationsResponseDTO() { }

    /** Same envelope every list route in this service uses. */
    public static class Page<T> {
        private List<T> items = new ArrayList<>();
        private String org;
        private String backend;
        private boolean supported;
        private int total;

        public List<T> getItems()            { return items; }
        public void setItems(List<T> v)      { this.items = v; }
        public String getOrg()               { return org; }
        public void setOrg(String v)         { this.org = v; }
        public String getBackend()           { return backend; }
        public void setBackend(String v)     { this.backend = v; }
        public boolean isSupported()         { return supported; }
        public void setSupported(boolean v)  { this.supported = v; }
        public int getTotal()                { return total; }
        public void setTotal(int v)          { this.total = v; }
    }

    /**
     * One alert as the UI shows it — the current state of a fingerprint, not a
     * single firing.
     *
     * <p>Timestamps are ISO strings for display, and the epoch-microsecond
     * originals are alongside them. Both, because the UI needs to sort and to
     * compute "3 minutes ago" without reparsing, and re-deriving a sort key
     * from a formatted string is where timezone bugs come from.</p>
     */
    public static class Active {
        private String fingerprint;
        private String org;
        private String alertName;
        private String alertType;
        private String streamType;
        private String streamName;
        private String severity;

        /**
         * How that severity was decided — url-override | payload |
         * value-threshold | rule | name-guess | default. Lets the UI show why an
         * alert is graded the way it is instead of asking people to trust it.
         */
        private String severitySource;

        /** firing | acknowledged | resolved */
        private String state;

        private String firstFiredAt;
        private String lastFiredAt;
        private long firstFiredAtMicros;
        private long lastFiredAtMicros;

        /** Total firings in the current episode. */
        private int occurrences;

        /** How many times this alert has fired, cleared, and fired again. */
        private int episodes;

        private String acknowledgedBy;
        private String acknowledgedAt;

        /**
         * Firings received since it was acknowledged.
         *
         * <p>Acknowledging does not silence an alert, so this is how the UI can
         * say "still firing, 4 times since you acknowledged it" instead of
         * showing a stale badge that implies the problem stopped.</p>
         */
        private int newSinceAck;

        private String resolvedBy;
        private String resolvedAt;

        /** True when resolution was inferred from silence rather than declared by a person. */
        private boolean autoResolved;

        private String note;

        // The numbers from the most recent firing.
        private Integer period;
        private String operator;
        private Long threshold;
        private Long observedCount;
        private Double aggValue;
        private String alertUrl;

        /** Set only when the inbound body could not be parsed; surfaces a broken template. */
        private String parseError;

        /**
         * The transitions this alert will accept right now — the Actions cell.
         *
         * <p>Lowercase, matching the POST routes on this controller:
         * {@code acknowledge} and {@code resolve}. {@code close} and {@code reopen}
         * also appear, because {@link AlertState#availableActions} is shared with the
         * enterprise screen and describes the alert rather than one controller's
         * route table; a client of THIS feed should offer only the two it has
         * endpoints for.</p>
         *
         * <p>Additive, so the existing UI is unaffected: it is a new key in the JSON
         * and nothing was renamed or removed.</p>
         */
        private List<String> actions = new ArrayList<>();

        public List<String> getActions()           { return actions; }
        public void setActions(List<String> v)     { this.actions = v == null ? new ArrayList<>() : v; }
        public String getFingerprint()             { return fingerprint; }
        public void setFingerprint(String v)       { this.fingerprint = v; }
        public String getOrg()                     { return org; }
        public void setOrg(String v)               { this.org = v; }
        public String getAlertName()               { return alertName; }
        public void setAlertName(String v)         { this.alertName = v; }
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
        public String getState()                   { return state; }
        public void setState(String v)             { this.state = v; }
        public String getFirstFiredAt()            { return firstFiredAt; }
        public void setFirstFiredAt(String v)      { this.firstFiredAt = v; }
        public String getLastFiredAt()             { return lastFiredAt; }
        public void setLastFiredAt(String v)       { this.lastFiredAt = v; }
        public long getFirstFiredAtMicros()        { return firstFiredAtMicros; }
        public void setFirstFiredAtMicros(long v)  { this.firstFiredAtMicros = v; }
        public long getLastFiredAtMicros()         { return lastFiredAtMicros; }
        public void setLastFiredAtMicros(long v)   { this.lastFiredAtMicros = v; }
        public int getOccurrences()                { return occurrences; }
        public void setOccurrences(int v)          { this.occurrences = v; }
        public int getEpisodes()                   { return episodes; }
        public void setEpisodes(int v)             { this.episodes = v; }
        public String getAcknowledgedBy()          { return acknowledgedBy; }
        public void setAcknowledgedBy(String v)    { this.acknowledgedBy = v; }
        public String getAcknowledgedAt()          { return acknowledgedAt; }
        public void setAcknowledgedAt(String v)    { this.acknowledgedAt = v; }
        public int getNewSinceAck()                { return newSinceAck; }
        public void setNewSinceAck(int v)          { this.newSinceAck = v; }
        public String getResolvedBy()              { return resolvedBy; }
        public void setResolvedBy(String v)        { this.resolvedBy = v; }
        public String getResolvedAt()              { return resolvedAt; }
        public void setResolvedAt(String v)        { this.resolvedAt = v; }
        public boolean isAutoResolved()            { return autoResolved; }
        public void setAutoResolved(boolean v)     { this.autoResolved = v; }
        public String getNote()                    { return note; }
        public void setNote(String v)              { this.note = v; }
        public Integer getPeriod()                 { return period; }
        public void setPeriod(Integer v)           { this.period = v; }
        public String getOperator()                { return operator; }
        public void setOperator(String v)          { this.operator = v; }
        public Long getThreshold()                 { return threshold; }
        public void setThreshold(Long v)           { this.threshold = v; }
        public Long getObservedCount()             { return observedCount; }
        public void setObservedCount(Long v)       { this.observedCount = v; }
        public Double getAggValue()                { return aggValue; }
        public void setAggValue(Double v)          { this.aggValue = v; }
        public String getAlertUrl()                { return alertUrl; }
        public void setAlertUrl(String v)          { this.alertUrl = v; }
        public String getParseError()              { return parseError; }
        public void setParseError(String v)        { this.parseError = v; }
    }

    /** One row of the audit trail — an individual event, not a rolled-up alert. */
    public static class HistoryItem {
        private String fingerprint;
        private String eventType;
        private String alertName;
        private String severity;
        private String streamName;
        private String occurredAt;
        private long occurredAtMicros;
        private String actor;
        private String note;
        private Long observedCount;
        private Double aggValue;

        public String getFingerprint()            { return fingerprint; }
        public void setFingerprint(String v)      { this.fingerprint = v; }
        public String getEventType()              { return eventType; }
        public void setEventType(String v)        { this.eventType = v; }
        public String getAlertName()              { return alertName; }
        public void setAlertName(String v)        { this.alertName = v; }
        public String getSeverity()               { return severity; }
        public void setSeverity(String v)         { this.severity = v; }
        public String getStreamName()             { return streamName; }
        public void setStreamName(String v)       { this.streamName = v; }
        public String getOccurredAt()             { return occurredAt; }
        public void setOccurredAt(String v)       { this.occurredAt = v; }
        public long getOccurredAtMicros()         { return occurredAtMicros; }
        public void setOccurredAtMicros(long v)   { this.occurredAtMicros = v; }
        public String getActor()                  { return actor; }
        public void setActor(String v)            { this.actor = v; }
        public String getNote()                   { return note; }
        public void setNote(String v)             { this.note = v; }
        public Long getObservedCount()            { return observedCount; }
        public void setObservedCount(Long v)      { this.observedCount = v; }
        public Double getAggValue()               { return aggValue; }
        public void setAggValue(Double v)         { this.aggValue = v; }
    }

    /** Counts for the nav badge, so the UI need not fetch the whole list to render one number. */
    public static class Summary {
        private String org;
        private boolean supported;
        private int firing;
        private int acknowledged;
        private int resolved;

        /** Severity → count, over unresolved alerts only. Ordered critical → info. */
        private Map<String, Integer> bySeverity = new LinkedHashMap<>();

        /** What the badge should show: unresolved alerts, acknowledged or not. */
        private int needsAttention;

        public String getOrg()                        { return org; }
        public void setOrg(String v)                  { this.org = v; }
        public boolean isSupported()                  { return supported; }
        public void setSupported(boolean v)           { this.supported = v; }
        public int getFiring()                        { return firing; }
        public void setFiring(int v)                  { this.firing = v; }
        public int getAcknowledged()                  { return acknowledged; }
        public void setAcknowledged(int v)            { this.acknowledged = v; }
        public int getResolved()                      { return resolved; }
        public void setResolved(int v)                { this.resolved = v; }
        public Map<String, Integer> getBySeverity()   { return bySeverity; }
        public void setBySeverity(Map<String, Integer> v) { this.bySeverity = v; }
        public int getNeedsAttention()                { return needsAttention; }
        public void setNeedsAttention(int v)          { this.needsAttention = v; }
    }

    /** What the webhook answers OpenObserve. Kept small — nothing reads it but the logs. */
    public static class Ingested {
        private boolean ok;
        private int accepted;
        private List<String> fingerprints = new ArrayList<>();
        private String message;

        public boolean isOk()                      { return ok; }
        public void setOk(boolean v)               { this.ok = v; }
        public int getAccepted()                   { return accepted; }
        public void setAccepted(int v)             { this.accepted = v; }
        public List<String> getFingerprints()      { return fingerprints; }
        public void setFingerprints(List<String> v){ this.fingerprints = v; }
        public String getMessage()                 { return message; }
        public void setMessage(String v)           { this.message = v; }
    }

    /** Result of acknowledge / resolve. */
    public static class WriteResult {
        private boolean ok;
        private String message;
        private Active alert;

        public boolean isOk()             { return ok; }
        public void setOk(boolean v)      { this.ok = v; }
        public String getMessage()        { return message; }
        public void setMessage(String v)  { this.message = v; }
        public Active getAlert()          { return alert; }
        public void setAlert(Active v)    { this.alert = v; }
    }

    /**
     * Operational read-out of the webhook itself.
     *
     * <p>Deliberately reports whether a secret is set, never what it is.</p>
     */
    public static class WebhookStatus {
        private String org;
        private boolean secretConfigured;
        private boolean publicBaseUrlConfigured;
        private String webhookUrl;
        private String stream;
        private int staleAfterMinutes;
        private boolean allowQueryToken;
        private boolean destinationRegistered;
        private boolean templateRegistered;
        private String destinationName;
        private String templateName;
        private int trackedAlerts;
        private List<String> warnings = new ArrayList<>();

        public String getOrg()                          { return org; }
        public void setOrg(String v)                    { this.org = v; }
        public boolean isSecretConfigured()             { return secretConfigured; }
        public void setSecretConfigured(boolean v)      { this.secretConfigured = v; }
        public boolean isPublicBaseUrlConfigured()      { return publicBaseUrlConfigured; }
        public void setPublicBaseUrlConfigured(boolean v) { this.publicBaseUrlConfigured = v; }
        public String getWebhookUrl()                   { return webhookUrl; }
        public void setWebhookUrl(String v)             { this.webhookUrl = v; }
        public String getStream()                       { return stream; }
        public void setStream(String v)                 { this.stream = v; }
        public int getStaleAfterMinutes()               { return staleAfterMinutes; }
        public void setStaleAfterMinutes(int v)         { this.staleAfterMinutes = v; }
        public boolean isAllowQueryToken()              { return allowQueryToken; }
        public void setAllowQueryToken(boolean v)       { this.allowQueryToken = v; }
        public boolean isDestinationRegistered()        { return destinationRegistered; }
        public void setDestinationRegistered(boolean v) { this.destinationRegistered = v; }
        public boolean isTemplateRegistered()           { return templateRegistered; }
        public void setTemplateRegistered(boolean v)    { this.templateRegistered = v; }
        public String getDestinationName()              { return destinationName; }
        public void setDestinationName(String v)        { this.destinationName = v; }
        public String getTemplateName()                 { return templateName; }
        public void setTemplateName(String v)           { this.templateName = v; }
        public int getTrackedAlerts()                   { return trackedAlerts; }
        public void setTrackedAlerts(int v)             { this.trackedAlerts = v; }
        public List<String> getWarnings()               { return warnings; }
        public void setWarnings(List<String> v)         { this.warnings = v; }
    }

    /** Outcome of registering the template + destination in OpenObserve. */
    public static class RegisterResult {
        private boolean ok;
        private String message;
        private String templateName;
        private String destinationName;
        private String webhookUrl;

        public boolean isOk()                    { return ok; }
        public void setOk(boolean v)             { this.ok = v; }
        public String getMessage()               { return message; }
        public void setMessage(String v)         { this.message = v; }
        public String getTemplateName()          { return templateName; }
        public void setTemplateName(String v)    { this.templateName = v; }
        public String getDestinationName()       { return destinationName; }
        public void setDestinationName(String v) { this.destinationName = v; }
        public String getWebhookUrl()            { return webhookUrl; }
        public void setWebhookUrl(String v)      { this.webhookUrl = v; }
    }
}
