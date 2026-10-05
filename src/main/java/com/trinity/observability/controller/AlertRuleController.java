package com.trinity.observability.controller;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.config.AlertPersistenceConfig;
import com.trinity.observability.dto.alerts.AlertRuleRequestDTO;
import com.trinity.observability.dto.alerts.AlertRuleResponseDTO;
import com.trinity.observability.service.AlertRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Alert rules owned by this service, stored in PostgreSQL.
 *
 * <h2>Relationship to {@link AlertsController}</h2>
 *
 * <p>{@code /api/alerts} is a pass-through to OpenObserve's own alert store and
 * remains exactly as it was — it still serves rules authored directly in
 * OpenObserve's UI, which this service does not own.</p>
 *
 * <p>{@code /api/alert-rules} is the managed path: PostgreSQL is the source of
 * truth, OpenObserve holds a derived copy for evaluation, and
 * {@link #sync} rebuilds that copy from scratch. New UI work should use this
 * one; the two are deliberately separate so adopting it does not disturb
 * anything already working.</p>
 *
 * <p>Every route is scoped to the request's organization via {@code ?org=} or
 * {@code X-Org-Id}, as elsewhere in this service.</p>
 */
@RestController
@RequestMapping("/api/alert-rules")
@ConditionalOnBean(AlertPersistenceConfig.class)
@Tag(name = "Alert Rules", description = "PostgreSQL-backed alert rule definitions")
public class AlertRuleController {

    private static final Logger log = LogManager.getLogger(AlertRuleController.class);

    private final AlertRuleService service;

    private final com.trinity.observability.serviceimpl.AlertRuleImporter importer;

    public AlertRuleController(AlertRuleService service,
            com.trinity.observability.serviceimpl.AlertRuleImporter importer) {
        this.service = service;
        this.importer = importer;
    }

    @GetMapping
    @Operation(summary = "List alert rules", description =
            "Reads from PostgreSQL, not OpenObserve. Rules whose syncStatus is FAILED are "
            + "stored correctly but are NOT being evaluated — surface that in the UI.")
    public AlertRuleResponseDTO.Page<AlertRuleResponseDTO.Rule> list(
            @RequestParam(name = "enabled", required = false) Boolean enabled,
            @RequestParam(name = "streamType", required = false) String streamType,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "syncStatus", required = false) String syncStatus,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {

        AlertRuleRequestDTO.ListFilter f = new AlertRuleRequestDTO.ListFilter();
        f.setEnabled(enabled);
        f.setStreamType(streamType);
        f.setSeverity(severity);
        f.setSyncStatus(syncStatus);
        f.setSearch(search);
        f.setPage(page);
        f.setSize(size);

        log.info("GET /api/alert-rules received (enabled={}, streamType={}, search={})",
                enabled, streamType, search);
        return service.list(f);
    }

    @GetMapping("/{uuid}")
    @Operation(summary = "Get one alert rule")
    public AlertRuleResponseDTO.Rule get(@PathVariable("uuid") UUID uuid) {
        log.info("GET /api/alert-rules/{} received", uuid);
        return service.get(uuid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an alert rule", description =
            "Stored in PostgreSQL first, then pushed to OpenObserve. A failed push does NOT "
            + "fail the request — the definition is safe and syncStatus reports the problem. "
            + "`activation` decides when the rule starts acting: NOW (the default, and what a "
            + "body that omits the field gets), LATER to store it without evaluating until "
            + "somebody enables it, or SCHEDULED with `activateAt` to have it turn itself on. "
            + "A staged rule is pushed to OpenObserve DISABLED, so it genuinely is not "
            + "evaluating — not merely marked as such here.")
    public AlertRuleResponseDTO.WriteResult create(
            @Valid @RequestBody AlertRuleRequestDTO.SaveRule body) {
        log.info("POST /api/alert-rules received (name='{}')", body.getName());
        return service.create(body);
    }

    @PutMapping("/{uuid}")
    @Operation(summary = "Replace an alert rule")
    public AlertRuleResponseDTO.WriteResult update(
            @PathVariable("uuid") UUID uuid,
            @Valid @RequestBody AlertRuleRequestDTO.SaveRule body) {
        log.info("PUT /api/alert-rules/{} received (name='{}')", uuid, body.getName());
        return service.update(uuid, body);
    }

    @DeleteMapping("/{uuid}")
    @Operation(summary = "Delete an alert rule", description =
            "Soft-deleted here and removed from OpenObserve. If the remote removal fails the "
            + "rule is marked DELETE_PENDING and retried by the next sync — until then it may "
            + "still fire.")
    public AlertRuleResponseDTO.WriteResult delete(
            @PathVariable("uuid") UUID uuid,
            @RequestParam(name = "actor", required = false) String actor) {
        log.info("DELETE /api/alert-rules/{} received", uuid);
        return service.delete(uuid, actor);
    }

    @PatchMapping("/{uuid}/enable")
    @Operation(summary = "Enable or disable a rule", description =
            "The plain on/off switch. Enabling a STAGED rule also settles whatever was "
            + "holding it back, and disabling records an explicit suppression rather than "
            + "staging — a rule switched off at 3am must not then read as 'starts later', "
            + "and a pending schedule must not turn it back on. Use "
            + "PATCH /{uuid}/activation to stage or schedule instead.")
    public AlertRuleResponseDTO.WriteResult setEnabled(
            @PathVariable("uuid") UUID uuid,
            @RequestParam(name = "value") boolean value,
            @RequestParam(name = "actor", required = false) String actor) {
        log.info("PATCH /api/alert-rules/{}/enable?value={} received", uuid, value);
        return service.setEnabled(uuid, value, actor);
    }

    @PatchMapping("/{uuid}/activation")
    @Operation(summary = "Decide when a rule acts, without resending its definition",
            description =
            "The 'enable now or later' control, applied to a rule that already exists. "
            + "NOW makes it live immediately. LATER stages it — stored, valid, pushed to "
            + "OpenObserve DISABLED, and waiting for a person. SCHEDULED stages it until "
            + "activateAt, at which point the service turns it on by itself. "
            + "activateAt is ISO-8601 and must carry an offset (2026-09-22T09:00:00Z or "
            + "2026-09-22T14:30:00+05:30); a local time would have to be guessed at, and "
            + "guessing wrong starts the rule hours late with nothing to show it did. "
            + "Separate from PUT so postponing a rule does not mean resending the whole "
            + "definition from a copy the client may have been holding for a while.")
    public AlertRuleResponseDTO.WriteResult setActivation(
            @PathVariable("uuid") UUID uuid,
            @RequestParam(name = "activation") String activation,
            @RequestParam(name = "activateAt", required = false) String activateAt,
            @RequestParam(name = "actor", required = false) String actor) {
        log.info("PATCH /api/alert-rules/{}/activation?activation={}&activateAt={} received",
                uuid, activation, activateAt);
        return service.setActivation(uuid, activation, activateAt, actor);
    }

    @PostMapping("/sync")
    @Operation(summary = "Rebuild every rule in OpenObserve from PostgreSQL", description =
            "The recovery path. Reads what OpenObserve currently holds, creates what is "
            + "missing, updates what exists and removes what should not be there. Idempotent — "
            + "safe to run against a healthy instance as well as an empty one.")
    public AlertRuleResponseDTO.SyncResult sync() {
        log.info("POST /api/alert-rules/sync received");
        return service.syncAll();
    }

    @PostMapping("/import")
    @Operation(summary = "Store every OpenObserve-authored rule in PostgreSQL", description =
            "The OTHER direction from /sync. /sync pushes what this database holds INTO "
            + "OpenObserve; this reads what OpenObserve holds INTO the database, so rules "
            + "written in OpenObserve's own UI stop being invisible here. That was the whole "
            + "gap: 20 rules lived in OpenObserve, 9 rows existed in alert_rules, and the two "
            + "sets did not overlap — which is why 73 of 90 stored alerts showed no rule. "
            + "Idempotent. A rule already known here is ADOPTED (linked to its OpenObserve id) "
            + "and its stored definition is left untouched, so an edit made here is never "
            + "overwritten by the remote copy. Safe to re-run, and worth re-running after "
            + "somebody adds a rule in OpenObserve.")
    public AlertRuleResponseDTO.SyncResult importFromOpenObserve(
            @RequestParam(name = "actor", required = false) String actor) {
        log.info("POST /api/alert-rules/import received");
        return importer.importAll(actor == null || actor.isBlank() ? "import" : actor);
    }

    @PostMapping("/{uuid}/sync")
    @Operation(summary = "Retry the push for one rule")
    public AlertRuleResponseDTO.WriteResult syncOne(@PathVariable("uuid") UUID uuid) {
        log.info("POST /api/alert-rules/{}/sync received", uuid);
        return service.syncOne(uuid);
    }
}
