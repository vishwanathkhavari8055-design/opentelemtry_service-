package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.GrafanaClientService;
import com.trinity.observability.config.GrafanaProperties;
import com.trinity.observability.dto.dashboards.DashboardCatalogRequestDTO;
import com.trinity.observability.dto.dashboards.DashboardCatalogResponseDTO;
import com.trinity.observability.exception.GrafanaException;
import com.trinity.observability.exception.ResourceConflictException;
import com.trinity.observability.exception.ResourceNotFoundException;
import com.trinity.observability.registry.DashboardRegistration;
import com.trinity.observability.registry.DashboardRegistryStore;
import com.trinity.observability.service.DashboardCatalogService;
import com.trinity.observability.util.DashboardDatasourceNormalizer;
import com.trinity.observability.util.DashboardQueryCache;
import com.trinity.observability.util.OrgContext;

/**
 * The Dashboard Catalog.
 *
 * <p>Three rules run through everything here.</p>
 *
 * <p><b>Grafana is validated before anything is stored.</b> Registering resolves
 * the UID against Grafana and captures its real title and folder. A UID that does
 * not resolve is refused with the reason, because the alternative is a tile that
 * looks fine and opens onto an error — and the person who typed the UID is long
 * gone by then.</p>
 *
 * <p><b>Nothing about a dashboard is cached except its label.</b> Title and Grafana
 * folder are stored so the tiles and the registry table are one database read
 * instead of forty Grafana calls. The definition never is: opening a dashboard
 * always re-reads it, which is what keeps this application and Grafana from ever
 * disagreeing about what a panel shows.</p>
 *
 * <p><b>An unconfigured deployment answers, it does not fail.</b> Every read returns
 * an empty list with {@code supported=false} when Grafana is not configured, so the
 * UI can explain itself. Only the WRITES refuse outright — registering into a
 * Grafana that does not exist cannot be made to mean anything.</p>
 */
@Service
public class DashboardCatalogServiceImpl implements DashboardCatalogService {

    private static final Logger log = LogManager.getLogger(DashboardCatalogServiceImpl.class);

    /** Folder title used for registrations with no folder, matching the UI's label. */
    private static final String UNGROUPED = "Ungrouped";

    /** How a folder path reads: "Monitoring / OpenObserver / Default Org.". */
    private static final String PATH_SEPARATOR = " / ";

    /**
     * How far up a folder's parentage is walked. Grafana's own nesting limit is
     * well under this; the ceiling is here so a corrupted parent chain cannot turn
     * a folder listing into an unbounded walk.
     */
    private static final int MAX_FOLDER_DEPTH = 16;

    private final GrafanaClientService grafana;
    private final DashboardRegistryStore store;
    private final GrafanaProperties properties;

    /**
     * Panel query results, reused across panels and opens — see
     * {@link DashboardQueryCache}. The one exception to "nothing about a dashboard
     * is cached": the definition is still read fresh on every open, but the answer
     * to a query two panels both ask, or that was answered seconds ago, is not
     * fetched twice.
     */
    private final DashboardQueryCache queryCache;

    public DashboardCatalogServiceImpl(GrafanaClientService grafana,
            DashboardRegistryStore store, GrafanaProperties properties) {
        this.grafana = grafana;
        this.store = store;
        this.properties = properties;
        GrafanaProperties.QueryCache settings = properties.getQueryCache();
        this.queryCache = new DashboardQueryCache(grafana::query, settings.isEnabled(),
                settings.getFreshSeconds(), settings.getMaxStaleSeconds(), settings.getMaxEntries(),
                System::currentTimeMillis);
    }

    @jakarta.annotation.PreDestroy
    void shutdownQueryCache() {
        queryCache.shutdown();
    }

    // ── scope ───────────────────────────────────────────────────────────────

