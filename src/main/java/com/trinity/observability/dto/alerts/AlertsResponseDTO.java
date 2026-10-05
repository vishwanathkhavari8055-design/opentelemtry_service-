package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Responses for the Alerts screen: the alert list, folders, destinations and
 * templates.
 *
 * <p>One file with several nested shapes, following the same convention as
 * {@link com.trinity.observability.dto.iam.IamResponseDTO} — they are only ever
 * used together by one screen.</p>
 *
 * <h2>Why the list is typed but a single alert is not</h2>
 *
 * <p>{@link ListItem} is a real DTO because the table renders fixed columns and
 * the read path can safely narrow to them.</p>
 *
 * <p>A SINGLE alert, by contrast, is carried as a raw {@link JsonNode} (see
 * {@link Detail}). An alert document is recursive (condition groups nest
 * arbitrarily), several of its fields are untagged enums, and OpenObserve keeps
 * adding to it — {@code anomaly_config}, {@code deduplication} and
 * {@code creates_incident} all post-date this integration. Narrowing it to a DTO
 * would mean any field this service does not model is dropped the next time the
 * alert is saved, so an alert authored in OpenObserve's own UI would come back
 * subtly different after being edited here. That is a data-loss bug that no test
 * on this side would catch, so the document is passed through verbatim in both
 * directions and the UI merges its edits into it.</p>
 */
public final class AlertsResponseDTO {

    private AlertsResponseDTO() {}

    /**
     * One row of the alert list.
     *
     * <p>Field set is the union of the columns across the three list tabs: All
     * and Scheduled show the evaluation window and cadence, Real time does not
     * (a realtime alert has neither), and the anomaly-detection fields are only
     * populated for that alert type. Null means "not applicable to this row",
     * which is exactly what the UI renders as an em dash.</p>
     */
    public static class ListItem {
        private String alertId;
        private String name;
        private String description;
        private String owner;
        private String folderId;
        private String folderName;
        /** "scheduled" | "realtime" | "anomaly_detection". */
        private String alertType;
        private boolean enabled;
        private boolean realTime;
        private String streamType;
        private String streamName;

        /** Evaluation window in MINUTES — the "Look back window" column. */
        private Integer period;
        /** Evaluation cadence in MINUTES — the "Check every" column. */
        private Integer frequency;
        /** "minutes" | "cron"; a cron alert reports its expression instead. */
        private String frequencyType;
        private String cron;
        private Integer silence;
        private Integer threshold;
        private String operator;

        /** ISO-8601, converted from OpenObserve's epoch MICROSECONDS. */
        private String lastTriggeredAt;
        private String lastSatisfiedAt;
        /** Anomaly-detection only; null for the other two types. */
        private String lastTrainedAt;
        private String status;
        private String lastError;

        public String getAlertId()                 { return alertId; }
        public void   setAlertId(String v)         { this.alertId = v; }

        public String getName()                    { return name; }
        public void   setName(String v)            { this.name = v; }

        public String getDescription()             { return description; }
        public void   setDescription(String v)     { this.description = v; }

        public String getOwner()                   { return owner; }
        public void   setOwner(String v)           { this.owner = v; }

        public String getFolderId()                { return folderId; }
        public void   setFolderId(String v)        { this.folderId = v; }

        public String getFolderName()              { return folderName; }
        public void   setFolderName(String v)      { this.folderName = v; }

        public String getAlertType()               { return alertType; }
        public void   setAlertType(String v)       { this.alertType = v; }

        public boolean isEnabled()                 { return enabled; }
        public void    setEnabled(boolean v)       { this.enabled = v; }

        public boolean isRealTime()                { return realTime; }
        public void    setRealTime(boolean v)      { this.realTime = v; }

        public String getStreamType()              { return streamType; }
        public void   setStreamType(String v)      { this.streamType = v; }

        public String getStreamName()              { return streamName; }
        public void   setStreamName(String v)      { this.streamName = v; }

        public Integer getPeriod()                 { return period; }
        public void    setPeriod(Integer v)        { this.period = v; }

