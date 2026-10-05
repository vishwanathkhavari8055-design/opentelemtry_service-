package com.trinity.observability.serviceimpl;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.ProductCatalogRequestDTO;
import com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.exception.ResourceConflictException;
import com.trinity.observability.exception.ResourceNotFoundException;
import com.trinity.observability.exception.TelemetryNotFoundException;
import com.trinity.observability.registry.InMemoryProductCatalogRegistry;
import com.trinity.observability.registry.NoOpCatalogSnapshotStore;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.TelemetryDiscoveryService;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProductCatalogServiceImpl}: the rules that connect the
 * registry to OpenObserve discovery.
 *
 * <p>Uses the real in-memory registry (its semantics are what the rules rely on)
 * and a scripted {@link TelemetryDiscoveryService}, so no test touches the
 * network.</p>
 */
class ProductCatalogServiceImplTest {

    private ProductCatalogRegistry registry;
    private ScriptedDiscovery discovery;
    private ProductCatalogProperties properties;
    private ProductCatalogServiceImpl service;

    /**
     * A catalog with no product tier.
     *
     * <p>Which is exactly the shape these tests describe: they register resources by
     * bare name, and that has to keep working. `hasProducts` returning false is what
     * makes `product` optional, so the pre-product behaviour is preserved rather than
     * every one of these cases needing a product invented for it.</p>
     */
    private static final com.trinity.observability.service.CatalogProductService NO_PRODUCTS =
            new com.trinity.observability.service.CatalogProductService() {
                @Override
                public boolean hasProducts(
                        com.trinity.observability.dto.productcatalog.ResourceCategory category) {
                    return false;
                }

                @Override
                public com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO.ProductList list(
                        com.trinity.observability.dto.productcatalog.ResourceCategory category) {
                    return new com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO.ProductList();
                }

                @Override
                public java.util.Optional<String> resolveCode(
                        com.trinity.observability.dto.productcatalog.ResourceCategory category,
                        String raw) {
                    return java.util.Optional.empty();
                }

                @Override
                public String labelFor(
                        com.trinity.observability.dto.productcatalog.ResourceCategory category,
                        String code) {
                    return code;
                }

                @Override
                public java.util.List<String> codesFor(
                        com.trinity.observability.dto.productcatalog.ResourceCategory category) {
                    return java.util.List.of();
                }
            };

    @BeforeEach
    void setUp() throws Exception {
        registry = new InMemoryProductCatalogRegistry(new NoOpCatalogSnapshotStore());
        discovery = new ScriptedDiscovery();
        properties = new ProductCatalogProperties();
        service = new ProductCatalogServiceImpl(registry, discovery, config(), properties,
                NO_PRODUCTS);
    }

    // ── Categories ───────────────────────────────────────────────────────────

    /**
     * The taxonomy, in ORDER — including Mobile Application.
     *
     * <p>Order is asserted, not just membership, because the enum's order is what the
     * category rail and the Logs/Traces tree render. Mobile Application sits beside
     * Applications and AI Services rather than at the end: those three are the
     * categories that carry a product list, and a reader scanning the rail should
     * find them together.</p>
     */
    @Test
    void everyCategoryIsAlwaysListedInOrder() {
        ProductCatalogResponseDTO.CategoryList categories = service.listCategories();

        assertEquals(7, categories.getItems().size());
        List<String> codes = new ArrayList<>();
        categories.getItems().forEach(item -> codes.add(item.getCode()));
        assertEquals(List.of("applications", "platform", "ai-services", "mobile-application",
                        "tools", "databases", "dlh"),
                codes, "the taxonomy is fixed and mirrors the UI's own category table");
    }

    @Test
    void categoryCountsReflectRegistrations() {
        discovery.found = true;
        register("databases", "PostgreSQL");
        register("databases", "MongoDB");
        service.disable(idOf("MongoDB"));

        ProductCatalogResponseDTO.Category databases = service.listCategories().getItems().stream()
                .filter(item -> "databases".equals(item.getCode())).findFirst().orElseThrow();

        assertEquals(2, databases.getRegisteredCount());
        assertEquals(1, databases.getEnabledCount());
    }

    // ── Validate ─────────────────────────────────────────────────────────────

