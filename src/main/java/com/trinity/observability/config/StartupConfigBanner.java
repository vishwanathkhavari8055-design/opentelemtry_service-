package com.trinity.observability.config;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Prints the configuration this instance actually resolved, once, at startup.
 *
 * <h3>Why it exists</h3>
 *
 * <p>Every setting below can come from four places — an environment variable, a
 * {@code -D} JVM argument, a command-line argument, or the baked-in default in
 * {@code application.properties} — and the usual way to find out which one won
 * is to guess. On a deployed instance that guess is expensive: "why is it
 * querying the wrong OpenObserve host" and "why did it connect to the wrong
 * database" are the same question, and the answer is always in this table.</p>
 *
 * <p>So each line reports the VALUE <em>and</em> its ORIGIN. {@code env
 * OPENOBSERVE_HOST} means the environment supplied it; {@code default
 * (OPENOBSERVE_HOST unset)} means nobody did and the fallback applied. That
 * distinction is the whole point — a value that looks right for the wrong
 * reason is what makes these bugs take an afternoon.</p>
 *
 * <h3>Secrets</h3>
 *
 * <p>The OpenObserve auth token and the database password are printed IN FULL by
 * default, because that is what this deployment asked for. Console output is
 * routinely shipped to log aggregators and pasted into tickets, so before this
 * runs anywhere shared, set {@code STARTUP_CONFIG_BANNER_MASK=true} — the values
 * then print as {@code Basi****NDU=} and remain useful for "is it the token I
 * think it is" without being usable by whoever reads the log. Set
 * {@code STARTUP_CONFIG_BANNER=false} to suppress the banner entirely.</p>
 *
 * <h3>When it prints</h3>
 *
 * <p>On {@link ApplicationEnvironmentPreparedEvent} — as early as the
 * configuration exists, and long before the web server binds a port or the
 * datasource opens a connection. That timing is the point: it printed on
 * {@code ApplicationReadyEvent} first, which fires only on a SUCCESSFUL start,
 * so "port 8081 already in use" or an unreachable database produced a stack
 * trace and no configuration at all — the exact moment the configuration is
 * worth reading. Now every start prints it, successful or not.</p>
 *
 * <p>Registered by hand in {@code ObservabilityBackendApplication.main}: this
 * event fires before the application context exists, so a {@code @Component}
 * would not have been constructed yet. It is built into a single string and
 * logged in ONE call, so a concurrent logger cannot interleave lines through it.</p>
 */
