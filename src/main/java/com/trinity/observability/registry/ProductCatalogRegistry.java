package com.trinity.observability.registry;

import java.util.List;
import java.util.Optional;

import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;

/**
 * Where the Product Catalog's registrations live.
 *
 * <p>THIS INTERFACE IS THE SEAM. Everything above it — the service, the
 * controller, the eight REST routes and the UI — is written against these six
 * methods and knows nothing about how they are satisfied. The shipped
 * implementation is {@link InMemoryProductCatalogRegistry} with an
 * {@link CatalogSnapshotStore} behind it for durability; introducing a
 * PostgreSQL-, Redis- or file-backed registry later means adding one class that
 * implements this interface and letting
 * {@code ProductCatalogConfig}'s {@code @ConditionalOnMissingBean} step aside.
 * No API contract and no UI code changes.</p>
 *
 * <h2>Organization scoping</h2>
 *
 * <p>Every method takes the org explicitly rather than reading
 * {@code OrgContext}. Two reasons: the liveness refresher and the config seeder
 * run outside a request thread, where the ThreadLocal is unset; and an
 * implementation that silently defaulted a missing org would file one tenant's
 * resources under another. On this instance {@code default} and {@code DLH} are
 * genuinely different inventories.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Implementations must be safe for concurrent use — the catalog is read by
 * every Logs / Traces / Metrics request and written by the API. Returned
 * {@link CatalogResource} instances must be snapshots the caller may keep or
 * mutate without affecting stored state.</p>
 */
public interface ProductCatalogRegistry {

    /**
     * Every resource registered for an org, in a stable order (registration
     * time), so the UI's table does not reshuffle between polls.
     *
     * @return snapshots; never null, empty when nothing is registered
     */
    List<CatalogResource> findAll(String org);

    /** One resource by its registry id. */
    Optional<CatalogResource> findById(String org, String id);

    /**
     * Duplicate check. Names are compared case-insensitively WITHIN a category
     * — "postgresql" and "PostgreSQL" are the same registration to an operator,
     * even though telemetry filtering itself stays case-sensitive (see
     * {@code TelemetryBinding}).
     */
    Optional<CatalogResource> findByCategoryAndName(String org, ResourceCategory category, String resourceName);

    /**
     * Insert or replace by {@link CatalogResource#getId()}.
     *
     * @return the stored snapshot
     */
    CatalogResource save(String org, CatalogResource resource);

    /**
     * Remove a registration.
     *
     * @return true when something was removed, false when the id was unknown
     */
    boolean deleteById(String org, String id);

    /**
     * Apply an in-place update under the registry's own lock, so a concurrent
     * enable and liveness refresh cannot lose one of the two writes.
     *
     * <p>The mutator receives the LIVE instance and returns true when it changed
     * something worth persisting. Nothing else may retain that reference.</p>
     *
     * @return the resulting snapshot, or empty when the id was unknown
     */
    Optional<CatalogResource> update(String org, String id, java.util.function.Predicate<CatalogResource> mutator);
}
