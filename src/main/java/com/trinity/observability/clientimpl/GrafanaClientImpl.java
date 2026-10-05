package com.trinity.observability.clientimpl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.GrafanaClientService;
import com.trinity.observability.config.GrafanaConfig;
import com.trinity.observability.config.GrafanaProperties;
import com.trinity.observability.exception.GrafanaException;

/**
 * Grafana over HTTP.
 *
 * <p>Two things here are load-bearing beyond the plumbing.</p>
 *
 * <p><b>Failures are translated, not forwarded.</b> Grafana's own errors are
 * unhelpful out of context — a bare 403 on {@code /api/search} looks identical
 * whether the token is wrong, expired, or merely lacks folder permissions. Every
 * upstream failure becomes a {@link GrafanaException} carrying a sentence that
 * names the fix, because the person reading it is looking at a UI banner and not
 * at Grafana's source.</p>
 *
 * <p><b>404 is data, not an error.</b> A registration whose dashboard was deleted
 * in Grafana is an expected state — the catalog outlives the dashboards it points
 * at — so {@link #dashboard(String)} answers null and lets the caller say
 * "registered but missing" instead of failing the request.</p>
 */
@Service
public class GrafanaClientImpl implements GrafanaClientService {

    private static final Logger log = LogManager.getLogger(GrafanaClientImpl.class);

    /**
     * What a Grafana UID may contain.
     *
     * <p>Applied to every UID before it is interpolated into a path. UIDs arrive
     * from the browser (the register form takes one by hand), and Grafana's own
     * alphabet is this narrow — so anything outside it is a malformed request, and
     * refusing it here is what stops {@code ../} or a stray {@code ?} from turning
     * a dashboard read into some other Grafana call entirely.</p>
     */
    private static final Pattern UID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Default label-values window when the caller names none: the last six hours. */
    private static final long DEFAULT_LOOKBACK_SECONDS = 6 * 60 * 60L;

    /** Rows per page while enumerating folders. Grafana's search caps this at 5000. */
    private static final int FOLDER_PAGE_SIZE = 1000;

    /**
     * Pages the folder walk will read at most — 10,000 folders, far past any real
     * instance. A backstop, not a limit: a Grafana that ignored {@code page} would
     * otherwise return the same page forever.
     */
    private static final int FOLDER_MAX_PAGES = 10;
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_FOLDER_UID = "folderUid";

    private final GrafanaConfig config;
    private final ObjectMapper json = new ObjectMapper();

    public GrafanaClientImpl(GrafanaConfig config) {
        this.config = config;
    }

    private RestClient client() {
        return config.client();
    }

