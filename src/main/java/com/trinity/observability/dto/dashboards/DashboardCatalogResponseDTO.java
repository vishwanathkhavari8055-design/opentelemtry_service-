package com.trinity.observability.dto.dashboards;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the Dashboard Catalog answers with.
 *
 * <p>One thing here is deliberately NOT a modelled type: {@link Definition#model}
 * and {@link Query#results} are raw {@link JsonNode}. The browser turns the
 * dashboard model into a Scene and Grafana's own data frames into panels, so
 * translating either into Java types would mean this service had to understand
 * every panel option and every datasource's frame shape — and would silently drop
 * whatever it did not yet know about. Passing them through untouched is what makes
 * the renderer generic and keeps a new panel type a frontend concern.</p>
 *
 * <p>Every list carries {@code supported}, matching the Product Catalog: an
 * unconfigured deployment gets an empty list and a false flag rather than a 404,
 * so the UI can say "not configured" instead of looking broken.</p>
 */
public final class DashboardCatalogResponseDTO {

    private DashboardCatalogResponseDTO() {
    }

    /** One registered dashboard, as the UI sees it. */
    public static class Registration {

        private String uid;
        private String title;
        private String folderUid;
        private String folderTitle;
        private String grafanaFolderUid;
        private String grafanaFolderTitle;

        /**
         * True when the dashboard has since moved to a different Grafana folder
         * than the tile it is filed under. Reported rather than corrected — which
         * tile it appears in is this application's decision.
         */
        private boolean folderMismatch;

        private boolean enabled;
        private int displayOrder;
        private String registeredAt;
        private String updatedAt;

        public String getUid()                        { return uid; }
        public void setUid(String v)                  { this.uid = v; }
        public String getTitle()                      { return title; }
        public void setTitle(String v)                { this.title = v; }
        public String getFolderUid()                  { return folderUid; }
        public void setFolderUid(String v)            { this.folderUid = v; }
        public String getFolderTitle()                { return folderTitle; }
        public void setFolderTitle(String v)          { this.folderTitle = v; }
        public String getGrafanaFolderUid()           { return grafanaFolderUid; }
        public void setGrafanaFolderUid(String v)     { this.grafanaFolderUid = v; }
        public String getGrafanaFolderTitle()         { return grafanaFolderTitle; }
        public void setGrafanaFolderTitle(String v)   { this.grafanaFolderTitle = v; }
        public boolean isFolderMismatch()             { return folderMismatch; }
        public void setFolderMismatch(boolean v)      { this.folderMismatch = v; }
        public boolean isEnabled()                    { return enabled; }
        public void setEnabled(boolean v)             { this.enabled = v; }
        public int getDisplayOrder()                  { return displayOrder; }
        public void setDisplayOrder(int v)            { this.displayOrder = v; }
        public String getRegisteredAt()               { return registeredAt; }
        public void setRegisteredAt(String v)         { this.registeredAt = v; }
        public String getUpdatedAt()                  { return updatedAt; }
        public void setUpdatedAt(String v)            { this.updatedAt = v; }
    }

    public static class RegistrationList {

        private List<Registration> items = new ArrayList<>();
        private int total;
        private boolean supported = true;

        public List<Registration> getItems()          { return items; }
        public void setItems(List<Registration> v)    { this.items = v; }
        public int getTotal()                         { return total; }
        public void setTotal(int v)                   { this.total = v; }
        public boolean isSupported()                  { return supported; }
        public void setSupported(boolean v)           { this.supported = v; }
    }

    /**
     * One folder tile.
     *
     * <p>Counted server-side, next to the rows being counted. Computing this in the
     * browser would mean the tile and the list it opens onto could disagree, and
     * the disagreement would be silent.</p>
     */
    public static class FolderTile {

        private String folderUid;
        private String folderTitle;
        private int total;
        private int enabled;

        public String getFolderUid()                  { return folderUid; }
        public void setFolderUid(String v)            { this.folderUid = v; }
        public String getFolderTitle()                { return folderTitle; }
        public void setFolderTitle(String v)          { this.folderTitle = v; }
        public int getTotal()                         { return total; }
        public void setTotal(int v)                   { this.total = v; }
        public int getEnabled()                       { return enabled; }
        public void setEnabled(int v)                 { this.enabled = v; }
    }

    public static class FolderTileList {

        private List<FolderTile> items = new ArrayList<>();
        private boolean supported = true;

        public List<FolderTile> getItems()             { return items; }
        public void setItems(List<FolderTile> v)       { this.items = v; }
        public boolean isSupported()                   { return supported; }
        public void setSupported(boolean v)            { this.supported = v; }
    }

    /**
     * A Grafana folder offered in the register form, at any depth in the tree.
     *
     * <p>{@link #path} exists because {@link #title} is not unique and, on a nested
     * instance, not even meaningful on its own: "Default Org." says nothing until
     * you know it is "Monitoring / OpenObserver / Default Org.". A native
     * {@code <select>} cannot render a tree, so the path is what the option text
     * uses — the hierarchy travels in the string rather than in the markup.</p>
     */
    public static class GrafanaFolder {

        private String uid;
        private String title;
        private String parentUid;

        /** Titles from the root down, " / "-joined. Equal to {@link #title} at depth 0. */
        private String path;

        /** 0 for a root folder, 1 for its children, and so on. */
        private int depth;

        public String getUid()                        { return uid; }
        public void setUid(String v)                  { this.uid = v; }
        public String getTitle()                      { return title; }
        public void setTitle(String v)                { this.title = v; }
        public String getParentUid()                  { return parentUid; }
        public void setParentUid(String v)            { this.parentUid = v; }
        public String getPath()                       { return path; }
        public void setPath(String v)                 { this.path = v; }
        public int getDepth()                         { return depth; }
        public void setDepth(int v)                   { this.depth = v; }
    }

    public static class GrafanaFolderList {

        private List<GrafanaFolder> items = new ArrayList<>();

        /** True when {@code grafana.allowed-folder-uids} narrows what is on offer. */
        private boolean restricted;

        private boolean supported = true;
        private String message;

        public List<GrafanaFolder> getItems()          { return items; }
        public void setItems(List<GrafanaFolder> v)    { this.items = v; }
        public boolean isRestricted()                  { return restricted; }
        public void setRestricted(boolean v)           { this.restricted = v; }
        public boolean isSupported()                   { return supported; }
        public void setSupported(boolean v)            { this.supported = v; }
        public String getMessage()                     { return message; }
        public void setMessage(String v)               { this.message = v; }
    }

    /** A Grafana dashboard that is not registered yet — a candidate for the form. */
    public static class Candidate {

        private String uid;
        private String title;
        private String folderUid;
        private String folderTitle;
        private List<String> tags = new ArrayList<>();

        public String getUid()                        { return uid; }
        public void setUid(String v)                  { this.uid = v; }
        public String getTitle()                      { return title; }
        public void setTitle(String v)                { this.title = v; }
        public String getFolderUid()                  { return folderUid; }
        public void setFolderUid(String v)            { this.folderUid = v; }
        public String getFolderTitle()                { return folderTitle; }
        public void setFolderTitle(String v)          { this.folderTitle = v; }
        public List<String> getTags()                 { return tags; }
        public void setTags(List<String> v)           { this.tags = v; }
    }

    public static class CandidateList {

        private List<Candidate> items = new ArrayList<>();
        private int total;
        private boolean supported = true;
        private String message;

        public List<Candidate> getItems()              { return items; }
        public void setItems(List<Candidate> v)        { this.items = v; }
        public int getTotal()                          { return total; }
        public void setTotal(int v)                    { this.total = v; }
        public boolean isSupported()                   { return supported; }
        public void setSupported(boolean v)            { this.supported = v; }
        public String getMessage()                     { return message; }
        public void setMessage(String v)               { this.message = v; }
    }

    /**
     * A dashboard's live definition.
     *
     * @see DashboardCatalogResponseDTO for why {@code model} is a raw JsonNode
     */
    public static class Definition {

        private String uid;
        private String title;
        private Integer version;
        private String folderUid;
        private String folderTitle;

        /** The dashboard model, exactly as Grafana holds it. */
        private JsonNode model;

        public String getUid()                        { return uid; }
        public void setUid(String v)                  { this.uid = v; }
        public String getTitle()                      { return title; }
        public void setTitle(String v)                { this.title = v; }
        public Integer getVersion()                   { return version; }
        public void setVersion(Integer v)             { this.version = v; }
        public String getFolderUid()                  { return folderUid; }
        public void setFolderUid(String v)            { this.folderUid = v; }
        public String getFolderTitle()                { return folderTitle; }
        public void setFolderTitle(String v)          { this.folderTitle = v; }
        public JsonNode getModel()                    { return model; }
        public void setModel(JsonNode v)              { this.model = v; }
    }

    /** Panel query results, straight from Grafana. */
    public static class Query {

        private JsonNode results;

        public JsonNode getResults()                  { return results; }
        public void setResults(JsonNode v)            { this.results = v; }
    }

    /** A datasource, reduced to what a panel query needs to name one. */
    public static class Datasource {

        private String uid;
        private String name;
        private String type;

        public String getUid()                        { return uid; }
        public void setUid(String v)                  { this.uid = v; }
        public String getName()                       { return name; }
        public void setName(String v)                 { this.name = v; }
        public String getType()                       { return type; }
        public void setType(String v)                 { this.type = v; }
    }

    public static class DatasourceList {

        private List<Datasource> items = new ArrayList<>();
        private boolean supported = true;

        public List<Datasource> getItems()             { return items; }
        public void setItems(List<Datasource> v)       { this.items = v; }
        public boolean isSupported()                   { return supported; }
        public void setSupported(boolean v)            { this.supported = v; }
    }

    /** Values of one template variable. */
    public static class LabelValues {

        private List<String> values = new ArrayList<>();

        public List<String> getValues()                { return values; }
        public void setValues(List<String> v)          { this.values = v; }
    }

    /** The answer to a write, with a sentence the UI can show as-is. */
    public static class WriteResult {

        private boolean ok = true;
        private String uid;
        private String message;

        public static WriteResult of(String uid, String message) {
            WriteResult result = new WriteResult();
            result.uid = uid;
            result.message = message;
            return result;
        }

        public boolean isOk()                         { return ok; }
        public void setOk(boolean v)                  { this.ok = v; }
        public String getUid()                        { return uid; }
        public void setUid(String v)                  { this.uid = v; }
        public String getMessage()                    { return message; }
        public void setMessage(String v)              { this.message = v; }
    }

    /**
     * Whether the feature can work here, and against what.
     *
     * <p>Answers the one question a blank Dashboards screen raises: is Grafana
     * unconfigured, or is the catalog simply empty? The two look identical in the
     * UI and need completely different fixes.</p>
     */
    public static class Health {

        private boolean supported;
        private String grafanaUrl;
        private int orgId;
        private String registryScope;
        private String registryStore;
        private int registered;
        private String message;

        public boolean isSupported()                  { return supported; }
        public void setSupported(boolean v)           { this.supported = v; }
        public String getGrafanaUrl()                 { return grafanaUrl; }
        public void setGrafanaUrl(String v)           { this.grafanaUrl = v; }
        public int getOrgId()                         { return orgId; }
        public void setOrgId(int v)                   { this.orgId = v; }
        public String getRegistryScope()              { return registryScope; }
        public void setRegistryScope(String v)        { this.registryScope = v; }
        public String getRegistryStore()              { return registryStore; }
        public void setRegistryStore(String v)        { this.registryStore = v; }
        public int getRegistered()                    { return registered; }
        public void setRegistered(int v)              { this.registered = v; }
        public String getMessage()                    { return message; }
        public void setMessage(String v)              { this.message = v; }
    }
}
