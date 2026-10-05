package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ProductCatalogRequestDTO;
import com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.exception.ResourceConflictException;
import com.trinity.observability.exception.ResourceNotFoundException;
import com.trinity.observability.exception.TelemetryNotFoundException;
import com.trinity.observability.registry.JpaProductCatalogRegistry;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.CatalogProductService;
import com.trinity.observability.service.ProductCatalogService;
import com.trinity.observability.service.TelemetryDiscoveryService;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.TimeFormatter;

import jakarta.annotation.PreDestroy;

/**
 * The Product Catalog's behaviour: validate, register, toggle, remove, and
 * present.
 *
 * <p>Holds no state. The registry owns the resources, {@link
 * TelemetryDiscoveryService} owns the OpenObserve lookups, and this class owns
 * the rules that connect them — which is what makes it possible to change the
 * storage without changing anything a caller can see.</p>
 */
@Service
public class ProductCatalogServiceImpl implements ProductCatalogService {

    private static final Logger log = LogManager.getLogger(ProductCatalogServiceImpl.class);

    /**
     * The exact wording the requirement specifies for a failed validation.
     *
     * <p>One constant, used by both {@code validate} (as a message the dialog
     * renders) and {@code register} (as an exception message), so the two can
     * never tell the operator different stories about the same condition.</p>
     */
    static final String NOT_FOUND_MESSAGE =
            "Resource not found in OpenObserve. Please ensure telemetry is being emitted "
            + "before registration.";

    /**
     * Full discovery passes attempted per list request for resources that have no
     * proven identifier yet. See {@code bindUnboundResources} — this one cannot be
     * batched, so it is rate-limited rather than run for the whole catalog.
     */
    private static final int MAX_DISCOVERY_PER_LIST = 5;

    private final ProductCatalogRegistry registry;
    private final TelemetryDiscoveryService discovery;
    private final OpenObserveConfig openObserveConfig;
    private final ProductCatalogProperties properties;
    private final CatalogProductService productService;

    /**
     * Runs a stale liveness refresh off the request thread.
     *
     * <p>One thread: the work is a handful of OpenObserve queries that already
     * fan out internally, and a second concurrent refresh of the same catalog
     * would only duplicate them. Daemon, so a query that never returns cannot
     * hold JVM shutdown open.</p>
     */
    private final ExecutorService livenessPool;

    /**
     * Orgs whose liveness has been refreshed at least once in this process.
     *
     * <p>The FIRST list for an org is answered synchronously, because until then
     * Last Seen is whatever was written at registration — possibly days old — and
     * returning that instantly would be fast and wrong. Every list after it is
     * answered from the cache while the refresh runs behind it.</p>
     */
    private final Set<String> warmedOrgs = ConcurrentHashMap.newKeySet();

    /** Orgs with a background refresh in flight, so polls do not stack them up. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    /**
     * org -> the warm-up refresh for it, running or finished.
     *
     * <p>The first list of an org still waits for a completed refresh (see
     * {@link #warmedOrgs}), but it now JOINS one instead of running its own. The
     * default org's warm-up starts at boot ({@link #prewarmLiveness}), so by the
     * time anyone opens Logs after a restart it has usually finished. Before, the
     * first catalog request after every restart paid the whole refresh itself —
     * measured at ~6 s for 62 resources — and the Logs sidebar showed
     * "Loading catalog…" for all of it.</p>
     */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> warmups = new ConcurrentHashMap<>();

    /** Whether to warm the default org's Last Seen at boot. */
    @Value("${product-catalog.validation.prewarm:true}")
    private boolean prewarmEnabled = true;

    public ProductCatalogServiceImpl(ProductCatalogRegistry registry,
            TelemetryDiscoveryService discovery,
            OpenObserveConfig openObserveConfig,
            ProductCatalogProperties properties,
            CatalogProductService productService) {
        this.registry = registry;
        this.discovery = discovery;
        this.openObserveConfig = openObserveConfig;
        this.properties = properties;
        this.productService = productService;

        AtomicInteger seq = new AtomicInteger();
        this.livenessPool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "catalog-liveness-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        livenessPool.shutdownNow();
    }

