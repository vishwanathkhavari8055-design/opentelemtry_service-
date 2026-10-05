package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the enterprise alerts screen.
 *
 * <p>{@link AlertNotificationsResponseDTO} is left exactly as it is — the current
 * UI reads it, and the columns this screen needs (service, host, source, duration,
 * closed-by, reopen count) were not in it. Adding them there would have changed a
 * live contract; adding a second shape does not.</p>
 *
 * <p>Timestamps are ISO-8601 strings, as everywhere else in this service. Where a
 * duration is useful it is given in SECONDS as well as a formatted string, so the
 * table can sort on the number and render the words without parsing them back.</p>
 */
public final class AlertQueryResponseDTO {

    private AlertQueryResponseDTO() {
        // Container for the nested response shapes only.
    }

    /** One row of the enterprise alert table. */
    public static class Row {

        /** Episode id — what "Copy Alert ID" copies, and what bulk actions take. */
        private Long id;

        /** Identity of the alert SERIES across every episode. */
        private String fingerprint;

        private String alertName;

        /**
         * What KIND of problem this is, independent of how bad it currently is.
         *
         * <p>The rule name with a trailing severity token removed, and part of the
         * incident's identity — which is why a condition that escalates from
         * {@code …-warning} to {@code …-disaster} is one row here whose severity
         * changed, rather than two rows that look unrelated.</p>
         *
         * <p>{@code alertName} beside it is the rule that fired MOST RECENTLY, so the
         * two differ exactly when a level-per-rule set is escalating — and seeing both
         * is what tells an operator that is what happened.</p>
         */
        private String incidentType;

        private String severity;
        private String status;

        /** Ingest path: {@code stream} or {@code webhook}. */
        private String source;

        private String serviceName;
        private String hostName;
        private String namespace;
        private String environment;
        private String streamName;
        private String streamType;

        /** Name of the rule that produced it, when this application owns that rule. */
        private String ruleName;
        private String ruleUuid;

        private String triggeredAt;
        private String lastFiredAt;
        private String lastUpdatedAt;

        private long durationSeconds;
        private String duration;

        private int occurrences;
        private int episode;

        /** Firings since somebody acknowledged it; zero unless acknowledged. */
        private int newSinceAck;

        private String acknowledgedBy;
        private String acknowledgedAt;
        private String resolvedBy;
        private String resolvedAt;
        private String closedBy;
        private String closedAt;
        private int reopenCount;

        /** True when resolution was inferred from silence rather than observed. */
        private boolean autoResolved;

        private String note;

        /**
         * The cleaned description: one block per affected entity, separators and
         * template join artifacts removed. Multi-line — render it preformatted, not
         * as a single field.
         */
        private String description;

        /**
         * One line fit for a tooltip or a narrow cell: the first entity, the
         * measurement that tripped, and how many others there are.
         *
         * <p>Separate from {@link #description} because the table and the details
         * panel want opposite things — a hover cannot show 2,400 characters, and a
         * details panel should not hide them.</p>
         */
        private String descriptionHeadline;

        /** How many entities the description describes. */
        private int affectedCount;

        private String summary;
        private String entities;
        private String traceId;
        private String alertUrl;

        private Long threshold;
        private Long observedCount;
        private Double aggValue;
        private String operator;
        private Integer periodMinutes;
        private String windowStart;
        private String windowEnd;

        /**
         * How much is actually in use, WITH its unit — "4040 MB", "74 GB".
         *
         * <p>What Current Usage shows. {@code aggValue} is a bare percentage
         * ("78.9"), which says how close to the edge something is but not what is
         * being measured or by how much; this is the absolute figure the template
         * carries beside the ceiling, so the row reads "4040 MB of 5120 MB".</p>
         *
         * <p>Null for alerts that measure something with no absolute magnitude — a
         * restart count, a pod phase — and Current Usage then falls back to
         * {@code aggValue}, which for those IS the whole measurement.</p>
         */
        private String usedLabel;

