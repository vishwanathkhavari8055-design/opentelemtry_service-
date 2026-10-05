package com.trinity.observability.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A registered resource, in PostgreSQL.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The registry used to be an in-memory map made durable by an append-only
 * OpenObserve stream, and the argument for that was "no database, no migration".
 * It held until the OpenObserve address changed and the catalog emptied itself,
 * because the change log lived inside the instance that went away. PostgreSQL was
 * already the source of truth for alert rules and alerts; the catalog being the
 * one thing stored somewhere else was the reason it was also the one thing that
 * did not survive.</p>
 *
 * <p>The stream store is still there and is one property away
 * ({@code product-catalog.store=MEMORY}), so nothing was removed.</p>
 *
 * <h2>Encoding matches the stream store, on purpose</h2>
 *
 * <p>{@code signals} is comma-separated and {@code bindings} is one
 * {@code signal|stream|column|value} per line — byte-identical to what
 * {@code OpenObserveCatalogSnapshotStore} writes. That keeps the two stores
 * interchangeable: the same decoder reads either, so switching between them is a
 * property change rather than a data migration.</p>
 */
@Entity
@Table(name = "catalog_resources")
public class CatalogResourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The public identifier.
     *
     * <p>Kept as a UUID because the API has always handed one out and the UI holds
     * it in every table row and drawer — swapping in the bigint primary key would
     * break every client at once for no gain.</p>
     */
    @Column(name = "resource_uuid", nullable = false, updatable = false)
    private UUID resourceUuid;

    @Column(name = "org", nullable = false, length = 64)
    private String org;

    /** Category enum NAME, e.g. {@code MOBILE_APPLICATION}. */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    /** Product slug, or null for the categories that have no product list. */
    @Column(name = "product_code", length = 128)
    private String productCode;

    @Column(name = "resource_name", nullable = false)
    private String resourceName;

    @Column(name = "resolved_name")
    private String resolvedName;

    /** Free text — "1.4.2", "2024.3", "v3-rc1" and "latest" all occur. */
    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ENABLED";

    @Column(name = "source", nullable = false, length = 16)
    private String source = "MANUAL";

    @Column(name = "signals", columnDefinition = "text")
    private String signals;

    @Column(name = "bindings", columnDefinition = "text")
    private String bindings;

    @Column(name = "last_seen_micros")
    private Long lastSeenMicros;

    @Column(name = "registered_at_micros", nullable = false)
    private long registeredAtMicros;

    @Column(name = "updated_at_micros", nullable = false)
    private long updatedAtMicros;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Soft delete.
     *
     * <p>Unregistering has to stay distinguishable from never having registered:
     * the same name is legitimately registered again later, and a hard delete would
     * lose that the first one ever happened. It is also what lets the unique index
     * be partial, so a removed name can be reused.</p>
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long getId()                        { return id; }
    public void setId(Long v)                  { this.id = v; }
    public UUID getResourceUuid()              { return resourceUuid; }
    public void setResourceUuid(UUID v)        { this.resourceUuid = v; }
    public String getOrg()                     { return org; }
    public void setOrg(String v)               { this.org = v; }
    public String getCategory()                { return category; }
    public void setCategory(String v)          { this.category = v; }
    public String getProductCode()             { return productCode; }
    public void setProductCode(String v)       { this.productCode = v; }
    public String getResourceName()            { return resourceName; }
    public void setResourceName(String v)      { this.resourceName = v; }
    public String getResolvedName()            { return resolvedName; }
    public void setResolvedName(String v)      { this.resolvedName = v; }
    public String getVersion()                 { return version; }
    public void setVersion(String v)           { this.version = v; }
    public String getStatus()                  { return status; }
    public void setStatus(String v)            { this.status = v; }
    public String getSource()                  { return source; }
    public void setSource(String v)            { this.source = v; }
    public String getSignals()                 { return signals; }
    public void setSignals(String v)           { this.signals = v; }
    public String getBindings()                { return bindings; }
    public void setBindings(String v)          { this.bindings = v; }
    public Long getLastSeenMicros()            { return lastSeenMicros; }
    public void setLastSeenMicros(Long v)      { this.lastSeenMicros = v; }
    public long getRegisteredAtMicros()        { return registeredAtMicros; }
    public void setRegisteredAtMicros(long v)  { this.registeredAtMicros = v; }
    public long getUpdatedAtMicros()           { return updatedAtMicros; }
    public void setUpdatedAtMicros(long v)     { this.updatedAtMicros = v; }
    public String getCreatedBy()               { return createdBy; }
    public void setCreatedBy(String v)         { this.createdBy = v; }
    public String getUpdatedBy()               { return updatedBy; }
    public void setUpdatedBy(String v)         { this.updatedBy = v; }
    public Instant getCreatedAt()              { return createdAt; }
    public void setCreatedAt(Instant v)        { this.createdAt = v; }
    public Instant getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(Instant v)        { this.updatedAt = v; }
    public Instant getDeletedAt()              { return deletedAt; }
    public void setDeletedAt(Instant v)        { this.deletedAt = v; }
}
