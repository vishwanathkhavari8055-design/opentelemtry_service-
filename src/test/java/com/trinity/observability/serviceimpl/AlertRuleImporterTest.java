package com.trinity.observability.serviceimpl;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertRuleResponseDTO;
import com.trinity.observability.entity.AlertRuleEntity;
import com.trinity.observability.entity.SyncStatus;
import com.trinity.observability.repository.AlertRuleRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Getting OpenObserve-authored rules into the database.
 *
 * <p>PostgreSQL was the source of truth only for rules created THROUGH this service.
 * On this deployment that meant the database knew about none of the rules that
 * actually fire: 20 rules in OpenObserve, 9 rows in {@code alert_rules}, no overlap,
 * and 73 of 90 stored alerts with no rule attached.</p>
 */
class AlertRuleImporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORG = "default";

    private FakeClient client;
    private AlertRuleRepository rules;
    private AlertRuleImporter importer;

    @BeforeEach
    void setUp() {
        client = new FakeClient();
        rules = mock(AlertRuleRepository.class);
        when(rules.findByOrgAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(rules.save(any(AlertRuleEntity.class))).thenAnswer(i -> i.getArgument(0));

        OpenObserveConfig config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", ORG);
        importer = new AlertRuleImporter(client, rules, config);
    }

    /** A rule as the LIST endpoint returns it — no stream or destinations. */
    private void givenRemoteRule(String name, String id) {
        client.list.add(node("{\"alert_id\":\"" + id + "\",\"name\":\"" + name + "\","
                + "\"folder_id\":\"749139\",\"description\":\"Node CPU critical band\","
                + "\"alert_type\":\"scheduled\",\"enabled\":true,"
                + "\"trigger_condition\":{\"period\":5,\"threshold\":1}}"));
        // ...and as the SINGLE-rule endpoint returns it, which is where the NOT NULL
        // columns actually come from.
        client.documents.put(id, node("{\"id\":\"" + id + "\",\"name\":\"" + name + "\","
                + "\"stream_type\":\"metrics\",\"stream_name\":\"system_cpu_utilization\","
                + "\"destinations\":[\"dst-telegram-critical\"],"
                + "\"query_condition\":{\"type\":\"sql\",\"sql\":\"SELECT 1\"},"
                + "\"trigger_condition\":{\"period\":5,\"threshold\":1},"
                + "\"enabled\":true,\"context_attributes\":{}}"));
    }

    private static JsonNode node(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AlertRuleEntity captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(AlertRuleEntity.class);
        verify(rules).save(captor.capture());
        return captor.getValue();
    }

    // ── importing ─────────────────────────────────────────────────────────────

    @Test
    void aRuleAuthoredInOpenObserveIsStored() {
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        AlertRuleResponseDTO.SyncResult result = importer.importAll("tester");

        assertTrue(result.isOk());
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(0, result.getFailed());

        AlertRuleEntity saved = captureSaved();
        assertEquals("k8s-node-cpu-critical", saved.getName());
        assertEquals(ORG, saved.getOrg());
        assertNotNull(saved.getUuid());
    }

    /**
     * The NOT NULL columns come from the single-rule document, not the listing.
     *
     * <p>{@code stream_name} and {@code stream_type} are absent from the list
     * response and non-nullable in the schema, so an importer that only read the
     * listing would fail every insert.</p>
     */
    @Test
    void theStreamIsTakenFromTheFullDocument() {
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        importer.importAll("tester");

        AlertRuleEntity saved = captureSaved();
        assertEquals("metrics", saved.getStreamType());
        assertEquals("system_cpu_utilization", saved.getStreamName());
        assertTrue(saved.getDestinations().contains("dst-telegram-critical"));
    }

    /**
     * The condition JSON is stored VERBATIM.
     *
     * <p>An alert document is a recursive structure OpenObserve keeps extending, so
     * anything remodelled through a DTO would be silently dropped the next time the
     * rule is saved. An imported rule that cannot round-trip has made things worse.</p>
     */
    @Test
    void theConditionIsStoredVerbatim() {
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        importer.importAll("tester");

        AlertRuleEntity saved = captureSaved();
        assertTrue(saved.getQueryCondition().contains("SELECT 1"),
                "query_condition was not preserved: " + saved.getQueryCondition());
        assertTrue(saved.getTriggerCondition().contains("\"period\":5"));
    }

    /** Imported means already in OpenObserve, so it must not be queued for a push. */
    @Test
    void anImportedRuleIsAlreadySyncedAndLinked() {
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        importer.importAll("tester");

        AlertRuleEntity saved = captureSaved();
        assertEquals(SyncStatus.SYNCED, saved.getSyncStatus());
        assertEquals("3Hadlj", saved.getOpenObserveId());
        assertTrue(saved.isLinked());
    }

    /** Severity is inferred from the name, which is where these rules encode it. */
    @Test
    void severityIsInferredFromTheRuleName() {
        givenRemoteRule("k8s-node-cpu-critical", "a");
        importer.importAll("tester");
        assertEquals("critical", captureSaved().getSeverity());
    }

    // ── re-running it ─────────────────────────────────────────────────────────

    /**
     * Idempotent, and it never overwrites a local definition.
     *
     * <p>A rule the database already knows is ADOPTED — it gains the OpenObserve id —
     * and its stored condition is left alone. Replacing it with the remote copy would
     * discard an edit somebody made here and had every reason to trust.</p>
     */
    @Test
    void anExistingRuleIsAdoptedAndNotOverwritten() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setUuid(UUID.randomUUID());
        existing.setOrg(ORG);
        existing.setName("k8s-node-cpu-critical");
        existing.setQueryCondition("{\"sql\":\"LOCALLY EDITED\"}");
        when(rules.findByOrgAndNameAndDeletedAtIsNull(ORG, "k8s-node-cpu-critical"))
                .thenReturn(Optional.of(existing));
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        AlertRuleResponseDTO.SyncResult result = importer.importAll("tester");

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getUpdated());
        assertEquals("{\"sql\":\"LOCALLY EDITED\"}", existing.getQueryCondition(),
                "the local definition was overwritten by the remote copy");
        assertEquals("3Hadlj", existing.getOpenObserveId());
    }

    /** Nothing is written when an already-adopted rule is imported again. */
    @Test
    void aSecondRunOnAnAlreadyLinkedRuleWritesNothing() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setUuid(UUID.randomUUID());
        existing.setOrg(ORG);
        existing.setName("k8s-node-cpu-critical");
        existing.markSynced("3Hadlj");
        when(rules.findByOrgAndNameAndDeletedAtIsNull(ORG, "k8s-node-cpu-critical"))
                .thenReturn(Optional.of(existing));
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        AlertRuleResponseDTO.SyncResult result = importer.importAll("tester");

        assertEquals(1, result.getUpdated());
        verify(rules, never()).save(any());
    }

    // ── partial failure ───────────────────────────────────────────────────────

    /** One unreadable rule must not cost the other nineteen. */
    @Test
    void oneUnnamedRuleIsSkippedAndTheRestAreStored() {
        client.list.add(node("{\"alert_id\":\"x\",\"name\":\"\"}"));
        givenRemoteRule("k8s-node-cpu-critical", "3Hadlj");

        AlertRuleResponseDTO.SyncResult result = importer.importAll("tester");

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getSkipped());
    }

    /**
     * A rule whose full document cannot be read is still stored.
     *
     * <p>Losing the stream name is worth it: a row that records the rule exists, with
     * its condition from the listing, beats no row at all — which is the state that
     * left 73 alerts unattributed.</p>
     */
    @Test
    void aRuleWhoseDocumentIsUnreadableIsStillStored() {
        client.list.add(node("{\"alert_id\":\"missing\",\"name\":\"orphan-rule\","
                + "\"enabled\":true,\"trigger_condition\":{\"period\":1}}"));

        AlertRuleResponseDTO.SyncResult result = importer.importAll("tester");

        assertEquals(1, result.getCreated());
        AlertRuleEntity saved = captureSaved();
        assertNotNull(saved.getStreamType());
        assertNotNull(saved.getStreamName());
        assertNull(saved.getContextAttributes());
    }

    /** Serves the two endpoints the importer reads, and nothing else. */
    private static final class FakeClient implements com.trinity.observability.client.OpenObserveClientService {

        private final java.util.List<JsonNode> list = new java.util.ArrayList<>();
        private final Map<String, JsonNode> documents = new HashMap<>();

        @Override
        public JsonNode getJson(String path) {
            if (path.matches(".*/alerts\\?page_size=\\d+")) {
                var root = JSON.createObjectNode();
                var arr = root.putArray("list");
                list.forEach(arr::add);
                return root;
            }
            int slash = path.lastIndexOf('/');
            String id = slash < 0 ? "" : path.substring(slash + 1);
            JsonNode doc = documents.get(id);
            if (doc == null) {
                throw new IllegalStateException("no such alert: " + id);
            }
            return doc;
        }

        @Override
        public java.util.List<Map<String, Object>> search(String sql, long s, long e,
                int from, int size, String type) {
            return java.util.List.of();
        }

        @Override
        public JsonNode sendJson(String method, String path, JsonNode body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode deleteJson(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode ingest(String org, String stream, JsonNode records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<String> discoverServices() {
            return java.util.List.of();
        }

        @Override
        public java.util.List<String> listMetricStreams() {
            return java.util.List.of();
        }
    }
}
