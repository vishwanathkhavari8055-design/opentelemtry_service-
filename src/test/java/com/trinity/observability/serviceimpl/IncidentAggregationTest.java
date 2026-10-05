package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.dto.alerts.AlertEvent;
import com.trinity.observability.dto.alerts.AlertState;
import com.trinity.observability.entity.AlertEntity;
import com.trinity.observability.entity.AlertEventEntity;
import com.trinity.observability.repository.AlertEventRepository;
import com.trinity.observability.repository.AlertRepository;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.util.AlertStreamRowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Incident aggregation: many OpenObserve events becoming one current state.
 *
 * <h2>There is no incident table</h2>
 *
 * <p>An incident IS an alert episode, keyed by {@code alerts.ingest.identity-fields}.
 * That is the point of the design and the reason these tests exercise
 * {@link AlertProcessingServiceImpl} rather than a separate engine: one store, one
 * grouping rule, one place where the current state lives.</p>
 *
 * <h2>The service names below are TEST FIXTURES ONLY</h2>
 *
 * <p>"nifi", "kafka", "cpu" and "ram" appear here because a test needs concrete
 * values to compare. They appear NOWHERE in production code, and the grouping works
 * for any service and any metric the payload happens to carry — which is what
 * {@link #aMetricNobodyHasAlertedOnBeforeGroupsCorrectly()} exists to prove.</p>
 */
class IncidentAggregationTest {

    private static final String ORG = "default";

    private AlertRepository alerts;
    private AlertEventRepository events;
    private AlertRuleRepository rules;
    private AlertProcessingServiceImpl service;

    /** Stands in for the alerts table, keyed the way the real open-episode index is. */
    private final Map<String, AlertEntity> openEpisodes = new LinkedHashMap<>();
    private final List<AlertEntity> allSaved = new ArrayList<>();
    private final List<AlertEventEntity> history = new ArrayList<>();

    private final AlertIngestProperties ingest = new AlertIngestProperties();
    private final AlertIncidentProperties incidentProps = new AlertIncidentProperties();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        events = mock(AlertEventRepository.class);
        rules = mock(AlertRuleRepository.class);

        when(rules.findByOrgAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());

        // The open-episode lookup, mirroring uq_alerts_open_episode: at most one
        // non-terminal row per (org, fingerprint).
        //
        // Stubs the TWO-arg overload, which is the one the service calls. It is a
        // default method that delegates to the @Query method, but Mockito stubs
        // default methods too rather than running their bodies — so stubbing the
        // three-arg version alone leaves the real call answering Optional.empty()
        // and every firing silently opens a new episode.
        when(alerts.findOpenEpisode(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(
                        openEpisodes.get(key(inv.getArgument(0), inv.getArgument(1)))));

        when(alerts.findMaxEpisode(anyString(), anyString())).thenAnswer(inv -> {
            String fingerprint = inv.getArgument(1);
            return allSaved.stream()
                    .filter(a -> fingerprint.equals(a.getFingerprint()))
                    .mapToInt(AlertEntity::getEpisode)
                    .max().orElse(0);
        });

        when(alerts.save(any(AlertEntity.class))).thenAnswer(inv -> {
            AlertEntity a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId((long) (allSaved.size() + 1));
                allSaved.add(a);
            }
            String k = key(a.getOrg(), a.getFingerprint());
            if (a.isTerminal()) {
                openEpisodes.remove(k);
            } else {
                openEpisodes.put(k, a);
            }
            return a;
        });

        // Idempotency, mirroring the unique index on (org, event_hash).
        when(events.existsByOrgAndEventHash(anyString(), anyString())).thenAnswer(inv ->
                history.stream().anyMatch(e -> inv.getArgument(0).equals(e.getOrg())
                        && inv.getArgument(1).equals(e.getEventHash())));
        when(events.save(any(AlertEventEntity.class))).thenAnswer(inv -> {
            AlertEventEntity e = inv.getArgument(0);
            boolean clash = e.getEventHash() != null && history.stream().anyMatch(
                    h -> e.getEventHash().equals(h.getEventHash()) && e.getOrg().equals(h.getOrg()));
            if (clash) {
                throw new DataIntegrityViolationException("duplicate event_hash");
            }
            history.add(e);
            return e;
        });

        service = new AlertProcessingServiceImpl(alerts, events, rules, incidentProps);
    }

    private static String key(String org, String fingerprint) {
        return org + "\0" + fingerprint;
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /**
     * One stream row, mapped exactly as ingest maps it.
     *
     * <p>Deliberately goes through {@link AlertStreamRowMapper} rather than building an
     * {@link AlertEvent} by hand: the fingerprint and the event hash are what these
     * tests are about, and hand-setting them would test the assertions rather than the
     * grouping.</p>
     */
    private AlertEvent event(long micros, String service, String rule, String severity,
            Double value, Long threshold) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_timestamp", micros);
        row.put("alertname", rule);
        row.put("service_name", service);
        row.put("severity", severity);
        if (value != null) row.put("current_usage", String.valueOf(value));
        if (threshold != null) row.put("alert_threshold", String.valueOf(threshold));
        return AlertStreamRowMapper.map(row, ORG, ingest.getMapping(), 8_000,
                ingest.getIdentityFields(), incidentProps.getSeverityOrder(),
                incidentProps.isStripSeveritySuffix());
    }

    private AlertEntity store(AlertEvent e) {
        service.store(e, "stream");
        return openEpisodes.get(key(e.getOrg(), e.getFingerprint()));
    }

    // ── 1-4: grouping ────────────────────────────────────────────────────────

    @Test
    void firstEventCreatesAnIncident() {
        AlertEntity incident = store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));

        assertNotNull(incident);
        assertEquals(1, incident.getOccurrences());
        assertEquals(AlertState.FIRING, incident.getStatus());
        assertEquals(1, allSaved.size());
    }

    @Test
    void sameServiceAndTypeFoldsIntoTheSameIncident() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        AlertEntity incident = store(event(2_000L, "nifi", "cpu-critical", "critical", 87.0, 80L));

        assertEquals(2, incident.getOccurrences(), "the second firing must not open a new row");
        assertEquals(1, allSaved.size(), "exactly one incident row");
    }

    @Test
    void sameServiceDifferentTypeIsASeparateIncident() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        store(event(2_000L, "nifi", "ram-warning", "warning", 75.0, 70L));

        assertEquals(2, allSaved.size(),
                "one service with two different problems is two incidents");
    }

    @Test
    void differentServicesSameTypeAreSeparateIncidents() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        store(event(2_000L, "kafka", "cpu-critical", "critical", 90.0, 80L));

        assertEquals(2, allSaved.size(),
                "the same metric on two services is two incidents, not one");
    }

    /** The prompt's worked example, end to end. */
    @Test
    void theEscalationScenarioProducesThreeIncidents() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        store(event(2_000L, "nifi", "cpu-critical", "critical", 87.0, 80L));
        AlertEntity nifiCpu = store(event(3_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L));

        assertEquals(1, allSaved.size(), "three firings of one condition are ONE incident");
        assertEquals(3, nifiCpu.getOccurrences());
        assertEquals("disaster", nifiCpu.getSeverity());
        assertEquals(96.0, nifiCpu.getAggValue());

        store(event(4_000L, "nifi", "ram-warning", "warning", 75.0, 70L));
        assertEquals(2, allSaved.size());

        store(event(5_000L, "kafka", "cpu-critical", "critical", 90.0, 80L));
        assertEquals(3, allSaved.size());
    }

    // ── 5-7: severity ────────────────────────────────────────────────────────

    @Test
    void severityEscalatesWithTheWorstFiring() {
        store(event(1_000L, "nifi", "cpu-warning", "warning", 75.0, 70L));
        AlertEntity incident = store(event(2_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L));

        assertEquals("disaster", incident.getSeverity());
        assertEquals(1, allSaved.size(), "an escalation is the same incident");
    }

    /**
     * A lower-severity firing must NOT downgrade a live incident.
     *
     * <p>OpenObserve states what it saw on that evaluation, so a warning after a
     * disaster is one sample of a condition that is still open — not the problem
     * improving. Relabelling it is how an incident stops being paged about while it is
     * still happening.</p>
     */
    @Test
    void aLaterLowerSeverityFiringDoesNotDowngradeAnOpenIncident() {
        store(event(1_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L));
        AlertEntity incident = store(event(2_000L, "nifi", "cpu-warning", "warning", 71.0, 70L));

        assertEquals("disaster", incident.getSeverity(),
                "a live disaster must not be relabelled by a later lower sample");
        assertEquals(2, incident.getOccurrences(), "but it is still counted");
    }

    /** Resolution is the downgrade path, and it is the EXISTING recovery semantic. */
    @Test
    void aNewEpisodeAfterResolutionStartsFromItsOwnSeverity() {
        AlertEntity first = store(event(1_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L));
        first.resolve("auto:stale", null, true);
        alerts.save(first);

        AlertEntity second = store(event(2_000L, "nifi", "cpu-warning", "warning", 71.0, 70L));

        assertNotEquals(first.getId(), second.getId(), "resolution ends the episode");
        assertEquals(first.getFingerprint(), second.getFingerprint(),
                "the same identity — it is the next episode of it, not a different alert");
        assertEquals("warning", second.getSeverity(),
                "a resolved incident does not hold its old severity over a new episode");
        assertEquals(2, second.getEpisode(), "episodes number continuously per identity");
    }

    // ── 8-9: dynamic values ──────────────────────────────────────────────────

    @Test
    void valuesAndThresholdsFollowTheLatestFiring() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        AlertEntity incident = store(event(2_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L));

        assertEquals(96.0, incident.getAggValue());
        assertEquals(90L, incident.getThreshold());
    }

    @Test
    void aFieldMissingFromTheNewEventKeepsItsPreviousValue() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        // Same condition, but this firing's template rendered no threshold.
        AlertEntity incident = store(event(2_000L, "nifi", "cpu-critical", "critical", 87.0, null));

        assertEquals(80L, incident.getThreshold(),
                "an absent field must not erase a value that was known");
        assertEquals(87.0, incident.getAggValue(), "while a present one still updates");
    }

    // ── 10: idempotency ──────────────────────────────────────────────────────

    /**
     * The same OpenObserve event seen twice is counted once.
     *
     * <p>Not hypothetical: the poll window carries a deliberate overlap, and several
     * alerts can share one microsecond, so rows ARE re-read by design.</p>
     */
    @Test
    void reprocessingTheSameEventDoesNotIncrementTheCount() {
        AlertEvent first = event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L);
        assertTrue(service.store(first, "stream"), "the first sighting is new");

        AlertEvent replay = event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L);
        assertEquals(first.getEventHash(), replay.getEventHash(), "same row, same hash");
        assertTrue(!service.store(replay, "stream"), "the replay is not new");

        AlertEntity incident = openEpisodes.get(key(ORG, first.getFingerprint()));
        assertEquals(1, incident.getOccurrences(), "a re-read must not double-count");

        // A genuinely different event does count.
        service.store(event(2_000L, "nifi", "cpu-critical", "critical", 87.0, 80L), "stream");
        assertEquals(2, incident.getOccurrences());
    }

    // ── 11-12: type derivation ───────────────────────────────────────────────

    @Test
    void severityVariantsOfOneRuleShareAnIncidentType() {
        AlertEvent warning  = event(1_000L, "nifi", "cpu-warning", "warning", 75.0, 70L);
        AlertEvent disaster = event(2_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L);

        assertEquals(warning.getIncidentType(), disaster.getIncidentType());
        assertEquals(warning.getFingerprint(), disaster.getFingerprint());
        assertNotEquals(warning.getAlertName(), disaster.getAlertName(),
                "the rule names still differ — only the derived type collapses");
    }

    @Test
    void aRuleNameWithoutASeverityTokenIsUnchanged() {
        AlertEvent e = event(1_000L, "svc", "IoTOpsCIMService_high_cpu", "critical", 0.9, 1L);

        assertEquals("IoTOpsCIMService_high_cpu", e.getIncidentType(),
                "the other naming convention in this deployment must pass through untouched");
    }

    // ── 13: history ──────────────────────────────────────────────────────────

    @Test
    void everyEventIsRetainedAsHistory() {
        store(event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L));
        store(event(2_000L, "nifi", "cpu-critical", "critical", 87.0, 80L));
        store(event(3_000L, "nifi", "cpu-disaster", "disaster", 96.0, 90L));

        assertEquals(3, history.size(),
                "aggregating into one incident must not discard the individual events");
        assertEquals(1, allSaved.size());
    }

    // ── 14: concurrency ──────────────────────────────────────────────────────

    /**
     * Two deliveries racing on one identity produce ONE incident.
     *
     * <p>The read finding nothing does not mean nothing is there — the partial unique
     * index is what actually decides. This drives that path: the insert is rejected, and
     * the firing folds into the episode the other delivery opened.</p>
     */
    @Test
    void aRacingInsertFoldsIntoTheEpisodeTheWinnerOpened() {
        AlertEvent first = event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L);
        AlertEntity winner = store(first);

        AlertEvent racer = event(2_000L, "nifi", "cpu-critical", "critical", 87.0, 80L);
        // The lookup misses once — the state the loser of the race sees — then the
        // insert is rejected and the retry finds the winner.
        when(alerts.findOpenEpisode(anyString(), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(alerts.save(any(AlertEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_alerts_open_episode"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.store(racer, "stream");

        assertEquals(2, winner.getOccurrences(), "the racer folded in rather than duplicating");
        assertEquals(1, allSaved.size());
    }

    /** A duplicate event hash that slips past the pre-check rolls the whole copy back. */
    @Test
    void aRacedEventHashRollsBackRatherThanDoubleCounting() {
        AlertEvent first = event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L);
        service.store(first, "stream");

        // Pre-check lies (as it would if the other writer committed just after it ran),
        // so the unique index is what stops it.
        when(events.existsByOrgAndEventHash(anyString(), anyString())).thenReturn(false);

        AlertEvent replay = event(1_000L, "nifi", "cpu-critical", "critical", 82.0, 80L);
        assertThrows(DataIntegrityViolationException.class,
                () -> service.store(replay, "stream"),
                "must propagate so the transaction rolls back the occurrence increment");
    }

    // ── 15: works for anything ───────────────────────────────────────────────

    /**
     * A metric and service this codebase has never heard of group correctly.
     *
     * <p>The guarantee behind "do not hardcode": nothing in production code enumerates
     * services or metrics, so a rule on something invented after this test was written
     * needs no change to work.</p>
     */
    @Test
    void aMetricNobodyHasAlertedOnBeforeGroupsCorrectly() {
        store(event(1_000L, "quantum-mesh-7", "flux-capacitor-jitter-warning", "warning", 3.0, 2L));
        AlertEntity incident = store(
                event(2_000L, "quantum-mesh-7", "flux-capacitor-jitter-disaster", "disaster", 9.0, 8L));

        assertEquals(1, allSaved.size(), "grouped without any knowledge of this metric");
        assertEquals(2, incident.getOccurrences());
        assertEquals("disaster", incident.getSeverity());
        assertEquals("flux-capacitor-jitter", incident.getIncidentType());
    }
}
