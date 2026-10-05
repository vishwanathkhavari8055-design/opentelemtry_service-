package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What an alert can be written about once a service, tool or product has been
 * picked.
 *
 * <h2>The problem this shape exists to solve</h2>
 *
 * <p>The condition builder used to offer every stream and every metric this
 * tenant has — on this deployment that is roughly three thousand metric names —
 * regardless of which service the operator had just selected. Picking the right
 * one out of that list is not a filtering problem for a human; it is a search
 * problem they have no index for, and the usual outcome is a rule written
 * against a metric the chosen service does not emit. That rule is syntactically
 * perfect, syncs cleanly, reports itself healthy and never fires.</p>
 *
 * <h2>Two different narrowings, because the signals differ</h2>
 *
 * <p>For {@code metrics}, each metric name IS its own stream in OpenObserve, so
 * scoping narrows the STREAM list: {@link TargetList#getItems()} is the set of
 * metrics that service actually writes.</p>
 *
 * <p>For {@code logs} and {@code traces} everything shares a handful of wide
 * streams, so there is nothing to narrow — the scoping has to happen inside the
 * query instead. That is what {@link TargetList#getFilter()} carries: the
 * {@code service_name = 'X'} predicate the builder must splice into the
 * condition tree, already in OpenObserve's own condition shape so the UI does
 * not have to construct it and get the spelling wrong.</p>
 */
public class AlertScopeResponseDTO {

    private AlertScopeResponseDTO() { }

    /** One thing the operator can scope an alert to. */
    public static class ScopeOption {

        /**
         * {@code service} — a telemetry-reported {@code service_name};
         * {@code product} — a Product Catalog product, covering its resources;
         * {@code resource} — one registered Product Catalog entry.
         */
        private String type;

        /** What to send back as {@code scopeKey}. */
        private String key;

        /** What to show. */
        private String label;

        /** Catalog category, or the producer index's classification. For grouping. */
        private String category;

        /** Owning product slug, when this option has one. */
        private String productCode;

        /** Signals known to carry data for it: logs / traces / metrics. */
        private List<String> signals = new ArrayList<>();

        /**
         * Metrics this scope can be alerted on, or {@code -1} when the producer
         * index has not finished building.
         *
         * <p>{@code -1} rather than {@code 0}: a zero renders as "this service
         * has no metrics", which is a statement about the service. The real
         * situation is a statement about US — we have not looked yet.</p>
         */
        private int metricCount = -1;

        /** The telemetry column that identifies this scope, e.g. {@code service_name}. */
        private String filterColumn;

        /**
         * The exact values that column takes, as telemetry spells them.
         *
         * <p>A product resolves to several; a service to one. Never the label —
         * {@code IoTOpsApiSvc} and {@code iotopsapisvc} are two different
         * services on this deployment, so matching case-insensitively would
         * quietly alert on both.</p>
         */
        private List<String> filterValues = new ArrayList<>();

        public String getType()                 { return type; }
        public void setType(String v)           { this.type = v; }
        public String getKey()                  { return key; }
        public void setKey(String v)            { this.key = v; }
        public String getLabel()                { return label; }
        public void setLabel(String v)          { this.label = v; }
        public String getCategory()             { return category; }
        public void setCategory(String v)       { this.category = v; }
        public String getProductCode()          { return productCode; }
        public void setProductCode(String v)    { this.productCode = v; }
        public List<String> getSignals()        { return signals; }
        public void setSignals(List<String> v)  { this.signals = v == null ? new ArrayList<>() : v; }
        public int getMetricCount()             { return metricCount; }
        public void setMetricCount(int v)       { this.metricCount = v; }
        public String getFilterColumn()         { return filterColumn; }
        public void setFilterColumn(String v)   { this.filterColumn = v; }
        public List<String> getFilterValues()   { return filterValues; }
        public void setFilterValues(List<String> v) { this.filterValues = v == null ? new ArrayList<>() : v; }
    }

    /** {@code GET /api/alert-builder/scopes}. */
    public static class ScopeList {
        private String org;
        private List<ScopeOption> items = new ArrayList<>();
        private int total;

        /** True while the producer index is still sweeping; counts are lower bounds. */
        private boolean building;
        /** True when the served index came from an unfinished sweep. */
        private boolean partial;
        private int progressDone;
        private int progressTotal;

        /** Plain-language caveat, or null. Rendered as-is; never a silent empty list. */
        private String notice;

        public String getOrg()                      { return org; }
        public void setOrg(String v)                { this.org = v; }
        public List<ScopeOption> getItems()         { return items; }
        public void setItems(List<ScopeOption> v)   { this.items = v == null ? new ArrayList<>() : v; }
        public int getTotal()                       { return total; }
        public void setTotal(int v)                 { this.total = v; }
        public boolean isBuilding()                 { return building; }
        public void setBuilding(boolean v)          { this.building = v; }
        public boolean isPartial()                  { return partial; }
        public void setPartial(boolean v)           { this.partial = v; }
        public int getProgressDone()                { return progressDone; }
        public void setProgressDone(int v)          { this.progressDone = v; }
        public int getProgressTotal()               { return progressTotal; }
        public void setProgressTotal(int v)         { this.progressTotal = v; }
        public String getNotice()                   { return notice; }
        public void setNotice(String v)             { this.notice = v; }
    }

    /** One thing a rule can be pointed at: a metric name, or a log/trace stream. */
    public static class Target {
        private String name;

        /**
         * {@code emitted} — the service's own instrumentation;
         * {@code observed} — platform telemetry ABOUT it, emitted by something
         * else (kube-state-metrics, cAdvisor, the collector);
         * {@code stream} — a log or trace stream, where the distinction does
         * not apply.
         *
         * <p>Both metric kinds are legitimate things to alert on and both are
         * returned. The distinction is kept because it changes what a threshold
         * means: a service's own {@code http_server_duration} going quiet is
         * that service failing, while {@code kube_pod_status_phase} going quiet
         * is the scraper failing.</p>
         */
        private String kind;

        /** Catalogue grouping, e.g. {@code jvm}, {@code kubernetes}. May be null. */
        private String technology;

        /** Prometheus type — counter, gauge, histogram. May be null. */
        private String type;

        /**
         * The column THIS stream identifies the scope by. Null for metrics, and
         * for a stream whose schema could not be read.
         *
         * <p>Per stream because the streams disagree. A tenant here has logs
         * exported by the OTel SDK, which carry {@code service_name}, and logs
         * collected by the Kubernetes scraper, which carry
         * {@code k8s_container_name} and no service attribute at all. One
         * predicate for the whole list would be wrong for half of it — and wrong
         * in the worst way, because the query fails with {@code unknown field}
         * and reads as the editor being broken rather than as a bad column.</p>
         */
        private String filterColumn;

        public Target() { }

        public Target(String name, String kind, String technology, String type) {
            this.name = name;
            this.kind = kind;
            this.technology = technology;
            this.type = type;
        }

        public String getName()              { return name; }
        public void setName(String v)        { this.name = v; }
        public String getKind()              { return kind; }
        public void setKind(String v)        { this.kind = v; }
        public String getTechnology()        { return technology; }
        public void setTechnology(String v)  { this.technology = v; }
        public String getType()              { return type; }
        public void setType(String v)        { this.type = v; }
        public String getFilterColumn()      { return filterColumn; }
        public void setFilterColumn(String v) { this.filterColumn = v; }
    }

    /**
     * The predicate that confines a rule to the chosen scope.
     *
     * <p>Only meaningful for {@code logs} and {@code traces}, where one stream
     * carries every service. Null for {@code metrics}, where the narrowing
     * already happened in the stream list.</p>
     */
    public static class ScopeFilter {
        private String column;
        private String operator;

        /** Single value, when the scope resolves to exactly one. */
        private String value;

        /** Every value, when it resolves to more than one. */
        private List<String> values = new ArrayList<>();

        /**
         * The same predicate in OpenObserve's own condition shape, ready to be
         * spliced into {@code query_condition.conditions}.
         *
         * <p>Returned built rather than described because the builder would
         * otherwise assemble it client-side, and the one thing it must not get
         * wrong is the column name and the exact spelling of the value.</p>
         */
        private JsonNode condition;

        /** Equivalent SQL fragment, for the SQL-mode editor and the preview. */
        private String sql;

        public String getColumn()               { return column; }
        public void setColumn(String v)         { this.column = v; }
        public String getOperator()             { return operator; }
        public void setOperator(String v)       { this.operator = v; }
        public String getValue()                { return value; }
        public void setValue(String v)          { this.value = v; }
        public List<String> getValues()         { return values; }
        public void setValues(List<String> v)   { this.values = v == null ? new ArrayList<>() : v; }
        public JsonNode getCondition()          { return condition; }
        public void setCondition(JsonNode v)    { this.condition = v; }
        public String getSql()                  { return sql; }
        public void setSql(String v)            { this.sql = v; }
    }

    /** {@code GET /api/alert-builder/targets}. */
    public static class TargetList {
        private String org;
        private String scopeType;
        private String scopeKey;
        private String scopeLabel;
        private String streamType;

        /** The telemetry identities the scope resolved to. Empty when unscoped. */
        private List<String> resolvedNames = new ArrayList<>();

        private List<Target> items = new ArrayList<>();
        private int total;

        /**
         * True when the list really was narrowed to the scope.
         *
         * <p>False means the caller is looking at everything, and the reason is
         * in {@link #getNotice()}. This flag is the difference between "these
         * are your service's metrics" and "we could not work that out, so here
         * is everything" — and a UI that showed both the same way would let
         * somebody pick a metric their service does not emit while believing
         * the list had vouched for it.</p>
         */
        private boolean scoped;

        private ScopeFilter filter;

        private boolean building;
        private boolean partial;
        private String notice;

        public String getOrg()                          { return org; }
        public void setOrg(String v)                    { this.org = v; }
        public String getScopeType()                    { return scopeType; }
        public void setScopeType(String v)              { this.scopeType = v; }
        public String getScopeKey()                     { return scopeKey; }
        public void setScopeKey(String v)               { this.scopeKey = v; }
        public String getScopeLabel()                   { return scopeLabel; }
        public void setScopeLabel(String v)             { this.scopeLabel = v; }
        public String getStreamType()                   { return streamType; }
        public void setStreamType(String v)             { this.streamType = v; }
        public List<String> getResolvedNames()          { return resolvedNames; }
        public void setResolvedNames(List<String> v)    { this.resolvedNames = v == null ? new ArrayList<>() : v; }
        public List<Target> getItems()                  { return items; }
        public void setItems(List<Target> v)            { this.items = v == null ? new ArrayList<>() : v; }
        public int getTotal()                           { return total; }
        public void setTotal(int v)                     { this.total = v; }
        public boolean isScoped()                       { return scoped; }
        public void setScoped(boolean v)                { this.scoped = v; }
        public ScopeFilter getFilter()                  { return filter; }
        public void setFilter(ScopeFilter v)            { this.filter = v; }
        public boolean isBuilding()                     { return building; }
        public void setBuilding(boolean v)              { this.building = v; }
        public boolean isPartial()                      { return partial; }
        public void setPartial(boolean v)               { this.partial = v; }
        public String getNotice()                       { return notice; }
        public void setNotice(String v)                 { this.notice = v; }
    }

    /** {@code GET /api/alert-builder/fields} — columns on one scoped stream. */
    public static class FieldList {
        private String org;
        private String streamType;
        private String streamName;
        private List<AlertsResponseDTO.StreamField> items = new ArrayList<>();
        private int total;

        /** The scope predicate again, so the builder can seed the first condition. */
        private ScopeFilter filter;

        private String notice;

        public String getOrg()                                      { return org; }
        public void setOrg(String v)                                { this.org = v; }
        public String getStreamType()                               { return streamType; }
        public void setStreamType(String v)                         { this.streamType = v; }
        public String getStreamName()                               { return streamName; }
        public void setStreamName(String v)                         { this.streamName = v; }
        public List<AlertsResponseDTO.StreamField> getItems()       { return items; }
        public void setItems(List<AlertsResponseDTO.StreamField> v) { this.items = v == null ? new ArrayList<>() : v; }
        public int getTotal()                                       { return total; }
        public void setTotal(int v)                                 { this.total = v; }
        public ScopeFilter getFilter()                              { return filter; }
        public void setFilter(ScopeFilter v)                        { this.filter = v; }
        public String getNotice()                                   { return notice; }
        public void setNotice(String v)                             { this.notice = v; }
    }
}