    /**
     * Resolve and enforce the product for a registration.
     *
     * <p>Required exactly where a product list EXISTS. That is read from the data
     * rather than from a list of category names in code, so adding the first product
     * to a category starts being enforced on the next request with nothing to keep
     * in step — and Tools, Databases, Platform and DLH stay registerable by bare
     * name instead of needing a synthetic product invented for them.</p>
     *
     * <p>A supplied product is rejected when it does not belong to the category.
     * Silently dropping it would file the resource directly under the category and
     * report success, so the operator would believe they had registered it under a
     * product and find it missing from that branch of the tree.</p>
     */
    private String resolveProduct(ResourceCategory category, String raw) {
        boolean hasProducts = productService.hasProducts(category);
        String supplied = (raw == null || raw.isBlank()) ? null : raw.trim();

        if (supplied == null) {
            if (hasProducts) {
                throw new IllegalArgumentException(category.getDisplayName()
                        + " is organised by product, so 'product' is required. Choose one of: "
                        + productService.codesFor(category));
            }
            return null;
        }
        if (!hasProducts) {
            throw new IllegalArgumentException(category.getDisplayName()
                    + " has no products, so 'product' must be omitted.");
        }
        return productService.resolveCode(category, supplied)
                .orElseThrow(() -> new IllegalArgumentException("'" + supplied
                        + "' is not a product of " + category.getDisplayName()
                        + ". Choose one of: " + productService.codesFor(category)));
    }

    /**
     * The existing live registration for this exact (category, product, name).
     *
     * <p>Uses the product-aware lookup when the registry has one. The generic
     * {@code findByCategoryAndName} on the interface is deliberately
     * product-agnostic — the seeder and the liveness refresher rely on that — so
     * using it here would report a clash between two products that should coexist.</p>
     */
    private Optional<CatalogResource> findExisting(String org, ResourceCategory category,
            String productCode, String resourceName) {
        if (registry instanceof JpaProductCatalogRegistry jpa) {
            return jpa.findByCategoryProductAndName(org, category, productCode, resourceName);
        }
        // In-memory store: no product dimension, so the pre-product behaviour stands.
        // Two products sharing a microservice name is then still a clash, which is a
        // limitation of that store rather than of the model — and it is the store a
        // deployment only chooses when it has no database.
        return registry.findByCategoryAndName(org, category, resourceName);
    }

    // ── Categories ───────────────────────────────────────────────────────────

    @Override
    public ProductCatalogResponseDTO.CategoryList listCategories() {
        List<CatalogResource> resources = safeFindAll();

        Map<ResourceCategory, int[]> counts = new LinkedHashMap<>();
        for (ResourceCategory category : ResourceCategory.values()) {
            counts.put(category, new int[2]);
        }
        for (CatalogResource resource : resources) {
            int[] tally = counts.get(resource.getCategory());
            if (tally == null) {
                continue;
            }
            tally[0]++;
            if (resource.isEnabled()) {
                tally[1]++;
            }
        }

        ProductCatalogResponseDTO.CategoryList out = new ProductCatalogResponseDTO.CategoryList();
        List<ProductCatalogResponseDTO.Category> items = new ArrayList<>();
        for (ResourceCategory category : ResourceCategory.values()) {
            ProductCatalogResponseDTO.Category dto = new ProductCatalogResponseDTO.Category();
            dto.setCode(category.getCode());
            dto.setLabel(category.getDisplayName());
            dto.setDescription(category.getDescription());
            int[] tally = counts.get(category);
            dto.setRegisteredCount(tally[0]);
            dto.setEnabledCount(tally[1]);
            items.add(dto);
        }
        out.setItems(items);
        return out;
    }

    // ── List ─────────────────────────────────────────────────────────────────

    @Override
    public ProductCatalogResponseDTO.ResourceList list(ProductCatalogRequestDTO.ListFilter filter) {
        long t0 = System.nanoTime();
        ProductCatalogRequestDTO.ListFilter effective =
                filter == null ? new ProductCatalogRequestDTO.ListFilter() : filter;

        String org = org();
        if (!effective.isRefresh() && !warmedOrgs.contains(org)) {
            // Cold org: wait for its warm-up (normally already running since boot)
            // rather than starting a second, identical refresh on this thread. The
            // registry is read AFTER it, so the answer carries the fresh Last Seen.
            awaitWarmup(org);
        }
        List<CatalogResource> all = safeFindAll();
        refreshLiveness(org, all, effective.isRefresh());

        ResourceCategory categoryFilter = categoryFilterOf(effective.getCategory());
        ResourceStatus statusFilter = ResourceStatus.fromCodeOrNull(effective.getStatus());
        String search = effective.getSearch() == null ? "" : effective.getSearch().trim().toLowerCase(Locale.ROOT);

        long nowMicros = nowMicros();
        List<ProductCatalogResponseDTO.Resource> items = new ArrayList<>();
        for (CatalogResource resource : all) {
            if (matchesFilters(resource, categoryFilter, statusFilter, search)) {
                items.add(toDto(resource, nowMicros));
            }
        }

        ProductCatalogResponseDTO.ResourceList out = new ProductCatalogResponseDTO.ResourceList();
        out.setItems(items);
        out.setTotal(items.size());
        out.setOrg(org);
        out.setSupported(discovery.isSupported());
        out.setFilterMode(properties.getFilter().getMode().name());

        log.info("Product Catalog list: {} of {} resource(s) after filters in {} ms "
                + "(org={}, category={}, status={}, refresh={})",
                items.size(), all.size(), (System.nanoTime() - t0) / 1_000_000L,
                org, effective.getCategory(), effective.getStatus(), effective.isRefresh());
        return out;
    }

