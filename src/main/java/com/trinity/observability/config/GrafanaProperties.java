package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Which Grafana this service reads, and how the Dashboard Catalog stores what an
 * administrator has registered out of it.
 *
 * <h2>Why the backend is involved at all</h2>
 *
 * <p>Dashboards are rendered natively in the browser by {@code @grafana/scenes} —
 * no iframe, no Grafana chrome. What the browser must NOT hold is the credential:
 * listing folders, reading a dashboard's JSON and running its panel queries all
 * need a Grafana service-account token, and a token reachable from the browser is
 * a token any user can lift and replay against the whole Grafana instance. So this
 * service is the only thing that ever sees it, and the UI talks exclusively to
 * {@code /api/dashboards}.</p>
 *
 * <h2>Grafana stays the source of truth</h2>
 *
 * <p>Nothing here stores a dashboard. The registry holds only which UIDs this
 * application exposes and where it files them; the definition is re-read from
 * Grafana every time a dashboard is opened. A panel edited in Grafana therefore
 * changes here on the next open, with no rebuild and no configuration change.</p>
 *
 * <p>Bound by the package-wide component scan rather than an
 * {@code @EnableConfigurationProperties}, matching {@link InfrastructureProperties}
 * and {@code TraceLookupProperties}.</p>
 */
@Component
@ConfigurationProperties(prefix = "grafana")
public class GrafanaProperties {

    /**
     * Master switch. When false the endpoints still answer — with
     * {@code supported=false} and empty lists — rather than 404ing, so the UI can
     * say "dashboards are not configured on this deployment" instead of rendering
     * a screen that looks broken.
     */
    private boolean enabled = true;

    /** Grafana origin, no trailing slash. Blank disables the feature. */
    private String baseUrl = "";

    /**
     * Service-account token used for every call.
     *
     * <p>A VIEWER-role token is enough and is what should be configured: this
     * service reads dashboards and runs their queries, and never writes. An Editor
     * or Admin token reads identically — and would also let anyone who ever
     * extracts it rewrite every dashboard in the org.</p>
     */
    private String token = "";

    /** Grafana org the dashboards live in. Reported to the UI for diagnostics. */
    private int orgId = 1;

    /**
     * Read timeout for METADATA calls — folder lists, dashboard search, a
     * dashboard's JSON. These are small, fast reads, so this doubles as a
     * stuck-connection backstop.
     */
    private int requestTimeoutSeconds = 20;

    /**
     * Read timeout for PANEL QUERIES, which are a different problem.
     *
     * <p>A dashboard panel can legitimately run a heavy PromQL aggregation over a
     * wide window and take tens of seconds. Sharing the metadata timeout meant
     * those panels failed at 20s with "Cannot reach Grafana" — a message that
     * sends whoever reads it looking at networking and certificates when the
     * query was simply still running.</p>
     */
    private int queryTimeoutSeconds = 90;

    /**
     * Trust any TLS certificate Grafana presents.
     *
     * <p>Off by default and deliberately so — it disables the check that the host
     * you reached is the host you asked for. Turn it on ONLY for an internal
     * instance behind a self-signed certificate, and prefer adding that
     * certificate to the JVM truststore where you can.</p>
     */
    private boolean tlsInsecure = false;

    /** Ceiling on rows one {@code /api/search} call may return. */
    private int searchLimit = 500;

    /**
     * Optional allow-list of Grafana folder UIDs. Non-empty means only dashboards
     * living in those folders may be registered, and only those folders are
     * offered in the register form. Empty allows the whole instance.
     */
    private List<String> allowedFolderUids = new ArrayList<>();

    private final Registry registry = new Registry();

