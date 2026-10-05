package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.dto.dashboards.DashboardCatalogRequestDTO;
import com.trinity.observability.dto.dashboards.DashboardCatalogResponseDTO;
import com.trinity.observability.service.DashboardCatalogService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The Dashboard Catalog, and the render path behind it.
 *
 * <p>Two groups of endpoints, and the split is the security boundary:</p>
 *
 * <ul>
 *   <li>{@code /registry/**} — administration. What this application exposes:
 *       register a Grafana UID into a folder, move it, switch it off, remove it.</li>
 *   <li>{@code /definition}, {@code /query}, {@code /datasources},
 *       {@code /label-values} — what the browser needs to actually DRAW a
 *       dashboard. These exist because the Grafana token must never reach the
 *       browser; they are the only way the frontend can reach Grafana at all.</li>
 * </ul>
 *
 * <p>Nothing here writes to Grafana. Not a design accident — a Viewer-role
 * service-account token is the whole privilege the feature needs, and the catalog
 * is about which dashboards are reachable, never about authoring them.</p>
 */
@RestController
@RequestMapping("/api/dashboards")
@Tag(name = "Dashboard Catalog",
        description = "Grafana dashboards registered into this application, and the read-only "
                + "proxy that lets the browser render them without holding a Grafana token")
public class DashboardCatalogController {

    private static final Logger log = LogManager.getLogger(DashboardCatalogController.class);

    private final DashboardCatalogService service;

    public DashboardCatalogController(DashboardCatalogService service) {
        this.service = service;
    }

    // ── diagnostics ─────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(summary = "Whether dashboards are configured, and against which Grafana",
            description = "Answers the one question a blank Dashboards screen raises: is Grafana "
                    + "unconfigured, or is the catalog simply empty? Also reports where "
                    + "registrations are stored, which is what tells you whether they will "
                    + "survive a restart.")
    public DashboardCatalogResponseDTO.Health health() {
        log.info("GET /api/dashboards/health received");
        return service.health();
    }

    // ── registry ────────────────────────────────────────────────────────────

    @GetMapping("/registry")
    @Operation(summary = "List registered dashboards",
            description = "Everything this application exposes. `enabledOnly` gives just what a "
                    + "viewer may open; `folderUid` narrows to one folder tile's contents. "
                    + "Answers from this service's own registry, so it still works when Grafana "
                    + "is unreachable.")
    public DashboardCatalogResponseDTO.RegistrationList list(
            @RequestParam(name = "enabledOnly", required = false, defaultValue = "false")
            boolean enabledOnly,
            @RequestParam(name = "folderUid", required = false) String folderUid) {
        log.info("GET /api/dashboards/registry received: enabledOnly={}, folderUid={}",
                enabledOnly, folderUid);
        return service.list(enabledOnly, folderUid);
    }

    @GetMapping("/registry/folder-summary")
    @Operation(summary = "The folder tiles",
            description = "One tile per folder holding registrations, with how many are in it and "
                    + "how many are enabled. Counted server-side so a tile and the list it opens "
                    + "onto can never disagree.")
    public DashboardCatalogResponseDTO.FolderTileList folderSummary() {
        log.info("GET /api/dashboards/registry/folder-summary received");
        return service.folderTiles();
    }

    @GetMapping("/folders")
    @Operation(summary = "Folders a dashboard may be filed under",
            description = "Grafana's folders, narrowed by grafana.allowed-folder-uids when that "
                    + "is set (`restricted` says whether it was). Empty with supported=false when "
                    + "Grafana is not configured — the register form shows the reason rather than "
                    + "an empty dropdown.")
    public DashboardCatalogResponseDTO.GrafanaFolderList folders() {
        log.info("GET /api/dashboards/folders received");
        return service.folders();
    }

    @GetMapping("/available")
    @Operation(summary = "Dashboards that are not registered yet",
            description = "Discovery for the register form: what is in a Grafana folder that this "
                    + "application does not already expose. A UID typed by hand is always "
                    + "accepted whether it appears here or not.")
    public DashboardCatalogResponseDTO.CandidateList available(
            DashboardCatalogRequestDTO.Available filter) {
        log.info("GET /api/dashboards/available received: {}", filter);
        return service.available(filter);
    }

    @PostMapping("/registry")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a dashboard",
            description = "Resolves the UID against Grafana first and stores its real title and "
                    + "folder, so a typo is a 404 that says so rather than a tile that opens onto "
                    + "an error. 409 when the UID is already registered.")
    public DashboardCatalogResponseDTO.Registration register(
            @Valid @RequestBody DashboardCatalogRequestDTO.Register body) {
        log.info("POST /api/dashboards/registry received: {}", body);
        return service.register(body);
    }

    @PatchMapping("/registry/{uid}")
    @Operation(summary = "Move, reorder, enable or disable a registration",
            description = "PATCH: a null field is left alone. Moving to another folder without "
                    + "naming a position appends it to the end of that folder.")
    public DashboardCatalogResponseDTO.Registration update(@PathVariable("uid") String uid,
            @Valid @RequestBody DashboardCatalogRequestDTO.Update body) {
        log.info("PATCH /api/dashboards/registry/{} received: {}", uid, body);
        return service.update(uid, body);
    }

    @PostMapping("/registry/{uid}/refresh")
    @Operation(summary = "Re-read a registration's title and folder from Grafana",
            description = "The cached label only. Nothing about the dashboard's contents is "
                    + "stored, so there is nothing else here to go stale.")
    public DashboardCatalogResponseDTO.Registration refresh(@PathVariable("uid") String uid) {
        log.info("POST /api/dashboards/registry/{}/refresh received", uid);
        return service.refresh(uid);
    }

    @DeleteMapping("/registry/{uid}")
    @Operation(summary = "Unregister a dashboard",
            description = "Removes it from this application. The dashboard is NOT deleted or "
                    + "changed in Grafana, and can be registered again at any time.")
    public DashboardCatalogResponseDTO.WriteResult unregister(@PathVariable("uid") String uid) {
        log.info("DELETE /api/dashboards/registry/{} received", uid);
        return service.unregister(uid);
    }

    // ── render path ─────────────────────────────────────────────────────────

    @GetMapping("/registry/{uid}/definition")
    @Operation(summary = "A registered dashboard's live definition",
            description = "Read fresh from Grafana on every call — nothing about a dashboard's "
                    + "contents is cached here, so a panel edited in Grafana appears on the next "
                    + "open. Gated on the registry: 404 for a UID this application does not "
                    + "expose, 409 when it is registered but disabled.")
    public DashboardCatalogResponseDTO.Definition definition(@PathVariable("uid") String uid) {
        log.info("GET /api/dashboards/registry/{}/definition received", uid);
        return service.definition(uid);
    }

    @PostMapping("/query")
    @Operation(summary = "Run panel queries",
            description = "A pass-through to Grafana's own /api/ds/query, which is what lets one "
                    + "code path serve every datasource type on the instance — Grafana, not this "
                    + "service, knows how to execute each of them. The payload is the browser's, "
                    + "verbatim; the credential is this service's.")
    public DashboardCatalogResponseDTO.Query query(@RequestBody JsonNode payload) {
        log.debug("POST /api/dashboards/query received");
        return service.query(payload);
    }

    @GetMapping("/datasources")
    @Operation(summary = "Datasources, reduced to uid/name/type",
            description = "Only those three fields, deliberately: Grafana's own record carries "
                    + "URLs, usernames and secureJsonFields, none of which the browser has any "
                    + "business seeing. The browser needs this to turn a dashboard's datasource "
                    + "reference — which may be a name or a ${DS_X} variable — into a concrete UID.")
    public DashboardCatalogResponseDTO.DatasourceList datasources(
            @RequestParam(name = "type", required = false) String type) {
        log.info("GET /api/dashboards/datasources received: type={}", type);
        return service.datasources(type);
    }

    @GetMapping("/datasources/{uid}/label-values")
    @Operation(summary = "Values of a Prometheus label",
            description = "Backs a dashboard's template variables. Proxied through Grafana so the "
                    + "datasource's own URL and credentials never leave it.")
    public DashboardCatalogResponseDTO.LabelValues labelValues(
            @PathVariable("uid") String uid,
            @RequestParam("label") String label,
            @RequestParam(name = "metric", required = false) String metric,
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to) {
        log.debug("GET /api/dashboards/datasources/{}/label-values received: label={}, metric={}",
                uid, label, metric);
        return service.labelValues(uid, label, metric, from, to);
    }
}
