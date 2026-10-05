package com.trinity.observability.serviceimpl;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.registry.InMemoryProductCatalogRegistry;
import com.trinity.observability.registry.NoOpCatalogSnapshotStore;
import com.trinity.observability.registry.ProductCatalogRegistry;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The catalog read behind {@code sqlPredicate}.
 *
 * <p>{@code sqlPredicate} runs on every telemetry query this service builds, and
 * each call used to make two PostgreSQL round trips. That is what put the
 * 10-connection Hikari pool on the critical path of every request. These tests
 * pin the two properties that matter: the read is reused within the TTL, and it
 * is not reused past it.</p>
 */
class CatalogTelemetryFilterImplCacheTest {

    private static final String ORG = "default";

    private CountingRegistry registry;
    private ProductCatalogProperties properties;

    @BeforeEach
    void setUp() {
        registry = new CountingRegistry(
                new InMemoryProductCatalogRegistry(new NoOpCatalogSnapshotStore()));
        properties = new ProductCatalogProperties();
    }

    @Test
    void repeatedPredicateBuildsReadTheCatalogOnce() throws Exception {
        registerDisabled(registry, "redis-master");
        CatalogTelemetryFilterImpl filter = filter(60_000L);

        String first = filter.sqlPredicate("logs");
        for (int i = 0; i < 24; i++) {
            assertEquals(first, filter.sqlPredicate("logs"),
                    "a cached read must not change the predicate");
        }

        assertEquals(1, registry.findAllCalls.get(),
                "25 predicate builds should cost ONE catalog read, not 25");
    }

    @Test
    void everySignalSharesTheOneCachedRead() throws Exception {
        registerDisabled(registry, "redis-master");
        CatalogTelemetryFilterImpl filter = filter(60_000L);

        filter.sqlPredicate("logs");
        filter.sqlPredicate("traces");
        filter.sqlPredicate("metrics");

        assertEquals(1, registry.findAllCalls.get(),
                "the catalog is org-scoped, not signal-scoped");
    }

    @Test
    void aZeroTtlDisablesCachingEntirely() throws Exception {
        registerDisabled(registry, "redis-master");
        CatalogTelemetryFilterImpl filter = filter(0L);

        filter.sqlPredicate("logs");
        filter.sqlPredicate("logs");
        filter.sqlPredicate("logs");

        assertEquals(3, registry.findAllCalls.get());
    }

    @Test
    void theCacheExpires() throws Exception {
        registerDisabled(registry, "redis-master");
        CatalogTelemetryFilterImpl filter = filter(40L);

        filter.sqlPredicate("logs");
        assertEquals(1, registry.findAllCalls.get());

        // Keep reading until the TTL lapses: a read inside it is served from the
        // cache, so the counter can only move once the entry has expired.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    filter.sqlPredicate("logs");
                    return registry.findAllCalls.get() > 1;
                });
        assertEquals(2, registry.findAllCalls.get(), "the read must not be reused past its TTL");
    }

    @Test
    void invalidateForcesTheNextReadThrough() throws Exception {
        registerDisabled(registry, "redis-master");
        CatalogTelemetryFilterImpl filter = filter(60_000L);

        filter.sqlPredicate("logs");
        filter.invalidate();
        filter.sqlPredicate("logs");

        assertEquals(2, registry.findAllCalls.get());
    }

    /** A newly disabled resource must start filtering once the TTL lapses. */
    @Test
    void aResourceDisabledLaterIsPickedUpAfterTheTtl() throws Exception {
        CatalogTelemetryFilterImpl filter = filter(40L);
        assertEquals("", filter.sqlPredicate("logs"), "empty catalog filters nothing");

        registerDisabled(registry, "redis-master");

        await("a disabled resource must take effect once the cached read expires")
                .atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> filter.sqlPredicate("logs").contains("redis-master"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CatalogTelemetryFilterImpl filter(long ttlMillis) throws Exception {
        return new CatalogTelemetryFilterImpl(registry, config(ORG), properties, ttlMillis);
    }

    /** Mirrors the register() helper in {@link CatalogTelemetryFilterImplTest}. */
    private static void registerDisabled(ProductCatalogRegistry registry, String serviceName) {
        CatalogResource resource = new CatalogResource();
        resource.setId(UUID.randomUUID().toString());
        resource.setCategory(ResourceCategory.TOOLS);
        resource.setResourceName(serviceName);
        resource.setStatus(ResourceStatus.DISABLED);
        resource.setBindings(List.of(
                new TelemetryBinding("logs", "default", "service_name", serviceName)));
        resource.setRegisteredAtMicros(System.nanoTime());
        registry.save(ORG, resource);
    }

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

    /** Delegates everything; counts the reads the filter is supposed to collapse. */
    private static final class CountingRegistry implements ProductCatalogRegistry {

        private final ProductCatalogRegistry delegate;
        final AtomicInteger findAllCalls = new AtomicInteger();

        CountingRegistry(ProductCatalogRegistry delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<CatalogResource> findAll(String org) {
            findAllCalls.incrementAndGet();
            return delegate.findAll(org);
        }

        @Override
        public Optional<CatalogResource> findById(String org, String id) {
            return delegate.findById(org, id);
        }

        @Override
        public Optional<CatalogResource> findByCategoryAndName(String org,
                ResourceCategory category, String resourceName) {
            return delegate.findByCategoryAndName(org, category, resourceName);
        }

        @Override
        public CatalogResource save(String org, CatalogResource resource) {
            return delegate.save(org, resource);
        }

        @Override
        public boolean deleteById(String org, String id) {
            return delegate.deleteById(org, id);
        }

        @Override
        public Optional<CatalogResource> update(String org, String id,
                Predicate<CatalogResource> mutator) {
            return delegate.update(org, id, mutator);
        }
    }
}
