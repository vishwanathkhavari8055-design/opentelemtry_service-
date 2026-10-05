package com.trinity.observability.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InMemoryProductCatalogRegistry} — the semantics half of
 * the registry, exercised with a recording snapshot store so nothing touches
 * OpenObserve.
 */
class InMemoryProductCatalogRegistryTest {

    private static final String ORG = "default";

    private RecordingStore store;
    private InMemoryProductCatalogRegistry registry;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        registry = new InMemoryProductCatalogRegistry(store);
    }

    @Test
    void savedResourceIsFoundByIdAndListed() {
        CatalogResource saved = registry.save(ORG, resource("PostgreSQL", ResourceCategory.DATABASES));

        assertEquals(1, registry.findAll(ORG).size());
        assertTrue(registry.findById(ORG, saved.getId()).isPresent());
        assertEquals("PostgreSQL", registry.findById(ORG, saved.getId()).get().getResourceName());
    }

    @Test
    void duplicateLookupFoldsCaseWithinACategory() {
        registry.save(ORG, resource("PostgreSQL", ResourceCategory.DATABASES));

        assertTrue(registry.findByCategoryAndName(ORG, ResourceCategory.DATABASES, "postgresql").isPresent(),
                "an operator typing a different case means the same registration");
        assertTrue(registry.findByCategoryAndName(ORG, ResourceCategory.DATABASES, "  POSTGRESQL ").isPresent(),
                "surrounding whitespace is not a distinguishing feature");
    }

    @Test
    void theSameNameInAnotherCategoryIsNotADuplicate() {
        registry.save(ORG, resource("PostgreSQL", ResourceCategory.DATABASES));

        assertTrue(registry.findByCategoryAndName(ORG, ResourceCategory.TOOLS, "PostgreSQL").isEmpty(),
                "categories are separate namespaces");
    }

    @Test
    void organizationsAreSeparateInventories() {
        registry.save(ORG, resource("PostgreSQL", ResourceCategory.DATABASES));

        assertTrue(registry.findAll("DLH").isEmpty(),
                "a resource registered under one org must not appear under another");
    }

    @Test
    void returnedResourcesAreSnapshotsNotLiveState() {
        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));

        CatalogResource first = registry.findById(ORG, saved.getId()).orElseThrow();
        CatalogResource second = registry.findById(ORG, saved.getId()).orElseThrow();
        assertNotSame(first, second, "each read hands back its own copy");

        first.setStatus(ResourceStatus.DISABLED);
        assertEquals(ResourceStatus.ENABLED,
                registry.findById(ORG, saved.getId()).orElseThrow().getStatus(),
                "mutating a snapshot must not reach stored state");
    }

    @Test
    void updateAppliesUnderTheRegistryLockAndReportsTheResult() {
        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));

        Optional<CatalogResource> updated = registry.update(ORG, saved.getId(), live -> {
            live.setStatus(ResourceStatus.DISABLED);
            return true;
        });

        assertTrue(updated.isPresent());
        assertEquals(ResourceStatus.DISABLED, updated.get().getStatus());
        assertEquals(ResourceStatus.DISABLED,
                registry.findById(ORG, saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void updateOfAnUnknownIdIsEmptyRatherThanAnError() {
        assertTrue(registry.update(ORG, UUID.randomUUID().toString(), live -> true).isEmpty());
    }

    @Test
    void aMutatorThatChangesNothingIsNotPersisted() {
        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));
        int writesAfterSave = store.changes.size();

        registry.update(ORG, saved.getId(), live -> false);

        assertEquals(writesAfterSave, store.changes.size(),
                "a no-op update must not append to the change log");
    }

    @Test
    void deleteRemovesAndRecordsATombstone() {
        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));

        assertTrue(registry.deleteById(ORG, saved.getId()));
        assertTrue(registry.findAll(ORG).isEmpty());
        assertFalse(registry.deleteById(ORG, saved.getId()), "a second delete finds nothing");

        Change last = store.changes.get(store.changes.size() - 1);
        assertTrue(last.deleted, "removal is recorded as a tombstone, not an absence");
        assertEquals(saved.getId(), last.resource.getId());
    }

    @Test
    void theTombstoneIsStampedNewerThanTheChangeBeforeIt() {
        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));
        long versionAtSave = saved.getUpdatedAtMicros();

        registry.deleteById(ORG, saved.getId());

        Change tombstone = store.changes.get(store.changes.size() - 1);
        // Otherwise the tombstone ties with the row before it, and a store whose
        // ordering is by timestamp can apply them either way round — which
        // resurrects a deleted resource on the next restart.
        assertTrue(tombstone.resource.getUpdatedAtMicros() > versionAtSave,
                "tombstone version " + tombstone.resource.getUpdatedAtMicros()
                        + " must be strictly newer than " + versionAtSave);
    }

    @Test
    void aCacheOnlyUpdateMutatesMemoryButIsNotPersisted() {
        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));
        int writesAfterSave = store.changes.size();

        // What a Last Seen refresh does: change a cached field, return false.
        registry.update(ORG, saved.getId(), live -> {
            live.setLastSeenMicros(9_999L);
            return false;
        });

        assertEquals(9_999L, registry.findById(ORG, saved.getId()).orElseThrow().getLastSeenMicros(),
                "the in-memory update still happens — the mutator ran");
        assertEquals(writesAfterSave, store.changes.size(),
                "but a cache field must not append to the change log: this path runs on "
                        + "every catalog list past the TTL, once per resource");
    }

    @Test
    void registrationsAreRestoredFromTheChangeLogOnFirstRead() {
        CatalogResource persisted = resource("dlhbackendservice", ResourceCategory.DLH);
        persisted.setBindings(List.of(
                new TelemetryBinding("logs", "default", "service_name", "dlhbackendservice")));
        store.preloaded.add(persisted);

        List<CatalogResource> restored = registry.findAll(ORG);

        assertEquals(1, restored.size());
        assertEquals("dlhbackendservice", restored.get(0).getResourceName());
        assertEquals(1, restored.get(0).getBindings().size());
        assertEquals(1, store.loadCalls, "the change log is replayed once per org");

        registry.findAll(ORG);
        assertEquals(1, store.loadCalls, "and not again on subsequent reads");
    }

    @Test
    void aStoreThatFailsToLoadLeavesAnEmptyCatalogRatherThanFailing() {
        store.failOnLoad = true;

        assertTrue(registry.findAll(ORG).isEmpty(),
                "an unreadable change log degrades to 'nothing registered'");
    }

    @Test
    void aStoreThatFailsToWriteDoesNotFailTheRegistration() {
        store.failOnWrite = true;

        CatalogResource saved = registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));

        assertTrue(registry.findById(ORG, saved.getId()).isPresent(),
                "losing durability must not lose the registration");
    }

    @Test
    void listOrderIsRegistrationOrderSoPollingDoesNotReshuffle() {
        CatalogResource first = resource("aaa", ResourceCategory.TOOLS);
        first.setRegisteredAtMicros(1_000L);
        CatalogResource second = resource("bbb", ResourceCategory.TOOLS);
        second.setRegisteredAtMicros(2_000L);

        registry.save(ORG, second);
        registry.save(ORG, first);

        List<CatalogResource> listed = registry.findAll(ORG);
        assertEquals("aaa", listed.get(0).getResourceName());
        assertEquals("bbb", listed.get(1).getResourceName());
    }

    @Test
    void savingWithoutAnIdIsRejected() {
        CatalogResource missingId = resource("kafka1", ResourceCategory.TOOLS);
        missingId.setId(null);

        IllegalArgumentException expected = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> registry.save(ORG, missingId));
        assertTrue(expected.getMessage().contains("id"));
    }

    private static CatalogResource resource(String name, ResourceCategory category) {
        CatalogResource resource = new CatalogResource();
        resource.setId(UUID.randomUUID().toString());
        resource.setCategory(category);
        resource.setResourceName(name);
        resource.setStatus(ResourceStatus.ENABLED);
        resource.setRegisteredAtMicros(1_000L);
        resource.setUpdatedAtMicros(1_000L);
        return resource;
    }

    /** One appended change, as the store saw it. */
    private record Change(String org, CatalogResource resource, boolean deleted) {
    }

    /** Records appends, replays whatever the test preloaded, fails on demand. */
    private static final class RecordingStore implements CatalogSnapshotStore {

        private final List<Change> changes = new ArrayList<>();
        private final List<CatalogResource> preloaded = new ArrayList<>();
        private int loadCalls;
        private boolean failOnLoad;
        private boolean failOnWrite;

        @Override
        public List<CatalogResource> load(String org) {
            loadCalls++;
            if (failOnLoad) {
                throw new IllegalStateException("simulated store failure");
            }
            List<CatalogResource> copies = new ArrayList<>();
            for (CatalogResource resource : preloaded) {
                copies.add(resource.copy());
            }
            return copies;
        }

        @Override
        public void recordChange(String org, CatalogResource resource, boolean deleted) {
            if (failOnWrite) {
                throw new IllegalStateException("simulated write failure");
            }
            changes.add(new Change(org, resource.copy(), deleted));
        }

        @Override
        public String describe() {
            return "recording test store";
        }
    }

    /** Guards the assumption the whole suite rests on: the store is a collaborator. */
    @Test
    void theRegistryUsesTheInjectedStore() {
        assertSame(store.describe(), store.describe());
        registry.save(ORG, resource("kafka1", ResourceCategory.TOOLS));
        assertEquals(1, store.changes.size());
    }
}
