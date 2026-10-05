package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.config.ProductCatalogProperties.FilterMode;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.CatalogTelemetryFilter;
import com.trinity.observability.util.SqlSafety;

/**
 * Turns the registry into a WHERE clause.
 *
 * <h2>The two modes, concretely</h2>
 *
 * <p><b>EXCLUDE_DISABLED</b> (default) produces, per identifier column that any
 * disabled resource is bound to:</p>
 *
 * <pre>(service_name IS NULL OR service_name NOT IN ('redis-master', 'kafkaui'))</pre>
 *
 * <p>The {@code IS NULL} half is not decoration. In SQL, {@code NULL NOT IN (…)}
 * evaluates to NULL, which is not true, so the row is dropped — meaning a plain
 * {@code NOT IN} would silently delete every log line that happens to have no
 * {@code service_name} at all. On this deployment that is a large fraction of the
 * scraped Kubernetes logs.</p>
 *
 * <p><b>ENABLED_ONLY</b> produces a disjunction, because a row only has to match
 * ONE of a resource's identifiers to belong to it:</p>
 *
 * <pre>(service_name IN ('IoTOpsApiSvc') OR db_name IN ('openobserve'))</pre>
 *
 * <p>and returns {@code ""} on an empty registry, so switching the mode on does
 * not blank every screen before the first registration.</p>
 *
 * <h2>Case sensitivity</h2>
 *
 * <p>Comparisons are EXACT. Registration resolves the spelling telemetry actually
 * uses and stores it in the binding, so there is nothing left to fold — and
 * folding would be actively wrong here, because {@code IoTOpsApiSvc} and
 * {@code iotopsapisvc} are two different services on this instance (SDK-reported
 * production vs. the dev cluster's log scraper). Disabling one must not hide the
 * other.</p>
 */
@Service
public class CatalogTelemetryFilterImpl implements CatalogTelemetryFilter {

    private static final Logger log = LogManager.getLogger(CatalogTelemetryFilterImpl.class);

    /**
     * Column used when a resource carries no binding for a signal.
     *
     * <p>Reached by a resource seeded from configuration, or registered while
     * validation was switched off: it has a name but no proven identifier. Leaving
     * such a resource unfilterable would mean a Disabled row in the UI whose
     * telemetry keeps appearing, so its name is excluded on the one column that
     * exists on every stream in all three signals — and which every pre-existing
     * query in this service already filters on.</p>
     */
    private static final String FALLBACK_COLUMN = "service_name";

    private final ProductCatalogRegistry registry;
    private final OpenObserveConfig openObserveConfig;
    private final ProductCatalogProperties properties;

    /**
     * org → the catalog as it was read, and when that reading goes stale.
     *
     * <p>{@link #sqlPredicate} runs on EVERY telemetry query this service builds,
     * and each call went to {@code registry.findAll(org)}, which is two PostgreSQL
     * round trips (the resource rows, then the product labels). Individually that
     * is ~15 ms and invisible; in aggregate it is what put the Hikari pool — max
     * size 10 — on the critical path of every request, so throughput stopped
     * scaling past about ten concurrent users while latency climbed.</p>
     *
     * <p>A few seconds of staleness is the right trade here: catalog entries change
     * when an operator enables or disables a resource, which is a deliberate,
     * occasional action, and the effect of the delay is that a just-disabled
     * resource's telemetry keeps appearing for at most one TTL. Hand-rolled to
     * match {@code AlertScopeServiceImpl.probeCache} and
     * {@code AlertStreamMeasurementLookup.cache} rather than introducing a caching
     * framework for one map.</p>
     */
    private final ConcurrentHashMap<String, CachedResources> catalogCache = new ConcurrentHashMap<>();

    /**
     * How long a read of the catalog is reused, in milliseconds.
     *
     * <p>Deliberately short. It exists to collapse the many calls made while
     * serving ONE request (and the several requests of one dashboard poll) into a
     * single database read — not to hold the catalog for any length of time.</p>
     */
    private final long catalogTtlMillis;

    public CatalogTelemetryFilterImpl(ProductCatalogRegistry registry,
            OpenObserveConfig openObserveConfig,
            ProductCatalogProperties properties,
            @Value("${product-catalog.filter.cache-millis:5000}") long catalogTtlMillis) {
        this.registry = registry;
        this.openObserveConfig = openObserveConfig;
        this.properties = properties;
        this.catalogTtlMillis = catalogTtlMillis;
    }