        /**
         * The ceiling the observed value was measured against, WITH its unit —
         * "2048 MB", "91 GB".
         *
         * <p>A string rather than a number because the unit differs per alert
         * shape: a memory alert's ceiling is megabytes, a disk alert's is
         * gigabytes, and returning bare numbers would put 2048 and 91 in one column
         * as though they were comparable. Null when the alert has no numeric
         * ceiling at all — a pod in {@code Pending} or a restart count has nothing
         * to be a percentage of — and null is the honest answer there, not zero.
         *
         * <p>Read from the stored payload rather than a column, so it is populated
         * for alerts recorded before the column existed. See
         * {@link com.trinity.observability.config.AlertDisplayProperties}.</p>
         */
        private String limitLabel;

        /**
         * The template's own rendering of the measurement — "87.4% (1790.0MB /
         * 2048.0MB)", "state: Pending", "20884 restarts / 5m".
         *
         * <p>The tooltip behind Current Usage and Limit. It is the one measurement
         * field present on every alert shape here, so it is what explains a row
         * whose Limit is legitimately blank.</p>
         */
        private String displayValue;

        /** How severity was decided, so a disputed grade is explainable. */
        private String severitySource;

        /** Set when the payload could not be interpreted; the alert is still listed. */
        private String parseError;

        /**
         * The transitions this row will accept right now — the Actions cell.
         *
         * <p>Lowercase names matching the POST routes: {@code acknowledge},
         * {@code resolve}, {@code close}, {@code reopen}. Derived from
         * {@link AlertState#availableActions}, which is the same rule the write path
         * enforces, so a button that appears here is a button that works.</p>
         *
         * <p>Sent rather than left for the client to infer from {@code status},
         * because every client would then have to re-implement the RESOLVED-vs-CLOSED
         * distinction and one of them would get it wrong.</p>
         */
        private List<String> actions = new ArrayList<>();

        /**
         * Every NUMBER the latest firing carried, keyed by the payload's own field name.
         *
         * <p>Deliberately a map and not a field per metric. The alternative —
         * {@code cpuValue}, {@code ramValue}, {@code diskValue} — is wrong on the day it
         * ships, because the next metric somebody alerts on then needs a schema change,
         * a DTO change and a UI change before it can be seen at all. Here a rule on a
         * metric nobody has thought of yet renders correctly with no code change.</p>
         *
         * <p>{@code aggValue} and {@code threshold} above remain, unchanged: they are
         * the specific pair the rule TESTED, which this map cannot identify among the
         * others. This is the full picture; those two are the part that tripped.</p>
         */
        private Map<String, Double> currentValues = new LinkedHashMap<>();

        /**
         * Everything else the latest firing carried that is not a number — the
         * dimensions, tags and labels, again keyed by the payload's own field names.
         *
         * <p>Split from {@link #currentValues} because the two are read differently: a
         * value is compared and trended, a label is matched and grouped. One
         * undifferentiated map would force the client to guess which is which, and it
         * would guess by inspecting key names — the hardcoding this design exists to
         * avoid.</p>
         */
        private Map<String, String> labels = new LinkedHashMap<>();

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public void setFingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        public String getAlertName() {
            return alertName;
        }

