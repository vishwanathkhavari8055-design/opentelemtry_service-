package com.trinity.observability.config;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.registry.ProductCatalogRegistry;

import jakarta.annotation.PostConstruct;

/**
 * Applies {@code product-catalog.seed[*]} to the registry at startup.
 *
 * <p>This is the "configuration" half of the requirement's "configuration,
 * in-memory registry, or another non-database mechanism": a deployment that knows
 * its inventory up front can declare it in {@code application.properties} and
 * have the catalog populated on every boot, with no API calls and nothing to
 * back up.</p>
 *
 * <h2>Idempotent by name</h2>
 *
 * <p>Seeding skips a (category, name) pair that already exists, so it composes
 * safely with the change log: a resource seeded on the first boot and then
 * disabled through the API stays disabled on the next boot rather than being
 * silently re-enabled by its own declaration. The corollary is that changing a
 * seed's {@code status} does NOT retroactively change an existing registration —
 * that would make the config quietly override an operator's deliberate action.</p>
 *
 * <h2>Seeds are not validated by default</h2>
 *
 * <p>See {@code ProductCatalogProperties.Seed#isValidate()}. A seed is an
 * assertion by whoever wrote the configuration, and rejecting it because an agent
 * had not started yet would make the catalog's contents depend on boot ordering.
 * Bindings and Last Seen are filled in by the first liveness refresh; until then
 * the resource filters on its name, which is the same fallback an unvalidated
 * registration uses.</p>
 */
public class ProductCatalogSeeder {

    private static final Logger log = LogManager.getLogger(ProductCatalogSeeder.class);

    private final ProductCatalogRegistry registry;
    private final OpenObserveConfig openObserveConfig;
    private final ProductCatalogProperties properties;
    private final boolean active;

    ProductCatalogSeeder(ProductCatalogRegistry registry, OpenObserveConfig openObserveConfig,
            ProductCatalogProperties properties) {
        this.registry = registry;
        this.openObserveConfig = openObserveConfig;
        this.properties = properties;
        this.active = true;
    }

    private ProductCatalogSeeder() {
        this.registry = null;
        this.openObserveConfig = null;
        this.properties = null;
        this.active = false;
    }

    /** A seeder that does nothing, for the (default) case of no declared seeds. */
    static ProductCatalogSeeder disabled() {
        return new ProductCatalogSeeder();
    }

    @PostConstruct
    void seed() {
        if (!active) {
            return;
        }
        // The DEFAULT org, not the request-scoped one: there is no request during
        // startup, and OrgContext is unset. A deployment that needs per-tenant
        // seeds registers them through the API instead — see the class note.
        String org = openObserveConfig.getDefaultOrg();
        int created = 0;
        int skipped = 0;

        for (ProductCatalogProperties.Seed spec : properties.getSeed()) {
            try {
                if (applySeed(org, spec)) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // One bad seed must not abort the rest, and must not stop the
                // application: the catalog is a feature, not a prerequisite.
                log.error("Product Catalog seed '{}' / '{}' rejected: {}",
                        spec.getCategory(), spec.getResourceName(), e.getMessage());
            }
        }
        log.info("Product Catalog seeding complete for org '{}': {} created, {} already present",
                org, created, skipped);
    }

    /** @return true when a resource was created, false when one already existed */
    private boolean applySeed(String org, ProductCatalogProperties.Seed spec) {
        ResourceCategory category = ResourceCategory.fromCode(spec.getCategory());
        String name = spec.getResourceName() == null ? "" : spec.getResourceName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("resource-name is required");
        }

        Optional<CatalogResource> existing = registry.findByCategoryAndName(org, category, name);
        if (existing.isPresent()) {
            return false;
        }

        ResourceStatus status = ResourceStatus.fromCodeOrNull(spec.getStatus());
        long nowMicros = nowMicros();

        CatalogResource resource = new CatalogResource();
        resource.setId(UUID.randomUUID().toString());
        resource.setOrg(org);
        resource.setCategory(category);
        resource.setResourceName(name);
        resource.setStatus(status == null ? ResourceStatus.ENABLED : status);
        resource.setSource(CatalogResource.Source.CONFIG);
        resource.setRegisteredAtMicros(nowMicros);
        resource.setUpdatedAtMicros(nowMicros);
        // lastCheckedMicros deliberately left null so the first list request
        // treats this resource as due for a liveness refresh and discovers its
        // bindings then, rather than issuing queries during startup.

        registry.save(org, resource);
        log.info("Product Catalog seeded '{}' under {} (status={}, org={})",
                name, category, resource.getStatus(), org);
        return true;
    }

    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
