package com.trinity.observability.dto.alerts;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * What to preview.
 *
 * <p>Deliberately NOT {@link AlertRuleRequestDTO.SaveRule}. A preview runs
 * while the rule is still being typed — there is no name yet, no destinations,
 * often no severity — and reusing the save shape would make the editor invent
 * values for fields the preview does not read, just to get past validation. The
 * overlap is the four things a preview actually needs: where to look, what to
 * match, and what would count as firing.</p>
 */
public class AlertPreviewRequestDTO {

    private AlertPreviewRequestDTO() { }

    /** Body of {@code POST /api/alert-builder/preview}. */
    public static class Preview {

        @NotBlank(message = "streamType is required")
        @Pattern(regexp = "logs|metrics|traces", message = "streamType must be logs, metrics or traces")
        private String streamType;

        /**
         * The stream to read. For {@code metrics} this is the metric name, which
         * is what a metric's stream is called in OpenObserve.
         *
         * <p>Optional only for a PromQL preview, where the query names its own
         * metric and a stream would be ignored.</p>
         */
        private String streamName;

        /**
         * OpenObserve's {@code query_condition}, exactly as it would be saved.
         *
         * <p>Passed whole rather than as a list of conditions so the preview can
         * be given a rule straight out of the editor — or straight out of
         * {@code GET /api/alert-rules/{uuid}} — with nothing reshaped in
         * between. A preview of a reshaped rule is a preview of a different
         * rule.</p>
         */
        private JsonNode queryCondition;

        /**
         * OpenObserve's {@code trigger_condition}: {@code period},
         * {@code operator}, {@code threshold}.
         *
         * <p>Optional. Without it the preview still shows what each condition
         * matches — it just cannot say whether that WOULD FIRE, because nothing
         * has told it what firing means.</p>
         */
        private JsonNode triggerCondition;

        /**
         * Look-back window in minutes, overriding {@code triggerCondition.period}.
         *
         * <p>For the operator who wants to see whether a rule would have caught
         * something that happened an hour ago without editing the rule's real
         * period to find out.</p>
         */
        @Min(value = 1, message = "periodMinutes must be at least 1")
        @Max(value = 1440, message = "periodMinutes must be at most 1440 (24h)")
        private Integer periodMinutes;

        /** Sample rows returned per step. 0 turns samples off and halves the queries. */
        @Min(value = 0, message = "sampleSize must not be negative")
        @Max(value = 100, message = "sampleSize must be at most 100")
        private Integer sampleSize;

        /**
         * Optionally confine the preview to a scope, the same way the editor
         * confines the rule.
         *
         * <p>Without it, previewing a logs condition against the shared stream
         * counts every service's rows and reports a number the finished,
         * scoped rule will never produce.</p>
         */
        private String scopeType;
        private String scopeKey;

        public String getStreamType()                { return streamType; }
        public void setStreamType(String v)          { this.streamType = v; }
        public String getStreamName()                { return streamName; }
        public void setStreamName(String v)          { this.streamName = v; }
        public JsonNode getQueryCondition()          { return queryCondition; }
        public void setQueryCondition(JsonNode v)    { this.queryCondition = v; }
        public JsonNode getTriggerCondition()        { return triggerCondition; }
        public void setTriggerCondition(JsonNode v)  { this.triggerCondition = v; }
        public Integer getPeriodMinutes()            { return periodMinutes; }
        public void setPeriodMinutes(Integer v)      { this.periodMinutes = v; }
        public Integer getSampleSize()               { return sampleSize; }
        public void setSampleSize(Integer v)         { this.sampleSize = v; }
        public String getScopeType()                 { return scopeType; }
        public void setScopeType(String v)           { this.scopeType = v; }
        public String getScopeKey()                  { return scopeKey; }
        public void setScopeKey(String v)            { this.scopeKey = v; }
    }
}
