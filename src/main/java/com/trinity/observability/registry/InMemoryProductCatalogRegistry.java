package com.trinity.observability.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;

/**
 * The shipped registry: an in-memory map per organization, with an
 * {@link CatalogSnapshotStore} behind it so registrations survive a restart.
 *
 * <p>No database, no table, no migration — which is the requirement. The map is
 * the source of truth at runtime; the store is a change log that can rebuild it.
 * Every read is served from memory, so the per-request cost that
 * {@code CatalogTelemetryFilter} adds to Logs / Traces / Metrics is a hash
 * lookup, not I/O.</p>
 *
 * <h2>Locking</h2>
 *
 * <p>Reads are lock-free over a {@link ConcurrentHashMap}. Writes synchronise on
 * the per-org map, which is what makes {@link #update} atomic: enable/disable
 * from the API and a Last Seen refresh from the list endpoint routinely touch
 * the same resource at the same time, and a read-modify-write on the caller's
 * side would let one of them silently lose.</p>
 *
 * <h2>Lazy load, not eager</h2>
 *
 * <p>An org's change log is replayed the first time that org is read, not at
 * startup. Two reasons: the set of orgs is not known up front (they are created
 * in OpenObserve, see {@code OrgContext}), and a store that is slow or
 * unreachable must not be able to delay or fail application startup.</p>
 */
public class InMemoryProductCatalogRegistry implements ProductCatalogRegistry {

    private static final Logger log = LogManager.getLogger(InMemoryProductCatalogRegistry.class);

    /** org → (id → resource). Insertion-ordered inner map: registration order
     *  is the table's order, so polling does not reshuffle rows. */
    private final Map<String, Map<String, CatalogResource>> byOrg = new ConcurrentHashMap<>();

    /** Orgs whose change log has been replayed SUCCESSFULLY. A failed replay is
     *  deliberately absent, so it is retried rather than cached as "empty". */
    private final Map<String, Boolean> loaded = new ConcurrentHashMap<>();

    /** org → when a replay was last attempted, successful or not. */
    private final Map<String, Long> replayAttemptedAtMs = new ConcurrentHashMap<>();

    /** How long a failed replay waits before another read may retry it. */
    private static final long REPLAY_RETRY_BACKOFF_MS = 30_000L;

    private final CatalogSnapshotStore store;

    public InMemoryProductCatalogRegistry(CatalogSnapshotStore store) {
        this.store = store;
        if (log.isInfoEnabled()) {
            log.info("Product Catalog registry: in-memory, durability via {}", store.describe());
        }
    }

