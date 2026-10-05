package com.trinity.observability.serviceimpl;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertDisplayProperties;
import com.trinity.observability.config.AlertLifecycleProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.dto.alerts.AlertQueryResponseDTO;
import com.trinity.observability.entity.AlertEntity;
import com.trinity.observability.repository.AlertEventRepository;
import com.trinity.observability.repository.AlertRepository;
import com.trinity.observability.repository.AlertRuleRepository;
import com.trinity.observability.serviceimpl.AlertStreamMeasurementLookup.Measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the Limit column's value comes from.
 *
 * <p>The stream is the source of truth — {@code raw_payload} is a snapshot taken at
 * ingest and capped by {@code alerts.ingest.max-payload-chars}, so it can be missing
 * fields the stream still has. But the stream cannot answer for every row (an alert
 * older than the lookback, retention having dropped it, a window OpenObserve served
 * only partially), so the payload has to remain a per-row fallback. These tests pin
 * that precedence in both directions.</p>
 */
class AlertQueryServiceImplMeasurementTest {

    private static final String ALERT = "K8s Pod High Memory Usage";
    private static final String ENTITY = "dev/elasticsearch-6d7697cddd-89xfq";

    private AlertRepository alerts;
    private AlertStreamMeasurementLookup lookup;
    private AlertQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        AlertEventRepository events = mock(AlertEventRepository.class);
        AlertRuleRepository rules = mock(AlertRuleRepository.class);
        lookup = mock(AlertStreamMeasurementLookup.class);

        OpenObserveConfig config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", "default");

