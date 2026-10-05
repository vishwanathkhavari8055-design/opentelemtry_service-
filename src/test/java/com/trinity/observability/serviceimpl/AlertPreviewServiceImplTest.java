package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertPreviewRequestDTO;
import com.trinity.observability.dto.alerts.AlertPreviewResponseDTO;
import com.trinity.observability.service.AlertScopeService;
import com.trinity.observability.service.PromMetricsService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers what the preview CLAIMS, which is the part that can be wrong quietly.
 *
 * <p>Whether a query runs is obvious the first time somebody presses the button.
 * Whether the ladder attributes a collapse to the right condition, whether an
 * unscannable window is reported as such instead of as an honest zero, and
 * whether "would fire" agrees with the trigger it was given are not obvious at
 * all — they produce a confident screen either way, and the operator acts on
 * it.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertPreviewServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private OpenObserveClientService client;
    @Mock
    private AlertScopeService scopes;
    @Mock
    private PromMetricsService prom;

    private OpenObserveConfig config;
    private AlertPreviewServiceImpl service;

    /** Row counts to answer with, keyed by a fragment of the WHERE clause. */
    private final Map<String, Long> countsByWhere = new LinkedHashMap<>();
    private long unfilteredCount;
    private boolean complete = true;

    @BeforeEach
    void setUp() {
        config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", "default");

        service = new AlertPreviewServiceImpl(client, config, scopes, prom);
        ReflectionTestUtils.setField(service, "openObserveEnabled", true);

        when(client.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (!sql.startsWith("SELECT COUNT(*)")) {
                        // A sample query. One row is enough to prove samples are
                        // requested and attached; their content is not the point.
                        return new OpenObserveClientService.SearchOutcome(
                                List.of(Map.of("_timestamp", 1L)), !complete, null);
                    }
                    long rows = unfilteredCount;
                    for (Map.Entry<String, Long> entry : countsByWhere.entrySet()) {
                        if (sql.contains(entry.getKey())) {
                            rows = entry.getValue();
                        }
                    }
                    return new OpenObserveClientService.SearchOutcome(
                            List.of(Map.of("trinity_preview_count", rows)), !complete, null);
                });
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AlertPreviewRequestDTO.Preview request(String conditions, String trigger) {
        AlertPreviewRequestDTO.Preview request = new AlertPreviewRequestDTO.Preview();
        request.setStreamType("logs");
        request.setStreamName("default");
        request.setQueryCondition(json("{\"type\":\"custom\",\"conditions\":" + conditions + "}"));
        if (trigger != null) {
            request.setTriggerCondition(json(trigger));
        }
        request.setSampleSize(0);
        return request;
    }

    // ── the ladder ──────────────────────────────────────────────────────────

    @Test
    void reportsEachConditionAsItsOwnStep() {
        unfilteredCount = 10_000;
        countsByWhere.put("\"service_name\" = 'svc'", 4_000L);
        countsByWhere.put("\"code\" >= 500", 120L);

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"service_name","operator":"=","value":"svc"},
                        {"column":"code","operator":">=","value":500}]}""", null));

        assertEquals(3, out.getSteps().size());
        assertEquals(10_000, out.getSteps().get(0).getMatched());
        assertEquals(4_000, out.getSteps().get(1).getMatched());
        assertEquals(120, out.getSteps().get(2).getMatched());
    }

    @Test
    void attributesTheRowsRemovedToTheConditionThatRemovedThem() {
        // The number the eye goes to. Running totals alone do not say which
        // condition did the damage, which is the whole reason for the ladder.
        unfilteredCount = 10_000;
        countsByWhere.put("\"service_name\" = 'svc'", 9_900L);
        countsByWhere.put("\"code\" >= 500", 0L);

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"service_name","operator":"=","value":"svc"},
                        {"column":"code","operator":">=","value":500}]}""", null));

        assertEquals(0, out.getSteps().get(0).getRemoved(), "nothing precedes step 0");
        assertEquals(100, out.getSteps().get(1).getRemoved());
        assertEquals(9_900, out.getSteps().get(2).getRemoved(), "the collapsing condition owns it");
    }

    @Test
    void anEmptyStepZeroSeparatesAWrongStreamFromWrongConditions() {
        // With no step 0, an empty result is indistinguishable from conditions
        // that match nothing, and the operator rewrites a rule that was fine.
        unfilteredCount = 0;

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"service_name","operator":"=","value":"svc"}]}""", null));

        assertEquals(0, out.getSteps().get(0).getMatched());
        assertEquals(0, out.getSteps().get(0).getRemoved());
    }

    @Test
    void reportsPartialityRatherThanPassingItOffAsZero() {
        // OpenObserve answers an unscannable window with 200, no hits and
        // is_partial. Reported as complete, that is a lie the operator acts on.
        unfilteredCount = 0;
        complete = false;

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"a","operator":"=","value":1}]}""", null));

        assertFalse(out.getSteps().get(0).isComplete());
    }

    @Test
    void aColumnTheStreamDoesNotHaveIsReportedOnItsOwnStep() {
        // THE common mistake, and the one the ladder exists for. It translates
        // fine and only fails when the query runs, so the steps around it — which
        // are the context that makes it obvious — must survive.
        unfilteredCount = 500;
        when(client.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("typo_column")) {
                        throw new IllegalStateException("unknown field 'typo_column'");
                    }
                    return new OpenObserveClientService.SearchOutcome(
                            List.of(Map.of("trinity_preview_count", 500L)), false, null);
                });

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"service_name","operator":"=","value":"svc"},
                        {"column":"typo_column","operator":"=","value":"x"}]}""", null));

        assertEquals(3, out.getSteps().size());
        assertEquals(500, out.getSteps().get(0).getMatched());
        assertNull(out.getSteps().get(1).getError());
        assertNotNull(out.getSteps().get(2).getError(), "the bad step names itself");
        assertTrue(out.getSteps().get(2).getError().contains("typo_column"));
        assertFalse(out.getEvaluation().isEvaluated(),
                "and firing is not decided from a query that did not run");
    }

    @Test
    void refusesAnOperatorItCannotTranslate() {
        // Unlike a bad column, this cannot be turned into SQL at all, so there is
        // no query to report a finding about — it is the request that is wrong.
        // The message names the operator so the caller can act on it.
        unfilteredCount = 500;
        AlertPreviewRequestDTO.Preview request = request("""
                {"and":[{"column":"code","operator":"matches_regex","value":"5.."}]}""", null);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.preview(request));
        assertTrue(thrown.getMessage().contains("matches_regex"));
    }

    @Test
    void refusesAnInjectedColumnOutright() {
        AlertPreviewRequestDTO.Preview request = request("""
                {"and":[{"column":"a' OR '1'='1","operator":"=","value":"x"}]}""", null);
        assertThrows(IllegalArgumentException.class, () -> service.preview(request));
    }

    @Test
    void refusesAPreviewWithNoStream() {
        AlertPreviewRequestDTO.Preview request = request("""
                {"and":[{"column":"a","operator":"=","value":1}]}""", null);
        request.setStreamName(null);
        assertThrows(IllegalArgumentException.class, () -> service.preview(request));
    }

    @Test
    void attachesSampleRowsOnlyWhenAsked() {
        unfilteredCount = 5;
        AlertPreviewRequestDTO.Preview off = request("""
                {"and":[{"column":"a","operator":"=","value":1}]}""", null);
        assertTrue(service.preview(off).getSteps().get(0).getRows().isEmpty());

        AlertPreviewRequestDTO.Preview on = request("""
                {"and":[{"column":"a","operator":"=","value":1}]}""", null);
        on.setSampleSize(5);
        assertFalse(service.preview(on).getSteps().get(0).getRows().isEmpty());
    }

    // ── would it fire ───────────────────────────────────────────────────────

    @Test
    void decidesFiringFromTheTriggerItWasGiven() {
        unfilteredCount = 10_000;
        countsByWhere.put("\"code\" >= 500", 12L);

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"code","operator":">=","value":500}]}""",
                """
                {"period":15,"operator":">=","threshold":10}"""));

        assertTrue(out.getEvaluation().isEvaluated());
        assertTrue(out.getEvaluation().isWouldFire());
        assertEquals(12.0, out.getEvaluation().getObserved());
        assertEquals(15, out.getPeriodMinutes(), "the window comes from the trigger's period");
    }

    @Test
    void saysItWouldNotFireWhenTheCountIsBelowTheThreshold() {
        unfilteredCount = 10_000;
        countsByWhere.put("\"code\" >= 500", 3L);

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"code","operator":">=","value":500}]}""",
                """
                {"period":10,"operator":">=","threshold":10}"""));

        assertFalse(out.getEvaluation().isWouldFire());
        assertNotNull(out.getEvaluation().getSummary());
    }

    @Test
    void declinesToDecideWithoutATrigger() {
        // Rather than defaulting to ">= 1", which would report almost every
        // draft rule as firing.
        unfilteredCount = 42;

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[]}""", null));

        assertFalse(out.getEvaluation().isEvaluated());
        assertFalse(out.getEvaluation().isWouldFire());
        assertEquals(42.0, out.getEvaluation().getObserved());
    }

    @Test
    void anUnrecognisedTriggerOperatorDoesNotBecomeGreaterThan() {
        // Defaulting an unknown operator to ">=" would report a rule as firing
        // when it does not. Equality is the reading that cannot manufacture one.
        unfilteredCount = 10_000;
        countsByWhere.put("\"code\" >= 500", 12L);

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[{"column":"code","operator":">=","value":500}]}""",
                """
                {"period":10,"operator":"wobbles","threshold":10}"""));

        assertFalse(out.getEvaluation().isWouldFire());
    }

    @Test
    void honoursAPeriodOverrideWithoutTouchingTheRule() {
        unfilteredCount = 1;
        AlertPreviewRequestDTO.Preview request = request("""
                {"and":[]}""",
                """
                {"period":10,"operator":">=","threshold":1}""");
        request.setPeriodMinutes(120);

        assertEquals(120, service.preview(request).getPeriodMinutes());
    }

    // ── aggregation ─────────────────────────────────────────────────────────

    @Test
    void countsQualifyingGroupsRatherThanRowsWhenTheRuleAggregates() {
        // A rule matching ten thousand rows fires on how many GROUPS clear the
        // HAVING clause. Previewing the row count and calling it the same thing
        // is how such a rule looks healthy and never pages.
        when(client.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.startsWith("SELECT COUNT(*)")) {
                        return new OpenObserveClientService.SearchOutcome(
                                List.of(Map.of("trinity_preview_count", 10_000L)), false, null);
                    }
                    if (sql.contains("trinity_preview_value")) {
                        List<Map<String, Object>> groups = new ArrayList<>();
                        groups.add(Map.of("k8s_namespace", "prod", "trinity_preview_value", 95.0));
                        groups.add(Map.of("k8s_namespace", "dev", "trinity_preview_value", 12.0));
                        groups.add(Map.of("k8s_namespace", "qa", "trinity_preview_value", 79.0));
                        return new OpenObserveClientService.SearchOutcome(groups, false, null);
                    }
                    return new OpenObserveClientService.SearchOutcome(List.of(), false, null);
                });

        AlertPreviewRequestDTO.Preview request = new AlertPreviewRequestDTO.Preview();
        request.setStreamType("metrics");
        request.setStreamName("container_cpu_usage");
        request.setSampleSize(0);
        request.setQueryCondition(json("""
                {"type":"custom","conditions":{"and":[]},
                 "aggregation":{"group_by":["k8s_namespace"],"function":"avg",
                                "having":{"column":"value","operator":">=","value":80}}}"""));
        request.setTriggerCondition(json("""
                {"period":10,"operator":">=","threshold":1}"""));

        AlertPreviewResponseDTO.Preview out = service.preview(request);

        assertEquals(1.0, out.getEvaluation().getObserved(), "only prod clears 80");
        assertTrue(out.getEvaluation().isWouldFire());
        assertEquals(3, out.getEvaluation().getGroups().size(),
                "every group is returned, including the ones that just missed");
        assertTrue(out.getEvaluation().getGroups().get(0).isSatisfies());
        assertFalse(out.getEvaluation().getGroups().get(2).isSatisfies(), "qa is at 79");
    }

    // ── disabled backend ────────────────────────────────────────────────────

    @Test
    void saysSoRatherThanShowingZerosWhenOpenObserveIsOff() {
        // Zeros would read as "your rule matches nothing", which is a claim
        // about the rule. The truth is a claim about us.
        ReflectionTestUtils.setField(service, "openObserveEnabled", false);

        AlertPreviewResponseDTO.Preview out = service.preview(request("""
                {"and":[]}""", null));

        assertFalse(out.isSupported());
        assertNotNull(out.getError());
        assertTrue(out.getSteps().isEmpty());
    }
}
