package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the Product Catalog is allowed to differ on between deployments.
 *
 * <p>Nothing in this module hard-codes a stream, a column, a window or a
 * threshold: the probe list below IS the validation logic's extensibility point,
 * so teaching the catalog to recognise a new kind of resource (Kafka by
 * {@code k8s_deployment_name}, Redis by an exporter's {@code service_name}) is a
 * configuration change, not a code change.</p>
 *
 * <h2>The two filter modes, and why the default is the conservative one</h2>
 *
 * <p>{@link FilterMode#EXCLUDE_DISABLED} (default) removes only what an operator
 * has explicitly disabled. An empty catalog therefore changes nothing — which
 * matters, because the registry starts empty on a fresh deployment and the
 * alternative would blank every Logs, Traces and Metrics screen in the product
 * until somebody registered their first resource.</p>
 *
 * <p>{@link FilterMode#ENABLED_ONLY} is the strict allow-list: once ANY resource
 * is registered, only registered-and-enabled resources are visible anywhere.
 * That is the stronger reading of "return only telemetry belonging to enabled
 * resources", and it is available by flipping one property — but it is opt-in,
 * because turning it on hides every service nobody has got around to
 * registering yet.</p>
 */
@ConfigurationProperties(prefix = "product-catalog")
public class ProductCatalogProperties {

    /** How the registry decides what telemetry the rest of the app may see. */
    public enum FilterMode {
        /** Hide only resources an operator disabled. Safe on an empty registry. */
        EXCLUDE_DISABLED,
        /** Allow-list: with a non-empty registry, show only enabled resources. */
        ENABLED_ONLY
    }

    /** Where the registry keeps its state between restarts. Never a database. */
    public enum PersistenceMode {
        /** In-memory only. Registrations are lost on restart. */
        NONE,
        /**
         * Append each change to an OpenObserve stream and replay it on first
         * read. Uses the same ingest mechanism the alert feed already relies on,
         * so it introduces no new dependency and no schema to migrate.
         */
        OPENOBSERVE_STREAM
    }

    private static final String COLUMN_SERVICE_NAME = "service_name";
    private static final String SIGNAL_TRACES = "traces";
    private static final String SIGNAL_METRICS = "metrics";
    private static final String STREAM_TOKEN_LOGS = "@logs";
    private static final String STREAM_TOKEN_TRACES = "@traces";

    /** Master switch. When false the API answers but never filters anything. */
    private boolean enabled = true;

    private Filter filter = new Filter();
    private Validation validation = new Validation();
    private Persistence persistence = new Persistence();

    /**
     * Where registrations are STORED.
     *
     * <p>POSTGRES is the default now, and it is a correctness fix rather than a
     * preference. The previous store was an in-memory map made durable by an
     * append-only OpenObserve stream, which meant the catalog's durability lived
     * inside the very system it describes — so changing the OpenObserve address
     * emptied the catalog, and no amount of retrying brought it back. PostgreSQL
     * was already the source of truth for alert rules and alerts; the catalog being
     * the exception is exactly why it was the one thing that did not survive.</p>
     *
     * <p>MEMORY restores the old behaviour exactly, including the stream store
     * configured under {@code product-catalog.persistence.*}. Nothing was removed —
     * a deployment that cannot add a database keeps working on one property.</p>
     */
    private CatalogStore store = CatalogStore.POSTGRES;

    /** The two registries this module ships. */
    public enum CatalogStore {
        /** PostgreSQL {@code catalog_resources} — survives an OpenObserve change. */
        POSTGRES,
        /** In-memory, durable via the OpenObserve change-log stream. */
        MEMORY
    }

    /** Resources present from startup, declared in configuration. */
    private List<Seed> seed = new ArrayList<>();

    public CatalogStore getStore() {
        return store;
    }

    public void setStore(CatalogStore store) {
        this.store = store == null ? CatalogStore.POSTGRES : store;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Filter getFilter() {
        return filter;
    }

    public void setFilter(Filter filter) {
        this.filter = filter == null ? new Filter() : filter;
    }

    public Validation getValidation() {
        return validation;
    }

    public void setValidation(Validation validation) {
        this.validation = validation == null ? new Validation() : validation;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence == null ? new Persistence() : persistence;
    }

    public List<Seed> getSeed() {
        return seed;
    }

    public void setSeed(List<Seed> seed) {
        this.seed = seed == null ? new ArrayList<>() : seed;
    }

    /** {@code product-catalog.filter.*} */
    public static class Filter {

        /**
         * Kill switch for the telemetry integration alone. Off means the
         * catalog is a pure registry: it lists and validates resources, and
         * Logs / Traces / Metrics behave exactly as they did before this module
         * existed. Useful for isolating a suspected regression without
         * unregistering anything.
         */
        private boolean enabled = true;

        private FilterMode mode = FilterMode.EXCLUDE_DISABLED;

        /**
         * Identifier columns that may appear in a filter predicate, per signal.
         *
         * <p>This is a safety list, not a preference. A predicate naming a
         * column that does not exist on the queried stream is a DataFusion
         * error, i.e. a blank screen — and metrics are the hazard, because
         * OpenObserve keeps one stream per metric name and only
         * {@code service_name} is reliably present across all of them. Logs and
         * traces are single wide streams, so the richer set is safe there.</p>
         */
        private Map<String, List<String>> columns = defaultColumns();

        /**
         * Mutable map of mutable lists, deliberately.
         *
         * <p>Spring's relaxed binding may populate a Map or List property by
         * mutating whatever the getter returns rather than calling the setter, so
         * {@code List.of(…)} here would make a deployment that overrides one of
         * these keys fail at startup with an UnsupportedOperationException.</p>
         */
        private static Map<String, List<String>> defaultColumns() {
            Map<String, List<String>> defaults = new LinkedHashMap<>();
            defaults.put("logs", new ArrayList<>(List.of(COLUMN_SERVICE_NAME, "k8s_deployment_name",
                    "k8s_namespace_name", "host_name")));
            defaults.put(SIGNAL_TRACES, new ArrayList<>(List.of(COLUMN_SERVICE_NAME, "db_name", "db_system")));
            defaults.put(SIGNAL_METRICS, new ArrayList<>(List.of(COLUMN_SERVICE_NAME)));
            return defaults;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public FilterMode getMode() {
            return mode;
        }

        public void setMode(FilterMode mode) {
            this.mode = mode == null ? FilterMode.EXCLUDE_DISABLED : mode;
        }

        public Map<String, List<String>> getColumns() {
            return columns;
        }

        public void setColumns(Map<String, List<String>> columns) {
            this.columns = (columns == null || columns.isEmpty()) ? defaultColumns() : columns;
        }

        /** Columns allowed for a signal; empty list when the signal is unknown. */
        public List<String> columnsFor(String signal) {
            List<String> allowed = columns.get(signal == null ? "" : signal.toLowerCase(Locale.ROOT));
            return allowed == null ? List.of() : allowed;
        }
    }

    /** {@code product-catalog.validation.*} */
    public static class Validation {

        /**
         * Require telemetry to exist before a registration is accepted.
         *
         * <p>On by default — it is the whole point of the Validate step. Turning
         * it off lets a resource be pre-registered before its agent is
         * deployed; it will simply carry no bindings and no Last Seen until
         * telemetry appears.
         */
        private boolean required = true;

        /** Lookback for discovery, in the {@code now-24h} form used elsewhere. */
        private String window = "now-24h";

        /**
         * Narrower windows retried when {@link #window} finds nothing at all.
         *
         * <p>This is not belt-and-braces; it is required to make validation work
         * on this deployment. OpenObserve reports a window it cannot scan as
         * <b>HTTP 200 with an empty {@code hits} array</b> and a
         * {@code function_error} of
         * {@code {"code":20006,"message":"Search parquet file not found"}} — the
         * same silent-empty failure documented on
         * {@code LogsServiceImpl.listServices}, which once made that endpoint
         * report 18 of 106 services. Verified live: this instance serves a 6h
         * scan of the logs stream and fails a 24h one, while the stream is
         * actively receiving data.</p>
         *
         * <p>Because the failure is indistinguishable from a genuine miss at this
         * layer, the only reliable response is to narrow and retry. A retry costs
         * nothing when the primary window works, and when a narrower one is what
         * succeeds that fact is logged at WARN — the ops signal that the primary
         * window is not being served.</p>
         *
         * <p>Kept short deliberately: each entry re-runs the whole probe chain,
         * so this is the trade between finding a low-volume resource and how long
         * a Validate click takes to answer "no".</p>
         */
        private List<String> fallbackWindows = new ArrayList<>(List.of("now-6h", "now-1h"));

        /**
         * How long a Last Seen value is served from cache before the list
         * endpoint re-queries OpenObserve. The UI polls, so 0 would mean a
         * query storm; the refresher is also batched into one query per
         * (stream, column) pair regardless of how many resources are listed.
         */
        private int livenessTtlSeconds = 60;

        /**
         * Probe queries in flight at once, across the whole application.
         *
         * <p>Discovery used to run its probes strictly one after another, and each
         * one is a ~750 ms round trip to OpenObserve. Measured on this deployment
         * that made a successful Validate take 6 s and a failed one 20 s — nine
         * probes over three windows, in series. Running a window's probes together
         * turns that window into roughly one round trip.</p>
         *
         * <p>The default matches the number of probes in {@link #probes}, so the
         * default chain finishes a window in a single wave. It is a bound shared by
         * the whole application rather than a per-validation one on purpose:
         * discovery is also triggered by the catalog list endpoint for resources
         * with no bindings yet, and a per-call fan-out would let one screen refresh
         * put dozens of simultaneous queries on OpenObserve. Here the extra work
         * queues instead. Set to 1 to go back to sequential.</p>
         */
        private int probeConcurrency = 9;

        /**
         * How long a SUCCESSFUL discovery may be reused by the registration path.
         *
         * <p>Registration validates twice: the operator clicks Validate, and then
         * Register re-runs the identical probe chain a second later. On this
         * deployment one chain costs several seconds, so the flow paid it twice —
         * which is what made registering feel slow. Register reuses what Validate
         * just proved, if it is this fresh.</p>
         *
         * <p>Misses are never reused, at any TTL: someone who retries after "not
         * found" has almost always just started the agent, and answering from cache
         * would refuse a resource that now exists. Keep this short — it is the
         * window in which telemetry could stop and a registration still succeed.
         * 0 disables reuse and makes Register re-query every time.</p>
         */
        private int discoveryCacheSeconds = 120;

        /**
         * Ordered identifier probes. First hit per signal wins, so put the
         * cheapest and most canonical first — {@code service_name} answers for
         * almost every resource, and the rest only run when it does not.
         *
         * <p>{@code stream} accepts two tokens resolved at runtime:
         * {@code @logs} → {@code openobserve.logs-stream} and {@code @traces} →
         * {@code openobserve.traces-stream}. Anything else is a literal stream
         * name, which is what metric probes need (one stream per metric).</p>
         */
        private List<Probe> probes = defaultProbes();

        private static List<Probe> defaultProbes() {
            List<Probe> defaults = new ArrayList<>();
            // service_name first, for all three signals — the canonical OTel
            // resource identifier and the column every existing query filters on.
            defaults.add(new Probe("logs", STREAM_TOKEN_LOGS, COLUMN_SERVICE_NAME));
            defaults.add(new Probe(SIGNAL_TRACES, STREAM_TOKEN_TRACES, COLUMN_SERVICE_NAME));
            defaults.add(new Probe(SIGNAL_METRICS, "jvm_memory_used", COLUMN_SERVICE_NAME));
            // Non-JVM resources heartbeat through the Actuator health gauge the
            // collector scrapes; try it before giving up on the metrics signal.
            defaults.add(new Probe(SIGNAL_METRICS, "application_health", COLUMN_SERVICE_NAME));
            // "database name" from the requirement — a datastore that is not
            // itself instrumented still appears as the target of client spans.
            defaults.add(new Probe(SIGNAL_TRACES, STREAM_TOKEN_TRACES, "db_name"));
            defaults.add(new Probe(SIGNAL_TRACES, STREAM_TOKEN_TRACES, "db_system"));
            // "tool name" / "platform name" — infrastructure whose logs are
            // scraped from Kubernetes rather than emitted by an SDK, so they
            // carry no service_name of their own.
            defaults.add(new Probe("logs", STREAM_TOKEN_LOGS, "k8s_deployment_name"));
            defaults.add(new Probe("logs", STREAM_TOKEN_LOGS, "k8s_namespace_name"));
            defaults.add(new Probe("logs", STREAM_TOKEN_LOGS, "host_name"));
            return defaults;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = (window == null || window.isBlank()) ? "now-24h" : window.trim();
        }

        public List<String> getFallbackWindows() {
            return fallbackWindows;
        }

        public void setFallbackWindows(List<String> fallbackWindows) {
            // An explicitly empty list is meaningful — "do not retry" — so unlike
            // the probe list this does NOT fall back to the defaults when cleared.
            this.fallbackWindows = fallbackWindows == null
                    ? new ArrayList<>()
                    : new ArrayList<>(fallbackWindows);
        }

        public int getLivenessTtlSeconds() {
            return livenessTtlSeconds;
        }

        public void setLivenessTtlSeconds(int livenessTtlSeconds) {
            this.livenessTtlSeconds = Math.max(0, livenessTtlSeconds);
        }

        public int getProbeConcurrency() {
            return probeConcurrency;
        }

        public void setProbeConcurrency(int probeConcurrency) {
            this.probeConcurrency = Math.max(1, probeConcurrency);
        }

        public int getDiscoveryCacheSeconds() {
            return discoveryCacheSeconds;
        }

        public void setDiscoveryCacheSeconds(int discoveryCacheSeconds) {
            this.discoveryCacheSeconds = Math.max(0, discoveryCacheSeconds);
        }

        public List<Probe> getProbes() {
            return probes;
        }

        public void setProbes(List<Probe> probes) {
            this.probes = (probes == null || probes.isEmpty()) ? defaultProbes() : probes;
        }
    }

    /** One identifier the validator knows how to look for. */
    public static class Probe {

        /** {@code logs}, {@code traces} or {@code metrics}. */
        private String signal;

        /** Stream name, or the {@code @logs} / {@code @traces} token. */
        private String stream;

        /** Identifier column compared against the resource name. */
        private String column;

        public Probe() {
            // Relaxed binding from configuration.
        }

        public Probe(String signal, String stream, String column) {
            this.signal = signal;
            this.stream = stream;
            this.column = column;
        }

        public String getSignal() {
            return signal;
        }

        public void setSignal(String signal) {
            this.signal = signal;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        @Override
        public String toString() {
            return signal + ':' + stream + ':' + column;
        }
    }

    /** {@code product-catalog.persistence.*} */
    public static class Persistence {

        private PersistenceMode mode = PersistenceMode.OPENOBSERVE_STREAM;

        /** Stream the change log is appended to. Created on the first write. */
        private String stream = "product_catalog";

        /** How far back the change log is replayed when an org is first read. */
        private int replayDays = 365;

        public PersistenceMode getMode() {
            return mode;
        }

        public void setMode(PersistenceMode mode) {
            this.mode = mode == null ? PersistenceMode.OPENOBSERVE_STREAM : mode;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = (stream == null || stream.isBlank()) ? "product_catalog" : stream.trim();
        }

        public int getReplayDays() {
            return replayDays;
        }

        public void setReplayDays(int replayDays) {
            this.replayDays = Math.max(1, replayDays);
        }
    }

    /** One {@code product-catalog.seed[*]} entry. */
    public static class Seed {

        private String category;
        private String resourceName;

        /** Default ENABLED; set DISABLED to seed a resource that starts hidden. */
        private String status;

        /**
         * Skip telemetry validation for this entry. Seeds are declared by an
         * operator who already knows the resource exists, and a seed that
         * failed validation because the agent had not started yet would
         * silently vanish from the catalog — so seeds default to trusted and
         * pick up their bindings on the first liveness refresh.
         */
        private boolean validate = false;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getResourceName() {
            return resourceName;
        }

        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public boolean isValidate() {
            return validate;
        }

        public void setValidate(boolean validate) {
            this.validate = validate;
        }
    }
}
