package com.trinity.observability.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.entity.CatalogProductEntity;
import com.trinity.observability.entity.CatalogResourceEntity;
import com.trinity.observability.repository.CatalogProductRepository;
import com.trinity.observability.repository.CatalogResourceRepository;

/**
 * The registry, backed by PostgreSQL.
 *
 * <h2>Why this replaced the in-memory store</h2>
 *
 * <p>The catalog used to live in a map, made durable by an append-only OpenObserve
 * stream. The argument was "no database, no migration", and it held right up until
 * the OpenObserve address changed — at which point the catalog emptied itself,
 * because the change log lived inside the instance that had gone away. PostgreSQL
 * was already the source of truth for alert rules and alerts; the catalog being
 * stored elsewhere was precisely why it was the one thing that did not survive.</p>
 *
 * <p>{@link InMemoryProductCatalogRegistry} is untouched and one property away
 * ({@code product-catalog.store=MEMORY}), so this is additive.</p>
 *
 * <h2>Reads are not cached here</h2>
 *
 * <p>The in-memory registry served every Logs/Traces/Metrics request from a map.
 * This one goes to PostgreSQL, which is a real change in cost — so the caching
 * moved up a layer to {@code CatalogTelemetryFilter}, where it was already
 * per-request, rather than being duplicated here. A local cache would have to be
 * invalidated across instances, and getting that wrong means a disabled resource
 * still returning telemetry on one node.</p>
 *
 * <h2>Encoding is shared with the stream store</h2>
 *
 * <p>Signals comma-separated, bindings one {@code signal|stream|column|value} per
 * line — identical to {@code OpenObserveCatalogSnapshotStore}. That keeps the two
 * stores interchangeable: switching is a property change, not a data migration.</p>
 */
public class JpaProductCatalogRegistry implements ProductCatalogRegistry {

    private static final Logger log = LogManager.getLogger(JpaProductCatalogRegistry.class);

    private final CatalogResourceRepository resources;
    private final CatalogProductRepository products;

    /**
     * Last Seen and Last Checked, held in memory rather than in a column.
     *
     * <p>Both are a CACHE recomputed from OpenObserve, and the liveness refresher
     * deliberately reports "nothing durable changed" for them so they never reach
     * the change log — see the note in {@code ProductCatalogServiceImpl}. The
     * in-memory registry got that for free: its mutator wrote straight onto the
     * live object, so the stamp survived the call. Here the mutator is handed a
     * DTO and an unchanged verdict means nothing is saved at all, so without this
     * overlay {@code lastCheckedMicros} came back null on EVERY read — the TTL
     * could never be satisfied, and listing the catalog re-queried every binding
     * on every single request. That is the whole cost of the list endpoint.</p>
     *
     * <p>Process-local on purpose. Losing it on restart costs one refresh, which
     * is exactly the trade the change-log note already accepted, and two instances
     * refreshing on their own timers is correct rather than merely tolerable.</p>
     */
    private final ConcurrentHashMap<UUID, Liveness> liveness = new ConcurrentHashMap<>();

    /** The two cached timestamps, or null for "never established". */
    private record Liveness(Long lastCheckedMicros, Long lastSeenMicros) {
    }

    public JpaProductCatalogRegistry(CatalogResourceRepository resources,
            CatalogProductRepository products) {
        this.resources = resources;
        this.products = products;
        log.info("Product Catalog registry: PostgreSQL (catalog_resources)");
    }

    // ── reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CatalogResource> findAll(String org) {
        List<CatalogResourceEntity> rows =
                resources.findByOrgAndDeletedAtIsNullOrderByRegisteredAtMicrosAsc(orgKey(org));
        // One lookup for every product label on the page rather than one per row:
        // the table is read on a poll, and a query per row would turn a 40-row
        // catalog into 41 queries every refresh.
        Map<String, String> labels = productLabels();
        List<CatalogResource> out = new ArrayList<>(rows.size());
        for (CatalogResourceEntity row : rows) {
            out.add(toDto(row, labels));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CatalogResource> findById(String org, String id) {
        UUID uuid = parseUuid(id);
        if (uuid == null) {
            return Optional.empty();
        }
        return resources.findByResourceUuidAndDeletedAtIsNull(uuid)
                // Scoped to the org even though the uuid is globally unique: an id
                // from another tenant must read as "not found", not as somebody
                // else's resource.
                .filter(row -> orgKey(org).equals(row.getOrg()))
                .map(row -> toDto(row, productLabels()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CatalogResource> findByCategoryAndName(String org, ResourceCategory category,
            String resourceName) {
        if (category == null || resourceName == null || resourceName.isBlank()) {
            return Optional.empty();
        }
        // Product-agnostic by contract — see the interface. The first live match in
        // registration order is returned, which is what a duplicate check wants.
        List<CatalogResourceEntity> found =
                resources.findLiveByName(orgKey(org), category.name(), resourceName.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDto(found.get(0), productLabels()));
    }

    /**
     * The same lookup, narrowed to one product.
     *
     * <p>Beyond the interface because the interface predates products. The generic
     * {@link #findByCategoryAndName} has to stay product-agnostic for the callers
     * that know nothing about products, but registration needs the narrow answer:
     * the same microservice name under two different products is two legitimate
     * registrations, and rejecting the second as a duplicate would make the
     * product tier unusable.</p>
     */
    @Transactional(readOnly = true)
    public Optional<CatalogResource> findByCategoryProductAndName(String org,
            ResourceCategory category, String productCode, String resourceName) {
        if (category == null || resourceName == null || resourceName.isBlank()) {
            return Optional.empty();
        }
        return resources.findLive(orgKey(org), category.name(),
                        blankToNull(productCode), resourceName.trim())
                .map(row -> toDto(row, productLabels()));
    }

    // ── writes ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CatalogResource save(String org, CatalogResource resource) {
        String key = orgKey(org);
        CatalogResourceEntity row = null;

        UUID uuid = parseUuid(resource.getId());
        if (uuid != null) {
            row = resources.findByResourceUuidAndDeletedAtIsNull(uuid).orElse(null);
        }
        if (row == null) {
            row = new CatalogResourceEntity();
            row.setResourceUuid(uuid == null ? UUID.randomUUID() : uuid);
            row.setOrg(key);
            row.setRegisteredAtMicros(resource.getRegisteredAtMicros() > 0
                    ? resource.getRegisteredAtMicros() : nowMicros());
        }

        apply(row, resource, key);
        CatalogResourceEntity saved = resources.save(row);
        rememberLiveness(saved.getResourceUuid(), resource);
        return toDto(saved, productLabels());
    }

    @Override
    @Transactional
    public boolean deleteById(String org, String id) {
        UUID uuid = parseUuid(id);
        if (uuid == null) {
            return false;
        }
        Optional<CatalogResourceEntity> found = resources.findByResourceUuidAndDeletedAtIsNull(uuid)
                .filter(row -> orgKey(org).equals(row.getOrg()));
        if (found.isEmpty()) {
            return false;
        }
        // Soft delete, so the name can be registered again while the fact that it
        // once was is not lost. The partial unique index is what makes reuse legal.
        CatalogResourceEntity row = found.get();
        row.setDeletedAt(Instant.now());
        row.setUpdatedAtMicros(nowMicros());
        resources.save(row);
        liveness.remove(row.getResourceUuid());
        return true;
    }

    /**
     * Read-modify-write inside one transaction.
     *
     * <p>{@code REQUIRES_NEW} is deliberate. The in-memory registry synchronised on
     * its per-org map so an enable and a liveness refresh could not lose one of the
     * two writes; here the equivalent guarantee is the row lock this transaction
     * takes, and it has to commit on its own rather than joining whatever read-only
     * transaction the caller happens to be in — a liveness refresh runs inside the
     * list endpoint's read, and a nested write there would either fail or be
     * silently discarded.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CatalogResource> update(String org, String id, Predicate<CatalogResource> mutator) {
        UUID uuid = parseUuid(id);
        if (uuid == null) {
            return Optional.empty();
        }
        Optional<CatalogResourceEntity> found = resources.findByResourceUuidAndDeletedAtIsNull(uuid)
                .filter(row -> orgKey(org).equals(row.getOrg()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CatalogResourceEntity row = found.get();
        Map<String, String> labels = productLabels();

        // The mutator is handed a DTO rather than the entity: it is caller code, and
        // giving it a managed entity would let it write columns the registry never
        // agreed to expose.
        CatalogResource working = toDto(row, labels);
        boolean changed = mutator.test(working);
        if (changed) {
            apply(row, working, row.getOrg());
            row = resources.save(row);
        }
        // BEFORE the final mapping, and regardless of `changed`: a liveness refresh
        // reports false by design, and its whole purpose is to record that this
        // resource was just checked. Skipping it here is what made the TTL a no-op.
        rememberLiveness(row.getResourceUuid(), working);
        return Optional.of(toDto(row, labels));
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private void apply(CatalogResourceEntity row, CatalogResource dto, String org) {
        row.setOrg(org);
        row.setCategory(dto.getCategory() == null ? null : dto.getCategory().name());
        row.setProductCode(blankToNull(dto.getProductCode()));
        row.setResourceName(dto.getResourceName());
        row.setResolvedName(dto.getResolvedName());
        row.setVersion(blankToNull(dto.getVersion()));
        row.setStatus(dto.getStatus() == null ? ResourceStatus.ENABLED.name() : dto.getStatus().name());
        row.setSource(dto.getSource() == null
                ? CatalogResource.Source.MANUAL.name() : dto.getSource().name());
        row.setSignals(String.join(",", dto.getSignals()));
        row.setBindings(encodeBindings(dto.getBindings()));
        row.setLastSeenMicros(dto.getLastSeenMicros());
        if (dto.getRegisteredAtMicros() > 0) {
            row.setRegisteredAtMicros(dto.getRegisteredAtMicros());
        }
        row.setUpdatedAtMicros(dto.getUpdatedAtMicros() > 0 ? dto.getUpdatedAtMicros() : nowMicros());
    }

    private CatalogResource toDto(CatalogResourceEntity row, Map<String, String> labels) {
        CatalogResource dto = new CatalogResource();
        dto.setId(row.getResourceUuid().toString());
        dto.setOrg(row.getOrg());
        dto.setCategory(categoryOf(row.getCategory()));
        dto.setProductCode(row.getProductCode());
        dto.setProductLabel(labelFor(row.getCategory(), row.getProductCode(), labels));
        dto.setResourceName(row.getResourceName());
        dto.setResolvedName(row.getResolvedName());
        dto.setVersion(row.getVersion());
        dto.setStatus(statusOf(row.getStatus()));
        dto.setSource(sourceOf(row.getSource()));
        dto.setSignals(decodeSignals(row.getSignals()));
        dto.setBindings(decodeBindings(row.getBindings()));
        dto.setLastSeenMicros(row.getLastSeenMicros());
        dto.setRegisteredAtMicros(row.getRegisteredAtMicros());
        dto.setUpdatedAtMicros(row.getUpdatedAtMicros());
        applyLiveness(row.getResourceUuid(), dto);
        return dto;
    }

    /**
     * Lay the in-memory liveness cache over what the row holds.
     *
     * <p>Last Seen only ever moves FORWARD. The stored value is the one written at
     * registration; a refresh that found nothing must leave it alone, because
     * "quiet for an hour" and "never seen" are different states and the column
     * cannot tell them apart once overwritten.</p>
     */
    private void applyLiveness(UUID uuid, CatalogResource dto) {
        Liveness cached = liveness.get(uuid);
        if (cached == null) {
            return;
        }
        dto.setLastCheckedMicros(cached.lastCheckedMicros());
        Long seen = cached.lastSeenMicros();
        if (seen != null && (dto.getLastSeenMicros() == null || seen > dto.getLastSeenMicros())) {
            dto.setLastSeenMicros(seen);
        }
    }

    /** Record what a write established about this resource's liveness. */
    private void rememberLiveness(UUID uuid, CatalogResource source) {
        if (uuid == null || source == null) {
            return;
        }
        Long checked = source.getLastCheckedMicros();
        Long seen = source.getLastSeenMicros();
        if (checked == null && seen == null) {
            return;
        }
        liveness.merge(uuid, new Liveness(checked, seen), (previous, fresh) -> new Liveness(
                newest(previous.lastCheckedMicros(), fresh.lastCheckedMicros()),
                newest(previous.lastSeenMicros(), fresh.lastSeenMicros())));
    }

    private static Long newest(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    /** category|code → label, for every product, active or not. */
    private Map<String, String> productLabels() {
        Map<String, String> out = new HashMap<>();
        for (CatalogProductEntity p : products.findAll()) {
            out.put(p.getCategory() + "|" + p.getCode(), p.getLabel());
        }
        return out;
    }

    /**
     * The product's label, falling back to its code.
     *
     * <p>A code that no longer exists in the product table still renders as
     * something an operator can act on. Showing nothing would make the resource
     * look product-less, which is a different and wrong statement.</p>
     */
    private static String labelFor(String category, String code, Map<String, String> labels) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return labels.getOrDefault(category + "|" + code, code);
    }

    private static ResourceCategory categoryOf(String raw) {
        try {
            return ResourceCategory.fromCode(raw);
        } catch (RuntimeException e) {
            // A category retired from the enum. Logged, not thrown: one unreadable
            // row must not make the whole catalog unlistable.
            log.warn("Catalog row has unknown category '{}' — skipping its category", raw);
            return null;
        }
    }

    private static ResourceStatus statusOf(String raw) {
        try {
            ResourceStatus s = ResourceStatus.fromCodeOrNull(raw);
            return s == null ? ResourceStatus.ENABLED : s;
        } catch (RuntimeException e) {
            return ResourceStatus.ENABLED;
        }
    }

    private static CatalogResource.Source sourceOf(String raw) {
        if (raw != null) {
            for (CatalogResource.Source candidate : CatalogResource.Source.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return CatalogResource.Source.MANUAL;
    }

    private static String encodeBindings(List<TelemetryBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (TelemetryBinding binding : bindings) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(binding.encode());
        }
        return out.toString();
    }

    private static List<TelemetryBinding> decodeBindings(String raw) {
        List<TelemetryBinding> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            TelemetryBinding binding = TelemetryBinding.decode(line);
            if (binding != null) {
                out.add(binding);
            }
        }
        return out;
    }

    private static Set<String> decodeSignals(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            // An id that is not a UUID cannot identify a row; "not found" is the
            // honest answer, and a 400 here would turn a stale bookmark into an error.
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String orgKey(String org) {
        return (org == null || org.isBlank()) ? "default" : org.trim().toLowerCase(Locale.ROOT);
    }

    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
