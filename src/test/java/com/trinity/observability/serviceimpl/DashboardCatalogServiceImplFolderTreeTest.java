package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.GrafanaClientService;
import com.trinity.observability.config.GrafanaProperties;
import com.trinity.observability.dto.dashboards.DashboardCatalogRequestDTO;
import com.trinity.observability.dto.dashboards.DashboardCatalogResponseDTO;
import com.trinity.observability.registry.DashboardRegistration;
import com.trinity.observability.registry.DashboardRegistryStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the Dashboard Catalog presents a NESTED Grafana folder tree.
 *
 * <p>The instance this was built against files the folders that matter under
 * {@code Monitoring / OpenObserver / <org> / <service group> / <service>} — four
 * levels down. Two things follow, and both are what these tests pin:</p>
 *
 * <ul>
 *   <li>a folder must be offered by its full PATH, because "Default Org." is not a
 *       unique or even meaningful label on its own; and</li>
 *   <li>{@code grafana.allowed-folder-uids} must approve SUBTREES, because an
 *       allow-list that has to be edited every time someone adds a folder in
 *       Grafana is one that will be wrong within a week.</li>
 * </ul>
 *
 * <p>The Grafana client is scripted, so nothing here touches the network.</p>
 */
class DashboardCatalogServiceImplFolderTreeTest {

    private ScriptedGrafana grafana;
    private GrafanaProperties properties;
    private DashboardCatalogServiceImpl service;

    /**
     * A cut-down copy of the real tree, deep enough to matter: {@code monitoring}
     * holds {@code openobserve}, which holds two org folders, one of which holds a
     * service group holding a leaf. {@code standalone} sits at the root as the
     * control.
     */
    @BeforeEach
    void setUp() {
        grafana = new ScriptedGrafana();
        grafana.folder("monitoring", "Monitoring", "");
        grafana.folder("openobserve", "OpenObserver", "monitoring");
        grafana.folder("default-org", "Default Org.", "openobserve");
        grafana.folder("dlh-org", "DLHLnM Org.", "openobserve");
        grafana.folder("dlh-services", "DLH Services", "dlh-org");
        grafana.folder("kafka", "Apache Kafka", "dlh-services");
        grafana.folder("standalone", "Standalone", "");

        properties = new GrafanaProperties();
        properties.setBaseUrl("https://grafana.example");
        properties.setToken("glsa_test");

        service = new DashboardCatalogServiceImpl(grafana, new InMemoryStore(), properties);
    }

    private Map<String, DashboardCatalogResponseDTO.GrafanaFolder> foldersByUid() {
        Map<String, DashboardCatalogResponseDTO.GrafanaFolder> byUid = new HashMap<>();
        service.folders().getItems().forEach(folder -> byUid.put(folder.getUid(), folder));
        return byUid;
    }

    // ── the whole tree is offered, not just its top level ────────────────────

    @Test
    void offersNestedFoldersAndNotOnlyRoots() {
        List<DashboardCatalogResponseDTO.GrafanaFolder> items = service.folders().getItems();

        assertEquals(7, items.size(), "every folder in the tree should be selectable");
        assertTrue(items.stream().anyMatch(folder -> "kafka".equals(folder.getUid())),
                "a folder four levels down must still be offered — this is the whole point");
    }

    @Test
    void namesEachFolderByItsFullPath() {
        Map<String, DashboardCatalogResponseDTO.GrafanaFolder> byUid = foldersByUid();

        assertEquals("Monitoring", byUid.get("monitoring").getPath(),
                "a root folder's path is just its title");
        assertEquals("Monitoring / OpenObserver / Default Org.",
                byUid.get("default-org").getPath());
        assertEquals("Monitoring / OpenObserver / DLHLnM Org. / DLH Services / Apache Kafka",
                byUid.get("kafka").getPath());

        // The bare title stays available: it is what gets stored as the tile label,
        // where the full path would be noise.
        assertEquals("Apache Kafka", byUid.get("kafka").getTitle());
        assertEquals(4, byUid.get("kafka").getDepth());
        assertEquals(0, byUid.get("monitoring").getDepth());
    }

    @Test
    void sortsByPathSoChildrenFollowTheirParent() {
        List<String> paths = service.folders().getItems().stream()
                .map(DashboardCatalogResponseDTO.GrafanaFolder::getPath)
                .toList();

        // Case-insensitive, so "Default Org." lands before "DLHLnM Org." — the
        // property that matters is that a child never floats away from its parent.
        assertEquals(List.of(
                "Monitoring",
                "Monitoring / OpenObserver",
                "Monitoring / OpenObserver / Default Org.",
                "Monitoring / OpenObserver / DLHLnM Org.",
                "Monitoring / OpenObserver / DLHLnM Org. / DLH Services",
                "Monitoring / OpenObserver / DLHLnM Org. / DLH Services / Apache Kafka",
                "Standalone"), paths);
    }

    /**
     * A folder created in Grafana after startup needs no configuration change here:
     * the list is read live, never cached.
     */
    @Test
    void picksUpAFolderAddedAfterTheFirstListing() {
        assertEquals(7, service.folders().getItems().size());

        grafana.folder("trino", "Trino", "dlh-services");

        Map<String, DashboardCatalogResponseDTO.GrafanaFolder> byUid = foldersByUid();
        assertEquals(8, byUid.size());
        assertEquals("Monitoring / OpenObserver / DLHLnM Org. / DLH Services / Trino",
                byUid.get("trino").getPath());
    }

