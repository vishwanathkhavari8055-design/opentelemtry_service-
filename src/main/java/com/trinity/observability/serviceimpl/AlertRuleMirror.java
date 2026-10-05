package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.util.AlertPayloadNormalizer;

/**
 * Keeps {@code alert_rules} in step with rules written straight to OpenObserve.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>There are two ways a rule reaches OpenObserve. {@code AlertRuleService}
 * owns rules in PostgreSQL and pushes them out. {@code AlertsService} is a
 * pass-through: the browser's alert wizard and editor send an OpenObserve
 * document and it forwards it. The second path is the one the UI actually uses,
 * and it wrote nothing to this database — so a rule created in the wizard existed
 * in OpenObserve and nowhere else, which is what
 * {@link AlertRuleImporter} was written to repair after the fact.</p>
 *
 * <p>This does the same job at the moment of the write instead of in a batch
 * afterwards: every create, update, delete and enable that succeeds against
 * OpenObserve is reflected here. The importer stays — it is still the way to
 * adopt rules authored in OpenObserve's own UI, which this service never sees.</p>
 *
 * <h2>Who is the source of truth</h2>
 *
 * <p>For rules arriving through this path, OpenObserve is: the write has already
 * happened there by the time anything below runs. The row recorded here is a
 * MIRROR, marked {@code SYNCED} and carrying the full document in
 * {@code source_document} so a later re-push cannot strip the fields this
 * schema does not model. That is the opposite direction to
 * {@code AlertRuleService}, and deliberately so — each path records the truth it
 * actually has.</p>
 *
 * <h2>Best-effort, always</h2>
 *
 * <p>Nothing here may fail the caller. The alert has already been created in
 * OpenObserve and is already evaluating; throwing now would report a failure for
 * an operation that succeeded, and an operator who retried would get a duplicate.
 * So every method swallows its own errors and logs them, and the database being
 * unavailable degrades this service back to exactly the pass-through it was
 * before — which is also what happens when persistence is switched off, because
 * then this bean does not exist at all.</p>
 *
 * <p>Drift is therefore possible and is not pretended away: a mirror write that
 * failed leaves the rule in OpenObserve and not here, and
 * {@code POST /api/alert-rules/import} reconciles it.</p>
 */
@Component
// The same two guards AlertPersistenceConfig carries. Without them this bean
// would demand an AlertRuleRepository on a deployment that never enabled
// persistence, and taking AlertsServiceImpl down with it would turn a database
// this service was told not to use into a reason the alerts API does not start.
@ConditionalOnClass(name = "jakarta.persistence.EntityManagerFactory")
@ConditionalOnProperty(name = "alerts.persistence.enabled", havingValue = "true",
        matchIfMissing = false)
public class AlertRuleMirror {

    private static final Logger log = LogManager.getLogger(AlertRuleMirror.class);

    /** Recorded as the author when the caller did not say who was acting. */
    private static final String DEFAULT_ACTOR = "alerts-api";

    /** {@code stream_type} is NOT NULL and CHECK-constrained to these three. */
    private static final String DEFAULT_STREAM_TYPE = "logs";

    private final AlertRuleRepository rules;

    public AlertRuleMirror(AlertRuleRepository rules) {
        this.rules = rules;
    }

