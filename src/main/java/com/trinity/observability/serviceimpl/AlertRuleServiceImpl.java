package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.config.AlertPersistenceConfig;
import com.trinity.observability.config.AlertRuleSyncProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertRuleRequestDTO;
import com.trinity.observability.dto.alerts.AlertRuleResponseDTO;
import com.trinity.observability.dto.alerts.AlertsRequestDTO;
import com.trinity.observability.dto.alerts.AlertsResponseDTO;
import com.trinity.observability.entity.ActivationMode;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.entity.AlertRuleEventEntity;
import com.trinity.observability.entity.SyncStatus;
import com.trinity.observability.exception.OpenObserveQueryException;
import com.trinity.observability.exception.ResourceConflictException;
import com.trinity.observability.exception.ResourceNotFoundException;
import com.trinity.observability.repository.AlertRuleEventRepository;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.service.AlertHistoryDestinationService;
import com.trinity.observability.service.AlertRuleService;
import com.trinity.observability.service.AlertsService;
import com.trinity.observability.util.SeverityLadder;

/**
 * PostgreSQL-owned alert rules, pushed into OpenObserve for evaluation.
 *
 * <h2>Transaction boundaries</h2>
 *
 * <p>Database work runs inside {@link TransactionTemplate} blocks and the
 * OpenObserve call happens strictly <em>between</em> them, never inside one.
 * This matters under load: a transaction spanning the HTTP call would pin a
 * pooled connection for the round trip, so a slow OpenObserve would drain the
 * connection pool and take PostgreSQL reads down with it — an outage in the
 * evaluation engine becoming an outage in the source of truth.</p>
 *
 * <p>{@link TransactionTemplate} rather than {@code @Transactional} on private
 * helpers, because Spring's annotation is proxy-based and does nothing when a
 * method calls its own class's method — a silent no-op that looks correct.</p>
 *
 * <h2>Reuse of {@link AlertsService}</h2>
 *
 * <p>The OpenObserve HTTP details — v2 paths, folder placement, {@code org_id}
 * forcing, stripping server-owned fields — already live in
 * {@link AlertsServiceImpl} and are non-obvious. This class composes that
 * rather than restating it, so the two paths cannot drift into disagreeing
 * about what a valid alert document is.</p>
 */
@Service
@ConditionalOnBean(AlertPersistenceConfig.class)
public class AlertRuleServiceImpl implements AlertRuleService {

    private static final Logger log = LogManager.getLogger(AlertRuleServiceImpl.class);
    private static final String ACTION_FAILED = "FAILED";

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_SYNC_ERROR_CHARS = 2000;
    private static final String DEFAULT_FOLDER = "default";
    private static final String DEFAULT_ALERT_TYPE = "scheduled";
    private static final String DEFAULT_SEVERITY = "warning";
    private static final String SYSTEM_ACTOR = "system";

    /**
     * How far in the past an {@code activateAt} may be before it is refused.
     *
     * <p>Not zero: the browser's clock and this server's differ by a second or
     * two routinely, and rejecting "start at 09:00" typed at 08:59:59 as a past
     * time would be indefensible. Small enough that a genuinely stale date is
     * still caught.</p>
     */
    private static final long CLOCK_SKEW_GRACE_SECONDS = 120L;

    /** Rules activated per sweep, so a large backlog cannot monopolise a poll. */
    private static final int ACTIVATION_BATCH_SIZE = 100;

    private static final String RESOURCE_TYPE_ALERT_RULE = "Alert rule";

    private final AlertRuleRepository repository;
    private final AlertRuleEventRepository ruleEvents;
    private final AlertsService alertsService;
    private final AlertHistoryDestinationService historyDestinations;
    private final AlertRuleSyncProperties syncProperties;
    private final OpenObserveConfig config;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public AlertRuleServiceImpl(AlertRuleRepository repository,
                                AlertRuleEventRepository ruleEvents,
                                AlertsService alertsService,
                                AlertHistoryDestinationService historyDestinations,
                                AlertRuleSyncProperties syncProperties,
                                OpenObserveConfig config,
                                TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.ruleEvents = ruleEvents;
        this.alertsService = alertsService;
        this.historyDestinations = historyDestinations;
        this.syncProperties = syncProperties;
        this.config = config;
        this.tx = transactionTemplate;
    }

