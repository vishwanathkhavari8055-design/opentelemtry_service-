package com.trinity.observability.dto.alerts;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request bodies and filters for the Alerts API.
 *
 * <p>Only the shapes this service actually interprets live here. A whole alert
 * document does NOT — it is forwarded as a raw {@code JsonNode}, for the reasons
 * set out on {@link AlertsResponseDTO}.</p>
 */
public final class AlertsRequestDTO {

    private AlertsRequestDTO() {}

    /**
     * Filters for the alert list.
     *
     * <p>Every field is optional. Nulls (and blanks) are omitted from the
     * outgoing query string rather than sent empty — OpenObserve treats
     * {@code ?enabled=} as a parse error, not as "no filter".</p>
     */
    public static class ListFilter {
        /** Folder to list; null means "all folders". */
        private String folder;
        /** "all" | "scheduled" | "realtime" | "anomaly_detection". */
        private String alertType;
        /** Case-insensitive substring match on the alert name. */
        private String search;
        private String streamType;
        private String streamName;
        private String owner;
        private Boolean enabled;
        private Integer pageSize;
        private Integer pageIdx;

        public String getFolder()                { return folder; }
        public void   setFolder(String v)        { this.folder = v; }

        public String getAlertType()             { return alertType; }
        public void   setAlertType(String v)     { this.alertType = v; }

        public String getSearch()                { return search; }
        public void   setSearch(String v)        { this.search = v; }

        public String getStreamType()            { return streamType; }
        public void   setStreamType(String v)    { this.streamType = v; }

        public String getStreamName()            { return streamName; }
        public void   setStreamName(String v)    { this.streamName = v; }

        public String getOwner()                 { return owner; }
        public void   setOwner(String v)         { this.owner = v; }

        public Boolean getEnabled()              { return enabled; }
        public void    setEnabled(Boolean v)     { this.enabled = v; }

        public Integer getPageSize()             { return pageSize; }
        public void    setPageSize(Integer v)    { this.pageSize = v; }

        public Integer getPageIdx()              { return pageIdx; }
        public void    setPageIdx(Integer v)     { this.pageIdx = v; }
    }

    /**
     * Create/update body.
     *
     * <p>{@code alert} is the OpenObserve document; {@code folderId} is carried
     * beside it because it is a placement concern rather than part of the
     * definition (OpenObserve takes it as a query parameter).</p>
     */
    public static class SaveAlert {
        private JsonNode alert;
        private String folderId;

        public JsonNode getAlert()               { return alert; }
        public void     setAlert(JsonNode v)     { this.alert = v; }

        public String getFolderId()              { return folderId; }
        public void   setFolderId(String v)      { this.folderId = v; }
    }

    /**
     * Body for the composite-expression check.
     *
     * <p>{@code compositeCondition} is forwarded VERBATIM for the same reason an
     * alert document is: it is OpenObserve's own {@code CompositeCondition}
     * shape ({@code expression}, {@code stale_child_policy},
     * {@code warning_counts_as_firing}) and remapping it through typed fields
     * here would drop whatever it gains next.</p>
     */
    public static class ValidateComposite {
        private JsonNode compositeCondition;
        /** The composite being edited, so the cycle check can exempt it; null when new. */
        private String compositeId;

        public JsonNode getCompositeCondition()          { return compositeCondition; }
        public void     setCompositeCondition(JsonNode v) { this.compositeCondition = v; }

        public String getCompositeId()                   { return compositeId; }
        public void   setCompositeId(String v)           { this.compositeId = v; }
    }

    /** Import body: one or many alert documents, plus their target folder. */
    public static class ImportAlerts {
        private List<JsonNode> alerts;
        private String folderId;

        public List<JsonNode> getAlerts()            { return alerts; }
        public void           setAlerts(List<JsonNode> v) { this.alerts = v; }

        public String getFolderId()              { return folderId; }
        public void   setFolderId(String v)      { this.folderId = v; }
    }

    /** Folder create/update body. */
    public static class SaveFolder {
        private String name;
        private String description;

        public String getName()                  { return name; }
        public void   setName(String v)          { this.name = v; }

        public String getDescription()           { return description; }
        public void   setDescription(String v)   { this.description = v; }
    }

    /** Clone body. */
    public static class CloneAlert {
        private String name;
        private String folderId;

        public String getName()                  { return name; }
        public void   setName(String v)          { this.name = v; }

        public String getFolderId()              { return folderId; }
        public void   setFolderId(String v)      { this.folderId = v; }
    }

    /** Move body. */
    public static class MoveAlerts {
        private List<String> alertIds;
        private String targetFolderId;

        public List<String> getAlertIds()             { return alertIds; }
        public void         setAlertIds(List<String> v) { this.alertIds = v; }

        public String getTargetFolderId()            { return targetFolderId; }
        public void   setTargetFolderId(String v)    { this.targetFolderId = v; }
    }
}
