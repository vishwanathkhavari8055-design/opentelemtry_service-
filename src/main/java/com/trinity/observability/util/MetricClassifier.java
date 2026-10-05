package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.trinity.observability.config.MetricClassificationProperties;

/**
 * Sorts a metric NAME into a group and a technology, so the catalogue can be
 * read instead of scrolled.
 *
 * <h2>The problem</h2>
 *
 * <p>This instance exposes 3,215 metric names through one endpoint, and they are
 * not one population. Measured on 2026-09-03: 1,897 come from third-party
 * middleware (WSO2/Carbon JMX beans, Postgres, Kafka, Tomcat), 449 from
 * Kubernetes and the hosts, 282 from the observability stack monitoring itself,
 * and only 354 from our own instrumented services. A flat alphabetical list puts
 * {@code jvm_memory_used_bytes} — the one an engineer wants — between
 * {@code java_nio_bufferpool_totalcapacity} and {@code kafka_consumer_commit_rate},
 * which is why the screen reads as "all the metrics mixed".</p>
 *
 * <h2>Why the metric NAME is the right key</h2>
 *
 * <p>Every exporter namespaces its metrics, and that namespace is the one piece
 * of provenance available without querying: the catalogue endpoint returns names
 * and metadata, no labels. Classifying by name therefore costs nothing and
 * covers all 3,215 — 3,211 of them at time of writing, with the remainder
 * landing in {@link #GROUP_OTHER} rather than being guessed at.</p>
 *
 * <h2>Where the name is NOT enough — {@link #GROUP_RUNTIME}</h2>
 *
 * <p>Some metric names are emitted by EVERY producer, so no name-based rule can
 * honestly assign them an owner. Verified against the live instance by reading
 * {@code /series} label sets:</p>
 *
 * <ul>
 *   <li>{@code process_cpu_seconds_total} — six series, spanning
 *       {@code service_name} openobserve, tiotapi, tiotesb, DLHAppService and
 *       MsSqlExporter. Three different groups at once.</li>
 *   <li>{@code system_cpu_utilization} — over a thousand series, mostly our own
 *       services but not exclusively.</li>
 *   <li>{@code go_gc_duration_seconds} — the Go runtime of whichever exporter or
 *       tool happens to be written in Go.</li>
 *   <li>{@code up} — 60 series: collectors, exporters and our services alike.</li>
 * </ul>
 *
 * <p>Filing these under "Microservices" because most of the series happen to be
 * ours would be a lie the UI then repeats. They get their own group instead, and
 * {@link Classification#isSharedAcrossProducers()} tells the frontend to prompt
 * for a {@code service_name} filter — the only thing that actually segregates
 * them.</p>
 *
 * <h2>Longest prefix wins</h2>
 *
 * <p>The rule table overlaps deliberately: {@code pg_} is PostgreSQL, but
 * {@code pg_exporter_} is the exporter's own scrape health and belongs with the
 * observability stack. Resolution is by longest matching prefix, so the specific
 * rule beats the general one regardless of declaration order — which keeps the
 * table safe to extend, here or via
 * {@link MetricClassificationProperties#getExtraRules()}.</p>
 */
@Component
public class MetricClassifier {

    private static final Logger log = LogManager.getLogger(MetricClassifier.class);

    // --- Group keys -------------------------------------------------------
    //
    // Five buckets, and the split is by WHO OWNS THE FIX when the number is
    // bad, because that is the question the screen is being asked. A slow
    // endpoint is our code (microservice); a saturated connection pool on the
    // database is not (middleware); a pod being evicted is neither (platform).

    /** Our own instrumented services — Micrometer / OTel SDK inside our code. */
    public static final String GROUP_MICROSERVICE  = "microservice";
    /** Third-party products we run: databases, brokers, integration servers. */
    public static final String GROUP_MIDDLEWARE    = "middleware";
    /** Kubernetes, containers and the machines underneath. */
    public static final String GROUP_PLATFORM      = "platform";
    /** The telemetry pipeline watching everything else, including itself. */
    public static final String GROUP_OBSERVABILITY = "observability";
    /** Emitted by many producers at once — segregate by {@code service_name}. */
    public static final String GROUP_RUNTIME       = "runtime";
    /** No rule matched. Deliberately visible: it is the to-do list for this table. */
    public static final String GROUP_OTHER         = "other";

