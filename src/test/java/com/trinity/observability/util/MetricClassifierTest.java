package com.trinity.observability.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.trinity.observability.config.MetricClassificationProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cases where the rule table is doing something non-obvious.
 *
 * Not an inventory of all 3,215 names — that would test the data, not the
 * logic. What is asserted here is the behaviour a future edit to the table
 * could silently break: overlapping prefixes, exact matches, the shared-runtime
 * split, and configuration overriding a built-in.
 */
class MetricClassifierTest {

    private static MetricClassifier classifier(String... extraRules) {
        MetricClassificationProperties props = new MetricClassificationProperties();
        props.setExtraRules(List.of(extraRules));
        return new MetricClassifier(props);
    }

    @Test
    void sortsEachPopulationIntoItsOwnGroup() {
        MetricClassifier c = classifier();

        assertEquals(MetricClassifier.GROUP_MICROSERVICE, c.classify("jvm_memory_used_bytes").getGroup());
        assertEquals(MetricClassifier.GROUP_MIDDLEWARE, c.classify("kafka_consumer_commit_rate").getGroup());
        assertEquals(MetricClassifier.GROUP_PLATFORM, c.classify("kube_pod_status_phase").getGroup());
        assertEquals(MetricClassifier.GROUP_OBSERVABILITY, c.classify("zo_compact_pending_jobs").getGroup());
        assertEquals(MetricClassifier.GROUP_RUNTIME, c.classify("go_gc_duration_seconds").getGroup());
    }

    /**
     * The overlap that motivated longest-prefix-wins: the exporter's own scrape
     * health is not a fact about the database it scrapes.
     */
    @Test
    void longerPrefixWinsOverShorter() {
        MetricClassifier c = classifier();

        assertEquals("PostgreSQL", c.classify("pg_database_size_bytes").getTechnology());
        assertEquals("Postgres exporter", c.classify("pg_exporter_scrapes_total").getTechnology());
        assertEquals(MetricClassifier.GROUP_MIDDLEWARE, c.classify("pg_locks_count").getGroup());
        assertEquals(MetricClassifier.GROUP_OBSERVABILITY, c.classify("pg_exporter_last_scrape_error").getGroup());

        // Same shape, WSO2's MBean namespaces.
        assertEquals("WSO2 (Axis2 transport)", c.classify("org_apache_axis2_transport_bytessent").getTechnology());
        assertEquals("WSO2 (Apache MBeans)", c.classify("org_apache_synapse_something").getTechnology());
        assertEquals("WSO2 (Carbon MBeans)", c.classify("org_wso2_carbon_something").getTechnology());
    }

    /**
     * Two Tomcats. catalina_* is the JMX exporter on the WSO2 nodes; tomcat_* is
     * Micrometer's view of the embedded server inside our own services. Reading
     * them as one product would put 329 third-party names under "Microservices".
     */
    @Test
    void separatesJmxTomcatFromEmbeddedTomcat() {
        MetricClassifier c = classifier();

        assertEquals(MetricClassifier.GROUP_MIDDLEWARE, c.classify("catalina_threadpool_currentthreadcount").getGroup());
        assertEquals(MetricClassifier.GROUP_MICROSERVICE, c.classify("tomcat_sessions_active_current").getGroup());
    }

    @Test
    void exactRulesDoNotMatchByPrefix() {
        MetricClassifier c = classifier();

        assertEquals(MetricClassifier.GROUP_RUNTIME, c.classify("up").getGroup());
        assertEquals("Target health", c.classify("up").getTechnology());
        // Would have been swallowed by a bare "up" prefix rule.
        assertEquals(MetricClassifier.GROUP_OTHER, c.classify("upstream_requests_total").getGroup());
    }

    /**
     * The honesty requirement: metrics confirmed to carry series from several
     * unrelated producers must not be attributed to any one of them.
     */
    @Test
    void marksMultiProducerMetricsAsShared() {
        MetricClassifier c = classifier();

        assertTrue(c.classify("process_cpu_seconds_total").isSharedAcrossProducers());
        assertTrue(c.classify("system_cpu_utilization").isSharedAcrossProducers());
        assertTrue(c.classify("up").isSharedAcrossProducers());

        assertFalse(c.classify("jvm_memory_used_bytes").isSharedAcrossProducers());
        assertFalse(c.classify("pg_database_size_bytes").isSharedAcrossProducers());
    }

    @Test
    void unmatchedNamesAreVisibleRatherThanGuessed() {
        MetricClassifier c = classifier();

        MetricClassifier.Classification unknown = c.classify("some_new_exporter_thing_total");
        assertEquals(MetricClassifier.GROUP_OTHER, unknown.getGroup());
        assertEquals("Unclassified", unknown.getTechnology());

        assertEquals(MetricClassifier.GROUP_OTHER, c.classify(null).getGroup());
        assertEquals(MetricClassifier.GROUP_OTHER, c.classify("  ").getGroup());
    }

    @Test
    void configuredRulesAddNewTechnologies() {
        MetricClassifier c = classifier("rabbitmq_:middleware:RabbitMQ");

        assertEquals(MetricClassifier.GROUP_MIDDLEWARE, c.classify("rabbitmq_queue_messages").getGroup());
        assertEquals("RabbitMQ", c.classify("rabbitmq_queue_messages").getTechnology());
    }

    /** An extra rule of the same length as a built-in corrects it. */
    @Test
    void configuredRulesOverrideBuiltInsOfEqualLength() {
        MetricClassifier c = classifier("cluster_:platform:Cluster state");

        assertEquals(MetricClassifier.GROUP_PLATFORM, c.classify("cluster_is_clustered").getGroup());
        assertEquals("Cluster state", c.classify("cluster_is_clustered").getTechnology());
    }

    @Test
    void configuredExactRuleUsesTrailingEquals() {
        MetricClassifier c = classifier("queuesize=:middleware:Broker");

        assertEquals("Broker", c.classify("queuesize").getTechnology());
        // The rule must not leak onto names that merely start with it.
        assertEquals(MetricClassifier.GROUP_OTHER, c.classify("queuesizes_total").getGroup());
    }

    /** A typo in an optional grouping hint must not take the service down. */
    @Test
    void malformedRulesAreSkippedNotFatal() {
        MetricClassifier c = classifier(
                "no-colons-here",
                "prefix_:nosuchgroup:Whatever",
                ":middleware:Empty prefix",
                "trailing_:middleware:",
                "valid_:middleware:Valid");

        assertEquals(MetricClassifier.GROUP_OTHER, c.classify("prefix_thing").getGroup());
        assertEquals(MetricClassifier.GROUP_OTHER, c.classify("trailing_thing").getGroup());
        assertEquals("Valid", c.classify("valid_thing").getTechnology());
    }

    @Test
    void groupOrderPutsOurServicesFirstAndUnclassifiedLast() {
        List<String> order = MetricClassifier.groupOrder();

        assertEquals(MetricClassifier.GROUP_MICROSERVICE, order.get(0));
        assertEquals(MetricClassifier.GROUP_OTHER, order.get(order.size() - 1));
        assertEquals("Microservices", MetricClassifier.groupLabel(MetricClassifier.GROUP_MICROSERVICE));
    }
}