    /**
     * Appends to the rule audit trail, never failing the operation it describes.
     *
     * <p>Its own transaction, so the record survives even when the caller's work
     * rolls back — which is exactly the case that most needs recording: a create
     * rejected because OpenObserve refused it leaves no rule behind, and without
     * this there would be no trace that anybody tried.</p>
     */
    private void audit(Long ruleId, String org, AlertRuleEventEntity.Type type,
            String actor, String detail, SyncStatus syncStatus) {
        if (ruleId == null) {
            return;
        }
        try {
            tx.execute(status -> ruleEvents.save(AlertRuleEventEntity.of(
                    ruleId, org, type, actorOf(actor), truncate(detail, MAX_SYNC_ERROR_CHARS),
                    syncStatus)));
        } catch (Exception e) {
            log.warn("Could not record rule audit event {} for rule {}: {}", type, ruleId, e.getMessage());
        }
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Override
    public AlertRuleResponseDTO.WriteResult create(AlertRuleRequestDTO.SaveRule request) {
        String org = config.getOrg();
        String name = request.getName().trim();

        AlertRuleEntity stored = tx.execute(status -> {
            if (repository.existsByOrgAndNameAndDeletedAtIsNull(org, name)) {
                throw new ResourceConflictException(
                        "An alert rule named '" + name + "' already exists in org " + org);
            }
            AlertRuleEntity entity = new AlertRuleEntity();
            entity.setUuid(UUID.randomUUID());
            entity.setOrg(org);
            applyRequest(entity, request, name);
            entity.setCreatedBy(actorOf(request.getActor()));
            entity.setUpdatedBy(actorOf(request.getActor()));
            entity.setSyncStatus(SyncStatus.PENDING);
            return repository.save(entity);
        });
        if (stored == null) {
            throw new IllegalStateException("Failed to persist alert rule");
        }

        log.info("Alert rule stored: uuid={} name='{}' org={} (PostgreSQL is now the source of truth)",
                stored.getUuid(), name, org);

        AlertRuleEntity after = pushToOpenObserve(stored.getUuid(), false);
        if (after == null) {
            throw new IllegalStateException("pushToOpenObserve returned null");
        }

        if (after.getSyncStatus() != SyncStatus.SYNCED && syncProperties.isFailCreateOnSyncError()) {
            // ATOMIC CREATE: a rule that exists here but is evaluating nowhere is a
            // rule somebody believes is protecting them. Rather than leave one
            // behind, the insert is undone and the caller is told.
            //
            // A COMPENSATING DELETE rather than one transaction spanning the push:
            // holding a write transaction (and a pooled connection) open across an
            // HTTP call to OpenObserve would let a slow upstream exhaust the pool
            // and take down every other endpoint. The row was never returned to
            // anyone, so removing it is indistinguishable from a rollback — except
            // that the audit trail keeps the attempt.
            audit(after.getId(), org, AlertRuleEventEntity.Type.SYNC_FAILED,
                    request.getActor(), after.getSyncError(), after.getSyncStatus());
            String reason = after.getSyncError();
            tx.execute(status -> {
                repository.findById(after.getId()).ifPresent(repository::delete);
                return null;
            });
            log.error("Alert rule '{}' rejected — OpenObserve refused it and the local row has been "
                    + "removed so nothing half-created survives: {}", name, reason);
            throw new OpenObserveQueryException(0,
                    "The rule could not be created in OpenObserve, so it was not saved: " + reason,
                    "n/a");
        }

        audit(after.getId(), org, AlertRuleEventEntity.Type.CREATED,
                request.getActor(), "Rule created", after.getSyncStatus());
        return writeResult(after, after.getSyncStatus() == SyncStatus.SYNCED
                ? "Alert rule created and synced to OpenObserve"
                : "Alert rule created; OpenObserve sync failed — see syncError. "
                  + "The rule is stored and will be retried automatically.");
    }

    // ── update ──────────────────────────────────────────────────────────────

    @Override
    public AlertRuleResponseDTO.WriteResult update(UUID uuid, AlertRuleRequestDTO.SaveRule request) {
        String org = config.getOrg();
        String name = request.getName().trim();

        AlertRuleEntity stored = tx.execute(status -> {
            AlertRuleEntity entity = requireLive(uuid);
            // A rename must not collide with a different live rule. Comparing
            // uuid, not name, so re-saving a rule under its own name is fine.
            Optional<AlertRuleEntity> clash = repository.findByOrgAndNameAndDeletedAtIsNull(org, name);
            if (clash.isPresent() && !clash.get().getUuid().equals(uuid)) {
                throw new ResourceConflictException(
                        "Another alert rule named '" + name + "' already exists in org " + org);
            }
            applyRequest(entity, request, name);
            entity.setUpdatedBy(actorOf(request.getActor()));
            // Back to PENDING: the stored definition no longer matches what
            // OpenObserve is evaluating until the push below succeeds.
            entity.setSyncStatus(SyncStatus.PENDING);
            return repository.save(entity);
        });
        if (stored == null) {
            throw new IllegalStateException("Failed to update alert rule");
        }

        log.info("Alert rule updated: uuid={} name='{}' org={}", uuid, name, org);

        AlertRuleEntity after = pushToOpenObserve(stored.getUuid(), false);
        if (after == null) {
            throw new IllegalStateException("pushToOpenObserve returned null");
        }

        if (after.getSyncStatus() != SyncStatus.SYNCED && syncProperties.isFailUpdateOnSyncError()) {
            audit(after.getId(), org, AlertRuleEventEntity.Type.SYNC_FAILED,
                    request.getActor(), after.getSyncError(), after.getSyncStatus());
            throw new OpenObserveQueryException(0,
                    "The rule was not updated in OpenObserve: " + after.getSyncError(), "n/a");
        }

        audit(after.getId(), org, AlertRuleEventEntity.Type.UPDATED,
                request.getActor(), "Rule updated", after.getSyncStatus());
        // Update is TOLERANT by default, unlike create. On create there is nothing
        // to lose — the operator still has the form open. Here the edit is already
        // stored, and discarding it to punish a transient OpenObserve outage throws
        // away work that succeeded. The rule is marked out of sync and the retry
        // sweep pushes it when OpenObserve comes back; the list shows that state
        // rather than implying the rule is live.
        return writeResult(after, after.getSyncStatus() == SyncStatus.SYNCED
                ? "Alert rule updated and synced to OpenObserve"
                : "Alert rule updated here, but OpenObserve rejected the push — see syncError. "
                  + "OpenObserve is still evaluating the PREVIOUS definition; the retry sweep "
                  + "will push this one.");
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Override
    public AlertRuleResponseDTO.WriteResult delete(UUID uuid, String actor) {
        AlertRuleEntity marked = tx.execute(status -> {
            AlertRuleEntity entity = requireLive(uuid);
            entity.setDeletedAt(Instant.now());
            entity.setUpdatedBy(actorOf(actor));
            // DELETE_PENDING until OpenObserve confirms. A rule deleted here but
            // still live there would keep firing with nothing in this database
            // to explain it, so the obligation is recorded rather than assumed.
            entity.setSyncStatus(entity.isLinked() ? SyncStatus.DELETE_PENDING : SyncStatus.SYNCED);
            return repository.save(entity);
        });
        if (marked == null) {
            throw new IllegalStateException("Failed to mark alert rule for deletion");
        }

        if (!marked.isLinked()) {
            log.info("Alert rule deleted: uuid={} (never existed in OpenObserve)", uuid);
            audit(marked.getId(), marked.getOrg(), AlertRuleEventEntity.Type.DELETED,
                    actor, "Rule deleted; it had never been created in OpenObserve",
                    marked.getSyncStatus());
            return writeResult(marked, "Alert rule deleted");
        }

        AlertRuleEntity after = removeFromOpenObserve(marked);
        if (after == null) {
            throw new IllegalStateException("removeFromOpenObserve returned null");
        }
        audit(after.getId(), after.getOrg(), AlertRuleEventEntity.Type.DELETED, actor,
                after.getSyncStatus() == SyncStatus.SYNCED
                        ? "Rule deleted here and in OpenObserve"
                        : "Rule deleted here; OpenObserve removal failed: " + after.getSyncError(),
                after.getSyncStatus());
        return writeResult(after, after.getSyncStatus() == SyncStatus.SYNCED
                ? "Alert rule deleted here and in OpenObserve"
                : "Alert rule deleted here; OpenObserve removal failed and will be retried "
                  + "by the next sync — the rule may still fire until then.");
    }

    // ── enable / disable ────────────────────────────────────────────────────

    @Override
    public AlertRuleResponseDTO.WriteResult setEnabled(UUID uuid, boolean enabled, String actor) {
        AlertRuleEntity stored = tx.execute(status -> {
            AlertRuleEntity entity = requireLive(uuid);
            if (enabled) {
                // Turning a staged rule on by hand settles whatever was holding
                // it back. Leaving the schedule in place would mean the
                // activation sweep picks the rule up again the next time
                // somebody disables it — re-enabling a rule that was
                // deliberately switched off.
                entity.markActivated(Instant.now());
            } else {
                // An explicit off is NOT staging. Recording it as such would
                // make a suppressed rule read as "starts later" in the list.
                entity.setEnabled(false);
                entity.setActivationMode(ActivationMode.NOW);
                entity.setActivateAt(null);
            }
            entity.setUpdatedBy(actorOf(actor));
            entity.setSyncStatus(SyncStatus.PENDING);
            return repository.save(entity);
        });
        if (stored == null) {
            throw new IllegalStateException("Failed to update alert rule enabled status");
        }

        AlertRuleEntity after = pushToOpenObserve(stored.getUuid(), false);
        if (after == null) {
            throw new IllegalStateException("pushToOpenObserve returned null");
        }
        audit(after.getId(), after.getOrg(),
                enabled ? AlertRuleEventEntity.Type.ENABLED : AlertRuleEventEntity.Type.DISABLED,
                actor, enabled ? "Rule enabled" : "Rule disabled", after.getSyncStatus());
        return writeResult(after, (enabled ? "Alert rule enabled" : "Alert rule disabled")
                + (after.getSyncStatus() == SyncStatus.SYNCED ? "" : " locally; OpenObserve sync failed"));
    }

    // ── retry sweep ─────────────────────────────────────────────────────────

    /**
     * Pushes rules that are not in step with OpenObserve.
     *
     * <p>This is what makes "no manual synchronisation should be required" true.
     * Without it, a rule left PENDING by a tolerated update failure — or a delete
     * obligation OpenObserve refused — would sit there until somebody noticed and
     * pressed a button, which is exactly the manual step the requirement removes.</p>
     *
     * <p>Batched, and it re-reads what OpenObserve actually holds rather than
     * trusting the stored id, so a wiped instance is repaired rather than reported
     * as a set of successful updates to alerts that do not exist.</p>
     */
    @Scheduled(fixedDelayString = "${alerts.rule-sync.retry-interval-ms:300000}",
               initialDelayString = "${alerts.rule-sync.retry-initial-delay-ms:60000}")
    public void retryOutOfSyncRules() {
        try {
            String org = config.getDefaultOrg();
            List<AlertRuleEntity> stale = tx.execute(status ->
                    repository.findOutOfSync(org, PageRequest.of(0, syncProperties.getRetryBatchSize())));
            if (stale == null || stale.isEmpty()) {
                return;
            }

            Set<String> remoteIds = fetchRemoteAlertIds();
            int fixed = 0;
            for (AlertRuleEntity rule : stale) {
                if (retryOne(rule, remoteIds)) {
                    fixed++;
                }
            }
            if (fixed > 0) {
                log.info("Rule retry sweep synchronised {} of {} out-of-sync rule(s)", fixed, stale.size());
            }
        } catch (Exception e) {
            // A sweep that throws would stop rescheduling nothing — Spring keeps the
            // schedule — but it would lose the reason, and this is the mechanism that
            // makes manual sync unnecessary, so its failures must be visible.
            log.error("Rule retry sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Settles one out-of-sync rule for the retry sweep.
     *
     * @return true when the rule is now SYNCED with OpenObserve (a settled delete
     *         obligation is not counted)
     */
    private boolean retryOne(AlertRuleEntity rule, Set<String> remoteIds) {
        if (rule.getDeletedAt() != null) {
            // A delete obligation. Settle it whether or not it is still there.
            if (remoteIds.contains(rule.getOpenObserveId())) {
                removeFromOpenObserve(rule);
            }
            return false;
        }
        boolean existsRemotely = rule.isLinked() && remoteIds.contains(rule.getOpenObserveId());
        AlertRuleEntity after = pushToOpenObserve(rule.getUuid(), !existsRemotely);
        if (after != null && after.getSyncStatus() == SyncStatus.SYNCED) {
            audit(after.getId(), after.getOrg(), AlertRuleEventEntity.Type.SYNCED,
                    SYSTEM_ACTOR, "Pushed by the retry sweep", after.getSyncStatus());
            return true;
        }
        return false;
    }

    @Override
    public AlertRuleResponseDTO.WriteResult setActivation(UUID uuid, String activation,
            String activateAt, String actor) {

        ActivationMode mode = parseActivation(activation);
        if (mode == null) {
            throw new IllegalArgumentException(
                    "activation is required (NOW, LATER or SCHEDULED)");
        }
        // Parsed and range-checked BEFORE the transaction opens, so a bad date
        // costs a 400 rather than a write that has to be rolled back.
        final Instant scheduledFor = mode == ActivationMode.SCHEDULED
                ? requireScheduledAt(activateAt) : null;
        AlertRuleEntity stored = tx.execute(status -> {
            AlertRuleEntity entity = requireLive(uuid);
            applyMode(entity, mode, scheduledFor);
            entity.setUpdatedBy(actorOf(actor));
            entity.setSyncStatus(SyncStatus.PENDING);
            return repository.save(entity);
        });
        if (stored == null) {
            throw new IllegalStateException("Failed to update activation mode");
        }

        AlertRuleEntity after = pushToOpenObserve(stored.getUuid(), false);
        if (after == null) {
            throw new IllegalStateException("pushToOpenObserve returned null");
        }
        audit(after.getId(), after.getOrg(),
                after.isEnabled() ? AlertRuleEventEntity.Type.ENABLED
                                  : AlertRuleEventEntity.Type.DISABLED,
                actor, "Activation set to " + mode
                        + (scheduledFor == null ? "" : " (activateAt=" + scheduledFor + ")"),
                after.getSyncStatus());

        String what = activationMessage(mode, scheduledFor);
        return writeResult(after, what + (after.getSyncStatus() == SyncStatus.SYNCED
                ? "" : "; OpenObserve sync failed — see syncError"));
    }

    /** The start time a SCHEDULED activation request must carry, parsed and range-checked. */
    private static Instant requireScheduledAt(String activateAt) {
        Instant at = parseActivateAt(activateAt);
        if (at == null) {
            throw new IllegalArgumentException(
                    "activateAt is required when activation is SCHEDULED");
        }
        requireNotPast(at);
        return at;
    }

    /** Refuses a start time already behind us (beyond the clock-skew grace). */
    private static void requireNotPast(Instant at) {
        if (at.isBefore(Instant.now().minusSeconds(CLOCK_SKEW_GRACE_SECONDS))) {
            throw new IllegalArgumentException("activateAt is in the past (" + at
                    + "). To start the rule now, send activation=NOW instead.");
        }
    }

    /** Applies an explicit activation change to a stored rule. */
    private static void applyMode(AlertRuleEntity entity, ActivationMode mode, Instant scheduledFor) {
        switch (mode) {
            case NOW -> entity.markActivated(Instant.now());
            case LATER -> {
                entity.setEnabled(false);
                entity.setActivationMode(ActivationMode.LATER);
                entity.setActivateAt(null);
            }
            case SCHEDULED -> {
                entity.setEnabled(false);
                entity.setActivationMode(ActivationMode.SCHEDULED);
                entity.setActivateAt(scheduledFor);
            }
        }
    }

    private static String activationMessage(ActivationMode mode, Instant scheduledFor) {
        return switch (mode) {
            case NOW -> "Alert rule is live now";
            case LATER -> "Alert rule is staged — it is stored and NOT evaluating until "
                          + "somebody enables it";
            case SCHEDULED -> "Alert rule is staged — it goes live at " + scheduledFor;
        };
    }

    // ── scheduled activation ────────────────────────────────────────────────

    /**
     * Turns on rules whose start time has arrived.
     *
     * <p>This is the half of "enable later" that nobody is present for. A rule
     * saved with {@code activation=SCHEDULED} is stored disabled and pushed to
     * OpenObserve disabled, so it is genuinely not evaluating; this flips it
     * when its time comes and pushes the change.</p>
     *
     * <p>Runs more often than the retry sweep because the error it controls is
     * different in kind: a rule pushed a few minutes late is a few minutes of
     * missed coverage the operator explicitly asked for, whereas a retry landing
     * late costs nothing that was not already broken. A minute is well inside
     * the granularity anyone schedules at and costs one indexed query.</p>
     *
     * <p>{@code LATER} rules are untouched by design — they are waiting for a
     * person, and a timer deciding on their behalf would defeat the whole point
     * of the option.</p>
     */
    @Scheduled(fixedDelayString = "${alerts.rule-sync.activation-interval-ms:60000}",
               initialDelayString = "${alerts.rule-sync.activation-initial-delay-ms:30000}")
    public void activateDueRules() {
        try {
            String org = config.getDefaultOrg();
            Instant now = Instant.now();
            List<AlertRuleEntity> due = tx.execute(status -> repository.findDueForActivation(
                    org, now, PageRequest.of(0, ACTIVATION_BATCH_SIZE)));
            if (due == null || due.isEmpty()) {
                return;
            }

            for (AlertRuleEntity rule : due) {
                UUID uuid = rule.getUuid();
                Instant scheduledFor = rule.getActivateAt();
                // Flip locally FIRST, in its own transaction. If the push then
                // fails the rule is left enabled-but-PENDING, which the retry
                // sweep already knows how to finish — whereas activating only on
                // a successful push would silently skip the rule and leave it
                // waiting on a time that has already gone by.
                tx.execute(status -> {
                    AlertRuleEntity fresh = requireAny(uuid);
                    fresh.markActivated(Instant.now());
                    fresh.setUpdatedBy(SYSTEM_ACTOR);
                    fresh.setSyncStatus(SyncStatus.PENDING);
                    return repository.save(fresh);
                });

                AlertRuleEntity after = pushToOpenObserve(uuid, false);
                if (after != null) {
                    boolean ok = after.getSyncStatus() == SyncStatus.SYNCED;
                    log.info("Alert rule '{}' (uuid={}) activated on schedule (due {}), OpenObserve push {}",
                            after.getName(), uuid, scheduledFor, ok ? "succeeded" : "FAILED — will be retried");
                    audit(after.getId(), after.getOrg(), AlertRuleEventEntity.Type.ENABLED,
                            SYSTEM_ACTOR, "Activated on schedule (activateAt=" + scheduledFor + ")",
                            after.getSyncStatus());
                }
            }
        } catch (Exception e) {
            // Swallowed for the same reason the retry sweep swallows: Spring
            // keeps rescheduling either way, and losing the reason is worse than
            // the failure. An alert that did not start on time is exactly the
            // kind of thing nobody notices until it is needed.
            log.error("Scheduled activation sweep failed: {}", e.getMessage(), e);
        }
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Override
    public AlertRuleResponseDTO.Rule get(UUID uuid) {
        return tx.execute(status -> toDto(requireLive(uuid)));
    }

    @Override
    public AlertRuleResponseDTO.Page<AlertRuleResponseDTO.Rule> list(
            AlertRuleRequestDTO.ListFilter filter) {

        String org = config.getOrg();
        int page = filter.getPage() == null || filter.getPage() < 0 ? 0 : filter.getPage();
        int size = clampPageSize(filter.getSize());

        SyncStatus syncStatus = parseSyncStatus(filter.getSyncStatus());
        String search = blankToNull(filter.getSearch());

        org.springframework.data.domain.Page<AlertRuleEntity> result = repository.search(
                org,
                filter.getEnabled(),
                blankToNull(filter.getStreamType()),
                blankToNull(filter.getSeverity()),
                syncStatus,
                search,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name")));

        AlertRuleResponseDTO.Page<AlertRuleResponseDTO.Rule> out = new AlertRuleResponseDTO.Page<>();
        out.setOrg(org);
        out.setPage(page);
        out.setSize(size);
        out.setTotal(result.getTotalElements());
        result.getContent().forEach(e -> out.getItems().add(toDto(e)));

        log.info("list() returning {} of {} alert rule(s) for org={}",
                out.getItems().size(), out.getTotal(), org);
        return out;
    }

    // ── sync ────────────────────────────────────────────────────────────────

    @Override
    public AlertRuleResponseDTO.SyncResult syncAll() {
        long t0 = System.nanoTime();
        String org = config.getOrg();

        AlertRuleResponseDTO.SyncResult result = new AlertRuleResponseDTO.SyncResult();
        result.setOrg(org);

        // What OpenObserve currently holds, read once.
        //
        // This single call is what makes recovery work. Deciding create-vs-update
        // from our own openobserve_id alone would issue a PUT for every rule
        // after OpenObserve lost its data — each one a 404 — and recover
        // nothing. Asking what actually exists means a wiped instance is simply
        // a set of rules that are all missing, and they get created.
        Set<String> remoteIds = fetchRemoteAlertIds();

        List<AlertRuleEntity> live = repository.findAllLiveForSync(org);
        List<AlertRuleEntity> pendingDeletes = repository.findPendingDeletionsIncludingDeleted(org);
        result.setTotal(live.size() + pendingDeletes.size());

        for (AlertRuleEntity rule : live) {
            result.getOutcomes().add(syncLiveRule(rule, remoteIds, result));
        }

        for (AlertRuleEntity rule : pendingDeletes) {
            result.getOutcomes().add(syncPendingDelete(rule, remoteIds, result));
        }

        result.setOk(result.getFailed() == 0);
        result.setDurationMs((System.nanoTime() - t0) / 1_000_000L);
        log.info("syncAll() org={} total={} created={} updated={} deleted={} failed={} in {} ms",
                org, result.getTotal(), result.getCreated(), result.getUpdated(),
                result.getDeleted(), result.getFailed(), result.getDurationMs());
        return result;
    }

    /** Pushes one live rule, creating or updating it remotely, and tallies the outcome. */
    private AlertRuleResponseDTO.RuleOutcome syncLiveRule(AlertRuleEntity rule, Set<String> remoteIds,
                                                          AlertRuleResponseDTO.SyncResult result) {
        boolean existsRemotely = rule.isLinked() && remoteIds.contains(rule.getOpenObserveId());
        AlertRuleEntity after = pushToOpenObserve(rule.getUuid(), !existsRemotely);
        if (after == null) {
            AlertRuleResponseDTO.RuleOutcome outcome = outcomeFor(rule);
            outcome.setAction(ACTION_FAILED);
            outcome.setMessage("pushToOpenObserve returned null");
            result.setFailed(result.getFailed() + 1);
            return outcome;
        }

        AlertRuleResponseDTO.RuleOutcome outcome = outcomeFor(after);
        if (after.getSyncStatus() != SyncStatus.SYNCED) {
            markFailed(outcome, after, result);
        } else if (existsRemotely) {
            outcome.setAction("UPDATED");
            result.setUpdated(result.getUpdated() + 1);
        } else {
            outcome.setAction("CREATED");
            result.setCreated(result.getCreated() + 1);
        }
        return outcome;
    }

    /** Settles one pending remote delete and tallies the outcome. */
    private AlertRuleResponseDTO.RuleOutcome syncPendingDelete(AlertRuleEntity rule, Set<String> remoteIds,
                                                               AlertRuleResponseDTO.SyncResult result) {
        AlertRuleResponseDTO.RuleOutcome outcome = outcomeFor(rule);
        if (!remoteIds.contains(rule.getOpenObserveId())) {
            // Already gone remotely — settle the obligation rather than
            // reporting a failure for a delete that has in fact happened.
            AlertRuleEntity settled = tx.execute(s -> {
                AlertRuleEntity e = repository.findById(rule.getId()).orElse(rule);
                e.setSyncStatus(SyncStatus.SYNCED);
                e.setSyncError(null);
                e.setLastSyncedAt(Instant.now());
                return repository.save(e);
            });
            outcome.setAction("DELETED");
            outcome.setMessage("Already absent in OpenObserve");
            result.setDeleted(result.getDeleted() + 1);
            if (settled != null) {
                log.debug("sync: delete obligation for uuid={} settled ({})",
                        settled.getUuid(), settled.getSyncStatus());
            }
            return outcome;
        }
        AlertRuleEntity after = removeFromOpenObserve(rule);
        if (after == null) {
            outcome.setAction(ACTION_FAILED);
            outcome.setMessage("removeFromOpenObserve returned null");
            result.setFailed(result.getFailed() + 1);
            return outcome;
        }
        if (after.getSyncStatus() == SyncStatus.SYNCED) {
            outcome.setAction("DELETED");
            result.setDeleted(result.getDeleted() + 1);
        } else {
            markFailed(outcome, after, result);
        }
        return outcome;
    }

    private static void markFailed(AlertRuleResponseDTO.RuleOutcome outcome, AlertRuleEntity after,
                                   AlertRuleResponseDTO.SyncResult result) {
        outcome.setAction(ACTION_FAILED);
        outcome.setMessage(after.getSyncError());
        result.setFailed(result.getFailed() + 1);
    }

    @Override
    public AlertRuleResponseDTO.WriteResult syncOne(UUID uuid) {
        AlertRuleEntity rule = tx.execute(status -> requireLive(uuid));
        if (rule == null) {
            throw new ResourceNotFoundException("Alert rule not found: " + uuid);
        }
        boolean existsRemotely = rule.isLinked() && fetchRemoteAlertIds().contains(rule.getOpenObserveId());
        AlertRuleEntity after = pushToOpenObserve(uuid, !existsRemotely);
        if (after == null) {
            throw new IllegalStateException("pushToOpenObserve returned null");
        }
        return writeResult(after, after.getSyncStatus() == SyncStatus.SYNCED
                ? "Alert rule synced to OpenObserve"
                : "Sync failed — see syncError");
    }

    // ── OpenObserve interaction ─────────────────────────────────────────────

    /**
     * Pushes one rule to OpenObserve and records the outcome.
     *
     * <p>Runs outside any transaction. The two short transactions around it
     * re-read the entity by id rather than reusing a detached instance, so the
     * write records the row as it is now and cannot resurrect stale field
     * values that changed while the HTTP call was in flight.</p>
     *
     * @param forceCreate create even when a local {@code openobserve_id} exists —
     *                    the recovery case, where OpenObserve no longer has it
     */
    private AlertRuleEntity pushToOpenObserve(UUID uuid, boolean forceCreate) {
        AlertRuleEntity rule = tx.execute(status -> requireAny(uuid));
        if (rule == null) {
            throw new ResourceNotFoundException("Alert rule not found: " + uuid);
        }

        try {
            ObjectNode document = toOpenObserveDocument(rule);
            String newId;

            if (rule.isLinked() && !forceCreate) {
                alertsService.update(rule.getOpenObserveId(), document, rule.getFolderId());
                newId = rule.getOpenObserveId();
            } else {
                AlertsResponseDTO.WriteResult remote = alertsService.create(document, rule.getFolderId());
                // OpenObserve does not always echo the id on create; fall back to
                // looking it up by name so the mapping is never left null after a
                // successful write — a null mapping would make the next sync
                // create a second copy and fire the alert twice.
                newId = remote.getAlertId() != null ? remote.getAlertId() : lookupRemoteIdByName(rule.getName());
            }

            final String resolvedId = newId;
            return tx.execute(status -> {
                AlertRuleEntity fresh = requireAny(uuid);
                fresh.markSynced(resolvedId);
                return repository.save(fresh);
            });

        } catch (Exception e) {
            String reason = truncate(e.getMessage(), MAX_SYNC_ERROR_CHARS);
            log.error("OpenObserve sync failed for rule uuid={} name='{}': {}",
                    uuid, rule.getName(), reason);
            return tx.execute(status -> {
                AlertRuleEntity fresh = requireAny(uuid);
                fresh.markSyncFailed(reason);
                return repository.save(fresh);
            });
        }
    }

    /** Deletes a rule from OpenObserve and settles or re-flags the obligation. */
    private AlertRuleEntity removeFromOpenObserve(AlertRuleEntity rule) {
        Long id = rule.getId();
        try {
            alertsService.delete(rule.getOpenObserveId());
            log.info("Alert rule uuid={} removed from OpenObserve (id={})",
                    rule.getUuid(), rule.getOpenObserveId());
            return tx.execute(status -> {
                AlertRuleEntity fresh = repository.findById(id)
                        .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE_TYPE_ALERT_RULE, id));
                fresh.setSyncStatus(SyncStatus.SYNCED);
                fresh.setSyncError(null);
                fresh.setLastSyncedAt(Instant.now());
                return repository.save(fresh);
            });
        } catch (Exception e) {
            String reason = truncate(e.getMessage(), MAX_SYNC_ERROR_CHARS);
            log.error("OpenObserve delete failed for rule uuid={} (id={}): {}",
                    rule.getUuid(), rule.getOpenObserveId(), reason);
            return tx.execute(status -> {
                AlertRuleEntity fresh = repository.findById(id)
                        .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE_TYPE_ALERT_RULE, id));
                fresh.setSyncStatus(SyncStatus.DELETE_PENDING);
                fresh.setSyncError(reason);
                return repository.save(fresh);
            });
        }
    }

    /** Every alert id OpenObserve currently holds for this org. Empty if unreachable. */
    private Set<String> fetchRemoteAlertIds() {
        Set<String> ids = new HashSet<>();
        try {
            AlertsRequestDTO.ListFilter all = new AlertsRequestDTO.ListFilter();
            AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> page = alertsService.list(all);
            page.getItems().forEach(item -> {
                if (item.getAlertId() != null) ids.add(item.getAlertId());
            });
        } catch (Exception e) {
            // An empty set means "everything looks missing", so the sync will
            // attempt creates. OpenObserve rejects duplicates by name, which
            // surfaces as a per-rule FAILED rather than as silent duplication.
            log.error("Could not list OpenObserve alerts; sync will treat every rule as missing: {}",
                    e.getMessage());
        }
        return ids;
    }

    /** Resolves an alert id by name, for the create path where the id is not echoed. */
    private String lookupRemoteIdByName(String name) {
        try {
            AlertsRequestDTO.ListFilter filter = new AlertsRequestDTO.ListFilter();
            filter.setSearch(name);
            for (AlertsResponseDTO.ListItem item : alertsService.list(filter).getItems()) {
                if (name.equalsIgnoreCase(item.getName())) return item.getAlertId();
            }
        } catch (Exception e) {
            log.warn("Could not resolve OpenObserve id for rule '{}': {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * Assembles the OpenObserve alert document from a stored rule.
     *
     * <p>The condition trees are re-inserted as parsed JSON, not re-modelled, so
     * whatever the caller originally supplied — including fields this service
     * has never heard of — reaches OpenObserve intact.</p>
     *
     * <p>It starts from {@code source_document} when the rule has one, rather
     * than from an empty object. That is what keeps the keys this entity does not
     * model — {@code row_template}, {@code priority}, {@code tags},
     * {@code deduplication}, {@code creates_incident},
     * {@code composite_condition}, {@code anomaly_config} — alive across a push.
     * Building from nothing silently stripped all of them, and the save still
     * succeeded, so an imported rule lost its priority and its tags the first time
     * anything re-synced it. The modelled columns are overlaid ON TOP, so a local
     * edit still wins over the captured copy.</p>
     */
    private ObjectNode toOpenObserveDocument(AlertRuleEntity rule) {
        ObjectNode doc = baseDocument(rule);
        doc.put("name", rule.getName());
        doc.put("org_id", rule.getOrg());
        doc.put("stream_type", rule.getStreamType());
        doc.put("stream_name", rule.getStreamName());
        doc.put("is_real_time", "realtime".equalsIgnoreCase(rule.getAlertType()));
        doc.put("enabled", rule.isEnabled());
        doc.put("description", rule.getDescription() == null ? "" : rule.getDescription());
        doc.set("query_condition", readJson(rule.getQueryCondition(), json.createObjectNode()));
        doc.set("trigger_condition", readJson(rule.getTriggerCondition(), json.createObjectNode()));

        doc.set("destinations", withHistoryDestination(rule));

        if (rule.getContextAttributes() != null) {
            doc.set("context_attributes", readJson(rule.getContextAttributes(), json.createObjectNode()));
        }
        return doc;
    }

    /**
     * The captured OpenObserve document to build on, or an empty object.
     *
     * <p>Server-owned keys are dropped from the copy for the same reason
     * {@code AlertsServiceImpl.prepare} drops them: {@code id} in the body fights
     * the id in the path on an update, and echoing a stale
     * {@code last_triggered_at} back would rewrite firing history from a value
     * this database happened to be holding.</p>
     */
    private ObjectNode baseDocument(AlertRuleEntity rule) {
        String captured = rule.getSourceDocument();
        if (captured == null || captured.isBlank()) return json.createObjectNode();
        JsonNode parsed = readJson(captured, null);
        if (parsed == null || !parsed.isObject()) return json.createObjectNode();
        ObjectNode doc = ((ObjectNode) parsed).deepCopy();
        doc.remove(List.of("id", "alert_id", "updated_at", "updatedAt", "last_edited_by",
                "last_triggered_at", "last_satisfied_at", "lastTriggeredAt", "createdAt"));
        return doc;
    }

    /**
     * The rule's own destinations, plus the one that routes firings into this UI.
     *
     * <p>This is what makes a rule created here actually appear on the Alerts
     * screen. Without it the rule evaluates, notifies whatever the operator
     * attached, and is invisible to this application — a failure that looks
     * identical to a healthy, synchronised rule, which is why it is not left as a
     * step somebody has to remember.</p>
     *
     * <p>The history destination is ADDED, never substituted: an operator who
     * attached email and Telegram destinations still wants those. Duplicates are
     * avoided by name, so re-saving a rule does not accumulate copies.</p>
     *
     * <p>Provisioning failure is tolerated here rather than thrown. The rule
     * itself is still valid, and refusing to save it because a destination could
     * not be created would block the operator over something they cannot fix from
     * this form. It is logged loudly by the provisioning service.</p>
     */
    private ArrayNode withHistoryDestination(AlertRuleEntity rule) {
        JsonNode stored = readJson(rule.getDestinations(), json.createArrayNode());
        ArrayNode out = json.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (stored.isArray()) {
            addDistinctNames(stored, out, seen);
        }

        if (!historyDestinations.isEnabled()) {
            return out;
        }
        AlertHistoryDestinationService.Provisioned provisioned =
                historyDestinations.ensureProvisioned(rule.getOrg());
        if (provisioned.ok() && provisioned.destinationName() != null
                && seen.add(provisioned.destinationName())) {
            out.add(provisioned.destinationName());
        }
        return out;
    }

    /** Adds each non-empty name in {@code names} to {@code out} once, recording it in {@code seen}. */
    private static void addDistinctNames(JsonNode names, ArrayNode out, Set<String> seen) {
        for (JsonNode entry : names) {
            String name = entry.asText("");
            if (!name.isEmpty() && seen.add(name)) {
                out.add(name);
            }
        }
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private void applyRequest(AlertRuleEntity entity, AlertRuleRequestDTO.SaveRule r, String name) {
        entity.setName(name);
        entity.setDescription(r.getDescription());
        entity.setFolderId(orDefault(r.getFolderId(), DEFAULT_FOLDER));
        entity.setStreamType(r.getStreamType().trim().toLowerCase(Locale.ROOT));
        entity.setStreamName(r.getStreamName().trim());
        entity.setAlertType(orDefault(r.getAlertType(), DEFAULT_ALERT_TYPE));
        entity.setSeverity(orDefault(r.getSeverity(), DEFAULT_SEVERITY));
        applyThresholds(entity, r.getSeverityThresholds());
        applyActivation(entity, r);
        entity.setQueryCondition(writeJson(r.getQueryCondition(), "{}"));
        entity.setTriggerCondition(writeJson(r.getTriggerCondition(), "{}"));
        entity.setDestinations(writeJson(destinationsNode(r.getDestinations()), "[]"));
        entity.setContextAttributes(r.getContextAttributes() == null
                ? null : writeJson(r.getContextAttributes(), null));
    }

    /**
     * Resolves "enable now or later" into the enabled flag plus the intent
     * behind it.
     *
     * <p>{@code enabled} stays the only thing that decides whether OpenObserve
     * evaluates the rule — nothing downstream had to change. What is added is
     * WHY it holds that value, because the boolean alone cannot distinguish a
     * rule staged until Monday from one switched off at 3am for misfiring, and
     * the two want opposite treatment from the next person who sees the list.</p>
     *
     * <p><b>A caller that sends no {@code activation} gets exactly the old
     * behaviour.</b> That is the compatibility guarantee: the existing frontend,
     * the importer and every stored request keep working unchanged, and the
     * rule is simply recorded as {@code NOW} — which is what it always was.</p>
     */
    private void applyActivation(AlertRuleEntity entity, AlertRuleRequestDTO.SaveRule r) {
        ActivationMode mode = parseActivation(r.getActivation());

        if (mode == null || mode == ActivationMode.NOW) {
            applyNow(entity, r);
            return;
        }

        if (mode == ActivationMode.SCHEDULED) {
            applyScheduled(entity, r);
            return;
        }

        // LATER: stored, valid, and waiting for a person. No timer will touch it.
        entity.setEnabled(false);
        entity.setActivationMode(ActivationMode.LATER);
        entity.setActivateAt(null);
    }

    /** Unset, or explicitly "now": the enabled flag speaks for itself. */
    private static void applyNow(AlertRuleEntity entity, AlertRuleRequestDTO.SaveRule r) {
        boolean enabled = r.getEnabled() == null || r.getEnabled();
        entity.setEnabled(enabled);
        entity.setActivationMode(ActivationMode.NOW);
        entity.setActivateAt(null);
        if (enabled && entity.getActivatedAt() == null) {
            entity.setActivatedAt(Instant.now());
        }
    }

    private static void applyScheduled(AlertRuleEntity entity, AlertRuleRequestDTO.SaveRule r) {
        Instant at = parseActivateAt(r.getActivateAt());
        if (at == null) {
            throw new IllegalArgumentException(
                    "activateAt is required when activation is SCHEDULED — a scheduled rule "
                    + "with no start time would wait forever while looking healthy.");
        }
        // Rejected while the operator is still looking at the form. Stored,
        // a past time makes the rule go live on the next sweep — which is
        // the opposite of what somebody typing a date is asking for, and
        // they would find out from a page rather than from the screen.
        requireNotPast(at);
        entity.setEnabled(false);
        entity.setActivationMode(ActivationMode.SCHEDULED);
        entity.setActivateAt(at);
    }

    private static ActivationMode parseActivation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ActivationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown activation '" + raw
                    + "' (expected NOW, LATER or SCHEDULED)");
        }
    }

    /**
     * Parses an ISO-8601 start time, requiring an explicit offset.
     *
     * <p>A local time without one has to be guessed at, and guessing UTC for
     * "Monday 09:00" typed in IST starts the rule five and a half hours late
     * with nothing in the stored row to show that it did. Refused instead.</p>
     */
    private static Instant parseActivateAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Not a Z-form instant; an offset form is equally acceptable.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("activateAt '" + raw
                    + "' is not ISO-8601 with an offset. Expected something like "
                    + "2026-09-22T09:00:00Z or 2026-09-22T14:30:00+05:30.");
        }
    }

    /**
     * Stores the value-based severity bands, or clears them.
     *
     * <p>Null clears rather than preserves. An update that omits the field is
     * saying "this rule no longer grades by value" — silently keeping stale
     * thresholds would leave a rule behaving by rules its own definition no
     * longer shows, which is the worst kind of surprise in alerting.</p>
     */
    private void applyThresholds(AlertRuleEntity entity,
                                 AlertRuleRequestDTO.SeverityThresholds t) {
        if (t == null) {
            entity.setSeverityDisasterAt(null);
            entity.setSeverityCriticalAt(null);
            entity.setSeverityWarningAt(null);
            return;
        }
        // Rejected here, at write time, while the person who typed it is still
        // watching — inverted bands are accepted by the database and then behave
        // nonsensically at 3am.
        SeverityLadder.validate(t.getDisaster(), t.getCritical(), t.getWarning());
        entity.setSeverityDisasterAt(t.getDisaster());
        entity.setSeverityCriticalAt(t.getCritical());
        entity.setSeverityWarningAt(t.getWarning());
    }

    private ArrayNode destinationsNode(List<String> destinations) {
        ArrayNode array = json.createArrayNode();
        if (destinations != null) {
            destinations.stream()
                    .filter(d -> d != null && !d.isBlank())
                    .forEach(d -> array.add(d.trim()));
        }
        return array;
    }

    private AlertRuleResponseDTO.Rule toDto(AlertRuleEntity e) {
        AlertRuleResponseDTO.Rule r = new AlertRuleResponseDTO.Rule();
        r.setUuid(e.getUuid() == null ? null : e.getUuid().toString());
        r.setOrg(e.getOrg());
        r.setName(e.getName());
        r.setDescription(e.getDescription());
        r.setFolderId(e.getFolderId());
        r.setStreamType(e.getStreamType());
        r.setStreamName(e.getStreamName());
        r.setAlertType(e.getAlertType());
        r.setSeverity(e.getSeverity());
        r.setEnabled(e.isEnabled());
        r.setActivation(e.getActivationMode() == null ? ActivationMode.NOW.name()
                : e.getActivationMode().name());
        r.setActivateAt(iso(e.getActivateAt()));
        r.setActivatedAt(iso(e.getActivatedAt()));
        r.setStaged(e.isStaged());
        if (SeverityLadder.isEnabled(e.getSeverityDisasterAt(), e.getSeverityCriticalAt(),
                                     e.getSeverityWarningAt())) {
            AlertRuleRequestDTO.SeverityThresholds t = new AlertRuleRequestDTO.SeverityThresholds();
            t.setDisaster(e.getSeverityDisasterAt());
            t.setCritical(e.getSeverityCriticalAt());
            t.setWarning(e.getSeverityWarningAt());
            r.setSeverityThresholds(t);
        }
        r.setQueryCondition(readJson(e.getQueryCondition(), json.createObjectNode()));
        r.setTriggerCondition(readJson(e.getTriggerCondition(), json.createObjectNode()));
        JsonNode destinations = readJson(e.getDestinations(), json.createArrayNode());
        List<String> names = new ArrayList<>();
        if (destinations.isArray()) destinations.forEach(d -> names.add(d.asText()));
        r.setDestinations(names);
        r.setContextAttributes(e.getContextAttributes() == null
                ? null : readJson(e.getContextAttributes(), null));
        r.setOpenObserveId(e.getOpenObserveId());
        r.setSyncStatus(e.getSyncStatus() == null ? null : e.getSyncStatus().name());
        r.setSyncError(e.getSyncError());
        r.setLastSyncedAt(iso(e.getLastSyncedAt()));
        r.setCreatedBy(e.getCreatedBy());
        r.setUpdatedBy(e.getUpdatedBy());
        r.setCreatedAt(iso(e.getCreatedAt()));
        r.setUpdatedAt(iso(e.getUpdatedAt()));
        return r;
    }

    private AlertRuleResponseDTO.WriteResult writeResult(AlertRuleEntity entity, String message) {
        AlertRuleResponseDTO.WriteResult w = new AlertRuleResponseDTO.WriteResult();
        w.setOk(entity.getSyncStatus() != SyncStatus.FAILED);
        w.setMessage(message);
        w.setRule(toDto(entity));
        return w;
    }

    private AlertRuleResponseDTO.RuleOutcome outcomeFor(AlertRuleEntity e) {
        AlertRuleResponseDTO.RuleOutcome o = new AlertRuleResponseDTO.RuleOutcome();
        if (e == null) {
            return o;
        }
        o.setUuid(e.getUuid() == null ? null : e.getUuid().toString());
        o.setName(e.getName());
        o.setOpenObserveId(e.getOpenObserveId());
        return o;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private AlertRuleEntity requireLive(UUID uuid) {
        return repository.findByUuidAndDeletedAtIsNull(uuid)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE_TYPE_ALERT_RULE, uuid));
    }

    /** Includes soft-deleted rows — the delete-sync path legitimately needs them. */
    private AlertRuleEntity requireAny(UUID uuid) {
        return repository.findByUuid(uuid)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE_TYPE_ALERT_RULE, uuid));
    }

    private JsonNode readJson(String raw, JsonNode fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            // Stored JSON that will not parse means the column was written by
            // something other than this service. Logged rather than thrown so
            // one bad row cannot make the whole rules list unreadable.
            log.error("Stored JSON for an alert rule is unparseable, substituting empty: {}",
                    e.getMessage());
            return fallback;
        }
    }

    private String writeJson(JsonNode node, String fallback) {
        if (node == null || node.isNull()) return fallback;
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Value is not serialisable as JSON: " + e.getMessage(), e);
        }
    }

    private static SyncStatus parseSyncStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return SyncStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown syncStatus '" + raw
                    + "' (expected PENDING, SYNCED, FAILED or DELETE_PENDING)");
        }
    }

    private static int clampPageSize(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static String actorOf(String actor) {
        return (actor == null || actor.isBlank()) ? SYSTEM_ACTOR : actor.trim();
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "(no message)";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
