package com.trinity.observability.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.trinity.observability.config.AlertPersistenceConfig;
import com.trinity.observability.dto.alerts.AlertNotificationsRequestDTO;
import com.trinity.observability.dto.alerts.AlertNotificationsResponseDTO;
import com.trinity.observability.service.AlertIngestService;
import com.trinity.observability.service.AlertNotificationsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The Alerts screen's live feed — what is firing, what someone owns, what is over.
 *
 * <p>{@link AlertRuleController} is about alert <em>definitions</em>; this is
 * about what they produced. Both are backed by PostgreSQL — definitions in
 * {@code alert_rules}, firings in {@code alerts} and {@code alert_events}.</p>
 *
 * <p>Every route is scoped to the request's organization via {@code ?org=} or
 * {@code X-Org-Id}, exactly as elsewhere.</p>
 *
 * <p>These paths sit under {@code /api/alerts/…} alongside
 * {@code GET /api/alerts/{alertId}}. Spring resolves the overlap in favour of
 * the literal segment, so {@code /api/alerts/notifications} reaches this class
 * and never the {@code {alertId}} handler — the same way {@code /folders} and
 * {@code /destinations} already coexist with it.</p>
 */
@RestController
@ConditionalOnBean(AlertPersistenceConfig.class)
@RequestMapping("/api/alerts/notifications")
@Tag(name = "Alert Notifications", description = "Live feed of firing alerts")
public class AlertNotificationsController {

    private static final Logger log = LogManager.getLogger(AlertNotificationsController.class);

    private final AlertNotificationsService notifications;
    private final AlertIngestService ingest;

    public AlertNotificationsController(AlertNotificationsService notifications,
            AlertIngestService ingest) {
        this.notifications = notifications;
        this.ingest = ingest;
    }

    @GetMapping
    @Operation(summary = "List alerts currently needing attention", description =
            "Defaults to UNRESOLVED alerts (firing + acknowledged). Pass state=all for "
            + "everything tracked, or state=resolved for recent history.")
    public AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.Active> list(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "streamName", required = false) String streamName,
            @RequestParam(name = "limit", required = false) Integer limit) {

        long t0 = System.nanoTime();
        AlertNotificationsRequestDTO.ListFilter f = new AlertNotificationsRequestDTO.ListFilter();
        f.setState(state);
        f.setSeverity(severity);
        f.setSearch(search);
        f.setStreamName(streamName);
        f.setLimit(limit);

        AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.Active> result =
                notifications.listActive(f);
        log.info("GET /api/alerts/notifications returned {} of {} for org={} in {} ms",
                result.getItems().size(), result.getTotal(), result.getOrg(),
                (System.nanoTime() - t0) / 1_000_000L);
        return result;
    }

    @GetMapping("/summary")
    @Operation(summary = "Counts for the nav badge", description =
            "One grouped COUNT against PostgreSQL — cheap enough to poll.")
    public AlertNotificationsResponseDTO.Summary summary() {
        return notifications.summary();
    }

    @GetMapping("/history")
    @Operation(summary = "The event trail", description =
            "Read from the append-only alert_events table, so it survives the alert row "
            + "being overwritten by later firings and outlives the episode itself.")
    public AlertNotificationsResponseDTO.Page<AlertNotificationsResponseDTO.HistoryItem> history(
            @RequestParam(name = "fingerprint", required = false) String fingerprint,
            @RequestParam(name = "hours", required = false) Integer hours,
            @RequestParam(name = "limit", required = false) Integer limit) {
        log.info("GET /api/alerts/notifications/history received (fingerprint={}, hours={})",
                fingerprint, hours);
        return notifications.history(fingerprint, hours, limit);
    }

    @PostMapping("/{fingerprint}/acknowledge")
    @Operation(summary = "Take ownership of an alert", description =
            "Does not silence it — the alert keeps firing and the occurrence count keeps "
            + "rising, so the UI can show how much has happened since.")
    public AlertNotificationsResponseDTO.WriteResult acknowledge(
            @PathVariable("fingerprint") String fingerprint,
            @RequestBody(required = false) AlertNotificationsRequestDTO.Action body) {
        log.info("POST /api/alerts/notifications/{}/acknowledge received", fingerprint);
        return notifications.acknowledge(fingerprint, body);
    }

    @PostMapping("/{fingerprint}/resolve")
    @Operation(summary = "Close an alert by hand", description =
            "Alerts also close on their own once they stop firing — this is for closing "
            + "one ahead of that window.")
    public AlertNotificationsResponseDTO.WriteResult resolve(
            @PathVariable("fingerprint") String fingerprint,
            @RequestBody(required = false) AlertNotificationsRequestDTO.Action body) {
        log.info("POST /api/alerts/notifications/{}/resolve received", fingerprint);
        return notifications.resolve(fingerprint, body);
    }

    @PostMapping("/reload")
    @Operation(summary = "Report the tracked alert count", description =
            "Retained for compatibility. PostgreSQL is read directly, so there is no cached "
            + "projection left to rebuild; this now simply reports the live count.")
    public Map<String, Object> reload() {
        log.info("POST /api/alerts/notifications/reload received");
        int tracked = notifications.reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("trackedAlerts", tracked);
        return out;
    }

    /**
     * Whether alerts are actually arriving.
     *
     * <p>This endpoint exists because of what replaced the webhook. With a push
     * ingest, "nothing is firing" and "the ingest is broken" were equally silent —
     * the only evidence was an OpenObserve delivery log nobody watches. A poller
     * has a watermark and a failure count, so the difference can be stated, and the
     * UI can say which it is instead of showing an empty table that might mean
     * either.</p>
     */
    @GetMapping("/ingest-status")
    @Operation(summary = "Alert ingest health",
            description = "Per-organization poller state: how far it has read, when it last "
                    + "succeeded, and the last error. An empty list means no poll has run yet.")
    public Map<String, Object> ingestStatus() {
        log.info("GET /api/alerts/notifications/ingest-status received");
        List<AlertIngestService.CursorStatus> cursors = ingest.cursorStatus();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // An absent cursor is NOT health — it means nothing has polled yet, which on
        // a running instance is itself the problem worth surfacing.
        out.put("healthy", !cursors.isEmpty()
                && cursors.stream().allMatch(AlertIngestService.CursorStatus::healthy));
        // Reported separately from `healthy` because it is the opposite kind of
        // failure: the polls are succeeding and the watermark still is not moving. ANY
        // stalled cursor counts — one wedged organization means that organization's
        // alerts have stopped arriving, whatever the others are doing.
        out.put("stalled", cursors.stream().anyMatch(AlertIngestService.CursorStatus::stalled));
        out.put("cursors", cursors);
        return out;
    }

    /**
     * Poll now rather than waiting for the timer.
     *
     * <p>Behind the Refresh button, so an operator who has just triggered a test
     * alert does not have to wait out the interval to find out whether the pipeline
     * works. Reports what the poll actually did, which is also the fastest way to
     * diagnose a field-mapping problem.</p>
     */
    @PostMapping("/ingest-now")
    @Operation(summary = "Run the alert ingest poll immediately",
            description = "Returns rows read, stored and de-duplicated per organization.")
    public Map<String, Object> ingestNow() {
        log.info("POST /api/alerts/notifications/ingest-now received");
        List<AlertIngestService.PollResult> results = ingest.pollOnce();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", results.stream().allMatch(AlertIngestService.PollResult::ok));
        out.put("results", results);
        return out;
    }
}