    private GrafanaProperties props() {
        return config.properties();
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Override
    public List<DashboardHit> searchDashboards(String folderUid, String query) {
        StringBuilder path = new StringBuilder("/api/search?type=dash-db&limit=")
                .append(props().getSearchLimit());
        if (folderUid != null && !folderUid.isBlank()) {
            path.append("&folderUIDs=").append(enc(safeUid(folderUid)));
        }
        // Grafana's own `query` is a substring match on the title. Passing it
        // upstream rather than filtering here keeps the result inside the limit:
        // filtering locally would search only the first `limit` rows.
        if (query != null && !query.isBlank()) {
            path.append("&query=").append(enc(query.trim()));
        }

        JsonNode body = getJson(path.toString(), "dashboard search");
        List<DashboardHit> hits = new ArrayList<>();
        if (body != null && body.isArray()) {
            for (JsonNode node : body) {
                hits.add(new DashboardHit(
                        text(node, "uid"),
                        text(node, FIELD_TITLE),
                        text(node, "url"),
                        text(node, FIELD_FOLDER_UID),
                        text(node, "folderTitle"),
                        strings(node.path("tags"))));
            }
        }
        log.debug("Grafana searchDashboards(folder={}, query={}): {} row(s)",
                folderUid, query, hits.size());
        return hits;
    }

    /**
     * Every folder, at every depth.
     *
     * <p><b>Why this is two calls and not one.</b> {@code /api/folders} answers with
     * ROOT-LEVEL folders only — a nested folder is invisible to it unless you ask
     * for its parent explicitly with {@code ?parentUid=}. On the instance this was
     * built against that is 39 folders out of 77: everything under
     * {@code Monitoring / OpenObserver} — the org and service folders the catalog
     * actually wants — was simply absent from the picker, and no amount of
     * refreshing would make it appear. Walking the tree with one
     * {@code ?parentUid=} call per folder would be 77 round trips.</p>
     *
     * <p>{@code /api/search?type=dash-folder} returns the whole tree flat in one
     * paged call, naming each folder's parent in {@code folderUid}. That is the
     * primary source. {@code /api/folders} is still read and unioned in, so this
     * method can never return FEWER folders than it did before nesting was
     * supported — if search is unavailable or restricted on some deployment, the
     * old root-level list is what survives rather than an empty dropdown.</p>
     */
    @Override
    public List<FolderHit> listFolders() {
        // Search first, so its rows — the only ones that carry parentage — win the
        // merge. A root folder is identical in both answers.
        Map<String, FolderHit> merged = new LinkedHashMap<>();
        GrafanaException searchFailure = null;
        try {
            for (FolderHit hit : searchFolders()) {
                merged.putIfAbsent(hit.uid(), hit);
            }
        } catch (GrafanaException e) {
            // Not fatal on its own: /api/folders below may still answer, and a
            // root-level list beats no list at all.
            log.warn("Grafana folder search failed ({}) — falling back to the root-level "
                    + "/api/folders listing, which cannot see nested folders", e.getMessage());
            searchFailure = e;
        }

        try {
            for (FolderHit hit : rootFolders()) {
                merged.putIfAbsent(hit.uid(), hit);
            }
        } catch (GrafanaException e) {
            if (merged.isEmpty()) {
                throw e;
            }
            log.warn("Grafana /api/folders failed ({}) — using the {} folder(s) the search "
                    + "returned", e.getMessage(), merged.size());
        }

        if (merged.isEmpty() && searchFailure != null) {
            throw searchFailure;
        }

        List<FolderHit> folders = new ArrayList<>(merged.values());
        log.debug("Grafana listFolders(): {} folder(s), nested included", folders.size());
        return folders;
    }

    /** Root-level folders — Grafana's {@code /api/folders}, which stops at depth 0. */
    private List<FolderHit> rootFolders() {
        JsonNode body = getJson("/api/folders?limit=" + FOLDER_PAGE_SIZE, "folder list");
        List<FolderHit> folders = new ArrayList<>();
        if (body != null && body.isArray()) {
            for (JsonNode node : body) {
                folders.add(folderHit(node));
            }
        }
        return folders;
    }

    /**
     * The whole folder tree, flat, from {@code /api/search?type=dash-folder}.
     *
     * <p>Paged rather than one large {@code limit}, because Grafana caps that
     * parameter and silently returns the first page when it is exceeded — which
     * would look exactly like "the instance has no more folders". The page loop
     * stops on the first short page, and {@link #FOLDER_MAX_PAGES} is a backstop so
     * a Grafana that ignores {@code page} cannot spin here forever.</p>
     */
    private List<FolderHit> searchFolders() {
        List<FolderHit> folders = new ArrayList<>();
        boolean hasMore = true;
        for (int page = 1; page <= FOLDER_MAX_PAGES && hasMore; page++) {
            hasMore = addFolderPage(page, folders);
        }
        return folders;
    }

    /**
     * Fetch one page of the folder search into {@code folders}.
     *
     * @return whether another page may follow: false on an empty or short page
     */
    private boolean addFolderPage(int page, List<FolderHit> folders) {
        JsonNode body = getJson("/api/search?type=dash-folder&limit=" + FOLDER_PAGE_SIZE
                + "&page=" + page, "folder search");
        if (body == null || !body.isArray() || body.isEmpty()) {
            return false;
        }
        for (JsonNode node : body) {
            folders.add(folderHit(node));
        }
        return body.size() >= FOLDER_PAGE_SIZE;
    }

    /**
     * One folder row, from either endpoint.
     *
     * <p>The parent is read from {@code parentUid} OR {@code folderUid} because the
     * two endpoints disagree on the name for it: {@code /api/folders} says
     * {@code parentUid}, {@code /api/search} says {@code folderUid}. Reading both
     * is what lets one mapper serve both, and keeps this correct if a Grafana
     * upgrade settles on either spelling.</p>
     */
    private static FolderHit folderHit(JsonNode node) {
        String parent = text(node, "parentUid");
        if (parent.isEmpty()) {
            parent = text(node, FIELD_FOLDER_UID);
        }
        return new FolderHit(text(node, "uid"), text(node, FIELD_TITLE), parent);
    }

    @Override
    public DashboardDefinition dashboard(String uid) {
        String safe = safeUid(uid);
        JsonNode body;
        try {
            body = getJson("/api/dashboards/uid/" + enc(safe), "dashboard " + safe);
        } catch (GrafanaException e) {
            if (e.getUpstreamStatus() == 404) {
                log.info("Grafana dashboard {} not found (404) — the UID is stale or the service "
                        + "account cannot read it", safe);
                return null;
            }
            throw e;
        }
        if (body == null || !body.hasNonNull("dashboard")) {
            return null;
        }

        JsonNode dashboard = body.path("dashboard");
        JsonNode meta = body.path("meta");
        JsonNode version = dashboard.path("version");
        String resolvedUid = text(dashboard, "uid");
        return new DashboardDefinition(
                resolvedUid.isEmpty() ? safe : resolvedUid,
                text(dashboard, FIELD_TITLE),
                text(meta, FIELD_FOLDER_UID),
                text(meta, "folderTitle"),
                version.isNumber() ? version.asInt() : null,
                dashboard,
                meta);
    }

    @Override
    public List<DatasourceHit> datasources(String type) {
        JsonNode body = getJson("/api/datasources", "datasource list");
        List<DatasourceHit> out = new ArrayList<>();
        if (body != null && body.isArray()) {
            for (JsonNode node : body) {
                String dsType = text(node, "type");
                if (type != null && !type.isBlank() && !type.equals(dsType)) {
                    continue;
                }
                out.add(new DatasourceHit(text(node, "uid"), text(node, "name"), dsType));
            }
        }
        log.debug("Grafana datasources(type={}): {} row(s)", type, out.size());
        return out;
    }

    @Override
    public JsonNode query(JsonNode payload) {
        try {
            // queryClient(), not client(): a panel query gets the long read
            // timeout. See GrafanaConfig.
            return config.queryClient().post()
                    .uri("/api/ds/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.error("Grafana POST /api/ds/query failed: status={} body={}",
                    e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            throw translate(e, "run the panel query");
        } catch (ResourceAccessException e) {
            throw accessFailure(e, "the panel query", props().getQueryTimeoutSeconds());
        }
    }

    @Override
    public List<String> labelValues(String dsUid, String label, String metric,
            Long fromMs, Long toMs) {
        String safeDs = safeUid(dsUid);
        // Prometheus label names are a narrower alphabet than dashboard UIDs, and
        // this one also lands in a path segment.
        if (label == null || !label.matches("[A-Za-z_]\\w{0,127}")) {
            throw new IllegalArgumentException("Not a valid Prometheus label name: " + label);
        }

        long endSec = toMs != null ? toMs / 1000L : System.currentTimeMillis() / 1000L;
        long startSec = fromMs != null ? fromMs / 1000L : endSec - DEFAULT_LOOKBACK_SECONDS;

        // A URI TEMPLATE with the values passed as variables, never pre-encoded
        // into the string. RestClient encodes a template string itself, so a
        // selector encoded here first went out encoded TWICE: for example, a namespace
        // matcher reached Prometheus as literal text %7B..., failing with parse
        // error: unexpected <op:%>. A bare metric name has nothing to encode,
        // which is why only chained variables (Pod filtered by $namespace) broke.
        StringBuilder template = new StringBuilder(
                "/api/datasources/proxy/uid/{ds}/api/v1/label/{label}/values?start={start}&end={end}");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ds", safeDs);
        vars.put("label", label);
        vars.put("start", startSec);
        vars.put("end", endSec);
        if (metric != null && !metric.isBlank()) {
            template.append("&match[]={match}");
            vars.put("match", metric.trim());
        }

        JsonNode body = getJson(template.toString(), vars, "label values for " + label);
        List<String> values = strings(body == null ? null : body.path("data"));
        log.debug("Grafana labelValues(ds={}, label={}): {} value(s)", safeDs, label, values.size());
        return values;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private JsonNode getJson(String path, String what) {
        return getJson(path, null, what);
    }

    /**
     * With {@code vars}, {@code path} is a URI template and each value is encoded
     * into it exactly once. Without, the path is sent exactly as before.
     */
    private JsonNode getJson(String path, Map<String, ?> vars, String what) {
        try {
            return (vars == null ? client().get().uri(path) : client().get().uri(path, vars))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Logged at info for 404 — a stale UID is routine, and dashboard()
            // turns it into a null rather than a failure.
            if (e.getStatusCode().value() == 404) {
                log.debug("Grafana GET {} -> 404", path);
            } else {
                log.error("Grafana GET {} failed: status={} body={}", path,
                        e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            }
            throw translate(e, what);
        } catch (ResourceAccessException e) {
            throw accessFailure(e, what, props().getRequestTimeoutSeconds());
        }
    }

    /**
     * Turn a Grafana HTTP failure into a message that names the fix.
     *
     * <p>The auth case is split out on purpose. It is by far the most common
     * misconfiguration, and Grafana's own body for it ({@code {"message":"Unauthorized"}})
     * tells whoever is reading a UI banner nothing they can act on.</p>
     */
    private GrafanaException translate(RestClientResponseException e, String what) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new GrafanaException("Grafana rejected the service-account token while trying "
                    + "to " + what + ". Check grafana.token (GRAFANA_SA_TOKEN) and that the "
                    + "service account can read the folder.", status, e);
        }
        if (status == 404) {
            return new GrafanaException("Grafana has nothing at that address (" + what + ").",
                    404, e);
        }
        String upstream = upstreamMessage(e);
        return new GrafanaException("Grafana returned HTTP " + status + " while trying to "
                + what + (upstream == null ? "." : ": " + upstream), status, e);
    }

    /**
     * Tell a TIMEOUT apart from an unreachable host.
     *
     * <p>Both arrive as {@link ResourceAccessException} and the difference is the
     * whole diagnosis: "Grafana is unreachable" sends someone to check DNS,
     * firewalls and certificates, when in fact the request was answered late — or
     * would have been, given a few more seconds. That misdirection cost real time
     * on this feature, because a heavy panel query hitting the metadata timeout
     * reported the host as down while every other call to it was succeeding.</p>
     *
     * <p>The test is on the message rather than the exception type because the JDK
     * HTTP client surfaces a read timeout as a plain {@code IOException("Request
     * cancelled")} or an {@code HttpTimeoutException} depending on where in the
     * exchange it fires. Getting the classification wrong only costs wording, so a
     * heuristic here is the right trade.</p>
     */
    private GrafanaException accessFailure(ResourceAccessException e, String what,
            int timeoutSeconds) {
        String detail = String.valueOf(e.getMessage()).toLowerCase();
        Throwable cause = e.getCause();
        String causeName = cause == null ? "" : cause.getClass().getSimpleName().toLowerCase();

        boolean timedOut = detail.contains("timed out") || detail.contains("timeout")
                || detail.contains("cancelled") || causeName.contains("timeout");

        if (timedOut) {
            // WARN, not ERROR: a slow dashboard panel is a capacity observation,
            // not a fault, and logging it at ERROR trains people to ignore ERROR.
            log.warn("Grafana timed out after {}s while trying to {} — the query is probably "
                    + "just slow. Raise grafana.query-timeout-seconds if this is normal for "
                    + "this dashboard.", timeoutSeconds, what);
            return new GrafanaException("Grafana did not answer within " + timeoutSeconds
                    + "s while trying to " + what + ". The query may simply be slow — narrow the "
                    + "time range, or raise grafana.query-timeout-seconds.", 504, e);
        }

        log.error("Grafana unreachable while trying to {}: {}", what, e.getMessage());
        return new GrafanaException("Cannot reach Grafana at " + props().getBaseUrl()
                + " — check grafana.base-url, network access and TLS trust. (" + e.getMessage()
                + ")", 0, e);
    }

    private String upstreamMessage(RestClientResponseException e) {
        String raw = e.getResponseBodyAsString();
        if (raw.isBlank()) {
            return null;
        }
        try {
            JsonNode body = json.readTree(raw);
            if (body.hasNonNull("message")) {
                return body.get("message").asText();
            }
            if (body.hasNonNull("error")) {
                return body.get("error").asText();
            }
        } catch (Exception ignored) {
            // Not JSON — fall through to the raw text below.
        }
        return truncate(raw);
    }

    /** UID validation. Rejects rather than sanitises: a mangled UID would 404 confusingly. */
    private static String safeUid(String uid) {
        String trimmed = uid == null ? "" : uid.trim();
        if (!UID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Not a valid Grafana UID: '" + trimmed
                    + "'. UIDs are up to 64 characters of letters, digits, '-' and '_'.");
        }
        return trimmed;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            out.add(node.asText());
        }
        return out;
    }

    /** Upstream bodies can be a whole HTML error page; a log line does not need it. */
    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        String single = raw.replaceAll("\\s+", " ").trim();
        return single.length() <= 300 ? single : single.substring(0, 300) + "…";
    }
}