    @Override
    public List<CatalogResource> findAll(String org) {
        Map<String, CatalogResource> resources = orgMap(org);
        synchronized (resources) {
            List<CatalogResource> out = new ArrayList<>(resources.size());
            for (CatalogResource resource : resources.values()) {
                out.add(resource.copy());
            }
            // Stable secondary sort on name so two resources registered in the
            // same millisecond (config seeding) do not swap places between calls.
            out.sort(Comparator.comparingLong(CatalogResource::getRegisteredAtMicros)
                    .thenComparing(CatalogResource::getResourceName,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
            return out;
        }
    }

    @Override
    public Optional<CatalogResource> findById(String org, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Map<String, CatalogResource> resources = orgMap(org);
        synchronized (resources) {
            CatalogResource found = resources.get(id);
            return found == null ? Optional.empty() : Optional.of(found.copy());
        }
    }

    @Override
    public Optional<CatalogResource> findByCategoryAndName(String org, ResourceCategory category,
            String resourceName) {
        if (category == null || resourceName == null || resourceName.isBlank()) {
            return Optional.empty();
        }
        String needle = fold(resourceName);
        Map<String, CatalogResource> resources = orgMap(org);
        synchronized (resources) {
            for (CatalogResource resource : resources.values()) {
                if (resource.getCategory() == category && fold(resource.getResourceName()).equals(needle)) {
                    return Optional.of(resource.copy());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public CatalogResource save(String org, CatalogResource resource) {
        if (resource == null || resource.getId() == null || resource.getId().isBlank()) {
            throw new IllegalArgumentException("save: resource and its id are required");
        }
        CatalogResource stored = resource.copy();
        stored.setOrg(org);

        Map<String, CatalogResource> resources = orgMap(org);
        synchronized (resources) {
            resources.put(stored.getId(), stored);
        }
        recordQuietly(org, stored, false);
        return stored.copy();
    }

    @Override
    public boolean deleteById(String org, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        Map<String, CatalogResource> resources = orgMap(org);
        CatalogResource removed;
        synchronized (resources) {
            removed = resources.remove(id);
        }
        if (removed == null) {
            return false;
        }

        // Stamp the tombstone as the NEWEST version of this id before recording it.
        //
        // Without this the tombstone inherits `updatedAtMicros` from whatever
        // change came last, so it ties with that row on version — and since a
        // stream can hand back two rows with the same ingest timestamp in either
        // order, replay could apply the tombstone first and then re-add the
        // resource from the older row. A deleted resource silently coming back
        // after a restart is about the worst failure this store could have.
        removed.setUpdatedAtMicros(nowMicros());
        recordQuietly(org, removed, true);
        return true;
    }

    @Override
    public Optional<CatalogResource> update(String org, String id, Predicate<CatalogResource> mutator) {
        if (id == null || id.isBlank() || mutator == null) {
            return Optional.empty();
        }
        Map<String, CatalogResource> resources = orgMap(org);
        CatalogResource snapshot;
        boolean changed;
        synchronized (resources) {
            CatalogResource live = resources.get(id);
            if (live == null) {
                return Optional.empty();
            }
            changed = mutator.test(live);
            snapshot = live.copy();
        }
        if (changed) {
            recordQuietly(org, snapshot, false);
        }
        return Optional.of(snapshot);
    }

    /**
     * The per-org map, replaying the change log on first touch.
     *
     * <p>{@code computeIfAbsent} creates the map; the replay happens outside it,
     * because loading calls OpenObserve and doing I/O inside a
     * {@code ConcurrentHashMap} mapping function risks blocking every other org
     * on the same bin.</p>
     */
    private Map<String, CatalogResource> orgMap(String org) {
        String key = orgKey(org);
        Map<String, CatalogResource> resources =
                byOrg.computeIfAbsent(key, unused -> new LinkedHashMap<>());
        if (!loaded.containsKey(key) && claimReplayAttempt(key)) {
            replay(key);
        }
        return resources;
    }

    /**
     * Take the right to attempt a replay for this org, at most once per backoff.
     *
     * <p>The org is NOT marked loaded here — that only happens once a replay has
     * actually succeeded. Marking it up front is what made a failed replay
     * permanent: the org counted as loaded, the catalog stayed empty, and nothing
     * retried until the next restart. So a transient OpenObserve blip presented as
     * "every registration is gone", indistinguishable from a real empty catalog.</p>
     *
     * <p>The backoff is what keeps retrying cheap. Without it, every catalog read
     * of a failing org — and the UI polls — would issue another replay query.</p>
     */
    private boolean claimReplayAttempt(String org) {
        long now = System.currentTimeMillis();
        Long lastAttempt = replayAttemptedAtMs.get(org);
        if (lastAttempt != null && now - lastAttempt < REPLAY_RETRY_BACKOFF_MS) {
            return false;
        }
        // compute() so two concurrent readers cannot both claim the same attempt.
        Boolean[] claimed = { Boolean.FALSE };
        replayAttemptedAtMs.compute(org, (unused, previous) -> {
            if (previous != null && now - previous < REPLAY_RETRY_BACKOFF_MS) {
                return previous;
            }
            claimed[0] = Boolean.TRUE;
            return now;
        });
        return claimed[0];
    }

    private void replay(String org) {
        Map<String, CatalogResource> target = byOrg.get(org);
        if (target == null) {
            return;
        }
        List<CatalogResource> restored;
        try {
            restored = store.load(org);
        } catch (Exception e) {
            // Never fatal, and never cached: the org stays unloaded so the next
            // read past the backoff tries again. Keeping whatever is already in
            // memory is the point — reporting "nothing registered" because the
            // change log could not be READ is a far stronger claim than the
            // evidence supports, and with filter.mode=ENABLED_ONLY it is also the
            // difference between a scoped Logs screen and an unscoped one.
            log.error("Product Catalog replay failed for org '{}' — keeping the {} resource(s) "
                    + "already in memory and retrying in {}s: {}",
                    org, target.size(), REPLAY_RETRY_BACKOFF_MS / 1000L, e.getMessage(), e);
            return;
        }
        loaded.put(org, Boolean.TRUE);
        if (restored.isEmpty()) {
            return;
        }
        synchronized (target) {
            for (CatalogResource resource : restored) {
                if (resource.getId() != null && !resource.getId().isBlank()) {
                    resource.setOrg(org);
                    // An entry already present was written by a concurrent
                    // registration that raced the replay; the live write wins.
                    target.putIfAbsent(resource.getId(), resource);
                }
            }
        }
        log.info("Product Catalog restored {} resource(s) for org '{}'", restored.size(), org);
    }

    /** Durability is best-effort — see {@link CatalogSnapshotStore}'s failure policy. */
    private void recordQuietly(String org, CatalogResource resource, boolean deleted) {
        try {
            store.recordChange(orgKey(org), resource, deleted);
        } catch (Exception e) {
            log.warn("Product Catalog change not persisted (org={}, id={}, deleted={}): {}",
                    org, resource.getId(), deleted, e.getMessage());
        }
    }

    private static long nowMicros() {
        java.time.Instant now = java.time.Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    /** A blank org is the configured default, matching {@code OpenObserveConfig.getOrg()}. */
    private static String orgKey(String org) {
        return (org == null || org.isBlank()) ? "" : org.trim();
    }

    private static String fold(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