    /**
     * Who the caller's registrations belong to.
     *
     * <p>Resolved per request rather than injected, because under ORG scope it is a
     * property of the request: {@code OrgContextFilter} has already put the active
     * organization on the thread. Under GLOBAL scope — the default — every caller
     * shares one list, which is what a Grafana instance that knows nothing about
     * OpenObserve organizations actually warrants.</p>
     */
    private String scopeKey() {
        if (properties.getRegistry().getScope() == GrafanaProperties.Registry.Scope.ORG) {
            String org = OrgContext.get();
            if (org != null && !org.isBlank()) {
                return org;
            }
            // No org on the request under ORG scope: fall back to the shared list
            // rather than inventing a scope, so a call without ?org= sees something
            // consistent instead of an empty catalog that looks like data loss.
            log.debug("Dashboard registry scope is ORG but the request carried no org — "
                    + "falling back to the shared scope");
        }
        return DashboardRegistration.GLOBAL_SCOPE;
    }

    // ── health ──────────────────────────────────────────────────────────────

    @Override
    public DashboardCatalogResponseDTO.Health health() {
        DashboardCatalogResponseDTO.Health health = new DashboardCatalogResponseDTO.Health();
        health.setSupported(properties.isUsable());
        health.setGrafanaUrl(properties.getBaseUrl());
        health.setOrgId(properties.getOrgId());
        health.setRegistryScope(properties.getRegistry().getScope().name());
        health.setRegistryStore(store.describe());
        health.setRegistered(store.list(scopeKey()).size());
        health.setMessage(unsupportedReason());
        return health;
    }