    /** Display order and labels for the facets, most-asked-about first. */
    private static final Map<String, String> GROUP_LABELS = new LinkedHashMap<>();
    static {
        GROUP_LABELS.put(GROUP_MICROSERVICE,  "Microservices");
        GROUP_LABELS.put(GROUP_MIDDLEWARE,    "Middleware & data stores");
        GROUP_LABELS.put(GROUP_PLATFORM,      "Kubernetes & hosts");
        GROUP_LABELS.put(GROUP_OBSERVABILITY, "Observability stack");
        GROUP_LABELS.put(GROUP_RUNTIME,       "Shared runtime");
        GROUP_LABELS.put(GROUP_OTHER,         "Unclassified");
    }

    /**
     * One classification rule: a prefix (or whole name, when {@code exact}) and
     * the group plus technology it maps to.
     */
    private static final class Rule {
        private final String match;
        private final boolean exact;
        private final String group;
        private final String tech;

        Rule(String match, boolean exact, String group, String tech) {
            this.match = match;
            this.exact = exact;
            this.group = group;
            this.tech = tech;
        }

        boolean matches(String metricName) {
            return exact ? metricName.equals(match) : metricName.startsWith(match);
        }
    }

    private static final String TECH_POSTGRES_EXPORTER = "Postgres exporter";
    private static final String TECH_K8S_CONTROLLERS = "Kubernetes controllers";
    private static final String TECH_POSTGRESQL = "PostgreSQL";
    private static final String TECH_APPLICATION = "Application";