        public Integer getFrequency()              { return frequency; }
        public void    setFrequency(Integer v)     { this.frequency = v; }

        public String getFrequencyType()           { return frequencyType; }
        public void   setFrequencyType(String v)   { this.frequencyType = v; }

        public String getCron()                    { return cron; }
        public void   setCron(String v)            { this.cron = v; }

        public Integer getSilence()                { return silence; }
        public void    setSilence(Integer v)       { this.silence = v; }

        public Integer getThreshold()              { return threshold; }
        public void    setThreshold(Integer v)     { this.threshold = v; }

        public String getOperator()                { return operator; }
        public void   setOperator(String v)        { this.operator = v; }

        public String getLastTriggeredAt()         { return lastTriggeredAt; }
        public void   setLastTriggeredAt(String v) { this.lastTriggeredAt = v; }

        public String getLastSatisfiedAt()         { return lastSatisfiedAt; }
        public void   setLastSatisfiedAt(String v) { this.lastSatisfiedAt = v; }

        public String getLastTrainedAt()           { return lastTrainedAt; }
        public void   setLastTrainedAt(String v)   { this.lastTrainedAt = v; }

        public String getStatus()                  { return status; }
        public void   setStatus(String v)          { this.status = v; }

        public String getLastError()               { return lastError; }
        public void   setLastError(String v)       { this.lastError = v; }
    }

    /**
     * A single alert, carried verbatim.
     *
     * <p>{@code alert} is OpenObserve's own document. See the class note on why
     * it is deliberately not narrowed to typed fields.</p>
     */
    public static class Detail {
        private JsonNode alert;
        private String alertId;
        private String folderId;
        private String org;
        private boolean supported;

        public JsonNode getAlert()             { return alert; }
        public void     setAlert(JsonNode v)   { this.alert = v; }

        public String getAlertId()             { return alertId; }
        public void   setAlertId(String v)     { this.alertId = v; }

        public String getFolderId()            { return folderId; }
        public void   setFolderId(String v)    { this.folderId = v; }

        public String getOrg()                 { return org; }
        public void   setOrg(String v)         { this.org = v; }

        public boolean isSupported()           { return supported; }
        public void    setSupported(boolean v) { this.supported = v; }
    }

    /** One entry in the folders rail. */
    public static class Folder {
        private String folderId;
        private String name;
        private String description;

        public String getFolderId()             { return folderId; }
        public void   setFolderId(String v)     { this.folderId = v; }

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }

