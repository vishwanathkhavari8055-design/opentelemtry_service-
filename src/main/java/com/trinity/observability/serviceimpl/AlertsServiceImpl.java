package com.trinity.observability.serviceimpl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertsRequestDTO;
import com.trinity.observability.dto.alerts.AlertsResponseDTO;
import com.trinity.observability.exception.OpenObserveQueryException;
import com.trinity.observability.service.AlertsService;
import com.trinity.observability.service.StreamCatalogService;
import com.trinity.observability.util.TimeFormatter;

/**
 * Talks to OpenObserve's alert endpoints:
 *
 * <pre>
 *   GET    /api/v2/{org}/alerts                    → {"list":[…]}
 *   GET    /api/v2/{org}/alerts/{id}               → the alert document
 *   POST   /api/v2/{org}/alerts?folder={f}         → {"code":200,"id":…,"name":…}
 *   PUT    /api/v2/{org}/alerts/{id}?folder={f}
 *   DELETE /api/v2/{org}/alerts/{id}
 *   PATCH  /api/v2/{org}/alerts/{id}/enable?value={bool}
 *   PATCH  /api/v2/{org}/alerts/{id}/trigger
 *   POST   /api/v2/{org}/alerts/{id}/clone
 *   PATCH  /api/v2/{org}/alerts/move
 *   GET    /api/v2/{org}/folders/alerts            → {"list":[{folderId,name,…}]}
 *   GET    /api/{org}/alerts/destinations          → [ … ]  (bare array)
 *   GET    /api/{org}/alerts/templates             → [ … ]  (bare array)
 * </pre>
 *
 * <p>Note the version split, which is not a mistake: alerts and their folders
 * moved to {@code /api/v2} (alerts gained opaque string IDs there, which is what
 * makes rename-safe editing possible), while destinations and templates are
 * still v1. Both are current on this OpenObserve build.</p>
 *
 * <h2>The "default" folder</h2>
 *
 * <p>OpenObserve's root alert folder is addressed by the literal id
 * {@code "default"} and is NOT returned by the folders endpoint. The UI shows it
 * as the first rail entry, so this service accepts and forwards it like any other
 * folder id rather than special-casing it at the call site.</p>
 */
@Service
public class AlertsServiceImpl implements AlertsService {

    private static final Logger log = LogManager.getLogger(AlertsServiceImpl.class);

    /**
     * Ids and folder ids land in a URL PATH, so they are validated rather than
     * escaped. OpenObserve's v2 alert ids are ksuid-style
     * ({@code 3HUZ47WcNMr2L45N8qdKHnJOerv}) and its folder ids are snowflake
     * integers or the literal "default" — both fit comfortably.
     */
    private static final Pattern ID_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** Guard on the list endpoint so a hand-made request cannot ask for 1e9 rows. */
    private static final int MAX_PAGE_SIZE = 1000;

    private static final String API_V2_PREFIX = "/api/v2/";
    private static final String API_PREFIX = "/api/";
    private static final String ALERTS_SEGMENT = "/alerts/";
    private static final String METHOD_PATCH = "PATCH";
    private static final String FIELD_ALERT_TYPE = "alert_type";
    private static final String FIELD_ENABLED = "enabled";
    private static final String FIELD_ALERT_ID = "alert_id";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_FOLDER_ID = "folder_id";

    private final OpenObserveClientService oo;
    private final OpenObserveConfig config;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Records every accepted write into {@code alert_rules}.
     *
     * <p>An {@link ObjectProvider} rather than a constructor dependency, because
     * this service is the pass-through the whole alerts API is built on and it
     * has to start whether or not the database is configured. Persistence off
     * means the bean is simply absent and every write below behaves exactly as it
     * did before the mirror existed.</p>
     */
    private final ObjectProvider<AlertRuleMirror> mirror;

    /**
     * The cached stream-name listing, shared with the Logs screen.
     *
     * <p>Here so {@link #listStreams} does not repeat the catalog's OpenObserve call
     * without its cache — see that method for what that cost on metrics.</p>
     */
    private final StreamCatalogService streamCatalog;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public AlertsServiceImpl(OpenObserveClientService oo, OpenObserveConfig config,
            ObjectProvider<AlertRuleMirror> mirror, StreamCatalogService streamCatalog) {
        this.oo = oo;
        this.config = config;
        this.mirror = mirror;
        this.streamCatalog = streamCatalog;
    }

    /**
     * Run a mirror update, if there is a mirror.
     *
     * <p>Every call site sits AFTER a successful OpenObserve write, so nothing
     * here may throw: the alert already exists and is already evaluating, and
     * reporting a failure for it would invite a retry that creates a duplicate.
     * {@link AlertRuleMirror} swallows its own errors; this only handles the bean
     * being absent.</p>
     */
    private void mirror(java.util.function.Consumer<AlertRuleMirror> action) {
        AlertRuleMirror m = mirror.getIfAvailable();
        if (m == null) return;
        try {
            action.accept(m);
        } catch (Exception e) {
            log.error("Alert rule mirroring failed: {}", e.getMessage(), e);
        }
    }

