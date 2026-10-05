package com.trinity.observability.clientimpl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.trinity.observability.client.GrafanaClientService;
import com.trinity.observability.config.GrafanaConfig;
import com.trinity.observability.config.GrafanaProperties;
import com.trinity.observability.dto.dashboards.DashboardCatalogResponseDTO;
import com.trinity.observability.registry.DashboardRegistration;
import com.trinity.observability.registry.DashboardRegistryStore;
import com.trinity.observability.serviceimpl.DashboardCatalogServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test of the folder walk against a LIVE Grafana.
 *
 * <p>Skipped unless {@code GRAFANA_LIVE_SMOKE=1}, and it needs
 * {@code GRAFANA_BASE_URL} and {@code GRAFANA_SA_TOKEN} in the environment. Same
 * shape as the OpenObserve smoke test beside it, and for the same reason: this is
 * the one thing a scripted client cannot check.</p>
 *
 * <p>What it exists to catch is specific. {@code /api/folders} answers with
 * ROOT-LEVEL folders only — on the instance this was written against, 39 of 77 —
 * and a unit test with a hand-written folder list will never notice, because the
 * fixture simply contains whatever the author put in it. The only way to know the
 * nested folders are actually reachable is to ask a real Grafana, so this asserts
 * the paged search finds strictly MORE folders than the root listing, and that at
 * least one of them has a parent.</p>
 */
@EnabledIfEnvironmentVariable(named = "GRAFANA_LIVE_SMOKE", matches = "1")
class GrafanaFolderTreeLiveSmokeTest {

    @Test
    void liveListingIncludesNestedFolders() {
        GrafanaProperties properties = new GrafanaProperties();
        properties.setBaseUrl(System.getenv("GRAFANA_BASE_URL"));
        properties.setToken(System.getenv("GRAFANA_SA_TOKEN"));
        properties.setTlsInsecure(true);

        GrafanaClientImpl client = new GrafanaClientImpl(new GrafanaConfig(properties));
        List<GrafanaClientService.FolderHit> folders = client.listFolders();

        assertFalse(folders.isEmpty(), "a live Grafana should have at least one folder");

        long nested = folders.stream()
                .filter(f -> f.parentUid() != null && !f.parentUid().isBlank())
                .count();
        System.out.println("[live] folders: " + folders.size() + ", of which nested: " + nested);
        folders.stream()
                .filter(f -> f.parentUid() != null && !f.parentUid().isBlank())
                .limit(10)
                .forEach(f -> System.out.println(
                        "[live]   " + f.title() + " (parent " + f.parentUid() + ")"));

        assertTrue(nested > 0, "the walk found no nested folder — /api/search?type=dash-folder "
                + "is either unavailable on this instance or its parentage is being dropped");
    }

    /**
     * The strings the folder picker will actually render, built from live parentage.
     *
     * <p>Goes one layer up from the test above, because the path is assembled in the
     * service and a client that returns correct parentage can still be presented
     * wrongly. Prints the whole tree so a mis-parented folder is visible rather than
     * merely counted.</p>
     */
    @Test
    void livePathsReadAsBreadcrumbs() {
        GrafanaProperties properties = new GrafanaProperties();
        properties.setBaseUrl(System.getenv("GRAFANA_BASE_URL"));
        properties.setToken(System.getenv("GRAFANA_SA_TOKEN"));
        properties.setTlsInsecure(true);

        DashboardCatalogServiceImpl service = new DashboardCatalogServiceImpl(
                new GrafanaClientImpl(new GrafanaConfig(properties)), NO_REGISTRY, properties);

        List<DashboardCatalogResponseDTO.GrafanaFolder> items = service.folders().getItems();
        assertFalse(items.isEmpty());

        System.out.println("[live] " + items.size() + " folder(s) offered by the picker:");
        items.forEach(folder -> System.out.println(
                "[live]   d" + folder.getDepth() + "  " + folder.getPath()));

        // A nested folder's path names every ancestor; a root folder's is its title.
        items.forEach(folder -> {
            if (folder.getDepth() == 0) {
                assertEquals(folder.getTitle(), folder.getPath());
            } else {
                assertTrue(folder.getPath().endsWith(" / " + folder.getTitle()),
                        "path should end in the folder's own title: " + folder.getPath());
                assertEquals(folder.getDepth(), folder.getPath().split(" / ").length - 1);
            }
        });

        assertTrue(items.stream().anyMatch(folder -> folder.getDepth() > 0),
                "no nested folder reached the picker");
    }

    /**
     * A registry that refuses every call.
     *
     * <p>Listing folders is a pure Grafana read — it must not consult what is
     * registered — so anything that did would fail here loudly instead of quietly
     * coupling the picker to the registry.</p>
     */
    private static final DashboardRegistryStore NO_REGISTRY = new DashboardRegistryStore() {

        @Override
        public List<DashboardRegistration> list(String scopeKey) {
            throw new UnsupportedOperationException("folders() must not read the registry");
        }

        @Override
        public Optional<DashboardRegistration> find(String scopeKey, String uid) {
            throw new UnsupportedOperationException("folders() must not read the registry");
        }

        @Override
        public DashboardRegistration save(DashboardRegistration registration) {
            throw new UnsupportedOperationException("this test writes nothing");
        }

        @Override
        public boolean delete(String scopeKey, String uid) {
            throw new UnsupportedOperationException("this test writes nothing");
        }

        @Override
        public String describe() {
            return "none (live smoke test)";
        }
    };
}
