package com.trinity.observability.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A registered Grafana dashboard, as a row.
 *
 * <p>Metadata only — see {@code DashboardRegistration} for why there is no
 * dashboard model stored anywhere. The table is deliberately narrow: it exists so
 * a registration survives a restart, not so this service can answer questions
 * about dashboards without Grafana.</p>
 *
 * <p>Hard delete rather than the soft delete {@code catalog_resources} uses.
 * Unregistering here removes nothing and hides nothing — the dashboard is
 * untouched in Grafana and can be registered again in one click — so there is no
 * audit trail worth the tombstone, and no unique-index-with-predicate to work
 * around.</p>
 */
@Entity
@Table(name = "grafana_dashboard_registrations")
public class GrafanaDashboardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenancy boundary: {@code "*"} when registrations are shared across the
     * deployment (the default), the organization identifier when they are not.
     * Stored rather than derived so switching {@code grafana.registry.scope} does
     * not silently reinterpret existing rows.
     */
    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;

    @Column(name = "dashboard_uid", nullable = false, length = 64)
    private String dashboardUid;

    @Column(name = "title", nullable = false, length = 512)
    private String title = "";

    /** Where the dashboard appears in THIS application. Blank means ungrouped. */
    @Column(name = "folder_uid", nullable = false, length = 64)
    private String folderUid = "";

    @Column(name = "folder_title", nullable = false, length = 512)
    private String folderTitle = "";

    /** Where it actually lives in Grafana, as of the last registration or sync. */
    @Column(name = "grafana_folder_uid", length = 64)
    private String grafanaFolderUid;

    @Column(name = "grafana_folder_title", length = 512)
    private String grafanaFolderTitle;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId()                          { return id; }
    public void setId(Long v)                    { this.id = v; }
    public String getScopeKey()                  { return scopeKey; }
    public void setScopeKey(String v)            { this.scopeKey = v; }
    public String getDashboardUid()              { return dashboardUid; }
    public void setDashboardUid(String v)        { this.dashboardUid = v; }
    public String getTitle()                     { return title; }
    public void setTitle(String v)               { this.title = v; }
    public String getFolderUid()                 { return folderUid; }
    public void setFolderUid(String v)           { this.folderUid = v; }
    public String getFolderTitle()               { return folderTitle; }
    public void setFolderTitle(String v)         { this.folderTitle = v; }
    public String getGrafanaFolderUid()          { return grafanaFolderUid; }
    public void setGrafanaFolderUid(String v)    { this.grafanaFolderUid = v; }
    public String getGrafanaFolderTitle()        { return grafanaFolderTitle; }
    public void setGrafanaFolderTitle(String v)  { this.grafanaFolderTitle = v; }
    public boolean isEnabled()                   { return enabled; }
    public void setEnabled(boolean v)            { this.enabled = v; }
    public int getDisplayOrder()                 { return displayOrder; }
    public void setDisplayOrder(int v)           { this.displayOrder = v; }
    public Instant getRegisteredAt()             { return registeredAt; }
    public void setRegisteredAt(Instant v)       { this.registeredAt = v; }
    public Instant getUpdatedAt()                { return updatedAt; }
    public void setUpdatedAt(Instant v)          { this.updatedAt = v; }
}
