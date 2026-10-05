package com.trinity.observability.serviceimpl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.analytics.AnalyticsResponseDTO;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.AnalyticsService;
import com.trinity.observability.service.CatalogProductService;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.RefreshingCache;
import com.trinity.observability.util.SqlSafety;
import com.trinity.observability.util.TimeFormatter;
import com.trinity.observability.util.TimeRangeParser;

/**
 * Log counts per catalog level, from OpenObserve.
 *
 * <h2>Nothing about the estate is written down here</h2>
 *
 * <p>Which categories exist, which products they hold and which microservices sit
 * under those products all come from the registry. The dashboard therefore follows
 * a registration immediately: register a microservice under
 * "trinityIoT - IoT Hub System and IoT SDK" and that product appears with a count,
 * remove the last one and it stops being listed.</p>
 *
 * <h2>Counting is grouped by the catalog's OWN identifier columns</h2>
 *
 * <p>A resource is not always identified by {@code service_name} — the catalog
 * binds some by {@code k8s_deployment_name}, a database by {@code db_name}. So the
 * in-scope resources are bucketed by the column their logs binding names, and ONE
 * grouped query is issued per distinct column. In practice that is one query; it is
 * two when the scope mixes an SDK-instrumented service with a scraped one, and it
 * is never one query per row.</p>
 *
 * <h2>Severity is canonicalised, not trusted</h2>
 *
 * <p>This stream carries {@code INFO}, {@code WARN}, {@code WARNING}, {@code ERROR},
 * {@code DEBUG}, {@code TRACE} and a bucket whose severity is the literal
 * {@code "0"} — the largest of them. Counting raw values would report WARN and
 * WARNING as unrelated, and it is why Total is every row rather than the sum of the
 * named columns: INFO, WARN, ERROR and DEBUG are reported, TRACE and the unspecified
 * bucket are left in {@code other}, and the four plus {@code other} reconcile to
 * Total.</p>
 */