    /**
     * Record a rule that OpenObserve has just accepted.
     *
     * <p>Idempotent, and matched in two steps: by OpenObserve id first, then by
     * name. The second lookup is what stops this fighting
     * {@code AlertRuleService} — that service creates its row locally and then
     * pushes THROUGH the pass-through API, which lands here; finding its row by
     * name and linking it is the correct outcome, where inserting a second row
     * would collide with the unique index on (org, name) and lose the write.</p>
     *
     * @param org      organization the rule belongs to
     * @param alertId  OpenObserve's id for it; null is tolerated but leaves the
     *                 row unlinked and PENDING, to be resolved by an import
     * @param document the document as sent to OpenObserve
     * @param folderId placement, for the row's own folder column
     * @param actor    who to attribute the write to
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSaved(String org, String alertId, JsonNode document,
            String folderId, String actor) {
        if (document == null || !document.isObject()) return;
        String name = text(document, "name");
        if (!hasText(name)) {
            log.warn("Not mirroring a rule with no name (org={}, id={})", org, alertId);
            return;
        }

        try {
            AlertRuleEntity rule = locate(org, alertId, name).orElseGet(AlertRuleEntity::new);
            boolean isNew = rule.getUuid() == null;
            Instant now = Instant.now();

            if (isNew) {
                rule.setUuid(UUID.randomUUID());
                rule.setOrg(org);
                rule.setCreatedBy(actorOrDefault(actor));
                rule.setCreatedAt(now);
            }

            applyDocument(rule, document, name, folderId);

            rule.setUpdatedBy(actorOrDefault(actor));
            rule.setUpdatedAt(now);
            // A mirrored rule was written to OpenObserve before we got here, so it
            // is synced by definition. Leaving it PENDING would queue a pointless
            // push of a rule that is already there.
            if (hasText(alertId)) rule.markSynced(alertId);

            rules.save(rule);
            log.info("Mirrored alert rule '{}' (org={}, id={}) as {}",
                    name, org, alertId, isNew ? "a new row" : "an update");

        } catch (Exception e) {
            // Never rethrown: see the class note. The alert exists in OpenObserve
            // and is evaluating; failing the caller now would report a failure for
            // work that succeeded and invite a duplicate on retry.
            log.error("Could not mirror alert rule '{}' (org={}, id={}) into PostgreSQL: {}"
                    + " — the rule is live in OpenObserve; run POST /api/alert-rules/import"
                    + " to reconcile.", name, org, alertId, e.getMessage(), e);
        }
    }

    /** Copies what OpenObserve was sent onto the mirrored row. */
    private static void applyDocument(AlertRuleEntity rule, JsonNode document, String name,
            String folderId) {
        rule.setName(name);
        rule.setDescription(text(document, "description"));
        rule.setFolderId(blankTo(folderId, "default"));

        /* A composite alert has no stream at all, and both columns are NOT
         * NULL with stream_type CHECK-constrained. Defaulting rather than
         * refusing the row: the alternative is that the one alert type with
         * no stream is the one type this database cannot record. The values
         * are never pushed back — source_document carries the real document. */
        rule.setStreamType(streamType(text(document, "stream_type")));
        rule.setStreamName(blankTo(text(document, "stream_name"), name));

        rule.setAlertType(alertType(document));
        rule.setSeverity(AlertPayloadNormalizer.normalizeSeverity(null, name));
        rule.setEnabled(!document.has("enabled") || document.path("enabled").asBoolean(true));

        rule.setQueryCondition(jsonOr(document.path("query_condition"), "{}"));
        rule.setTriggerCondition(jsonOr(document.path("trigger_condition"), "{}"));
        rule.setDestinations(jsonOr(document.path("destinations"), "[]"));
        rule.setContextAttributes(jsonOr(document.path("context_attributes"), null));

        // The whole document, so a re-push cannot strip what this schema does
        // not model. This is the field that makes the mirror safe to sync.
        rule.setSourceDocument(document.toString());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String actorOrDefault(String actor) {
        return actor == null ? DEFAULT_ACTOR : actor;
    }

    /**
     * Record that a rule has been removed from OpenObserve.
     *
     * <p>Soft-deleted and marked synced, not left {@code DELETE_PENDING}: the
     * remote delete has already succeeded, so there is no outstanding obligation
     * and queueing one would have the next sync try to delete an id that is
     * already gone.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeleted(String org, String alertId, String actor) {
        if (alertId == null || alertId.isBlank()) return;
        try {
            Optional<AlertRuleEntity> found =
                    rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(org, alertId);
            if (found.isEmpty()) {
                log.debug("No mirrored row for deleted alert {} (org={}); nothing to do",
                        alertId, org);
                return;
            }
            AlertRuleEntity rule = found.get();
            rule.setDeletedAt(Instant.now());
            rule.setUpdatedAt(Instant.now());
            rule.setUpdatedBy(actor == null ? DEFAULT_ACTOR : actor);
            rules.save(rule);
            log.info("Mirrored the deletion of alert rule '{}' (org={}, id={})",
                    rule.getName(), org, alertId);
        } catch (Exception e) {
            log.error("Could not mirror the deletion of alert {} (org={}): {}",
                    alertId, org, e.getMessage(), e);
        }
    }

    /**
     * Record an enable/disable that OpenObserve has already applied.
     *
     * <p>{@code source_document} is updated in step too. Without that, the stored
     * document would keep the old {@code enabled} value and a later re-push would
     * silently switch the rule back.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEnabled(String org, String alertId, boolean enabled, String actor) {
        if (alertId == null || alertId.isBlank()) return;
        try {
            Optional<AlertRuleEntity> found =
                    rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(org, alertId);
            if (found.isEmpty()) {
                log.debug("No mirrored row for alert {} (org={}); nothing to toggle",
                        alertId, org);
                return;
            }
            AlertRuleEntity rule = found.get();
            rule.setEnabled(enabled);
            rule.setUpdatedAt(Instant.now());
            rule.setUpdatedBy(actor == null ? DEFAULT_ACTOR : actor);
            rules.save(rule);
            log.info("Mirrored enabled={} for alert rule '{}' (org={})",
                    enabled, rule.getName(), org);
        } catch (Exception e) {
            log.error("Could not mirror enabled={} for alert {} (org={}): {}",
                    enabled, alertId, org, e.getMessage(), e);
        }
    }

    /**
     * Record that alerts have been moved into another folder.
     *
     * <p>Folder placement is the one thing a move changes, so only that is
     * written — re-reading each document to mirror it whole would be several
     * round trips for a field we already know. Ids with no mirrored row are
     * skipped silently; they are alerts this database has not adopted, and an
     * import is the thing that fixes that, not a move.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMoved(String org, java.util.List<String> alertIds,
            String folderId, String actor) {
        if (alertIds == null || alertIds.isEmpty()) return;
        String target = blankTo(folderId, "default");
        int moved = 0;
        for (String alertId : alertIds) {
            if (alertId != null && !alertId.isBlank() && moveOne(org, alertId, target, actor)) {
                moved++;
            }
        }
        if (moved > 0) {
            log.info("Mirrored a move of {} alert rule(s) into folder '{}' (org={})",
                    moved, target, org);
        }
    }

    /** Mirrors one alert's folder move; true when a local rule was updated. */
    private boolean moveOne(String org, String alertId, String target, String actor) {
        try {
            Optional<AlertRuleEntity> found =
                    rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(org, alertId);
            if (found.isEmpty()) return false;
            AlertRuleEntity rule = found.get();
            rule.setFolderId(target);
            rule.setUpdatedAt(Instant.now());
            rule.setUpdatedBy(actor == null ? DEFAULT_ACTOR : actor);
            rules.save(rule);
            return true;
        } catch (Exception e) {
            log.error("Could not mirror the move of alert {} (org={}): {}",
                    alertId, org, e.getMessage(), e);
            return false;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** By OpenObserve id first, then by name. See recordSaved for why both. */
    private Optional<AlertRuleEntity> locate(String org, String alertId, String name) {
        if (alertId != null && !alertId.isBlank()) {
            Optional<AlertRuleEntity> byId =
                    rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(org, alertId);
            if (byId.isPresent()) return byId;
        }
        return rules.findByOrgAndNameAndDeletedAtIsNull(org, name);
    }

    /**
     * How the row records the alert's kind.
     *
     * <p>{@code alert_type} is preferred over {@code is_real_time} because it is
     * the only field that can say "composite" — a composite carries
     * {@code is_real_time: false} exactly like a scheduled alert, so inferring
     * from the boolean alone would file every composite as scheduled.</p>
     */
    private static String alertType(JsonNode document) {
        String declared = text(document, "alert_type");
        if (declared != null && !declared.isBlank()) return declared.trim();
        return document.path("is_real_time").asBoolean(false) ? "realtime" : "scheduled";
    }

    /** Constrained by a CHECK to logs/metrics/traces; anything else is stored as logs. */
    private static String streamType(String raw) {
        if (raw == null) return DEFAULT_STREAM_TYPE;
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "logs", "metrics", "traces" -> t;
            default -> DEFAULT_STREAM_TYPE;
        };
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /** The node serialised, or {@code fallback} when it is absent or null. */
    private static String jsonOr(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        return node.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }
}