    /** The category a list filter names, or null for none / blank / "ALL". */
    private static ResourceCategory categoryFilterOf(String category) {
        return category == null || category.isBlank() || "ALL".equalsIgnoreCase(category.trim())
                ? null
                : ResourceCategory.fromCode(category);
    }

    /** Whether a resource passes the list's category, status and search filters. */
    private static boolean matchesFilters(CatalogResource resource, ResourceCategory categoryFilter,
            ResourceStatus statusFilter, String search) {
        if (categoryFilter != null && resource.getCategory() != categoryFilter) {
            return false;
        }
        if (statusFilter != null && resource.getStatus() != statusFilter) {
            return false;
        }
        return search.isEmpty() || matchesSearch(resource, search);
    }

    /** Free-text match across the fields the table shows plus the resolved name. */
    private static boolean matchesSearch(CatalogResource resource, String lowercaseNeedle) {
        return contains(resource.getResourceName(), lowercaseNeedle)
                || contains(resource.getResolvedName(), lowercaseNeedle)
                || (resource.getCategory() != null
                        && (contains(resource.getCategory().getCode(), lowercaseNeedle)
                                || contains(resource.getCategory().getDisplayName(), lowercaseNeedle)))
                || (resource.getStatus() != null && contains(resource.getStatus().name(), lowercaseNeedle));
    }