    // ── the allow-list approves subtrees ─────────────────────────────────────

    @Test
    void approvingAParentApprovesEverythingUnderIt() {
        properties.setAllowedFolderUids(List.of("openobserve"));

        List<String> uids = service.folders().getItems().stream()
                .map(DashboardCatalogResponseDTO.GrafanaFolder::getUid)
                .toList();

        assertEquals(List.of("openobserve", "default-org", "dlh-org", "dlh-services", "kafka"),
                uids, "the approved folder and its descendants, and nothing else");
        assertTrue(service.folders().isRestricted());
    }

    @Test
    void approvingALeafStillMeansOnlyThatLeaf() {
        properties.setAllowedFolderUids(List.of("kafka"));

        List<String> uids = service.folders().getItems().stream()
                .map(DashboardCatalogResponseDTO.GrafanaFolder::getUid)
                .toList();

        assertEquals(List.of("kafka"), uids);
    }

    @Test
    void offersEverythingWhenNoAllowListIsSet() {
        assertFalse(service.folders().isRestricted());
        assertEquals(7, service.folders().getItems().size());
    }

    // ── registering into a nested folder ────────────────────────────────────

    @Test
    void registersIntoANestedFolderAndStoresItsBareTitle() {
        grafana.dashboard("dash-1", "Kafka Throughput", "kafka", "Apache Kafka");

        DashboardCatalogRequestDTO.Register body = new DashboardCatalogRequestDTO.Register();
        body.setUid("dash-1");
        body.setFolderUid("kafka");

        DashboardCatalogResponseDTO.Registration saved = service.register(body);

        assertEquals("kafka", saved.getFolderUid());
        // The tile label — the bare title, not the path, which would read as
        // "Monitoring / OpenObserver / … / Apache Kafka" on the Dashboards screen.
        assertEquals("Apache Kafka", saved.getFolderTitle());
        assertFalse(saved.isFolderMismatch());
    }

    @Test
    void refusesADashboardOutsideTheApprovedSubtree() {
        properties.setAllowedFolderUids(List.of("openobserve"));
        grafana.dashboard("dash-2", "Elsewhere", "standalone", "Standalone");

        DashboardCatalogRequestDTO.Register body = new DashboardCatalogRequestDTO.Register();
        body.setUid("dash-2");
        body.setFolderUid("standalone");

        // Refused at the folder, before the dashboard is even read.
        assertThrows(IllegalArgumentException.class, () -> service.register(body));
    }

    @Test
    void acceptsADashboardInASubfolderOfTheApprovedFolder() {
        properties.setAllowedFolderUids(List.of("openobserve"));
        grafana.dashboard("dash-3", "Kafka Lag", "kafka", "Apache Kafka");

        DashboardCatalogRequestDTO.Register body = new DashboardCatalogRequestDTO.Register();
        body.setUid("dash-3");
        body.setFolderUid("kafka");

        assertEquals("dash-3", service.register(body).getUid());
    }

    // ── fakes ───────────────────────────────────────────────────────────────

    /** A Grafana whose folder tree and dashboards the test writes by hand. */
    private static final class ScriptedGrafana implements GrafanaClientService {

        private final List<FolderHit> folders = new ArrayList<>();
        private final Map<String, DashboardDefinition> dashboards = new HashMap<>();

        void folder(String uid, String title, String parentUid) {
            folders.add(new FolderHit(uid, title, parentUid));
        }

        void dashboard(String uid, String title, String folderUid, String folderTitle) {
            dashboards.put(uid,
                    new DashboardDefinition(uid, title, folderUid, folderTitle, 1, null, null));
        }

        @Override
        public List<DashboardHit> searchDashboards(String folderUid, String query) {
            return dashboards.values().stream()
                    .filter(d -> folderUid == null || folderUid.isBlank()
                            || folderUid.equals(d.folderUid()))
                    .map(d -> new DashboardHit(d.uid(), d.title(), "/d/" + d.uid(),
                            d.folderUid(), d.folderTitle(), List.of()))
                    .toList();
        }

        @Override
        public List<FolderHit> listFolders() {
            return List.copyOf(folders);
        }

        @Override
        public DashboardDefinition dashboard(String uid) {
            return dashboards.get(uid);
        }

        @Override
        public List<DatasourceHit> datasources(String type) {
            return List.of();
        }

        @Override
        public JsonNode query(JsonNode payload) {
            return null;
        }

        @Override
        public List<String> labelValues(String dsUid, String label, String metric,
                Long fromMs, Long toMs) {
            return List.of();
        }
    }

    /** The registry, in a HashMap. */
    private static final class InMemoryStore implements DashboardRegistryStore {

        private final Map<String, DashboardRegistration> rows = new HashMap<>();

        private static String key(String scope, String uid) {
            return scope + ' ' + uid;
        }

        @Override
        public List<DashboardRegistration> list(String scopeKey) {
            return rows.values().stream()
                    .filter(reg -> reg.scopeKey().equals(scopeKey))
                    .toList();
        }

        @Override
        public Optional<DashboardRegistration> find(String scopeKey, String uid) {
            return Optional.ofNullable(rows.get(key(scopeKey, uid)));
        }

        @Override
        public DashboardRegistration save(DashboardRegistration registration) {
            rows.put(key(registration.scopeKey(), registration.uid()), registration);
            return registration;
        }

        @Override
        public boolean delete(String scopeKey, String uid) {
            return rows.remove(key(scopeKey, uid)) != null;
        }

        @Override
        public String describe() {
            return "in-memory (test)";
        }
    }
}