        public String getDescription()          { return description; }
        public void   setDescription(String v)  { this.description = v; }
    }

    /** One selectable notification destination. */
    public static class Destination {
        private String name;
        /** "email" | "http" | "sns" | "action". */
        private String type;
        private String url;
        private String method;
        private String template;
        private List<String> emails;

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }

        public String getType()                 { return type; }
        public void   setType(String v)         { this.type = v; }

        public String getUrl()                  { return url; }
        public void   setUrl(String v)          { this.url = v; }

        public String getMethod()               { return method; }
        public void   setMethod(String v)       { this.method = v; }

        public String getTemplate()             { return template; }
        public void   setTemplate(String v)     { this.template = v; }

        public List<String> getEmails()             { return emails; }
        public void         setEmails(List<String> v) { this.emails = v; }
    }

    /** One notification template. */
    public static class Template {
        private String name;
        private String type;
        private String title;
        private Boolean isDefault;

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }

        public String getType()                 { return type; }
        public void   setType(String v)         { this.type = v; }

        public String getTitle()                { return title; }
        public void   setTitle(String v)        { this.title = v; }

        public Boolean getIsDefault()           { return isDefault; }
        public void    setIsDefault(Boolean v)  { this.isDefault = v; }
    }

    /**
     * One column on a stream, with enough type information to filter by it.
     *
     * <p>{@code /streams/{name}/fields} answers with bare names, which is all the
     * condition builder needs — a filter compares whatever the column holds. An
     * AGGREGATION cannot: {@code avg} of a {@code Utf8} column is not a question
     * with an answer, and OpenObserve's own alert UI offers only the numeric
     * columns in its "avg of …" picker. That distinction is what this carries.</p>
     *
     * <p>{@code type} is Arrow's own spelling as OpenObserve reports it —
     * {@code Int64}, {@code Float64}, {@code Utf8}, {@code Boolean} — passed
     * through verbatim so the UI can show it; {@code numeric} is the derived
     * answer, computed here so every caller agrees on what counts.</p>
     */
    public static class StreamField {
        private String name;
        private String type;
        private boolean numeric;

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }

        public String getType()                 { return type; }
        public void   setType(String v)         { this.type = v; }

        public boolean isNumeric()              { return numeric; }
        public void    setNumeric(boolean v)    { this.numeric = v; }
    }

    /**
     * One child alert referenced by a composite expression, as OpenObserve
     * resolved it.
     *
     * <p>Mirrors its {@code CompositeChildResponse}. The state fields are what
     * make the expression editor useful rather than decorative: an expression
     * over five alerts is unreadable until you can see which of them are
     * currently true, and {@code accessible} / {@code enabled} name the two ways
     * a reference can be syntactically fine and still never fire.</p>
     */
    public static class CompositeChild {
        private String alertId;
        private String name;
        private String alertType;
        private String folderId;
        private Boolean accessible;
        private Boolean enabled;
        /** Current severity of the child: "critical", "warning", or null. */
        private String level;
        /** How the child evaluates right now under the composite's truth policy. */
        private Boolean truth;
        /** True when the child has not evaluated inside its freshness deadline. */
        private Boolean stale;

        public String getAlertId()              { return alertId; }
        public void   setAlertId(String v)      { this.alertId = v; }

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }

        public String getAlertType()            { return alertType; }
        public void   setAlertType(String v)    { this.alertType = v; }

        public String getFolderId()             { return folderId; }
        public void   setFolderId(String v)     { this.folderId = v; }

        public Boolean getAccessible()          { return accessible; }
        public void    setAccessible(Boolean v) { this.accessible = v; }

        public Boolean getEnabled()             { return enabled; }
        public void    setEnabled(Boolean v)    { this.enabled = v; }

        public String getLevel()                { return level; }
        public void   setLevel(String v)        { this.level = v; }

        public Boolean getTruth()               { return truth; }
        public void    setTruth(Boolean v)      { this.truth = v; }

        public Boolean getStale()               { return stale; }
        public void    setStale(Boolean v)      { this.stale = v; }
    }

    /**
     * Whether a composite expression is usable, and what it currently evaluates to.
     *
     * <p>A composite alert never re-queries any stream — it combines the CURRENT
     * STATES of other alerts — so "is this expression valid" is a question only
     * OpenObserve can answer: it has to resolve every referenced alert, check the
     * caller may read it, and check the reference graph stays acyclic. This
     * carries that answer back verbatim.</p>
     *
     * <p>A rejection is NOT modelled as an error. OpenObserve answers an
     * unusable expression with HTTP 400 and a {@code {code, message}} body, and
     * that body is the thing the editor has to show — turning it into a 500 would
     * leave the user with an expression the UI calls broken for no stated
     * reason. So {@code valid: false} plus the message is a successful response.</p>
     */
    public static class CompositeValidation {
        private boolean valid;
        private boolean supported;
        /** OpenObserve's normalised form of the expression, when it accepted it. */
        private String canonicalExpression;
        /** How the expression evaluates right now; null when indeterminate. */
        private Boolean result;
        /** Severity that evaluation would fire at. */
        private String resultLevel;
        /** Machine-readable rejection code, e.g. {@code child_not_eligible}. */
        private String code;
        /** Human-readable reason, shown next to the expression box. */
        private String message;
        private List<CompositeChild> children = new ArrayList<>();
        /** Non-fatal advisories, each naming the alert it is about. */
        private List<String> warnings = new ArrayList<>();

        public boolean isValid()                    { return valid; }
        public void    setValid(boolean v)          { this.valid = v; }

        public boolean isSupported()                { return supported; }
        public void    setSupported(boolean v)      { this.supported = v; }

        public String getCanonicalExpression()      { return canonicalExpression; }
        public void   setCanonicalExpression(String v) { this.canonicalExpression = v; }

        public Boolean getResult()                  { return result; }
        public void    setResult(Boolean v)         { this.result = v; }

        public String getResultLevel()              { return resultLevel; }
        public void   setResultLevel(String v)      { this.resultLevel = v; }

        public String getCode()                     { return code; }
        public void   setCode(String v)             { this.code = v; }

        public String getMessage()                  { return message; }
        public void   setMessage(String v)          { this.message = v; }

        public List<CompositeChild> getChildren()             { return children; }
        public void setChildren(List<CompositeChild> v)       { this.children = v; }

        public List<String> getWarnings()           { return warnings; }
        public void setWarnings(List<String> v)     { this.warnings = v; }
    }

    /**
     * Result of a write (create / update / delete / enable / clone / move).
     *
     * <p>Carries OpenObserve's own message rather than inventing one, so the UI
     * can show the real reason a save was rejected.</p>
     */
    public static class WriteResult {
        private boolean ok;
        private String message;
        private String alertId;
        private String name;

        public boolean isOk()                   { return ok; }
        public void    setOk(boolean v)         { this.ok = v; }

        public String getMessage()              { return message; }
        public void   setMessage(String v)      { this.message = v; }

        public String getAlertId()              { return alertId; }
        public void   setAlertId(String v)      { this.alertId = v; }

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }
    }

    /**
     * Outcome of importing one alert document.
     *
     * <p>Import is per-document rather than all-or-nothing: a file can hold many
     * alerts and one bad entry must not discard the good ones. The UI lists these
     * in its Output Messages panel.</p>
     */
    public static class ImportOutcome {
        private String name;
        private boolean ok;
        private String message;
        private String alertId;

        public String getName()                 { return name; }
        public void   setName(String v)         { this.name = v; }

        public boolean isOk()                   { return ok; }
        public void    setOk(boolean v)         { this.ok = v; }

        public String getMessage()              { return message; }
        public void   setMessage(String v)      { this.message = v; }

        public String getAlertId()              { return alertId; }
        public void   setAlertId(String v)      { this.alertId = v; }
    }

    /** Envelope shared by the list endpoints, mirroring IamResponseDTO.Page. */
    public static class Page<T> {
        private List<T> items = new ArrayList<>();
        private String org;
        private boolean supported;
        private String backend;
        /** Total matching rows when OpenObserve reports one; else null. */
        private Integer total;

        /**
         * Whether this list was narrowed to a scope. Null when no scope was asked
         * for, which is every existing caller of every route using this envelope.
         *
         * <p>Only {@code GET /api/alerts/streams} sets it, and only when
         * {@code scopeType}/{@code scopeKey} were supplied. It has to be
         * distinguishable from the list itself: {@code false} means "we could not
         * work out which streams carry that service, so here is everything", and
         * a UI that rendered that identically to a genuine narrowing would let
         * somebody write a rule against a stream their service never touches
         * while believing the list had vouched for it.</p>
         */
        private Boolean scoped;

        /** Why {@link #scoped} is false, in plain language. Null otherwise. */
        private String notice;

        public List<T> getItems()              { return items; }
        public void    setItems(List<T> v)     { this.items = v; }

        public String getOrg()                 { return org; }
        public void   setOrg(String v)         { this.org = v; }

        public boolean isSupported()           { return supported; }
        public void    setSupported(boolean v) { this.supported = v; }

        public String getBackend()             { return backend; }
        public void   setBackend(String v)     { this.backend = v; }

        public Integer getTotal()              { return total; }
        public void    setTotal(Integer v)     { this.total = v; }

        public Boolean getScoped()             { return scoped; }
        public void    setScoped(Boolean v)    { this.scoped = v; }

        public String getNotice()              { return notice; }
        public void   setNotice(String v)      { this.notice = v; }
    }
}