    private static boolean contains(String haystack, String lowercaseNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    @Override
    public ProductCatalogResponseDTO.Resource get(String id) {
        CatalogResource resource = require(id);
        return toDto(resource, nowMicros());
    }

    // ── Validate ─────────────────────────────────────────────────────────────

    @Override
    public ProductCatalogResponseDTO.ValidationResult validate(ProductCatalogRequestDTO.Validate request) {
        ResourceCategory category = ResourceCategory.fromCode(request.getCategory());
        String resourceName = requireName(request.getResourceName());

        ProductCatalogResponseDTO.ValidationResult out = new ProductCatalogResponseDTO.ValidationResult();
        out.setCategory(category.getCode());
        out.setResourceName(resourceName);
        out.setSupported(discovery.isSupported());

        Optional<CatalogResource> existing =
                registry.findByCategoryAndName(org(), category, resourceName);
        out.setAlreadyRegistered(existing.isPresent());

        if (!discovery.isSupported()) {
            // OpenObserve is the source of truth; without it, "not found" would be
            // a claim about telemetry we cannot make. Say what is actually wrong.
            out.setFound(false);
            out.setMessage("The Product Catalog needs the OpenObserve backend to validate a "
                    + "resource. Set openobserve.enabled=true.");
            return out;
        }

        TelemetryDiscoveryService.Discovery result = discovery.discover(category, resourceName);
        out.setWindowStart(TimeFormatter.microsToIso(result.windowStartMicros()));
        out.setWindowEnd(TimeFormatter.microsToIso(result.windowEndMicros()));
        out.setFound(result.found());
        out.setBindings(toBindingDtos(result.bindings()));
        out.setSignals(toSignals(result.signals(), true));

        if (!result.found()) {
            out.setMessage(NOT_FOUND_MESSAGE);
            return out;
        }

        out.setResolvedName(result.resolvedName());
        if (result.lastSeenMicros() != null) {
            out.setLastSeen(TimeFormatter.microsToIso(result.lastSeenMicros()));
        }
        out.setMessage(buildFoundMessage(result, resourceName, existing.isPresent()));
        return out;
    }

    private static String buildFoundMessage(TelemetryDiscoveryService.Discovery result,
            String typedName, boolean alreadyRegistered) {
        StringBuilder message = new StringBuilder("Telemetry found in ")
                .append(String.join(", ", result.signals()))
                .append('.');
        // A spelling difference is worth stating: it is what will be filtered on,
        // and silently registering a different string than the operator typed is
        // how a catalog stops matching the telemetry it claims to describe.
        if (result.resolvedName() != null && !result.resolvedName().equals(typedName)) {
            message.append(" Telemetry spells this '").append(result.resolvedName())
                    .append("'; that is the identifier that will be registered.");
        }
        if (alreadyRegistered) {
            message.append(" This resource is already in the catalog.");
        }
        return message.toString();
    }

    // ── Register ─────────────────────────────────────────────────────────────

    @Override
    public ProductCatalogResponseDTO.Resource register(ProductCatalogRequestDTO.Register request) {
        ResourceCategory category = ResourceCategory.fromCode(request.getCategory());
        String resourceName = requireName(request.getResourceName());
        String org = org();
        String productCode = resolveProduct(category, request.getProduct());

        // Duplicate check first: it is free, and re-running discovery for a name
        // that cannot be stored anyway would be four wasted OpenObserve queries.
        //
        // Scoped to the PRODUCT as well as the category. The same microservice name
        // under two different products is two legitimate registrations — an "api"
        // service exists in several suites — and rejecting the second as a duplicate
        // would make the product tier unusable.
        findExisting(org, category, productCode, resourceName).ifPresent(existing -> {
            throw new ResourceConflictException("'" + existing.getResourceName()
                    + "' is already registered under " + category.getDisplayName()
                    + (existing.getProductLabel() == null ? ""
                            : " / " + existing.getProductLabel())
                    + " (id " + existing.getId() + ").");
        });

        TelemetryDiscoveryService.Discovery result = runDiscoveryForRegistration(category, resourceName);

        long nowMicros = nowMicros();
        CatalogResource resource = new CatalogResource();
        resource.setId(UUID.randomUUID().toString());
        resource.setOrg(org);
        resource.setCategory(category);
        resource.setProductCode(productCode);
        resource.setResourceName(resourceName);
        resource.setVersion(request.getVersion());
        resource.setResolvedName(result.resolvedName());
        resource.setStatus(parseInitialStatus(request.getStatus()));
        resource.setBindings(result.bindings());
        resource.setSignals(new java.util.LinkedHashSet<>(result.signals()));
        resource.setLastSeenMicros(result.lastSeenMicros());
        resource.setLastCheckedMicros(nowMicros);
        resource.setRegisteredAtMicros(nowMicros);
        resource.setUpdatedAtMicros(nowMicros);
        resource.setSource(CatalogResource.Source.MANUAL);

        CatalogResource stored = registry.save(org, resource);
        log.info("Product Catalog registered '{}' under {} (id={}, org={}, signals={}, resolvedName={})",
                stored.getResourceName(), category, stored.getId(), org,
                stored.getSignals(), stored.getResolvedName());
        return toDto(stored, nowMicros);
    }

    /**
     * Discovery as the registration path needs it: a miss is fatal, unless
     * validation has been deliberately switched off.
     */
    private TelemetryDiscoveryService.Discovery runDiscoveryForRegistration(ResourceCategory category,
            String resourceName) {
        boolean mustValidate = properties.getValidation().isRequired();

        if (!discovery.isSupported()) {
            if (mustValidate) {
                throw new IllegalStateException("The Product Catalog cannot validate a resource "
                        + "while openobserve.enabled=false, and validation is required. Set "
                        + "product-catalog.validation.required=false to register without it.");
            }
            log.warn("Registering '{}' without validation — OpenObserve is disabled", resourceName);
            return emptyDiscovery();
        }

        // Cached, because Validate almost always ran moments ago and the chain is
        // expensive. Only a SUCCESS is reusable, so the refusal below is always
        // based on a fresh look — which is what someone who has just started their
        // agent and is retrying needs.
        TelemetryDiscoveryService.Discovery result = discovery.discoverCached(category, resourceName);
        if (!result.found() && mustValidate) {
            throw new TelemetryNotFoundException(NOT_FOUND_MESSAGE);
        }
        if (!result.found()) {
            log.warn("Registering '{}' with no telemetry found — "
                    + "product-catalog.validation.required=false", resourceName);
        }
        return result;
    }

    private static TelemetryDiscoveryService.Discovery emptyDiscovery() {
        long now = nowMicros();
        return new TelemetryDiscoveryService.Discovery(false, null, List.of(), List.of(), null, now, now);
    }

    private static ResourceStatus parseInitialStatus(String raw) {
        ResourceStatus parsed = ResourceStatus.fromCodeOrNull(raw);
        return parsed == null ? ResourceStatus.ENABLED : parsed;
    }

    // ── Enable / disable / remove ────────────────────────────────────────────

    @Override
    public ProductCatalogResponseDTO.WriteResult enable(String id) {
        return setStatus(id, ResourceStatus.ENABLED);
    }

    @Override
    public ProductCatalogResponseDTO.WriteResult disable(String id) {
        return setStatus(id, ResourceStatus.DISABLED);
    }

    private ProductCatalogResponseDTO.WriteResult setStatus(String id, ResourceStatus target) {
        String org = org();
        long nowMicros = nowMicros();

        // Through registry.update so this cannot race a concurrent liveness
        // refresh on the same resource and lose one of the two writes.
        CatalogResource updated = registry.update(org, id, live -> {
            if (live.getStatus() == target) {
                return false;
            }
            live.setStatus(target);
            live.setUpdatedAtMicros(nowMicros);
            return true;
        }).orElseThrow(() -> notFound(id));

        String verb = target == ResourceStatus.ENABLED ? "enabled" : "disabled";
        log.info("Product Catalog {} '{}' (id={}, org={})", verb, updated.getResourceName(), id, org);

        ProductCatalogResponseDTO.WriteResult out = new ProductCatalogResponseDTO.WriteResult();
        out.setId(id);
        out.setResource(toDto(updated, nowMicros));
        out.setMessage("'" + updated.getResourceName() + "' " + verb
                + (target == ResourceStatus.ENABLED
                        ? ". Its logs, traces and metrics are visible again."
                        : ". Its telemetry is now hidden across Logs, Traces and Metrics."));
        return out;
    }

    @Override
    public ProductCatalogResponseDTO.WriteResult remove(String id) {
        String org = org();
        CatalogResource existing = require(id);
        if (!registry.deleteById(org, id)) {
            // Lost a race with another delete. Same outcome the caller wanted.
            throw notFound(id);
        }
        log.info("Product Catalog removed '{}' (id={}, org={})", existing.getResourceName(), id, org);

        ProductCatalogResponseDTO.WriteResult out = new ProductCatalogResponseDTO.WriteResult();
        out.setId(id);
        out.setMessage("'" + existing.getResourceName() + "' removed from the catalog. "
                + "Its telemetry in OpenObserve is untouched.");
        return out;
    }

    // ── Liveness ─────────────────────────────────────────────────────────────

    /** Starts the default org's warm-up once the context is up, off the request path. */
    @EventListener(ApplicationReadyEvent.class)
    void prewarmLiveness() {
        if (!prewarmEnabled || !discovery.isSupported()) {
            return;
        }
        String org = openObserveConfig.getDefaultOrg();
        log.info("Product Catalog: warming Last Seen for org '{}' at startup", org);
        warmup(org);
    }

    /**
     * The warm-up for an org: one full liveness refresh on the liveness pool, after
     * which the org is marked warm. Started at most once per org; a failed start
     * is forgotten so the next list can try again.
     */
    private CompletableFuture<Void> warmup(String org) {
        return warmups.computeIfAbsent(org, key -> {
            try {
                return CompletableFuture.runAsync(() -> {
                    String previous = OrgContext.get();
                    try {
                        // The pool thread has no request scope; every query below
                        // builds its URL from OrgContext.
                        OrgContext.set(key);
                        List<CatalogResource> resources = registry.findAll(key);
                        if (!resources.isEmpty() && discovery.isSupported()) {
                            long nowMicros = nowMicros();
                            Map<String, List<TelemetryBinding>> due = new LinkedHashMap<>();
                            List<CatalogResource> unbound = new ArrayList<>();
                            collectDue(resources, false, nowMicros, due, unbound);
                            runLivenessRefresh(key, null, due, unbound, nowMicros);
                        }
                        warmedOrgs.add(key);
                    } finally {
                        if (previous == null) {
                            OrgContext.clear();
                        } else {
                            OrgContext.set(previous);
                        }
                    }
                }, livenessPool);
            } catch (RuntimeException e) {
                // Rejected during shutdown.
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    /** Waits for the org's warm-up. On failure the list falls back to warming inline. */
    private void awaitWarmup(String org) {
        CompletableFuture<Void> future = warmup(org);
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warmups.remove(org, future);
            log.warn("Product Catalog warm-up failed for org '{}'; refreshing inline: {}",
                    org, e.getMessage());
        }
    }

    /** Sorts the resources whose Last Seen is due into bound (by id) and unbound. */
    private void collectDue(List<CatalogResource> resources, boolean force, long nowMicros,
            Map<String, List<TelemetryBinding>> due, List<CatalogResource> unbound) {
        long ttlMicros = properties.getValidation().getLivenessTtlSeconds() * 1_000_000L;
        for (CatalogResource resource : resources) {
            Long checked = resource.getLastCheckedMicros();
            boolean stale = force || checked == null || nowMicros - checked >= ttlMicros;
            if (!stale) {
                continue;
            }
            if (resource.getBindings().isEmpty()) {
                unbound.add(resource);
            } else {
                due.put(resource.getId(), resource.getBindings());
            }
        }
    }

    /**
     * Bring Last Seen up to date for the resources whose cached value has aged
     * past the TTL.
     *
     * <p>Batched into one query per distinct (stream, column) by
     * {@link TelemetryDiscoveryService#lastSeen}, so the cost of listing the
     * catalog does not grow with the number of resources in it — which matters
     * because the UI polls this endpoint.</p>
     *
     * <p>A resource that returns no row keeps its previous Last Seen rather than
     * being reset to null: "quiet for the last hour" and "never seen" are
     * different states, and collapsing them would make a briefly idle service
     * look unregistered.</p>
     */
    private void refreshLiveness(String org, List<CatalogResource> resources, boolean force) {
        if (resources.isEmpty() || !discovery.isSupported()) {
            return;
        }
        long nowMicros = nowMicros();
        Map<String, List<TelemetryBinding>> due = new LinkedHashMap<>();
        List<CatalogResource> unbound = new ArrayList<>();
        collectDue(resources, force, nowMicros, due, unbound);
        if (due.isEmpty() && unbound.isEmpty()) {
            return;
        }

        // An explicit refresh, and the first list of an org, are answered the slow
        // way — the caller asked to be told what is true NOW, and on a cold process
        // the cached answer is whatever registration wrote.
        if (force || !warmedOrgs.contains(org)) {
            runLivenessRefresh(org, resources, due, unbound, nowMicros);
            warmedOrgs.add(org);
            return;
        }

        // Otherwise: answer from the cache and refresh behind it. This endpoint is
        // POLLED — by the catalog screen and by step 1 of the alert wizard — and a
        // poll interval equal to the TTL meant nearly every one of them waited on a
        // full re-query. Values are at most one poll old, which is what a cache
        // recomputed on a timer means anyway.
        // Copies: the background pass writes its findings back onto the resources it
        // is given, and the ones collected above are the very objects the request
        // thread is about to map into the response. Handing over the originals would
        // be two threads on one object for no gain — the registry, not these
        // snapshots, is what the next poll reads.
        List<CatalogResource> unboundCopy = new ArrayList<>(unbound.size());
        for (CatalogResource resource : unbound) {
            unboundCopy.add(resource.copy());
        }
        scheduleLivenessRefresh(org, due, unboundCopy, nowMicros);
    }

    /**
     * Refresh in the background, at most one per org at a time.
     *
     * <p>Dropped rather than queued when one is already running: the next poll is a
     * second away and will schedule its own with fresher input, so queueing would
     * only build a backlog of work that is stale by the time it runs.</p>
     */
    private void scheduleLivenessRefresh(String org, Map<String, List<TelemetryBinding>> due,
            List<CatalogResource> unbound, long nowMicros) {
        if (!refreshing.add(org)) {
            log.debug("Product Catalog liveness refresh for org '{}' already in flight — skipped", org);
            return;
        }
        // The org lives in a ThreadLocal the pool thread does not inherit, and every
        // query below builds its URL from it. Carried explicitly, as the discovery
        // pool does; `org` here is already the resolved value.
        try {
            livenessPool.execute(() -> {
                String previous = OrgContext.get();
                try {
                    OrgContext.set(org);
                    // Null: nothing to reflect onto. The request that scheduled this
                    // has already been answered, and the snapshots it mapped are its
                    // own — writing to them from here would be a race for no benefit.
                    runLivenessRefresh(org, null, due, unbound, nowMicros);
                } catch (Exception e) {
                    log.warn("Product Catalog background liveness refresh failed for org '{}': {}",
                            org, e.getMessage());
                } finally {
                    if (previous == null) {
                        OrgContext.clear();
                    } else {
                        OrgContext.set(previous);
                    }
                    refreshing.remove(org);
                }
            });
        } catch (RuntimeException e) {
            // Rejected during shutdown. Never fatal — the list is already answered.
            refreshing.remove(org);
            log.debug("Product Catalog liveness refresh not scheduled for org '{}': {}",
                    org, e.getMessage());
        }
    }

    /**
     * The refresh itself.
     *
     * @param resources the snapshots the caller is about to map, or null when this
     *                  runs behind a response that has already been sent
     */
    private void runLivenessRefresh(String org, List<CatalogResource> resources,
            Map<String, List<TelemetryBinding>> due, List<CatalogResource> unbound,
            long nowMicros) {
        bindUnboundResources(org, unbound, nowMicros);
        if (due.isEmpty()) {
            return;
        }

        Map<String, Long> newest;
        try {
            newest = discovery.lastSeen(due);
        } catch (Exception e) {
            // Never fatal: the catalog still lists, with whatever Last Seen it had.
            log.warn("Product Catalog liveness refresh failed for org '{}': {}", org, e.getMessage());
            return;
        }

        recordLastSeen(org, due, newest, nowMicros);
        reflectOntoSnapshots(resources, due, newest, nowMicros);
        log.debug("Product Catalog liveness refreshed {} resource(s) for org '{}'", due.size(), org);
    }

    /** Stamp the fetched Last Seen, and the check itself, onto the registry. */
    private void recordLastSeen(String org, Map<String, List<TelemetryBinding>> due,
            Map<String, Long> newest, long nowMicros) {
        for (Map.Entry<String, List<TelemetryBinding>> entry : due.entrySet()) {
            Long seen = newest.get(entry.getKey());
            registry.update(org, entry.getKey(), live -> {
                live.setLastCheckedMicros(nowMicros);
                if (seen != null
                        && (live.getLastSeenMicros() == null || seen > live.getLastSeenMicros())) {
                    live.setLastSeenMicros(seen);
                }
                // FALSE: the in-memory update still happens (the mutator has run),
                // but this must NOT be appended to the durable change log.
                //
                // Last Seen is a cache, recomputed from OpenObserve on demand —
                // and this method runs on every catalog list request past the TTL.
                // Persisting it appended one row per resource per TTL forever, so
                // a catalog of 50 resources polled every 30s would have written
                // ~4M rows a month to record nothing an operator ever changed.
                // Losing it on restart costs one refresh.
                return false;
            });
        }
    }

    /**
     * Reflect the refresh onto the snapshots the caller is about to map, so
     * the response shows the value that was just fetched rather than the one
     * from the previous poll. Absent on the background path — that response
     * has already gone out, and the next poll reads the registry's cache.
     */
    private static void reflectOntoSnapshots(List<CatalogResource> resources,
            Map<String, List<TelemetryBinding>> due, Map<String, Long> newest, long nowMicros) {
        for (CatalogResource resource : resources == null ? List.<CatalogResource>of() : resources) {
            Long seen = newest.get(resource.getId());
            if (seen != null && (resource.getLastSeenMicros() == null
                    || seen > resource.getLastSeenMicros())) {
                resource.setLastSeenMicros(seen);
            }
            if (due.containsKey(resource.getId())) {
                resource.setLastCheckedMicros(nowMicros);
            }
        }
    }

    /**
     * Give resources that have no proven identifier yet a chance to acquire one.
     *
     * <p>Reached by a config seed and by anything registered while
     * {@code product-catalog.validation.required=false}: it has a name but no
     * binding, so it filters only on the fallback column and shows no signals. A
     * full discovery pass fixes that as soon as its telemetry appears, without the
     * operator having to re-register it.</p>
     *
     * <p>Bounded by {@link #MAX_DISCOVERY_PER_LIST} per request, because this one
     * is NOT batchable — discovery is a chain of queries per name, so an
     * unvalidated catalog of fifty resources would otherwise turn one poll into
     * two hundred queries. The rest are picked up by subsequent polls.</p>
     */
    private void bindUnboundResources(String org, List<CatalogResource> unbound, long nowMicros) {
        if (unbound.isEmpty()) {
            return;
        }
        int attempts = Math.min(unbound.size(), MAX_DISCOVERY_PER_LIST);
        if (unbound.size() > attempts) {
            log.debug("Product Catalog deferring discovery for {} of {} unbound resource(s) "
                    + "to a later request", unbound.size() - attempts, unbound.size());
        }

        for (int i = 0; i < attempts; i++) {
            CatalogResource resource = unbound.get(i);
            TelemetryDiscoveryService.Discovery result;
            try {
                result = discovery.discover(resource.getCategory(), resource.getResourceName());
            } catch (Exception e) {
                log.debug("Product Catalog late discovery failed for '{}': {}",
                        resource.getResourceName(), e.getMessage());
                continue;
            }
            applyDiscovery(org, resource, result, nowMicros);
        }
    }

    /** Record one late discovery attempt on the registry and on the caller's snapshot. */
    private void applyDiscovery(String org, CatalogResource resource,
            TelemetryDiscoveryService.Discovery result, long nowMicros) {
        // Stamp the check even on a miss, so a resource whose telemetry never
        // arrives is retried on the TTL rather than on every single request.
        registry.update(org, resource.getId(), live -> {
            live.setLastCheckedMicros(nowMicros);
            if (!result.found()) {
                // Only a cache stamp changed — see the note in refreshLiveness
                // on why that must not reach the change log.
                return false;
            }
            live.setResolvedName(result.resolvedName());
            live.setBindings(result.bindings());
            live.setSignals(new java.util.LinkedHashSet<>(result.signals()));
            if (result.lastSeenMicros() != null) {
                live.setLastSeenMicros(result.lastSeenMicros());
            }
            // Bindings and the resolved identifier ARE durable state — they
            // decide what gets filtered — so this one is worth a row.
            live.setUpdatedAtMicros(nowMicros);
            return true;
        });

        resource.setLastCheckedMicros(nowMicros);
        if (result.found()) {
            resource.setResolvedName(result.resolvedName());
            resource.setBindings(result.bindings());
            resource.setSignals(new java.util.LinkedHashSet<>(result.signals()));
            if (result.lastSeenMicros() != null) {
                resource.setLastSeenMicros(result.lastSeenMicros());
            }
            log.info("Product Catalog late-bound '{}' (id={}): signals={}, resolvedName={}",
                    resource.getResourceName(), resource.getId(),
                    result.signals(), result.resolvedName());
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private ProductCatalogResponseDTO.Resource toDto(CatalogResource resource, long nowMicros) {
        ProductCatalogResponseDTO.Resource dto = new ProductCatalogResponseDTO.Resource();
        dto.setId(resource.getId());
        if (resource.getCategory() != null) {
            dto.setCategory(resource.getCategory().getCode());
            dto.setCategoryLabel(resource.getCategory().getDisplayName());
        }
        dto.setProduct(resource.getProductCode());
        // The label is resolved from the product table rather than stored on the
        // resource, so correcting a product's name does not require rewriting every
        // row registered under it.
        dto.setProductLabel(resource.getProductLabel() != null
                ? resource.getProductLabel()
                : productService.labelFor(resource.getCategory(), resource.getProductCode()));
        dto.setResourceName(resource.getResourceName());
        dto.setVersion(resource.getVersion());
        dto.setResolvedName(resource.getResolvedName());
        dto.setStatus(resource.getStatus() == null ? ResourceStatus.ENABLED.name()
                : resource.getStatus().name());
        dto.setSource(resource.getSource() == null ? CatalogResource.Source.MANUAL.name()
                : resource.getSource().name());

        if (resource.getLastSeenMicros() != null) {
            dto.setLastSeen(TimeFormatter.microsToIso(resource.getLastSeenMicros()));
            dto.setLastSeenAgoSec(Math.max(0L, (nowMicros - resource.getLastSeenMicros()) / 1_000_000L));
        }
        if (resource.getRegisteredAtMicros() > 0) {
            dto.setRegisteredAt(TimeFormatter.microsToIso(resource.getRegisteredAtMicros()));
        }
        if (resource.getUpdatedAtMicros() > 0) {
            dto.setUpdatedAt(TimeFormatter.microsToIso(resource.getUpdatedAtMicros()));
        }

        dto.setBindings(toBindingDtos(resource.getBindings()));
        // The checklist answers "would you see this right now", so a disabled
        // resource reads as three crosses even though its telemetry exists.
        dto.setSignals(toSignals(new ArrayList<>(resource.getSignals()), resource.isEnabled()));
        return dto;
    }

    private static ProductCatalogResponseDTO.Signals toSignals(List<String> available, boolean enabled) {
        ProductCatalogResponseDTO.Signals signals = new ProductCatalogResponseDTO.Signals();
        signals.setAvailable(available == null ? List.of() : new ArrayList<>(available));
        if (available == null || !enabled) {
            return signals;
        }
        signals.setLogs(available.contains("logs"));
        signals.setTraces(available.contains("traces"));
        signals.setMetrics(available.contains("metrics"));
        return signals;
    }

    private static List<ProductCatalogResponseDTO.Binding> toBindingDtos(List<TelemetryBinding> bindings) {
        List<ProductCatalogResponseDTO.Binding> out = new ArrayList<>();
        if (bindings == null) {
            return out;
        }
        for (TelemetryBinding binding : bindings) {
            out.add(new ProductCatalogResponseDTO.Binding(binding.signal(), binding.stream(),
                    binding.column(), binding.value()));
        }
        return out;
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /**
     * The organization the current request is scoped to.
     *
     * <p>Read from {@code OpenObserveConfig} rather than {@code OrgContext}
     * directly, so the catalog is keyed by exactly the same value that decides
     * which OpenObserve org the validation queries hit. Reading them from two
     * places is how a resource ends up registered against one tenant and
     * validated against another.</p>
     */
    private String org() {
        return openObserveConfig.getOrg();
    }

    private List<CatalogResource> safeFindAll() {
        try {
            return registry.findAll(org());
        } catch (Exception e) {
            log.error("Product Catalog registry unreadable: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private CatalogResource require(String id) {
        return registry.findById(org(), id).orElseThrow(() -> notFound(id));
    }

    private static ResourceNotFoundException notFound(String id) {
        return new ResourceNotFoundException("No catalog resource with id '" + id + "'.");
    }

    private static String requireName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("resourceName is required and must not be blank.");
        }
        return raw.trim();
    }

    /** Epoch microseconds, matching every timestamp in the OpenObserve layer. */
    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
