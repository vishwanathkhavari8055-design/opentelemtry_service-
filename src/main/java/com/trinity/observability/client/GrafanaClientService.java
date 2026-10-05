package com.trinity.observability.client;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything this service asks of Grafana, and nothing more.
 *
 * <p>Six reads. There is no write on this interface at all, which is the point:
 * the Dashboard Catalog registers UIDs, it never authors or edits a dashboard, so
 * a Viewer-role service-account token is the whole privilege the feature needs and
 * an Editor token would only widen the blast radius of a leak.</p>
 *
 * <p>The three methods that back the RENDERER — {@link #dashboard},
 * {@link #query} and {@link #labelValues} — are deliberately shaped as
 * pass-throughs over Grafana's own API rather than as a translated model. That is
 * what makes the viewer generic: a dashboard's JSON reaches the browser exactly as
 * Grafana holds it, so support for a new panel type or a new datasource is a
 * frontend renderer and not a change here.</p>
 */
public interface GrafanaClientService {

    /** One row of {@code /api/search?type=dash-db}. */
    record DashboardHit(
            String uid,
            String title,
            String url,
            String folderUid,
            String folderTitle,
            List<String> tags) {
    }

    /**
     * One folder, at any depth.
     *
     * @param parentUid the folder this one sits inside, or blank at the root. Grafana
     *                  spells this {@code parentUid} on {@code /api/folders} and
     *                  {@code folderUid} on {@code /api/search}; both are read into
     *                  this one field.
     */
    record FolderHit(String uid, String title, String parentUid) {
    }

    /**
     * A dashboard as Grafana holds it.
     *
     * @param dashboard the untouched dashboard model — this is what the browser
     *                  turns into a Scene, so nothing about it is normalised here
     * @param version   Grafana's revision counter, surfaced so the UI can show
     *                  which revision it is looking at
     */
    record DashboardDefinition(
            String uid,
            String title,
            String folderUid,
            String folderTitle,
            Integer version,
            JsonNode dashboard,
            JsonNode meta) {
    }

    /**
     * A datasource, reduced to the three fields a panel query needs to name one.
     * Deliberately NOT the full Grafana record: that carries URLs, usernames and
     * {@code secureJsonFields}, none of which the browser has any business seeing.
     */
    record DatasourceHit(String uid, String name, String type) {
    }

    /**
     * Dashboards visible to the service account.
     *
     * @param folderUid restrict to one folder, or null/blank for the whole instance
     * @param query     case-insensitive title filter, or null/blank for no filter
     */
    List<DashboardHit> searchDashboards(String folderUid, String query);

    /**
     * Every folder the service account can see, at every depth, each naming its
     * parent.
     *
     * <p>Nested folders included — which takes more than {@code /api/folders},
     * since that endpoint answers with root-level folders only. See the
     * implementation for how the tree is enumerated without one call per folder.</p>
     */
    List<FolderHit> listFolders();

    /**
     * One dashboard's full definition.
     *
     * @return null when Grafana has no dashboard with that UID — a stale
     *         registration is an expected state, not an upstream failure
     */
    DashboardDefinition dashboard(String uid);

    /**
     * Datasources, optionally narrowed to one plugin type.
     *
     * <p>Backs the browser's datasource resolution: a dashboard can reference a
     * datasource by name or through a {@code ${DS_X}} variable, and turning either
     * into a concrete UID needs this catalog.</p>
     */
    List<DatasourceHit> datasources(String type);

    /**
     * Run panel queries: a pass-through to Grafana's {@code /api/ds/query}.
     *
     * <p>The payload is the browser's, verbatim. That is what lets one code path
     * serve every datasource type on the instance — Prometheus, Elasticsearch,
     * MSSQL, InfluxDB — because Grafana, not this service, knows how to execute
     * each of them.</p>
     */
    JsonNode query(JsonNode payload);

    /**
     * Values of a Prometheus label, for a dashboard template variable.
     *
     * <p>Proxied through {@code /api/datasources/proxy} so the datasource's own URL
     * and credentials stay inside Grafana.</p>
     *
     * @param metric optional metric to narrow the label to ({@code match[]})
     * @param fromMs optional window start in epoch millis
     * @param toMs   optional window end in epoch millis
     */
    List<String> labelValues(String dsUid, String label, String metric, Long fromMs, Long toMs);
}
