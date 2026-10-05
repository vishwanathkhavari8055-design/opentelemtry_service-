package com.trinity.observability.registry;

import java.time.Instant;

/**
 * One registered Grafana dashboard — the whole of what this application stores
 * about it.
 *
 * <p>There is no dashboard model here, and that is the design: Grafana owns the
 * definition, so this record holds only the identity (which UID), the placement
 * (which folder tile it appears under), and the switches an administrator
 * controls. Opening a dashboard always re-reads it from Grafana.</p>
 *
 * <h2>Two folders, not one</h2>
 *
 * <p>{@link #folderUid()} is where the dashboard appears in THIS application —
 * chosen at registration, and what the folder tiles are grouped by.
 * {@link #grafanaFolderUid()} is where it actually lives in Grafana, captured at
 * registration and re-read by a sync.</p>
 *
 * <p>They are almost always the same, and separating them is what makes the
 * difference visible when they are not: a dashboard moved to another folder in
 * Grafana keeps working and keeps its tile, and the UI can say where it went
 * instead of the tile quietly describing the wrong thing.</p>
 *
 * @param scopeKey who this registration belongs to — {@code "*"} under the default
 *                 GLOBAL scope, or the OpenObserve organization identifier under
 *                 ORG scope. See {@code GrafanaProperties.Registry.Scope}.
 */
public record DashboardRegistration(
        String scopeKey,
        String uid,
        String title,
        String folderUid,
        String folderTitle,
        String grafanaFolderUid,
        String grafanaFolderTitle,
        boolean enabled,
        int displayOrder,
        Instant registeredAt,
        Instant updatedAt) {

    /** The scope key used when registrations are shared across the deployment. */
    public static final String GLOBAL_SCOPE = "*";

    /** Re-read title and Grafana folder, leaving placement and switches alone. */
    public DashboardRegistration withGrafanaMetadata(String newTitle,
            String newGrafanaFolderUid, String newGrafanaFolderTitle, Instant now) {
        return new DashboardRegistration(scopeKey, uid, newTitle, folderUid, folderTitle,
                newGrafanaFolderUid, newGrafanaFolderTitle, enabled, displayOrder,
                registeredAt, now);
    }

    /** Move to another folder tile. */
    public DashboardRegistration withFolder(String newFolderUid, String newFolderTitle,
            Instant now) {
        return new DashboardRegistration(scopeKey, uid, title, newFolderUid, newFolderTitle,
                grafanaFolderUid, grafanaFolderTitle, enabled, displayOrder, registeredAt, now);
    }

    public DashboardRegistration withEnabled(boolean value, Instant now) {
        return new DashboardRegistration(scopeKey, uid, title, folderUid, folderTitle,
                grafanaFolderUid, grafanaFolderTitle, value, displayOrder, registeredAt, now);
    }

    public DashboardRegistration withDisplayOrder(int value, Instant now) {
        return new DashboardRegistration(scopeKey, uid, title, folderUid, folderTitle,
                grafanaFolderUid, grafanaFolderTitle, enabled, value, registeredAt, now);
    }

    /**
     * Whether the dashboard has since moved to a different Grafana folder than the
     * one it was filed from. Reported to the UI rather than corrected, because
     * which tile it appears under is an application decision that a Grafana move
     * should not silently override.
     */
    public boolean folderMismatch() {
        return grafanaFolderUid != null
                && !grafanaFolderUid.isBlank()
                && !grafanaFolderUid.equals(folderUid);
    }
}