@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final Logger log = LogManager.getLogger(AnalyticsServiceImpl.class);

    /** Default window when the caller does not choose one. */
    private static final String DEFAULT_WINDOW = "now-24h";

    /**
     * Cap on (identifier × severity) permutations read back per query.
     *
     * <p>The result of a GROUP BY over two low-cardinality columns: a few hundred
     * services times seven severities. Far above any real deployment, and it is a
     * guard against a corrupt schema turning one dashboard load into an unbounded
     * read rather than a limit anyone should reach.</p>
     */
    private static final int MAX_GROUPS = 10_000;

    /** Identifier values per query. Postgres and DataFusion both handle large IN
     *  lists, but a single query naming every service in a huge estate is worth
     *  splitting so one slow scan cannot hold the whole dashboard. */
    private static final int MAX_IDENTIFIERS_PER_QUERY = 500;

    private final ProductCatalogRegistry registry;
    private final CatalogProductService productService;
    private final OpenObserveClientService openObserve;
    private final OpenObserveConfig openObserveConfig;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /**
     * How long a set of log counts is reused. Each load is a 24-hour GROUP BY over
     * the logs stream (~0.9 s measured), repeated by the dashboard's poll and by
     * every revisit, for totals that barely move inside a minute. Keyed by org,
     * window expression and the exact identifiers counted, so registering or
     * disabling a resource is a different key and shows at once. 0 disables it.
     */
    @Value("${analytics.log-counts.cache-seconds:30}")
    private int countsCacheSeconds;

    /** The counts together with the window they were taken over, so a cached
     *  answer reports the range it actually covers. */
    private record CountedWindow(Window range, Counted counted) {
    }

    private static final String SEV_DEBUG = "debug";
    private static final String SEV_UNSPECIFIED = "unspecified";

    /** Lower-cased severity prefixes counted as errors: ERROR and everything above it. */
    private static final List<String> ERROR_SEVERITY_PREFIXES =
            List.of("err", "fatal", "crit", "alert", "emerg", "severe", "panic");

    private final AtomicReference<RefreshingCache<CountedWindow>> countsCache = new AtomicReference<>();

    public AnalyticsServiceImpl(ProductCatalogRegistry registry,
            CatalogProductService productService,
            OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig) {
        this.registry = registry;
        this.productService = productService;
        this.openObserve = openObserve;
        this.openObserveConfig = openObserveConfig;
    }

    @Override
    public AnalyticsResponseDTO.LogCounts logCounts(String category, String product, String window,
            String level) {
        long t0 = System.nanoTime();

        String org = openObserveConfig.getOrg();
        String windowExpr = windowExprOf(window);
        // End is "now" and start is the expression resolved against it, matching how
        // every other screen reads a relative window — so the dashboard and the Logs
        // table asked for "now-24h" cover the same range.
        long endMicros = TimeRangeParser.defaultEndMicros();
        Window range = new Window(
                TimeRangeParser.toMicros(windowExpr, TimeRangeParser.defaultStartMicros()),
                endMicros);

        AnalyticsResponseDTO.LogCounts out = new AnalyticsResponseDTO.LogCounts();
        out.setOrg(org);
        out.setWindow(windowExpr);
        out.setWindowStart(TimeFormatter.microsToIso(range.startMicros()));
        out.setWindowEnd(TimeFormatter.microsToIso(range.endMicros()));

        if (!openObserveEnabled) {
            out.setSupported(false);
            out.setMessage("The Analytics Dashboard reads log volume from OpenObserve, "
                    + "which is disabled (openobserve.enabled=false).");
            return out;
        }

        ResourceCategory scopeCategory = scopeCategoryOf(category);
        String scopeProduct = scopeProductOf(product);
        AnalyticsResponseDTO.Level requestedLevel = validatedLevel(scopeCategory, scopeProduct, level);

        // ENABLED only. A disabled resource contributes nothing to Logs, Traces or
        // Metrics anywhere else in the application, and counting it here would report
        // volume for something the rest of the product says is hidden.
        List<CatalogResource> registered = enabledResources(org);
        if (registered.isEmpty()) {
            out.setCatalogEmpty(true);
            out.setMessage("Nothing is registered in the Product Catalog yet, so there is "
                    + "nothing to report on.");
            return out;
        }

        // The rows, and which resources each row is counted from.
        Scope scope = scopeOf(registered, scopeCategory, scopeProduct, requestedLevel);
        out.setLevel(scope.level());
        applyScopeLabels(out, scopeCategory, scopeProduct);
        if (scope.rows().isEmpty()) {
            out.setMessage("Nothing is registered at this level yet.");
            return out;
        }

        // Counts, keyed by column|value.
        CountedWindow countedWindow = countLogsCached(org, windowExpr, scope.resourcesInScope(), range);
        Counted counted = countedWindow.counted();
        out.setWindowStart(TimeFormatter.microsToIso(countedWindow.range().startMicros()));
        out.setWindowEnd(TimeFormatter.microsToIso(countedWindow.range().endMicros()));
        out.setComplete(counted.complete());
        if (!counted.complete()) {
            out.setMessage("OpenObserve served only part of this time range, so these counts "
                    + "are a floor rather than a total. Narrow the range for an exact answer.");
        }

        List<AnalyticsResponseDTO.Row> items = new ArrayList<>(scope.rows().size());
        AnalyticsResponseDTO.Row totals = new AnalyticsResponseDTO.Row();
        totals.setLabel("Total");

        for (RowSpec spec : scope.rows()) {
            AnalyticsResponseDTO.Row row = countedRow(spec, counted);
            items.add(row);
            addToTotals(totals, row);
        }
        totals.setOther(remainder(totals));
        totals.setChildCount(items.size());

        out.setItems(items);
        out.setTotals(totals);

        log.debug("Analytics logCounts(level={}, category={}, product={}, window={}): {} row(s) "
                        + "in {} ms", scope.level(), category, product, windowExpr, items.size(),
                (System.nanoTime() - t0) / 1_000_000L);
        return out;
    }

    private static String windowExprOf(String window) {
        return (window == null || window.isBlank()) ? DEFAULT_WINDOW : window.trim();
    }

    /** The category to scope to, or null for every category. */
    private static ResourceCategory scopeCategoryOf(String category) {
        return category == null || category.isBlank()
                ? null : ResourceCategory.fromCode(category);
    }

    /** The product to scope to, or null for every product. */
    private static String scopeProductOf(String product) {
        return (product == null || product.isBlank()) ? null : product.trim();
    }

    /**
     * The requested level, after checking that the scope arguments make sense
     * together: a product, or any level below CATEGORY, needs a category.
     */
    private AnalyticsResponseDTO.Level validatedLevel(ResourceCategory scopeCategory,
            String scopeProduct, String level) {
        if (scopeProduct != null && scopeCategory == null) {
            throw new IllegalArgumentException(
                    "A product only identifies a row within a category, so 'category' is "
                            + "required when 'product' is supplied.");
        }

        AnalyticsResponseDTO.Level requestedLevel = parseLevel(level);
        if (requestedLevel != null && requestedLevel != AnalyticsResponseDTO.Level.CATEGORY
                && scopeCategory == null) {
            throw new IllegalArgumentException("level=" + requestedLevel.name().toLowerCase(Locale.ROOT)
                    + " describes rows within one category, so 'category' is required with it.");
        }
        return requestedLevel;
    }

    /** The ENABLED resources of the org that carry a category. */
    private List<CatalogResource> enabledResources(String org) {
        List<CatalogResource> registered = new ArrayList<>();
        for (CatalogResource resource : safeFindAll(org)) {
            if (resource.getStatus() == ResourceStatus.ENABLED && resource.getCategory() != null) {
                registered.add(resource);
            }
        }
        return registered;
    }

    /** Echo the category and product the counts were scoped to, when given. */
    private void applyScopeLabels(AnalyticsResponseDTO.LogCounts out, ResourceCategory scopeCategory,
            String scopeProduct) {
        if (scopeCategory != null) {
            out.setCategory(scopeCategory.getCode());
            out.setCategoryLabel(scopeCategory.getDisplayName());
        }
        if (scopeProduct != null) {
            out.setProduct(scopeProduct);
            out.setProductLabel(productService.labelFor(scopeCategory, scopeProduct));
        }
    }

    /** One row of the table, with the counts of every identifier its resources bind to. */
    private AnalyticsResponseDTO.Row countedRow(RowSpec spec, Counted counted) {
        AnalyticsResponseDTO.Row row = new AnalyticsResponseDTO.Row();
        row.setKey(spec.key());
        row.setLabel(spec.label());
        row.setChildCount(spec.childCount());
        // The microservices actually counted, whatever level this row is. What a
        // card badges when the click below it lists microservices rather than
        // products.
        row.setLeafCount(spec.resources().size());
        row.setParentKey(spec.parentKey());
        row.setParentLabel(spec.parentLabel());

        Set<String> seen = new LinkedHashSet<>();
        for (CatalogResource resource : spec.resources()) {
            for (String identifier : identifiersOf(resource)) {
                // Deduplicated: two resources in one row can legitimately bind to
                // the same identifier, and adding its counts twice would report
                // double the volume that exists.
                if (!seen.add(identifier)) {
                    continue;
                }
                Tally tally = counted.byIdentifier().get(identifier);
                if (tally != null) {
                    row.setTotal(row.getTotal() + tally.total);
                    row.setInfo(row.getInfo() + tally.info);
                    row.setWarn(row.getWarn() + tally.warn);
                    row.setError(row.getError() + tally.error);
                    row.setDebug(row.getDebug() + tally.debug);
                }
                row.getMatchedOn().add(identifier.replace('\0', '='));
            }
        }
        row.setOther(remainder(row));
        return row;
    }

    /** Add one row's counts to the running totals. */
    private static void addToTotals(AnalyticsResponseDTO.Row totals, AnalyticsResponseDTO.Row row) {
        totals.setTotal(totals.getTotal() + row.getTotal());
        totals.setInfo(totals.getInfo() + row.getInfo());
        totals.setWarn(totals.getWarn() + row.getWarn());
        totals.setError(totals.getError() + row.getError());
        totals.setDebug(totals.getDebug() + row.getDebug());
        totals.setLeafCount(totals.getLeafCount() + row.getLeafCount());
    }

    @Override
    public AnalyticsResponseDTO.Scope scope() {
        String org = openObserveConfig.getOrg();

        AnalyticsResponseDTO.Scope out = new AnalyticsResponseDTO.Scope();
        out.setOrg(org);

        // ENABLED only, matching what the counts report and what the rest of the
        // application shows. Offering a disabled resource as a picker option would
        // advertise a selection whose table is empty by design.
        List<CatalogResource> registered = enabledResources(org);
        if (registered.isEmpty()) {
            out.setCatalogEmpty(true);
            return out;
        }

        // Enum order, so the picker lists categories in the same order as the catalog
        // rail, the Logs tree and the dashboard's own category rows.
        for (ResourceCategory category : ResourceCategory.values()) {
            List<CatalogResource> inCategory = inCategory(registered, category);
            if (inCategory.isEmpty()) {
                // Not an option: selecting it could only ever show an empty table.
                continue;
            }
            out.getCategories().add(scopeCategory(category, inCategory));
        }
        return out;
    }

    /** One category of the picker, with its products and product-less microservices. */
    private AnalyticsResponseDTO.ScopeCategory scopeCategory(ResourceCategory category,
            List<CatalogResource> inCategory) {
        AnalyticsResponseDTO.ScopeCategory dto = new AnalyticsResponseDTO.ScopeCategory();
        dto.setCode(category.getCode());
        dto.setLabel(category.getDisplayName());

        Map<String, List<CatalogResource>> byProduct = new LinkedHashMap<>();
        for (CatalogResource r : inCategory) {
            if (r.getProductCode() == null) {
                dto.getMicroservices().add(resourceOption(r));
            } else {
                byProduct.computeIfAbsent(r.getProductCode(), k -> new ArrayList<>()).add(r);
            }
        }
        byProduct.forEach((code, list) -> {
            AnalyticsResponseDTO.ScopeProduct product = new AnalyticsResponseDTO.ScopeProduct();
            product.setCode(code);
            product.setLabel(list.get(0).getProductLabel() != null
                    ? list.get(0).getProductLabel()
                    : productService.labelFor(category, code));
            list.forEach(r -> product.getMicroservices().add(resourceOption(r)));
            dto.getProducts().add(product);
        });
        return dto;
    }

    /**
     * What Total holds that the named columns do not — trace, and the unspecified
     * bucket.
     *
     * <p>Floored at zero rather than allowed to go negative. It cannot in principle,
     * because every named column is a subset of the same COUNT(*), but a schema that
     * reported a severity twice would otherwise put a negative number on a dashboard,
     * which reads as a bug in the dashboard rather than in the data.</p>
     */
    private static long remainder(AnalyticsResponseDTO.Row row) {
        return Math.max(0L, row.getTotal() - row.getInfo() - row.getWarn()
                - row.getError() - row.getDebug());
    }

    /** One microservice as a picker option, keyed by the id the counts use. */
    private static AnalyticsResponseDTO.ScopeResource resourceOption(CatalogResource resource) {
        AnalyticsResponseDTO.ScopeResource dto = new AnalyticsResponseDTO.ScopeResource();
        dto.setId(resource.getId());
        dto.setName(resource.getResourceName());
        dto.setVersion(resource.getVersion());
        // Built here, not in the UI, so the picker option and the table row that it
        // selects read identically — they are the same string from the same place.
        dto.setLabel(resource.getVersion() == null || resource.getVersion().isBlank()
                ? resource.getResourceName()
                : resource.getResourceName() + " · v" + resource.getVersion());
        return dto;
    }

    // ── scope: what the rows are ─────────────────────────────────────────────

    /**
     * One row, and the registered resources whose logs it aggregates.
     *
     * <p>{@code parentKey}/{@code parentLabel} are the product a MICROSERVICE row sits
     * under, and null everywhere else. They exist for the flattened level, where the
     * product tier is skipped and would otherwise be unrecoverable from the row.</p>
     */
    private record RowSpec(String key, String label, int childCount,
            List<CatalogResource> resources, String parentKey, String parentLabel) {

        /** A row with no product above it — every level except the flattened one. */
        RowSpec(String key, String label, int childCount, List<CatalogResource> resources) {
            this(key, label, childCount, resources, null, null);
        }
    }

    private record Scope(AnalyticsResponseDTO.Level level, List<RowSpec> rows,
            List<CatalogResource> resourcesInScope) {
    }

    /**
     * Build the rows for the requested level, entirely from what is registered.
     *
     * <p>Only categories and products that actually HOLD a registration are listed.
     * Rendering all sixteen products of Applications when one has a microservice
     * would bury the row that carries information behind fifteen zeroes — and a zero
     * that means "nothing registered" reads identically to one meaning "registered
     * but silent", which is the more important distinction to keep visible.</p>
     */
    private Scope scopeOf(List<CatalogResource> registered, ResourceCategory category,
            String product, AnalyticsResponseDTO.Level requestedLevel) {

        // An explicit CATEGORY beats a category argument: the caller asked for the top
        // level, so a leftover category is a filter it did not mean to apply.
        if (requestedLevel == AnalyticsResponseDTO.Level.CATEGORY) {
            return categoryScope(registered);
        }

        // The flattened level: every microservice in the category, product tier skipped.
        // Only when no product was named — a product argument is narrower, and honouring
        // the level over it would silently widen what the caller asked for.
        if (requestedLevel == AnalyticsResponseDTO.Level.MICROSERVICE && category != null
                && product == null) {
            return flatMicroserviceScope(registered, category);
        }

        if (category == null) {
            return categoryScope(registered);
        }

        List<CatalogResource> inCategory = inCategory(registered, category);

        if (product == null) {
            return productScope(inCategory, category);
        }

        // Microservice level.
        List<CatalogResource> inProduct = new ArrayList<>();
        inCategory.forEach(r -> {
            if (product.equals(r.getProductCode())) {
                inProduct.add(r);
            }
        });
        List<RowSpec> rows = new ArrayList<>(inProduct.size());
        for (CatalogResource r : inProduct) {
            rows.add(new RowSpec(r.getId(), microserviceLabel(r), 0, List.of(r)));
        }
        return new Scope(AnalyticsResponseDTO.Level.MICROSERVICE, rows, inProduct);
    }

    /**
     * Product level. Resources with no product are listed as themselves, so a
     * category without a product tier still drills to something rather than
     * showing an empty table.
     */
    private Scope productScope(List<CatalogResource> inCategory, ResourceCategory category) {
        Map<String, List<CatalogResource>> byProduct = new LinkedHashMap<>();
        List<RowSpec> rows = new ArrayList<>();
        for (CatalogResource r : inCategory) {
            if (r.getProductCode() == null) {
                rows.add(new RowSpec(r.getId(), r.getResourceName(), 0, List.of(r)));
            } else {
                byProduct.computeIfAbsent(r.getProductCode(), k -> new ArrayList<>()).add(r);
            }
        }
        byProduct.forEach((code, list) -> {
            String label = list.get(0).getProductLabel() != null
                    ? list.get(0).getProductLabel()
                    : productService.labelFor(category, code);
            rows.add(new RowSpec(code, label, list.size(), list));
        });
        // PRODUCT even when some rows are bare resources: the level names what a
        // click means, and clicking a product-less row has nothing further to open.
        return new Scope(AnalyticsResponseDTO.Level.PRODUCT, rows, inCategory);
    }

    /**
     * A row per category.
     *
     * <p>Insertion-ordered by the enum so the rows match the order of the catalog rail
     * and the Logs tree.</p>
     */
    private Scope categoryScope(List<CatalogResource> registered) {
        Map<ResourceCategory, List<CatalogResource>> byCategory = new LinkedHashMap<>();
        for (ResourceCategory candidate : ResourceCategory.values()) {
            byCategory.put(candidate, new ArrayList<>());
        }
        registered.forEach(r -> byCategory.get(r.getCategory()).add(r));

        List<RowSpec> rows = new ArrayList<>();
        byCategory.forEach((cat, list) -> {
            if (list.isEmpty()) {
                return;
            }
            // A category's CHILD count is its distinct products, or its microservices
            // when it has no product tier — what the number has to mean for a drill to
            // the product level. A caller that skips that level reads leafCount on the
            // row instead, which is always the microservices.
            Set<String> products = new LinkedHashSet<>();
            list.forEach(r -> {
                if (r.getProductCode() != null) {
                    products.add(r.getProductCode());
                }
            });
            int children = products.isEmpty() ? list.size() : products.size();
            rows.add(new RowSpec(cat.getCode(), cat.getDisplayName(), children, list));
        });
        return new Scope(AnalyticsResponseDTO.Level.CATEGORY, rows, registered);
    }

    /**
     * Every microservice in one category, with the product tier flattened away.
     *
     * <p>One row per registered resource, whether it sits under a product or straight
     * under the category — so Applications behaves exactly as Tools does, which is the
     * point. The product it came from travels on the row as {@code parentLabel} rather
     * than being dropped: it is not what the rows are grouped by any more, but it is
     * still the thing that tells two similarly-named services apart.</p>
     *
     * <p>Ordered by product, then by registration order within it. Even a flat list
     * benefits from related services landing next to each other, and it costs nothing
     * — the caller is free to sort by any column afterwards.</p>
     */
    private Scope flatMicroserviceScope(List<CatalogResource> registered,
            ResourceCategory category) {

        List<CatalogResource> inCategory = inCategory(registered, category);

        // Grouped first, then flattened, purely to order the output. Resources with no
        // product go last: they are the exception in a category that has a product tier,
        // and in one that does not they are the only entries and the bucket is the whole
        // list anyway.
        Map<String, List<CatalogResource>> byProduct = new LinkedHashMap<>();
        List<CatalogResource> productless = new ArrayList<>();
        for (CatalogResource r : inCategory) {
            if (r.getProductCode() == null) {
                productless.add(r);
            } else {
                byProduct.computeIfAbsent(r.getProductCode(), k -> new ArrayList<>()).add(r);
            }
        }

        List<RowSpec> rows = new ArrayList<>(inCategory.size());
        byProduct.forEach((code, list) -> {
            String productLabel = list.get(0).getProductLabel() != null
                    ? list.get(0).getProductLabel()
                    : productService.labelFor(category, code);
            for (CatalogResource r : list) {
                rows.add(new RowSpec(r.getId(), microserviceLabel(r), 0, List.of(r),
                        code, productLabel));
            }
        });
        for (CatalogResource r : productless) {
            // Null parent, not the category's own name: this resource genuinely has no
            // product, and labelling it with one would invent a tier the catalog does
            // not record.
            rows.add(new RowSpec(r.getId(), microserviceLabel(r), 0, List.of(r)));
        }

        return new Scope(AnalyticsResponseDTO.Level.MICROSERVICE, rows, inCategory);
    }

    /** The resources registered under one category. */
    private static List<CatalogResource> inCategory(List<CatalogResource> registered,
            ResourceCategory category) {
        List<CatalogResource> out = new ArrayList<>();
        registered.forEach(r -> {
            if (r.getCategory() == category) {
                out.add(r);
            }
        });
        return out;
    }

    /** Name plus version, built in one place so the same resource reads identically
     *  whichever level listed it. */
    private static String microserviceLabel(CatalogResource resource) {
        return resource.getVersion() == null || resource.getVersion().isBlank()
                ? resource.getResourceName()
                : resource.getResourceName() + " · v" + resource.getVersion();
    }

    /**
     * The caller's requested grouping, or null to infer it from the other arguments.
     *
     * <p>Lenient about form — {@code microservice}, {@code MICROSERVICE} and
     * {@code microservices} all work — and strict about content: an unknown value is a
     * 400 rather than a silent fall back to the inferred level, because a caller that
     * asked for a level it misspelled would otherwise get a different level's rows and
     * no indication of it.</p>
     */
    private static AnalyticsResponseDTO.Level parseLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        if (normalised.endsWith("S")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        for (AnalyticsResponseDTO.Level candidate : AnalyticsResponseDTO.Level.values()) {
            if (candidate.name().equals(normalised)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown level: '" + raw
                + "'. Allowed: category, product, microservice.");
    }

    // ── counting ─────────────────────────────────────────────────────────────

    /** The resolved time range, in epoch micros. */
    private record Window(long startMicros, long endMicros) {
    }

    /** Per-identifier severity tally. */
    private static final class Tally {
        private long total;
        private long info;
        private long warn;
        private long error;
        private long debug;
    }

    private record Counted(Map<String, Tally> byIdentifier, boolean complete) {
    }

    /**
     * The identifiers a resource's LOG volume should be counted from.
     *
     * <p>From the catalog's own bindings, so a resource matched on
     * {@code k8s_deployment_name} is counted on that column rather than on a
     * {@code service_name} it does not have. Falls back to
     * {@code service_name = effectiveName()} for a resource registered while
     * validation was switched off, which has no bindings — that is a guess, and it is
     * the same guess the rest of the application already makes for such a resource.</p>
     *
     * <p>Keyed {@code column\0value}: NUL cannot occur in either, so no pair of
     * column and value can collide with a different pair by straddling the join.</p>
     */
    private static Set<String> identifiersOf(CatalogResource resource) {
        Set<String> out = new LinkedHashSet<>();
        for (TelemetryBinding binding : resource.getBindings()) {
            if ("logs".equalsIgnoreCase(binding.signal())
                    && binding.column() != null && binding.value() != null) {
                out.add(binding.column() + '\0' + binding.value());
            }
        }
        if (out.isEmpty()) {
            String name = resource.effectiveName();
            if (name != null && !name.isBlank()) {
                out.add("service_name" + '\0' + name);
            }
        }
        return out;
    }

    /**
     * One grouped query per identifier column.
     *
     * <p>Not one per resource: a hundred registered microservices would be a hundred
     * round trips at roughly 750 ms each, which is a dashboard nobody waits for. The
     * counts arrive as (identifier, severity, n) and are pivoted here.</p>
     */
    private CountedWindow countLogsCached(String org, String windowExpr,
            List<CatalogResource> resources, Window range) {
        if (countsCacheSeconds <= 0) {
            return new CountedWindow(range, countLogs(resources, range));
        }
        // Sorted, so the same set of resources is the same key whatever order the
        // registry returned them in.
        java.util.TreeSet<String> identifiers = new java.util.TreeSet<>();
        for (CatalogResource resource : resources) {
            identifiers.addAll(identifiersOf(resource));
        }
        String key = RefreshingCache.orgKey(org, windowExpr, String.join("\n", identifiers));
        // A refresh re-resolves the window against its own "now", so a refreshed
        // entry covers the same length of time ending at the refresh.
        try {
            return countsCache().get(key, () -> {
                long end = TimeRangeParser.defaultEndMicros();
                Window fresh = new Window(
                        TimeRangeParser.toMicros(windowExpr, TimeRangeParser.defaultStartMicros()), end);
                Counted counted = countLogs(resources, fresh);
                if (!counted.complete()) {
                    // A partial answer is returned to this caller but not reused.
                    throw new PartialCounts(new CountedWindow(fresh, counted));
                }
                return new CountedWindow(fresh, counted);
            });
        } catch (PartialCounts e) {
            return e.result;
        }
    }

    /** Carries an incomplete count past the cache without caching it. */
    private static final class PartialCounts extends RuntimeException {
        private final transient CountedWindow result;
        PartialCounts(CountedWindow result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    private RefreshingCache<CountedWindow> countsCache() {
        return countsCache.updateAndGet(existing -> {
            if (existing != null) {
                return existing;
            }
            return new RefreshingCache<>("analytics log counts",
                    Duration.ofSeconds(countsCacheSeconds), Duration.ofSeconds(15));
        });
    }

    private Counted countLogs(List<CatalogResource> resources, Window range) {
        Map<String, Set<String>> byColumn = valuesByColumn(resources);

        Map<String, Tally> byIdentifier = new LinkedHashMap<>();
        boolean complete = true;
        String stream = SqlSafety.validateIdentifier(openObserveConfig.getLogsStream());

        for (Map.Entry<String, Set<String>> entry : byColumn.entrySet()) {
            String column;
            try {
                // Validated, not escaped. A column name reaches SQL as an identifier,
                // and the catalog only ever records columns it has proven exist on the
                // stream — but this is the boundary, so it is checked here too.
                column = SqlSafety.validateIdentifier(entry.getKey());
            } catch (RuntimeException e) {
                log.warn("Analytics skipped catalog column '{}': {}", entry.getKey(), e.getMessage());
                continue;
            }

            for (List<String> batch : batches(new ArrayList<>(entry.getValue()))) {
                if (!countBatch(stream, column, batch, range, byIdentifier)) {
                    complete = false;
                }
            }
        }
        return new Counted(byIdentifier, complete);
    }

    /** column → the values to count under it, from each resource's identifiers. */
    private static Map<String, Set<String>> valuesByColumn(List<CatalogResource> resources) {
        Map<String, Set<String>> byColumn = new LinkedHashMap<>();
        for (CatalogResource resource : resources) {
            for (String identifier : identifiersOf(resource)) {
                int split = identifier.indexOf('\0');
                String column = identifier.substring(0, split);
                String value = identifier.substring(split + 1);
                byColumn.computeIfAbsent(column, k -> new LinkedHashSet<>()).add(value);
            }
        }
        return byColumn;
    }

    private boolean countBatch(String stream, String column, List<String> values,
            Window range, Map<String, Tally> byIdentifier) {

        StringBuilder inList = quotedInList(values);

        String sql = "SELECT \"" + column + "\" AS k, severity AS sev, COUNT(*) AS n "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + range.startMicros() + " "
                + "AND _timestamp <= " + range.endMicros() + " "
                + "AND \"" + column + "\" IN (" + inList + ") "
                + "GROUP BY 1, 2";

        if (log.isDebugEnabled()) {
            log.debug("Analytics log-count SQL: {}", sql);
        }

        OpenObserveClientService.SearchOutcome outcome;
        String previousOrg = OrgContext.get();
        try {
            outcome = openObserve.searchDetailed(sql, range.startMicros(), range.endMicros(),
                    0, MAX_GROUPS, "logs");
        } catch (Exception e) {
            // One failed batch is reported as incomplete rather than thrown: the other
            // batches hold real counts, and a dashboard that renders nothing because
            // one column failed is less useful than one that says so.
            log.warn("Analytics log-count query failed for column '{}': {}", column, e.getMessage());
            return false;
        } finally {
            restoreOrg(previousOrg);
        }

        tallyHits(outcome.hits(), column, byIdentifier);
        // is_partial means rows were missed, so every count from this batch is a floor.
        return !outcome.partial();
    }

    /** {@code 'v1', 'v2', ...} with each value escaped as a SQL string literal. */
    private static StringBuilder quotedInList(List<String> values) {
        StringBuilder inList = new StringBuilder();
        for (String value : values) {
            if (!inList.isEmpty()) {
                inList.append(", ");
            }
            inList.append('\'').append(SqlSafety.escapeStringLiteral(value)).append('\'');
        }
        return inList;
    }

    /** Puts back the org that was current before the query, or clears it if there was none. */
    private static void restoreOrg(String previousOrg) {
        if (previousOrg == null) {
            OrgContext.clear();
        } else {
            OrgContext.set(previousOrg);
        }
    }

    /** Adds each (identifier, severity, n) row with a key and a positive count to its tally. */
    private static void tallyHits(List<Map<String, Object>> hits, String column, Map<String, Tally> byIdentifier) {
        for (Map<String, Object> hit : hits) {
            Object keyValue = hit.get("k");
            long n = asLong(hit.get("n"));
            if (keyValue != null && n > 0) {
                Tally tally = byIdentifier.computeIfAbsent(
                        column + '\0' + keyValue, k -> new Tally());
                tally.total += n;
                addToSeverity(tally, canonicalSeverity(hit.get("sev")), n);
            }
        }
    }

    private static void addToSeverity(Tally tally, String severity, long n) {
        switch (severity) {
            case "info" -> tally.info += n;
            case "warn" -> tally.warn += n;
            case "error" -> tally.error += n;
            case SEV_DEBUG -> tally.debug += n;
            default -> {
                // trace and unspecified count toward the total only, and are reported
                // as `other` so a row still reconciles.
            }
        }
    }

    /**
     * Collapse a raw severity onto the buckets the dashboard reports.
     *
     * <p>Necessary, not defensive: this stream carries {@code WARN} and
     * {@code WARNING} as separate values, and its single largest bucket is the
     * literal string {@code "0"} — 1.38 million rows in one hour. Treating raw
     * values as levels would split warnings in two and silently promote the
     * unspecified bucket into whichever column matched first.</p>
     */
    private static String canonicalSeverity(Object raw) {
        if (raw == null) {
            return SEV_UNSPECIFIED;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (isUnspecifiedSeverity(value)) {
            return SEV_UNSPECIFIED;
        }
        if (value.startsWith("info") || "notice".equals(value)) {
            return "info";
        }
        if (value.startsWith("warn")) {
            return "warn";
        }
        // Anything at or above ERROR is an error for counting purposes: an operator
        // scanning for problems wants FATAL included, not filed under "other".
        if (isErrorSeverity(value)) {
            return "error";
        }
        // DEBUG only. TRACE is deliberately left in `other`: it is a rounding error on
        // this deployment, and folding it in here would make a column labelled Debug
        // mean "debug and trace" without saying so.
        if (isDebugSeverity(value)) {
            return SEV_DEBUG;
        }
        return "other";
    }

    private static boolean isUnspecifiedSeverity(String value) {
        return value.isEmpty() || "0".equals(value) || SEV_UNSPECIFIED.equals(value);
    }

    /** At or above ERROR, by prefix. */
    private static boolean isErrorSeverity(String value) {
        for (String prefix : ERROR_SEVERITY_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDebugSeverity(String value) {
        return value.startsWith(SEV_DEBUG) || "dbg".equals(value) || "fine".equals(value);
    }

    private List<List<String>> batches(List<String> values) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i += MAX_IDENTIFIERS_PER_QUERY) {
            out.add(values.subList(i, Math.min(values.size(), i + MAX_IDENTIFIERS_PER_QUERY)));
        }
        return out;
    }

    private List<CatalogResource> safeFindAll(String org) {
        try {
            return registry.findAll(org);
        } catch (Exception e) {
            log.error("Analytics could not read the Product Catalog for org '{}': {}",
                    org, e.getMessage(), e);
            return List.of();
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
