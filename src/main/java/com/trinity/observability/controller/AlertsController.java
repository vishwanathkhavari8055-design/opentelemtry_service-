package com.trinity.observability.controller;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.dto.alerts.AlertScopeResponseDTO;
import com.trinity.observability.dto.alerts.AlertsRequestDTO;
import com.trinity.observability.dto.alerts.AlertsResponseDTO;
import com.trinity.observability.service.AlertScopeService;
import com.trinity.observability.service.AlertsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Alert definitions, folders and notification targets for the UI's Alerts screen.
 *
 * <p>READ AND WRITE, unlike the rest of this service. There is exactly one store
 * for an alert definition — OpenObserve's — and this controller is a conduit to
 * it, so an alert created here appears in OpenObserve immediately and one created
 * in OpenObserve appears here on the next list. Nothing is duplicated locally; a
 * second copy of an alert definition would be a second answer to "will this page
 * someone at 3am".</p>
 *
 * <p>Every route is scoped to the request's organization, taken from {@code ?org=}
 * (or {@code X-Org-Id}) by OrgContextFilter, exactly as elsewhere.</p>
 */
@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Alerts API", description = "Alert definitions, folders, destinations and templates")
public class AlertsController {

    private static final Logger log = LogManager.getLogger(AlertsController.class);

    private final AlertsService alertsService;

    /**
     * Used by {@code /streams} ONLY, and only when a scope is asked for.
     *
     * <p>This controller is otherwise a pass-through to OpenObserve and stays
     * one. The stream dropdown is the single place where an editor already
     * calling this route needs an answer that OpenObserve cannot give — which
     * of these streams belongs to the service I just picked — and making them
     * move to a different endpoint to get it would mean rewriting a control
     * that works rather than passing two more parameters to it.</p>
     */
    private final AlertScopeService scopes;

    public AlertsController(AlertsService alertsService, AlertScopeService scopes) {
        this.alertsService = alertsService;
        this.scopes = scopes;
    }

