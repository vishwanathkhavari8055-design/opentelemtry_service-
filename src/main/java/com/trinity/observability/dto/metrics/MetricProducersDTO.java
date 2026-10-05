package com.trinity.observability.dto.metrics;

import java.util.List;

/**
 * Which service exposes which metrics — the catalogue inverted.
 *
 * <h2>Two lists per service, because {@code service_name} conflates two things</h2>
 *
 * <p>{@code service_name} says which OTel resource a sample was attached to,
 * and that is NOT the same as "this service exposes this metric". Measured on
 * this instance: twenty-odd pods each carry exactly 200 {@code kube_*} metrics
 * with their own name in {@code service_name} — because kube-state-metrics
 * describes every pod in the cluster, and the collector stamps the described
 * pod's identity onto the sample. Reading those as exposed metrics would claim
 * that {@code tiotmlinferencesvc} exports 200 Kubernetes metrics, when it
 * exports none at all.</p>
 *
 * <p>So each service carries {@link Service#getEmitted()} — its own
 * instrumentation — and {@link Service#getObserved()} — platform telemetry
 * about it. The split is by classified group: {@code platform} metrics are
 * observations, everything else is the service's own.</p>
 *
 * <h2>The kinds, and why "scrape-only" is one of them</h2>
 *
 * <p>{@link Service#getKind()} is derived from the EMITTED set only, so a pod
 * that kube-state-metrics happens to describe is not promoted to a
 * microservice. Beyond the classifier's groups there are two kinds that only
 * exist once the catalogue is inverted, and both are findings rather than
 * labels:</p>
 *
 * <ul>
 *   <li>{@code scrape-only} — the emitted set is nothing but {@code up} and
 *       {@code scrape_*}: we are scraping the target and it exposes no
 *       telemetry of its own. 44 of them here.</li>
 *   <li>{@code observed-only} — nothing emitted at all; the name is known only
 *       because platform telemetry mentions it. 213 of them here.</li>
 * </ul>
 *
 * <p>Those two numbers are the instrumentation gap, and a screen that hid them
 * behind "no metrics found" would be hiding the most actionable thing here.</p>
 */
public class MetricProducersDTO {

    private boolean supported = true;
    private String error;

    /** True while a build is running; {@code services} may be a partial sweep. */
    private boolean building;
    /**
     * True when the served index came from a sweep that had not finished.
     *
     * <p>A running sweep republishes as it goes, so the screen fills in instead
     * of waiting eighty seconds for services nobody asked about. The flag is
     * the honesty half of that: counts are lower bounds and services may still
     * be missing, and a partial index presented as complete would read as "that
     * service exposes nothing".</p>
     */
    private boolean partial;
    /** Probes completed / to do, for a progress indicator. Both 0 when idle. */
    private int progressDone;
    private int progressTotal;

    /** When the served index finished building, ISO-8601. Null before the first build. */
    private String builtAt;
    /** Age of the served index in seconds — the UI labels it rather than hiding it. */
    private long ageSeconds;
    /** How long the build took, for the operator wondering what it costs. */
    private long buildMillis;
    /** Look-back window the probes used, so a "no metrics" answer is readable. */
    private int windowSeconds;

    private List<Service> services;

    /** Metrics that exist in the catalogue but wrote nothing inside the window. */
    private int metricsWithNoData;
    /** Metrics present in the window but carrying no {@code service_name} at all. */
    private int metricsUnscoped;
    /** Metrics probed in total — reconciles with the catalogue's count. */
    private int metricsProbed;
    /** Probes that failed even after a retry. Non-zero means the index is partial. */
    private int probeFailures;

    /** One producer: a distinct {@code service_name}, and what it accounts for. */
    public static class Service {
        private String name;
        /**
         * microservice | middleware | platform | observability | runtime |
         * scrape-only | observed-only — derived from the emitted set only.
         */
        private String kind;
        private String kindLabel;

        /** Metrics this service exposes itself. */
        private Bucket emitted;
        /** Platform metrics that describe this service but are emitted elsewhere. */
        private Bucket observed;

        public Service() {}

        public Service(String name, String kind, String kindLabel, Bucket emitted, Bucket observed) {
            this.name = name;
            this.kind = kind;
            this.kindLabel = kindLabel;
            this.emitted = emitted;
            this.observed = observed;
        }

