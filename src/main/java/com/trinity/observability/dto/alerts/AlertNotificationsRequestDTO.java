package com.trinity.observability.dto.alerts;

/**
 * Inputs to the alert notification feed.
 */
public class AlertNotificationsRequestDTO {

    private AlertNotificationsRequestDTO() { }

    /** Filters for the live list. Every field is optional; unset means "no filter". */
    public static class ListFilter {
        /** firing | acknowledged | resolved. Null means unresolved only — see the service. */
        private String state;
        private String severity;
        private String search;
        private String streamName;
        private Integer limit;

        public String getState()            { return state; }
        public void setState(String v)      { this.state = v; }
        public String getSeverity()         { return severity; }
        public void setSeverity(String v)   { this.severity = v; }
        public String getSearch()           { return search; }
        public void setSearch(String v)     { this.search = v; }
        public String getStreamName()       { return streamName; }
        public void setStreamName(String v) { this.streamName = v; }
        public Integer getLimit()           { return limit; }
        public void setLimit(Integer v)     { this.limit = v; }
    }

    /**
     * Body of an acknowledge or resolve.
     *
     * <p>{@code actor} is who did it. It is supplied by the caller rather than
     * taken from a session because this service has no authentication of its
     * own — the honest thing is to record what the UI claimed and label it as
     * such, not to invent an identity the backend cannot actually verify.</p>
     */
    public static class Action {
        private String actor;
        private String note;

        public String getActor()          { return actor; }
        public void setActor(String v)    { this.actor = v; }
        public String getNote()           { return note; }
        public void setNote(String v)     { this.note = v; }
    }

    /** Body of the one-time registration call that wires OpenObserve to this webhook. */
    public static class Register {
        /**
         * Full webhook URL as OpenObserve should call it. Optional — defaults to
         * {@code alerts.webhook.public-base-url} plus the webhook path.
         */
        private String url;

        /** Destination name in OpenObserve. Defaults to {@code trinity-ui-webhook}. */
        private String destinationName;

        /** Template name in OpenObserve. Defaults to {@code trinity-ui-webhook}. */
        private String templateName;

        /**
         * Severity stamped on every alert routed through this destination.
         *
         * <p>Appended to the URL as {@code ?severity=}, which is what lets one
         * template back several destinations — a "critical" one and a "warning"
         * one — instead of needing a copy of the template per level.</p>
         */
        private String severity;

        public String getUrl()                    { return url; }
        public void setUrl(String v)              { this.url = v; }
        public String getDestinationName()        { return destinationName; }
        public void setDestinationName(String v)  { this.destinationName = v; }
        public String getTemplateName()           { return templateName; }
        public void setTemplateName(String v)     { this.templateName = v; }
        public String getSeverity()               { return severity; }
        public void setSeverity(String v)         { this.severity = v; }
    }
}