    @Test
    void validateReportsTheRequiredWordingWhenNoTelemetryExists() {
        discovery.found = false;

        ProductCatalogResponseDTO.ValidationResult result = validate("databases", "Nowhere");

        assertFalse(result.isFound());
        assertEquals("Resource not found in OpenObserve. Please ensure telemetry is being "
                + "emitted before registration.", result.getMessage());
        assertNotNull(result.getWindowStart(), "the searched window is stated so the miss is falsifiable");
        assertNotNull(result.getWindowEnd());
    }

    @Test
    void validateDoesNotThrowForAMissBecauseTheDialogHasToRenderTheReason() {
        discovery.found = false;

        assertNotNull(validate("tools", "Nowhere"));
    }

    @Test
    void validateReportsTheSignalsThatHeldData() {
        discovery.found = true;
        discovery.signals = List.of("logs", "metrics");

        ProductCatalogResponseDTO.ValidationResult result = validate("tools", "kafka1");

        assertTrue(result.isFound());
        assertTrue(result.getSignals().isLogs());
        assertFalse(result.getSignals().isTraces());
        assertTrue(result.getSignals().isMetrics());
    }

    @Test
    void validateSurfacesADifferentTelemetrySpellingBecauseThatIsWhatGetsRegistered() {
        discovery.found = true;
        discovery.resolvedName = "PostgreSQL";

        ProductCatalogResponseDTO.ValidationResult result = validate("databases", "postgresql");

        assertEquals("PostgreSQL", result.getResolvedName());
        assertTrue(result.getMessage().contains("PostgreSQL"), result.getMessage());
    }

    @Test
    void validateFlagsAnExistingRegistrationSoRegisterIsNotOffered() {
        discovery.found = true;
        register("databases", "PostgreSQL");

        assertTrue(validate("databases", "PostgreSQL").isAlreadyRegistered());
        assertTrue(validate("databases", "postgresql").isAlreadyRegistered(),
                "the duplicate check folds case, so the flag has to as well");
    }

    @Test
    void validateSaysWhatIsWrongWhenOpenObserveIsOff() {
        discovery.supported = false;

        ProductCatalogResponseDTO.ValidationResult result = validate("databases", "PostgreSQL");

        assertFalse(result.isSupported());
        assertFalse(result.isFound());
        assertTrue(result.getMessage().contains("openobserve.enabled"),
                "'not found' would be a claim about telemetry we cannot make: " + result.getMessage());
    }

