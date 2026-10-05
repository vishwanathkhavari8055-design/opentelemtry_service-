package com.trinity.observability.dto.productcatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A registered monitored resource — the Product Catalog's unit of record.
 *
 * <p>This is the REGISTRY model, not the wire model: it is what
 * {@code ProductCatalogRegistry} stores and what {@code CatalogTelemetryFilter}
 * reads. {@code ProductCatalogResponseDTO.Resource} is the shape the UI sees,
 * and the two are mapped explicitly so a field can be added here without
 * changing the API contract (and vice versa).</p>
 *
 * <p>Deliberately a plain mutable POJO rather than a record: the liveness
 * refresher updates {@link #setLastSeenMicros(Long)} in place on a shared
 * instance, and enable/disable updates one field. Every write goes through the
 * registry, which is where the concurrency guarantees live.</p>
 */
public class CatalogResource {

    /** How a registration came to exist. */
    public enum Source {
        /** Registered through the API by an operator. */
        MANUAL,
        /** Seeded from {@code product-catalog.seed[*]} at startup. */
        CONFIG
    }

    private String id;
    private String org;
    private ResourceCategory category;

    /**
     * Which product of that category this belongs to, as a stable slug.
     *
     * <p>The middle tier: category → PRODUCT → microservice. Null for the
     * categories that have no product list — Tools, Databases, Platform and DLH
     * are registered by bare name, and inventing a product for them would put an
     * empty level in the tree for four of the seven categories.</p>
     *
     * <p>A code rather than the label because the labels contain spaces,
     * ampersands and en-dashes, none of which belong in something that travels in
     * a URL or a query parameter.</p>
     */
    private String productCode;

    /** The product's human label, resolved for display. Never persisted on the
     *  resource — it is read from the product table, so renaming a product does
     *  not require rewriting every resource registered under it. */
    private String productLabel;

    /** What the operator typed — the display name in the table. */
    private String resourceName;

    /**
     * The version in service, exactly as the operator typed it.
     *
     * <p>Free text, deliberately. Real estates carry "1.4.2", "2024.3", "v3-rc1"
     * and "latest" side by side; a structured version type would reject most of
     * those, and nothing here sorts or compares them.</p>
     */
    private String version;

    /**
     * The spelling telemetry actually uses for this resource, resolved during
     * validation. Filtering keys off this, never off {@link #resourceName}, so
     * a case difference between the form and the wire cannot silently widen or
     * narrow the filter.
     */
    private String resolvedName;

    private ResourceStatus status = ResourceStatus.ENABLED;

    /** Proven telemetry links. Empty only for a resource registered while
     *  validation was switched off. */
    private List<TelemetryBinding> bindings = new ArrayList<>();

    /** Signals that held data at validation time: {@code logs}/{@code traces}/{@code metrics}. */
    private Set<String> signals = new LinkedHashSet<>();

    /** Newest telemetry timestamp seen for this resource, epoch micros. */
    private Long lastSeenMicros;

    /** When {@link #lastSeenMicros} was last recomputed, epoch micros. Drives
     *  the refresh TTL so listing the catalog does not re-query OpenObserve on
     *  every poll. */
    private Long lastCheckedMicros;

    private long registeredAtMicros;
    private long updatedAtMicros;

    private Source source = Source.MANUAL;

    public CatalogResource() {
        // Bean-style construction; the service populates fields explicitly.
    }

    /** Defensive copy — the registry hands callers a snapshot, never its own
     *  instance, so a consumer cannot mutate stored state by accident. */
    public CatalogResource copy() {
        CatalogResource clone = new CatalogResource();
        clone.id = this.id;
        clone.org = this.org;
        clone.category = this.category;
        clone.productCode = this.productCode;
        clone.productLabel = this.productLabel;
        clone.resourceName = this.resourceName;
        clone.version = this.version;
        clone.resolvedName = this.resolvedName;
        clone.status = this.status;
        clone.bindings = new ArrayList<>(this.bindings);
        clone.signals = new LinkedHashSet<>(this.signals);
        clone.lastSeenMicros = this.lastSeenMicros;
        clone.lastCheckedMicros = this.lastCheckedMicros;
        clone.registeredAtMicros = this.registeredAtMicros;
        clone.updatedAtMicros = this.updatedAtMicros;
        clone.source = this.source;
        return clone;
    }

    /** The value telemetry queries should filter on. */
    public String effectiveName() {
        return (resolvedName == null || resolvedName.isBlank()) ? resourceName : resolvedName;
    }

    public boolean isEnabled() {
        return status == ResourceStatus.ENABLED;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrg() {
        return org;
    }

    public void setOrg(String org) {
        this.org = org;
    }

    public ResourceCategory getCategory() {
        return category;
    }

    public void setCategory(ResourceCategory category) {
        this.category = category;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = (productCode == null || productCode.isBlank()) ? null : productCode.trim();
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
        this.version = (version == null || version.isBlank()) ? null : version.trim();
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

    public ResourceStatus getStatus() {
        return status;
    }

    public void setStatus(ResourceStatus status) {
        this.status = status;
    }

    public List<TelemetryBinding> getBindings() {
        return bindings;
    }

    public void setBindings(List<TelemetryBinding> bindings) {
        this.bindings = bindings == null ? new ArrayList<>() : new ArrayList<>(bindings);
    }

    public Set<String> getSignals() {
        return signals;
    }

    public void setSignals(Set<String> signals) {
        this.signals = signals == null ? new LinkedHashSet<>() : new LinkedHashSet<>(signals);
    }

    public Long getLastSeenMicros() {
        return lastSeenMicros;
    }

    public void setLastSeenMicros(Long lastSeenMicros) {
        this.lastSeenMicros = lastSeenMicros;
    }

    public Long getLastCheckedMicros() {
        return lastCheckedMicros;
    }

    public void setLastCheckedMicros(Long lastCheckedMicros) {
        this.lastCheckedMicros = lastCheckedMicros;
    }

    public long getRegisteredAtMicros() {
        return registeredAtMicros;
    }

    public void setRegisteredAtMicros(long registeredAtMicros) {
        this.registeredAtMicros = registeredAtMicros;
    }

    public long getUpdatedAtMicros() {
        return updatedAtMicros;
    }

    public void setUpdatedAtMicros(long updatedAtMicros) {
        this.updatedAtMicros = updatedAtMicros;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "CatalogResource{id=" + id + ", org=" + org + ", category=" + category
                + ", resourceName=" + resourceName + ", status=" + status + '}';
    }
}