    // ── List ────────────────────────────────────────────────────────────────

    @Override
    public AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> list(AlertsRequestDTO.ListFilter filter) {
        String org = config.getOrg();
        AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> out = newPage(org);
        if (!openObserveEnabled) {
            log.info("list() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        StringBuilder qs = listQueryString(filter);

        String path = API_V2_PREFIX + org + "/alerts" + (qs.isEmpty() ? "" : "?" + qs);
        try {
            JsonNode root = oo.getJson(path);
            readListResponse(root, out);
        } catch (Exception e) {
            // Degrade to an empty table with a logged reason rather than 500ing
            // the screen — same posture as the IAM tables.
            log.error("alerts list query failed: {}", e.getMessage(), e);
        }
        log.info("list() returning {} alert(s) for org={} (folder={}, type={})",
                out.getItems().size(), org, filter.getFolder(), filter.getAlertType());
        return out;
    }

    private void readListResponse(JsonNode root, AlertsResponseDTO.Page<AlertsResponseDTO.ListItem> out) {
        JsonNode list = root.path("list");
        if (list.isArray()) {
            for (JsonNode n : list) out.getItems().add(toListItem(n));
        } else {
            log.warn("alerts list: response had no 'list' array");
        }
        if (root.path("total").isNumber()) out.setTotal(root.path("total").asInt());
    }

    /** The OpenObserve list query string for a filter. */
    private StringBuilder listQueryString(AlertsRequestDTO.ListFilter filter) {
        StringBuilder qs = new StringBuilder();
        appendParam(qs, "folder", filter.getFolder());
        // "all" is OpenObserve's own default; sending it is harmless but noisy.
        if (isSpecificAlertType(filter.getAlertType())) {
            appendParam(qs, FIELD_ALERT_TYPE, filter.getAlertType());
        }
        appendParam(qs, "alert_name_substring", filter.getSearch());
        appendParam(qs, "stream_type", filter.getStreamType());
        // OpenObserve ignores stream_name unless stream_type is also set; sending
        // it alone would silently widen the result instead of narrowing it.
        if (hasText(filter.getStreamType())) {
            appendParam(qs, "stream_name", filter.getStreamName());
        }
        appendParam(qs, "owner", filter.getOwner());
        if (filter.getEnabled() != null) {
            appendParam(qs, FIELD_ENABLED, String.valueOf(filter.getEnabled()));
        }
        appendPaging(qs, filter);
        return qs;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** A real alert-type filter, as opposed to none or OpenObserve's own "all". */
    private static boolean isSpecificAlertType(String alertType) {
        return hasText(alertType) && !"all".equalsIgnoreCase(alertType);
    }

    /** page_size, capped, and page_idx only alongside it. */
    private static void appendPaging(StringBuilder qs, AlertsRequestDTO.ListFilter filter) {
        if (filter.getPageSize() != null && filter.getPageSize() > 0) {
            appendParam(qs, "page_size", String.valueOf(Math.min(filter.getPageSize(), MAX_PAGE_SIZE)));
            if (filter.getPageIdx() != null && filter.getPageIdx() >= 0) {
                appendParam(qs, "page_idx", String.valueOf(filter.getPageIdx()));
            }
        }
    }

    /**
     * Maps one list row.
     *
     * <p>The trigger/query detail sits under {@code trigger_condition} and
     * {@code condition} in the list payload, but under {@code trigger_condition}
     * and {@code query_condition} on a single alert. Only the list shape is read
     * here.</p>
     */
    private AlertsResponseDTO.ListItem toListItem(JsonNode n) {
        AlertsResponseDTO.ListItem it = new AlertsResponseDTO.ListItem();
        it.setAlertId(text(n, FIELD_ALERT_ID));
        it.setName(text(n, "name"));
        it.setDescription(text(n, FIELD_DESCRIPTION));
        it.setOwner(text(n, "owner"));
        it.setFolderId(text(n, FIELD_FOLDER_ID));
        it.setFolderName(text(n, "folder_name"));
        it.setAlertType(text(n, FIELD_ALERT_TYPE));
        it.setEnabled(n.path(FIELD_ENABLED).asBoolean(false));
        it.setRealTime(n.path("is_real_time").asBoolean(false));
        it.setStreamType(text(n, "stream_type"));
        it.setStreamName(text(n, "stream_name"));

        JsonNode tc = n.path("trigger_condition");
        if (tc.isObject()) {
            it.setPeriod(intOrNull(tc, "period"));
            it.setFrequency(intOrNull(tc, "frequency"));
            it.setFrequencyType(text(tc, "frequency_type"));
            it.setCron(text(tc, "cron"));
            it.setSilence(intOrNull(tc, "silence"));
            it.setThreshold(intOrNull(tc, "threshold"));
            it.setOperator(text(tc, "operator"));
        }

        it.setLastTriggeredAt(isoMicros(n, "last_triggered_at"));
        it.setLastSatisfiedAt(isoMicros(n, "last_satisfied_at"));
        it.setLastTrainedAt(isoMicros(n, "last_trained_at"));
        it.setStatus(text(n, "status"));
        it.setLastError(text(n, "last_error"));
        return it;
    }

    // ── Single alert ────────────────────────────────────────────────────────

    @Override
    public AlertsResponseDTO.Detail get(String alertId) {
        String org = config.getOrg();
        AlertsResponseDTO.Detail out = new AlertsResponseDTO.Detail();
        out.setOrg(org);
        out.setAlertId(alertId);
        if (!openObserveEnabled) {
            log.info("get({}) returning supported=false on ES path", alertId);
            return out;
        }
        out.setSupported(true);

        JsonNode alert = oo.getJson(API_V2_PREFIX + org + ALERTS_SEGMENT + validId(alertId));
        out.setAlert(alert);
        // The document itself does not carry its folder, so the caller needs it
        // from elsewhere; the list row has it. Echoed here when present so a
        // caller that only has the id is not forced to list as well.
        out.setFolderId(text(alert, FIELD_FOLDER_ID));
        log.info("get({}) ok for org={}", alertId, org);
        return out;
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    @Override
    public AlertsResponseDTO.WriteResult create(JsonNode alert, String folderId) {
        requireOpenObserve("create an alert");
        ObjectNode body = prepare(alert, folderId);
        String path = API_V2_PREFIX + config.getOrg() + "/alerts";
        JsonNode res = oo.sendJson("POST", path, body);
        String newId = text(res, "id");
        if (log.isInfoEnabled()) {
            log.info("create() alert name={} folder={} -> id={}",
                    text(body, "name"), folderId, newId);
        }
        // Mirrored AFTER OpenObserve accepted it, so the row records something
        // that is genuinely live rather than something we hope will be.
        mirror(m -> m.recordSaved(config.getOrg(), newId, body, folderId, null));
        return writeResult(res, "Alert created");
    }

    @Override
    public AlertsResponseDTO.WriteResult update(String alertId, JsonNode alert, String folderId) {
        requireOpenObserve("update an alert");
        ObjectNode body = prepare(alert, folderId);
        String path = API_V2_PREFIX + config.getOrg() + ALERTS_SEGMENT + validId(alertId);
        JsonNode res = oo.sendJson("PUT", path, body);
        if (log.isInfoEnabled()) {
            log.info("update({}) name={} folder={} ok", alertId, text(body, "name"), folderId);
        }
        mirror(m -> m.recordSaved(config.getOrg(), alertId, body, folderId, null));
        return writeResult(res, "Alert updated");
    }

    @Override
    public AlertsResponseDTO.WriteResult delete(String alertId) {
        requireOpenObserve("delete an alert");
        JsonNode res = oo.deleteJson(API_V2_PREFIX + config.getOrg() + ALERTS_SEGMENT + validId(alertId));
        log.info("delete({}) ok", alertId);
        // Soft-deleted here. The remote delete has already happened, so this is a
        // record of a completed removal, not an obligation for the next sync.
        mirror(m -> m.recordDeleted(config.getOrg(), alertId, null));
        return writeResult(res, "Alert deleted");
    }

    @Override
    public AlertsResponseDTO.WriteResult setEnabled(String alertId, boolean enabled) {
        requireOpenObserve("enable or disable an alert");
        // No body: this endpoint reads `value` from the query string only.
        String path = API_V2_PREFIX + config.getOrg() + ALERTS_SEGMENT + validId(alertId)
                + "/enable?value=" + enabled;
        JsonNode res = oo.sendJson(METHOD_PATCH, path, null);
        log.info("setEnabled({}, {}) ok", alertId, enabled);
        mirror(m -> m.recordEnabled(config.getOrg(), alertId, enabled, null));
        return writeResult(res, enabled ? "Alert enabled" : "Alert disabled");
    }

    @Override
    public AlertsResponseDTO.WriteResult trigger(String alertId) {
        requireOpenObserve("trigger an alert");
        String path = API_V2_PREFIX + config.getOrg() + ALERTS_SEGMENT + validId(alertId) + "/trigger";
        JsonNode res = oo.sendJson(METHOD_PATCH, path, null);
        log.info("trigger({}) ok", alertId);
        return writeResult(res, "Alert evaluated");
    }

    @Override
    public AlertsResponseDTO.WriteResult clone(String alertId, String name, String folderId) {
        requireOpenObserve("clone an alert");
        ObjectNode body = json.createObjectNode();
        if (name != null && !name.isBlank()) body.put("name", name.trim());
        if (folderId != null && !folderId.isBlank()) body.put(FIELD_FOLDER_ID, folderId.trim());
        String path = API_V2_PREFIX + config.getOrg() + ALERTS_SEGMENT + validId(alertId) + "/clone";
        JsonNode res = oo.sendJson("POST", path, body);
        String cloneId = text(res, "id");
        log.info("clone({}) -> name={} folder={} newId={}", alertId, name, folderId, cloneId);
        // A clone is a NEW alert, so it needs its own row. Its document is read
        // back rather than reconstructed from the source: OpenObserve decides
        // what a clone actually contains — the name it settled on, the folder it
        // landed in — and guessing here would mirror something that does not
        // match what is now live.
        mirrorClone(cloneId, folderId);
        return writeResult(res, "Alert cloned");
    }

    @Override
    public AlertsResponseDTO.WriteResult move(List<String> alertIds, String targetFolderId) {
        requireOpenObserve("move alerts");
        if (alertIds == null || alertIds.isEmpty()) {
            throw new IllegalArgumentException("move: alertIds must not be empty");
        }
        ObjectNode body = json.createObjectNode();
        ArrayNode ids = body.putArray("alert_ids");
        alertIds.forEach(id -> ids.add(validId(id)));
        body.put("dst_folder_id", validId(targetFolderId));
        JsonNode res = oo.sendJson(METHOD_PATCH, API_V2_PREFIX + config.getOrg() + "/alerts/move", body);
        log.info("move({} alert(s)) -> folder={}", alertIds.size(), targetFolderId);
        mirror(m -> m.recordMoved(config.getOrg(), alertIds, targetFolderId, null));
        return writeResult(res, "Alerts moved");
    }

    @Override
    public List<AlertsResponseDTO.ImportOutcome> importAlerts(List<JsonNode> alerts, String folderId) {
        requireOpenObserve("import alerts");
        List<AlertsResponseDTO.ImportOutcome> out = new ArrayList<>();
        if (alerts == null || alerts.isEmpty()) {
            throw new IllegalArgumentException("import: no alert documents supplied");
        }

        for (int i = 0; i < alerts.size(); i++) {
            out.add(importOne(alerts.get(i), i, folderId));
        }
        long okCount = out.stream().filter(AlertsResponseDTO.ImportOutcome::isOk).count();
        log.info("importAlerts() {} of {} imported into folder={}", okCount, out.size(), folderId);
        return out;
    }

    /** Imports document {@code i} of a batch; a failure is reported on the outcome, never thrown. */
    private AlertsResponseDTO.ImportOutcome importOne(JsonNode doc, int i, String folderId) {
        AlertsResponseDTO.ImportOutcome o = new AlertsResponseDTO.ImportOutcome();
        String name = text(doc, "name");
        o.setName(name != null ? name : "(entry " + (i + 1) + ")");
        try {
            if (!doc.isObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("missing required field \"name\"");
            }
            AlertsResponseDTO.WriteResult w = create(doc, folderId);
            o.setOk(w.isOk());
            o.setMessage(w.getMessage());
            o.setAlertId(w.getAlertId());
        } catch (Exception e) {
            // One bad document must not discard the good ones — a file can
            // hold many alerts and a partial import is far more useful than
            // an all-or-nothing rejection.
            o.setOk(false);
            o.setMessage(e.getMessage());
            log.warn("import: alert '{}' rejected: {}", o.getName(), e.getMessage());
        }
        return o;
    }

    /**
     * Mirror a freshly cloned alert by reading back what OpenObserve created.
     *
     * <p>Best-effort in both halves: without an id there is nothing to read, and
     * a failed read leaves the clone live in OpenObserve and absent here, which
     * {@code POST /api/alert-rules/import} reconciles. Neither case may fail the
     * clone, which has already happened.</p>
     */
    private void mirrorClone(String cloneId, String folderId) {
        if (cloneId == null || cloneId.isBlank() || mirror.getIfAvailable() == null) return;
        try {
            AlertsResponseDTO.Detail detail = get(cloneId);
            JsonNode document = detail == null ? null : detail.getAlert();
            if (document == null || !document.isObject()) {
                log.warn("Cloned alert {} could not be read back, so it is not mirrored", cloneId);
                return;
            }
            String placement = detail.getFolderId() != null ? detail.getFolderId() : folderId;
            mirror(m -> m.recordSaved(config.getOrg(), cloneId, document, placement, null));
        } catch (Exception e) {
            log.error("Could not read back cloned alert {} to mirror it: {}",
                    cloneId, e.getMessage(), e);
        }
    }

    /**
     * Normalises an incoming alert document for OpenObserve.
     *
     * <p>Three things happen here, and nothing else — the document is otherwise
     * forwarded exactly as received so unmodelled fields survive the round trip:</p>
     *
     * <ol>
     *   <li>{@code folder_id} is set from the caller's placement choice. It goes
     *       in the BODY, not the query string: the v2 create/update endpoints
     *       read it from there.</li>
     *   <li>{@code org_id} is forced to the request's organization. Importing a
     *       file exported from another tenant would otherwise carry that tenant's
     *       id and either fail or, worse, write across organizations.</li>
     *   <li>Server-owned fields are stripped — see below.</li>
     * </ol>
     */
    private ObjectNode prepare(JsonNode alert, String folderId) {
        if (alert == null || !alert.isObject()) {
            throw new IllegalArgumentException("alert document must be a JSON object");
        }
        ObjectNode body = ((ObjectNode) alert).deepCopy();

        // Server-owned fields. `id` in the body would fight the id in the path
        // on update; the rest are runtime state and audit stamps that belong to
        // OpenObserve — echoing a stale `last_triggered_at` back at it would
        // rewrite firing history from a value the browser happened to be holding.
        //
        // `owner` is deliberately NOT stripped. It arrives from the GET of this
        // same alert, so passing it through preserves the original author when an
        // alert created in OpenObserve's UI is edited here. Dropping it would let
        // OpenObserve reassign ownership to the shared service account this
        // service authenticates as, quietly erasing who actually owns the alert.
        body.remove(List.of("id", "updated_at", "last_edited_by",
                "last_triggered_at", "last_satisfied_at"));

        body.put("org_id", config.getOrg());
        if (folderId != null && !folderId.isBlank()) {
            body.put(FIELD_FOLDER_ID, validId(folderId));
        }
        return body;
    }

    // ── Folders ─────────────────────────────────────────────────────────────

    @Override
    public AlertsResponseDTO.Page<AlertsResponseDTO.Folder> listFolders() {
        String org = config.getOrg();
        AlertsResponseDTO.Page<AlertsResponseDTO.Folder> out = newPage(org);
        if (!openObserveEnabled) return out;
        out.setSupported(true);

        try {
            JsonNode list = oo.getJson(API_V2_PREFIX + org + "/folders/alerts").path("list");
            if (list.isArray()) {
                for (JsonNode n : list) {
                    AlertsResponseDTO.Folder f = new AlertsResponseDTO.Folder();
                    f.setFolderId(text(n, "folderId"));
                    f.setName(text(n, "name"));
                    f.setDescription(text(n, FIELD_DESCRIPTION));
                    out.getItems().add(f);
                }
            }
        } catch (Exception e) {
            log.error("alert folders query failed: {}", e.getMessage(), e);
        }
        log.info("listFolders() returning {} folder(s) for org={}", out.getItems().size(), org);
        return out;
    }

    @Override
    public AlertsResponseDTO.WriteResult createFolder(String name, String description) {
        requireOpenObserve("create a folder");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("folder name must not be empty");
        }
        ObjectNode body = json.createObjectNode();
        body.put("name", name.trim());
        // Required by the endpoint even when empty.
        body.put(FIELD_DESCRIPTION, description == null ? "" : description.trim());
        JsonNode res = oo.sendJson("POST", API_V2_PREFIX + config.getOrg() + "/folders/alerts", body);
        log.info("createFolder({}) ok", name);
        return writeResult(res, "Folder created");
    }

    @Override
    public AlertsResponseDTO.WriteResult updateFolder(String folderId, String name, String description) {
        requireOpenObserve("update a folder");
        ObjectNode body = json.createObjectNode();
        body.put("name", name == null ? "" : name.trim());
        body.put(FIELD_DESCRIPTION, description == null ? "" : description.trim());
        String path = API_V2_PREFIX + config.getOrg() + "/folders/alerts/" + validId(folderId);
        JsonNode res = oo.sendJson("PUT", path, body);
        log.info("updateFolder({}) ok", folderId);
        return writeResult(res, "Folder updated");
    }

    @Override
    public AlertsResponseDTO.WriteResult deleteFolder(String folderId) {
        requireOpenObserve("delete a folder");
        String path = API_V2_PREFIX + config.getOrg() + "/folders/alerts/" + validId(folderId);
        JsonNode res = oo.deleteJson(path);
        log.info("deleteFolder({}) ok", folderId);
        return writeResult(res, "Folder deleted");
    }

    // ── Notification targets ────────────────────────────────────────────────

    @Override
    public AlertsResponseDTO.Page<AlertsResponseDTO.Destination> listDestinations() {
        String org = config.getOrg();
        AlertsResponseDTO.Page<AlertsResponseDTO.Destination> out = newPage(org);
        if (!openObserveEnabled) return out;
        out.setSupported(true);

        try {
            // v1, and a BARE ARRAY rather than a {"list":…} envelope.
            for (JsonNode n : arrayOf(oo.getJson(API_PREFIX + org + "/alerts/destinations"))) {
                AlertsResponseDTO.Destination d = new AlertsResponseDTO.Destination();
                d.setName(text(n, "name"));
                d.setType(text(n, "type"));
                d.setUrl(text(n, "url"));
                d.setMethod(text(n, "method"));
                d.setTemplate(text(n, "template"));
                if (n.path("emails").isArray()) {
                    List<String> emails = new ArrayList<>();
                    n.path("emails").forEach(e -> {
                        String v = e.asText(null);
                        if (v != null && !v.isBlank()) emails.add(v);
                    });
                    d.setEmails(emails);
                }
                out.getItems().add(d);
            }
        } catch (Exception e) {
            log.error("alert destinations query failed: {}", e.getMessage(), e);
        }
        log.info("listDestinations() returning {} destination(s)", out.getItems().size());
        return out;
    }

    @Override
    public AlertsResponseDTO.WriteResult createDestination(JsonNode destination) {
        requireOpenObserve("create a destination");
        if (destination == null || !destination.isObject()) {
            throw new IllegalArgumentException("destination must be a JSON object");
        }
        if (text(destination, "name") == null) {
            throw new IllegalArgumentException("destination name must not be empty");
        }
        // A destination without a template is created as a PIPELINE destination
        // and then silently will not appear in the alert editor's list — a
        // confusing failure well worth catching here.
        if (text(destination, "template") == null) {
            throw new IllegalArgumentException(
                    "destination requires a template — without one OpenObserve creates a "
                    + "pipeline destination, which alerts cannot use");
        }
        JsonNode res = oo.sendJson("POST",
                API_PREFIX + config.getOrg() + "/alerts/destinations", destination);
        if (log.isInfoEnabled()) {
            log.info("createDestination({}) ok", text(destination, "name"));
        }
        return writeResult(res, "Destination created");
    }

    @Override
    public AlertsResponseDTO.Page<AlertsResponseDTO.Template> listTemplates() {
        String org = config.getOrg();
        AlertsResponseDTO.Page<AlertsResponseDTO.Template> out = newPage(org);
        if (!openObserveEnabled) return out;
        out.setSupported(true);

        try {
            for (JsonNode n : arrayOf(oo.getJson(API_PREFIX + org + "/alerts/templates"))) {
                AlertsResponseDTO.Template t = new AlertsResponseDTO.Template();
                t.setName(text(n, "name"));
                t.setType(text(n, "type"));
                t.setTitle(text(n, "title"));
                t.setIsDefault(n.path("isDefault").isBoolean()
                        ? n.path("isDefault").asBoolean() : null);
                out.getItems().add(t);
            }
        } catch (Exception e) {
            log.error("alert templates query failed: {}", e.getMessage(), e);
        }
        log.info("listTemplates() returning {} template(s)", out.getItems().size());
        return out;
    }

    // ── Stream metadata ─────────────────────────────────────────────────────

    /**
     * Stream names of one type, read through the shared stream catalog.
     *
     * <h2>Why this delegates rather than asking OpenObserve itself</h2>
     *
     * <p>It used to issue its own {@code /streams?type=} call — the same call
     * {@link StreamCatalogService} was already making for the Logs screen, but with
     * no cache behind it. For logs and traces that was merely wasteful. For metrics
     * it was the reason the alert editor felt broken: OpenObserve answers that one
     * with <b>19.2 MB</b> covering 19,567 metric streams, all of it parsed so that a
     * single {@code name} can be read off each entry, and it was re-fetched on every
     * request. Measured on the live instance, {@code GET /api/alerts/streams?type=
     * metrics} took 2.7–7.1 s <i>every time</i> — and the editor calls it whenever
     * the stream dropdown opens.</p>
     *
     * <p>The catalog serves that list from memory and refreshes it behind the
     * request, so the same call now answers in milliseconds. Nothing else changes:
     * the names, their order and the empty-on-failure behaviour are identical, since
     * the catalog sorts case-insensitively and swallows failures the same way.</p>
     *
     * <p>The strict type check stays HERE rather than moving to the catalog. This is
     * an API-facing entry point where an unknown {@code type=} is a caller's mistake
     * worth reporting; the catalog quietly falls back to logs because it also backs
     * internal callers that must not throw.</p>
     */
    @Override
    public List<String> listStreams(String streamType) {
        if (!openObserveEnabled) return new ArrayList<>();
        String type = normaliseStreamType(streamType);
        List<String> names = streamCatalog.list(type);
        log.debug("listStreams({}) -> {} stream(s)", type, names.size());
        // Copied because the catalog hands back the cached list itself, and a caller
        // that sorted or filtered it in place would corrupt every later reader.
        return new ArrayList<>(names);
    }

    @Override
    public List<String> listStreamFields(String streamType, String streamName) {
        // LinkedHashSet: the schema and the settings lists overlap, and the
        // condition-column dropdown must not show a field twice.
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (AlertsResponseDTO.StreamField f : listStreamSchema(streamType, streamName)) {
            fields.add(f.getName());
        }
        log.debug("listStreamFields({}, {}) -> {} field(s)", streamType, streamName, fields.size());
        return new ArrayList<>(fields);
    }

    @Override
    public List<AlertsResponseDTO.StreamField> listStreamSchema(String streamType, String streamName) {
        // Keyed by name for the same reason the name list is de-duplicated: the
        // schema and the settings lists overlap, and a column offered twice in a
        // dropdown looks like two different columns.
        LinkedHashMap<String, AlertsResponseDTO.StreamField> fields = new LinkedHashMap<>();
        if (!openObserveEnabled) return new ArrayList<>(fields.values());
        if (streamName == null || streamName.isBlank()) return new ArrayList<>(fields.values());

        String type = normaliseStreamType(streamType);
        try {
            String path = API_PREFIX + config.getOrg() + "/streams/"
                    + URLEncoder.encode(streamName, StandardCharsets.UTF_8)
                    + "/schema?type=" + type;
            JsonNode schema = oo.getJson(path).path("schema");
            if (schema.isArray()) {
                for (JsonNode f : schema) {
                    String name = text(f, "name");
                    if (name == null) continue;
                    AlertsResponseDTO.StreamField field = new AlertsResponseDTO.StreamField();
                    field.setName(name);
                    field.setType(text(f, "type"));
                    field.setNumeric(isNumericArrowType(field.getType()));
                    fields.putIfAbsent(name, field);
                }
            }
        } catch (Exception e) {
            // A stream with no data yet has no schema. That is normal, not an
            // error — the editor falls back to a free-text column input.
            log.warn("stream schema ({}/{}) unavailable: {}", type, streamName, e.getMessage());
        }
        log.debug("listStreamSchema({}, {}) -> {} field(s)", type, streamName, fields.size());
        return new ArrayList<>(fields.values());
    }

    /**
     * Is this Arrow type something an aggregation can be computed over?
     *
     * <p>Matched on a PREFIX because Arrow spells the widths out — {@code Int8},
     * {@code UInt64}, {@code Float32}, {@code Decimal128(38,10)} — and listing
     * every one of them would silently drop the next width OpenObserve starts
     * reporting. Timestamps are excluded on purpose: they are numbers underneath,
     * but "the average of _timestamp" is not a threshold anyone means to set.</p>
     */
    private static boolean isNumericArrowType(String arrowType) {
        if (arrowType == null) return false;
        String t = arrowType.trim();
        return t.startsWith("Int") || t.startsWith("UInt")
                || t.startsWith("Float") || t.startsWith("Decimal");
    }

    // ── Composite alerts ────────────────────────────────────────

    @Override
    public AlertsResponseDTO.CompositeValidation validateComposite(JsonNode compositeCondition,
                                                                    String compositeId) {
        AlertsResponseDTO.CompositeValidation out = new AlertsResponseDTO.CompositeValidation();
        out.setSupported(openObserveEnabled);
        if (!openObserveEnabled) {
            out.setMessage("OpenObserve is not configured, so a composite expression "
                    + "cannot be checked.");
            return out;
        }
        if (compositeCondition == null || !compositeCondition.isObject()) {
            throw new IllegalArgumentException("compositeCondition must be a JSON object");
        }

        ObjectNode body = json.createObjectNode();
        body.set("composite_condition", compositeCondition);
        // Present only when editing: it tells the cycle check to ignore the
        // composite being edited, which otherwise counts as referencing itself.
        if (compositeId != null && !compositeId.isBlank()) {
            body.put("composite_id", compositeId.trim());
        }

        String path = API_V2_PREFIX + config.getOrg() + "/alerts/composites/validate";
        try {
            JsonNode res = oo.sendJson("POST", path, body);
            readCompositeValidation(res, out);

            log.debug("validateComposite() -> valid={} children={}",
                    out.isValid(), out.getChildren().size());
            return out;

        } catch (OpenObserveQueryException e) {
            /* A rejected expression comes back as 4xx with {code, message}, and
             * that IS the answer the editor asked for. Rethrowing would turn a
             * user typing an unfinished expression into a 500 in the browser
             * console and an editor that says only "something went wrong". */
            out.setValid(false);
            out.setCode(String.valueOf(e.getCode()));
            out.setMessage(cleanOpenObserveMessage(e.getMessage()));
            log.debug("validateComposite() rejected: {}", out.getMessage());
            return out;
        }
    }

    /** Copies OpenObserve's validation answer onto {@code out}. */
    private void readCompositeValidation(JsonNode res, AlertsResponseDTO.CompositeValidation out) {
        out.setValid(res.path("valid").asBoolean(false));
        out.setCanonicalExpression(text(res, "canonical_expression"));
        out.setResultLevel(text(res, "result_level"));
        if (res.path("result").isBoolean()) out.setResult(res.path("result").asBoolean());

        for (JsonNode c : arrayOf(res.path("children"))) {
            out.getChildren().add(toCompositeChild(c));
        }

        for (JsonNode w : arrayOf(res.path("warnings"))) {
            addCompositeWarning(out, w);
        }
    }

    private AlertsResponseDTO.CompositeChild toCompositeChild(JsonNode c) {
        AlertsResponseDTO.CompositeChild child = new AlertsResponseDTO.CompositeChild();
        child.setAlertId(text(c, FIELD_ALERT_ID));
        child.setName(text(c, "name"));
        child.setAlertType(text(c, FIELD_ALERT_TYPE));
        child.setFolderId(text(c, FIELD_FOLDER_ID));
        if (c.path("accessible").isBoolean()) child.setAccessible(c.path("accessible").asBoolean());
        if (c.path(FIELD_ENABLED).isBoolean()) child.setEnabled(c.path(FIELD_ENABLED).asBoolean());
        if (c.path("truth").isBoolean())      child.setTruth(c.path("truth").asBoolean());
        if (c.path("stale").isBoolean())      child.setStale(c.path("stale").asBoolean());
        child.setLevel(text(c, "level"));
        return child;
    }

    private void addCompositeWarning(AlertsResponseDTO.CompositeValidation out, JsonNode w) {
        String code = text(w, "code");
        String alertId = text(w, FIELD_ALERT_ID);
        if (code == null) return;
        out.getWarnings().add(alertId == null ? code : code + ": " + alertId);
    }

    /**
     * Strip the wrapper {@link OpenObserveQueryException} puts around a message.
     *
     * <p>It prefixes every message with "OpenObserve query failed (code=…,
     * oo_trace_id=…): ", which is right for a log line and wrong for a label
     * under a text box that the user is expected to act on.</p>
     */
    private static String cleanOpenObserveMessage(String raw) {
        if (raw == null) return "The expression was rejected.";
        int at = raw.lastIndexOf("): ");
        return at >= 0 ? raw.substring(at + 3).trim() : raw.trim();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Rejects an unknown stream type instead of forwarding it.
     *
     * <p>The value goes into a query string, and OpenObserve answers an unknown
     * type with a 500 whose message does not say which parameter was wrong.</p>
     */
    private static String normaliseStreamType(String streamType) {
        String t = (streamType == null || streamType.isBlank()) ? "logs" : streamType.trim().toLowerCase();
        return switch (t) {
            case "logs", "metrics", "traces" -> t;
            default -> throw new IllegalArgumentException(
                    "Unsupported stream type: '" + streamType + "' (expected logs, metrics or traces)");
        };
    }

    private String validId(String id) {
        if (id == null || !ID_RE.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Invalid id: '" + id + "' (must match " + ID_RE.pattern() + ")");
        }
        return id;
    }

    private void requireOpenObserve(String what) {
        if (!openObserveEnabled) {
            throw new IllegalStateException(
                    "Cannot " + what + ": alerts require the OpenObserve backend.");
        }
    }

    private static void appendParam(StringBuilder qs, String key, String value) {
        if (value == null || value.isBlank()) return;
        if (!qs.isEmpty()) qs.append('&');
        qs.append(key).append('=').append(URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
    }

    /**
     * Builds a WriteResult from OpenObserve's reply.
     *
     * <p>Its own message is preferred over {@code fallback} so a rejection reason
     * reaches the user verbatim. A non-2xx would already have thrown in the
     * client, so reaching here means success.</p>
     */
    private AlertsResponseDTO.WriteResult writeResult(JsonNode res, String fallback) {
        AlertsResponseDTO.WriteResult w = new AlertsResponseDTO.WriteResult();
        w.setOk(true);
        String message = res == null ? null : text(res, "message");
        w.setMessage(message != null ? message : fallback);
        if (res != null) {
            w.setAlertId(text(res, "id"));
            w.setName(text(res, "name"));
        }
        return w;
    }

    private <T> AlertsResponseDTO.Page<T> newPage(String org) {
        AlertsResponseDTO.Page<T> p = new AlertsResponseDTO.Page<>();
        p.setItems(new ArrayList<>());
        p.setOrg(org);
        p.setBackend(openObserveEnabled ? "openobserve" : "elasticsearch");
        return p;
    }

    /** Destinations and templates answer with a bare array, not an envelope. */
    private static List<JsonNode> arrayOf(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root.isArray()) root.forEach(out::add);
        else if (root.path("list").isArray()) root.path("list").forEach(out::add);
        return out;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        String v = n.path(field).asText(null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n.path(field).isNumber() ? n.path(field).asInt() : null;
    }

    /**
     * OpenObserve stamps these in epoch MICROSECONDS, same unit as _timestamp.
     *
     * <p>Zero means "never" here, not the Unix epoch — an alert that has not
     * fired reports {@code last_triggered_at: 0}. Converting that literally would
     * put "1 Jan 1970" in the Last Triggered column of every new alert, which
     * reads as a real event rather than the absence of one.</p>
     */
    private static String isoMicros(JsonNode n, String field) {
        if (!n.path(field).isNumber()) return null;
        long micros = n.path(field).asLong();
        return micros <= 0 ? null : TimeFormatter.microsToIso(micros);
    }
}
