package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a rule would match right now, condition by condition.
 *
 * <h2>Why the answer is a ladder and not a number</h2>
 *
 * <p>A preview that reports one figure — "this rule matches 0 rows" — is
 * accurate and nearly useless. Zero has at least four causes, and they need four
 * different fixes: the stream is wrong, the window is wrong, one condition is
 * wrong, or the rule is correct and the thing it watches for is simply not
 * happening. From a single number they are indistinguishable, so the operator
 * starts guessing.</p>
 *
 * <p>{@link #getSteps()} separates them by construction. Step 0 is the window
 * with no conditions at all, so an empty step 0 says "wrong stream or wrong
 * window" before any condition is blamed. Each later step adds exactly one
 * condition and reports what survived, so the condition that emptied the set
 * names itself.</p>
 */
public class AlertPreviewResponseDTO {

    private AlertPreviewResponseDTO() { }

    /** One rung: the query with the first N conditions applied. */
    public static class Step {

        /** 0 for the unfiltered window, then 1..n in the order conditions appear. */
        private int index;

        /** What this step ADDED, in the operator's own terms. */
        private String label;

        /** The cumulative WHERE clause behind this step. Null for step 0. */
        private String where;

        /** Rows matching everything up to and including this step. */
        private long matched;

        /**
         * How many rows this one condition removed, relative to the step before.
         *
         * <p>The number the eye actually goes to. A condition that removes
         * nothing is usually a condition that does nothing, and one that removes
         * everything is the one to look at — neither is visible from the running
         * totals alone.</p>
         */
        private long removed;

        /**
         * False when OpenObserve could not scan the whole window.
         *
         * <p>Load-bearing, not decorative. OpenObserve answers a window it cannot
         * serve with HTTP 200, zero hits and {@code is_partial}, which is
         * indistinguishable from "nothing matched" — and a preview that reported
         * that as an honest zero would send somebody to rewrite a rule that was
         * correct all along.</p>
         */
        private boolean complete = true;

        /** Sample rows, newest first. Empty when samples are switched off. */
        private List<Map<String, Object>> rows = new ArrayList<>();

        /**
         * Why this step could not be evaluated, or null.
         *
         * <p>Per step rather than per request: one condition naming a column
         * that does not exist should not blank the other five, which are exactly
         * the evidence needed to see what is wrong with it.</p>
         */
        private String error;

        public int getIndex()                           { return index; }
        public void setIndex(int v)                     { this.index = v; }
        public String getLabel()                        { return label; }
        public void setLabel(String v)                  { this.label = v; }
        public String getWhere()                        { return where; }
        public void setWhere(String v)                  { this.where = v; }
        public long getMatched()                        { return matched; }
        public void setMatched(long v)                  { this.matched = v; }
        public long getRemoved()                        { return removed; }
        public void setRemoved(long v)                  { this.removed = v; }
        public boolean isComplete()                     { return complete; }
        public void setComplete(boolean v)              { this.complete = v; }
        public List<Map<String, Object>> getRows()      { return rows; }
        public void setRows(List<Map<String, Object>> v) { this.rows = v == null ? new ArrayList<>() : v; }
        public String getError()                        { return error; }
        public void setError(String v)                  { this.error = v; }
    }

    /** One row of an aggregated preview: the group, and its computed value. */
    public static class Group {
        private Map<String, Object> key;
        private Double value;
        /** Whether this group satisfies the aggregation's HAVING clause. */
        private boolean satisfies;

        public Map<String, Object> getKey()         { return key; }
        public void setKey(Map<String, Object> v)   { this.key = v; }
        public Double getValue()                    { return value; }
        public void setValue(Double v)              { this.value = v; }
        public boolean isSatisfies()                { return satisfies; }
        public void setSatisfies(boolean v)         { this.satisfies = v; }
    }

    /**
     * Whether the finished rule would fire on this window.
     *
     * <p>Separate from the steps because it answers a different question. The
     * steps say what the conditions match; this says whether that amount crosses
     * the trigger — which is where the other half of the mistakes live, and
     * where a rule that matches plenty of rows still never pages anybody.</p>
     */
    public static class Evaluation {

        /** False when no trigger condition was supplied — nothing to decide against. */
        private boolean evaluated;

        private String operator;
        private Double threshold;

        /** What was measured: the row count, or the number of qualifying groups. */
        private Double observed;

        /** What {@link #observed} counts, in words. */
        private String observedLabel;

        private boolean wouldFire;

        /** One sentence an operator can act on. Always populated. */
        private String summary;

        /** Per-group results, when the rule aggregates. Empty otherwise. */
        private List<Group> groups = new ArrayList<>();

        public boolean isEvaluated()                { return evaluated; }
        public void setEvaluated(boolean v)         { this.evaluated = v; }
        public String getOperator()                 { return operator; }
        public void setOperator(String v)           { this.operator = v; }
        public Double getThreshold()                { return threshold; }
        public void setThreshold(Double v)          { this.threshold = v; }
        public Double getObserved()                 { return observed; }
        public void setObserved(Double v)           { this.observed = v; }
        public String getObservedLabel()            { return observedLabel; }
        public void setObservedLabel(String v)      { this.observedLabel = v; }
        public boolean isWouldFire()                { return wouldFire; }
        public void setWouldFire(boolean v)         { this.wouldFire = v; }
        public String getSummary()                  { return summary; }
        public void setSummary(String v)            { this.summary = v; }
        public List<Group> getGroups()              { return groups; }
        public void setGroups(List<Group> v)        { this.groups = v == null ? new ArrayList<>() : v; }
    }

    /** {@code POST /api/alert-builder/preview}. */
    public static class Preview {

        /** False when OpenObserve is disabled, so the UI can say so rather than show zeros. */
        private boolean supported = true;

        private String org;
        private String streamType;
        private String streamName;

        /** {@code custom} | {@code sql} | {@code promql} — how the rule queries. */
        private String mode;

        /** The window previewed, ISO-8601 and in epoch micros for the chart. */
        private String windowStart;
        private String windowEnd;
        private long windowStartMicros;
        private long windowEndMicros;
        private int periodMinutes;

        /** The scope the preview was confined to, when one was given. */
        private String scopeLabel;
        private String scopeSql;

        /** The full query behind the last step, for the operator who wants to read it. */
        private String sql;

        private List<Step> steps = new ArrayList<>();

        private Evaluation evaluation = new Evaluation();

        /** How long the whole preview took, so a slow window is visibly slow. */
        private long durationMs;

        /** A caveat that applies to the whole preview, or null. */
        private String notice;

        /** Set only when the preview could not run at all. */
        private String error;

        public boolean isSupported()            { return supported; }
        public void setSupported(boolean v)     { this.supported = v; }
        public String getOrg()                  { return org; }
        public void setOrg(String v)            { this.org = v; }
        public String getStreamType()           { return streamType; }
        public void setStreamType(String v)     { this.streamType = v; }
        public String getStreamName()           { return streamName; }
        public void setStreamName(String v)     { this.streamName = v; }
        public String getMode()                 { return mode; }
        public void setMode(String v)           { this.mode = v; }
        public String getWindowStart()          { return windowStart; }
        public void setWindowStart(String v)    { this.windowStart = v; }
        public String getWindowEnd()            { return windowEnd; }
        public void setWindowEnd(String v)      { this.windowEnd = v; }
        public long getWindowStartMicros()      { return windowStartMicros; }
        public void setWindowStartMicros(long v) { this.windowStartMicros = v; }
        public long getWindowEndMicros()        { return windowEndMicros; }
        public void setWindowEndMicros(long v)  { this.windowEndMicros = v; }
        public int getPeriodMinutes()           { return periodMinutes; }
        public void setPeriodMinutes(int v)     { this.periodMinutes = v; }
        public String getScopeLabel()           { return scopeLabel; }
        public void setScopeLabel(String v)     { this.scopeLabel = v; }
        public String getScopeSql()             { return scopeSql; }
        public void setScopeSql(String v)       { this.scopeSql = v; }
        public String getSql()                  { return sql; }
        public void setSql(String v)            { this.sql = v; }
        public List<Step> getSteps()            { return steps; }
        public void setSteps(List<Step> v)      { this.steps = v == null ? new ArrayList<>() : v; }
        public Evaluation getEvaluation()       { return evaluation; }
        public void setEvaluation(Evaluation v) { this.evaluation = v; }
        public long getDurationMs()             { return durationMs; }
        public void setDurationMs(long v)       { this.durationMs = v; }
        public String getNotice()               { return notice; }
        public void setNotice(String v)         { this.notice = v; }
        public String getError()                { return error; }
        public void setError(String v)          { this.error = v; }
    }
}