    /**
     * The built-in table.
     *
     * <p>Order is irrelevant to resolution (longest prefix wins) but is kept
     * grouped for reading. Every entry below was checked against the names this
     * instance actually returns; nothing here is speculative padding.</p>
     */
    private static final List<Rule> BUILT_IN = List.of(
            // --- Observability stack ---------------------------------------
            // The pipeline itself. Split out from "platform" because when these
            // go wrong the symptom is missing data everywhere else, and that is
            // the first thing to rule out.
            rule("zo_",                 GROUP_OBSERVABILITY, "OpenObserve"),
            rule("otelcol_",            GROUP_OBSERVABILITY, "OpenTelemetry Collector"),
            rule("otlp_exporter_",      GROUP_OBSERVABILITY, "OpenTelemetry Collector"),
            rule("scrape_",             GROUP_OBSERVABILITY, "Scrape health"),
            rule("promhttp_",           GROUP_OBSERVABILITY, "Scrape health"),
            rule("tcpcheck_",           GROUP_OBSERVABILITY, "Synthetic checks"),
            rule("jmx_",                GROUP_OBSERVABILITY, "JMX exporter"),
            // Longer than the "pg_" / "postgres_" rules below, so these win:
            // they describe the exporter's own scraping, not the database.
            rule("pg_exporter_",        GROUP_OBSERVABILITY, TECH_POSTGRES_EXPORTER),
            rule("pg_scrape_",          GROUP_OBSERVABILITY, TECH_POSTGRES_EXPORTER),
            rule("postgres_exporter_",  GROUP_OBSERVABILITY, TECH_POSTGRES_EXPORTER),

            // --- Kubernetes & hosts ----------------------------------------
            // kube_* and k8s_* are the SAME objects seen through two collectors
            // (kube-state-metrics vs the collector's k8s receivers), so the
            // technology label names the source — a pod count that disagrees
            // between them is a collector question, not a cluster question.
            rule("kube_",               GROUP_PLATFORM, "Kubernetes (kube-state-metrics)"),
            rule("k8s_",                GROUP_PLATFORM, "Kubernetes (collector)"),
            rule("kubernetes_",         GROUP_PLATFORM, "Kubernetes"),
            rule("container_",          GROUP_PLATFORM, "Containers"),
            rule("apiserver_",          GROUP_PLATFORM, "Kubernetes control plane"),
            rule("controller_runtime_", GROUP_PLATFORM, TECH_K8S_CONTROLLERS),
            rule("workqueue_",          GROUP_PLATFORM, TECH_K8S_CONTROLLERS),
            rule("leader_election_",    GROUP_PLATFORM, TECH_K8S_CONTROLLERS),
            rule("rest_client_",        GROUP_PLATFORM, TECH_K8S_CONTROLLERS),
            rule("certmanager_",        GROUP_PLATFORM, "cert-manager"),
            rule("certwatcher_",        GROUP_PLATFORM, "cert-manager"),
            rule("metallb_",            GROUP_PLATFORM, "MetalLB"),
            rule("coredns_",            GROUP_PLATFORM, "CoreDNS"),
            rule("windows_",            GROUP_PLATFORM, "Windows hosts"),

            // --- Middleware & data stores ----------------------------------
            rule("pg_",                 GROUP_MIDDLEWARE, TECH_POSTGRESQL),
            rule("postgresql_",         GROUP_MIDDLEWARE, TECH_POSTGRESQL),
            rule("postgres_",           GROUP_MIDDLEWARE, TECH_POSTGRESQL),
            rule("mssql_",              GROUP_MIDDLEWARE, "SQL Server"),
            rule("redis_",              GROUP_MIDDLEWARE, "Redis"),
            rule("kafka_",              GROUP_MIDDLEWARE, "Kafka"),
            rule("nifi_",               GROUP_MIDDLEWARE, "NiFi"),
            // NiFi's reporter emits its cluster metrics unprefixed
            // (cluster_is_clustered, cluster_connected_node_count). Four names,
            // and the only other cluster_* producer here is NiFi itself.
            rule("cluster_",            GROUP_MIDDLEWARE, "NiFi (cluster)"),
            rule("wso2_",               GROUP_MIDDLEWARE, "WSO2"),
            rule("org_wso2_",           GROUP_MIDDLEWARE, "WSO2 (Carbon MBeans)"),
            rule("org_apache_axis2_",   GROUP_MIDDLEWARE, "WSO2 (Axis2 transport)"),
            rule("org_apache_",         GROUP_MIDDLEWARE, "WSO2 (Apache MBeans)"),
            // 329 names from the JMX exporter on the WSO2 nodes. Not to be
            // confused with tomcat_* below, which is Micrometer's much smaller
            // view of the EMBEDDED Tomcat inside our own Spring Boot services.
            rule("catalina_",           GROUP_MIDDLEWARE, "Tomcat / Catalina (JMX)"),
            rule("java_lang_",          GROUP_MIDDLEWARE, "JMX platform MBeans"),
            rule("java_nio_",           GROUP_MIDDLEWARE, "JMX platform MBeans"),

            // --- Our microservices -----------------------------------------
            rule("jvm",                 GROUP_MICROSERVICE, "JVM (Micrometer)"),
            rule("spring_",             GROUP_MICROSERVICE, "Spring Boot"),
            rule("hikaricp_",           GROUP_MICROSERVICE, "HikariCP pool"),
            rule("db_pool_",            GROUP_MICROSERVICE, "HikariCP pool"),
            rule("tomcat_",             GROUP_MICROSERVICE, "Embedded Tomcat"),
            rule("http_",               GROUP_MICROSERVICE, "HTTP (OTel semconv)"),
            rule("db_client_",          GROUP_MICROSERVICE, "DB client (OTel semconv)"),
            rule("db_",                 GROUP_MICROSERVICE, "DB client (OTel semconv)"),
            rule("graphql_",            GROUP_MICROSERVICE, "GraphQL"),
            rule("frontend_",           GROUP_MICROSERVICE, "Frontend / RUM"),
            rule("executor_",           GROUP_MICROSERVICE, "Task executors"),
            rule("app_",                GROUP_MICROSERVICE, TECH_APPLICATION),
            rule("application_",        GROUP_MICROSERVICE, TECH_APPLICATION),
            rule("endpoint_",           GROUP_MICROSERVICE, TECH_APPLICATION),
            exact("queuesize",          GROUP_MICROSERVICE, TECH_APPLICATION),
            exact("processedlogs",      GROUP_MICROSERVICE, TECH_APPLICATION),
            exact("processedspans",     GROUP_MICROSERVICE, TECH_APPLICATION),
            rule("log4j2_",             GROUP_MICROSERVICE, "Logging"),
            rule("logback_",            GROUP_MICROSERVICE, "Logging"),
            // Generated by the collector's spanmetrics connector, but they
            // DESCRIBE our services' request rate, errors and duration, and
            // that is the question they get asked. Grouped by subject, not by
            // whichever process did the arithmetic.
            rule("traces_spanmetrics_", GROUP_MICROSERVICE, "Span metrics (RED)"),
            rule("otel_sdk_",           GROUP_MICROSERVICE, "OTel SDK"),

            // --- Shared runtime — no single owner --------------------------
            // See the class comment: each of these was confirmed to carry
            // series from several unrelated producers.
            rule("process_",            GROUP_RUNTIME, "Process runtime"),
            rule("system_",             GROUP_RUNTIME, "Host / system"),
            rule("disk_",               GROUP_RUNTIME, "Host / system"),
            rule("go_",                 GROUP_RUNTIME, "Go runtime"),
            rule("python_",             GROUP_RUNTIME, "Python runtime"),
            rule("cpython_",            GROUP_RUNTIME, "Python runtime"),
            rule("dotnet_",             GROUP_RUNTIME, ".NET runtime"),
            // Exact: "up" as a prefix would swallow any future up*_ metric.
            exact("up",                 GROUP_RUNTIME, "Target health")
    );