    /** The sentence the UI shows when the feature cannot work. Null when it can. */
    private String unsupportedReason() {
        if (!properties.isEnabled()) {
            return "Grafana dashboards are switched off on this deployment "
                    + "(grafana.enabled=false).";
        }
        if (properties.getBaseUrl().isEmpty()) {
            return "No Grafana is configured on this deployment. Set grafana.base-url "
                    + "(GRAFANA_BASE_URL).";
        }
        if (properties.getToken().isEmpty()) {
            return "No Grafana service-account token is configured. Set grafana.token "
                    + "(GRAFANA_SA_TOKEN) to a Viewer-role token.";
        }
        return null;
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Override
    public DashboardCatalogResponseDTO.RegistrationList list(boolean enabledOnly,
            String folderUid) {
        DashboardCatalogResponseDTO.RegistrationList out =
                new DashboardCatalogResponseDTO.RegistrationList();
        out.setSupported(properties.isUsable());

        // The registry is readable even when Grafana is not: what is registered is
        // this application's own state, and hiding it would make an unreachable
        // Grafana look like an empty catalog.
        List<DashboardRegistration> rows = sorted(store.list(scopeKey())).stream()
                .filter(reg -> !enabledOnly || reg.enabled())
                .filter(reg -> folderUid == null || folderUid.isBlank()
                        || folderUid.equals(reg.folderUid()))
                .toList();

        out.setItems(rows.stream().map(DashboardCatalogServiceImpl::toView).toList());
        out.setTotal(out.getItems().size());
        return out;
    }

    @Override
    public DashboardCatalogResponseDTO.FolderTileList folderTiles() {
        DashboardCatalogResponseDTO.FolderTileList out =
                new DashboardCatalogResponseDTO.FolderTileList();
        out.setSupported(properties.isUsable());

        Map<String, DashboardCatalogResponseDTO.FolderTile> tiles = new LinkedHashMap<>();
        for (DashboardRegistration reg : store.list(scopeKey())) {
            String key = reg.folderUid() == null ? "" : reg.folderUid();
            DashboardCatalogResponseDTO.FolderTile tile = tiles.computeIfAbsent(key, k -> {
                DashboardCatalogResponseDTO.FolderTile fresh =
                        new DashboardCatalogResponseDTO.FolderTile();
                fresh.setFolderUid(k);
                fresh.setFolderTitle(reg.folderTitle() == null || reg.folderTitle().isBlank()
                        ? UNGROUPED : reg.folderTitle());
                return fresh;
            });
            tile.setTotal(tile.getTotal() + 1);
            if (reg.enabled()) {
                tile.setEnabled(tile.getEnabled() + 1);
            }
        }

        List<DashboardCatalogResponseDTO.FolderTile> items = new ArrayList<>(tiles.values());
        items.sort(Comparator.comparing(
                DashboardCatalogResponseDTO.FolderTile::getFolderTitle,
                String.CASE_INSENSITIVE_ORDER));
        out.setItems(items);
        return out;
    }

    /**
     * The Grafana folders an administrator may file a dashboard under — the WHOLE
     * tree, not just its top level.
     *
     * <p>Every folder carries its full path ("Monitoring / OpenObserver / Default
     * Org.") rather than only its own title, because a nested instance has titles
     * that mean nothing alone: two different parents can each hold a "Default Org."
     * and the picker would offer two identical-looking options. The path is
     * computed here, from the parentage the client returns, so the browser never
     * has to reassemble a tree it did not fetch.</p>
     *
     * <p>Sorted BY PATH, which puts each child directly after its parent — a flat
     * title sort would scatter a folder's children across the whole dropdown.</p>
     */
    @Override
    public DashboardCatalogResponseDTO.GrafanaFolderList folders() {
        DashboardCatalogResponseDTO.GrafanaFolderList out =
                new DashboardCatalogResponseDTO.GrafanaFolderList();
        List<String> allowed = properties.getAllowedFolderUids();
        out.setRestricted(!allowed.isEmpty());

        if (!properties.isUsable()) {
            out.setSupported(false);
            out.setMessage(unsupportedReason());
            return out;
        }

        try {
            Map<String, GrafanaClientService.FolderHit> index = folderIndex();
            List<DashboardCatalogResponseDTO.GrafanaFolder> items = index.values().stream()
                    .filter(folder -> isFolderAllowed(index, folder.uid()))
                    .map(folder -> {
                        List<GrafanaClientService.FolderHit> line = lineage(index, folder.uid());
                        DashboardCatalogResponseDTO.GrafanaFolder view =
                                new DashboardCatalogResponseDTO.GrafanaFolder();
                        view.setUid(folder.uid());
                        view.setTitle(titleOf(folder));
                        view.setParentUid(folder.parentUid());
                        view.setPath(line.stream()
                                .map(DashboardCatalogServiceImpl::titleOf)
                                .collect(Collectors.joining(PATH_SEPARATOR)));
                        view.setDepth(Math.max(0, line.size() - 1));
                        return view;
                    })
                    .sorted(Comparator.comparing(
                            DashboardCatalogResponseDTO.GrafanaFolder::getPath,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            out.setItems(items);
        } catch (GrafanaException e) {
            // A failed folder listing costs the picker, not the screen: an admin can
            // still paste a UID, and the message says why the dropdown is empty.
            log.warn("Could not list Grafana folders: {}", e.getMessage());
            out.setSupported(false);
            out.setMessage(e.getMessage());
        }
        return out;
    }

    @Override
    public DashboardCatalogResponseDTO.CandidateList available(
            DashboardCatalogRequestDTO.Available filter) {
        DashboardCatalogResponseDTO.CandidateList out =
                new DashboardCatalogResponseDTO.CandidateList();

        if (!properties.isUsable()) {
            out.setSupported(false);
            out.setMessage(unsupportedReason());
            return out;
        }

        List<String> allowed = properties.getAllowedFolderUids();
        String folderUid = filter == null ? null : filter.getFolderUid();
        // With exactly one approved folder, "no folder chosen" can only mean that
        // one — so the picker is useful before the admin has chosen anything.
        if ((folderUid == null || folderUid.isBlank()) && allowed.size() == 1) {
            folderUid = allowed.get(0);
        }

        Set<String> registered = store.list(scopeKey()).stream()
                .map(DashboardRegistration::uid)
                .collect(Collectors.toSet());

        try {
            // Only fetched when there is an allow-list to apply — an unrestricted
            // deployment should not pay a folder listing to filter nothing out.
            Map<String, GrafanaClientService.FolderHit> index =
                    allowed.isEmpty() ? Map.of() : folderIndex();
            List<DashboardCatalogResponseDTO.Candidate> items = grafana
                    .searchDashboards(folderUid, filter == null ? null : filter.getQuery())
                    .stream()
                    .filter(hit -> !registered.contains(hit.uid()))
                    .filter(hit -> isFolderAllowed(index, hit.folderUid()))
                    .map(hit -> {
                        DashboardCatalogResponseDTO.Candidate view =
                                new DashboardCatalogResponseDTO.Candidate();
                        view.setUid(hit.uid());
                        view.setTitle(hit.title());
                        view.setFolderUid(hit.folderUid());
                        view.setFolderTitle(hit.folderTitle());
                        view.setTags(hit.tags());
                        return view;
                    })
                    .toList();
            out.setItems(items);
            out.setTotal(items.size());
        } catch (GrafanaException e) {
            log.warn("Could not search Grafana dashboards: {}", e.getMessage());
            out.setSupported(false);
            out.setMessage(e.getMessage());
        }
        return out;
    }

    // ── writes ──────────────────────────────────────────────────────────────

    @Override
    public DashboardCatalogResponseDTO.Registration register(
            DashboardCatalogRequestDTO.Register body) {
        requireUsable();

        String scope = scopeKey();
        String uid = body.getUid().trim();
        String folderUid = body.getFolderUid().trim();

        Optional<DashboardRegistration> existing = store.find(scope, uid);
        if (existing.isPresent()) {
            throw new ResourceConflictException("Dashboard '" + uid + "' is already registered"
                    + " under '" + folderTitleOf(existing.get()) + "'.");
        }

        // Resolve the folder against Grafana FIRST, so a mistyped folder is refused
        // before the dashboard lookup — the two errors read very differently and
        // conflating them sends the admin looking at the wrong field.
        String folderTitle = resolveFolderTitle(folderUid);
        GrafanaClientService.DashboardDefinition definition = fetchAuthorized(uid);

        Instant now = Instant.now();
        DashboardRegistration registration = new DashboardRegistration(
                scope,
                definition.uid(),
                definition.title(),
                folderUid,
                folderTitle,
                definition.folderUid(),
                definition.folderTitle(),
                body.getEnabled() == null || body.getEnabled(),
                body.getDisplayOrder() != null ? body.getDisplayOrder() : nextOrderIn(scope, folderUid),
                now,
                now);

        DashboardRegistration saved = store.save(registration);
        log.info("Registered Grafana dashboard uid={} title='{}' into folder '{}' "
                + "(lives in Grafana folder '{}', scope={})",
                saved.uid(), saved.title(), saved.folderTitle(), saved.grafanaFolderTitle(), scope);
        return toView(saved);
    }

    @Override
    public DashboardCatalogResponseDTO.Registration update(String uid,
            DashboardCatalogRequestDTO.Update body) {
        String scope = scopeKey();
        DashboardRegistration registration = require(scope, uid);
        Instant now = Instant.now();

        if (body.getFolderUid() != null && !body.getFolderUid().isBlank()
                && !body.getFolderUid().trim().equals(registration.folderUid())) {
            String target = body.getFolderUid().trim();
            registration = registration.withFolder(target, resolveFolderTitle(target), now);
            // A dashboard moved into a folder it has never been in has no meaningful
            // position there, so it goes to the end unless the caller said otherwise.
            if (body.getDisplayOrder() == null) {
                registration = registration.withDisplayOrder(nextOrderIn(scope, target), now);
            }
        }
        if (body.getEnabled() != null) {
            registration = registration.withEnabled(body.getEnabled(), now);
        }
        if (body.getDisplayOrder() != null) {
            registration = registration.withDisplayOrder(body.getDisplayOrder(), now);
        }

        DashboardRegistration saved = store.save(registration);
        log.info("Updated dashboard registration uid={} (folder='{}', enabled={}, order={})",
                saved.uid(), saved.folderTitle(), saved.enabled(), saved.displayOrder());
        return toView(saved);
    }

    @Override
    public DashboardCatalogResponseDTO.Registration refresh(String uid) {
        requireUsable();
        String scope = scopeKey();
        DashboardRegistration registration = require(scope, uid);

        GrafanaClientService.DashboardDefinition definition = fetchAuthorized(registration.uid());
        DashboardRegistration saved = store.save(registration.withGrafanaMetadata(
                definition.title(), definition.folderUid(), definition.folderTitle(),
                Instant.now()));

        log.info("Re-read dashboard uid={} from Grafana: title='{}', Grafana folder='{}'",
                saved.uid(), saved.title(), saved.grafanaFolderTitle());
        return toView(saved);
    }

    @Override
    public DashboardCatalogResponseDTO.WriteResult unregister(String uid) {
        String scope = scopeKey();
        DashboardRegistration registration = require(scope, uid);
        store.delete(scope, registration.uid());
        log.info("Unregistered dashboard uid={} ('{}') — untouched in Grafana",
                registration.uid(), registration.title());
        return DashboardCatalogResponseDTO.WriteResult.of(registration.uid(),
                "Unregistered \"" + registration.title() + "\". The dashboard itself is "
                + "untouched in Grafana.");
    }

    // ── rendering ───────────────────────────────────────────────────────────

    @Override
    public DashboardCatalogResponseDTO.Definition definition(String uid) {
        requireUsable();
        String scope = scopeKey();

        DashboardRegistration registration = store.find(scope, uid).orElseThrow(
                () -> new ResourceNotFoundException("Dashboard '" + uid + "' is not registered in "
                        + "this application. Register it in Settings -> Dashboard Catalog first."));
        if (!registration.enabled()) {
            // 409 rather than 403: nothing about the caller's identity is wrong, the
            // catalog's own state is. And it is one toggle away from being right.
            throw new ResourceConflictException("Dashboard \"" + registration.title()
                    + "\" is currently disabled in the Dashboard Catalog.");
        }

        GrafanaClientService.DashboardDefinition live = grafana.dashboard(registration.uid());
        if (live == null) {
            throw new ResourceNotFoundException("Dashboard '" + registration.uid() + "' is "
                    + "registered here but no longer exists in Grafana (or the service account "
                    + "cannot read it). Unregister it, or restore it in Grafana.");
        }

        // Repair datasource references the model points at but never declares.
        // A no-op for a well-formed dashboard; for an imported one whose
        // ${DS_X} placeholders were only half-substituted it is the difference
        // between a dashboard that draws and one that draws half its panels.
        // See DashboardDatasourceNormalizer.
        DashboardDatasourceNormalizer.Result repaired =
                DashboardDatasourceNormalizer.normalize(live.dashboard());
        if (repaired.changed()) {
            log.info("Dashboard {} (\"{}\") names {} datasource(s) no template variable declares; "
                    + "resolved them within their own panels so the panels can render: {}",
                    live.uid(), live.title(), repaired.repairs().size(), repaired.repairs());
        }

        DashboardCatalogResponseDTO.Definition out = new DashboardCatalogResponseDTO.Definition();
        out.setUid(live.uid());
        out.setTitle(live.title());
        out.setVersion(live.version());
        out.setFolderUid(live.folderUid());
        out.setFolderTitle(live.folderTitle());
        out.setModel(repaired.model());
        return out;
    }

    @Override
    public DashboardCatalogResponseDTO.Query query(JsonNode payload) {
        requireUsable();
        if (payload == null || !payload.hasNonNull("queries") || !payload.get("queries").isArray()) {
            throw new IllegalArgumentException(
                    "A query payload must carry a \"queries\" array, as Grafana's own "
                    + "/api/ds/query expects.");
        }
        DashboardCatalogResponseDTO.Query out = new DashboardCatalogResponseDTO.Query();
        JsonNode response = queryCache.query(payload);
        out.setResults(response == null ? null : response.path("results"));
        return out;
    }

    @Override
    public DashboardCatalogResponseDTO.DatasourceList datasources(String type) {
        DashboardCatalogResponseDTO.DatasourceList out =
                new DashboardCatalogResponseDTO.DatasourceList();
        if (!properties.isUsable()) {
            out.setSupported(false);
            return out;
        }
        out.setItems(grafana.datasources(type).stream().map(hit -> {
            DashboardCatalogResponseDTO.Datasource view =
                    new DashboardCatalogResponseDTO.Datasource();
            view.setUid(hit.uid());
            view.setName(hit.name());
            view.setType(hit.type());
            return view;
        }).toList());
        return out;
    }

    @Override
    public DashboardCatalogResponseDTO.LabelValues labelValues(String datasourceUid, String label,
            String metric, Long fromMs, Long toMs) {
        requireUsable();
        DashboardCatalogResponseDTO.LabelValues out =
                new DashboardCatalogResponseDTO.LabelValues();
        out.setValues(grafana.labelValues(datasourceUid, label, metric, fromMs, toMs));
        return out;
    }

    // ── helpers: folder tree ────────────────────────────────────────────────

    /**
     * Grafana's folders keyed by UID, in the order Grafana returned them.
     *
     * <p>Read fresh per request and deliberately not cached: "a folder I just made
     * in Grafana shows up here" is the behaviour this feature is judged on, and a
     * cache is exactly what would break it. One metadata call per catalog load is
     * the price.</p>
     */
    private Map<String, GrafanaClientService.FolderHit> folderIndex() {
        Map<String, GrafanaClientService.FolderHit> index = new LinkedHashMap<>();
        for (GrafanaClientService.FolderHit folder : grafana.listFolders()) {
            if (folder.uid() != null && !folder.uid().isBlank()) {
                index.put(folder.uid(), folder);
            }
        }
        return index;
    }

    /**
     * A folder and its ancestors, root first, the folder itself last.
     *
     * <p>Empty when the UID is not in the index — an unknown folder has no lineage,
     * and callers treat that as "nothing to inherit" rather than guessing.</p>
     *
     * <p>Guarded twice over: a {@code seen} set so a parent cycle (which a corrupted
     * Grafana row can produce) terminates instead of hanging the request, and a
     * depth ceiling so a very long chain cannot turn a dropdown into a stall.</p>
     */
    private static List<GrafanaClientService.FolderHit> lineage(
            Map<String, GrafanaClientService.FolderHit> index, String uid) {
        LinkedList<GrafanaClientService.FolderHit> chain = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        String cursor = uid;
        while (cursor != null && !cursor.isBlank() && seen.add(cursor)
                && chain.size() < MAX_FOLDER_DEPTH) {
            GrafanaClientService.FolderHit node = index.get(cursor);
            if (node == null) {
                break;
            }
            chain.addFirst(node);
            cursor = node.parentUid();
        }
        return chain;
    }

    /**
     * Whether {@code grafana.allowed-folder-uids} admits this folder.
     *
     * <p>The allow-list is read as SUBTREES, not as an exact set: approving
     * "OpenObserver" approves everything under it, now and later. That is the
     * difference between an allow-list an administrator maintains once and one they
     * must edit — and redeploy — every time a folder is added in Grafana. Naming a
     * leaf folder still restricts to that leaf, so the narrow case is unchanged.</p>
     *
     * <p>Empty allow-list means the whole instance, as before.</p>
     */
    private boolean isFolderAllowed(Map<String, GrafanaClientService.FolderHit> index,
            String folderUid) {
        List<String> allowed = properties.getAllowedFolderUids();
        if (allowed.isEmpty()) {
            return true;
        }
        if (folderUid != null && allowed.contains(folderUid)) {
            return true;
        }
        // Not named directly — approved only if an ancestor is. An unknown UID has
        // no ancestry, so it stays refused.
        return lineage(index, folderUid).stream()
                .anyMatch(node -> allowed.contains(node.uid()));
    }

    private static String titleOf(GrafanaClientService.FolderHit folder) {
        return folder.title() == null || folder.title().isBlank() ? folder.uid() : folder.title();
    }

    // ── helpers: registry and validation ────────────────────────────────────

    private void requireUsable() {
        if (!properties.isUsable()) {
            // 409, via IllegalStateException: the request is well-formed, the
            // deployment is not in a state where it can be carried out.
            throw new IllegalStateException(unsupportedReason());
        }
    }

    private DashboardRegistration require(String scope, String uid) {
        return store.find(scope, uid).orElseThrow(() -> new ResourceNotFoundException(
                "Dashboard '" + uid + "' is not registered in this application."));
    }

    /** Display order after everything already filed in that folder. */
    private int nextOrderIn(String scope, String folderUid) {
        return store.list(scope).stream()
                .filter(reg -> folderUid.equals(reg.folderUid()))
                .mapToInt(DashboardRegistration::displayOrder)
                .max()
                .orElse(0) + 1;
    }

    /**
     * The title of a folder the caller may file into.
     *
     * <p>Doubles as the authorisation check: a folder that Grafana does not have, or
     * that {@code grafana.allowed-folder-uids} excludes, is not in the list and is
     * therefore refused. One lookup, two guarantees.</p>
     */
    private String resolveFolderTitle(String folderUid) {
        for (DashboardCatalogResponseDTO.GrafanaFolder folder : folders().getItems()) {
            if (folderUid.equals(folder.getUid())) {
                return folder.getTitle();
            }
        }
        throw new IllegalArgumentException("Folder '" + folderUid + "' does not exist in Grafana, "
                + "or is not approved for this application.");
    }

    /**
     * Read a dashboard for registration, and check it is somewhere we are allowed
     * to register from.
     */
    private GrafanaClientService.DashboardDefinition fetchAuthorized(String uid) {
        GrafanaClientService.DashboardDefinition definition = grafana.dashboard(uid);
        if (definition == null) {
            throw new ResourceNotFoundException("No dashboard with UID '" + uid + "' exists in "
                    + "Grafana, or the service account cannot read it. Copy the UID from the "
                    + "Grafana URL: /d/<uid>/<slug>.");
        }
        // Subtree semantics, matching the picker: a dashboard in a SUBFOLDER of an
        // approved folder is approved. Checking the exact UID only would refuse
        // every dashboard the nested picker now offers.
        if (!properties.getAllowedFolderUids().isEmpty()
                && !isFolderAllowed(folderIndex(), definition.folderUid())) {
            throw new IllegalArgumentException("Dashboard \"" + definition.title() + "\" lives in "
                    + "Grafana folder \"" + definition.folderTitle() + "\", which is not an "
                    + "approved folder for this application.");
        }
        return definition;
    }

    private static String folderTitleOf(DashboardRegistration registration) {
        String title = registration.folderTitle();
        return title == null || title.isBlank() ? UNGROUPED : title;
    }

    /** Display order, then title — so the tiles are stable across reloads. */
    private static List<DashboardRegistration> sorted(List<DashboardRegistration> rows) {
        List<DashboardRegistration> copy = new ArrayList<>(rows);
        copy.sort(Comparator
                .comparingInt(DashboardRegistration::displayOrder)
                .thenComparing(reg -> reg.title() == null ? "" : reg.title(),
                        String.CASE_INSENSITIVE_ORDER));
        return copy;
    }

    private static DashboardCatalogResponseDTO.Registration toView(DashboardRegistration reg) {
        DashboardCatalogResponseDTO.Registration view =
                new DashboardCatalogResponseDTO.Registration();
        view.setUid(reg.uid());
        view.setTitle(reg.title());
        view.setFolderUid(reg.folderUid());
        view.setFolderTitle(folderTitleOf(reg));
        view.setGrafanaFolderUid(reg.grafanaFolderUid());
        view.setGrafanaFolderTitle(reg.grafanaFolderTitle());
        view.setFolderMismatch(reg.folderMismatch());
        view.setEnabled(reg.enabled());
        view.setDisplayOrder(reg.displayOrder());
        view.setRegisteredAt(reg.registeredAt() == null ? null : reg.registeredAt().toString());
        view.setUpdatedAt(reg.updatedAt() == null ? null : reg.updatedAt().toString());
        return view;
    }
}
