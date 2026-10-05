package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.config.AlertRuleSyncProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertRuleRequestDTO;
import com.trinity.observability.dto.alerts.AlertRuleResponseDTO;
import com.trinity.observability.dto.alerts.AlertsResponseDTO;
import com.trinity.observability.entity.ActivationMode;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.repository.AlertRuleEventRepository;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.service.AlertHistoryDestinationService;
import com.trinity.observability.service.AlertsService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers "enable now, or later".
 *
 * <p>The state this adds is small and the ways to get it subtly wrong are not.
 * {@code enabled} still has to be the only thing that decides evaluation —
 * anything else would mean two answers to "is this rule live" — while the
 * activation mode carries the INTENT, which is the part a boolean cannot hold.
 * The tests below pin the four places those two can fall out of step: saving,
 * enabling by hand, disabling by hand, and the timer.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertRuleActivationTest {

    private static final String ORG = "default";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private AlertRuleRepository repository;
    @Mock
    private AlertRuleEventRepository ruleEvents;
    @Mock
    private AlertsService alertsService;
    @Mock
    private AlertHistoryDestinationService historyDestinations;
    @Mock
    private TransactionTemplate tx;

    private OpenObserveConfig config;
    private AlertRuleServiceImpl service;

    /** Stands in for the table, so save/re-read round trips behave as they do live. */
    private final Map<UUID, AlertRuleEntity> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", ORG);

        // The template runs its callback inline. The transaction boundaries are
        // not what is under test here; what happens inside them is.
        when(tx.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));

        when(repository.save(any(AlertRuleEntity.class))).thenAnswer(invocation -> {
            AlertRuleEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(ids.getAndIncrement());
            }
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(Instant.now());
            }
            entity.setUpdatedAt(Instant.now());
            rows.put(entity.getUuid(), entity);
            return entity;
        });
        when(repository.findByUuid(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.findByUuidAndDeletedAtIsNull(any()))
                .thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.findById(any())).thenAnswer(i -> rows.values().stream()
                .filter(r -> i.getArgument(0).equals(r.getId())).findFirst());
        when(repository.existsByOrgAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(false);
        when(repository.findByOrgAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());

        when(historyDestinations.isEnabled()).thenReturn(false);

        AlertsResponseDTO.WriteResult accepted = new AlertsResponseDTO.WriteResult();
        accepted.setOk(true);
        accepted.setAlertId("oo-1");
        when(alertsService.create(any(), any())).thenReturn(accepted);
        when(alertsService.update(anyString(), any(), any())).thenReturn(accepted);

        service = new AlertRuleServiceImpl(repository, ruleEvents, alertsService,
                historyDestinations, new AlertRuleSyncProperties(), config, tx);
    }

    private AlertRuleRequestDTO.SaveRule rule(String name) {
        AlertRuleRequestDTO.SaveRule request = new AlertRuleRequestDTO.SaveRule();
        request.setName(name);
        request.setStreamType("logs");
        request.setStreamName("default");
        request.setQueryCondition(MAPPER.createObjectNode());
        request.setTriggerCondition(MAPPER.createObjectNode());
        return request;
    }

    private AlertRuleEntity stored(AlertRuleResponseDTO.WriteResult result) {
        return rows.get(UUID.fromString(result.getRule().getUuid()));
    }

    // ── saving ──────────────────────────────────────────────────────────────

    @Test
    void aRuleWithNoActivationBehavesExactlyAsItDidBefore() {
        // The compatibility guarantee. The existing frontend sends no activation
        // at all, and it must keep producing live rules.
        AlertRuleEntity entity = stored(service.create(rule("legacy")));

        assertTrue(entity.isEnabled());
        assertEquals(ActivationMode.NOW, entity.getActivationMode());
        assertNull(entity.getActivateAt());
        assertFalse(entity.isStaged());
    }

    @Test
    void anExplicitlyDisabledRuleWithNoActivationIsOffNotStaged() {
        // enabled=false on its own has always meant "off". Reading it as staged
        // would relabel every suppressed rule in the estate as "starts later".
        AlertRuleRequestDTO.SaveRule request = rule("suppressed");
        request.setEnabled(false);

        AlertRuleEntity entity = stored(service.create(request));

        assertFalse(entity.isEnabled());
        assertEquals(ActivationMode.NOW, entity.getActivationMode());
        assertFalse(entity.isStaged());
    }

    @Test
    void laterStoresTheRuleWithoutLettingItEvaluate() {
        AlertRuleRequestDTO.SaveRule request = rule("staged");
        request.setActivation("LATER");

        AlertRuleEntity entity = stored(service.create(request));

        assertFalse(entity.isEnabled(), "a staged rule must not be evaluating");
        assertEquals(ActivationMode.LATER, entity.getActivationMode());
        assertNull(entity.getActivateAt(), "LATER waits for a person, not a clock");
        assertTrue(entity.isStaged());
    }

    @Test
    void laterOverridesAnEnabledFlagRatherThanFightingIt() {
        // The editor may leave its enabled toggle on while the operator picks
        // "later". Honouring the toggle would make the rule live immediately —
        // the exact opposite of what was asked for.
        AlertRuleRequestDTO.SaveRule request = rule("staged");
        request.setActivation("LATER");
        request.setEnabled(true);

        assertFalse(stored(service.create(request)).isEnabled());
    }

    @Test
    void scheduledStoresTheStartTimeAndStaysOffUntilThen() {
        Instant when = Instant.now().plus(2, ChronoUnit.HOURS);
        AlertRuleRequestDTO.SaveRule request = rule("monday");
        request.setActivation("SCHEDULED");
        request.setActivateAt(when.toString());

        AlertRuleEntity entity = stored(service.create(request));

        assertFalse(entity.isEnabled());
        assertEquals(ActivationMode.SCHEDULED, entity.getActivationMode());
        assertEquals(when, entity.getActivateAt());
        assertTrue(entity.isStaged());
    }

    @Test
    void acceptsAnOffsetTimeAsWellAsAZuluOne() {
        AlertRuleRequestDTO.SaveRule request = rule("ist");
        request.setActivation("SCHEDULED");
        request.setActivateAt(Instant.now().plus(3, ChronoUnit.HOURS)
                .atZone(java.time.ZoneId.of("+05:30")).toOffsetDateTime().toString());

        assertNotNull(stored(service.create(request)).getActivateAt());
    }

    @Test
    void refusesAScheduledRuleWithNoStartTime() {
        // It would wait forever while looking perfectly healthy in the list.
        AlertRuleRequestDTO.SaveRule request = rule("nowhen");
        request.setActivation("SCHEDULED");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void refusesALocalTimeWithNoOffset() {
        // "Monday 09:00" typed in IST and read as UTC starts the rule five and a
        // half hours late, with nothing in the stored row to show that it did.
        AlertRuleRequestDTO.SaveRule request = rule("ambiguous");
        request.setActivation("SCHEDULED");
        request.setActivateAt("2030-09-22T09:00:00");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void refusesAStartTimeThatHasAlreadyPassed() {
        // Stored, it would go live on the next sweep — the opposite of what
        // somebody typing a date is asking for, and they would learn about it
        // from a page rather than from the screen.
        AlertRuleRequestDTO.SaveRule request = rule("yesterday");
        request.setActivation("SCHEDULED");
        request.setActivateAt(Instant.now().minus(1, ChronoUnit.DAYS).toString());

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void refusesAnActivationItDoesNotRecognise() {
        AlertRuleRequestDTO.SaveRule request = rule("typo");
        request.setActivation("SOMEDAY");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void reportsStagingOnTheRuleItReturns() {
        AlertRuleRequestDTO.SaveRule request = rule("staged");
        request.setActivation("LATER");

        AlertRuleResponseDTO.Rule dto = service.create(request).getRule();

        assertEquals("LATER", dto.getActivation());
        assertTrue(dto.isStaged(), "the list must not render this as an ordinary disabled rule");
        assertFalse(dto.isEnabled());
    }

    // ── enabling and disabling by hand ──────────────────────────────────────

    @Test
    void enablingAStagedRuleSettlesWhateverWasHoldingItBack() {
        // Leaving the schedule in place would let the sweep re-enable the rule
        // the next time somebody deliberately switched it off.
        AlertRuleRequestDTO.SaveRule request = rule("monday");
        request.setActivation("SCHEDULED");
        request.setActivateAt(Instant.now().plus(2, ChronoUnit.HOURS).toString());
        UUID uuid = UUID.fromString(service.create(request).getRule().getUuid());

        service.setEnabled(uuid, true, "alice");

        AlertRuleEntity entity = rows.get(uuid);
        assertTrue(entity.isEnabled());
        assertEquals(ActivationMode.NOW, entity.getActivationMode());
        assertNull(entity.getActivateAt());
        assertNotNull(entity.getActivatedAt());
    }

    @Test
    void disablingIsSuppressionNotStaging() {
        UUID uuid = UUID.fromString(service.create(rule("live")).getRule().getUuid());

        service.setEnabled(uuid, false, "alice");

        AlertRuleEntity entity = rows.get(uuid);
        assertFalse(entity.isEnabled());
        assertEquals(ActivationMode.NOW, entity.getActivationMode());
        assertFalse(entity.isStaged(), "a rule switched off must not read as 'starts later'");
    }

    @Test
    void anExistingRuleCanBePostponedWithoutResendingItsDefinition() {
        UUID uuid = UUID.fromString(service.create(rule("live")).getRule().getUuid());
        Instant when = Instant.now().plus(1, ChronoUnit.DAYS);

        service.setActivation(uuid, "SCHEDULED", when.toString(), "alice");

        AlertRuleEntity entity = rows.get(uuid);
        assertFalse(entity.isEnabled());
        assertEquals(ActivationMode.SCHEDULED, entity.getActivationMode());
        assertEquals(when, entity.getActivateAt());
    }

    @Test
    void aScheduleCanBeCancelledBackToLater() {
        UUID uuid = UUID.fromString(service.create(rule("live")).getRule().getUuid());
        service.setActivation(uuid, "SCHEDULED",
                Instant.now().plus(1, ChronoUnit.DAYS).toString(), "alice");

        service.setActivation(uuid, "LATER", null, "alice");

        AlertRuleEntity entity = rows.get(uuid);
        assertEquals(ActivationMode.LATER, entity.getActivationMode());
        assertNull(entity.getActivateAt(), "no timer may act on it any more");
    }

    @Test
    void settingActivationValidatesBeforeItWritesAnything() {
        UUID uuid = UUID.fromString(service.create(rule("live")).getRule().getUuid());
        AlertRuleEntity before = rows.get(uuid);
        boolean wasEnabled = before.isEnabled();

        assertThrows(IllegalArgumentException.class,
                () -> service.setActivation(uuid, "SCHEDULED", "not-a-date", "alice"));

        assertEquals(wasEnabled, rows.get(uuid).isEnabled(), "a bad date must not disable the rule");
    }

    // ── the timer ───────────────────────────────────────────────────────────

    @Test
    void theSweepTurnsDueRulesOnAndPushesThem() {
        AlertRuleRequestDTO.SaveRule request = rule("monday");
        request.setActivation("SCHEDULED");
        request.setActivateAt(Instant.now().plusSeconds(60).toString());
        UUID uuid = UUID.fromString(service.create(request).getRule().getUuid());

        // Its time has come round.
        when(repository.findDueForActivation(anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(rows.get(uuid)));

        service.activateDueRules();

        AlertRuleEntity entity = rows.get(uuid);
        assertTrue(entity.isEnabled());
        assertEquals(ActivationMode.NOW, entity.getActivationMode());
        assertNull(entity.getActivateAt(), "the schedule is history, not pending work");
        assertNotNull(entity.getActivatedAt());
    }

    @Test
    void aRuleStaysActivatedEvenWhenThePushFails() {
        // Activating only on a successful push would skip the rule and leave it
        // waiting on a time that has already gone by — silently, forever. Left
        // enabled-but-PENDING, the existing retry sweep finishes the job.
        AlertRuleRequestDTO.SaveRule request = rule("monday");
        request.setActivation("SCHEDULED");
        request.setActivateAt(Instant.now().plusSeconds(60).toString());
        UUID uuid = UUID.fromString(service.create(request).getRule().getUuid());

        when(repository.findDueForActivation(anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(rows.get(uuid)));
        when(alertsService.update(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("OpenObserve is down"));
        when(alertsService.create(any(), any()))
                .thenThrow(new IllegalStateException("OpenObserve is down"));

        service.activateDueRules();

        assertTrue(rows.get(uuid).isEnabled());
        assertEquals(com.trinity.observability.entity.SyncStatus.FAILED,
                rows.get(uuid).getSyncStatus());
    }

    @Test
    void anEmptySweepDoesNothingAtAll() {
        when(repository.findDueForActivation(anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.activateDueRules();

        verify(alertsService, never()).update(anyString(), any(), any());
    }

    @Test
    void aFailingSweepDoesNotEscape() {
        // Spring reschedules either way, but an activation that did not happen is
        // exactly the kind of thing nobody notices until it is needed, so the
        // reason has to reach the log rather than the caller.
        when(repository.findDueForActivation(anyString(), any(Instant.class), any(Pageable.class)))
                .thenThrow(new IllegalStateException("database is down"));

        assertDoesNotThrow(() -> service.activateDueRules());
    }

    // ── what the rule sends to OpenObserve ──────────────────────────────────

    @Test
    void aStagedRuleIsPushedToOpenObserveDisabled() {
        // "Stored but not evaluating" has to be true THERE, not just here.
        // A staged rule that reached OpenObserve enabled would page people on a
        // schedule the operator had not agreed to yet.
        AlertRuleRequestDTO.SaveRule request = rule("staged");
        request.setActivation("LATER");

        List<Boolean> pushedEnabled = new ArrayList<>();
        when(alertsService.create(any(), any())).thenAnswer(invocation -> {
            pushedEnabled.add(invocation.<com.fasterxml.jackson.databind.JsonNode>getArgument(0)
                    .path("enabled").asBoolean(true));
            AlertsResponseDTO.WriteResult result = new AlertsResponseDTO.WriteResult();
            result.setOk(true);
            result.setAlertId("oo-1");
            return result;
        });

        service.create(request);

        assertEquals(List.of(false), pushedEnabled);
    }
}
