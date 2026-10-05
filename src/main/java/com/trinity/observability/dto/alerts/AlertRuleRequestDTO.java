package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inputs for creating and updating an alert rule.
 *
 * <p>Validated at the controller boundary rather than in the service. A rule
 * that reaches PostgreSQL malformed becomes a rule that fails every subsequent
 * sync to OpenObserve, and the failure surfaces far from its cause — so the
 * cheapest place to reject it is before it is stored.</p>
 */
public class AlertRuleRequestDTO {

    private AlertRuleRequestDTO() { }

    /** Body of POST /api/alert-rules and PUT /api/alert-rules/{uuid}. */
    public static class SaveRule {

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        // Lands in an OpenObserve URL path on some operations, so the character
        // set is constrained here rather than escaped at each call site.
        @Pattern(regexp = "^\\w[\\w\\-. ]*$",
                 message = "name may contain letters, digits, spaces, '_', '-' and '.', and must not start with punctuation")
        private String name;

        @Size(max = 4000, message = "description must be at most 4000 characters")
        private String description;

        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "folderId must match ^[A-Za-z0-9_-]{1,64}$")
        private String folderId;

        @NotBlank(message = "streamType is required")
        @Pattern(regexp = "logs|metrics|traces", message = "streamType must be logs, metrics or traces")
        private String streamType;

        @NotBlank(message = "streamName is required")
        @Size(max = 255)
        private String streamName;

        @Pattern(regexp = "scheduled|realtime", message = "alertType must be scheduled or realtime")
        private String alertType;

        @Pattern(regexp = "disaster|critical|error|warning|info",
                 message = "severity must be disaster, critical, error, warning or info")
        private String severity;

        /**
         * Optional value-based severity. When set, the observed value at firing
         * time decides the grade and this overrides {@link #severity}, which
         * then only applies if no value is available to grade.
         *
         * <p>Omit it entirely to keep the static label — which is what every
         * rule created before this feature does.</p>
         */
        @Valid
        private SeverityThresholds severityThresholds;

        private Boolean enabled;

        /**
         * When the rule should start acting: {@code NOW}, {@code LATER} or
         * {@code SCHEDULED}.
         *
         * <p>This is the "enable now or later" choice in the editor. It is
         * separate from {@link #enabled} because that boolean cannot carry the
         * reason: a rule staged until Monday and a rule switched off at 3am for
         * misfiring are both {@code enabled=false}, and only one of them should
         * be turned back on by the next person who looks at the list.</p>
         *
         * <p>Omit it and nothing changes — the rule behaves exactly as it did
         * before this field existed, taking its state from {@link #enabled}
         * alone. That is what keeps every current caller working.</p>
         *
         * <ul>
         *   <li>{@code NOW} — live as soon as it is saved (the default).</li>
         *   <li>{@code LATER} — stored and NOT evaluating; somebody enables it
         *       by hand when they are ready. No timer will do it.</li>
         *   <li>{@code SCHEDULED} — stored and not evaluating until
         *       {@link #activateAt} passes, at which point it turns itself on.</li>
         * </ul>
         */
        @Pattern(regexp = "(?i)NOW|LATER|SCHEDULED",
                 message = "activation must be NOW, LATER or SCHEDULED")
        private String activation;

        /**
         * When a {@code SCHEDULED} rule goes live, ISO-8601 WITH an offset —
         * {@code 2026-09-22T09:00:00Z} or {@code 2026-09-22T14:30:00+05:30}.
         *
         * <p>An offset is required rather than assumed. "Monday at 9" typed by
         * somebody in IST and read as UTC starts the rule five and a half hours
         * late, and nothing about the stored row would show that it had.</p>
         *
         * <p>Required when {@code activation=SCHEDULED}, ignored otherwise.</p>
         */
        private String activateAt;

        /**
         * OpenObserve's {@code query_condition}, verbatim.
         *
         * <p>Passed through as JSON rather than modelled field-by-field: the
         * condition tree is recursive with untagged variants and OpenObserve
         * keeps extending it, so anything this service failed to model would be
         * dropped on the next save.</p>
         */
        @NotNull(message = "queryCondition is required")
        private JsonNode queryCondition;

        /** OpenObserve's {@code trigger_condition} (period, threshold, operator, silence). */
        @NotNull(message = "triggerCondition is required")
        private JsonNode triggerCondition;

