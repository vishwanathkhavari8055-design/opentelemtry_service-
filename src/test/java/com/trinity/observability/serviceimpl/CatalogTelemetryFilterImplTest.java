package com.trinity.observability.serviceimpl;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.config.ProductCatalogProperties.FilterMode;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.registry.InMemoryProductCatalogRegistry;
import com.trinity.observability.registry.NoOpCatalogSnapshotStore;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.CatalogTelemetryFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CatalogTelemetryFilterImpl} — the SQL the Product
 * Catalog contributes to every Logs / Traces / Metrics query.
 *
 * <p>The assertions are about SHAPE, because the shape is what protects the
 * existing screens: an empty predicate on an empty registry (nothing changes
 * until something is registered), the {@code IS NULL} guard on the deny-list
 * (without it every row lacking the column disappears), and exact-case matching
 * (this deployment runs services whose names differ only in case).</p>
 */
class CatalogTelemetryFilterImplTest {

    private static final String ORG = "default";

    private ProductCatalogRegistry registry;
    private ProductCatalogProperties properties;
    private CatalogTelemetryFilterImpl filter;

    @BeforeEach
    void setUp() throws Exception {
        registry = new InMemoryProductCatalogRegistry(new NoOpCatalogSnapshotStore());
        properties = new ProductCatalogProperties();
        // TTL 0 — these assertions are about the predicate SHAPE, so every call
        // reads the registry directly. Caching has its own test.
        filter = new CatalogTelemetryFilterImpl(registry, config(ORG), properties, 0L);
    }

    // ── Nothing registered ───────────────────────────────────────────────────

    @Test
    void anEmptyRegistryFiltersNothing() {
        assertEquals("", filter.sqlPredicate("logs"));
        assertEquals("", filter.sqlPredicate("traces"));
        assertEquals("", filter.sqlPredicate("metrics"));
    }

    @Test
    void onlyEnabledResourcesMeansNothingToExcludeInTheDefaultMode() {
        register("IoTOpsApiSvc", ResourceStatus.ENABLED,
                new TelemetryBinding("logs", "default", "service_name", "IoTOpsApiSvc"));

        assertEquals("", filter.sqlPredicate("logs"),
                "EXCLUDE_DISABLED subtracts; with nothing disabled there is nothing to subtract");
    }

    // ── EXCLUDE_DISABLED ─────────────────────────────────────────────────────

