package com.trinity.observability.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.entity.CatalogResourceEntity;
import com.trinity.observability.repository.CatalogProductRepository;
import com.trinity.observability.repository.CatalogResourceRepository;

/**
 * Last Seen and Last Checked survive a read, under the PostgreSQL registry.
 *
 * <h2>What these cover</h2>
 *
 * <p>The liveness refresher deliberately tells the registry "nothing durable
 * changed" — Last Seen is a cache recomputed from OpenObserve, and persisting it
 * grew the change log by a row per resource per TTL. The in-memory registry gave
 * the stamp back for free, because its mutator wrote onto the live object.
 * This one hands the mutator a DTO and, on an unchanged verdict, saved nothing at
 * all — so {@code lastCheckedMicros} read back null every time, the TTL was never
 * satisfiable, and listing the catalog re-queried every binding on every request.
 * With 59 resources across three streams that was the whole 6–14s the endpoint
 * cost, and it is what made step 1 of the alert wizard slow to open.</p>
 *
 * <p>{@link InMemoryProductCatalogRegistryTest} covers the other store, where this
 * always worked — which is exactly why the gap went unseen.</p>
 */
class JpaProductCatalogRegistryLivenessTest {

    private static final String ORG = "default";

    private final Map<UUID, CatalogResourceEntity> rows = new LinkedHashMap<>();

    private JpaProductCatalogRegistry registry;

    @BeforeEach
    void setUp() {
        rows.clear();

        CatalogResourceRepository resources = mock(CatalogResourceRepository.class);
        CatalogProductRepository products = mock(CatalogProductRepository.class);

        when(products.findAll()).thenReturn(List.of());

        when(resources.save(any(CatalogResourceEntity.class))).thenAnswer(call -> {
            CatalogResourceEntity row = call.getArgument(0);
            rows.put(row.getResourceUuid(), row);
            return row;
        });
        when(resources.findByResourceUuidAndDeletedAtIsNull(any(UUID.class))).thenAnswer(call -> {
            CatalogResourceEntity row = rows.get(call.<UUID>getArgument(0));
            return row == null || row.getDeletedAt() != null ? Optional.empty() : Optional.of(row);
        });
        when(resources.findByOrgAndDeletedAtIsNullOrderByRegisteredAtMicrosAsc(any()))
                .thenAnswer(call -> {
                    String org = call.getArgument(0);
                    List<CatalogResourceEntity> out = new ArrayList<>();
                    for (CatalogResourceEntity row : rows.values()) {
                        if (row.getDeletedAt() == null && org.equals(row.getOrg())) {
                            out.add(row);
                        }
                    }
                    return out;
                });

        registry = new JpaProductCatalogRegistry(resources, products);
    }

    private CatalogResource register(String name, long checkedMicros, Long seenMicros) {
        CatalogResource resource = new CatalogResource();
        resource.setId(UUID.randomUUID().toString());
        resource.setOrg(ORG);
        resource.setCategory(ResourceCategory.APPLICATIONS);
        resource.setResourceName(name);
        resource.setResolvedName(name);
        resource.setStatus(ResourceStatus.ENABLED);
        resource.setBindings(List.of(
                new TelemetryBinding("logs", "default", "service_name", name)));
        resource.setLastSeenMicros(seenMicros);
        resource.setLastCheckedMicros(checkedMicros);
        resource.setRegisteredAtMicros(checkedMicros);
        resource.setUpdatedAtMicros(checkedMicros);
        return registry.save(ORG, resource);
    }

    private CatalogResource onlyListed() {
        List<CatalogResource> all = registry.findAll(ORG);
        assertEquals(1, all.size());
        return all.get(0);
    }

    @Test
    void aRegistrationsCheckStampIsVisibleToTheNextList() {
        register("Svc", 1_000L, 900L);

        CatalogResource listed = onlyListed();

        assertEquals(1_000L, listed.getLastCheckedMicros(),
                "a resource checked at registration is inside the TTL, so the very next "
                        + "list must not re-query it");
        assertEquals(900L, listed.getLastSeenMicros());
    }

    @Test
    void aRefreshThatReportsNoDurableChangeStillRecordsThatItRan() {
        CatalogResource stored = register("Svc", 1_000L, 900L);

        // Exactly what refreshLiveness does: stamp the check, raise Last Seen, and
        // return false so the change log is not appended to.
        registry.update(ORG, stored.getId(), live -> {
            live.setLastCheckedMicros(5_000L);
            live.setLastSeenMicros(4_500L);
            return false;
        });

        CatalogResource listed = onlyListed();
        assertEquals(5_000L, listed.getLastCheckedMicros(),
                "without this the TTL can never be satisfied and every list re-queries");
        assertEquals(4_500L, listed.getLastSeenMicros());
    }

    @Test
    void lastSeenOnlyEverMovesForward() {
        CatalogResource stored = register("Svc", 1_000L, 4_000L);

        // A refresh that found nothing leaves Last Seen alone — "quiet for an hour"
        // and "never seen" are different states.
        registry.update(ORG, stored.getId(), live -> {
            live.setLastCheckedMicros(9_000L);
            return false;
        });

        CatalogResource listed = onlyListed();
        assertEquals(9_000L, listed.getLastCheckedMicros());
        assertEquals(4_000L, listed.getLastSeenMicros(), "a miss must not erase a known sighting");
    }

    @Test
    void aDurableChangeKeepsTheLivenessStampToo() {
        CatalogResource stored = register("Svc", 1_000L, 900L);
        registry.update(ORG, stored.getId(), live -> {
            live.setLastCheckedMicros(5_000L);
            return false;
        });

        // Disabling a resource is a real write; it must not cost the catalog its
        // cached liveness and send the next list back to OpenObserve.
        registry.update(ORG, stored.getId(), live -> {
            live.setStatus(ResourceStatus.DISABLED);
            return true;
        });

        CatalogResource listed = onlyListed();
        assertEquals(ResourceStatus.DISABLED, listed.getStatus());
        assertEquals(5_000L, listed.getLastCheckedMicros());
    }

    @Test
    void theStampIsDroppedWhenTheResourceIs() {
        CatalogResource stored = register("Svc", 1_000L, 900L);
        assertTrue(registry.deleteById(ORG, stored.getId()));
        assertTrue(registry.findAll(ORG).isEmpty());

        // Re-registering the same name is legal (the delete is soft, the unique
        // index partial). The new row must not inherit the old row's liveness.
        CatalogResource fresh = register("Svc", 2_000L, null);
        assertNotNull(fresh.getId());

        List<CatalogResource> all = registry.findAll(ORG);
        assertEquals(1, all.size());
        assertEquals(2_000L, all.get(0).getLastCheckedMicros());
        assertNull(all.get(0).getLastSeenMicros(), "a fresh registration starts with no sighting");
    }
}