        public void setAlertName(String alertName) {
            this.alertName = alertName;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getHostName() {
            return hostName;
        }

        public void setHostName(String hostName) {
            this.hostName = hostName;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public String getStreamName() {
            return streamName;
        }

        public void setStreamName(String streamName) {
            this.streamName = streamName;
        }

        public String getStreamType() {
            return streamType;
        }

        public void setStreamType(String streamType) {
            this.streamType = streamType;
        }

        public String getRuleName() {
            return ruleName;
        }

        public void setRuleName(String ruleName) {
            this.ruleName = ruleName;
        }

        public String getRuleUuid() {
            return ruleUuid;
        }

        public void setRuleUuid(String ruleUuid) {
            this.ruleUuid = ruleUuid;
        }

        public String getTriggeredAt() {
            return triggeredAt;
        }

        public void setTriggeredAt(String triggeredAt) {
            this.triggeredAt = triggeredAt;
        }

        public String getLastFiredAt() {
            return lastFiredAt;
        }

        public void setLastFiredAt(String lastFiredAt) {
            this.lastFiredAt = lastFiredAt;
        }

        public String getLastUpdatedAt() {
            return lastUpdatedAt;
        }

        public void setLastUpdatedAt(String lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        public String getDuration() {
            return duration;
        }

        public void setDuration(String duration) {
            this.duration = duration;
        }

        public int getOccurrences() {
            return occurrences;
        }

        public void setOccurrences(int occurrences) {
            this.occurrences = occurrences;
        }

        public int getEpisode() {
            return episode;
        }

        public void setEpisode(int episode) {
            this.episode = episode;
        }

        public int getNewSinceAck() {
            return newSinceAck;
        }

        public void setNewSinceAck(int newSinceAck) {
            this.newSinceAck = newSinceAck;
        }

        public String getAcknowledgedBy() {
            return acknowledgedBy;
        }

        public void setAcknowledgedBy(String acknowledgedBy) {
            this.acknowledgedBy = acknowledgedBy;
        }

        public String getAcknowledgedAt() {
            return acknowledgedAt;
        }

        public void setAcknowledgedAt(String acknowledgedAt) {
            this.acknowledgedAt = acknowledgedAt;
        }

        public String getResolvedBy() {
            return resolvedBy;
        }

        public void setResolvedBy(String resolvedBy) {
            this.resolvedBy = resolvedBy;
        }

        public String getResolvedAt() {
            return resolvedAt;
        }

        public void setResolvedAt(String resolvedAt) {
            this.resolvedAt = resolvedAt;
        }

        public String getClosedBy() {
            return closedBy;
        }

        public void setClosedBy(String closedBy) {
            this.closedBy = closedBy;
        }

        public String getClosedAt() {
            return closedAt;
        }

        public void setClosedAt(String closedAt) {
            this.closedAt = closedAt;
        }

        public int getReopenCount() {
            return reopenCount;
        }

        public void setReopenCount(int reopenCount) {
            this.reopenCount = reopenCount;
        }

        public boolean isAutoResolved() {
            return autoResolved;
        }

        public void setAutoResolved(boolean autoResolved) {
            this.autoResolved = autoResolved;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getDescriptionHeadline() {
            return descriptionHeadline;
        }

        public void setDescriptionHeadline(String descriptionHeadline) {
            this.descriptionHeadline = descriptionHeadline;
        }

        public int getAffectedCount() {
            return affectedCount;
        }

        public void setAffectedCount(int affectedCount) {
            this.affectedCount = affectedCount;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getEntities() {
            return entities;
        }

        public void setEntities(String entities) {
            this.entities = entities;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getAlertUrl() {
            return alertUrl;
        }

        public void setAlertUrl(String alertUrl) {
            this.alertUrl = alertUrl;
        }

        public Long getThreshold() {
            return threshold;
        }

        public void setThreshold(Long threshold) {
            this.threshold = threshold;
        }

        public Long getObservedCount() {
            return observedCount;
        }

        public void setObservedCount(Long observedCount) {
            this.observedCount = observedCount;
        }

        public Double getAggValue() {
            return aggValue;
        }

        public void setAggValue(Double aggValue) {
            this.aggValue = aggValue;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public Integer getPeriodMinutes() {
            return periodMinutes;
        }

        public void setPeriodMinutes(Integer periodMinutes) {
            this.periodMinutes = periodMinutes;
        }

        public String getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(String windowStart) {
            this.windowStart = windowStart;
        }

        public String getWindowEnd() {
            return windowEnd;
        }

        public void setWindowEnd(String windowEnd) {
            this.windowEnd = windowEnd;
        }

        public String getUsedLabel() {
            return usedLabel;
        }

        public void setUsedLabel(String usedLabel) {
            this.usedLabel = usedLabel;
        }

        public String getLimitLabel() {
            return limitLabel;
        }

        public void setLimitLabel(String limitLabel) {
            this.limitLabel = limitLabel;
        }

        public String getDisplayValue() {
            return displayValue;
        }

        public void setDisplayValue(String displayValue) {
            this.displayValue = displayValue;
        }

        public String getSeveritySource() {
            return severitySource;
        }

        public void setSeveritySource(String severitySource) {
            this.severitySource = severitySource;
        }

        public String getParseError() {
            return parseError;
        }

        public void setParseError(String parseError) {
            this.parseError = parseError;
        }

        public List<String> getActions() {
            return actions;
        }

        public void setActions(List<String> actions) {
            this.actions = actions == null ? new ArrayList<>() : actions;
        }

        public String getIncidentType() {
            return incidentType;
        }

        public void setIncidentType(String incidentType) {
            this.incidentType = incidentType;
        }

        public Map<String, Double> getCurrentValues() {
            return currentValues;
        }

        public void setCurrentValues(Map<String, Double> currentValues) {
            this.currentValues = currentValues == null ? new LinkedHashMap<>() : currentValues;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels == null ? new LinkedHashMap<>() : labels;
        }
    }

    /** A page of rows, plus enough context for the pager and the sort headers. */
    public static class Page {

        private List<Row> items = new ArrayList<>();
        private long total;
        @SuppressWarnings("java:S1700") // field name matches JSON contract; renaming would break API
        private int page;
        private int size;
        private boolean hasMore;
        private String org;
        private String sortBy;
        private String sortDir;

        /** ISO bounds of the window the rows come from, so the header can state it. */
        private String windowStart;
        private String windowEnd;

        /**
         * Where these rows came from: {@code stored} (PostgreSQL, the normal path) or
         * {@code live-stream} (read straight out of OpenObserve).
         *
         * <p>Present so the screen can never show one silently in place of the other.
         * Live rows have no id, no episode history and no lifecycle — they are firings,
         * not incidents — and a table that looked identical either way would offer
         * Acknowledge on a row nothing can be acknowledged against.</p>
         */
        private String source = "stored";

        /**
         * True when OpenObserve served only part of the window.
         *
         * <p>Only the live path can set it. A partial answer that reads as complete is
         * how the ingest poller once lost 66 hours; on a screen the same silence would
         * mean "no alerts" when the truth is "some alerts, unknown how many".</p>
         */
        private boolean partial;

        /** True when the scan cap was reached and older firings in the window were cut. */
        private boolean truncated;

        public List<Row> getItems() {
            return items;
        }

        public void setItems(List<Row> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public boolean isHasMore() {
            return hasMore;
        }

        public void setHasMore(boolean hasMore) {
            this.hasMore = hasMore;
        }

        public String getOrg() {
            return org;
        }

        public void setOrg(String org) {
            this.org = org;
        }

        public String getSortBy() {
            return sortBy;
        }

        public void setSortBy(String sortBy) {
            this.sortBy = sortBy;
        }

        public String getSortDir() {
            return sortDir;
        }

        public void setSortDir(String sortDir) {
            this.sortDir = sortDir;
        }

        public String getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(String windowStart) {
            this.windowStart = windowStart;
        }

        public String getWindowEnd() {
            return windowEnd;
        }

        public void setWindowEnd(String windowEnd) {
            this.windowEnd = windowEnd;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public boolean isPartial() {
            return partial;
        }

        public void setPartial(boolean partial) {
            this.partial = partial;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean truncated) {
            this.truncated = truncated;
        }
    }

    /**
     * The summary cards.
     *
     * <p>Windowed by the same filter as the table beneath them. Cards that counted
     * all time while the table showed an hour would disagree with it, and the
     * operator would have no way to tell which one was lying.</p>
     */
    public static class Summary {

        private long open;
        private long acknowledged;
        private long resolved;
        private long closed;

        private long critical;
        private long error;
        private long warning;
        private long info;
        private long disaster;

        /** Open + acknowledged: what still needs somebody. */
        private long needsAttention;

        private String windowStart;
        private String windowEnd;

        public long getOpen() {
            return open;
        }

        public void setOpen(long open) {
            this.open = open;
        }

        public long getAcknowledged() {
            return acknowledged;
        }

        public void setAcknowledged(long acknowledged) {
            this.acknowledged = acknowledged;
        }

        public long getResolved() {
            return resolved;
        }

        public void setResolved(long resolved) {
            this.resolved = resolved;
        }

        public long getClosed() {
            return closed;
        }

        public void setClosed(long closed) {
            this.closed = closed;
        }

        public long getCritical() {
            return critical;
        }

        public void setCritical(long critical) {
            this.critical = critical;
        }

        public long getError() {
            return error;
        }

        public void setError(long error) {
            this.error = error;
        }

        public long getWarning() {
            return warning;
        }

        public void setWarning(long warning) {
            this.warning = warning;
        }

        public long getInfo() {
            return info;
        }

        public void setInfo(long info) {
            this.info = info;
        }

        public long getDisaster() {
            return disaster;
        }

        public void setDisaster(long disaster) {
            this.disaster = disaster;
        }

        public long getNeedsAttention() {
            return needsAttention;
        }

        public void setNeedsAttention(long needsAttention) {
            this.needsAttention = needsAttention;
        }

        public String getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(String windowStart) {
            this.windowStart = windowStart;
        }

        public String getWindowEnd() {
            return windowEnd;
        }

        public void setWindowEnd(String windowEnd) {
            this.windowEnd = windowEnd;
        }
    }

    /** One alert, expanded: the row plus its trail, its rule and its raw event. */
    public static class Detail {

        private Row alert;
        private List<Event> events = new ArrayList<>();
        private Rule rule;

        /** The notification payload as received, for the Raw Event panel. */
        private String rawPayload;

        public Row getAlert() {
            return alert;
        }

        public void setAlert(Row alert) {
            this.alert = alert;
        }

        public List<Event> getEvents() {
            return events;
        }

        public void setEvents(List<Event> events) {
            this.events = events == null ? new ArrayList<>() : events;
        }

        public Rule getRule() {
            return rule;
        }

        public void setRule(Rule rule) {
            this.rule = rule;
        }

        public String getRawPayload() {
            return rawPayload;
        }

        public void setRawPayload(String rawPayload) {
            this.rawPayload = rawPayload;
        }
    }

    /** One entry in an alert's audit trail. */
    public static class Event {

        private String type;
        private String occurredAt;
        private String actor;
        private String note;
        private Long observedCount;
        private Double aggValue;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getOccurredAt() {
            return occurredAt;
        }

        public void setOccurredAt(String occurredAt) {
            this.occurredAt = occurredAt;
        }

        public String getActor() {
            return actor;
        }

        public void setActor(String actor) {
            this.actor = actor;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Long getObservedCount() {
            return observedCount;
        }

        public void setObservedCount(Long observedCount) {
            this.observedCount = observedCount;
        }

        public Double getAggValue() {
            return aggValue;
        }

        public void setAggValue(Double aggValue) {
            this.aggValue = aggValue;
        }
    }

    /** The rule behind an alert, when this application owns it. */
    public static class Rule {

        private String uuid;
        private String name;
        private String description;
        private String severity;
        private boolean enabled;
        private String streamType;
        private String streamName;
        private Integer periodMinutes;
        private String operator;
        private Long threshold;

        /** The condition, as JSON text, for the Rule Information panel. */
        private String queryCondition;
        private String triggerCondition;
        private String syncStatus;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStreamType() {
            return streamType;
        }

        public void setStreamType(String streamType) {
            this.streamType = streamType;
        }

        public String getStreamName() {
            return streamName;
        }

        public void setStreamName(String streamName) {
            this.streamName = streamName;
        }

        public Integer getPeriodMinutes() {
            return periodMinutes;
        }

        public void setPeriodMinutes(Integer periodMinutes) {
            this.periodMinutes = periodMinutes;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public Long getThreshold() {
            return threshold;
        }

        public void setThreshold(Long threshold) {
            this.threshold = threshold;
        }

        public String getQueryCondition() {
            return queryCondition;
        }

        public void setQueryCondition(String queryCondition) {
            this.queryCondition = queryCondition;
        }

        public String getTriggerCondition() {
            return triggerCondition;
        }

        public void setTriggerCondition(String triggerCondition) {
            this.triggerCondition = triggerCondition;
        }

        public String getSyncStatus() {
            return syncStatus;
        }

        public void setSyncStatus(String syncStatus) {
            this.syncStatus = syncStatus;
        }
    }

    /**
     * Distinct values for the filter dropdowns.
     *
     * <p>Derived from what is actually stored rather than from a fixed list, so a
     * Service filter never offers a service that has never alerted — an empty
     * result from a dropdown the UI itself populated reads as a bug.</p>
     */
    public static class FilterOptions {

        private List<String> severities = new ArrayList<>();
        private List<String> statuses = new ArrayList<>();
        private List<String> sources = new ArrayList<>();
        private List<String> services = new ArrayList<>();
        private List<String> streams = new ArrayList<>();
        private List<Map<String, String>> rules = new ArrayList<>();

        public List<String> getSeverities() {
            return severities;
        }

        public void setSeverities(List<String> severities) {
            this.severities = severities;
        }

        public List<String> getStatuses() {
            return statuses;
        }

        public void setStatuses(List<String> statuses) {
            this.statuses = statuses;
        }

        public List<String> getSources() {
            return sources;
        }

        public void setSources(List<String> sources) {
            this.sources = sources;
        }

        public List<String> getServices() {
            return services;
        }

        public void setServices(List<String> services) {
            this.services = services;
        }

        public List<String> getStreams() {
            return streams;
        }

        public void setStreams(List<String> streams) {
            this.streams = streams;
        }

        public List<Map<String, String>> getRules() {
            return rules;
        }

        public void setRules(List<Map<String, String>> rules) {
            this.rules = rules;
        }

        /** Adds a rule option as {@code {uuid, name}}. */
        public void addRule(String uuid, String name) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("uuid", uuid);
            entry.put("name", name);
            rules.add(entry);
        }
    }

    /** Result of one lifecycle transition. */
    public static class WriteResult {

        private boolean ok = true;
        private Long id;
        private String status;
        private String message;
        private Row alert;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Row getAlert() {
            return alert;
        }

        public void setAlert(Row alert) {
            this.alert = alert;
        }
    }

    /**
     * Result of a bulk transition.
     *
     * <p>Reports per-id outcomes rather than a single boolean. A bulk acknowledge
     * over forty alerts where three were already resolved is a partial success, and
     * collapsing that to "failed" would make the operator redo the thirty-seven
     * that worked.</p>
     */
    public static class BulkResult {

        private boolean ok = true;
        private int requested;
        private int changed;
        private int skipped;
        private List<Map<String, String>> outcomes = new ArrayList<>();
        private String message;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public int getRequested() {
            return requested;
        }

        public void setRequested(int requested) {
            this.requested = requested;
        }

        public int getChanged() {
            return changed;
        }

        public void setChanged(int changed) {
            this.changed = changed;
        }

        public int getSkipped() {
            return skipped;
        }

        public void setSkipped(int skipped) {
            this.skipped = skipped;
        }

        public List<Map<String, String>> getOutcomes() {
            return outcomes;
        }

        public void setOutcomes(List<Map<String, String>> outcomes) {
            this.outcomes = outcomes;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        /** Records what happened to one id. */
        public void addOutcome(Long id, String outcome, String detail) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", String.valueOf(id));
            entry.put("outcome", outcome);
            if (detail != null) {
                entry.put("detail", detail);
            }
            outcomes.add(entry);
        }
    }
}
