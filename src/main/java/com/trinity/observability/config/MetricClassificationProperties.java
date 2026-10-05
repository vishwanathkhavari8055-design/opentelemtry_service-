package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator overrides for the metric catalogue's grouping.
 *
 * <h2>Why this is configuration at all</h2>
 *
 * <p>{@code /api/metrics/catalog} returns every metric name the instance knows
 * about — 3,215 on this deployment — and a flat list that long cannot be read.
 * {@link com.trinity.observability.util.MetricClassifier} splits it by name
 * prefix, which works because every exporter namespaces its metrics: {@code pg_}
 * is the Postgres exporter, {@code zo_} is OpenObserve, {@code jvm_} is
 * Micrometer inside one of our own services.</p>
 *
 * <p>That table ships with the rules this deployment needs, but the set of tools
 * being monitored changes faster than this service is rebuilt — the day someone
 * points a RabbitMQ or Elasticsearch exporter at the collector, its 200 metrics
 * would land in "Unclassified" until a release. {@link #getExtraRules()} is the
 * escape hatch: a rule added there is live on the next restart.</p>
 *
 * <h2>Rule syntax</h2>
 *
 * <p>Each rule is {@code prefix:group:Technology label}, and rules are
 * comma-separated:</p>
 *
 * <pre>
 * metrics.classification.extra-rules=rabbitmq_:middleware:RabbitMQ,\
 *                                    elasticsearch_:middleware:Elasticsearch
 * </pre>
 *
 * <p>{@code group} must be one of the keys in
 * {@link com.trinity.observability.util.MetricClassifier} — {@code microservice},
 * {@code middleware}, {@code platform}, {@code observability}, {@code runtime}.
 * An unknown group is dropped with a warning rather than inventing a facet the
 * UI has no label for.</p>
 *
 * <p>Suffix a prefix with {@code =} to match the metric name EXACTLY
 * ({@code up=:observability:Target health}). Without it the rule matches by
 * prefix, and the LONGEST matching prefix wins — which is what lets
 * {@code pg_exporter_} sit under the observability stack while the rest of
 * {@code pg_} stays under PostgreSQL.</p>
 */
@Component
@ConfigurationProperties(prefix = "metrics.classification")
public class MetricClassificationProperties {

    /**
     * Extra rules, appended after the built-ins.
     *
     * <p>Appended, not merged: on a prefix of the same length an extra rule wins,
     * so a deployment can also CORRECT a built-in it disagrees with (for example
     * moving {@code cluster_} off NiFi) without touching the classifier.</p>
     */
    private List<String> extraRules = new ArrayList<>();

    public List<String> getExtraRules()             { return extraRules; }
    public void setExtraRules(List<String> v)       { this.extraRules = v == null ? new ArrayList<>() : v; }
}
