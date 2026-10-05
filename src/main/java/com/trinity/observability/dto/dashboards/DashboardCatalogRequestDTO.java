package com.trinity.observability.dto.dashboards;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the UI sends to the Dashboard Catalog.
 *
 * <p>Both UID fields are pattern-checked here as well as in the Grafana client.
 * That is not redundancy — a bad UID caught by validation is a 400 naming the
 * field, whereas the client's check is a last line of defence that produces a
 * generic message. The register form takes a UID by hand, so the readable error
 * is the one that matters.</p>
 */
public final class DashboardCatalogRequestDTO {

    /** Grafana's own UID alphabet. Anything else cannot exist in Grafana. */
    public static final String UID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    private DashboardCatalogRequestDTO() {
    }

    /** Body of {@code POST /api/dashboards/registry}. */
    public static class Register {

        @NotBlank(message = "uid is required — copy it from the Grafana URL /d/<uid>/<slug>")
        @Pattern(regexp = UID_PATTERN,
                message = "uid must be up to 64 characters of letters, digits, '-' or '_'")
        private String uid;

        /**
         * The folder tile to file it under. Required, because a registration with
         * no home would land in an "Ungrouped" tile nobody asked for — and the
         * folder is the one decision registration actually makes.
         */
        @NotBlank(message = "folderUid is required — choose the folder this dashboard belongs to")
        @Pattern(regexp = UID_PATTERN,
                message = "folderUid must be up to 64 characters of letters, digits, '-' or '_'")
        private String folderUid;

        /** Null means "append after whatever is already in that folder". */
        private Integer displayOrder;

        /** Null means enabled — registering something in order to hide it is the rare case. */
        private Boolean enabled;

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getFolderUid() {
            return folderUid;
        }

        public void setFolderUid(String folderUid) {
            this.folderUid = folderUid;
        }

        public Integer getDisplayOrder() {
            return displayOrder;
        }

        public void setDisplayOrder(Integer displayOrder) {
            this.displayOrder = displayOrder;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return "Register{uid=" + uid + ", folderUid=" + folderUid + '}';
        }
    }

    /**
     * Body of {@code PATCH /api/dashboards/registry/{uid}}.
     *
     * <p>Every field is nullable and null means "leave it alone" — that is what
     * makes this a PATCH. A DTO with primitives would turn "disable this one" into
     * "disable it AND move it to folder null AND set its order to 0".</p>
     */
    public static class Update {

        @Pattern(regexp = UID_PATTERN,
                message = "folderUid must be up to 64 characters of letters, digits, '-' or '_'")
        private String folderUid;

        private Boolean enabled;

        private Integer displayOrder;

        public String getFolderUid() {
            return folderUid;
        }

        public void setFolderUid(String folderUid) {
            this.folderUid = folderUid;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getDisplayOrder() {
            return displayOrder;
        }

        public void setDisplayOrder(Integer displayOrder) {
            this.displayOrder = displayOrder;
        }

        @Override
        public String toString() {
            return "Update{folderUid=" + folderUid + ", enabled=" + enabled
                    + ", displayOrder=" + displayOrder + '}';
        }
    }

    /** Query string of {@code GET /api/dashboards/available}. */
    public static class Available {

        @Size(max = 64)
        private String folderUid;

        @Size(max = 255)
        private String query;

        public String getFolderUid() {
            return folderUid;
        }

        public void setFolderUid(String folderUid) {
            this.folderUid = folderUid;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        @Override
        public String toString() {
            return "Available{folderUid=" + folderUid + ", query=" + query + '}';
        }
    }
}