    private record CachedResources(long expiresAtMillis, List<CatalogResource> resources) {
        boolean fresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    /**
     * The catalog for an org, from cache when it is still fresh.
     *
     * <p>The cached list is immutable, so a caller cannot disturb the copy a
     * concurrent request is reading.</p>
     */
    private List<CatalogResource> resourcesFor(String org) {
        String key = org == null ? "" : org;
        CachedResources cached = catalogCache.get(key);
        if (cached != null && cached.fresh()) {
            return cached.resources();
        }
        List<CatalogResource> resources = List.copyOf(registry.findAll(org));
        catalogCache.put(key, new CachedResources(
                System.currentTimeMillis() + catalogTtlMillis, resources));
        return resources;
    }

    /** Drops the cached catalog. Test seam and operational escape hatch. */
    public void invalidate() {
        catalogCache.clear();
    }

    @Override
    public String sqlPredicate(String signal) {
        if (!isActive()) {
            return "";
        }
        try {
            List<CatalogResource> resources = resourcesFor(openObserveConfig.getOrg());
            if (resources.isEmpty()) {
                return "";
            }
            List<String> allowedColumns = properties.getFilter().columnsFor(signal);
            if (allowedColumns.isEmpty()) {
                return "";
            }
            return properties.getFilter().getMode() == FilterMode.ENABLED_ONLY
                    ? buildAllowList(signal, allowedColumns, resources)
                    : buildDenyList(signal, allowedColumns, resources);
        } catch (Exception e) {
            // Fail open — see the interface contract. A registry problem must not
            // become an empty Logs screen.
            log.error("Product Catalog filter unavailable for signal '{}'; not filtering: {}",
                    signal, e.getMessage(), e);
            return "";
        }
    }

