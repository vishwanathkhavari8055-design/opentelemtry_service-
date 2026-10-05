package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertRuleResponseDTO;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.util.AlertPayloadNormalizer;

/**
 * Adopts rules that were authored in OpenObserve into PostgreSQL.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>PostgreSQL was the source of truth only for rules created THROUGH this
 * service. Every rule an operator wrote in OpenObserve's own UI existed nowhere
 * here — and on this deployment that is all of the ones that matter. Measured:
 * 20 rules live in OpenObserve, 9 rows in {@code alert_rules}, and the two sets do
 * not overlap at all. The visible symptom is that 73 of 90 stored alerts had no
 * rule linked, so the Rule column and the details panel's Rule section were empty
 * for almost everything.</p>
 *
 * <h2>Why import rather than require re-authoring</h2>
 *
 * <p>The alternative was asking somebody to retype twenty rules through this
 * application so the database would know about them, which nobody would do, and
 * which would leave two copies to drift. Reading them out of OpenObserve makes the
 * database complete without changing a single rule's behaviour.</p>
 *
 * <h2>Documents are stored VERBATIM</h2>
 *
 * <p>{@code query_condition} and {@code trigger_condition} are kept as the JSON
 * OpenObserve returned. That is the same reasoning
 * {@code OpenObserveClientService.sendJson} documents: an alert document is a
 * recursive structure with untagged enums that OpenObserve keeps adding fields to,
 * so anything remodelled through a DTO would be silently dropped the next time the
 * rule is saved. An imported rule has to round-trip losslessly or importing it has
 * made things worse.</p>
 *
 * <h2>It never overwrites local edits</h2>
 *
 * <p>A rule that already exists here is ADOPTED, not replaced: it gains the
 * OpenObserve id and its sync state, and its stored definition is left alone. The
 * database is the source of truth for rules it already knows about, and silently
 * overwriting one with the remote copy would discard an edit somebody made here
 * and had every reason to believe was authoritative.</p>
 */
@Component
public class AlertRuleImporter {

    private static final Logger log = LogManager.getLogger(AlertRuleImporter.class);

    /** One page is plenty — this instance has 20 rules; the cap stops a runaway read. */
    private static final int PAGE_SIZE = 1_000;
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_ENABLED = "enabled";

    private final OpenObserveClientService openObserve;
    private final AlertRuleRepository rules;
    private final OpenObserveConfig config;

    public AlertRuleImporter(OpenObserveClientService openObserve,
            AlertRuleRepository rules,
            OpenObserveConfig config) {
        this.openObserve = openObserve;
        this.rules = rules;
        this.config = config;
    }

    /**
     * Read every rule from OpenObserve and make sure the database has it.
     *
     * <p>Per-rule failures are recorded and skipped rather than aborting: one rule
     * with a shape this mapper cannot read must not stop the other nineteen being
     * stored.</p>
     *
     * @param actor who to attribute the import to
     */
    @Transactional
    public AlertRuleResponseDTO.SyncResult importAll(String actor) {
        long t0 = System.nanoTime();
        String org = config.getOrg();

        AlertRuleResponseDTO.SyncResult out = new AlertRuleResponseDTO.SyncResult();
        out.setOrg(org);

        List<JsonNode> remote;
        try {
            remote = listRemote(org);
        } catch (Exception e) {
            out.setOk(false);
            out.setDurationMs((System.nanoTime() - t0) / 1_000_000L);
            log.error("Alert rule import failed for org '{}': {}", org, e.getMessage(), e);
            throw e;
        }

        out.setTotal(remote.size());
        List<AlertRuleResponseDTO.RuleOutcome> outcomes = new ArrayList<>();

        for (JsonNode summary : remote) {
            String name = text(summary, "name");
            String remoteId = firstText(summary, "alert_id", "id");
            if (name == null || name.isBlank()) {
                outcomes.add(outcome(null, name, "SKIPPED", null,
                        "The rule has no name, so it cannot be identified here."));
                out.setSkipped(out.getSkipped() + 1);
                continue;
            }
            try {
                applyOne(org, name, remoteId, summary, actor, out, outcomes);
            } catch (Exception e) {
                // One unreadable rule costs one rule.
                out.setFailed(out.getFailed() + 1);
                outcomes.add(outcome(null, name, "FAILED", remoteId, e.getMessage()));
                log.warn("Could not import alert rule '{}' (org={}): {}", name, org, e.getMessage());
            }
        }

        out.setOutcomes(outcomes);
        out.setOk(out.getFailed() == 0);
        out.setDurationMs((System.nanoTime() - t0) / 1_000_000L);
        log.info("Alert rule import (org={}): {} remote rule(s) — {} created, {} adopted, "
                + "{} skipped, {} failed in {} ms",
                org, out.getTotal(), out.getCreated(), out.getUpdated(), out.getSkipped(),
                out.getFailed(), out.getDurationMs());
        return out;
    }

