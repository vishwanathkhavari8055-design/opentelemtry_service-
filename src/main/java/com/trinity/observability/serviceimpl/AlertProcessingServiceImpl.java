package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.trinity.observability.dto.alerts.AlertEvent;
import com.trinity.observability.dto.alerts.AlertState;
import com.trinity.observability.entity.AlertEntity;
import com.trinity.observability.entity.AlertEventEntity;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.repository.AlertEventRepository;
import com.trinity.observability.repository.AlertRepository;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.service.AlertProcessingService;
import com.trinity.observability.util.AlertSeverityRank;
import com.trinity.observability.util.SeverityLadder;

/**
 * Stores one fired alert, idempotently.
 *
 * <p>The episode logic here is carried over from the webhook implementation
 * unchanged — it was already correct, and the change of ingest path is not a
 * reason to rewrite it. What is new is the event-level duplicate guard, which a
 * polling ingest needs and a push ingest did not.</p>
 */
@Service
public class AlertProcessingServiceImpl implements AlertProcessingService {

    private static final Logger log = LogManager.getLogger(AlertProcessingServiceImpl.class);

    /** Actor recorded for a firing — nobody did it, the engine observed it. */
    private static final String ACTOR_ENGINE = "openobserve";

    private final AlertRepository alerts;
    private final AlertEventRepository events;
    private final AlertRuleRepository rules;
    private final AlertIncidentProperties incidentProperties;

    public AlertProcessingServiceImpl(AlertRepository alerts,
            AlertEventRepository events,
            AlertRuleRepository rules,
            AlertIncidentProperties incidentProperties) {
        this.alerts = alerts;
        this.events = events;
        this.rules = rules;
        this.incidentProperties = incidentProperties;
    }

    /**
     * {@code REQUIRES_NEW} so each alert is its own transaction.
     *
     * <p>The poller stores a batch in a loop. Sharing one transaction across the
     * batch would mean a single unstorable row rolls back every alert that came
     * with it — and since the poller re-reads the same range after a failure, that
     * would repeat on every poll indefinitely. One transaction per alert makes a
     * bad row cost exactly one alert.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean store(AlertEvent event, String source) {
        // Cheap pre-check before doing any work. The unique index below is the
        // actual guarantee — this only avoids the common case of re-reading a
        // range the overlap window deliberately covers again.
        if (event.getEventHash() != null
                && events.existsByOrgAndEventHash(event.getOrg(), event.getEventHash())) {
            // Seen before, so there is nothing left to record. This branch used to
            // fold the row into its episode and re-drive the incident engine, which
            // is wrong in the one way that matters: the overlap window and the 24h
            // fallback query deliberately re-read rows that were already stored, so
            // every poll re-counted the same firing. occurrences climbed forever and
            // the incident's alertCount climbed with it, for a rule that had fired
            // exactly once. Idempotence is the whole point of the event hash.
            log.trace("Alert firing already ingested (hash={}, name='{}') — ignoring",
                    event.getEventHash(), event.getAlertName());
            return false;
        }

        Instant firedAt = micros(event.getTimestampMicros());
        Optional<AlertRuleEntity> rule = resolveRule(event);
        resolveSeverity(event, rule);

        AlertEntity alert = foldIntoEpisode(event, firedAt, rule, source);

        try {
            events.save(AlertEventEntity.ofStreamFiring(alert, AlertState.FIRING, firedAt,
                    ACTOR_ENGINE, null, event.getEventHash()));
        } catch (DataIntegrityViolationException duplicate) {
            // Lost a race on the event hash: another poll (or another instance)
            // recorded this exact firing between the pre-check and here. The
            // episode update above is idempotent in effect — the occurrence count
            // is the one thing that would double, so this has to propagate and roll
            // the whole thing back rather than be swallowed.
            log.debug("Alert firing raced another ingest (hash={}) — rolling back this copy",
                    event.getEventHash());
            throw duplicate;
        }

        log.info("Alert stored: name='{}' type='{}' severity={} status={} org={} service={} "
                        + "fingerprint={} occurrences={} source={} alertId={}",
                alert.getAlertName(), alert.getIncidentType(), alert.getSeverity(),
                alert.getStatus(), alert.getOrg(), alert.getServiceName(),
                alert.getFingerprint(), alert.getOccurrences(),
                alert.getSource(), alert.getId());
        return true;
    }

    /**
     * Folds this firing into the open episode, or opens a new one.
     *
     * <p>The insert can lose a race with a concurrent delivery even though the read
     * found nothing — the unique index is what actually decides. That rejection is
     * caught and retried as an update rather than surfaced, because "two firings
     * arrived at once" is normal traffic.</p>
     */
    private AlertEntity foldIntoEpisode(AlertEvent event, Instant firedAt,
            Optional<AlertRuleEntity> rule, String source) {
        Optional<AlertEntity> open = alerts.findOpenEpisode(event.getOrg(), event.getFingerprint());
        if (open.isPresent()) {
            AlertEntity alert = open.get();
            alert.recordFiring(firedAt, event.getObservedCount(), event.getAggValue(),
                    event.getThreshold(), event.getOperator(), event.getPeriod(), event.getAlertUrl());
            applyIdentity(alert, event, source);
            return alerts.save(alert);
        }
        try {
            return alerts.save(newEpisode(event, firedAt,
                    rule.map(AlertRuleEntity::getId).orElse(null), source));
        } catch (DataIntegrityViolationException race) {
            log.debug("Concurrent firing for fingerprint={} — folding into the episode the other "
                    + "delivery opened", event.getFingerprint());
            AlertEntity alert = alerts.findOpenEpisode(event.getOrg(), event.getFingerprint())
                    .orElseThrow(() -> race);
            alert.recordFiring(firedAt, event.getObservedCount(), event.getAggValue(),
                    event.getThreshold(), event.getOperator(), event.getPeriod(), event.getAlertUrl());
            applyIdentity(alert, event, source);
            return alerts.save(alert);
        }
    }

