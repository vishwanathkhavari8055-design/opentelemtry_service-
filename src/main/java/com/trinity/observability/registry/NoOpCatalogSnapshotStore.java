package com.trinity.observability.registry;

import java.util.List;

import com.trinity.observability.dto.productcatalog.CatalogResource;

/**
 * Durability turned off: the registry is purely in-memory and registrations do
 * not survive a restart.
 *
 * <p>Selected by {@code product-catalog.persistence.mode=NONE}. Useful for tests
 * and for a deployment that seeds its whole catalog from
 * {@code product-catalog.seed[*]}, where a change log would be a second,
 * disagreeing source of truth.</p>
 */
public class NoOpCatalogSnapshotStore implements CatalogSnapshotStore {

    @Override
    public List<CatalogResource> load(String org) {
        return List.of();
    }

    @Override
    public void recordChange(String org, CatalogResource resource, boolean deleted) {
        // Intentionally empty — this store exists to discard changes.
    }

    @Override
    public String describe() {
        return "none (in-memory only; registrations are lost on restart)";
    }
}
