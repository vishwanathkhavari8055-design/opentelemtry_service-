package com.trinity.observability.registry;

import java.util.List;

import com.trinity.observability.dto.productcatalog.CatalogResource;

/**
 * Durability for the in-memory registry, expressed as an append-only change log.
 *
 * <p>Kept separate from {@link ProductCatalogRegistry} on purpose. The registry
 * owns semantics — duplicate rules, ordering, locking — and a store owns bytes.
 * Splitting them means the durable implementation does not have to re-implement
 * any of the semantics, and the semantics get tested without any I/O.</p>
 *
 * <h2>Why a change log rather than "write the whole file"</h2>
 *
 * <p>The shipped implementation appends to an OpenObserve stream, which is the
 * mechanism this service already uses for the alert feed. Streams are
 * append-only by nature: there is no update-in-place and no delete-by-key. So a
 * change is recorded as an event and {@link #load(String)} replays them with
 * last-write-wins per id, tombstones included. That is also why the store is
 * told about each individual change instead of being handed a full snapshot —
 * re-appending every resource on every toggle would grow the stream by the size
 * of the catalog per click.</p>
 *
 * <h2>Failure policy</h2>
 *
 * <p>A store must NOT throw out of {@link #recordChange}. Losing durability is
 * bad; failing an operator's registration because a background write failed is
 * worse, and the registry is still correct in memory. Implementations log and
 * return.</p>
 */
public interface CatalogSnapshotStore {

    /**
     * Rebuild an org's registrations from the change log.
     *
     * <p>Called once per org, lazily, the first time that org's catalog is
     * read — not at startup, so a slow or unreachable store cannot delay the
     * application from coming up.</p>
     *
     * @return the resources that survived replay; never null, empty on any failure
     */
    List<CatalogResource> load(String org);

    /**
     * Append one change.
     *
     * @param org      the organization the change belongs to
     * @param resource the resource as it now stands (for a delete, the resource
     *                 as it stood — its id is what matters)
     * @param deleted  true to record a tombstone
     */
    void recordChange(String org, CatalogResource resource, boolean deleted);

    /** Human-readable name of this store, for the startup log line. */
    String describe();
}