    private AlertEntity newEpisode(AlertEvent event, Instant firedAt, Long ruleId, String source) {
        AlertEntity alert = new AlertEntity();
        alert.setFingerprint(event.getFingerprint());
        alert.setOrg(event.getOrg());
        alert.setFirstFiredAt(firedAt);
        alert.setLastFiredAt(firedAt);
        alert.setOccurrences(1);
        alert.setStatus(AlertState.FIRING);
        // Episodes number continuously across the fingerprint's whole history, so
        // "episode 4" means the fourth time this problem has occurred rather than
        // the fourth row that happens to exist.
        alert.setEpisode(alerts.findMaxEpisode(event.getOrg(), event.getFingerprint()) + 1);
        alert.setThreshold(event.getThreshold());
        alert.setObservedCount(event.getObservedCount());
        alert.setAggValue(event.getAggValue());
        alert.setOperator(event.getOperator());
        alert.setPeriodMinutes(event.getPeriod());
        alert.setWindowStart(micros(event.getWindowStartMicros()));
        alert.setWindowEnd(micros(event.getWindowEndMicros()));
        alert.setAlertUrl(event.getAlertUrl());
        alert.setParseError(event.getParseError());
        alert.setRawPayload(event.getRawPayload());
        // Seeded BEFORE applyIdentity, which only escalates. The field defaults to
        // "warning", so without this a genuinely INFO-level first firing would be
        // held at warning by its own escalation guard — the guard protecting a value
        // that no event ever supplied.
        if (event.getSeverity() != null) {
            alert.setSeverity(event.getSeverity());
            alert.setSeveritySource(event.getSeveritySource());
        }
        applyIdentity(alert, event, source);
        alert.setAlertRuleId(ruleId);
        return alert;
    }

    /**
     * Refreshes descriptive fields on every firing.
     *
     * <p>A rule renamed in OpenObserve keeps the same fingerprint inputs for org
     * and stream, and the feed should show what it is called now, not what it was
     * called when the episode opened. The same applies to the resource fields: an
     * alert whose affected hosts change between evaluations should show the current
     * set, not the first one seen.</p>
     */
    private void applyIdentity(AlertEntity alert, AlertEvent event, String source) {
        setIfPresent(event.getAlertName(), alert::setAlertName);
        setIfPresent(event.getIncidentType(), alert::setIncidentType);
        setIfPresent(event.getAlertType(), alert::setAlertType);
        setIfPresent(event.getStreamType(), alert::setStreamType);
        setIfPresent(event.getStreamName(), alert::setStreamName);
        applySeverity(alert, event);

        setIfPresent(event.getServiceName(), alert::setServiceName);
        setIfPresent(event.getHostName(), alert::setHostName);
        setIfPresent(event.getNamespace(), alert::setNamespace);
        setIfPresent(event.getEnvironment(), alert::setEnvironment);
        setIfPresent(event.getDescription(), alert::setDescription);
        setIfPresent(event.getSummary(), alert::setSummary);
        setIfPresent(event.getEntities(), alert::setEntities);
        setIfPresent(event.getTraceId(), alert::setTraceId);
        // The raw copy is refreshed too, so the Raw Event panel shows the LATEST
        // firing rather than the one that happened to open the episode — which is
        // what someone looking at a live alert expects to be reading.
        setIfPresent(event.getRawPayload(), alert::setRawPayload);
        setIfPresent(source, alert::setSource);
    }