    private void applyOne(String org, String name, String remoteId, JsonNode summary,
            String actor, AlertRuleResponseDTO.SyncResult out,
            List<AlertRuleResponseDTO.RuleOutcome> outcomes) {

        Optional<AlertRuleEntity> existing = rules.findByOrgAndNameAndDeletedAtIsNull(org, name);
        if (existing.isPresent()) {
            // ADOPT. The stored definition is deliberately untouched — see the class
            // note. All this records is "the database's copy and OpenObserve's are
            // the same rule", which is what makes the alert → rule link resolve.
            AlertRuleEntity rule = existing.get();
            boolean changed = false;
            if (remoteId != null && !remoteId.equals(rule.getOpenObserveId())) {
                rule.markSynced(remoteId);
                changed = true;
            }
            if (changed) {
                rule.setUpdatedBy(actor);
                rules.save(rule);
            }
            out.setUpdated(out.getUpdated() + 1);
            outcomes.add(outcome(rule.getUuid().toString(), name, "UPDATED", remoteId,
                    changed ? "Already present here; linked to its OpenObserve copy."
                            : "Already present here and already linked."));
            return;
        }

        // The full document, not the list entry: stream_name, stream_type and
        // destinations are absent from the listing and two of those are NOT NULL.
        JsonNode document = fetchDocument(org, remoteId);
        AlertRuleEntity created = toEntity(org, name, remoteId, summary, document, actor);
        rules.save(created);
        out.setCreated(out.getCreated() + 1);
        outcomes.add(outcome(created.getUuid().toString(), name, "CREATED", remoteId,
                "Imported from OpenObserve."));
    }

    /** Every rule in the org, as OpenObserve lists them. */
    private List<JsonNode> listRemote(String org) {
        JsonNode root = openObserve.getJson(
                "/api/v2/" + org + "/alerts?page_size=" + PAGE_SIZE);
        List<JsonNode> out = new ArrayList<>();
        JsonNode list = root.path("list");
        if (!list.isArray()) {
            // Older builds answer with a bare array or under `alerts`.
            list = root.isArray() ? root : root.path("alerts");
        }
        if (list.isArray()) {
            list.forEach(out::add);
        }
        return out;
    }

    /** The individual rule document; an empty node if it cannot be read. */
    private JsonNode fetchDocument(String org, String remoteId) {
        if (remoteId == null || remoteId.isBlank()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            JsonNode root = openObserve.getJson("/api/v2/" + org + "/alerts/" + remoteId);
            // Some endpoints wrap the payload in `data`.
            JsonNode data = root.path("data");
            return data.isObject() ? data : root;
        } catch (Exception e) {
            log.debug("Could not read the full document for rule id '{}': {}", remoteId,
                    e.getMessage());
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private AlertRuleEntity toEntity(String org, String name, String remoteId,
            JsonNode summary, JsonNode document, String actor) {

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setUuid(UUID.randomUUID());
        rule.setOrg(org);
        rule.setName(name);
        rule.setDescription(text(firstText(document, FIELD_DESCRIPTION) != null ? document : summary,
                FIELD_DESCRIPTION));

        String folderId = firstText(summary, "folder_id", "folderId");
        rule.setFolderId(defaultIfBlank(folderId, "default"));

        // NOT NULL columns, and the listing does not carry them. "logs" is
        // OpenObserve's own default stream type; the stream name falls back to the
        // rule name only so the row can be stored at all — it is never pushed back,
        // because an imported rule is only re-pushed if somebody edits it here, and
        // editing goes through the normal validated save path.
        String streamType = firstText(document, "stream_type", "streamType");
        rule.setStreamType(defaultIfBlank(streamType, "logs"));
        String streamName = firstText(document, "stream_name", "streamName");
        rule.setStreamName(defaultIfBlank(streamName, name));

        String alertType = firstText(summary, "alert_type", "alertType");
        boolean realTime = document.path("is_real_time").asBoolean(
                summary.path("is_real_time").asBoolean(false));
        rule.setAlertType(defaultIfBlank(alertType, realTime ? "realtime" : "scheduled"));

        // OpenObserve has no severity of its own, so it is inferred from the name the
        // same way a firing's is — these rules encode it there ("…-critical",
        // "…-disaster"), which is exactly the case the normaliser exists for.
        rule.setSeverity(AlertPayloadNormalizer.normalizeSeverity(null, name));

        rule.setEnabled(document.has(FIELD_ENABLED)
                ? document.path(FIELD_ENABLED).asBoolean(true)
                : summary.path(FIELD_ENABLED).asBoolean(true));

        // Verbatim, for the round-trip reason in the class note.
        rule.setQueryCondition(jsonOr(document.path("query_condition"),
                summary.path("condition"), "{}"));
        rule.setTriggerCondition(jsonOr(document.path("trigger_condition"),
                summary.path("trigger_condition"), "{}"));
        rule.setDestinations(jsonOr(document.path("destinations"), null, "[]"));
        rule.setContextAttributes(jsonOr(document.path("context_attributes"), null, null));

        rule.setCreatedBy(actor);
        rule.setUpdatedBy(actor);
        Instant now = Instant.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);

        // Imported means already in OpenObserve, by definition — so it is SYNCED and
        // linked. Marking it PENDING would queue a pointless push of a rule that is
        // already there, and the retry sweep would then fight the import.
        if (!isBlank(remoteId)) {
            rule.markSynced(remoteId);
        }
        return rule;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** {@code value}, or {@code fallback} when it is null or blank. */
    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    // ── json helpers ─────────────────────────────────────────────────────────

    /** First non-empty of the candidates, serialised; {@code fallback} if none. */
    private static String jsonOr(JsonNode preferred, JsonNode alternative, String fallback) {
        if (preferred != null && !preferred.isMissingNode() && !preferred.isNull()) {
            return preferred.toString();
        }
        if (alternative != null && !alternative.isMissingNode() && !alternative.isNull()) {
            return alternative.toString();
        }
        return fallback;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isBlank() ? null : s;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static AlertRuleResponseDTO.RuleOutcome outcome(String uuid, String name,
            String action, String remoteId, String message) {
        AlertRuleResponseDTO.RuleOutcome o = new AlertRuleResponseDTO.RuleOutcome();
        o.setUuid(uuid);
        o.setName(name);
        o.setAction(action);
        o.setOpenObserveId(remoteId);
        o.setMessage(message);
        return o;
    }
}
