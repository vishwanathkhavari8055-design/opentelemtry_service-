package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Shapes returned by the alert-rule API. */
public class AlertRuleResponseDTO {

    private AlertRuleResponseDTO() { }

    /** One rule, as stored here. */
    public static class Rule {
        private String uuid;
        private String org;
        private String name;
        private String description;
        private String folderId;
        private String streamType;
        private String streamName;
        private String alertType;
        private String severity;
        private boolean enabled;

        /** NOW | LATER | SCHEDULED — why the rule sits at its {@code enabled} value. */
        private String activation;

        /** When a SCHEDULED rule goes live, ISO-8601. Null in every other mode. */
        private String activateAt;

        /** When the rule actually went live, by timer or by hand. Null while staged. */
        private String activatedAt;

        /**
         * True when the rule is deliberately held back rather than simply off.
         *
         * <p>Derived, and exposed so the list does not have to re-derive it: a
         * staged rule must not be rendered as an ordinary disabled rule, or
         * somebody will "fix" it by turning it on early.</p>
         */
        private boolean staged;

        /** Value-based bands, or null when the rule uses its static severity. */
        private AlertRuleRequestDTO.SeverityThresholds severityThresholds;

        private JsonNode queryCondition;
        private JsonNode triggerCondition;
        private List<String> destinations = new ArrayList<>();
        private JsonNode contextAttributes;

        /**
         * The rule's id inside OpenObserve, or null when it has never been
         * accepted there. This is the mapping that makes the two stores
         * reconcilable, so it is exposed rather than kept internal.
         */
        private String openObserveId;

        /** PENDING | SYNCED | FAILED | DELETE_PENDING */
        private String syncStatus;

        /** Why the last push was rejected. Null when synced. */
        private String syncError;

        private String lastSyncedAt;
        private String createdBy;
        private String updatedBy;
        private String createdAt;
        private String updatedAt;

        public String getUuid()                       { return uuid; }
        public void setUuid(String v)                 { this.uuid = v; }
        public String getOrg()                        { return org; }
        public void setOrg(String v)                  { this.org = v; }
        public String getName()                       { return name; }
        public void setName(String v)                 { this.name = v; }
        public String getDescription()                { return description; }
        public void setDescription(String v)          { this.description = v; }
        public String getFolderId()                   { return folderId; }
        public void setFolderId(String v)             { this.folderId = v; }
        public String getStreamType()                 { return streamType; }
        public void setStreamType(String v)           { this.streamType = v; }
        public String getStreamName()                 { return streamName; }
        public void setStreamName(String v)           { this.streamName = v; }
        public String getAlertType()                  { return alertType; }
        public void setAlertType(String v)            { this.alertType = v; }
        public String getSeverity()                   { return severity; }
        public void setSeverity(String v)             { this.severity = v; }
        public boolean isEnabled()                    { return enabled; }
        public void setEnabled(boolean v)             { this.enabled = v; }
        public String getActivation()                 { return activation; }
        public void setActivation(String v)           { this.activation = v; }
        public String getActivateAt()                 { return activateAt; }
        public void setActivateAt(String v)           { this.activateAt = v; }
        public String getActivatedAt()                { return activatedAt; }
        public void setActivatedAt(String v)          { this.activatedAt = v; }
        public boolean isStaged()                     { return staged; }
        public void setStaged(boolean v)              { this.staged = v; }
        public AlertRuleRequestDTO.SeverityThresholds getSeverityThresholds() { return severityThresholds; }
        public void setSeverityThresholds(AlertRuleRequestDTO.SeverityThresholds v) { this.severityThresholds = v; }
        public JsonNode getQueryCondition()           { return queryCondition; }
        public void setQueryCondition(JsonNode v)     { this.queryCondition = v; }
        public JsonNode getTriggerCondition()         { return triggerCondition; }
        public void setTriggerCondition(JsonNode v)   { this.triggerCondition = v; }
        public List<String> getDestinations()         { return destinations; }
        public void setDestinations(List<String> v)   { this.destinations = v; }
        public JsonNode getContextAttributes()        { return contextAttributes; }
        public void setContextAttributes(JsonNode v)  { this.contextAttributes = v; }
        public String getOpenObserveId()              { return openObserveId; }
        public void setOpenObserveId(String v)        { this.openObserveId = v; }
        public String getSyncStatus()                 { return syncStatus; }
        public void setSyncStatus(String v)           { this.syncStatus = v; }
        public String getSyncError()                  { return syncError; }
        public void setSyncError(String v)            { this.syncError = v; }
        public String getLastSyncedAt()               { return lastSyncedAt; }
        public void setLastSyncedAt(String v)         { this.lastSyncedAt = v; }
        public String getCreatedBy()                  { return createdBy; }
        public void setCreatedBy(String v)            { this.createdBy = v; }
        public String getUpdatedBy()                  { return updatedBy; }
        public void setUpdatedBy(String v)            { this.updatedBy = v; }
        public String getCreatedAt()                  { return createdAt; }
        public void setCreatedAt(String v)            { this.createdAt = v; }
        public String getUpdatedAt()                  { return updatedAt; }
        public void setUpdatedAt(String v)            { this.updatedAt = v; }
    }