public class StartupConfigBanner implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LogManager.getLogger(StartupConfigBanner.class);

    /**
     * First name inside a {@code ${...}} placeholder.
     *
     * <p>Stops at the name deliberately: {@code ${ALERTS_DB_MIGRATE:${ALERTS_PERSISTENCE_ENABLED:true}}}
     * nests a second placeholder in the default, and the OUTER variable is the
     * one an operator would set.</p>
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)");

    /** Printed in full or masked, per {@code startup.config-banner.mask-secrets}. */
    private static final Set<String> SECRET_KEYS = Set.of(
            "openobserve.auth-token",
            "grafana.token",
            "spring.datasource.password");

    /** One block of the banner. */
    private record Section(String title, List<String> keys) { }

    /**
     * What gets printed, in order.
     *
     * <p>Curated rather than a full property dump: application.properties carries
     * ~110 keys, most of them tuning constants nobody has ever changed, and a
     * wall of those would bury the six lines that explain a misconfiguration.
     * Everything that points this service at an external system is here.</p>
     */
    private static final List<Section> SECTIONS = List.of(
            new Section("SERVER", List.of(
                    "server.port",
                    "server.servlet.context-path",
                    // Printed because getting it wrong produces a bodyless 403
                    // on every write while every read keeps working — see
                    // CorsConfig. Having the effective value in the startup log
                    // turns a half-hour misdiagnosis into a glance.
                    "cors.allowed-origin-patterns")),

            new Section("OPENOBSERVE", List.of(
                    "openobserve.enabled",
                    "openobserve.host",
                    "openobserve.org",
                    "openobserve.auth-token",
                    // openobserve.request-timeout-seconds is deliberately absent: it
                    // exists only as a @Value default in OpenObserveConfig, so it would
                    // print "(not set)" while the client happily uses 60s — a line that
                    // misleads more than it informs.
                    "openobserve.logs-stream",
                    "openobserve.traces-stream")),

            new Section("DATABASE (PostgreSQL)", List.of(
                    "spring.datasource.url",
                    "spring.datasource.username",
                    "spring.datasource.password",
                    "spring.datasource.driver-class-name",
                    "spring.datasource.hikari.pool-name",
                    "spring.datasource.hikari.maximum-pool-size",
                    "spring.datasource.hikari.minimum-idle",
                    "spring.jpa.hibernate.ddl-auto",
                    "spring.jpa.show-sql",
                    "spring.flyway.enabled",
                    "spring.flyway.locations")),

            new Section("PRODUCT CATALOG", List.of(
                    "product-catalog.enabled",
                    "product-catalog.filter.enabled",
                    "product-catalog.filter.mode",
                    "product-catalog.validation.required",
                    "product-catalog.validation.window",
                    "product-catalog.persistence.mode",
                    "product-catalog.persistence.stream",
                    "product-catalog.persistence.replay-days")),

            new Section("ALERTS", List.of(
                    "alerts.persistence.enabled",
                    "alerts.ingest.enabled",
                    "alerts.ingest.stream",
                    "alerts.ingest.orgs",
                    "alerts.ingest.interval-ms",
                    "alerts.ingest.backfill-minutes",
                    "alerts.lifecycle.auto-resolve-enabled",
                    "alerts.lifecycle.stale-after-minutes",
                    "alerts.rule-sync.history-destination-name",
                    "alerts.rule-sync.history-destination-url")),

            new Section("GRAFANA DASHBOARDS", List.of(
                    "grafana.enabled",
                    "grafana.base-url",
                    // Masked by SECRET_KEYS above. Printed anyway because "(not
                    // set)" versus "gl****" is exactly the distinction a blank
                    // Dashboards screen needs, and it is the single most common
                    // misconfiguration of this feature.
                    "grafana.token",
                    "grafana.org-id",
                    "grafana.tls-insecure",
                    "grafana.allowed-folder-uids",
                    // Where registrations live, i.e. whether they survive a
                    // restart. AUTO resolves at startup and the resolved answer
                    // is logged by DashboardCatalogConfig; this prints what was
                    // ASKED for, which is what a properties file can get wrong.
                    "grafana.registry.scope",
                    "grafana.registry.store",
                    "grafana.registry.file",
                    "grafana.request-timeout-seconds",
                    "grafana.query-timeout-seconds")),

            new Section("ELASTICSEARCH (legacy)", List.of(
                    "elasticsearch.host",
                    "elasticsearch.port",
                    "elasticsearch.scheme")));

    private static final String RULE = "=".repeat(100);

    /**
     * Spring's own name for the adapter source it attaches over all the others
     * ({@code ConfigurationPropertySources.ATTACHED_PROPERTY_SOURCE_NAME}).
     * Named here rather than imported: it is an internal constant, and a string
     * comparison cannot break at runtime if the class moves.
     */
    private static final String ATTACHED_ADAPTER_SOURCE = "configurationProperties";

    /**
     * One banner per JVM.
     *
     * <p>The environment can be prepared more than once — a devtools restart, or
     * a context that is rebuilt — and a configuration dump repeating itself in
     * the console is noise that makes the real one harder to find.</p>
     */
    private final AtomicBoolean printed = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (printed.compareAndSet(false, true)) {
            printConfiguration(event.getEnvironment());
        }
    }

    /** Package-private so it can be driven directly from a test. */
    void printConfiguration(ConfigurableEnvironment environment) {
        if (!Boolean.TRUE.equals(environment.getProperty("startup.config-banner.enabled", Boolean.class, true))) {
            return;
        }
        boolean mask = environment.getProperty(
                "startup.config-banner.mask-secrets", Boolean.class, false);

        // ASCII only, throughout. The console appender writes in the platform
        // encoding, and a Windows console on cp1252 turns an em dash into a
        // replacement character right in the banner's title.
        StringBuilder out = new StringBuilder(4096);
        out.append('\n').append(RULE)
           .append("\n  STARTUP CONFIGURATION - resolved values and where each one came from\n")
           .append(RULE).append('\n');

        appendRuntime(out, environment);

        for (Section section : SECTIONS) {
            out.append('\n').append("  [").append(section.title()).append("]\n");
            for (String key : section.keys()) {
                appendProperty(out, environment, key, mask);
            }
        }

        out.append('\n').append(RULE).append('\n');
        if (!mask) {
            out.append("""
                      NOTE: secrets above are printed IN FULL. Set STARTUP_CONFIG_BANNER_MASK=true to mask them,
                            or STARTUP_CONFIG_BANNER=false to suppress this banner.
                    """);
            out.append(RULE).append('\n');
        }

        // One call: a banner assembled line-by-line would be interleaved with
        // whatever the ingest scheduler logs at the same moment.
        if (log.isInfoEnabled()) {
            log.info(out.toString());
        }
    }

    /** JVM-level facts that are not properties but answer the same questions. */
    private static void appendRuntime(StringBuilder out, ConfigurableEnvironment environment) {
        String[] profiles = environment.getActiveProfiles();
        out.append('\n').append("  [RUNTIME]\n");
        line(out, "pid", String.valueOf(ProcessHandle.current().pid()), "jvm");
        line(out, "java.version", System.getProperty("java.version"), "jvm");
        line(out, "active.profiles", profiles.length == 0 ? "(none - default)" : String.join(",", profiles), "spring");
        line(out, "default.timezone", TimeZone.getDefault().getID(), "jvm");
        line(out, "uptime.at.banner", ManagementFactory.getRuntimeMXBean().getUptime() + " ms", "jvm");
    }

    /** One property row: value (masked if secret) plus where it came from. */
    private static void appendProperty(StringBuilder out, ConfigurableEnvironment environment,
                                       String key, boolean mask) {
        String value = environment.getProperty(key);
        boolean secret = SECRET_KEYS.contains(key);

        String shown;
        if (value == null) {
            shown = "(not set)";
        } else if (value.isEmpty()) {
            shown = "(empty)";
        } else if (secret && mask) {
            shown = maskValue(value);
        } else {
            shown = value;
        }
        if (secret) {
            shown = shown + (mask ? "   [masked]" : "   [SECRET]");
        }
        line(out, key, shown, origin(environment, key));
    }

    /**
     * One row: {@code key : value   (origin)}.
     *
     * <p>ASCII only, and the origin stays on the same line as its value. A
     * two-line-per-property layout doubles a 50-property banner into something
     * nobody reads to the end, and box-drawing characters arrive mangled on a
     * Windows console running a non-UTF-8 code page.</p>
     */
    private static void line(StringBuilder out, String key, String value, String origin) {
        out.append(String.format("    %-46s %s   (%s)%n", key + " :", value, origin));
    }

    /**
     * Which layer supplied this property.
     *
     * <p>Two different questions, answered in the right order. The first source
     * that HOLDS the key wins, because that is precisely how Spring resolved it.
     * If that source is application.properties and the raw text is a
     * {@code ${VAR:fallback}} placeholder, the answer is one level deeper — the
     * file holds the key, but the environment decides the value — so the
     * variable is looked up and reported as set or unset.</p>
     */
    private static String origin(ConfigurableEnvironment environment, String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            // "configurationProperties" is an ADAPTER Spring attaches over every
            // other source, so it answers containsProperty for everything and
            // would win this loop for every key — reporting "configurationProperties"
            // as the origin of the whole file. Skipping it lets the real source
            // through, which is the one worth naming.
            if (!ATTACHED_ADAPTER_SOURCE.equals(source.getName()) && source.containsProperty(key)) {
                return resolvePlaceholderOrigin(source.getProperty(key), source.getName());
            }
        }
        return "framework default";
    }

    private static String resolvePlaceholderOrigin(Object raw, String sourceName) {
        if (raw != null) {
            Matcher matcher = PLACEHOLDER.matcher(raw.toString());
            if (matcher.find()) {
                String variable = matcher.group(1);
                return System.getenv(variable) != null
                        ? "env " + variable
                        : "default in application.properties (" + variable + " unset)";
            }
        }
        return sourceLabel(sourceName);
    }

    /** Spring's property-source names are verbose; these are the ones that matter. */
    private static String sourceLabel(String name) {
        if (name == null) return "unknown";
        if (name.equals("systemEnvironment")) return "environment variable";
        if (name.equals("systemProperties")) return "JVM -D argument";
        if (name.startsWith("commandLineArgs")) return "command-line argument";
        if (name.contains("application.properties")) return "application.properties";
        if (name.contains("application.yml")) return "application.yml";
        return name;
    }

    /** {@code Basic dHJp...NDU=} → {@code Basi****NDU=}. Short values reveal nothing. */
    private static String maskValue(String value) {
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
