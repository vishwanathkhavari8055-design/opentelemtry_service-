package com.trinity.observability.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.trinity.observability.config.GrafanaProperties.Registry.Store;
import com.trinity.observability.registry.DashboardRegistryStore;
import com.trinity.observability.registry.FileDashboardRegistryStore;
import com.trinity.observability.registry.JpaDashboardRegistryStore;
import com.trinity.observability.repository.GrafanaDashboardRepository;

/**
 * Picks where dashboard registrations are stored.
 *
 * <p>The same shape as {@link ProductCatalogConfig}, for the same reason: this
 * artifact is consumed as a LIBRARY, and a bean that hard-requires the JPA
 * repository would fail to start on any consumer that has not configured a
 * datasource. {@link ObjectProvider} lets the repository be genuinely absent, and
 * the file store is a working answer rather than a degraded one.</p>
 *
 * <h2>What AUTO actually decides</h2>
 *
 * <p>"Is the repository bean there?" — which on this deployment means
 * {@code alerts.persistence.enabled=true} and a reachable PostgreSQL, because
 * {@link AlertPersistenceConfig} is what turns the repositories on. That is the
 * honest test: a datasource URL in a properties file proves nothing.</p>
 *
 * <p>{@code POSTGRES} pinned without a repository is a startup failure on purpose.
 * Someone who asked for the durable store and silently got a file would find out
 * when the container restarted and the catalog was empty.</p>
 */
@Configuration
public class DashboardCatalogConfig {

    private static final Logger log = LogManager.getLogger(DashboardCatalogConfig.class);

    @Bean
    @ConditionalOnMissingBean(DashboardRegistryStore.class)
    public DashboardRegistryStore dashboardRegistryStore(GrafanaProperties properties,
            ObjectProvider<GrafanaDashboardRepository> repositoryProvider) {

        Store requested = properties.getRegistry().getStore();
        GrafanaDashboardRepository repository =
                requested == Store.FILE ? null : repositoryProvider.getIfAvailable();

        if (requested == Store.POSTGRES && repository == null) {
            throw new IllegalStateException(
                    "grafana.registry.store=POSTGRES was requested but the dashboard repository "
                    + "is not available. Enable persistence (alerts.persistence.enabled=true with "
                    + "a reachable spring.datasource), or set grafana.registry.store=FILE.");
        }

        DashboardRegistryStore store;
        if (repository != null) {
            store = new JpaDashboardRegistryStore(repository);
        } else {
            if (requested == Store.AUTO) {
                log.info("Dashboard registry: no JPA repository available (persistence disabled "
                        + "or no datasource) — using the JSON file store.");
            }
            store = new FileDashboardRegistryStore(properties.getRegistry().getFile());
        }

        if (log.isInfoEnabled()) {
            log.info("Dashboard registry store: {}", store.describe());
        }
        return store;
    }
}
