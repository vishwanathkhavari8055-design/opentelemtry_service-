package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.alerts.AlertPreviewRequestDTO;
import com.trinity.observability.dto.alerts.AlertPreviewResponseDTO;
import com.trinity.observability.dto.alerts.AlertScopeResponseDTO;
import com.trinity.observability.service.AlertPreviewService;
import com.trinity.observability.service.AlertScopeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * What the alert editor needs while a rule is being WRITTEN.
 *
 * <h2>Why this is not on {@code /api/alert-rules}</h2>
 *
 * <p>Everything here is read-only and none of it needs a rule to exist — the
 * whole point is that the operator is still typing. {@code AlertRuleController}
 * is also {@code @ConditionalOnBean(AlertPersistenceConfig.class)}, so hanging
 * these routes off it would make scoping and preview disappear on a deployment
 * with no rules database, even though neither one touches it.</p>
 *
 * <p>Nothing on {@code /api/alerts} or {@code /api/alert-rules} changes. This is
 * additive: an editor that never calls these endpoints behaves exactly as it
 * does today.</p>
 *
 * <h2>The two problems it solves</h2>
 *
 * <ol>
 *   <li><b>Scoping.</b> {@code /scopes}, {@code /targets} and {@code /fields}
 *       narrow the pickers to the selected service, tool or product, so the
 *       metric list is the twenty metrics that service emits rather than the
 *       three thousand the tenant has.</li>
 *   <li><b>Preview.</b> {@code /preview} runs the conditions against real data
 *       one after another, so an operator sees what each condition matches
 *       before saving — and, critically, WHICH condition is the one that
 *       matches nothing.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/alert-builder")
@Tag(name = "Alert Builder", description = "Scoped pickers and live preview for the alert editor")
public class AlertBuilderController {

    private static final Logger log = LogManager.getLogger(AlertBuilderController.class);

    private final AlertScopeService scopes;
    private final AlertPreviewService previews;

    public AlertBuilderController(AlertScopeService scopes, AlertPreviewService previews) {
        this.scopes = scopes;
        this.previews = previews;
    }

    @GetMapping("/scopes")
    @Operation(summary = "What an alert can be scoped to",
            description = """
                    Registered Product Catalog resources and products first, then services seen in telemetry that the catalog does not cover. Send the chosen entry's `type` and `key` back as scopeType/scopeKey on the other routes.

                    `metricCount` is -1, not 0, while the producer index is still building: zero would read as 'this service has no metrics', which is a claim about the service rather than about how far we have looked.""")
    public AlertScopeResponseDTO.ScopeList scopes() {
        log.info("GET /api/alert-builder/scopes received");
        return scopes.listScopes();
    }

    @GetMapping("/targets")
    @Operation(summary = "Streams or metrics for the selected scope",
            description = """
                    For streamType=metrics this is the metric names the scope actually emits — in OpenObserve a metric is its own stream, so this IS the stream picker, narrowed.

                    For logs and traces the streams are shared, so each one is asked directly whether it holds a row for the scope over the last day, and only those that answer are listed. Each target carries the `filterColumn` THAT stream identifies the service by — service_name for SDK-exported logs, k8s_container_name for scraped ones — and `filter` carries the predicate to splice into query_condition.conditions.

                    Omit scopeType/scopeKey for the unscoped list; nothing is probed then.

                    Check `scoped`. When it is false the list was NOT narrowed and `notice` says why — a stream or metric picked from an unscoped list may be one the chosen service never writes to, and a rule on it will validate, sync, report itself healthy and never fire.""")
    public AlertScopeResponseDTO.TargetList targets(
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "scopeKey", required = false) String scopeKey,
            @RequestParam(name = "streamType", defaultValue = "metrics") String streamType,
            @RequestParam(name = "include", required = false) String include) {

        log.info("GET /api/alert-builder/targets?scopeType={}&scopeKey={}&streamType={} received",
                scopeType, scopeKey, streamType);
        return scopes.listTargets(scopeType, scopeKey, streamType, include);
    }

    @GetMapping("/fields")
    @Operation(summary = "Columns on one stream, with the scope predicate alongside",
            description = "The same schema /api/alerts/streams/{name}/schema returns, plus the "
                    + "scope filter, so the builder can seed a correctly scoped first condition "
                    + "from one call. An empty list is normal for a stream nothing has written "
                    + "to; the editor falls back to a free-text column.")
    public AlertScopeResponseDTO.FieldList fields(
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "scopeKey", required = false) String scopeKey,
            @RequestParam(name = "streamType", defaultValue = "logs") String streamType,
            @RequestParam(name = "streamName") String streamName) {

        log.info("GET /api/alert-builder/fields?streamType={}&streamName={} received",
                streamType, streamName);
        return scopes.listFields(scopeType, scopeKey, streamType, streamName);
    }

    @PostMapping("/preview")
    @Operation(summary = "Run the draft rule against real data, condition by condition",
            description = """
                    Returns a ladder. Step 0 is the window with no conditions at all; each later step adds exactly one condition and reports what survived and how much that condition removed.

                    That shape is the feature. A single 'matches 0 rows' has four different causes needing four different fixes — wrong stream, wrong window, one bad condition, or a rule that is simply correct and quiet — and they are indistinguishable from one number. An empty step 0 rules out the first two before any condition is blamed, and the step where the count collapses names the condition at fault.

                    `evaluation` then answers the separate question of whether that amount crosses the trigger, which is where a rule that matches plenty of rows and still never pages anybody goes wrong.

                    Nothing is stored and no rule need exist. sampleSize=0 skips the sample rows and halves the queries.""")
    public AlertPreviewResponseDTO.Preview preview(
            @Valid @RequestBody AlertPreviewRequestDTO.Preview body) {
        log.info("POST /api/alert-builder/preview received (streamType={}, stream={})",
                body.getStreamType(), body.getStreamName());
        return previews.preview(body);
    }
}
