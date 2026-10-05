package com.trinity.observability.serviceimpl;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertDisplayProperties;
import com.trinity.observability.config.AlertLifecycleProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.entity.AlertEntity;
import com.trinity.observability.repository.AlertEventRepository;
import com.trinity.observability.repository.AlertRepository;
import com.trinity.observability.repository.AlertRuleRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An acknowledgement has to name somebody.
 *
 * <p>"Acknowledged by unknown" was the real state of this audit trail. The UI
 * substituted that literal string whenever no session was stored — the normal case
 * in an embedded deployment — so the one field consulted to find out who owns an
 * incident recorded nothing, silently.</p>
 *
 * <p>These tests are on the SERVER for the reason the fix is: validating only in
 * the dialog leaves the next caller free to post "unknown" again, which is how it
 * happened the first time.</p>
 */
class AlertQueryServiceImplActorTest {

    private static final long ALERT_ID = 42L;

    private AlertRepository alerts;
    private AlertEventRepository events;
    private AlertLifecycleProperties lifecycle;
    private AlertQueryServiceImpl service;
    private AlertEntity alert;

    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        events = mock(AlertEventRepository.class);
        AlertRuleRepository rules = mock(AlertRuleRepository.class);

        OpenObserveConfig config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", "default");
        lifecycle = new AlertLifecycleProperties();

        // The stream lookup is stubbed to contribute nothing: these tests are about
        // the acknowledge rule, and the Limit column has its own tests. forOrg
        // returning an empty map is exactly the "stream unavailable" path, so toRow
        // falls back to the stored payload without any OpenObserve call.
        AlertStreamMeasurementLookup measurements = mock(AlertStreamMeasurementLookup.class);
        when(measurements.forOrg(any())).thenReturn(java.util.Map.of());

        service = new AlertQueryServiceImpl(alerts, events, rules, config,
                new AlertDisplayProperties(), lifecycle, measurements,
                new AlertIncidentProperties());

        alert = new AlertEntity();
        alert.setId(ALERT_ID);
        alert.setOrg("default");
        alert.setAlertName("K8s Pod High Memory Usage");
        alert.setFingerprint("c9daa063b3a9a748");
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(alert));
        when(alerts.save(any(AlertEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AlertQueryRequestDTO.Transition actor(String name) {
        AlertQueryRequestDTO.Transition body = new AlertQueryRequestDTO.Transition();
        body.setActor(name);
        return body;
    }

    // ── refused ───────────────────────────────────────────────────────────────

    @Test
    void acknowledgeWithNoBodyAtAllIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, null));
        verify(alerts, never()).save(any());
    }

    @Test
    void acknowledgeWithABlankNameIsRefused() {
        AlertQueryRequestDTO.Transition empty = actor("");
        AlertQueryRequestDTO.Transition blank = actor("   ");
        AlertQueryRequestDTO.Transition missing = actor(null);
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, empty));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, blank));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, missing));
        verify(alerts, never()).save(any());
    }

    /**
     * The exact string the old UI sent. Rejecting only empty input would have let
     * the original bug straight back through.
     */
    @Test
    void acknowledgeWithTheLiteralWordUnknownIsRefused() {
        AlertQueryRequestDTO.Transition lower = actor("unknown");
        AlertQueryRequestDTO.Transition upper = actor("UNKNOWN");
        AlertQueryRequestDTO.Transition padded = actor(" Unknown ");
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, lower));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, upper));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(ALERT_ID, padded));
        verify(alerts, never()).save(any());
    }

    // ── accepted, and recorded ────────────────────────────────────────────────

    @Test
    void acknowledgeWithARealNameRecordsThatName() {
        service.acknowledge(ALERT_ID, actor("Vishwanath V"));

        assertEquals("Vishwanath V", alert.getAcknowledgedBy());
        verify(alerts).save(alert);
    }

    @Test
    void surroundingWhitespaceIsTrimmedFromTheName() {
        service.acknowledge(ALERT_ID, actor("  Vishwanath V  "));

        assertEquals("Vishwanath V", alert.getAcknowledgedBy());
    }

    // ── scope of the rule ─────────────────────────────────────────────────────

    /**
     * Resolve is deliberately exempt.
     *
     * <p>The staleness sweep resolves alerts too, and it has no person to name —
     * requiring one would break automatic resolution, which is how an alert that
     * stops firing is ever closed at all.</p>
     */
    @Test
    void resolveDoesNotRequireANamedActor() {
        service.resolve(ALERT_ID, actor(""));

        verify(alerts).save(alert);
    }

    @Test
    void closeDoesNotRequireANamedActor() {
        service.close(ALERT_ID, actor(null));

        verify(alerts).save(alert);
    }

    /** Escape hatch for a deployment that cannot supply names yet. */
    @Test
    void theRequirementCanBeTurnedOff() {
        lifecycle.setRequireActorOnAcknowledge(false);

        service.acknowledge(ALERT_ID, actor("unknown"));

        verify(alerts).save(alert);
    }
}
