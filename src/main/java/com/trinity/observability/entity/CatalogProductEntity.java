package com.trinity.observability.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One product within a category — the middle tier of the Product Catalog.
 *
 * <p>Reference data, seeded by {@code V4__product_catalog_postgres.sql} and
 * extended by INSERT rather than by a release. It is NOT org-scoped: the product
 * taxonomy is the same catalogue of software whichever tenant is looking, and what
 * differs per tenant is which of its microservices are registered.</p>
 *
 * <p>{@code code} is a stable ASCII slug and {@code label} is what an operator
 * recognises. They are separate because the labels carry spaces, ampersands and
 * en-dashes — fine on screen, wrong in a URL or a query parameter — and because a
 * label can then be corrected without invalidating every resource registered
 * against it.</p>
 */
@Entity
@Table(name = "catalog_products")
public class CatalogProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The owning category, stored as the enum NAME (e.g. {@code AI_SERVICES}). */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "code", nullable = false, length = 128)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    /**
     * Guidance carried from the source inventory, e.g. "Covered in trinityMOBILE".
     *
     * <p>Shown next to the option so whoever is registering sees it at the moment
     * of choosing. It is a note, not a relation this schema pretends to model —
     * expressing it as a foreign key would claim a precision the source list does
     * not have.</p>
     */
    @Column(name = "note")
    private String note;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** False hides a product from the picker without deleting it, so resources
     *  already registered against it keep resolving their label. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private java.time.Instant createdAt;

    public Long getId()                  { return id; }
    public void setId(Long v)            { this.id = v; }
    public String getCategory()          { return category; }
    public void setCategory(String v)    { this.category = v; }
    public String getCode()              { return code; }
    public void setCode(String v)        { this.code = v; }
    public String getLabel()             { return label; }
    public void setLabel(String v)       { this.label = v; }
    public String getNote()              { return note; }
    public void setNote(String v)        { this.note = v; }
    public int getSortOrder()            { return sortOrder; }
    public void setSortOrder(int v)      { this.sortOrder = v; }
    public boolean isActive()            { return active; }
    public void setActive(boolean v)     { this.active = v; }
    public java.time.Instant getCreatedAt()   { return createdAt; }
    public void setCreatedAt(java.time.Instant v) { this.createdAt = v; }
}
