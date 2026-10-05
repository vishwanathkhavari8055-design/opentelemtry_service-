package com.trinity.observability.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.dto.alerts.AlertsRequestDTO;
import com.trinity.observability.dto.alerts.AlertsResponseDTO;

/**
 * Alert definitions, their folders, and the destinations/templates they notify.
 *
 * <p>Unlike every other service here, this one WRITES. Alerts are authored in
 * this UI and in OpenObserve's own UI interchangeably, and both must see the
 * same definitions — there is one store (OpenObserve's
 * {@code /api/v2/{org}/alerts}) and this service is a thin, faithful conduit to
 * it. Nothing is cached and nothing is mirrored locally, because a second copy
 * of an alert definition is a second answer to "will this page me at 3am".</p>
 *
 * <p>Everything is scoped to the request's organization via
 * {@link com.trinity.observability.config.OpenObserveConfig#getOrg()}, same as
 * the read-only services.</p>
 */
public interface AlertsService {

    /**
     * List alerts matching the given filters.
     *
     * @param filter never null; unset fields mean "no filter on this"
     * @return typed rows for the table; empty (supported=false) on the ES path
     */
    AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> list(AlertsRequestDTO.ListFilter filter);

    /**
     * Fetch one alert's full definition for editing.
     *
     * <p>The document is returned verbatim — see the note on
     * {@link AlertsResponseDTO.Detail}.</p>
     */
    AlertsResponseDTO.Detail get(String alertId);

    /**
     * Create an alert from a complete alert document.
     *
     * @param alert    OpenObserve alert document; forwarded as-is
     * @param folderId destination folder, or null/"default" for the root folder
     */
    AlertsResponseDTO.WriteResult create(JsonNode alert, String folderId);

    /** Replace an existing alert with the given document. */
    AlertsResponseDTO.WriteResult update(String alertId, JsonNode alert, String folderId);

    AlertsResponseDTO.WriteResult delete(String alertId);

    /** Enable or disable without touching the rest of the definition. */
    AlertsResponseDTO.WriteResult setEnabled(String alertId, boolean enabled);

    /** Evaluate the alert now, regardless of its schedule. */
    AlertsResponseDTO.WriteResult trigger(String alertId);

    /**
     * Copy an alert.
     *
     * @param name     new name, or null for OpenObserve's {@code <name>_copy}
     * @param folderId target folder, or null for the source alert's folder
     */
    AlertsResponseDTO.WriteResult clone(String alertId, String name, String folderId);

    /** Move alerts between folders. */
    AlertsResponseDTO.WriteResult move(List<String> alertIds, String targetFolderId);

    /**
     * Import one or more alert documents into a folder.
     *
     * <p>Per-document outcomes rather than a single pass/fail — see
     * {@link AlertsResponseDTO.ImportOutcome}.</p>
     */
    List<AlertsResponseDTO.ImportOutcome> importAlerts(List<JsonNode> alerts, String folderId);

    // ── Folders ─────────────────────────────────────────────────────────────

    AlertsResponseDTO.Page<AlertsResponseDTO.Folder> listFolders();

    AlertsResponseDTO.WriteResult createFolder(String name, String description);

    AlertsResponseDTO.WriteResult updateFolder(String folderId, String name, String description);

    AlertsResponseDTO.WriteResult deleteFolder(String folderId);

    // ── Notification targets ────────────────────────────────────────────────

    AlertsResponseDTO.Page<AlertsResponseDTO.Destination> listDestinations();

    AlertsResponseDTO.WriteResult createDestination(JsonNode destination);

    AlertsResponseDTO.Page<AlertsResponseDTO.Template> listTemplates();

    // ── Stream metadata, for the editor's dropdowns ─────────────────────────

    /**
     * Stream names of the given type, for the editor's "Stream Name" select.
     *
     * @param streamType "logs" | "metrics" | "traces"
     */
    List<String> listStreams(String streamType);

    /**
     * Field names on a stream, for the condition builder's column select.
     *
     * <p>An empty list is a legitimate answer: OpenObserve returns no schema for
     * a stream that has not been written to yet, and the UI falls back to a
     * free-text column input rather than blocking the user.</p>
     */
    List<String> listStreamFields(String streamType, String streamName);

    /**
     * The same schema as {@link #listStreamFields}, but typed.
     *
     * <p>Kept as a SEPARATE route rather than widening {@code /fields}: the
     * condition builder is fed by that endpoint and only ever wanted names, and
     * changing its element type would break every caller of it — including an
     * older frontend served from a cached bundle — to add information one new
     * control needs.</p>
     *
     * <p>Empty for a stream with no schema yet, exactly as {@code /fields} is.</p>
     */
    List<AlertsResponseDTO.StreamField> listStreamSchema(String streamType, String streamName);

    // ── Composite alerts ──────────────────────────────────────────

    /**
     * Check a composite alert's boolean expression, and evaluate it as it stands.
     *
     * <p>A composite alert fires on the CURRENT STATES of other alerts and never
     * queries a stream itself, so nothing about it can be validated locally: the
     * expression names other alerts by id, and only OpenObserve knows whether
     * those exist, whether this caller may read them, whether they are eligible
     * to be composed at all, and whether adding this composite would close a
     * cycle in the reference graph. This forwards the question.</p>
     *
     * <p>A REJECTED expression is a normal answer, not an exception — see
     * {@link AlertsResponseDTO.CompositeValidation}. The editor calls this on
     * every edit to drive its live preview, so a rejection has to come back as
     * data the preview can render.</p>
     *
     * @param compositeCondition the {@code {expression, stale_child_policy,
     *                           warning_counts_as_firing}} object, forwarded
     *                           verbatim
     * @param compositeId        the composite being edited, so the graph check
     *                           can exempt it from its own cycle test; null when
     *                           the alert does not exist yet
     */
    AlertsResponseDTO.CompositeValidation validateComposite(JsonNode compositeCondition,
                                                            String compositeId);
}