        service = new AlertQueryServiceImpl(alerts, events, rules, config,
                new AlertDisplayProperties(), new AlertLifecycleProperties(), lookup,
                new AlertIncidentProperties());
    }

    /** One stored alert whose payload carries a 2048 MB ceiling. */
    private void givenStoredAlert(String rawPayload) {
        AlertEntity alert = new AlertEntity();
        alert.setId(1L);
        alert.setOrg("default");
        alert.setAlertName(ALERT);
        alert.setEntities(ENTITY);
        alert.setServiceName(ENTITY);
        alert.setFingerprint("c9daa063b3a9a748");
        alert.setRawPayload(rawPayload);

        Page<AlertEntity> page = new PageImpl<>(List.of(alert));
        when(alerts.search(anyString(), anyBoolean(), anyList(), anyList(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(page);
    }

    private AlertQueryResponseDTO.Row firstRow() {
        return service.search(new AlertQueryRequestDTO.Search()).getItems().get(0);
    }

    private static String payloadWith2048() {
        return "{\"alertname\":\"" + ALERT + "\",\"limit_mb\":2048.0,"
                + "\"display_value\":\"87.4% (1790.0MB / 2048.0MB)\"}";
    }

    // ── the stream wins ───────────────────────────────────────────────────────

    /**
     * The whole point of reading the stream: when the two disagree, OpenObserve is
     * right. A pod's limit can be raised, and the payload still holds the old one.
     */
    @Test
    void theStreamValueOverridesTheStoredPayload() {
        givenStoredAlert(payloadWith2048());
        when(lookup.forOrg("default")).thenReturn(Map.of(
                AlertStreamMeasurementLookup.key(ALERT, ENTITY),
                new Measurement("4050 MB", "5120 MB", "79.1% (4050.0MB / 5120.0MB)")));

        AlertQueryResponseDTO.Row row = firstRow();

        assertEquals("4050 MB", row.getUsedLabel());
        assertEquals("5120 MB", row.getLimitLabel());
        assertEquals("79.1% (4050.0MB / 5120.0MB)", row.getDisplayValue());
        assertNotEquals("2048 MB", row.getLimitLabel());
    }

    /**
     * Both sides of the measurement come from the stream together.
     *
     * <p>They have to: "4040 MB" beside a limit read from a different, staler source
     * would be a ratio that never existed.</p>
     */
    @Test
    void bothUsedAndLimitComeFromTheSameStreamRow() {
        givenStoredAlert("{\"alertname\":\"" + ALERT + "\",\"used_mb\":1790,\"limit_mb\":2048.0}");
        when(lookup.forOrg(any())).thenReturn(Map.of(
                AlertStreamMeasurementLookup.key(ALERT, ENTITY),
                new Measurement("4040 MB", "5120 MB", "78.9% (4040.0MB / 5120.0MB)")));

        AlertQueryResponseDTO.Row row = firstRow();

        assertEquals("4040 MB", row.getUsedLabel());
        assertEquals("5120 MB", row.getLimitLabel());
    }

    /** The payload fallback fills BOTH sides, not just the limit. */
    @Test
    void thePayloadFallbackSuppliesTheUsedAmountToo() {
        givenStoredAlert("{\"alertname\":\"" + ALERT + "\",\"used_mb\":4040,\"limit_mb\":5120,"
                + "\"display_value\":\"78.9% (4040.0MB / 5120.0MB)\"}");
        when(lookup.forOrg(any())).thenReturn(Map.of());

        AlertQueryResponseDTO.Row row = firstRow();

        assertEquals("4040 MB", row.getUsedLabel());
        assertEquals("5120 MB", row.getLimitLabel());
    }

    // ── and falls back per row ────────────────────────────────────────────────

    /** An alert the stream has no row for — older than the lookback, say. */
    @Test
    void aRowMissingFromTheStreamFallsBackToItsPayload() {
        givenStoredAlert(payloadWith2048());
        when(lookup.forOrg("default")).thenReturn(Map.of(
                AlertStreamMeasurementLookup.key("Some Other Alert", "other/pod"),
                new Measurement("500 MB", "1 GB", "x")));

        assertEquals("2048 MB", firstRow().getLimitLabel());
    }

    /**
     * The stream being wholly unavailable must not blank the column.
     *
     * <p>An empty index is what the lookup returns when OpenObserve is unreachable or
     * the feature is switched off, so this is the degraded path — and it has to land
     * on the previous behaviour rather than on em-dashes.</p>
     */
    @Test
    void anEmptyStreamIndexFallsBackToPayloadsForEveryRow() {
        givenStoredAlert(payloadWith2048());
        when(lookup.forOrg(any())).thenReturn(Map.of());

        assertEquals("2048 MB", firstRow().getLimitLabel());
    }

    /** Neither source has it: blank, not zero and not a borrowed value. */
    @Test
    void anAlertWithNoCeilingAnywhereHasNoLimit() {
        givenStoredAlert("{\"alertname\":\"K8s Pod Crash\",\"display_value\":\"state: Pending\"}");
        when(lookup.forOrg(any())).thenReturn(Map.of());

        AlertQueryResponseDTO.Row row = firstRow();
        assertNull(row.getLimitLabel());
        assertEquals("state: Pending", row.getDisplayValue());
    }

    @Test
    void anUnstoredPayloadAndNoStreamRowIsHandledWithoutThrowing() {
        givenStoredAlert(null);
        when(lookup.forOrg(any())).thenReturn(Map.of());

        assertNull(firstRow().getLimitLabel());
    }

    // ── the join key ──────────────────────────────────────────────────────────

    /**
     * Case and whitespace are normalised because this joins two systems that do not
     * agree on either, and a near-miss shows up as a silently empty column.
     */
    @Test
    void theKeyIsCaseAndWhitespaceInsensitive() {
        assertEquals(AlertStreamMeasurementLookup.key(ALERT, ENTITY),
                AlertStreamMeasurementLookup.key("  " + ALERT.toUpperCase() + " ",
                        " " + ENTITY.toUpperCase() + "  "));
    }

    /**
     * Entity is part of the key, not decoration.
     *
     * <p>One rule fires for many pods with genuinely different ceilings — 2048 MB
     * against 5120 MB on the same rule — so keying on the alert name alone would
     * attach one pod's limit to another pod's row.
     */
    @Test
    void twoEntitiesOnTheSameRuleDoNotShareAKey() {
        assertNotEquals(
                AlertStreamMeasurementLookup.key(ALERT, "dev/kafka1-5f94c4959b-8hp6c"),
                AlertStreamMeasurementLookup.key(ALERT, "dev/kafka2-7578bc86bb-f2fgv"));
    }

    /** `entities` is tried before `serviceName`, and both are tried. */
    @Test
    void theServiceNameIsATriedAsAFallbackSpelling() {
        assertEquals(List.of("a", "b"), AlertStreamMeasurementLookup.entityCandidates("a", "b"));
        assertEquals(List.of("", "b"), AlertStreamMeasurementLookup.entityCandidates(null, "b"));
    }
}