    /** Applies {@code value} only when the event carried one; null never erases a stored field. */
    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Severity ESCALATES while an episode is open; it never falls.
     *
     * <p>This used to be a plain assignment, and that is wrong for the way these
     * rules report. OpenObserve evaluates on a schedule and states what it sees on
     * that evaluation, so a DISASTER at 10:34 followed by a WARNING at 10:35 is not
     * the problem improving — it is one sample of a condition that is still open, and
     * quietly relabelling a live disaster as a warning is how an incident stops being
     * paged about while it is still happening.</p>
     *
     * <p>The downgrade path is not removed, it is moved to where the existing recovery
     * semantics already put it: the episode ends — by hand, or by the staleness sweep
     * inferring it from silence, since OpenObserve emits no "recovered" event — and
     * the next firing opens a NEW episode whose severity starts from that firing. So
     * an alert that genuinely calms down does drop, one resolution later, and no new
     * resolution concept had to be invented for it.</p>
     *
     * <p>{@code severitySource} follows the severity it explains rather than the
     * latest event. Recording "value-threshold" beside a level that came from an
     * earlier firing would make the pair unexplainable, which is the exact thing that
     * column exists to prevent.</p>
     */
    private void applySeverity(AlertEntity alert, AlertEvent event) {
        String incoming = event.getSeverity();
        if (incoming == null) {
            return;
        }
        String current = alert.getSeverity();
        String worst = AlertSeverityRank.max(current, incoming,
                incidentProperties.getSeverityOrder());
        if (worst != null && worst.equals(current)) {
            // Held at the worse level. Logged at trace rather than silently, because
            // "the UI says disaster and the newest event said warning" is otherwise a
            // confusing thing to be asked about.
            if (!worst.equalsIgnoreCase(incoming)) {
                log.trace("Alert '{}' stays {} — incoming firing reported the lower {}",
                        alert.getAlertName(), current, incoming);
            }
            return;
        }
        alert.setSeverity(worst);
        if (event.getSeveritySource() != null) {
            alert.setSeveritySource(event.getSeveritySource());
        }
    }

    /**
     * Links the alert to the rule that produced it, when we own that rule.
     *
     * <p>Null is a legitimate result: alerts also arrive from rules authored
     * directly in OpenObserve's UI, which have no row in {@code alert_rules}.
     * Refusing those would hide real firings over a bookkeeping gap.</p>
     */
    private Optional<AlertRuleEntity> resolveRule(AlertEvent event) {
        if (event.getAlertName() == null) {
            return Optional.empty();
        }
        return rules.findByOrgAndNameAndDeletedAtIsNull(event.getOrg(), event.getAlertName());
    }

    /**
     * Lets the rule's configured severity win over a guessed one.
     *
     * <p>OpenObserve has no severity concept, so nothing in the notification says
     * how bad this is unless the template was written to say so. When it does not,
     * the mapper guesses from the alert's name — but if the rule came from here,
     * PostgreSQL already holds the answer the operator chose, and guessing
     * "warning" at something they marked critical is exactly the kind of quiet
     * downgrade that gets an incident missed.</p>
     */
    private void resolveSeverity(AlertEvent event, Optional<AlertRuleEntity> rule) {
        if (event.isSeverityExplicit() || rule.isEmpty()) {
            return;
        }
        AlertRuleEntity r = rule.get();
        String graded = SeverityLadder.grade(gradableValue(event),
                r.getSeverityDisasterAt(), r.getSeverityCriticalAt(), r.getSeverityWarningAt());
        if (graded != null) {
            event.setSeverity(graded);
            event.setSeveritySource("value-threshold");
            return;
        }
        String ruleSeverity = r.getSeverity();
        if (ruleSeverity != null && !ruleSeverity.isBlank()) {
            event.setSeverity(ruleSeverity);
            event.setSeveritySource("rule");
        }
    }

    /**
     * The number the ladder grades.
     *
     * <p>{@code aggValue} first — it is the aggregate the rule actually tested (an
     * average CPU, a p95 latency). {@code observedCount} is the fallback and means
     * something different: how many rows matched. Grading a row count against a
     * percentage ladder would be meaningless, but it beats refusing to grade a rule
     * whose condition is purely count-based.</p>
     */
    private Double gradableValue(AlertEvent event) {
        if (event.getAggValue() != null) {
            return event.getAggValue();
        }
        return event.getObservedCount() == null ? null : event.getObservedCount().doubleValue();
    }

    private static Instant micros(Long value) {
        return value == null ? null : Instant.ofEpochSecond(value / 1_000_000L,
                (value % 1_000_000L) * 1_000L);
    }

    private static Instant micros(long value) {
        return Instant.ofEpochSecond(value / 1_000_000L, (value % 1_000_000L) * 1_000L);
    }
}
