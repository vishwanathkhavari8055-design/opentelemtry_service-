package com.trinity.observability.dto.productcatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire shapes for the Product Catalog API.
 *
 * <p>Separate from {@link CatalogResource} on purpose: the registry model can
 * grow a field (a probe detail, a cache stamp) without that field appearing in
 * the API, which is the property that lets a persistent store be introduced
 * later without an API change.</p>
 *
 * <p>Timestamps are ISO-8601 strings, matching every other response in this
 * service ({@code LogResponseDTO}, {@code ServiceUptimeItemDTO}); the UI never
 * has to know that the registry keeps epoch microseconds internally.</p>
 */
public final class ProductCatalogResponseDTO {

    private ProductCatalogResponseDTO() {
        // Container for the nested response shapes only.
    }

    /** One row of the Product Catalog table. */
    public static class Resource {

        private String id;
        private String category;
        private String categoryLabel;

        /** Product slug, or null for a category with no product list. */
        private String product;
        /** The product's human label — what the table and the tree show. */
        private String productLabel;

        private String resourceName;

        /** Version in service, exactly as it was entered. */
        private String version;
        private String resolvedName;
        private String status;

        /** ISO-8601, or null when no telemetry has been seen for this resource. */
        private String lastSeen;

        /** Seconds since {@link #lastSeen}, so the UI can render "3m ago"
         *  without trusting the browser clock against the server's. */
        private Long lastSeenAgoSec;

        private String registeredAt;
        private String updatedAt;

        /** Where the registration came from: {@code MANUAL} or {@code CONFIG}. */
        private String source;

        /** Per-signal availability, feeding the Enabled/Disabled checklist. */
        private Signals signals = new Signals();

        /** The identifier columns this resource was matched on, for the details view. */
        private List<Binding> bindings = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getCategoryLabel() {
            return categoryLabel;
        }

        public void setCategoryLabel(String categoryLabel) {
            this.categoryLabel = categoryLabel;
        }

        public String getProduct() {
            return product;
        }

        public void setProduct(String product) {
            this.product = product;
        }

        public String getProductLabel() {
            return productLabel;
        }

