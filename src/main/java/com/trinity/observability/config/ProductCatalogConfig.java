package com.trinity.observability.config;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.ProductCatalogProperties.PersistenceMode;
import com.trinity.observability.registry.CatalogSnapshotStore;
import com.trinity.observability.registry.InMemoryProductCatalogRegistry;
import com.trinity.observability.registry.JpaProductCatalogRegistry;
import com.trinity.observability.repository.CatalogProductRepository;
import com.trinity.observability.repository.CatalogResourceRepository;
import com.trinity.observability.registry.NoOpCatalogSnapshotStore;
import com.trinity.observability.registry.OpenObserveCatalogSnapshotStore;
import com.trinity.observability.registry.ProductCatalogRegistry;

/**
 * Wires the Product Catalog's storage.
 *
 * <h2>Both beans are {@code @ConditionalOnMissingBean}, and that is the point</h2>
 *
 * <p>The requirement is that a persistent store can be introduced later without
 * touching the APIs or the UI. This is where that promise is kept: declare a
 * {@link ProductCatalogRegistry} bean anywhere in the application — a JDBC one, a
 * Redis one, whatever the deployment eventually wants — and these defaults step
 * aside. Nothing above the registry interface changes, because nothing above it
 * knows which implementation answered.</p>
 *
 * <p>Deliberately NO datasource, NO entity and NO migration: the catalog is a
 * registry held in the application layer, with an OpenObserve stream as its
 * change log. Adding a table was explicitly ruled out.</p>
 *
 * <h2>Library-safety</h2>
 *
 * <p>Like the rest of this artifact, these beans are component-scanned into any
 * service that imports it (see {@link ObservabilityAutoConfig}). They depend only
 * on {@link OpenObserveClientService} and {@link OpenObserveConfig}, which are
 * unconditional here — so a consumer gains a working Product Catalog without
 * gaining a new infrastructure requirement, and no OpenObserve call is made until
 * something actually reads the catalog.</p>
 */
@Configuration
@EnableConfigurationProperties(ProductCatalogProperties.class)
public class ProductCatalogConfig {

    private static final Logger log = LogManager.getLogger(ProductCatalogConfig.class);

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /**
     * Durability for the registry.
     *
     * <p>The OpenObserve-stream store is the default because a registry that
     * forgets everything on restart is not much of a registry — but it is
     * downgraded to the no-op store when OpenObserve is switched off, since
     * without OpenObserve there is nowhere for it to write and every mutation
     * would log an error the operator can do nothing about.</p>
     */
    @Bean
    @ConditionalOnMissingBean(CatalogSnapshotStore.class)
    public CatalogSnapshotStore catalogSnapshotStore(OpenObserveClientService openObserveClientService,
            ProductCatalogProperties properties) {
        PersistenceMode mode = properties.getPersistence().getMode();
        if (mode == PersistenceMode.OPENOBSERVE_STREAM && !openObserveEnabled) {
            log.warn("Product Catalog persistence requested OPENOBSERVE_STREAM but "
                    + "openobserve.enabled=false — falling back to in-memory only. "
                    + "Registrations will not survive a restart.");
            return new NoOpCatalogSnapshotStore();
        }
        return mode == PersistenceMode.OPENOBSERVE_STREAM
                ? new OpenObserveCatalogSnapshotStore(openObserveClientService, properties)
                : new NoOpCatalogSnapshotStore();
    }

    /**
     * The registry itself. Replace by declaring your own bean of this type.
     *
     * <p>The choice is made HERE rather than in two competing {@code @Bean} methods
     * on separate configurations. {@code @ConditionalOnMissingBean} inside a plain
     * {@code @Configuration} is evaluated in definition order, so two candidates
     * would make the winner depend on classpath scanning order — and the failure
     * mode is the catalog silently using the wrong store.</p>
     *
     * <p>The repositories arrive as {@link ObjectProvider} because they only exist
     * when alert persistence brought JPA with it. A deployment using this as a
     * library with no database still gets a working catalog, on the in-memory store,
     * with no missing-bean failure at startup.</p>
     */
    @Bean
    @ConditionalOnMissingBean(ProductCatalogRegistry.class)
    public ProductCatalogRegistry productCatalogRegistry(CatalogSnapshotStore catalogSnapshotStore,
            ProductCatalogProperties properties,
            ObjectProvider<CatalogResourceRepository> resourceRepository,
            ObjectProvider<CatalogProductRepository> productRepository) {

        if (properties.getStore() == ProductCatalogProperties.CatalogStore.MEMORY) {
            if (log.isInfoEnabled()) {
                log.info("Product Catalog store: MEMORY (durability via {})",
                        catalogSnapshotStore.describe());
            }
            return new InMemoryProductCatalogRegistry(catalogSnapshotStore);
        }

        CatalogResourceRepository resources = resourceRepository.getIfAvailable();
        CatalogProductRepository products = productRepository.getIfAvailable();
        if (resources == null || products == null) {
            // Stated loudly. POSTGRES was asked for and cannot be provided, and the
            // fallback has materially weaker durability — silently degrading would
            // reproduce the original bug, where the catalog appeared to work right
            // up until it lost everything.
            if (log.isWarnEnabled()) {
                log.warn("Product Catalog store POSTGRES was requested but the catalog "
                        + "repositories are not available (alerts.persistence.enabled=false, or no "
                        + "datasource). Falling back to MEMORY — registrations will only be as "
                        + "durable as {}.", catalogSnapshotStore.describe());
            }
            return new InMemoryProductCatalogRegistry(catalogSnapshotStore);
        }
        return new JpaProductCatalogRegistry(resources, products);
    }

    /**
     * Config-declared resources, applied once at startup.
     *
     * <p>Only created when {@code product-catalog.seed[*]} is actually populated.
     * That matters: seeding touches the registry, which replays the change log,
     * which calls OpenObserve — and an unreachable OpenObserve must not be able to
     * slow down or fail startup for a deployment that declared no seeds. With the
     * default (empty) list, this module issues no request until something reads
     * the catalog.</p>
     */
    @Bean
    public ProductCatalogSeeder productCatalogSeeder(ProductCatalogRegistry registry,
            OpenObserveConfig openObserveConfig,
            ProductCatalogProperties properties) {
        List<ProductCatalogProperties.Seed> seeds = properties.getSeed();
        if (seeds.isEmpty()) {
            return ProductCatalogSeeder.disabled();
        }
        log.info("Product Catalog will seed {} resource(s) from configuration", seeds.size());
        return new ProductCatalogSeeder(registry, openObserveConfig, properties);
    }
}
