package com.trinity.observability.serviceimpl;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.entity.SyncStatus;
import com.trinity.observability.repository.AlertRuleRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirroring pass-through alert writes into {@code alert_rules}.
 *
 * <p>Rules created in the browser's wizard go straight to OpenObserve and used to
 * exist nowhere else. The mirror records them as they are written. Three things
 * about it fail silently if they regress, which is why they are asserted rather
 * than eyeballed:</p>
 *
 * <ul>
 *   <li>the full document must be captured, or a later re-sync pushes back a
 *       stripped alert and quietly deletes its priority, tags, deduplication
 *       window or composite expression;</li>
 *   <li>a second save must UPDATE the row it already wrote, or the unique index
 *       on (org, name) rejects the write and the mirror silently stops tracking
 *       the rule it was created for;</li>
 *   <li>a database failure must not reach the caller, because the alert is
 *       already live in OpenObserve and reporting a failure invites a retry that
 *       creates a duplicate.</li>
 * </ul>
 */
class AlertRuleMirrorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORG = "default";
    private static final String OO_ID = "3J7xjrR4FBY25cvd1QbaK5ff2kl";

    private AlertRuleRepository rules;
    private AlertRuleMirror mirror;

    @BeforeEach
    void setUp() {
        rules = mock(AlertRuleRepository.class);
        when(rules.findByOrgAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(rules.save(any(AlertRuleEntity.class))).thenAnswer(i -> i.getArgument(0));
        mirror = new AlertRuleMirror(rules);
    }

    private static JsonNode doc(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AlertRuleEntity captureSaved() {
        ArgumentCaptor<AlertRuleEntity> captor = ArgumentCaptor.forClass(AlertRuleEntity.class);
        verify(rules).save(captor.capture());
        return captor.getValue();
    }

    private static final String SCHEDULED = """
            {"name":"checkout_errors_high","description":"too many 5xx",
             "stream_type":"logs","stream_name":"default","is_real_time":false,
             "enabled":true,
             "query_condition":{"type":"custom","conditions":{"and":[]}},
             "trigger_condition":{"period":10,"threshold":3,"operator":">="},
             "destinations":["Incident"],
             "priority":2,"tags":["prod"],"row_template":"{{x}}",
             "deduplication":{"enabled":true,"fingerprint_fields":["service_name"]}}
            """;

    @Test
    void storesTheRuleWithItsOpenObserveIdAndMarksItSynced() {
        mirror.recordSaved(ORG, OO_ID, doc(SCHEDULED), "default", "tester");

        AlertRuleEntity saved = captureSaved();
        assertEquals(ORG, saved.getOrg());
        assertEquals("checkout_errors_high", saved.getName());
        assertEquals("default", saved.getFolderId());
        assertEquals("logs", saved.getStreamType());
        assertEquals("default", saved.getStreamName());
        assertEquals("scheduled", saved.getAlertType());
        assertTrue(saved.isEnabled());
        assertNotNull(saved.getUuid());
        assertEquals("tester", saved.getCreatedBy());

        // Already in OpenObserve by the time this runs, so it is synced and
        // linked. PENDING would queue a pointless push of a rule already there.
        assertEquals(OO_ID, saved.getOpenObserveId());
        assertEquals(SyncStatus.SYNCED, saved.getSyncStatus());
        assertNotNull(saved.getLastSyncedAt());
    }

    /* The field the whole design rests on. Without it, AlertRuleService's push
       rebuilds the document from the modelled columns alone and the alert loses
       every key below on the next sync — with the save reporting success. */
    @Test
    void capturesTheWholeDocumentIncludingUnmodelledFields() {
        mirror.recordSaved(ORG, OO_ID, doc(SCHEDULED), "default", null);

        String captured = captureSaved().getSourceDocument();
        assertNotNull(captured, "the full document must be stored");
        JsonNode parsed = doc(captured);
        assertEquals(2, parsed.path("priority").asInt());
        assertEquals("prod", parsed.path("tags").get(0).asText());
        assertEquals("{{x}}", parsed.path("row_template").asText());
        assertTrue(parsed.path("deduplication").path("enabled").asBoolean());
    }

    /* A composite has no stream at all, and both columns are NOT NULL with
       stream_type CHECK-constrained. Refusing the row would make the one alert
       type with no stream the one type the database cannot record. */
    @Test
    void storesACompositeDespiteItHavingNoStream() {
        JsonNode composite = doc("""
                {"name":"checkout_degraded","alert_type":"composite","is_real_time":false,
                 "enabled":true,
                 "composite_condition":{"expression":"{a} && {b}",
                                        "stale_child_policy":"use_last_state",
                                        "warning_counts_as_firing":true}}
                """);

        assertDoesNotThrow(() -> mirror.recordSaved(ORG, OO_ID, composite, "default", null));

        AlertRuleEntity saved = captureSaved();
        assertEquals("composite", saved.getAlertType());
        assertEquals("logs", saved.getStreamType(), "defaulted so the NOT NULL column can be written");
        assertEquals("checkout_degraded", saved.getStreamName());
        // The real definition survives where it matters.
        assertEquals("{a} && {b}",
                doc(saved.getSourceDocument()).path("composite_condition").path("expression").asText());
    }

    /* `is_real_time` is false for a composite exactly as it is for a scheduled
       alert, so inferring the kind from the boolean alone files every composite
       as scheduled. */
    @Test
    void prefersAlertTypeOverTheRealtimeBoolean() {
        assertEquals("realtime", mirrorAndRead("""
                {"name":"n","is_real_time":true,"stream_name":"s","stream_type":"logs"}
                """).getAlertType());
        assertEquals("composite", mirrorAndRead("""
                {"name":"n","alert_type":"composite","is_real_time":false}
                """).getAlertType());
        assertEquals("scheduled", mirrorAndRead("""
                {"name":"n","is_real_time":false,"stream_name":"s","stream_type":"logs"}
                """).getAlertType());
    }

    private AlertRuleEntity mirrorAndRead(String json) {
        AlertRuleRepository repo = mock(AlertRuleRepository.class);
        when(repo.findByOrgAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.findByOrgAndOpenObserveIdAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.save(any(AlertRuleEntity.class))).thenAnswer(i -> i.getArgument(0));
        new AlertRuleMirror(repo).recordSaved(ORG, OO_ID, doc(json), "default", null);
        ArgumentCaptor<AlertRuleEntity> captor = ArgumentCaptor.forClass(AlertRuleEntity.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    /* Saving twice must not try to insert twice: the unique index on (org, name)
       would reject it and the mirror would silently stop tracking the rule. */
    @Test
    void updatesTheExistingRowRatherThanInsertingASecond() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setUuid(UUID.randomUUID());
        existing.setOrg(ORG);
        existing.setName("checkout_errors_high");
        existing.setCreatedBy("someone-else");
        when(rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(ORG, OO_ID))
                .thenReturn(Optional.of(existing));

        mirror.recordSaved(ORG, OO_ID, doc(SCHEDULED), "default", "tester");

        AlertRuleEntity saved = captureSaved();
        assertEquals(existing.getUuid(), saved.getUuid(), "the same row must be reused");
        assertEquals("someone-else", saved.getCreatedBy(), "the original author is not rewritten");
        assertEquals("tester", saved.getUpdatedBy());
    }

    /* AlertRuleService creates its row locally and then pushes THROUGH the
       pass-through API, which lands here. Finding its row by name and linking it
       is the correct outcome; inserting a second row would collide. */
    @Test
    void adoptsARowThatExistsUnderTheSameNameButIsNotYetLinked() {
        AlertRuleEntity local = new AlertRuleEntity();
        local.setUuid(UUID.randomUUID());
        local.setOrg(ORG);
        local.setName("checkout_errors_high");
        when(rules.findByOrgAndNameAndDeletedAtIsNull(ORG, "checkout_errors_high"))
                .thenReturn(Optional.of(local));

        mirror.recordSaved(ORG, OO_ID, doc(SCHEDULED), "default", null);

        AlertRuleEntity saved = captureSaved();
        assertEquals(local.getUuid(), saved.getUuid());
        assertEquals(OO_ID, saved.getOpenObserveId());
        assertEquals(SyncStatus.SYNCED, saved.getSyncStatus());
    }

    @Test
    void softDeletesTheMirroredRow() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setUuid(UUID.randomUUID());
        existing.setOrg(ORG);
        existing.setName("checkout_errors_high");
        existing.markSynced(OO_ID);
        when(rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(ORG, OO_ID))
                .thenReturn(Optional.of(existing));

        mirror.recordDeleted(ORG, OO_ID, "tester");

        AlertRuleEntity saved = captureSaved();
        assertNotNull(saved.getDeletedAt());
        // The remote delete already succeeded, so there is no obligation left for
        // the next sync to act on.
        assertEquals(SyncStatus.SYNCED, saved.getSyncStatus());
    }

    @Test
    void recordsAnEnableToggle() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setUuid(UUID.randomUUID());
        existing.setOrg(ORG);
        existing.setName("checkout_errors_high");
        existing.setEnabled(true);
        existing.markSynced(OO_ID);
        when(rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(ORG, OO_ID))
                .thenReturn(Optional.of(existing));

        mirror.recordEnabled(ORG, OO_ID, false, "tester");

        assertFalse(captureSaved().isEnabled());
    }

    @Test
    void recordsAFolderMoveForTheRowsItKnows() {
        AlertRuleEntity known = new AlertRuleEntity();
        known.setUuid(UUID.randomUUID());
        known.setOrg(ORG);
        known.setName("checkout_errors_high");
        known.setFolderId("default");
        known.markSynced(OO_ID);
        when(rules.findByOrgAndOpenObserveIdAndDeletedAtIsNull(ORG, OO_ID))
                .thenReturn(Optional.of(known));

        // The second id is an alert this database has never adopted. It is
        // skipped rather than inserted: an import is what fixes that, not a move.
        mirror.recordMoved(ORG, java.util.List.of(OO_ID, "unknown-id"), "DLH Ranger", "tester");

        AlertRuleEntity saved = captureSaved();
        assertEquals("DLH Ranger", saved.getFolderId());
        assertEquals("tester", saved.getUpdatedBy());
    }

    @Test
    void doesNothingWhenThereIsNoMirroredRowToDeleteOrToggle() {
        mirror.recordDeleted(ORG, OO_ID, null);
        mirror.recordEnabled(ORG, OO_ID, false, null);
        verify(rules, never()).save(any());
    }

    /* The caller has already created the alert in OpenObserve. Throwing now would
       report a failure for work that succeeded, and a retry would duplicate it. */
    @Test
    void swallowsADatabaseFailureRatherThanFailingTheCaller() {
        when(rules.save(any(AlertRuleEntity.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertDoesNotThrow(() -> mirror.recordSaved(ORG, OO_ID, doc(SCHEDULED), "default", null));
        assertDoesNotThrow(() -> mirror.recordDeleted(ORG, OO_ID, null));
        assertDoesNotThrow(() -> mirror.recordEnabled(ORG, OO_ID, true, null));
    }

    @Test
    void ignoresADocumentWithNoName() {
        mirror.recordSaved(ORG, OO_ID, doc("{\"stream_name\":\"default\"}"), "default", null);
        verify(rules, never()).save(any());
    }

    @Test
    void toleratesAMissingOpenObserveId() {
        mirror.recordSaved(ORG, null, doc(SCHEDULED), "default", null);

        AlertRuleEntity saved = captureSaved();
        assertNull(saved.getOpenObserveId());
        // Not claimed as synced without an id: isLinked() would be false and a
        // sync would then force-create a duplicate in OpenObserve.
        assertEquals(SyncStatus.PENDING, saved.getSyncStatus());
    }
}