    private static Rule rule(String prefix, String group, String tech) {
        return new Rule(prefix, false, group, tech);
    }

    private static Rule exact(String name, String group, String tech) {
        return new Rule(name, true, group, tech);
    }

    /** What one metric name was sorted into. */
    public static final class Classification {
        private final String group;
        private final String groupLabel;
        private final String technology;

        Classification(String group, String groupLabel, String technology) {
            this.group = group;
            this.groupLabel = groupLabel;
            this.technology = technology;
        }

        public String getGroup()      { return group; }
        public String getGroupLabel() { return groupLabel; }
        public String getTechnology() { return technology; }

        /**
         * True when this group's metrics come from many producers, so the name
         * alone does not identify an owner and the UI should offer a
         * {@code service_name} filter instead of implying one.
         */
        public boolean isSharedAcrossProducers() {
            return GROUP_RUNTIME.equals(group);
        }
    }

    private static final Classification UNCLASSIFIED =
            new Classification(GROUP_OTHER, GROUP_LABELS.get(GROUP_OTHER), "Unclassified");

    private final List<Rule> rules;

    public MetricClassifier(MetricClassificationProperties props) {
        List<Rule> all = new ArrayList<>(BUILT_IN);
        all.addAll(parseExtra(props.getExtraRules()));
        this.rules = List.copyOf(all);
        log.info("Metric classifier: {} rule(s) ({} built-in, {} from configuration)",
                all.size(), BUILT_IN.size(), all.size() - BUILT_IN.size());
    }

    /**
     * Parses {@code prefix:group:Technology} rules, skipping — with a warning —
     * anything malformed.
     *
     * <p>Skipping rather than failing startup is deliberate: a typo in an
     * optional grouping hint must not take the service down. The cost of the
     * skip is one facet reading "Unclassified", which is visible on the screen
     * and self-diagnosing.</p>
     */
    private static List<Rule> parseExtra(List<String> raw) {
        List<Rule> out = new ArrayList<>();
        for (String entry : raw) {
            Rule rule = parseRuleEntry(entry);
            if (rule != null) {
                out.add(rule);
            }
        }
        return out;
    }

    private static Rule parseRuleEntry(String entry) {
        if (entry == null || entry.isBlank()) return null;
        String[] parts = entry.split(":", 3);
        if (parts.length != 3) {
            log.warn("Ignoring metric classification rule '{}': expected prefix:group:Technology", entry);
            return null;
        }
        String match = parts[0].trim();
        String group = parts[1].trim().toLowerCase(Locale.ROOT);
        String tech = parts[2].trim();
        if (match.isEmpty() || tech.isEmpty()) {
            log.warn("Ignoring metric classification rule '{}': empty prefix or technology", entry);
            return null;
        }
        if (isUnassignableGroup(group)) {
            log.warn("Ignoring metric classification rule '{}': unknown group '{}' (expected one of {})",
                    entry, group, GROUP_LABELS.keySet());
            return null;
        }
        boolean isExact = match.endsWith("=");
        return new Rule(isExact ? match.substring(0, match.length() - 1) : match, isExact, group, tech);
    }

    /** A group a configured rule may not name: one that does not exist, or the catch-all. */
    private static boolean isUnassignableGroup(String group) {
        return !GROUP_LABELS.containsKey(group) || GROUP_OTHER.equals(group);
    }

    /**
     * Sorts one metric name. Never null — an unmatched name is
     * {@link #GROUP_OTHER}, not an exception and not a guess.
     */
    public Classification classify(String metricName) {
        if (metricName == null || metricName.isBlank()) return UNCLASSIFIED;
        Rule best = null;
        for (Rule r : rules) {
            if (!r.matches(metricName)) continue;
            // Longest match wins; on a tie the later rule wins, which is what
            // lets configuration override a built-in of the same length.
            if (best == null || r.match.length() >= best.match.length()) {
                best = r;
            }
        }
        return best == null
                ? UNCLASSIFIED
                : new Classification(best.group, GROUP_LABELS.get(best.group), best.tech);
    }

    /** Human label for a group key, for callers building facets. */
    public static String groupLabel(String key) {
        return GROUP_LABELS.getOrDefault(key, key);
    }

    /** Group keys in display order. */
    public static List<String> groupOrder() {
        return List.copyOf(GROUP_LABELS.keySet());
    }
}