    @Test
    void anUnknownCategoryIsRejectedWithTheAllowedValues() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validate("Warehouses", "PostgreSQL"));

        assertTrue(thrown.getMessage().contains("databases"), thrown.getMessage());
    }

    @Test
    void aBlankResourceNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validate("databases", "   "));
    }

    // ── Register ─────────────────────────────────────────────────────────────

    @Test
    void registeringStoresTheResolvedIdentifierNotWhatWasTyped() {
        discovery.found = true;
        discovery.resolvedName = "PostgreSQL";
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "PostgreSQL"));

        ProductCatalogResponseDTO.Resource created = register("databases", "postgresql");

        assertEquals("postgresql", created.getResourceName(), "the display name is what the operator typed");
        assertEquals("PostgreSQL", created.getResolvedName(), "the filter keys off the telemetry spelling");
        assertEquals("ENABLED", created.getStatus());
        assertEquals(1, created.getBindings().size());
        assertEquals("service_name", created.getBindings().get(0).getColumn());
    }

    @Test
    void registeringWithoutTelemetryIsRefusedWithTheRequiredWording() {
        discovery.found = false;

        TelemetryNotFoundException thrown = assertThrows(TelemetryNotFoundException.class,
                () -> register("databases", "Nowhere"));

        assertEquals("Resource not found in OpenObserve. Please ensure telemetry is being "
                + "emitted before registration.", thrown.getMessage());
        assertTrue(registry.findAll("default").isEmpty(), "nothing is stored on a refusal");
    }

    @Test
    void aDuplicateInTheSameCategoryIsRefused() {
        discovery.found = true;
        register("databases", "PostgreSQL");

        assertThrows(ResourceConflictException.class, () -> register("databases", "postgresql"));
    }

    @Test
    void theDuplicateCheckRunsBeforeDiscoverySoNoQueryIsWasted() {
        discovery.found = true;
        register("databases", "PostgreSQL");
        int callsAfterFirstRegistration = discovery.discoverCalls;

        assertThrows(ResourceConflictException.class, () -> register("databases", "PostgreSQL"));

        assertEquals(callsAfterFirstRegistration, discovery.discoverCalls);
    }

    @Test
    void theSameNameUnderTwoCategoriesIsAllowed() {
        discovery.found = true;
        register("databases", "PostgreSQL");

        assertNotNull(register("tools", "PostgreSQL"));
        assertEquals(2, registry.findAll("default").size());
    }

    @Test
    void registeringDisabledIsPossibleForAPreRegistration() {
        discovery.found = true;
        ProductCatalogRequestDTO.Register body = new ProductCatalogRequestDTO.Register();
        body.setCategory("tools");
        body.setResourceName("kafka1");
        body.setStatus("DISABLED");

        assertEquals("DISABLED", service.register(body).getStatus());
    }

    @Test
    void validationCanBeTurnedOffToPreRegisterAResourceWhoseAgentIsNotDeployed() {
        properties.getValidation().setRequired(false);
        discovery.found = false;

        ProductCatalogResponseDTO.Resource created = register("tools", "Trino");

        assertNotNull(created);
        assertTrue(created.getBindings().isEmpty());
        assertNull(created.getLastSeen());
    }

    @Test
    void registeringIsRefusedWhenValidationIsRequiredButOpenObserveIsOff() {
        discovery.supported = false;

        assertThrows(IllegalStateException.class, () -> register("tools", "kafka1"));
    }

    // ── Enable / disable / remove ────────────────────────────────────────────

    @Test
    void disablingClearsTheSignalChecklistBecauseNothingIsVisibleAnyMore() {
        discovery.found = true;
        discovery.signals = List.of("logs", "traces", "metrics");
        String id = register("tools", "kafka1").getId();

        ProductCatalogResponseDTO.WriteResult result = service.disable(id);

        assertEquals("DISABLED", result.getResource().getStatus());
        assertFalse(result.getResource().getSignals().isLogs());
        assertFalse(result.getResource().getSignals().isTraces());
        assertFalse(result.getResource().getSignals().isMetrics());
        assertEquals(List.of("logs", "traces", "metrics"),
                result.getResource().getSignals().getAvailable(),
                "the raw discovery result is kept so 'switched off' stays distinguishable "
                        + "from 'never emitted'");
    }

    @Test
    void enablingRestoresTheChecklistWithoutRevalidating() {
        discovery.found = true;
        discovery.signals = List.of("logs");
        String id = register("tools", "kafka1").getId();
        service.disable(id);
        int callsBeforeEnable = discovery.discoverCalls;

        ProductCatalogResponseDTO.WriteResult result = service.enable(id);

        assertEquals("ENABLED", result.getResource().getStatus());
        assertTrue(result.getResource().getSignals().isLogs());
        assertEquals(callsBeforeEnable, discovery.discoverCalls, "re-enabling is not a re-registration");
    }

    @Test
    void togglingTwiceIsIdempotent() {
        discovery.found = true;
        String id = register("tools", "kafka1").getId();

        service.disable(id);
        assertEquals("DISABLED", service.disable(id).getResource().getStatus());
    }

    @Test
    void removingUnregistersAndSaysTheTelemetryIsUntouched() {
        discovery.found = true;
        String id = register("tools", "kafka1").getId();

        ProductCatalogResponseDTO.WriteResult result = service.remove(id);

        assertTrue(result.isOk());
        assertTrue(result.getMessage().contains("untouched"), result.getMessage());
        assertTrue(registry.findAll("default").isEmpty());
    }

    @Test
    void operationsOnAnUnknownIdAre404() {
        assertThrows(ResourceNotFoundException.class, () -> service.get("no-such-id"));
        assertThrows(ResourceNotFoundException.class, () -> service.enable("no-such-id"));
        assertThrows(ResourceNotFoundException.class, () -> service.disable("no-such-id"));
        assertThrows(ResourceNotFoundException.class, () -> service.remove("no-such-id"));
    }

    // ── List ─────────────────────────────────────────────────────────────────

    @Test
    void listFiltersByCategoryStatusAndSearch() {
        discovery.found = true;
        register("databases", "PostgreSQL");
        register("tools", "kafka1");
        register("tools", "kafka2");
        service.disable(idOf("kafka2"));

        assertEquals(3, list(null, null, null).getTotal());
        assertEquals(2, list("tools", null, null).getTotal());
        assertEquals(1, list("tools", "DISABLED", null).getTotal());
        assertEquals(2, list(null, null, "kafka").getTotal());
        assertEquals(1, list(null, null, "postgres").getTotal(), "search folds case");
        assertEquals(3, list("ALL", "ALL", null).getTotal(), "ALL means no filter");
    }

    @Test
    void listReportsTheActiveFilterModeSoTheUiCanExplainDisabled() {
        assertEquals("EXCLUDE_DISABLED", list(null, null, null).getFilterMode());
    }

    @Test
    void lastSeenIsServedFromCacheWithinTheTtl() {
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");

        list(null, null, null);
        list(null, null, null);

        assertEquals(0, discovery.lastSeenCalls,
                "a fresh registration is inside the TTL, so polling costs no queries");
    }

    @Test
    void refreshForcesALivenessRecheck() {
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");

        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setRefresh(true);
        service.list(filter);

        assertEquals(1, discovery.lastSeenCalls);
    }

    @Test
    void aResourceWithNoBindingsIsGivenADiscoveryPassWhenItsCheckGoesStale() {
        // The state a config seed is in: a name, and nothing proven yet.
        properties.getValidation().setRequired(false);
        discovery.found = false;
        register("tools", "Trino");
        int callsAfterRegistration = discovery.discoverCalls;

        // Within the TTL nothing is retried — a resource whose telemetry never
        // arrives must not cost a probe chain on every poll.
        list(null, null, null);
        assertEquals(callsAfterRegistration, discovery.discoverCalls);

        discovery.found = true;
        discovery.resolvedName = "trino-coordinator";
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "trino-coordinator"));

        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setRefresh(true);
        ProductCatalogResponseDTO.ResourceList listed = service.list(filter);

        assertEquals(callsAfterRegistration + 1, discovery.discoverCalls);
        assertEquals("trino-coordinator", listed.getItems().get(0).getResolvedName(),
                "an unvalidated resource acquires its bindings once telemetry appears");
        assertEquals(1, listed.getItems().get(0).getBindings().size());
    }

    @Test
    void discoveryRetriesForUnboundResourcesAreRateLimitedPerRequest() {
        properties.getValidation().setRequired(false);
        discovery.found = false;
        for (int i = 0; i < 8; i++) {
            register("tools", "absent-" + i);
        }
        int callsAfterRegistration = discovery.discoverCalls;

        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setRefresh(true);
        service.list(filter);

        // Discovery is a chain of queries per name and cannot be batched, so an
        // unvalidated catalog must not turn one poll into a query storm.
        assertEquals(callsAfterRegistration + 5, discovery.discoverCalls);
    }

    @Test
    void aLivenessRefreshDoesNotWriteToTheChangeLog() {
        // Last Seen is a cache and this path runs on every list past the TTL, so
        // persisting it grew the durable change log by one row per resource per
        // poll — forever, to record nothing an operator ever changed.
        RecordingRegistry recording = new RecordingRegistry();
        ProductCatalogServiceImpl service2 = newService(recording);

        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        ProductCatalogRequestDTO.Register body = new ProductCatalogRequestDTO.Register();
        body.setCategory("tools");
        body.setResourceName("kafka1");
        service2.register(body);

        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setRefresh(true);
        service2.list(filter);

        assertEquals(1, recording.persistingUpdates,
                "exactly the durable updates — the liveness stamp is not one of them");
    }

    @Test
    void aLateBindingDiscoveryIsPersistedBecauseItChangesWhatGetsFiltered() {
        RecordingRegistry recording = new RecordingRegistry();
        ProductCatalogServiceImpl service2 = newService(recording);

        properties.getValidation().setRequired(false);
        discovery.found = false;
        ProductCatalogRequestDTO.Register body = new ProductCatalogRequestDTO.Register();
        body.setCategory("tools");
        body.setResourceName("Trino");
        service2.register(body);
        int before = recording.persistingUpdates;

        discovery.found = true;
        discovery.resolvedName = "trino-coordinator";
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "trino-coordinator"));
        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setRefresh(true);
        service2.list(filter);

        assertEquals(before + 1, recording.persistingUpdates,
                "bindings decide what is filtered, so they are durable state");
    }

    @Test
    void aLivenessFailureLeavesTheCatalogListable() {
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");
        discovery.failLastSeen = true;

        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setRefresh(true);

        assertEquals(1, service.list(filter).getTotal());
    }

    @Test
    void theFirstListOfAnOrgWaitsForTheRefresh() {
        // Cold process: Last Seen is whatever registration wrote, which can be days
        // old. Answering instantly here would be fast and wrong.
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");
        expire("kafka1");

        list(null, null, null);

        assertEquals(1, discovery.lastSeenCalls,
                "the first list of an org is answered the slow way, on purpose");
    }

    @Test
    void theFirstListJoinsTheBootWarmupInsteadOfRefreshingAgain() {
        // After a restart the default org is warmed at boot, so the first person to
        // open Logs no longer pays the whole Last Seen refresh on their request.
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");
        expire("kafka1");

        service.prewarmLiveness();
        assertEquals(1, list(null, null, null).getTotal());

        assertEquals(1, discovery.lastSeenCalls,
                "the boot warm-up and the first list share one refresh");
    }

    @Test
    void aStalePollIsAnsweredWithoutWaitingForTheRefresh() {
        // The endpoint is POLLED — by the catalog screen and by step 1 of the alert
        // wizard — at the same cadence as the TTL, so before this nearly every poll
        // waited on a full re-query of every binding. Measured at 6-14s against 59
        // resources, which is what made the wizard slow to open.
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");

        expire("kafka1");
        list(null, null, null);              // warms the org, synchronously

        // Now hold the refresh open. If the list still waited on it, this call could
        // not return until the latch is released — so returning IS the assertion.
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        discovery.lastSeenGate = held;
        expire("kafka1");
        try {
            assertEquals(1, list(null, null, null).getTotal(),
                    "a stale poll serves the cached Last Seen and refreshes behind it");
        } finally {
            held.countDown();
        }
    }

    @Test
    void onlyOneBackgroundRefreshRunsAtATime() {
        discovery.found = true;
        discovery.bindings = List.of(
                new TelemetryBinding("logs", "default", "service_name", "kafka1"));
        register("tools", "kafka1");
        expire("kafka1");
        list(null, null, null);

        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        discovery.lastSeenGate = held;
        int before = discovery.lastSeenCalls;

        // Ten polls while one refresh is stuck. Queueing them would build a backlog
        // of work that is stale by the time it runs; the next poll schedules its own.
        for (int i = 0; i < 10; i++) {
            expire("kafka1");
            list(null, null, null);
        }
        await("polls do not stack refreshes on top of one another")
                .during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1))
                .until(() -> discovery.lastSeenCalls == before + 1);
        held.countDown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Age a resource past the liveness TTL.
     *
     * <p>Clears the check stamp rather than winding a clock back, which is the same
     * state a resource is in before it has ever been checked — and what the TTL
     * comparison actually looks at.</p>
     */
    private void expire(String resourceName) {
        for (com.trinity.observability.dto.productcatalog.CatalogResource resource
                : registry.findAll("default")) {
            if (resourceName.equals(resource.getResourceName())) {
                registry.update("default", resource.getId(), live -> {
                    live.setLastCheckedMicros(null);
                    return false;
                });
            }
        }
    }

    private ProductCatalogResponseDTO.ValidationResult validate(String category, String name) {
        ProductCatalogRequestDTO.Validate body = new ProductCatalogRequestDTO.Validate();
        body.setCategory(category);
        body.setResourceName(name);
        return service.validate(body);
    }

    private ProductCatalogResponseDTO.Resource register(String category, String name) {
        ProductCatalogRequestDTO.Register body = new ProductCatalogRequestDTO.Register();
        body.setCategory(category);
        body.setResourceName(name);
        return service.register(body);
    }

    private ProductCatalogResponseDTO.ResourceList list(String category, String status, String search) {
        ProductCatalogRequestDTO.ListFilter filter = new ProductCatalogRequestDTO.ListFilter();
        filter.setCategory(category);
        filter.setStatus(status);
        filter.setSearch(search);
        return service.list(filter);
    }

    private String idOf(String resourceName) {
        return registry.findAll("default").stream()
                .filter(resource -> resourceName.equals(resource.getResourceName()))
                .findFirst().orElseThrow().getId();
    }

    /** A second service instance over a registry that counts persisting updates. */
    private ProductCatalogServiceImpl newService(ProductCatalogRegistry customRegistry) {
        try {
            return new ProductCatalogServiceImpl(customRegistry, discovery, config(), properties,
                    NO_PRODUCTS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Wraps the real registry and counts how many {@code update} calls asked to be
     * persisted — i.e. how many rows would reach the durable change log.
     */
    private static final class RecordingRegistry implements ProductCatalogRegistry {

        private final ProductCatalogRegistry delegate =
                new InMemoryProductCatalogRegistry(new NoOpCatalogSnapshotStore());
        private int persistingUpdates;

        @Override
        public List<com.trinity.observability.dto.productcatalog.CatalogResource> findAll(String org) {
            return delegate.findAll(org);
        }

        @Override
        public java.util.Optional<com.trinity.observability.dto.productcatalog.CatalogResource>
                findById(String org, String id) {
            return delegate.findById(org, id);
        }

        @Override
        public java.util.Optional<com.trinity.observability.dto.productcatalog.CatalogResource>
                findByCategoryAndName(String org, ResourceCategory category, String resourceName) {
            return delegate.findByCategoryAndName(org, category, resourceName);
        }

        @Override
        public com.trinity.observability.dto.productcatalog.CatalogResource save(String org,
                com.trinity.observability.dto.productcatalog.CatalogResource resource) {
            persistingUpdates++;
            return delegate.save(org, resource);
        }

        @Override
        public boolean deleteById(String org, String id) {
            persistingUpdates++;
            return delegate.deleteById(org, id);
        }

        @Override
        public java.util.Optional<com.trinity.observability.dto.productcatalog.CatalogResource>
                update(String org, String id,
                        java.util.function.Predicate<
                                com.trinity.observability.dto.productcatalog.CatalogResource> mutator) {
            return delegate.update(org, id, live -> {
                boolean persist = mutator.test(live);
                if (persist) {
                    persistingUpdates++;
                }
                return persist;
            });
        }
    }

    private static OpenObserveConfig config() throws Exception {
        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "org", "default");
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

    /** Discovery whose answer each test sets directly, with call counting. */
    private static final class ScriptedDiscovery implements TelemetryDiscoveryService {

        private boolean supported = true;
        private boolean found;
        private String resolvedName;
        private List<String> signals = List.of("logs");
        private List<TelemetryBinding> bindings = List.of();
        private boolean failLastSeen;

        /** Held open by tests that need a refresh to still be running. */
        private volatile java.util.concurrent.CountDownLatch lastSeenGate;

        private int discoverCalls;
        private volatile int lastSeenCalls;

        @Override
        public Discovery discover(ResourceCategory category, String resourceName) {
            discoverCalls++;
            if (!found) {
                return new Discovery(false, null, List.of(), List.of(), null, 1_000L, 2_000L);
            }
            return new Discovery(true,
                    resolvedName == null ? resourceName : resolvedName,
                    bindings, signals, 1_500L, 1_000L, 2_000L);
        }

        @Override
        public Map<String, Long> lastSeen(Map<String, List<TelemetryBinding>> bindingsByResourceId) {
            lastSeenCalls++;
            java.util.concurrent.CountDownLatch gate = lastSeenGate;
            if (gate != null) {
                try {
                    gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failLastSeen) {
                throw new IllegalStateException("simulated upstream failure");
            }
            Map<String, Long> out = new HashMap<>();
            bindingsByResourceId.keySet().forEach(id -> out.put(id, 1_900L));
            return out;
        }

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public String windowExpression() {
            return "now-24h";
        }
    }
}
