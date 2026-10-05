package com.trinity.observability.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.dto.dashboards.DashboardCatalogRequestDTO;
import com.trinity.observability.dto.dashboards.DashboardCatalogResponseDTO;

/**
 * The Dashboard Catalog: which Grafana dashboards this application exposes, and
 * everything the browser needs to render one.
 *
 * <h2>Registration is the gate</h2>
 *
 * <p>{@link #definition(String)} refuses a UID that is not registered, or is
 * registered and disabled — even though the service account could read it from
 * Grafana perfectly well. That is the point of the catalog: the Grafana instance
 * holds hundreds of dashboards belonging to other teams, and "reachable in this
 * application" has to be a decision someone made rather than a URL someone
 * guessed.</p>
 *
 * <p>{@link #query(JsonNode)} is the deliberate exception, and worth being explicit
 * about: it is a pass-through to Grafana's {@code /api/ds/query} that cannot check
 * which dashboard a query came from, because a query is just a datasource UID and
 * an expression. What it does enforce is that the caller can only reach datasources
 * Grafana already exposes to this service account — a Viewer token — so the worst
 * available is reading data the service account can read anyway.</p>
 */
public interface DashboardCatalogService {

    /** Whether the feature is configured, and against which Grafana. */
    DashboardCatalogResponseDTO.Health health();

    /** Registered dashboards in the current scope. */
    DashboardCatalogResponseDTO.RegistrationList list(boolean enabledOnly, String folderUid);

    /** One folder tile per folder holding registrations, with its counts. */
    DashboardCatalogResponseDTO.FolderTileList folderTiles();

    /** Folders a dashboard may be filed under. */
    DashboardCatalogResponseDTO.GrafanaFolderList folders();

    /** Dashboards in Grafana that are not registered yet. */
    DashboardCatalogResponseDTO.CandidateList available(
            DashboardCatalogRequestDTO.Available filter);

    /**
     * Register a dashboard UID into a folder.
     *
     * <p>Validated against Grafana first, so a typo is a 404 that says so rather
     * than a tile that opens onto nothing.</p>
     */
    DashboardCatalogResponseDTO.Registration register(DashboardCatalogRequestDTO.Register body);

    /** Move to another folder, enable/disable, or reorder. Null fields are left alone. */
    DashboardCatalogResponseDTO.Registration update(String uid,
            DashboardCatalogRequestDTO.Update body);

    /** Re-read the title and Grafana folder from Grafana. */
    DashboardCatalogResponseDTO.Registration refresh(String uid);

    /** Unregister. The dashboard itself is untouched in Grafana. */
    DashboardCatalogResponseDTO.WriteResult unregister(String uid);

    /**
     * The live definition of a REGISTERED dashboard, read fresh from Grafana.
     *
     * @throws com.trinity.observability.exception.ResourceNotFoundException if the
     *         UID is not registered, or no longer exists in Grafana
     */
    DashboardCatalogResponseDTO.Definition definition(String uid);

    /** Run panel queries. */
    DashboardCatalogResponseDTO.Query query(JsonNode payload);

    /** Datasources the browser may resolve a panel's reference against. */
    DashboardCatalogResponseDTO.DatasourceList datasources(String type);

    /** Values of a Prometheus label, for a dashboard template variable. */
    DashboardCatalogResponseDTO.LabelValues labelValues(String datasourceUid, String label,
            String metric, Long fromMs, Long toMs);
}
