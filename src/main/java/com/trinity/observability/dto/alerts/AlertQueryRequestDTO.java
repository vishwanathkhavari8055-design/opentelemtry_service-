package com.trinity.observability.dto.alerts;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inputs for the enterprise alerts screen.
 *
 * <p>Separate from {@link AlertNotificationsRequestDTO} rather than an extension
 * of it. That one is the contract the current UI calls and must keep working
 * byte-for-byte; this one is free to have multi-select filters, sorting and real
 * pagination without renegotiating it.</p>
 *
 * <h2>Validation is not decoration here</h2>
 *
 * <p>Sort fields and directions are constrained by pattern because they are
 * interpolated into a JPA {@code Sort}, and page size is bounded because an
 * unbounded one is a trivial way to make the server materialise every alert it
 * has ever stored. Both are the kind of parameter that looks harmless until
 * somebody points a script at it.</p>
 */
public final class AlertQueryRequestDTO {

    /**
     * Sortable columns, as an allow-list.
     *
     * <p>An allow-list rather than validation-by-pattern: a caller-supplied sort
     * field reaches Spring Data as a property name, and an unrecognised one throws
     * from deep inside the criteria builder with a message that means nothing to a
     * client. Rejecting it here produces a 400 naming the alternatives.</p>
     */
    public static final List<String> SORTABLE = List.of(
            "lastFiredAt", "firstFiredAt", "severity", "status", "alertName",
            "serviceName", "occurrences", "updatedAt");

    private AlertQueryRequestDTO() {
        // Container for the nested request shapes only.
    }

    /** Query string of the enterprise alert search. */
    public static class Search {

        /** Free text over name, service, host, stream, trace id and fingerprint. */
        @Size(max = 255)
        private String searchText;

        /** Multi-select. Empty means every severity. */
        private List<String> severity = new ArrayList<>();

        /** Multi-select over FIRING / ACKNOWLEDGED / RESOLVED / CLOSED. */
        private List<String> status = new ArrayList<>();

        @Size(max = 255)
        private String service;

        @Size(max = 255)
        private String stream;

        /** {@code stream} or {@code webhook} — which ingest path produced the row. */
        @Size(max = 32)
        private String source;

        /** Rule UUID, so the filter survives a rule rename. */
        @Size(max = 64)
        private String ruleId;

        /**
         * Relative window, e.g. {@code now-24h}, or blank for everything.
         *
         * <p>Same grammar as every other screen in this service, so a time filter
         * means the same thing here as it does on Logs.</p>
         */
        @Size(max = 32)
        private String window;

        @Min(0)
        private Integer page;

        @Min(1)
        @Max(500)
        private Integer size;

        /**
         * One row per alert (newest episode) rather than one row per episode.
         *
         * <p>Null means "use the configured default", which is collapsed — see
         * {@link com.trinity.observability.config.AlertDisplayProperties}. Present so
         * a caller that genuinely wants the episode history, such as an export, can
         * ask for it without a configuration change.</p>
         */
        private Boolean collapse;

        @Pattern(regexp = "lastFiredAt|firstFiredAt|severity|status|alertName|serviceName|occurrences|updatedAt",
                message = "sortBy must be one of " + "lastFiredAt, firstFiredAt, severity, status, "
                        + "alertName, serviceName, occurrences, updatedAt")
        private String sortBy;

        @Pattern(regexp = "(?i)asc|desc", message = "sortDir must be asc or desc")
        private String sortDir;

        public String getSearch() {
            return searchText;
        }

        public void setSearch(String search) {
            this.searchText = search;
        }

        public List<String> getSeverity() {
            return severity;
        }

        public void setSeverity(List<String> severity) {
            this.severity = severity == null ? new ArrayList<>() : severity;
        }

        public List<String> getStatus() {
            return status;
        }

        public void setStatus(List<String> status) {
            this.status = status == null ? new ArrayList<>() : status;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public Integer getSize() {
            return size;
        }

        public void setSize(Integer size) {
            this.size = size;
        }

        public Boolean getCollapse() {
            return collapse;
        }

        public void setCollapse(Boolean collapse) {
            this.collapse = collapse;
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

        @Override
        public String toString() {
            return "Search{search=" + searchText + ", severity=" + severity + ", status=" + status
                    + ", service=" + service + ", stream=" + stream + ", source=" + source
                    + ", ruleId=" + ruleId + ", window=" + window + ", page=" + page
                    + ", size=" + size + ", sortBy=" + sortBy + ", sortDir=" + sortDir + '}';
        }
    }

    /** Body of a single lifecycle transition. */
    public static class Transition {

        @Size(max = 128)
        private String actor;

        @Size(max = 500)
        private String note;

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
    }

    /**
     * Body of a bulk transition.
     *
     * <p>Ids rather than fingerprints: a fingerprint identifies the alert SERIES
     * across every episode it has ever had, so acknowledging by fingerprint would
     * be ambiguous about which episode the operator was looking at. An id is one
     * episode — the row they actually ticked.</p>
     */
    public static class BulkTransition extends Transition {

        @NotEmpty(message = "ids must not be empty")
        @Size(max = 500, message = "at most 500 alerts per bulk operation")
        private List<Long> ids = new ArrayList<>();

        @Pattern(regexp = "(?i)acknowledge|resolve|close|reopen",
                message = "action must be acknowledge, resolve, close or reopen")
        private String action;

        public List<Long> getIds() {
            return ids;
        }

        public void setIds(List<Long> ids) {
            this.ids = ids == null ? new ArrayList<>() : ids;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