    /** {@code EXCLUDE_DISABLED}: subtract what an operator switched off. */
    private String buildDenyList(String signal, List<String> allowedColumns,
            List<CatalogResource> resources) {
        Map<String, Set<String>> byColumn = collectValues(signal, allowedColumns, resources, false);
        if (byColumn.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>(byColumn.size());
        for (Map.Entry<String, Set<String>> entry : byColumn.entrySet()) {
            String column = entry.getKey();
            clauses.add('(' + column + " IS NULL OR " + column
                    + " NOT IN (" + inList(entry.getValue()) + "))");
        }
        String predicate = clauses.size() == 1 ? clauses.get(0) : '(' + String.join(" AND ", clauses) + ')';
        if (log.isDebugEnabled()) {
            log.debug("Product Catalog filter (EXCLUDE_DISABLED, {}): {}", signal, predicate);
        }
        return predicate;
    }

    /** {@code ENABLED_ONLY}: keep only what is registered and switched on. */
    private String buildAllowList(String signal, List<String> allowedColumns,
            List<CatalogResource> resources) {
        Map<String, Set<String>> byColumn = collectValues(signal, allowedColumns, resources, true);
        if (byColumn.isEmpty()) {
            // Every registered resource is disabled, or none is bound to a column
            // this signal can filter on. Excluding everything would be a defensible
            // reading, but a blank screen with no explanation is not a good failure
            // mode for a mode switch, so nothing is filtered and the reason is logged.
            log.warn("Product Catalog filter mode is ENABLED_ONLY but no enabled resource is "
                    + "bound to a filterable {} column — not filtering {}", signal, signal);
            return "";
        }
        List<String> clauses = new ArrayList<>(byColumn.size());
        for (Map.Entry<String, Set<String>> entry : byColumn.entrySet()) {
            clauses.add(entry.getKey() + " IN (" + inList(entry.getValue()) + ')');
        }
        String predicate = '(' + String.join(" OR ", clauses) + ')';
        if (log.isDebugEnabled()) {
            log.debug("Product Catalog filter (ENABLED_ONLY, {}): {}", signal, predicate);
        }
        return predicate;
    }

    /**
     * Column → identifier values, for resources in the requested state.
     *
     * @param wantEnabled true to collect enabled resources (allow-list), false to
     *                    collect disabled ones (deny-list)
     */
    private Map<String, Set<String>> collectValues(String signal, List<String> allowedColumns,
            List<CatalogResource> resources, boolean wantEnabled) {
        Map<String, Set<String>> byColumn = new LinkedHashMap<>();
        for (CatalogResource resource : resources) {
            if (resource.isEnabled() == wantEnabled
                    && !addBindings(byColumn, signal, allowedColumns, resource)) {
                addFallback(byColumn, allowedColumns, resource);
            }
        }
        return byColumn;
    }

    /**
     * Adds the resource's usable bindings for the signal.
     *
     * @return true when at least one binding was added
     */
    private static boolean addBindings(Map<String, Set<String>> byColumn, String signal,
            List<String> allowedColumns, CatalogResource resource) {
        boolean bound = false;
        for (TelemetryBinding binding : resource.getBindings()) {
            if (isUsable(binding, signal, allowedColumns)) {
                byColumn.computeIfAbsent(binding.column(), unused -> new LinkedHashSet<>())
                        .add(binding.value());
                bound = true;
            }
        }
        return bound;
    }

    /** A binding for this signal, on a filterable column, with a non-blank value. */
    private static boolean isUsable(TelemetryBinding binding, String signal,
            List<String> allowedColumns) {
        return signalMatches(signal, binding.signal())
                && allowedColumns.contains(binding.column())
                && binding.value() != null && !binding.value().isBlank();
    }

    /** See {@link #FALLBACK_COLUMN} for why an unbound resource still filters. */
    private void addFallback(Map<String, Set<String>> byColumn, List<String> allowedColumns,
            CatalogResource resource) {
        String name = resource.effectiveName();
        if (name == null || name.isBlank() || !allowedColumns.contains(FALLBACK_COLUMN)) {
            return;
        }
        byColumn.computeIfAbsent(FALLBACK_COLUMN, unused -> new LinkedHashSet<>()).add(name.trim());
    }

    @Override
    public boolean permits(String signal, String column, String value) {
        if (!isActive() || value == null) {
            return true;
        }
        try {
            List<CatalogResource> resources = registry.findAll(openObserveConfig.getOrg());
            if (resources.isEmpty()) {
                return true;
            }
            List<String> allowedColumns = properties.getFilter().columnsFor(signal);
            if (!allowedColumns.contains(column)) {
                return true;
            }
            if (properties.getFilter().getMode() == FilterMode.ENABLED_ONLY) {
                Set<String> enabled = collectValues(signal, allowedColumns, resources, true)
                        .getOrDefault(column, Set.of());
                return enabled.isEmpty() || enabled.contains(value);
            }
            return !collectValues(signal, allowedColumns, resources, false)
                    .getOrDefault(column, Set.of())
                    .contains(value);
        } catch (Exception e) {
            log.error("Product Catalog filter unavailable for {}.{}; permitting '{}': {}",
                    signal, column, value, e.getMessage());
            return true;
        }
    }

    @Override
    public List<String> retainPermitted(String signal, String column, List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.isEmpty() || !isActive()) {
            return values;
        }
        List<String> kept = permitted(signal, column, values);
        if (kept.size() != values.size() && log.isDebugEnabled()) {
            log.debug("Product Catalog filter removed {} of {} {}.{} value(s)",
                    values.size() - kept.size(), values.size(), signal, column);
        }
        return kept;
    }

    /** The values {@link #permits} accepts, in their original order. */
    private List<String> permitted(String signal, String column, List<String> values) {
        List<String> kept = new ArrayList<>(values.size());
        for (String value : values) {
            if (permits(signal, column, value)) {
                kept.add(value);
            }
        }
        return kept;
    }

    private boolean isActive() {
        return properties.isEnabled() && properties.getFilter().isEnabled();
    }

    private static boolean signalMatches(String requested, String bound) {
        return requested != null && bound != null
                && requested.toLowerCase(Locale.ROOT).equals(bound.toLowerCase(Locale.ROOT));
    }

    /** Quoted, escaped, comma-separated — ready for an {@code IN (…)}. */
    private static String inList(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append('\'').append(SqlSafety.escapeStringLiteral(value)).append('\'');
        }
        return out.toString();
    }
}
