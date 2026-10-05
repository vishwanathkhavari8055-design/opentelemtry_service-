package com.trinity.observability.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DashboardDatasourceNormalizer}.
 *
 * <p>Two things are being pinned down, and the second matters more than the
 * first. One: the shape that actually broke — the "IoT Device Monitoring
 * Dashboard", whose panels name a real Prometheus UID while their targets still
 * name the {@code ${DS_PROMETHEUS}} placeholder an import never substituted — is
 * repaired. Two: every OTHER shape is left strictly alone. This class runs on the
 * way to the browser for every dashboard in the catalog, so a false positive
 * here would break dashboards that work today, and the "leave it alone" cases
 * below outnumber the repair cases deliberately.</p>
 */
class DashboardDatasourceNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode model(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String targetDs(JsonNode model, int panel, int target) {
        return model.path("panels").path(panel).path("targets").path(target)
                .path("datasource").path("uid").asText();
    }

    private static String panelDs(JsonNode model, int panel) {
        return model.path("panels").path(panel).path("datasource").path("uid").asText();
    }

    // ── the bug ─────────────────────────────────────────────────────────────

    /**
     * The exact shape of the broken dashboard: a live panel datasource, targets
     * still on the placeholder, and no templating list to declare it.
     */
    @Test
    void targetOnAnUndeclaredVariableInheritsThePanelDatasource() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":4,"title":"Online Devices",
                   "datasource":{"type":"prometheus","uid":"ffto7tbvxmeiod"},
                   "targets":[{"refId":"A","expr":"sum(iot_device_status)",
                     "datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertTrue(out.changed());
        assertEquals("ffto7tbvxmeiod", targetDs(out.model(), 0, 0));
        // The panel itself was already correct and must not have moved.
        assertEquals("ffto7tbvxmeiod", panelDs(out.model(), 0));
        // Everything else about the target survives.
        assertEquals("sum(iot_device_status)",
                out.model().path("panels").path(0).path("targets").path(0).path("expr").asText());
    }

    /** Every target of a multi-query panel is repaired, not just the first. */
    @Test
    void allTargetsOfAPanelAreRepaired() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":5,"title":"Inventory",
                   "datasource":{"type":"prometheus","uid":"ffto7tbvxmeiod"},
                   "targets":[
                     {"refId":"Telemetry_Up","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}},
                     {"refId":"Operational_Status","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}},
                     {"refId":"A","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertEquals(3, out.repairs().size());
        for (int i = 0; i < 3; i++) {
            assertEquals("ffto7tbvxmeiod", targetDs(out.model(), 0, i));
        }
    }

    /** The reverse case: the panel lost its reference but a target kept one. */
    @Test
    void panelOnAnUndeclaredVariableInheritsTheFirstLiveTarget() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":7,"title":"Telemetry Status",
                   "datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"},
                   "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"dfsrt72yt7vgga"}}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertTrue(out.changed());
        assertEquals("dfsrt72yt7vgga", panelDs(out.model(), 0));
        assertEquals("dfsrt72yt7vgga", targetDs(out.model(), 0, 0));
    }

    /** Panels inside a collapsed row are reached too. */
    @Test
    void panelsNestedInARowAreRepaired() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":1,"type":"row","title":"Devices","collapsed":true,
                   "panels":[{"id":9,"title":"Inner",
                     "datasource":{"type":"prometheus","uid":"ffto7tbvxmeiod"},
                     "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}}]}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertTrue(out.changed());
        assertEquals("ffto7tbvxmeiod", out.model().path("panels").path(0).path("panels").path(0)
                .path("targets").path(0).path("datasource").path("uid").asText());
    }

    /** The legacy spelling, where a datasource reference is a bare string. */
    @Test
    void stringValuedVariableReferencesAreRepairedToo() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":2,"title":"Legacy","datasource":"Prometheus-Nightly",
                   "targets":[{"refId":"A","datasource":"$DS_PROMETHEUS"},
                              {"refId":"B","datasource":"[[DS_PROMETHEUS]]"},
                              {"refId":"C","datasource":"${DS_PROMETHEUS:raw}"}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertEquals(3, out.repairs().size());
        JsonNode targets = out.model().path("panels").path(0).path("targets");
        for (int i = 0; i < 3; i++) {
            assertEquals("Prometheus-Nightly", targets.path(i).path("datasource").asText());
        }
    }

    // ── everything that must be left alone ──────────────────────────────────

    /**
     * Models this class must hand back untouched — the same node, not a copy.
     */
    static java.util.stream.Stream<Arguments> modelsLeftUntouched() {
        return java.util.stream.Stream.of(
            // The whole safety case. A variable the dashboard DOES declare is a working
            // datasource picker, and rewriting it would pin every panel to one datasource.
            Arguments.of("declaredVariablesAreNeverTouched", """
                    {"templating":{"list":[{"name":"DS_PROMETHEUS","type":"datasource"}]},
                     "panels":[{"id":1,"title":"Picked",
                       "datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"},
                       "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}}]}]}"""),
            // A dashboard with concrete UIDs throughout is handed back as the same node.
            Arguments.of("aWellFormedModelIsReturnedUnchangedAndUncopied", """
                    {"templating":{"list":[]},
                     "panels":[{"id":3,"title":"Total Devices",
                       "datasource":{"type":"prometheus","uid":"dfsrt72yt7vgga"},
                       "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"dfsrt72yt7vgga"}}]}]}"""),
            // A panel where nothing names a live datasource keeps its placeholders. The
            // alternative is inventing a datasource nobody chose, which would draw a
            // confidently wrong panel instead of a visibly broken one.
            Arguments.of("aPanelWithNoLiveReferenceAnywhereIsLeftAlone", """
                    {"templating":{"list":[]},
                     "panels":[{"id":8,"title":"Orphan",
                       "datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"},
                       "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}}]}]}"""),
            // Grafana's built-in pseudo-datasources are plain names, not variables, and
            // the "-- Mixed --" case is exactly the one where per-target datasources
            // are meant to differ from the panel's.
            Arguments.of("builtInAndMixedDatasourcesSurvive", """
                    {"templating":{"list":[]},
                     "panels":[{"id":1,"title":"Mixed","datasource":{"type":"datasource","uid":"-- Mixed --"},
                       "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"dfsrt72yt7vgga"}},
                                  {"refId":"B","datasource":{"type":"loki","uid":"d59145df"}}]},
                      {"id":2,"title":"Dashboard ds","datasource":"-- Grafana --",
                       "targets":[{"refId":"A","datasource":"-- Dashboard --"}]}]}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modelsLeftUntouched")
    void modelsThatNeedNoRepairAreReturnedAsTheSameNode(String label, String json) {
        JsonNode in = model(json);

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertFalse(out.changed(), label);
        assertSame(in, out.model(), label);
    }

    /**
     * A target with no datasource of its own already inherits the panel's in
     * Grafana. Writing one in would be harmless but is not this class's business,
     * and staying out keeps the diff to what was actually broken.
     */
    @Test
    void targetsWithNoDatasourceAtAllAreLeftAlone() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":1,"title":"Implicit",
                   "datasource":{"type":"prometheus","uid":"dfsrt72yt7vgga"},
                   "targets":[{"refId":"A","expr":"up"}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertFalse(out.changed());
        assertTrue(out.model().path("panels").path(0).path("targets").path(0)
                .path("datasource").isMissingNode());
    }

    /**
     * A reference that merely CONTAINS a variable is not a reference this class
     * can read, so it is not one it may rewrite.
     */
    @Test
    void partialVariableInterpolationIsNotTreatedAsAVariable() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":1,"title":"Composed",
                   "datasource":{"type":"prometheus","uid":"dfsrt72yt7vgga"},
                   "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"prom-${env}"}}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertFalse(out.changed());
        assertEquals("prom-${env}", targetDs(out.model(), 0, 0));
    }

    /** The input node is never mutated — callers may still be holding it. */
    @Test
    void theInputModelIsNotMutated() {
        JsonNode in = model("""
                {"templating":{"list":[]},
                 "panels":[{"id":4,"title":"Online Devices",
                   "datasource":{"type":"prometheus","uid":"ffto7tbvxmeiod"},
                   "targets":[{"refId":"A","datasource":{"type":"prometheus","uid":"${DS_PROMETHEUS}"}}]}]}""");

        DashboardDatasourceNormalizer.Result out = DashboardDatasourceNormalizer.normalize(in);

        assertEquals("${DS_PROMETHEUS}", targetDs(in, 0, 0));
        assertEquals("ffto7tbvxmeiod", targetDs(out.model(), 0, 0));
    }

    // ── shapes that must not throw ──────────────────────────────────────────

    @Test
    void oddModelsAreToleratedRatherThanRejected() {
        assertNull(DashboardDatasourceNormalizer.normalize(null).model());
        assertFalse(DashboardDatasourceNormalizer.normalize(model("[]")).changed());
        assertFalse(DashboardDatasourceNormalizer.normalize(model("{}")).changed());
        assertFalse(DashboardDatasourceNormalizer.normalize(
                model("{\"panels\":\"nonsense\"}")).changed());
        assertFalse(DashboardDatasourceNormalizer.normalize(
                model("{\"panels\":[{\"id\":1},null,7]}")).changed());
        assertFalse(DashboardDatasourceNormalizer.normalize(
                model("{\"panels\":[{\"id\":1,\"datasource\":null,\"targets\":null}]}")).changed());
    }
}