    private final QueryCache queryCache = new QueryCache();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the configured origin with any trailing slashes removed. */
    public String getBaseUrl() {
        if (baseUrl == null) {
            return "";
        }
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token == null ? "" : token.trim();
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getOrgId() {
        return orgId;
    }

    public void setOrgId(int orgId) {
        this.orgId = orgId;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds <= 0 ? 20 : requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds <= 0 ? 90 : queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public boolean isTlsInsecure() {
        return tlsInsecure;
    }

    public void setTlsInsecure(boolean tlsInsecure) {
        this.tlsInsecure = tlsInsecure;
    }

    public int getSearchLimit() {
        return searchLimit <= 0 ? 500 : searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    /** Blank and null entries dropped, so a trailing comma in configuration is harmless. */
    public List<String> getAllowedFolderUids() {
        if (allowedFolderUids == null) {
            return List.of();
        }
        return allowedFolderUids.stream()
                .filter(uid -> uid != null && !uid.isBlank())
                .map(String::trim)
                .toList();
    }

    public void setAllowedFolderUids(List<String> allowedFolderUids) {
        this.allowedFolderUids = allowedFolderUids;
    }

    public Registry getRegistry() {
        return registry;
    }

    public QueryCache getQueryCache() {
        return queryCache;
    }

    /**
     * Whether discovery can actually be attempted. A blank base URL or token is
     * reported to the UI as {@code supported=false} rather than failing a request,
     * because "not configured on this deployment" is a legitimate state.
     */
    public boolean isUsable() {
        return enabled && !getBaseUrl().isEmpty() && !getToken().isEmpty();
    }

    /**
     * Reuse of panel query results — see {@code DashboardQueryCache}.
     *
     * <p>A result younger than {@code freshSeconds} is served as-is. One younger
     * than {@code maxStaleSeconds} is served at once and re-run in the background,
     * so the next load has fresh data. Anything older is run while the caller
     * waits, exactly as without the cache. Errors are never kept.</p>
     */
    public static class QueryCache {

        private boolean enabled = true;
        private int freshSeconds = 30;
        private int maxStaleSeconds = 120;
        private int maxEntries = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFreshSeconds() {
            return Math.max(0, freshSeconds);
        }

        public void setFreshSeconds(int freshSeconds) {
            this.freshSeconds = freshSeconds;
        }

        /** Never below {@link #getFreshSeconds()}. */
        public int getMaxStaleSeconds() {
            return Math.max(getFreshSeconds(), maxStaleSeconds);
        }

        public void setMaxStaleSeconds(int maxStaleSeconds) {
            this.maxStaleSeconds = maxStaleSeconds;
        }

        public int getMaxEntries() {
            return maxEntries <= 0 ? 500 : maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** Where registrations live, and who they belong to. */
    public static class Registry {

        /**
         * Who a registration is visible to.
         *
         * <p>{@code GLOBAL} — one list for the whole deployment. The default, and
         * the right one for this feature: Grafana knows nothing about OpenObserve
         * organizations, its folders are one shared set, and a dashboard worth
         * registering is worth registering once.</p>
         *
         * <p>{@code ORG} — a separate list per active organization, matching how
         * the Product Catalog behaves. Choose this when different tenants must not
         * see each other's dashboards.</p>
         */
        public enum Scope {
            GLOBAL,
            ORG
        }

        /**
         * Where rows are kept.
         *
         * <p>{@code AUTO} uses PostgreSQL when the alert/catalog datasource is
         * configured, and the JSON file otherwise — so the feature works on a
         * deployment with no database without anything being set. The other two
         * pin it, and PostgreSQL pinned without a datasource is a startup failure
         * rather than a silent downgrade.</p>
         */
        public enum Store {
            AUTO,
            POSTGRES,
            FILE
        }

        private Scope scope = Scope.GLOBAL;

        private Store store = Store.AUTO;

        /**
         * Registry file for the FILE store, relative to the service's working
         * directory unless absolute. Only metadata is written — never dashboard
         * JSON.
         */
        private String file = "data/grafana-dashboard-registry.json";

        public Scope getScope() {
            return scope == null ? Scope.GLOBAL : scope;
        }

        public void setScope(Scope scope) {
            this.scope = scope;
        }

        public Store getStore() {
            return store == null ? Store.AUTO : store;
        }

        public void setStore(Store store) {
            this.store = store;
        }

        public String getFile() {
            return file == null || file.isBlank()
                    ? "data/grafana-dashboard-registry.json" : file.trim();
        }

        public void setFile(String file) {
            this.file = file;
        }
    }
}
