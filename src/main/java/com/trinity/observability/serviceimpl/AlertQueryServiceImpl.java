package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.config.AlertDisplayProperties;
import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertLifecycleProperties;
import com.trinity.observability.config.AlertPersistenceConfig;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertEntityStatusCount;
import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.dto.alerts.AlertQueryResponseDTO;
import com.trinity.observability.dto.alerts.AlertState;
import com.trinity.observability.entity.AlertEntity;
import com.trinity.observability.entity.AlertEventEntity;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.exception.ResourceNotFoundException;
import com.trinity.observability.repository.AlertEventRepository;
import com.trinity.observability.repository.AlertRepository;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.service.AlertQueryService;
import com.trinity.observability.util.AlertAttributeProjector;
import com.trinity.observability.util.AlertDescriptionNormalizer;
import com.trinity.observability.util.AlertMeasurementReader;
import com.trinity.observability.util.TimeRangeParser;

/**
 * The enterprise alerts screen's read and transition path.
 */
@Service
@ConditionalOnBean(AlertPersistenceConfig.class)
public class AlertQueryServiceImpl implements AlertQueryService {

    private static final Logger log = LogManager.getLogger(AlertQueryServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_EVENTS_PER_ALERT = 200;
    private static final int MAX_ACTOR_CHARS = 128;
    private static final int MAX_NOTE_CHARS = 500;

    /** Actor recorded when the caller did not say who they are. */
    private static final String UNKNOWN_ACTOR = "unknown";
    private static final String ALERT_PREFIX = "Alert ";

    /**
     * Bounds standing in for "no time filter".
     *
     * <p>Needed because a null {@code Instant} parameter reaches PostgreSQL untyped
     * and fails the entire query — see the note on
     * {@code AlertRepository.search}. Year 9999 rather than {@code Instant.MAX},
     * which the JDBC driver rejects outright.</p>
     */
    private static final Instant OPEN_START = Instant.EPOCH;
    private static final Instant OPEN_END = Instant.parse("9999-12-31T23:59:59Z");

    /**
     * Severity ordering for the {@code severity} sort.
     *
     * <p>Sorting on the stored string would put "critical" before "info" before
     * "warning" — alphabetical, and useless. A CASE expression in the query would
     * be the alternative; a comparator over the page is enough because the page is
     * already bounded, and it keeps the ordering in one readable place.</p>
     */
    private static final List<String> SEVERITY_RANK =
            List.of("disaster", "critical", "error", "warning", "info");

    private final AlertRepository alerts;
    private final AlertEventRepository events;
    private final AlertRuleRepository rules;
    private final OpenObserveConfig config;
    private final AlertDisplayProperties display;
    private final AlertLifecycleProperties lifecycle;
    private final AlertStreamMeasurementLookup streamMeasurements;
    private final AlertIncidentProperties incidentProperties;

    public AlertQueryServiceImpl(AlertRepository alerts,
            AlertEventRepository events,
            AlertRuleRepository rules,
            OpenObserveConfig config,
            AlertDisplayProperties display,
            AlertLifecycleProperties lifecycle,
            AlertStreamMeasurementLookup streamMeasurements,
            AlertIncidentProperties incidentProperties) {
        this.alerts = alerts;
        this.events = events;
        this.rules = rules;
        this.config = config;
        this.display = display;
        this.lifecycle = lifecycle;
        this.streamMeasurements = streamMeasurements;
        this.incidentProperties = incidentProperties;
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AlertQueryResponseDTO.Page search(AlertQueryRequestDTO.Search request) {
        long t0 = System.nanoTime();
        AlertQueryRequestDTO.Search filter = request == null ? new AlertQueryRequestDTO.Search() : request;

        String org = config.getOrg();
        int page = filter.getPage() == null || filter.getPage() < 0 ? 0 : filter.getPage();
        int size = clampSize(filter.getSize());
        Window window = windowOf(filter.getWindow());

        String sortBy = resolveSortBy(filter.getSortBy());
        Sort.Direction direction = "asc".equalsIgnoreCase(filter.getSortDir())
                ? Sort.Direction.ASC : Sort.Direction.DESC;

        // `severity` is sorted in Java (see SEVERITY_RANK), so the query is asked
        // for a deterministic order it CAN provide and the comparator refines the
        // page. id is always the tiebreaker: lastFiredAt is not unique — a burst
        // shares a timestamp — and a non-unique sort key makes page boundaries
        // repeat or skip rows.
        String querySort = "severity".equals(sortBy) ? "lastFiredAt" : sortBy;
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(direction, querySort).and(Sort.by(Sort.Direction.DESC, "id")));

        Page<AlertEntity> found = alerts.search(
                org,
                collapseEpisodes(filter.getCollapse()),
                statusesOf(filter.getStatus()),
                severitiesOf(filter.getSeverity()),
                blankToNull(filter.getStream()),
                blankToNull(filter.getService()),
                blankToNull(filter.getSource()),
                ruleIdOf(filter.getRuleId(), org),
                window.start(),
                window.end(),
                blankToNull(filter.getSearch()),
                pageable);

        // ONE stream read for the whole page, before the rows are built. Per-row it
        // would be up to 250 OpenObserve round trips at roughly 750 ms each; indexed
        // once, every row is a hash lookup. Empty when the lookup is off or
        // unavailable, and toRow then falls back to each alert's stored payload.
        Map<String, AlertStreamMeasurementLookup.Measurement> measurements =
                streamMeasurements.forOrg(org);

        List<AlertQueryResponseDTO.Row> rows = new ArrayList<>(found.getNumberOfElements());
        for (AlertEntity alert : found.getContent()) {
            rows.add(toRow(alert, measurements));
        }
        if ("severity".equals(sortBy)) {
            rows.sort((a, b) -> {
                int cmp = Integer.compare(severityRank(a.getSeverity()), severityRank(b.getSeverity()));
                return direction == Sort.Direction.ASC ? -cmp : cmp;
            });
        }

        AlertQueryResponseDTO.Page out = new AlertQueryResponseDTO.Page();
        out.setItems(rows);
        out.setTotal(found.getTotalElements());
        out.setPage(page);
        out.setSize(size);
        out.setHasMore(found.hasNext());
        out.setOrg(org);
        out.setSortBy(sortBy);
        out.setSortDir(direction.name().toLowerCase(Locale.ROOT));
        out.setWindowStart(window.startIso());
        out.setWindowEnd(window.endIso());

        log.info("Alert search: {} of {} row(s) for org={} in {} ms ({})",
                rows.size(), found.getTotalElements(), org,
                (System.nanoTime() - t0) / 1_000_000L, filter);
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public AlertQueryResponseDTO.Summary summary(AlertQueryRequestDTO.Search request) {
        AlertQueryRequestDTO.Search filter = request == null ? new AlertQueryRequestDTO.Search() : request;
        String org = config.getOrg();
        Window window = windowOf(filter.getWindow());

        AlertQueryResponseDTO.Summary out = new AlertQueryResponseDTO.Summary();
        out.setWindowStart(window.startIso());
        out.setWindowEnd(window.endIso());

        // Collapsed on the SAME terms as the table. The cards sit directly above it,
        // so counting every episode while the table shows one row per alert would put
        // "13 alerts" over seven rows with nothing on screen saying which is right.
        for (AlertEntityStatusCount row : alerts.countByStatusAndSeverityInWindow(
                org, collapseEpisodes(filter.getCollapse()), window.start(), window.end())) {
            long n = row.getCount();
            countStatus(out, row.getStatus(), n);
            // Severity is counted across every status, not just the open ones: the
            // Critical card answers "how many critical alerts in this window", and
            // silently excluding the resolved ones would make it disagree with a
            // table filtered to the same window.
            countSeverity(out, row.getSeverity(), n);
        }
        out.setNeedsAttention(out.getOpen() + out.getAcknowledged());
        return out;
    }

    /** Adds {@code n} to the summary card for {@code status}. */
    private static void countStatus(AlertQueryResponseDTO.Summary out, AlertState status, long n) {
        switch (status) {
            case FIRING -> out.setOpen(out.getOpen() + n);
            case ACKNOWLEDGED -> out.setAcknowledged(out.getAcknowledged() + n);
            case RESOLVED -> out.setResolved(out.getResolved() + n);
            case CLOSED -> out.setClosed(out.getClosed() + n);
            default -> log.debug("Ignoring unexpected alert status {} in summary", status);
        }
    }

    /** Adds {@code n} to the summary card for {@code severity}. */
    private static void countSeverity(AlertQueryResponseDTO.Summary out, String severity, long n) {
        switch (nz(severity)) {
            case "disaster" -> out.setDisaster(out.getDisaster() + n);
            case "critical" -> out.setCritical(out.getCritical() + n);
            case "error" -> out.setError(out.getError() + n);
            case "warning" -> out.setWarning(out.getWarning() + n);
            case "info" -> out.setInfo(out.getInfo() + n);
            default -> log.debug("Ignoring unknown severity '{}' in summary", severity);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AlertQueryResponseDTO.Detail detail(Long id) {
        AlertEntity alert = require(id);

        AlertQueryResponseDTO.Detail out = new AlertQueryResponseDTO.Detail();
        out.setAlert(toRow(alert, streamMeasurements.forOrg(config.getOrg())));
        out.setRawPayload(alert.getRawPayload());

        // The trail is fetched by FINGERPRINT, not by alert id, so it spans every
        // episode of this alert. Scoping it to the row would cut the history at the
        // last time the alert resolved — usually the moment somebody reading it
        // cares most about.
        for (AlertEventEntity event : events.findByOrgAndFingerprintOrderByOccurredAtDesc(
                alert.getOrg(), alert.getFingerprint(),
                PageRequest.of(0, MAX_EVENTS_PER_ALERT))) {
            AlertQueryResponseDTO.Event dto = new AlertQueryResponseDTO.Event();
            dto.setType(event.getEventType() == null ? null : event.getEventType().name());
            dto.setOccurredAt(iso(event.getOccurredAt()));
            dto.setActor(event.getActor());
            dto.setNote(event.getNote());
            dto.setObservedCount(event.getObservedCount());
            dto.setAggValue(event.getAggValue());
            out.getEvents().add(dto);
        }

        resolveRule(alert).ifPresent(rule -> out.setRule(toRuleDto(rule)));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public AlertQueryResponseDTO.FilterOptions filterOptions() {
        String org = config.getOrg();
        AlertQueryResponseDTO.FilterOptions out = new AlertQueryResponseDTO.FilterOptions();

        // Severities and statuses come from the vocabulary, not from the data: an
        // operator filtering for Critical when nothing is critical should see an
        // empty table, not a missing filter option.
        out.setSeverities(new ArrayList<>(SEVERITY_RANK));
        List<String> statuses = new ArrayList<>();
        AlertState.assignableStatuses().forEach(s -> statuses.add(s.name()));
        out.setStatuses(statuses);

        out.setSources(alerts.findDistinctSources(org));
        out.setServices(alerts.findDistinctServiceNames(org));
        out.setStreams(alerts.findDistinctStreamNames(org));

        for (AlertRuleEntity rule : rules.findAllLiveForSync(org)) {
            out.addRule(rule.getUuid().toString(), rule.getName());
        }
        return out;
    }

    // ── transitions ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AlertQueryResponseDTO.WriteResult acknowledge(Long id, AlertQueryRequestDTO.Transition body) {
        return transition(id, body, Action.ACKNOWLEDGE);
    }

    @Override
    @Transactional
    public AlertQueryResponseDTO.WriteResult resolve(Long id, AlertQueryRequestDTO.Transition body) {
        return transition(id, body, Action.RESOLVE);
    }

    @Override
    @Transactional
    public AlertQueryResponseDTO.WriteResult close(Long id, AlertQueryRequestDTO.Transition body) {
        return transition(id, body, Action.CLOSE);
    }

    @Override
    @Transactional
    public AlertQueryResponseDTO.WriteResult reopen(Long id, AlertQueryRequestDTO.Transition body) {
        return transition(id, body, Action.REOPEN);
    }

    /** The four transitions, and what each one is allowed to act on. */
    private enum Action {
        ACKNOWLEDGE, RESOLVE, CLOSE, REOPEN
    }

    private AlertQueryResponseDTO.WriteResult transition(Long id,
            AlertQueryRequestDTO.Transition body, Action action) {
        AlertEntity alert = require(id);
        requireNamedActor(action, body == null ? null : body.getActor());
        String actor = actorOf(body == null ? null : body.getActor());
        String note = truncate(body == null ? null : body.getNote(), MAX_NOTE_CHARS);

        String rejection = applyAction(alert, action, actor, note);
        if (rejection != null) {
            // A 400 rather than silently doing nothing. "Acknowledge" on a resolved
            // alert is a mistake worth telling the operator about — most likely they
            // are looking at a stale table and about to act on the wrong row.
            throw new IllegalArgumentException(rejection);
        }

        AlertEntity saved = alerts.save(alert);
        events.save(AlertEventEntity.of(saved, eventTypeOf(action), Instant.now(), actor, note));

        log.info("Alert {} -> {} by {} (id={}, name='{}')",
                action, saved.getStatus(), actor, id, saved.getAlertName());

        AlertQueryResponseDTO.WriteResult out = new AlertQueryResponseDTO.WriteResult();
        out.setId(id);
        out.setStatus(saved.getStatus().name());
        out.setAlert(toRow(saved, streamMeasurements.forOrg(config.getOrg())));
        out.setMessage(messageFor(action, saved));
        return out;
    }

    /**
     * Applies one transition, or explains why it cannot happen.
     *
     * @return null when applied, otherwise the reason it was refused
     */
    private String applyAction(AlertEntity alert, Action action, String actor, String note) {
        AlertState current = alert.getStatus();
        return switch (action) {
            case ACKNOWLEDGE -> applyAcknowledge(alert, current, actor, note);
            case RESOLVE -> applyResolve(alert, current, actor, note);
            case CLOSE -> applyClose(alert, current, actor, note);
            case REOPEN -> applyReopen(alert, current, actor, note);
            default -> "Unsupported action " + action;
        };
    }

    /** @return null when acknowledged, otherwise the reason it was refused */
    private static String applyAcknowledge(AlertEntity alert, AlertState current,
            String actor, String note) {
        if (current != null && current.isTerminal()) {
            return ALERT_PREFIX + alert.getId() + " is " + current
                    + " and cannot be acknowledged. Reopen it first.";
        }
        alert.acknowledge(actor, note);
        return null;
    }

    /** @return null when resolved, otherwise the reason it was refused */
    private static String applyResolve(AlertEntity alert, AlertState current,
            String actor, String note) {
        if (current == AlertState.RESOLVED) {
            return ALERT_PREFIX + alert.getId() + " is already resolved.";
        }
        if (current == AlertState.CLOSED) {
            return ALERT_PREFIX + alert.getId() + " is closed. Reopen it first.";
        }
        alert.resolve(actor, note, false);
        return null;
    }

    /** @return null when closed, otherwise the reason it was refused */
    private static String applyClose(AlertEntity alert, AlertState current,
            String actor, String note) {
        if (current == AlertState.CLOSED) {
            return ALERT_PREFIX + alert.getId() + " is already closed.";
        }
        alert.close(actor, note);
        return null;
    }

    /** @return null when reopened, otherwise the reason it was refused */
    private static String applyReopen(AlertEntity alert, AlertState current,
            String actor, String note) {
        if (current == null || !current.isTerminal()) {
            return ALERT_PREFIX + alert.getId() + " is " + current
                    + " and is already open.";
        }
        alert.reopen(actor, note);
        return null;
    }

    private static AlertState eventTypeOf(Action action) {
        return switch (action) {
            case ACKNOWLEDGE -> AlertState.ACKNOWLEDGED;
            case RESOLVE -> AlertState.RESOLVED;
            case CLOSE -> AlertState.CLOSED;
            case REOPEN -> AlertState.REOPENED;
        };
    }

    private static String messageFor(Action action, AlertEntity alert) {
        return switch (action) {
            case ACKNOWLEDGE -> "'" + alert.getAlertName() + "' acknowledged by "
                    + alert.getAcknowledgedBy() + ".";
            case RESOLVE -> "'" + alert.getAlertName() + "' resolved.";
            case CLOSE -> "'" + alert.getAlertName() + "' closed.";
            case REOPEN -> "'" + alert.getAlertName() + "' reopened — it is firing again.";
        };
    }

    @Override
    @Transactional
    public AlertQueryResponseDTO.BulkResult bulk(AlertQueryRequestDTO.BulkTransition body) {
        Action action = parseAction(body.getAction());
        String org = config.getOrg();
        String actor = actorOf(body.getActor());
        String note = truncate(body.getNote(), MAX_NOTE_CHARS);

        // Scoped to the org, so a caller cannot reach another tenant's alerts by
        // guessing ids. Deduplicated, so the same id sent twice is applied once.
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(body.getIds()));
        List<AlertEntity> found = alerts.findAllByOrgAndIdIn(org, ids);

        AlertQueryResponseDTO.BulkResult out = new AlertQueryResponseDTO.BulkResult();
        out.setRequested(ids.size());

        Set<Long> seen = new LinkedHashSet<>();
        for (AlertEntity alert : found) {
            seen.add(alert.getId());
            String rejection = applyAction(alert, action, actor, note);
            if (rejection != null) {
                // Skipped, not failed. A bulk acknowledge over forty alerts where
                // three had already resolved is a partial success; reporting the
                // whole thing as failed would make the operator redo the rest.
                out.setSkipped(out.getSkipped() + 1);
                out.addOutcome(alert.getId(), "SKIPPED", rejection);
                continue;
            }
            AlertEntity saved = alerts.save(alert);
            events.save(AlertEventEntity.of(saved, eventTypeOf(action), Instant.now(), actor, note));
            out.setChanged(out.getChanged() + 1);
            out.addOutcome(saved.getId(), "CHANGED", saved.getStatus().name());
        }

        for (Long id : ids) {
            if (!seen.contains(id)) {
                out.setSkipped(out.getSkipped() + 1);
                out.addOutcome(id, "NOT_FOUND", "No alert with this id in org " + org);
            }
        }

        out.setOk(out.getChanged() > 0 || out.getRequested() == 0);
        out.setMessage(out.getChanged() + " of " + out.getRequested() + " alert(s) "
                + pastTense(action)
                + (out.getSkipped() > 0 ? ", " + out.getSkipped() + " skipped" : ""));
        log.info("Bulk {}: {} changed, {} skipped of {} requested (org={}, actor={})",
                action, out.getChanged(), out.getSkipped(), out.getRequested(), org, actor);
        return out;
    }

    /**
     * Past tense of an action, for the bulk result message.
     *
     * <p>Spelled out rather than appending "d" to the lowercased name, which reads
     * correctly for acknowledge/resolve/close and produces "reopend" for the fourth.</p>
     */
    private static String pastTense(Action action) {
        return switch (action) {
            case ACKNOWLEDGE -> "acknowledged";
            case RESOLVE -> "resolved";
            case CLOSE -> "closed";
            case REOPEN -> "reopened";
        };
    }

    private static Action parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "action is required (acknowledge, resolve, close or reopen)");
        }
        try {
            return Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown action '" + raw
                    + "' (expected acknowledge, resolve, close or reopen)");
        }
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private AlertQueryResponseDTO.Row toRow(AlertEntity alert,
            Map<String, AlertStreamMeasurementLookup.Measurement> fromStream) {
        AlertQueryResponseDTO.Row row = new AlertQueryResponseDTO.Row();
        row.setId(alert.getId());
        row.setFingerprint(alert.getFingerprint());
        row.setAlertName(alert.getAlertName());
        row.setSeverity(alert.getSeverity());
        row.setStatus(alert.getStatus() == null ? null : alert.getStatus().name());
        row.setActions(AlertState.availableActions(alert.getStatus()));
        row.setIncidentType(alert.getIncidentType());
        row.setSource(alert.getSource());
        row.setServiceName(alert.getServiceName());
        row.setHostName(alert.getHostName());
        row.setNamespace(alert.getNamespace());
        row.setEnvironment(alert.getEnvironment());
        row.setStreamName(alert.getStreamName());
        row.setStreamType(alert.getStreamType());

        row.setTriggeredAt(iso(alert.getFirstFiredAt()));
        row.setLastFiredAt(iso(alert.getLastFiredAt()));
        row.setLastUpdatedAt(iso(alert.getUpdatedAt()));
        row.setDurationSeconds(alert.getDurationSeconds());
        row.setDuration(humanDuration(alert.getDurationSeconds()));

        row.setOccurrences(alert.getOccurrences());
        row.setEpisode(alert.getEpisode());
        row.setNewSinceAck(alert.getNewSinceAck());

        row.setAcknowledgedBy(alert.getAcknowledgedBy());
        row.setAcknowledgedAt(iso(alert.getAcknowledgedAt()));
        row.setResolvedBy(alert.getResolvedBy());
        row.setResolvedAt(iso(alert.getResolvedAt()));
        row.setClosedBy(alert.getClosedBy());
        row.setClosedAt(iso(alert.getClosedAt()));
        row.setReopenCount(alert.getReopenCount());
        row.setAutoResolved(alert.isAutoResolved());

        row.setNote(alert.getNote());
        // Normalised again on READ, and the normaliser is idempotent so this costs
        // nothing for rows already stored clean. It is what makes the alerts that
        // predate the normalisation render properly — a resolved alert will never
        // fire again, so waiting for ingest to fix it would mean waiting forever.
        AlertDescriptionNormalizer.Normalized description =
                AlertDescriptionNormalizer.normalize(alert.getDescription());
        row.setDescription(description.text());
        row.setDescriptionHeadline(description.headline());
        row.setAffectedCount(description.affectedCount());
        row.setSummary(alert.getSummary());
        row.setEntities(alert.getEntities());
        row.setTraceId(alert.getTraceId());
        row.setAlertUrl(alert.getAlertUrl());

        row.setThreshold(alert.getThreshold());
        row.setObservedCount(alert.getObservedCount());
        row.setAggValue(alert.getAggValue());

        // Parsed once and shared: the measurement labels and the generic attribute
        // projection read the same tree.
        JsonNode payload = AlertMeasurementReader.parse(alert.getRawPayload());
        applyMeasurements(row, alert, fromStream, payload);

        // Everything the latest firing carried, split into numbers and labels, keyed by
        // the payload's own field names. This is what makes a metric nobody has alerted
        // on before visible with no code change — there is no list of metrics to add to.
        AlertAttributeProjector.Attributes attributes = AlertAttributeProjector.project(
                payload, incidentProperties.getExcludeAttributes(),
                incidentProperties.getMaxAttributes());
        row.setCurrentValues(attributes.currentValues());
        row.setLabels(attributes.labels());
        row.setOperator(alert.getOperator());
        row.setPeriodMinutes(alert.getPeriodMinutes());
        row.setWindowStart(iso(alert.getWindowStart()));
        row.setWindowEnd(iso(alert.getWindowEnd()));
        row.setSeveritySource(alert.getSeveritySource());
        row.setParseError(alert.getParseError());

        // Rule name is looked up per row. Alerts also arrive from rules authored in
        // OpenObserve directly, which have no row here — a null rule name is a
        // legitimate answer, not a missing join.
        resolveRule(alert).ifPresent(rule -> {
            row.setRuleName(rule.getName());
            row.setRuleUuid(rule.getUuid().toString());
        });
        return row;
    }