    // ── Alerts ──────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List alerts", description =
            "Filters are all optional. Omit `folder` to list across every folder.")
    public AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> list(
            @RequestParam(name = "folder", required = false) String folder,
            @RequestParam(name = "alertType", required = false) String alertType,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "streamType", required = false) String streamType,
            @RequestParam(name = "streamName", required = false) String streamName,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(name = "enabled", required = false) Boolean enabled,
            @RequestParam(name = "pageSize", required = false) Integer pageSize,
            @RequestParam(name = "pageIdx", required = false) Integer pageIdx) {

        long t0 = System.nanoTime();
        log.info("GET /api/alerts received (folder={}, alertType={}, search={})",
                folder, alertType, search);

        AlertsRequestDTO.ListFilter f = new AlertsRequestDTO.ListFilter();
        f.setFolder(folder);
        f.setAlertType(alertType);
        f.setSearch(search);
        f.setStreamType(streamType);
        f.setStreamName(streamName);
        f.setOwner(owner);
        f.setEnabled(enabled);
        f.setPageSize(pageSize);
        f.setPageIdx(pageIdx);

        AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> result = alertsService.list(f);
        log.info("GET /api/alerts returned {} alert(s) for org={} (supported={}) in {} ms",
                result.getItems().size(), result.getOrg(), result.isSupported(),
                (System.nanoTime() - t0) / 1_000_000L);
        return result;
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "Get one alert's full definition", description =
            "The alert document is returned verbatim so the editor can round-trip "
            + "fields this service does not model.")
    public AlertsResponseDTO.Detail get(@PathVariable("alertId") String alertId) {
        log.info("GET /api/alerts/{} received", alertId);
        return alertsService.get(alertId);
    }

    @PostMapping
    @Operation(summary = "Create an alert")
    public AlertsResponseDTO.WriteResult create(@RequestBody AlertsRequestDTO.SaveAlert body) {
        log.info("POST /api/alerts received (folder={})", body.getFolderId());
        return alertsService.create(body.getAlert(), body.getFolderId());
    }

    @PutMapping("/{alertId}")
    @Operation(summary = "Replace an alert")
    public AlertsResponseDTO.WriteResult update(
            @PathVariable("alertId") String alertId,
            @RequestBody AlertsRequestDTO.SaveAlert body) {
        log.info("PUT /api/alerts/{} received (folder={})", alertId, body.getFolderId());
        return alertsService.update(alertId, body.getAlert(), body.getFolderId());
    }

    @DeleteMapping("/{alertId}")
    @Operation(summary = "Delete an alert")
    public AlertsResponseDTO.WriteResult delete(@PathVariable("alertId") String alertId) {
        log.info("DELETE /api/alerts/{} received", alertId);
        return alertsService.delete(alertId);
    }

    @PatchMapping("/{alertId}/enable")
    @Operation(summary = "Enable or disable an alert", description =
            "Leaves the rest of the definition untouched.")
    public AlertsResponseDTO.WriteResult setEnabled(
            @PathVariable("alertId") String alertId,
            @RequestParam(name = "value") boolean value) {
        log.info("PATCH /api/alerts/{}/enable?value={} received", alertId, value);
        return alertsService.setEnabled(alertId, value);
    }

    @PatchMapping("/{alertId}/trigger")
    @Operation(summary = "Evaluate an alert now, ignoring its schedule")
    public AlertsResponseDTO.WriteResult trigger(@PathVariable("alertId") String alertId) {
        log.info("PATCH /api/alerts/{}/trigger received", alertId);
        return alertsService.trigger(alertId);
    }

    @PostMapping("/{alertId}/clone")
    @Operation(summary = "Copy an alert")
    public AlertsResponseDTO.WriteResult clone(
            @PathVariable("alertId") String alertId,
            @RequestBody(required = false) AlertsRequestDTO.CloneAlert body) {
        String name = body == null ? null : body.getName();
        String folderId = body == null ? null : body.getFolderId();
        log.info("POST /api/alerts/{}/clone received (name={}, folder={})", alertId, name, folderId);
        return alertsService.clone(alertId, name, folderId);
    }

    @PatchMapping("/move")
    @Operation(summary = "Move alerts to another folder")
    public AlertsResponseDTO.WriteResult move(@RequestBody AlertsRequestDTO.MoveAlerts body) {
        log.info("PATCH /api/alerts/move received ({} alert(s) -> {})",
                body.getAlertIds() == null ? 0 : body.getAlertIds().size(), body.getTargetFolderId());
        return alertsService.move(body.getAlertIds(), body.getTargetFolderId());
    }

    @PostMapping("/import")
    @Operation(summary = "Import one or more alert documents", description =
            "Per-document outcomes: one malformed entry does not discard the rest.")
    public List<AlertsResponseDTO.ImportOutcome> importAlerts(
            @RequestBody AlertsRequestDTO.ImportAlerts body) {
        int count = body.getAlerts() == null ? 0 : body.getAlerts().size();
        log.info("POST /api/alerts/import received ({} document(s) -> folder={})",
                count, body.getFolderId());
        return alertsService.importAlerts(body.getAlerts(), body.getFolderId());
    }

    // ── Folders ─────────────────────────────────────────────────────────────

    @GetMapping("/folders")
    @Operation(summary = "List alert folders", description =
            "Excludes OpenObserve's root folder, which is addressed by the literal "
            + "id \"default\" and is not returned by the upstream endpoint.")
    public AlertsResponseDTO.Page<AlertsResponseDTO.Folder> listFolders() {
        log.info("GET /api/alerts/folders received");
        return alertsService.listFolders();
    }

    @PostMapping("/folders")
    @Operation(summary = "Create an alert folder")
    public AlertsResponseDTO.WriteResult createFolder(
            @RequestBody AlertsRequestDTO.SaveFolder body) {
        log.info("POST /api/alerts/folders received (name={})", body.getName());
        return alertsService.createFolder(body.getName(), body.getDescription());
    }

    @PutMapping("/folders/{folderId}")
    @Operation(summary = "Rename an alert folder")
    public AlertsResponseDTO.WriteResult updateFolder(
            @PathVariable("folderId") String folderId,
            @RequestBody AlertsRequestDTO.SaveFolder body) {
        log.info("PUT /api/alerts/folders/{} received", folderId);
        return alertsService.updateFolder(folderId, body.getName(), body.getDescription());
    }

    @DeleteMapping("/folders/{folderId}")
    @Operation(summary = "Delete an alert folder")
    public AlertsResponseDTO.WriteResult deleteFolder(@PathVariable("folderId") String folderId) {
        log.info("DELETE /api/alerts/folders/{} received", folderId);
        return alertsService.deleteFolder(folderId);
    }

    // ── Notification targets ────────────────────────────────────────────────

    @GetMapping("/destinations")
    @Operation(summary = "List notification destinations")
    public AlertsResponseDTO.Page<AlertsResponseDTO.Destination> listDestinations() {
        log.info("GET /api/alerts/destinations received");
        return alertsService.listDestinations();
    }

    @PostMapping("/destinations")
    @Operation(summary = "Create a notification destination", description =
            "A template is required — without one OpenObserve creates a pipeline "
            + "destination, which alerts cannot use.")
    public AlertsResponseDTO.WriteResult createDestination(@RequestBody JsonNode body) {
        log.info("POST /api/alerts/destinations received");
        return alertsService.createDestination(body);
    }

    @GetMapping("/templates")
    @Operation(summary = "List notification templates")
    public AlertsResponseDTO.Page<AlertsResponseDTO.Template> listTemplates() {
        log.info("GET /api/alerts/templates received");
        return alertsService.listTemplates();
    }

    // ── Stream metadata for the editor's dropdowns ──────────────────────────

    @GetMapping("/streams")
    @Operation(summary = "Stream names of a given type", description = """
            Populates the editor's Stream Name select. type = logs | metrics | traces. \
            Without scopeType/scopeKey this is exactly what it has always been: every \
            stream of that type, unchanged.

            Supply scopeType + scopeKey (from GET /api/alert-builder/scopes) and the list \
            narrows to the streams that actually carry that service, tool or product. For \
            metrics that means the metric names it emits — a metric is its own stream in \
            OpenObserve — and for logs and traces it means the streams a probe found rows \
            in over the last day.

            Check `scoped`. TRUE means the list really was narrowed. FALSE means the \
            narrowing could not be worked out and you are looking at everything, with the \
            reason in `notice` — a stream picked from that list may be one the service \
            never writes to, and a rule on it would validate, sync, report itself healthy \
            and never fire.""")
    public AlertsResponseDTO.Page<String> listStreams(
            @RequestParam(name = "type", defaultValue = "logs") String type,
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "scopeKey", required = false) String scopeKey) {

        log.info("GET /api/alerts/streams?type={}&scopeType={}&scopeKey={} received",
                type, scopeType, scopeKey);

        if (scopeType == null || scopeType.isBlank() || scopeKey == null || scopeKey.isBlank()) {
            // The untouched path. Every existing caller lands here and gets the
            // identical response it did before this parameter existed — which is
            // the whole point of making the scope optional rather than a new
            // required argument.
            return stringPage(alertsService.listStreams(type));
        }

        AlertScopeResponseDTO.TargetList targets =
                scopes.listTargets(scopeType, scopeKey, type, null);

        List<String> names = new ArrayList<>();
        targets.getItems().forEach(t -> names.add(t.getName()));

        AlertsResponseDTO.Page<String> page = stringPage(names);
        page.setScoped(targets.isScoped());
        page.setNotice(targets.getNotice());
        return page;
    }

    @GetMapping("/streams/{streamName}/fields")
    @Operation(summary = "Field names on a stream", description =
            "Populates the condition builder's column select. An empty list is "
            + "normal for a stream that has not been written to yet.")
    public AlertsResponseDTO.Page<String> listStreamFields(
            @PathVariable("streamName") String streamName,
            @RequestParam(name = "type", defaultValue = "logs") String type) {
        log.info("GET /api/alerts/streams/{}/fields?type={} received", streamName, type);
        return stringPage(alertsService.listStreamFields(type, streamName));
    }

    @GetMapping("/streams/{streamName}/schema")
    @Operation(summary = "Field names AND types on a stream", description =
            "Populates the alert wizard's aggregation-column select, which may "
            + "only offer numeric columns. /fields is unchanged and still answers "
            + "with bare names for the condition builder.")
    public AlertsResponseDTO.Page<AlertsResponseDTO.StreamField> listStreamSchema(
            @PathVariable("streamName") String streamName,
            @RequestParam(name = "type", defaultValue = "logs") String type) {
        log.info("GET /api/alerts/streams/{}/schema?type={} received", streamName, type);
        List<AlertsResponseDTO.StreamField> fields = alertsService.listStreamSchema(type, streamName);
        AlertsResponseDTO.Page<AlertsResponseDTO.StreamField> p = new AlertsResponseDTO.Page<>();
        p.setItems(new ArrayList<>(fields));
        p.setSupported(true);
        p.setTotal(fields.size());
        return p;
    }

    @PostMapping("/composites/validate")
    @Operation(summary = "Check a composite alert's boolean expression", description =
            "A composite alert combines the CURRENT STATES of other alerts and never "
            + "queries a stream, so only OpenObserve can say whether an expression is "
            + "usable — it has to resolve every referenced alert, check access, check "
            + "eligibility and check the reference graph stays acyclic. A rejected "
            + "expression comes back as valid=false with a message, NOT as an error: "
            + "the editor calls this on every keystroke to drive its live preview.")
    public AlertsResponseDTO.CompositeValidation validateComposite(
            @RequestBody AlertsRequestDTO.ValidateComposite body) {
        log.info("POST /api/alerts/composites/validate received (compositeId={})",
                body == null ? null : body.getCompositeId());
        if (body == null) throw new IllegalArgumentException("a request body is required");
        return alertsService.validateComposite(body.getCompositeCondition(), body.getCompositeId());
    }

    /** Wraps a bare name list in the same envelope every other list route uses. */
    private AlertsResponseDTO.Page<String> stringPage(List<String> names) {
        AlertsResponseDTO.Page<String> p = new AlertsResponseDTO.Page<>();
        p.setItems(new ArrayList<>(names));
        p.setSupported(true);
        p.setTotal(names.size());
        return p;
    }
}
