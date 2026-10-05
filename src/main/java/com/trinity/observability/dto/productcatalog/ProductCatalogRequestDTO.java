package com.trinity.observability.dto.productcatalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inputs for the Product Catalog API.
 *
 * <p>Validated at the controller boundary with bean validation, so an empty
 * resource name or an unknown category is rejected with a 400 that names the
 * offending field before any OpenObserve query is issued. The category is
 * carried as a String rather than the enum so an unknown value produces the
 * message from {@link ResourceCategory#fromCode(String)} (which lists what IS
 * allowed) instead of Jackson's "not one of the values accepted".</p>
 */
public final class ProductCatalogRequestDTO {

    /**
     * The character set a resource name may use.
     *
     * <p>Matches {@code SqlSafety}'s identifier class plus space, because the
     * value ends up inside a SQL string literal and, for the OpenObserve-backed
     * snapshot store, inside an ingested record. It is escaped at every call
     * site as well — this is the belt to that braces, and it also stops a name
     * that could never match any telemetry from being registered at all.</p>
     */
    public static final String NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.\\-: ]*$";

    private ProductCatalogRequestDTO() {
        // Container for the nested request shapes only.
    }

    /** Body of {@code POST /api/product-catalog/validate}. */
    public static class Validate {

        @NotBlank(message = "category is required")
        private String category;

        @NotBlank(message = "resourceName is required")
        @Size(max = 255, message = "resourceName must be at most 255 characters")
        @Pattern(regexp = NAME_PATTERN,
                message = "resourceName may contain letters, digits, spaces, '_', '-', '.' and ':',"
                        + " and must start with a letter or digit")
        private String resourceName;

        /**
         * Which product of the category this microservice belongs to, as the code
         * from {@code GET /api/product-catalog/products}.
         *
         * <p>Optional at the protocol level, required by the service for the
         * categories that HAVE products — Applications, AI Services and Mobile
         * Application. Enforcing it here instead would reject Tools, Databases,
         * Platform and DLH, which are registered by bare name and have no product
         * list to pick from.</p>
         */
        @Size(max = 128)
        private String product;

        /**
         * The version in service, free text.
         *
         * <p>Unvalidated beyond a length cap on purpose: "1.4.2", "2024.3",
         * "v3-rc1" and "latest" all occur in one estate, and a pattern strict
         * enough to be meaningful would reject most of them.</p>
         */
        @Size(max = 64)
        private String version;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getResourceName() {
            return resourceName;
        }

        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        public String getProduct() {
            return product;
        }

        public void setProduct(String product) {
            this.product = product;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @Override
        public String toString() {
            return "Validate{category=" + category + ", resourceName=" + resourceName + '}';
        }
    }

    /**
     * Body of {@code POST /api/product-catalog/register}.
     *
     * <p>Extends {@link Validate} because registration IS a validation plus a
     * write — the same two fields, the same rules, and the same rejection
     * messages. Keeping one field set means the Validate button in the UI can
     * never accept something Register then refuses.</p>
     */
    public static class Register extends Validate {

        /**
         * Initial state. Omit for {@code ENABLED}, which is what registering a
         * resource means; {@code DISABLED} exists so a resource can be
         * pre-registered without its telemetry appearing yet.
         */
        @Pattern(regexp = "(?i)ENABLED|DISABLED", message = "status must be ENABLED or DISABLED")
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** Query string of {@code GET /api/product-catalog}. */
    public static class ListFilter {

        private String search;
        private String category;
        private String status;

        /**
         * Force a liveness re-check instead of serving the cached Last Seen.
         * The list endpoint is polled by the UI, so the default is the cached
         * value with a TTL — see {@code product-catalog.validation.liveness-ttl-seconds}.
         */
        private boolean refresh;

        public String getSearch() {
            return search;
        }

        public void setSearch(String search) {
            this.search = search;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public boolean isRefresh() {
            return refresh;
        }

        public void setRefresh(boolean refresh) {
            this.refresh = refresh;
        }

        @Override
        public String toString() {
            return "ListFilter{search=" + search + ", category=" + category
                    + ", status=" + status + ", refresh=" + refresh + '}';
        }
    }
}