        public String getName()      { return name; }
        public String getKind()      { return kind; }
        public String getKindLabel() { return kindLabel; }
        public Bucket getEmitted()   { return emitted; }
        public Bucket getObserved()  { return observed; }
        public void setName(String v)       { this.name = v; }
        public void setKind(String v)       { this.kind = v; }
        public void setKindLabel(String v)  { this.kindLabel = v; }
        public void setEmitted(Bucket v)    { this.emitted = v; }
        public void setObserved(Bucket v)   { this.observed = v; }
    }

    /**
     * A set of metrics with a per-technology breakdown.
     *
     * <p>The metrics are carried in full, not just counted: the question this
     * endpoint answers is "which metrics", and a count would send the caller
     * back for 363 follow-up requests.</p>
     */
    public static class Bucket {
        private int count;
        private List<MetricCatalogDTO.Technology> technologies;
        private List<Metric> metrics;

        public Bucket() {}

        public Bucket(int count, List<MetricCatalogDTO.Technology> technologies, List<Metric> metrics) {
            this.count = count;
            this.technologies = technologies;
            this.metrics = metrics;
        }

        public int getCount()                     { return count; }
        public List<MetricCatalogDTO.Technology> getTechnologies() { return technologies; }
        public List<Metric> getMetrics()          { return metrics; }
        public void setCount(int v)               { this.count = v; }
        public void setTechnologies(List<MetricCatalogDTO.Technology> v) { this.technologies = v; }
        public void setMetrics(List<Metric> v)    { this.metrics = v; }
    }

    /**
     * One metric in a bucket, carrying the two catalogue fields the caller
     * needs to render it: which technology it belongs to (so the list can be
     * grouped rather than dumped) and its Prometheus type (so a counter is
     * still marked as one here, and not plotted bare).
     */
    public static class Metric {
        private String name;
        private String technology;
        private String type;

        public Metric() {}

        public Metric(String name, String technology, String type) {
            this.name = name;
            this.technology = technology;
            this.type = type;
        }

        public String getName()       { return name; }
        public String getTechnology() { return technology; }
        public String getType()       { return type; }
        public void setName(String v)       { this.name = v; }
        public void setTechnology(String v) { this.technology = v; }
        public void setType(String v)       { this.type = v; }
    }

    public boolean isSupported()   { return supported; }
    public String getError()       { return error; }
    public boolean isBuilding()    { return building; }
    public boolean isPartial()     { return partial; }
    public int getProgressDone()   { return progressDone; }
    public int getProgressTotal()  { return progressTotal; }
    public String getBuiltAt()     { return builtAt; }
    public long getAgeSeconds()    { return ageSeconds; }
    public long getBuildMillis()   { return buildMillis; }
    public int getWindowSeconds()  { return windowSeconds; }
    public List<Service> getServices()  { return services; }
    public int getMetricsWithNoData()   { return metricsWithNoData; }
    public int getMetricsUnscoped()     { return metricsUnscoped; }
    public int getMetricsProbed()       { return metricsProbed; }
    public int getProbeFailures()       { return probeFailures; }
    public void setSupported(boolean v)      { this.supported = v; }
    public void setError(String v)           { this.error = v; }
    public void setBuilding(boolean v)       { this.building = v; }
    public void setPartial(boolean v)        { this.partial = v; }
    public void setProgressDone(int v)       { this.progressDone = v; }
    public void setProgressTotal(int v)      { this.progressTotal = v; }
    public void setBuiltAt(String v)         { this.builtAt = v; }
    public void setAgeSeconds(long v)        { this.ageSeconds = v; }
    public void setBuildMillis(long v)       { this.buildMillis = v; }
    public void setWindowSeconds(int v)      { this.windowSeconds = v; }
    public void setServices(List<Service> v) { this.services = v; }
    public void setMetricsWithNoData(int v)  { this.metricsWithNoData = v; }
    public void setMetricsUnscoped(int v)    { this.metricsUnscoped = v; }
    public void setMetricsProbed(int v)      { this.metricsProbed = v; }
    public void setProbeFailures(int v)      { this.probeFailures = v; }
}