        /**
         * Destination names in OpenObserve to notify.
         *
         * <p>Empty is allowed and is not an error: a rule can legitimately exist
         * disabled or notify nothing while being tuned. It will still appear in
         * the UI feed if it routes through the webhook destination.</p>
         */
        private List<String> destinations = new ArrayList<>();

        private JsonNode contextAttributes;

        /** Who is making the change. Recorded for audit; not an authorisation claim. */
        @Size(max = 128)
        private String actor;

        public String getName()                     { return name; }
        public void setName(String v)               { this.name = v; }
        public String getDescription()              { return description; }
        public void setDescription(String v)        { this.description = v; }
        public String getFolderId()                 { return folderId; }
        public void setFolderId(String v)           { this.folderId = v; }
        public String getStreamType()               { return streamType; }
        public void setStreamType(String v)         { this.streamType = v; }
        public String getStreamName()               { return streamName; }
        public void setStreamName(String v)         { this.streamName = v; }
        public String getAlertType()                { return alertType; }
        public void setAlertType(String v)          { this.alertType = v; }
        public String getSeverity()                 { return severity; }
        public void setSeverity(String v)           { this.severity = v; }
        public Boolean getEnabled()                 { return enabled; }
        public void setEnabled(Boolean v)           { this.enabled = v; }
        public String getActivation()               { return activation; }
        public void setActivation(String v)         { this.activation = v; }
        public String getActivateAt()               { return activateAt; }
        public void setActivateAt(String v)         { this.activateAt = v; }
        public JsonNode getQueryCondition()         { return queryCondition; }
        public void setQueryCondition(JsonNode v)   { this.queryCondition = v; }
        public JsonNode getTriggerCondition()       { return triggerCondition; }
        public void setTriggerCondition(JsonNode v) { this.triggerCondition = v; }
        public List<String> getDestinations()       { return destinations; }
        public void setDestinations(List<String> v) { this.destinations = v; }
        public JsonNode getContextAttributes()      { return contextAttributes; }
        public void setContextAttributes(JsonNode v){ this.contextAttributes = v; }
        public String getActor()                    { return actor; }
        public void setActor(String v)              { this.actor = v; }
        public SeverityThresholds getSeverityThresholds()       { return severityThresholds; }
        public void setSeverityThresholds(SeverityThresholds v) { this.severityThresholds = v; }
    }

    /**
     * Inclusive lower bound of each severity band.
     *
     * <p>Expresses the ladder operators usually write as a template:</p>
     *
     * <pre>{"disaster": 90, "critical": 80, "warning": 70}</pre>
     *
     * <p>gives disaster at >=90, critical at >=80, warning at >=70 and info
     * below that. Any tier may be omitted to skip it.</p>
     *
     * <p><b>These are on the metric's own scale.</b> 90/80/70 suits a 0..100
     * percentage; a metric reporting a 0..1 fraction (such as
     * {@code process_cpu_usage}) needs 0.9/0.8/0.7 instead, or every firing
     * grades as info.</p>
     */
    public static class SeverityThresholds {
        private Double disaster;
        private Double critical;
        private Double warning;

        public Double getDisaster()        { return disaster; }
        public void setDisaster(Double v)  { this.disaster = v; }
        public Double getCritical()        { return critical; }
        public void setCritical(Double v)  { this.critical = v; }
        public Double getWarning()         { return warning; }
        public void setWarning(Double v)   { this.warning = v; }
    }

    /** Filters for GET /api/alert-rules. All optional. */
    public static class ListFilter {
        private Boolean enabled;
        private String streamType;
        private String severity;
        private String syncStatus;
        private String search;
        private Integer page;
        private Integer size;

        public Boolean getEnabled()          { return enabled; }
        public void setEnabled(Boolean v)    { this.enabled = v; }
        public String getStreamType()        { return streamType; }
        public void setStreamType(String v)  { this.streamType = v; }
        public String getSeverity()          { return severity; }
        public void setSeverity(String v)    { this.severity = v; }
        public String getSyncStatus()        { return syncStatus; }
        public void setSyncStatus(String v)  { this.syncStatus = v; }
        public String getSearch()            { return search; }
        public void setSearch(String v)      { this.search = v; }
        public Integer getPage()             { return page; }
        public void setPage(Integer v)       { this.page = v; }
        public Integer getSize()             { return size; }
        public void setSize(Integer v)       { this.size = v; }
    }
}