    @Test
    void aDisabledResourceIsExcludedByItsBoundColumn() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));

        String predicate = filter.sqlPredicate("logs");

        assertEquals("(service_name IS NULL OR service_name NOT IN ('redis-master'))", predicate);
    }

    @Test
    void theIsNullGuardIsPresentSoRowsWithoutTheColumnSurvive() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));

        // Without this, `NULL NOT IN (…)` evaluates to NULL and every log line
        // that has no service_name at all silently disappears.
        assertTrue(filter.sqlPredicate("logs").contains("service_name IS NULL OR"));
    }

    @Test
    void severalDisabledResourcesShareOneInList() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));
        register("kafkaui", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "kafkaui"));

        assertEquals("(service_name IS NULL OR service_name NOT IN ('redis-master', 'kafkaui'))",
                filter.sqlPredicate("logs"));
    }

    @Test
    void twoColumnsAreAndedTogetherBecauseBothMustBeExcluded() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("traces", "default", "service_name", "redis-master"),
                new TelemetryBinding("traces", "default", "db_name", "cache"));

        String predicate = filter.sqlPredicate("traces");

        assertTrue(predicate.contains("service_name NOT IN ('redis-master')"), predicate);
        assertTrue(predicate.contains("db_name NOT IN ('cache')"), predicate);
        assertTrue(predicate.contains(" AND "), "both exclusions apply: " + predicate);
    }

    @Test
    void aSignalWithNoBindingFallsBackToExcludingByName() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("traces", "default", "db_name", "cache"));

        // The traces binding cannot be used against the logs stream — db_name is a
        // span column. But "no telemetry for disabled resources shown anywhere"
        // means logs still have to be excluded, so the resource's name is applied
        // to the one column present on every stream in all three signals.
        assertEquals("(service_name IS NULL OR service_name NOT IN ('redis-master'))",
                filter.sqlPredicate("logs"));
        assertEquals("(db_name IS NULL OR db_name NOT IN ('cache'))",
                filter.sqlPredicate("traces"),
                "where a real binding exists it is used in preference to the fallback");
    }

    @Test
    void metricsIsRestrictedToServiceNameBecauseEveryMetricIsItsOwnStream() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("metrics", "jvm_memory_used", "service_name", "redis-master"),
                new TelemetryBinding("metrics", "jvm_memory_used", "db_name", "cache"));

        String predicate = filter.sqlPredicate("metrics");

        assertTrue(predicate.contains("service_name"), predicate);
        assertFalse(predicate.contains("db_name"),
                "db_name is not on every metric stream; naming it would break the query: " + predicate);
    }

    @Test
    void anUnboundDisabledResourceStillFiltersOnItsName() {
        // The state a config seed, or a registration made with validation off, is in.
        register("PostgreSQL", ResourceStatus.DISABLED);

        assertEquals("(service_name IS NULL OR service_name NOT IN ('PostgreSQL'))",
                filter.sqlPredicate("logs"),
                "a Disabled row whose telemetry kept appearing would be a broken switch");
    }

    @Test
    void singleQuotesInAnIdentifierAreEscaped() {
        register("it's-a-service", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "it's-a-service"));

        assertTrue(filter.sqlPredicate("logs").contains("'it''s-a-service'"),
                filter.sqlPredicate("logs"));
    }

    @Test
    void matchingIsExactCaseBecauseCaseVariantsAreDifferentServicesHere() {
        register("IoTOpsApiSvc", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "IoTOpsApiSvc"));

        assertFalse(filter.permits("logs", "service_name", "IoTOpsApiSvc"));
        assertTrue(filter.permits("logs", "service_name", "iotopsapisvc"),
                "the lowercase name is the dev cluster's scraper, a different service");
    }

    // ── ENABLED_ONLY ─────────────────────────────────────────────────────────

    @Test
    void enabledOnlyModeStillFiltersNothingWhileTheRegistryIsEmpty() {
        properties.getFilter().setMode(FilterMode.ENABLED_ONLY);

        assertEquals("", filter.sqlPredicate("logs"),
                "switching the mode on must not blank every screen before the first registration");
    }

    @Test
    void enabledOnlyModeProducesAnAllowList() {
        properties.getFilter().setMode(FilterMode.ENABLED_ONLY);
        register("IoTOpsApiSvc", ResourceStatus.ENABLED,
                new TelemetryBinding("logs", "default", "service_name", "IoTOpsApiSvc"));
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));

        assertEquals("(service_name IN ('IoTOpsApiSvc'))", filter.sqlPredicate("logs"));
    }

    @Test
    void enabledOnlyModeOrsAcrossColumnsBecauseOneMatchIsEnough() {
        properties.getFilter().setMode(FilterMode.ENABLED_ONLY);
        register("IoTOpsApiSvc", ResourceStatus.ENABLED,
                new TelemetryBinding("traces", "default", "service_name", "IoTOpsApiSvc"));
        register("PostgreSQL", ResourceStatus.ENABLED,
                new TelemetryBinding("traces", "default", "db_name", "orders"));

        String predicate = filter.sqlPredicate("traces");

        assertTrue(predicate.contains(" OR "), "a span belongs if ANY identifier matches: " + predicate);
        assertTrue(predicate.contains("service_name IN ('IoTOpsApiSvc')"), predicate);
        assertTrue(predicate.contains("db_name IN ('orders')"), predicate);
    }

    @Test
    void enabledOnlyModeWithEverythingDisabledFiltersNothingAndSaysSo() {
        properties.getFilter().setMode(FilterMode.ENABLED_ONLY);
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));

        assertEquals("", filter.sqlPredicate("logs"),
                "a blank screen with no explanation is the wrong failure mode for a mode switch");
    }

    // ── Switches ─────────────────────────────────────────────────────────────

    @Test
    void theFilterKillSwitchDisablesTheIntegrationButNotTheRegistry() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));
        properties.getFilter().setEnabled(false);

        assertEquals("", filter.sqlPredicate("logs"));
        assertTrue(filter.permits("logs", "service_name", "redis-master"));
        assertEquals(1, registry.findAll(ORG).size(), "the registration is untouched");
    }

    @Test
    void theMasterSwitchDisablesTheIntegrationToo() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));
        properties.setEnabled(false);

        assertEquals("", filter.sqlPredicate("logs"));
    }

    // ── permits / retainPermitted ────────────────────────────────────────────

    @Test
    void retainPermittedDropsDisabledNamesAndKeepsOrder() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));

        List<String> kept = filter.retainPermitted("logs", "service_name",
                List.of("IoTOpsApiSvc", "redis-master", "kafka1"));

        assertEquals(List.of("IoTOpsApiSvc", "kafka1"), kept);
    }

    @Test
    void permitsIgnoresColumnsThatAreNotFilterableForTheSignal() {
        register("redis-master", ResourceStatus.DISABLED,
                new TelemetryBinding("logs", "default", "service_name", "redis-master"));

        assertTrue(filter.permits("logs", "some_other_column", "redis-master"));
    }

    @Test
    void permitAllIsInertAndUsableAsADefault() {
        CatalogTelemetryFilter permissive = CatalogTelemetryFilter.permitAll();

        assertEquals("", permissive.sqlPredicate("logs"));
        assertTrue(permissive.permits("logs", "service_name", "anything"));
        assertEquals(List.of("a", "b"), permissive.retainPermitted("logs", "service_name", List.of("a", "b")));
        assertEquals(List.of(), permissive.retainPermitted("logs", "service_name", null));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void register(String name, ResourceStatus status, TelemetryBinding... bindings) {
        CatalogResource resource = new CatalogResource();
        resource.setId(UUID.randomUUID().toString());
        resource.setCategory(ResourceCategory.TOOLS);
        resource.setResourceName(name);
        resource.setStatus(status);
        resource.setBindings(List.of(bindings));
        resource.setRegisteredAtMicros(System.nanoTime());
        registry.save(ORG, resource);
    }

    /**
     * An {@link OpenObserveConfig} whose {@code getOrg()} answers a fixed value.
     * Its fields are {@code @Value}-injected, so a test builds one by reflection
     * rather than through a constructor that does not exist.
     */
    private static OpenObserveConfig config(String org) throws Exception {
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "org", org);
        setField(config, "host", "http://localhost");
        setField(config, "logsStream", "default");
        setField(config, "tracesStream", "default");
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
