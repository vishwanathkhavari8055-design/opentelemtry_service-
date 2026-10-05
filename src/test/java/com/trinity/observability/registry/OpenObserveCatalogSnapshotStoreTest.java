package com.trinity.observability.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay semantics of {@link OpenObserveCatalogSnapshotStore}.
 *
 * <p>The tests here are about ORDER INDEPENDENCE, because that is the property a
 * stream cannot guarantee. OpenObserve returns rows ordered by {@code _timestamp}
 * and two rows can share one — so any fold whose outcome depends on which of two
 * equal-timestamp rows came first will, sooner or later, resurrect a resource an
 * operator deleted. That was a live bug: a tombstone inherited its version from
 * the change before it, so it tied, and the tie was broken by arrival order.</p>
 */
class OpenObserveCatalogSnapshotStoreTest {

    private static final String ORG = "default";
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    private FakeClient client;
    private OpenObserveCatalogSnapshotStore store;

    @BeforeEach
    void setUp() {
        client = new FakeClient();
        ProductCatalogProperties properties = new ProductCatalogProperties();
        store = new OpenObserveCatalogSnapshotStore(client, properties);
    }

    @Test
    void aRegisteredResourceIsRestored() {
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 1_000L));

        List<CatalogResource> restored = store.load(ORG);

        assertEquals(1, restored.size());
        assertEquals("SSOservice", restored.get(0).getResourceName());
        assertEquals(ResourceStatus.ENABLED, restored.get(0).getStatus());
    }

    /**
     * The failure that silently emptied the catalog.
     *
     * <p>OpenObserve answers a window it cannot scan with HTTP 200, {@code hits:
     * []} and {@code is_partial} — so a replay that reads only the rows would
     * conclude nothing was ever registered and hand back an empty catalog, which
     * the registry then caches. Every registration disappears, with no error
     * anywhere. Raising is the only honest answer: "I could not read the change
     * log" is not "the change log is empty".</p>
     */
    @Test
    void aPartialReadIsNotTreatedAsAnEmptyCatalog() {
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 1_000L));
        client.partialSearch = true;

        assertThrows(IllegalStateException.class, () -> store.load(ORG));
    }

    @Test
    void theLatestStatusWins() {
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 1_000L));
        client.rows.add(row(ID, "SSOservice", "DISABLED", false, 2_000L));

        assertEquals(ResourceStatus.DISABLED, store.load(ORG).get(0).getStatus());
    }

    @Test
    void anOlderRowArrivingLastDoesNotWin() {
        // Defends the version comparison itself: arrival order alone would apply
        // the ENABLED row and undo the disable.
        client.rows.add(row(ID, "SSOservice", "DISABLED", false, 2_000L));
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 1_000L));

        assertEquals(ResourceStatus.DISABLED, store.load(ORG).get(0).getStatus());
    }

    @Test
    void aTombstoneRemovesTheResource() {
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 1_000L));
        client.rows.add(row(ID, "SSOservice", "ENABLED", true, 2_000L));

        assertTrue(store.load(ORG).isEmpty());
    }

    @Test
    void aTombstoneOfEqualVersionStillWinsWhateverTheRowOrder() {
        // THE REGRESSION. A tombstone used to inherit its version from the change
        // before it, so these two rows tie — and a stream may return them in
        // either order. Both orders must delete.
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 2_000L));
        client.rows.add(row(ID, "SSOservice", "ENABLED", true, 2_000L));
        assertTrue(store.load(ORG).isEmpty(), "tombstone last must delete");

        client.rows.clear();
        client.rows.add(row(ID, "SSOservice", "ENABLED", true, 2_000L));
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 2_000L));
        assertTrue(store.load(ORG).isEmpty(),
                "tombstone FIRST must also delete — a deleted resource must not come "
                        + "back because the stream returned two equal timestamps the other way round");
    }

    @Test
    void aGenuineReRegistrationAfterDeletionIsRestored() {
        // The flip side: a strictly NEWER row for a tombstoned id is a real
        // re-registration, and must not be swallowed by the tombstone.
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 1_000L));
        client.rows.add(row(ID, "SSOservice", "ENABLED", true, 2_000L));
        client.rows.add(row(ID, "SSOservice", "ENABLED", false, 3_000L));

        List<CatalogResource> restored = store.load(ORG);
        assertEquals(1, restored.size());
        assertEquals(ResourceStatus.ENABLED, restored.get(0).getStatus());
    }

    @Test
    void bindingsAndSignalsSurviveTheRoundTrip() {
        CatalogResource original = new CatalogResource();
        original.setId(ID);
        original.setOrg(ORG);
        original.setCategory(ResourceCategory.APPLICATIONS);
        original.setResourceName("SSOservice");
        original.setResolvedName("SSOservice");
        original.setStatus(ResourceStatus.ENABLED);
        original.setBindings(List.of(
                new com.trinity.observability.dto.productcatalog.TelemetryBinding(
                        "logs", "default", "service_name", "SSOservice"),
                new com.trinity.observability.dto.productcatalog.TelemetryBinding(
                        "traces", "default", "service_name", "SSOservice")));
        original.setSignals(new java.util.LinkedHashSet<>(List.of("logs", "traces")));
        original.setLastSeenMicros(1_500L);
        original.setRegisteredAtMicros(1_000L);
        original.setUpdatedAtMicros(1_000L);

        // Write it, then feed exactly what was written back through load().
        store.recordChange(ORG, original, false);
        assertEquals(1, client.ingested.size());
        client.rows.add(toRow(client.ingested.get(0)));

        CatalogResource restored = store.load(ORG).get(0);
        assertEquals("SSOservice", restored.getResolvedName());
        assertEquals(2, restored.getBindings().size());
        assertEquals("service_name", restored.getBindings().get(0).column());
        assertEquals(List.of("logs", "traces"), new ArrayList<>(restored.getSignals()));
        assertEquals(1_500L, restored.getLastSeenMicros());
        assertEquals(ResourceCategory.APPLICATIONS, restored.getCategory());
    }

    @Test
    void everyPersistedFieldIsAStringSoTheStreamSchemaCannotConflict() {
        CatalogResource resource = new CatalogResource();
        resource.setId(ID);
        resource.setCategory(ResourceCategory.TOOLS);
        resource.setResourceName("kafka1");
        resource.setStatus(ResourceStatus.ENABLED);
        // lastSeenMicros left null — the case that would otherwise write a number
        // for one resource and null for the next, which OpenObserve rejects as a
        // schema conflict INSIDE a 200 response.
        store.recordChange(ORG, resource, false);

        JsonNode ingested = client.ingested.get(0);
        ingested.fieldNames().forEachRemaining(field ->
                assertTrue(ingested.get(field).isTextual(),
                        "field '" + field + "' must be written as a string, was "
                                + ingested.get(field).getNodeType()));
    }

    @Test
    void anUnreadableStreamYieldsAnEmptyCatalogRatherThanThrowing() {
        client.failSearch = true;

        assertTrue(store.load(ORG).isEmpty());
    }

    @Test
    void aFailedWriteDoesNotThrow() {
        client.failIngest = true;
        CatalogResource resource = new CatalogResource();
        resource.setId(ID);
        resource.setCategory(ResourceCategory.TOOLS);
        resource.setResourceName("kafka1");

        // Contract: losing durability must never fail the operator's action.
        assertDoesNotThrow(() -> store.recordChange(ORG, resource, false));
    }

    @Test
    void aRowWithAnUnknownCategoryIsDroppedNotGuessed() {
        Map<String, Object> bad = row(ID, "SSOservice", "ENABLED", false, 1_000L);
        bad.put("category", "WAREHOUSES");
        client.rows.add(bad);

        assertTrue(store.load(ORG).isEmpty(),
                "filing it under a guess would attach its filter to the wrong taxonomy");
    }

    @Test
    void describeNamesTheStreamSoStartupLogsAreActionable() {
        assertNotNull(store.describe());
        assertTrue(store.describe().contains("product_catalog"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> row(String id, String name, String status,
            boolean deleted, long version) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("catalog_id", id);
        row.put("catalog_org", ORG);
        row.put("category", ResourceCategory.APPLICATIONS.name());
        row.put("resource_name", name);
        row.put("resolved_name", name);
        row.put("status", status);
        row.put("source", "MANUAL");
        row.put("bindings", "");
        row.put("signals", "");
        row.put("last_seen_micros", "");
        row.put("registered_at_micros", "1000");
        row.put("updated_at_micros", Long.toString(version));
        row.put("deleted", Boolean.toString(deleted));
        return row;
    }

    /** An ingested record, as a search hit would come back. */
    private static Map<String, Object> toRow(JsonNode ingested) {
        Map<String, Object> row = new LinkedHashMap<>();
        ingested.fieldNames().forEachRemaining(f -> row.put(f, ingested.get(f).asText()));
        return row;
    }

    /** Serves canned rows, records ingests, fails on demand. */
    private static final class FakeClient implements OpenObserveClientService {

        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final List<JsonNode> ingested = new ArrayList<>();
        private boolean failSearch;
        private boolean failIngest;
        /** Simulates OpenObserve's 200-with-is_partial answer to an unservable window. */
        private boolean partialSearch;

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            return searchDetailed(sql, startMicros, endMicros, from, size, streamType).hits();
        }

        @Override
        public SearchOutcome searchDetailed(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            if (failSearch) {
                throw new IllegalStateException("simulated search failure");
            }
            if (partialSearch) {
                // The real hazard: partial results come back EMPTY, so they are
                // indistinguishable from "nothing registered" unless the flag is read.
                return new SearchOutcome(List.of(), true,
                        "{\"code\":20006,\"message\":\"Search parquet file not found\"}");
            }
            return new SearchOutcome(new ArrayList<>(rows), false, null);
        }

        @Override
        public JsonNode ingest(String org, String streamName, JsonNode records) {
            if (failIngest) {
                throw new IllegalStateException("simulated ingest failure");
            }
            records.forEach(ingested::add);
            return records;
        }

        @Override
        public JsonNode getJson(String path) {
            throw new UnsupportedOperationException();
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
        public List<String> discoverServices() {
            return List.of();
        }

        @Override
        public List<String> listMetricStreams() {
            return List.of();
        }
    }
}