    /** Paged envelope, matching the shape the rest of this API uses. */
    public static class Page<T> {
        private List<T> items = new ArrayList<>();
        private String org;
        private long total;
        private int pageNumber;
        private int size;

        public List<T> getItems()       { return items; }
        public void setItems(List<T> v) { this.items = v; }
        public String getOrg()          { return org; }
        public void setOrg(String v)    { this.org = v; }
        public long getTotal()          { return total; }
        public void setTotal(long v)    { this.total = v; }
        public int getPage()            { return pageNumber; }
        public void setPage(int v)      { this.pageNumber = v; }

        public int getSize()            { return size; }
        public void setSize(int v)      { this.size = v; }
    }

    /**
     * Outcome of a sync run.
     *
     * <p>Per-rule outcomes, not a single pass/fail. One rule OpenObserve rejects
     * must not make a 200-rule sync look like a total failure, and the operator
     * needs to know precisely which one to fix.</p>
     */
    public static class SyncResult {
        private boolean ok;
        private String org;
        private int total;
        private int created;
        private int updated;
        private int deleted;
        private int failed;
        private int skipped;
        private long durationMs;
        private List<RuleOutcome> outcomes = new ArrayList<>();

        public boolean isOk()                        { return ok; }
        public void setOk(boolean v)                 { this.ok = v; }
        public String getOrg()                       { return org; }
        public void setOrg(String v)                 { this.org = v; }
        public int getTotal()                        { return total; }
        public void setTotal(int v)                  { this.total = v; }
        public int getCreated()                      { return created; }
        public void setCreated(int v)                { this.created = v; }
        public int getUpdated()                      { return updated; }
        public void setUpdated(int v)                { this.updated = v; }
        public int getDeleted()                      { return deleted; }
        public void setDeleted(int v)                { this.deleted = v; }
        public int getFailed()                       { return failed; }
        public void setFailed(int v)                 { this.failed = v; }
        public int getSkipped()                      { return skipped; }
        public void setSkipped(int v)                { this.skipped = v; }
        public long getDurationMs()                  { return durationMs; }
        public void setDurationMs(long v)            { this.durationMs = v; }
        public List<RuleOutcome> getOutcomes()       { return outcomes; }
        public void setOutcomes(List<RuleOutcome> v) { this.outcomes = v; }
    }

    /** What happened to one rule during a sync. */
    public static class RuleOutcome {
        private String uuid;
        private String name;
        /** CREATED | UPDATED | DELETED | FAILED | SKIPPED */
        private String action;
        private String openObserveId;
        private String message;

        public String getUuid()                { return uuid; }
        public void setUuid(String v)          { this.uuid = v; }
        public String getName()                { return name; }
        public void setName(String v)          { this.name = v; }
        public String getAction()              { return action; }
        public void setAction(String v)        { this.action = v; }
        public String getOpenObserveId()       { return openObserveId; }
        public void setOpenObserveId(String v) { this.openObserveId = v; }
        public String getMessage()             { return message; }
        public void setMessage(String v)       { this.message = v; }
    }

    /** Result of a single write. */
    public static class WriteResult {
        private boolean ok;
        private String message;
        private Rule rule;

        public boolean isOk()            { return ok; }
        public void setOk(boolean v)     { this.ok = v; }
        public String getMessage()       { return message; }
        public void setMessage(String v) { this.message = v; }
        public Rule getRule()            { return rule; }
        public void setRule(Rule v)      { this.rule = v; }
    }
}