    private AlertQueryResponseDTO.Rule toRuleDto(AlertRuleEntity rule) {
        AlertQueryResponseDTO.Rule dto = new AlertQueryResponseDTO.Rule();
        dto.setUuid(rule.getUuid().toString());
        dto.setName(rule.getName());
        dto.setDescription(rule.getDescription());
        dto.setSeverity(rule.getSeverity());
        dto.setEnabled(rule.isEnabled());
        dto.setStreamType(rule.getStreamType());
        dto.setStreamName(rule.getStreamName());
        dto.setQueryCondition(rule.getQueryCondition());
        dto.setTriggerCondition(rule.getTriggerCondition());
        dto.setSyncStatus(rule.getSyncStatus() == null ? null : rule.getSyncStatus().name());
        return dto;
    }

    private Optional<AlertRuleEntity> resolveRule(AlertEntity alert) {
        return resolveRule(alert, AlertMeasurementReader.parse(alert.getRawPayload()));
    }

    /**
     * Which stored rule produced this alert.
     *
     * <p>Three ways, most trustworthy first, because on this deployment only the
     * weakest one is available.</p>
     *
     * <ol>
     *   <li>{@code alert_rule_id} — set when the rule was created through this
     *       service, so the link is a foreign key and cannot be wrong.</li>
     *   <li>A rule name the TEMPLATE supplied. Authoritative when present: it names
     *       the rule rather than resembling it. Nothing emits it here yet — see
     *       {@code alerts.display.rule-name-fields} for the one template line that
     *       turns it on, which then applies to alerts already stored.</li>
     *   <li>The alert's own display name, matched against rule names. A guess, and
     *       it only lands when the template happens to reuse the rule name — which
     *       is why 13 alerts here have no rule even though all 20 rules are now
     *       stored. Kept last so a real answer always wins.</li>
     * </ol>
     */
    private Optional<AlertRuleEntity> resolveRule(AlertEntity alert, JsonNode payload) {
        if (alert.getAlertRuleId() != null) {
            return rules.findById(alert.getAlertRuleId());
        }

        String declared = AlertMeasurementReader.displayValue(payload, display.getRuleNameFields());
        if (declared != null) {
            Optional<AlertRuleEntity> byRule =
                    rules.findByOrgAndNameAndDeletedAtIsNull(alert.getOrg(), declared);
            if (byRule.isPresent()) {
                return byRule;
            }
        }

        if (alert.getAlertName() == null) {
            return Optional.empty();
        }
        return rules.findByOrgAndNameAndDeletedAtIsNull(alert.getOrg(), alert.getAlertName());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The resolved time window, with both machine and display forms. */
    private record Window(Instant start, Instant end, String startIso, String endIso) {
    }

    /**
     * Resolves the window expression.
     *
     * <p>Blank means everything, expressed as explicit open bounds rather than
     * nulls — see {@link #OPEN_START}.</p>
     */
    private static Window windowOf(String expression) {
        if (expression == null || expression.isBlank()) {
            return new Window(OPEN_START, OPEN_END, null, null);
        }
        long endMicros = TimeRangeParser.defaultEndMicros();
        long startMicros = TimeRangeParser.toMicros(expression.trim(), endMicros);
        Instant start = Instant.ofEpochSecond(startMicros / 1_000_000L,
                (startMicros % 1_000_000L) * 1_000L);
        Instant end = Instant.ofEpochSecond(endMicros / 1_000_000L,
                (endMicros % 1_000_000L) * 1_000L);
        return new Window(start, end, start.toString(), end.toString());
    }

    private static List<AlertState> statusesOf(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            // No status filter means EVERY status here, unlike the legacy feed which
            // defaults to unresolved. The enterprise table has an explicit Status
            // filter and summary cards for each state, so a hidden default would
            // make the Resolved card unreachable from the table it sits above.
            return List.of();
        }
        List<AlertState> out = new ArrayList<>();
        for (String value : raw) {
            if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
                return List.of();
            }
            out.add(AlertState.parseStatus(value));
        }
        return out;
    }

    private static List<String> severitiesOf(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : raw) {
            if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
                return List.of();
            }
            String normalised = value.trim().toLowerCase(Locale.ROOT);
            if (!SEVERITY_RANK.contains(normalised)) {
                throw new IllegalArgumentException("Unknown severity '" + value
                        + "' (expected one of " + SEVERITY_RANK + ")");
            }
            out.add(normalised);
        }
        return out;
    }

    /** Rule UUID to local id, or null for "no rule filter". */
    private Long ruleIdOf(String raw, String org) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ruleId must be a UUID — got '" + raw + "'");
        }
        return rules.findByUuidAndDeletedAtIsNull(uuid)
                .map(AlertRuleEntity::getId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No alert rule with uuid '" + uuid + "' in org " + org));
    }

    private static String resolveSortBy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "lastFiredAt";
        }
        String trimmed = raw.trim();
        if (!AlertQueryRequestDTO.SORTABLE.contains(trimmed)) {
            throw new IllegalArgumentException("Cannot sort by '" + trimmed
                    + "' (expected one of " + AlertQueryRequestDTO.SORTABLE + ")");
        }
        return trimmed;
    }

    private static int severityRank(String severity) {
        int index = SEVERITY_RANK.indexOf(nz(severity));
        // Unknown severities sort last rather than first: an unrecognised grade is
        // not evidence of urgency.
        return index < 0 ? SEVERITY_RANK.size() : index;
    }

    private AlertEntity require(Long id) {
        String org = config.getOrg();
        return alerts.findById(id)
                // Org check in code, not in the query, so the message can tell a
                // cross-tenant reference apart from a genuinely missing row.
                .filter(a -> org.equals(a.getOrg()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No alert with id " + id + " in org " + org));
    }

    private static int clampSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /** "4m", "2h 15m", "3d 4h" — compact enough for a table cell. */
    static String humanDuration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }

    /**
     * An acknowledgement has to name somebody.
     *
     * <p>"Acknowledged by unknown" was the actual state of this audit trail: the UI
     * substituted the literal string {@code unknown} whenever it could not find a
     * stored session, and the field people consult to find out who took ownership
     * recorded that instead. Rejecting it here rather than only fixing the caller is
     * the point — the next caller would otherwise inherit the same trap.</p>
     *
     * <p>Acknowledge only. Resolve and close are also performed by the staleness
     * sweep, which has no person to name and correctly records itself.</p>
     */
    private void requireNamedActor(Action action, String raw) {
        if (action != Action.ACKNOWLEDGE || !lifecycle.isRequireActorOnAcknowledge()) {
            return;
        }
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || UNKNOWN_ACTOR.equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException(
                    "Acknowledging an alert requires the name of the person taking it on. "
                            + "Send a non-empty 'actor'.");
        }
    }

    /** The request's choice, or the configured default when it did not express one. */
    private boolean collapseEpisodes(Boolean requested) {
        return requested == null ? display.isCollapseEpisodes() : requested;
    }

    /**
     * Fill in Current Usage's companion columns: Limit and the rendered measurement.
     *
     * <p>The STREAM wins where it has an answer — OpenObserve is the source of truth
     * for what an alert measured, and the stored payload is a capped snapshot taken
     * at ingest. Where it has none, the payload is used, which covers alerts older
     * than the lookback, rows retention has dropped, and a window OpenObserve served
     * only partially. So the column degrades a row at a time instead of going blank.</p>
     *
     * <p>Matched on alert name AND entity because one rule fires for many pods with
     * genuinely different ceilings — 2048 MB against 5120 MB on the same rule — so
     * the name alone would attach one pod's limit to another's row.</p>
     *
     * @param payload the row's parsed payload, or null — parsed ONCE by the caller and
     *        threaded through, because the attribute projection needs the same tree and
     *        parsing a 8,000-character blob twice per row is a cost a 250-row page
     *        notices
     */
    private void applyMeasurements(AlertQueryResponseDTO.Row row, AlertEntity alert,
            Map<String, AlertStreamMeasurementLookup.Measurement> fromStream,
            JsonNode payload) {
        if (!fromStream.isEmpty()) {
            for (String entity : AlertStreamMeasurementLookup.entityCandidates(
                    alert.getEntities(), alert.getServiceName())) {
                AlertStreamMeasurementLookup.Measurement hit = fromStream.get(
                        AlertStreamMeasurementLookup.key(alert.getAlertName(), entity));
                if (hit != null) {
                    row.setUsedLabel(hit.usedLabel());
                    row.setLimitLabel(hit.limitLabel());
                    row.setDisplayValue(hit.displayValue());
                    return;
                }
            }
        }
        row.setUsedLabel(AlertMeasurementReader.usedLabel(payload, display.getUsedFields()));
        row.setLimitLabel(AlertMeasurementReader.limitLabel(payload, display.getLimitFields()));
        row.setDisplayValue(AlertMeasurementReader.displayValue(payload, display.getDisplayFields()));
    }

    private static String actorOf(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return UNKNOWN_ACTOR;
        }
        return trimmed.length() <= MAX_ACTOR_CHARS ? trimmed : trimmed.substring(0, MAX_ACTOR_CHARS);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return (trimmed.isEmpty() || "ALL".equalsIgnoreCase(trimmed)) ? null : trimmed;
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
