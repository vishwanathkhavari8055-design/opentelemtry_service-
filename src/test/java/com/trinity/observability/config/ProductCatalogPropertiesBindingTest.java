package com.trinity.observability.config;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.trinity.observability.config.ProductCatalogProperties.FilterMode;
import com.trinity.observability.config.ProductCatalogProperties.PersistenceMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds {@link ProductCatalogProperties} the way Spring does, from the real
 * {@code application.properties}.
 *
 * <p>This exists because the failure it guards against is invisible until
 * startup and total when it happens: a property key that does not match the
 * bean, or a default collection that turns out to be immutable when Spring tries
 * to populate it in place, is an application that will not boot. Compiling and
 * unit-testing the class proves neither.</p>
 */
class ProductCatalogPropertiesBindingTest {

    @Test
    void theCodeDefaultIsTheConservativeMode() {
        // Deliberately NOT the shipped value. This artifact is a library that
        // other services component-scan, and an allow-list default would blank
        // their Logs/Traces/Metrics screens the moment they picked it up. A
        // deployment opts in to ENABLED_ONLY in its own properties, as this
        // service's does.
        assertEquals(FilterMode.EXCLUDE_DISABLED, new ProductCatalogProperties().getFilter().getMode());
    }

    @Test
    void theShippedPropertiesFileSelectsTheAllowListMode() throws Exception {
        // This service's own deployment uses the Product Catalog AS the scope
        // control: the Logs/Traces sidebar tree renders the registry, so Logs,
        // Traces and Metrics must show only what is registered and enabled or the
        // tree would describe something that is not being enforced.
        assertEquals(FilterMode.ENABLED_ONLY,
                bind(loadShippedProperties()).getFilter().getMode());
    }

    @Test
    void theShippedPropertiesFileBindsCleanly() throws Exception {
        ProductCatalogProperties bound = bind(loadShippedProperties());

        assertTrue(bound.isEnabled());
        assertTrue(bound.getFilter().isEnabled());
        assertEquals(PersistenceMode.OPENOBSERVE_STREAM, bound.getPersistence().getMode());
        assertEquals("product_catalog", bound.getPersistence().getStream());
        assertEquals("now-24h", bound.getValidation().getWindow());
        assertEquals(60, bound.getValidation().getLivenessTtlSeconds());
        assertTrue(bound.getValidation().isRequired());
        assertTrue(bound.getSeed().isEmpty(), "seeding is opt-in; the shipped file declares none");
    }

    @Test
    void theShippedProbeChainBindsInOrderWithServiceNameFirst() throws Exception {
        ProductCatalogProperties bound = bind(loadShippedProperties());

        List<ProductCatalogProperties.Probe> probes = bound.getValidation().getProbes();
        assertEquals(9, probes.size());

        // Order is load-bearing: discovery short-circuits per signal, so a
        // fallback identifier ahead of service_name would be recorded instead of
        // it — and a namespace filters far more than the operator asked for.
        assertEquals("logs", probes.get(0).getSignal());
        assertEquals("service_name", probes.get(0).getColumn());
        assertEquals("traces", probes.get(1).getSignal());
        assertEquals("service_name", probes.get(1).getColumn());
        assertEquals("metrics", probes.get(2).getSignal());
        assertEquals("service_name", probes.get(2).getColumn());

        assertEquals("db_name", probes.get(4).getColumn());
        assertEquals("k8s_deployment_name", probes.get(6).getColumn());
    }

    @Test
    void theShippedFilterColumnsBindAndKeepMetricsRestricted() throws Exception {
        ProductCatalogProperties bound = bind(loadShippedProperties());

        assertEquals(List.of("service_name"), bound.getFilter().columnsFor("metrics"),
                "one stream per metric name means only service_name is reliably present");
        assertTrue(bound.getFilter().columnsFor("logs").contains("k8s_deployment_name"));
        assertTrue(bound.getFilter().columnsFor("traces").contains("db_system"));
        assertTrue(bound.getFilter().columnsFor("nonsense").isEmpty(),
                "an unknown signal filters nothing rather than throwing");
    }

    @Test
    void anOverriddenColumnListReplacesTheDefaultWithoutFailing() {
        Map<String, String> source = new HashMap<>();
        source.put("product-catalog.filter.columns.logs", "service_name");

        // The regression this guards: Spring may populate a collection property by
        // mutating what the getter returns, which throws if the default is List.of.
        ProductCatalogProperties bound = bind(source);

        assertEquals(List.of("service_name"), bound.getFilter().columnsFor("logs"));
    }

    @Test
    void seedEntriesBindWithKebabCaseKeys() {
        Map<String, String> source = new HashMap<>();
        source.put("product-catalog.seed[0].category", "databases");
        source.put("product-catalog.seed[0].resource-name", "PostgreSQL");
        source.put("product-catalog.seed[1].category", "tools");
        source.put("product-catalog.seed[1].resource-name", "kafka1");
        source.put("product-catalog.seed[1].status", "DISABLED");

        ProductCatalogProperties bound = bind(source);

        assertEquals(2, bound.getSeed().size());
        assertEquals("PostgreSQL", bound.getSeed().get(0).getResourceName());
        assertEquals("DISABLED", bound.getSeed().get(1).getStatus());
        assertFalse(bound.getSeed().get(0).isValidate(), "seeds are trusted by default");
    }

    @Test
    void anEmptyOrBlankWindowFallsBackRatherThanProducingABrokenQuery() {
        Map<String, String> source = new HashMap<>();
        source.put("product-catalog.validation.window", "   ");

        assertEquals("now-24h", bind(source).getValidation().getWindow());
    }

    @Test
    void aNegativeTtlIsClampedInsteadOfInvertingTheCacheCheck() {
        Map<String, String> source = new HashMap<>();
        source.put("product-catalog.validation.liveness-ttl-seconds", "-30");

        assertEquals(0, bind(source).getValidation().getLivenessTtlSeconds());
    }

    private static ProductCatalogProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("product-catalog", ProductCatalogProperties.class)
                .orElseGet(ProductCatalogProperties::new);
    }

    /**
     * The real {@code application.properties}, with its {@code ${VAR:default}}
     * placeholders resolved to their defaults — which is what an unset
     * environment produces.
     */
    private static Map<String, String> loadShippedProperties() throws Exception {
        Properties raw = new Properties();
        try (InputStream in = ProductCatalogPropertiesBindingTest.class
                .getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            raw.load(in);
        }

        Map<String, String> resolved = new HashMap<>();
        for (String key : raw.stringPropertyNames()) {
            if (key.startsWith("product-catalog.")) {
                resolved.put(key, resolvePlaceholderDefault(raw.getProperty(key)));
            }
        }
        assertFalse(resolved.isEmpty(), "the shipped file must declare product-catalog properties");
        return resolved;
    }

    /** {@code ${NAME:default}} → {@code default}; anything else is returned as-is. */
    private static String resolvePlaceholderDefault(String value) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String inner = value.substring(2, value.length() - 1);
        int colon = inner.indexOf(':');
        return colon < 0 ? "" : inner.substring(colon + 1);
    }
}