        public void setProductLabel(String productLabel) {
            this.productLabel = productLabel;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getResourceName() {
            return resourceName;
        }

        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        public String getResolvedName() {
            return resolvedName;
        }

        public void setResolvedName(String resolvedName) {
            this.resolvedName = resolvedName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getLastSeen() {
            return lastSeen;
        }

        public void setLastSeen(String lastSeen) {
            this.lastSeen = lastSeen;
        }

        public Long getLastSeenAgoSec() {
            return lastSeenAgoSec;
        }

        public void setLastSeenAgoSec(Long lastSeenAgoSec) {
            this.lastSeenAgoSec = lastSeenAgoSec;
        }

        public String getRegisteredAt() {
            return registeredAt;
        }

        public void setRegisteredAt(String registeredAt) {
            this.registeredAt = registeredAt;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Signals getSignals() {
            return signals;
        }

        public void setSignals(Signals signals) {
            this.signals = signals;
        }

        public List<Binding> getBindings() {
            return bindings;
        }

        public void setBindings(List<Binding> bindings) {
            this.bindings = bindings == null ? new ArrayList<>() : bindings;
        }
    }

    /**
     * One product in a category — an option in the register dialog's picker.
     *
     * <p>{@code code} is what a registration stores and {@code label} is what the
     * operator reads. They are separate because the labels carry spaces, ampersands
     * and en-dashes, and because a label can then be corrected without invalidating
     * the resources registered against it.</p>
     */
    public static class Product {

        private String code;
        private String label;
        /** Guidance from the source inventory, e.g. "Covered in trinityMOBILE". */
        private String note;
        private String category;
        private String categoryLabel;
        /** Live registrations under this product, so the picker shows what is filled in. */
        private int registeredCount;
        private int enabledCount;

        public String getCode()                  { return code; }
        public void setCode(String v)            { this.code = v; }
        public String getLabel()                 { return label; }
        public void setLabel(String v)           { this.label = v; }
        public String getNote()                  { return note; }
        public void setNote(String v)            { this.note = v; }
        public String getCategory()              { return category; }
        public void setCategory(String v)        { this.category = v; }
        public String getCategoryLabel()         { return categoryLabel; }
        public void setCategoryLabel(String v)   { this.categoryLabel = v; }
        public int getRegisteredCount()          { return registeredCount; }
        public void setRegisteredCount(int v)    { this.registeredCount = v; }
        public int getEnabledCount()             { return enabledCount; }
        public void setEnabledCount(int v)       { this.enabledCount = v; }
    }

    /** {@code GET /api/product-catalog/products} — the picker's options. */
    public static class ProductList {

        private List<Product> items = new ArrayList<>();
        private int total;
        private String org;
        /** False when the product tier has no store behind it, so the UI can say so
         *  rather than showing an empty dropdown that looks like a load failure. */
        private boolean supported = true;

        public List<Product> getItems()          { return items; }
        public void setItems(List<Product> v)    { this.items = v == null ? new ArrayList<>() : v; }
        public int getTotal()                    { return total; }
        public void setTotal(int v)              { this.total = v; }
        public String getOrg()                   { return org; }
        public void setOrg(String v)             { this.org = v; }
        public boolean isSupported()             { return supported; }
        public void setSupported(boolean v)      { this.supported = v; }
    }

    /**
     * Whether each signal is being surfaced for this resource.
     *
     * <p>Two facts are folded into one boolean per signal, and that is
     * intentional: the UI's checklist means "would you see this in Logs right
     * now", which is true only when telemetry EXISTS <em>and</em> the resource
     * is enabled. {@link #getAvailable()} keeps the raw discovery result so the
     * details view can distinguish "switched off" from "never emitted".</p>
     */
    public static class Signals {

        private boolean logs;
        private boolean traces;
        private boolean metrics;
        private List<String> available = new ArrayList<>();

        public boolean isLogs() {
            return logs;
        }

        public void setLogs(boolean logs) {
            this.logs = logs;
        }

        public boolean isTraces() {
            return traces;
        }

        public void setTraces(boolean traces) {
            this.traces = traces;
        }

        public boolean isMetrics() {
            return metrics;
        }

        public void setMetrics(boolean metrics) {
            this.metrics = metrics;
        }

        public List<String> getAvailable() {
            return available;
        }

        public void setAvailable(List<String> available) {
            this.available = available == null ? new ArrayList<>() : available;
        }
    }

    /** One matched identifier, as shown in View Details. */
    public static class Binding {

        private String signal;
        private String stream;
        private String column;
        private String value;

        public Binding() {
            // Jackson.
        }

        public Binding(String signal, String stream, String column, String value) {
            this.signal = signal;
            this.stream = stream;
            this.column = column;
            this.value = value;
        }

        public String getSignal() {
            return signal;
        }

        public void setSignal(String signal) {
            this.signal = signal;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** Body of {@code GET /api/product-catalog}. */
    public static class ResourceList {

        private List<Resource> items = new ArrayList<>();
        private int total;
        private String org;

        /** False when {@code openobserve.enabled=false} — the catalog cannot
         *  validate anything without its source of truth, so the UI says so
         *  rather than offering a Register button that always fails. */
        private boolean supported = true;

        /** The active filter mode, so the UI can explain what Disabled does. */
        private String filterMode;

        public List<Resource> getItems() {
            return items;
        }

        public void setItems(List<Resource> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public String getOrg() {
            return org;
        }

        public void setOrg(String org) {
            this.org = org;
        }

        public boolean isSupported() {
            return supported;
        }

        public void setSupported(boolean supported) {
            this.supported = supported;
        }

        public String getFilterMode() {
            return filterMode;
        }

        public void setFilterMode(String filterMode) {
            this.filterMode = filterMode;
        }
    }

    /** Body of {@code POST /api/product-catalog/validate}. */
    public static class ValidationResult {

        private boolean found;
        private String category;
        private String resourceName;

        /** The spelling telemetry uses, when it differs from what was typed.
         *  Shown in the dialog so the operator can see what will be registered. */
        private String resolvedName;

        private Signals signals = new Signals();
        private List<Binding> bindings = new ArrayList<>();
        private String lastSeen;

        /** Human-readable outcome. On failure this is the exact wording the
         *  requirement specifies, so the dialog can render it verbatim. */
        private String message;

        /** ISO-8601 bounds of the window the probes searched, so "not found"
         *  is falsifiable rather than mysterious. */
        private String windowStart;
        private String windowEnd;

        /** False when OpenObserve is switched off. */
        private boolean supported = true;

        /** True when the resource is already in the catalog — the dialog
         *  disables Register rather than letting it 409. */
        private boolean alreadyRegistered;

        public boolean isFound() {
            return found;
        }

        public void setFound(boolean found) {
            this.found = found;
        }

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

        public String getResolvedName() {
            return resolvedName;
        }

        public void setResolvedName(String resolvedName) {
            this.resolvedName = resolvedName;
        }

        public Signals getSignals() {
            return signals;
        }

        public void setSignals(Signals signals) {
            this.signals = signals;
        }

        public List<Binding> getBindings() {
            return bindings;
        }

        public void setBindings(List<Binding> bindings) {
            this.bindings = bindings == null ? new ArrayList<>() : bindings;
        }

        public String getLastSeen() {
            return lastSeen;
        }

        public void setLastSeen(String lastSeen) {
            this.lastSeen = lastSeen;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(String windowStart) {
            this.windowStart = windowStart;
        }

        public String getWindowEnd() {
            return windowEnd;
        }

        public void setWindowEnd(String windowEnd) {
            this.windowEnd = windowEnd;
        }

        public boolean isSupported() {
            return supported;
        }

        public void setSupported(boolean supported) {
            this.supported = supported;
        }

        public boolean isAlreadyRegistered() {
            return alreadyRegistered;
        }

        public void setAlreadyRegistered(boolean alreadyRegistered) {
            this.alreadyRegistered = alreadyRegistered;
        }
    }

    /** Body of {@code GET /api/product-catalog/categories}. */
    public static class CategoryList {

        private List<Category> items = new ArrayList<>();

        public List<Category> getItems() {
            return items;
        }

        public void setItems(List<Category> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }
    }

    /** One category, with how many resources are currently filed under it. */
    public static class Category {

        private String code;
        private String label;
        private String description;
        private int registeredCount;
        private int enabledCount;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public int getRegisteredCount() {
            return registeredCount;
        }

        public void setRegisteredCount(int registeredCount) {
            this.registeredCount = registeredCount;
        }

        public int getEnabledCount() {
            return enabledCount;
        }

        public void setEnabledCount(int enabledCount) {
            this.enabledCount = enabledCount;
        }
    }

    /** Body of the mutating routes: enable, disable and delete. */
    public static class WriteResult {

        private boolean ok = true;
        private String id;
        private String message;

        /** Present for enable/disable, absent for delete. */
        private Resource resource;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Resource getResource() {
            return resource;
        }

        public void setResource(Resource resource) {
            this.resource = resource;
        }
    }
}
