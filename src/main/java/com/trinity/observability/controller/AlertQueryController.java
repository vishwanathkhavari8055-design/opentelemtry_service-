package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.config.AlertPersistenceConfig;
import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.dto.alerts.AlertQueryResponseDTO;
import com.trinity.observability.service.AlertLiveStreamService;
import com.trinity.observability.service.AlertQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The enterprise Alerts screen.
 *
 * <h2>Why this is a new path rather than a change to an old one</h2>
 *
 * <p>{@code /api/alerts/notifications} is what the existing UI calls, and it stays
 * exactly as it was — same parameters, same response keys. This path serves the
 * columns that screen never had (service, host, source, duration, closed-by,
 * reopen count), multi-select filters, server-side sorting and the CLOSED /
 * REOPENED transitions. Adding those to the old contract would have changed a live
 * one; adding a second contract does not.</p>
 *
 * <h2>Ids, not fingerprints</h2>
 *
 * <p>The transition routes here take a numeric alert id. A fingerprint identifies
 * the alert SERIES across every episode it has ever had, so acknowledging by
 * fingerprint is ambiguous about which episode the operator was looking at. An id
 * is one episode — the row they actually clicked. The legacy routes keep taking a
 * fingerprint, because that is their published contract.</p>
 *
 * <p>Every route is scoped to the request's organization via {@code ?org=} or
 * {@code X-Org-Id}, as elsewhere in this service. Bulk operations are additionally
 * filtered by org in the query, so a caller cannot reach another tenant's alerts by
 * guessing ids.</p>
 */
@RestController
@ConditionalOnBean(AlertPersistenceConfig.class)
@RequestMapping("/api/alerts/query")
@Tag(name = "Alerts (enterprise)",
        description = "Filtered, sorted, paged alert search and the full lifecycle")
public class AlertQueryController {

    private static final Logger log = LogManager.getLogger(AlertQueryController.class);

    private final AlertQueryService service;
    private final AlertLiveStreamService liveStream;

    public AlertQueryController(AlertQueryService service, AlertLiveStreamService liveStream) {
        this.service = service;
        this.liveStream = liveStream;
    }

    @GetMapping
    @Operation(summary = "Search alerts",
            description = "Server-side filtering, sorting and pagination. Filters: search, "
                    + "severity (repeatable), status (repeatable), service, stream, source, "
                    + "ruleId and window. Sort by lastFiredAt, firstFiredAt, severity, status, "
                    + "alertName, serviceName, occurrences or updatedAt.")
    public AlertQueryResponseDTO.Page search(@Valid AlertQueryRequestDTO.Search request) {
        log.info("GET /api/alerts/query received: {}", request);
        return service.search(request);
    }

    @GetMapping("/summary")
    @Operation(summary = "Summary card counts",
            description = "Counted over the SAME window as the search, so the cards and the "
                    + "table beneath them cannot disagree.")
    public AlertQueryResponseDTO.Summary summary(@Valid AlertQueryRequestDTO.Search request) {
        log.info("GET /api/alerts/query/summary received: window={}",
                request == null ? null : request.getWindow());
        return service.summary(request);
    }

    /**
     * Values for the filter dropdowns.
     *
     * <p>Declared before {@code /{id}} so the literal segment is matched first;
     * Spring prefers it anyway, but relying on that ordering implicitly is how a
     * later refactor turns "filters" into an id lookup.</p>
     */
    @GetMapping("/filters")
    @Operation(summary = "Distinct filter values",
            description = "Services, streams, sources and rules derived from stored alerts, plus "
                    + "the fixed severity and status vocabularies.")
    public AlertQueryResponseDTO.FilterOptions filters() {
        log.info("GET /api/alerts/query/filters received");
        return service.filterOptions();
    }

    /**
     * The same alerts, read straight from the stream instead of from PostgreSQL.
     *
     * <p>For when the ingest is down or wedged: the stored table then holds nothing
     * recent, and an empty alerts screen reads as "all clear" when it means "we cannot
     * see". This answers from OpenObserve directly, so it is never stale — and it is
     * read-only, because the stream has no lifecycle to write to. The rows carry no
     * id for that reason: every transition route takes one, so there is no way to
     * acknowledge something that was never ingested.</p>
     *
     * <p>Declared before {@code /{id}} for the same reason as {@code /filters}.</p>
     */
    @GetMapping("/live")
    @Operation(summary = "Alerts read live from the alert_events stream",
            description = "Bypasses PostgreSQL and queries OpenObserve directly, grouping the "
                    + "window's firings by fingerprint. Read-only: rows have no id, no episode "
                    + "history and no lifecycle, and the response is marked source=live-stream so "
                    + "the caller can say which source it is showing. Blank window means 24h — a "
                    + "stream query cannot be unbounded.")
    public AlertQueryResponseDTO.Page live(@Valid AlertQueryRequestDTO.Search request) {
        log.info("GET /api/alerts/query/live received: window={}, size={}",
                request == null ? null : request.getWindow(),
                request == null ? null : request.getSize());
        return liveStream.live(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One alert in full",
            description = "The row, its audit trail across every episode of the same alert, the "
                    + "rule behind it when this application owns that rule, and the raw event.")
    public AlertQueryResponseDTO.Detail detail(@PathVariable("id") Long id) {
        log.info("GET /api/alerts/query/{} received", id);
        return service.detail(id);
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge an alert",
            description = "Records who took ownership. Survives re-firing, so the alert does not "
                    + "shout again on the next evaluation while somebody is working on it.")
    public AlertQueryResponseDTO.WriteResult acknowledge(@PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AlertQueryRequestDTO.Transition body) {
        log.info("POST /api/alerts/query/{}/acknowledge received", id);
        return service.acknowledge(id, body);
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve an alert", description = "The condition is over.")
    public AlertQueryResponseDTO.WriteResult resolve(@PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AlertQueryRequestDTO.Transition body) {
        log.info("POST /api/alerts/query/{}/resolve received", id);
        return service.resolve(id, body);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close an alert",
            description = "A human is finished with it. Distinct from resolve, which the "
                    + "staleness sweep also sets by inference — closure is never inferred.")
    public AlertQueryResponseDTO.WriteResult close(@PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AlertQueryRequestDTO.Transition body) {
        log.info("POST /api/alerts/query/{}/close received", id);
        return service.close(id, body);
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Reopen an alert",
            description = "Returns it to FIRING and clears the previous acknowledgement, so it "
                    + "demands attention again rather than looking already-handled.")
    public AlertQueryResponseDTO.WriteResult reopen(@PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AlertQueryRequestDTO.Transition body) {
        log.info("POST /api/alerts/query/{}/reopen received", id);
        return service.reopen(id, body);
    }

    @PostMapping("/bulk")
    @Operation(summary = "Apply one transition to many alerts",
            description = "Reports per-id outcomes. An alert the action cannot apply to is "
                    + "SKIPPED, not a failure — a bulk acknowledge over forty alerts where three "
                    + "had already resolved is a partial success.")
    public AlertQueryResponseDTO.BulkResult bulk(
            @Valid @RequestBody AlertQueryRequestDTO.BulkTransition body) {
        log.info("POST /api/alerts/query/bulk received: action={}, ids={}",
                body.getAction(), body.getIds().size());
        return service.bulk(body);
    }
}
