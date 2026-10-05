package com.trinity.observability.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.annotation.PostConstruct;

/**
 * Switches the PostgreSQL-backed alert layer on.
 *
 * <h2>Why any of this is conditional</h2>
 *
 * <p>This artifact is published as a <em>library</em> and consumed by other
 * Spring Boot services — {@link ObservabilityAutoConfig} component-scans the
 * whole {@code com.trinity.observability} package into whatever application
 * imports it. Unconditional JPA here would mean every one of those services
 * suddenly needs a datasource, and any that lacks one would fail to start with
 * "Failed to configure a DataSource" over a feature it never asked for.</p>
 *
 * <p>Two independent guards prevent that:</p>
 *
 * <ol>
 *   <li>The JPA, Flyway and PostgreSQL dependencies are {@code <optional>} in
 *       the POM, so they are packaged into this service's own jar but are
 *       <em>not</em> inherited transitively. A consumer's classpath is
 *       unchanged, and {@link ConditionalOnClass} then skips this
 *       configuration without ever attempting to load a JPA class.</li>
 *   <li>{@code alerts.persistence.enabled} defaults to <em>absent</em>, and
 *       {@code matchIfMissing = false} means absent is off. A consumer that
 *       happens to have JPA on its own classpath still gets nothing unless it
 *       explicitly opts in.</li>
 * </ol>
 *
 * <p>This service's own {@code application.properties} sets the flag to true,
 * so nothing about its behaviour is conditional in practice.</p>
 */
@Configuration
@ConditionalOnClass(name = "jakarta.persistence.EntityManagerFactory")
@ConditionalOnProperty(name = "alerts.persistence.enabled", havingValue = "true", matchIfMissing = false)
@EnableJpaRepositories(basePackages = "com.trinity.observability.repository")
@EntityScan(basePackages = "com.trinity.observability.entity")
@EnableTransactionManagement
@EnableScheduling
    /**
     * Bound here rather than on each properties class so they inherit this
     * configuration's guards. All describe the PostgreSQL-backed alert feature.
     * Binding them unconditionally would give a library consumer that never enabled
     * alert persistence a validated ingest configuration and a scheduled poller with
     * no tables to write into.
     */
    @EnableConfigurationProperties({
        AlertIngestProperties.class,
        AlertLifecycleProperties.class,
        AlertRuleSyncProperties.class,
        AlertDisplayProperties.class,
        AlertIncidentProperties.class })
public class AlertPersistenceConfig {

    private static final Logger log = LogManager.getLogger(AlertPersistenceConfig.class);

    @PostConstruct
    void announce() {
        log.info("Alert persistence ENABLED — PostgreSQL is the source of truth for alert rules "
                + "and alerts. Firings are READ FROM AN OPENOBSERVE STREAM by the ingest poller; "
                + "the inbound webhook has been removed.");
    }
}
