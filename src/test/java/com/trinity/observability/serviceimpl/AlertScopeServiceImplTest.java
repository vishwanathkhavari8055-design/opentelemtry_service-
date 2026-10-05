package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertScopeResponseDTO;
import com.trinity.observability.dto.metrics.MetricProducersDTO;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.registry.ProductCatalogRegistry;
import com.trinity.observability.service.AlertsService;
import com.trinity.observability.service.MetricProducerService;
import com.trinity.observability.service.StreamCatalogService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the narrowing, and — just as importantly — what happens when it cannot
 * be done.
 *
 * <p>The narrowing itself is the feature. The fallback is what stops the feature
 * from becoming a new way to be stuck: three separate things can leave a scope
 * unresolvable (an index still sweeping, a resource registered without proven
 * bindings, OpenObserve unreachable), all three are transient, and an empty
 * dropdown in that moment is indistinguishable from a broken screen. So every
 * path here has to end in a usable list plus an honest {@code scoped} flag.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertScopeServiceImplTest {

    private static final String ORG = "default";

    @Mock
    private MetricProducerService metricProducers;
    @Mock
    private ProductCatalogRegistry catalog;
    @Mock
    private AlertsService alerts;
    @Mock
    private StreamCatalogService streamCatalog;
    @Mock
    private OpenObserveClientService oo;

    private OpenObserveConfig config;
    private AlertScopeServiceImpl service;

    @BeforeEach
    void setUp() {
        config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", ORG);
        service = new AlertScopeServiceImpl(metricProducers, catalog, alerts,
                streamCatalog, oo, config);
        ReflectionTestUtils.setField(service, "openObserveEnabled", true);
        ReflectionTestUtils.setField(service, "lookbackMinutes", 1440);

        when(catalog.findAll(anyString())).thenReturn(List.of());
        when(alerts.listStreams(anyString())).thenReturn(List.of("default", "k8s_logs"));
        when(metricProducers.getProducers()).thenReturn(producers(
                service("orders", "microservice",
                        emitted("http_server_duration", "orders_placed_total"),
                        observed("container_cpu_usage")),
                service("payments", "microservice",
                        emitted("payments_latency"),
                        observed("container_cpu_usage"))));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static MetricProducersDTO producers(MetricProducersDTO.Service... services) {
        MetricProducersDTO dto = new MetricProducersDTO();
        dto.setSupported(true);
        dto.setWindowSeconds(900);
        dto.setServices(List.of(services));
        return dto;
    }

    private static MetricProducersDTO.Service service(String name, String kind,
            MetricProducersDTO.Bucket emitted, MetricProducersDTO.Bucket observed) {
        return new MetricProducersDTO.Service(name, kind, kind, emitted, observed);
    }

    private static MetricProducersDTO.Bucket emitted(String... names) {
        return bucket(names);
    }

    private static MetricProducersDTO.Bucket observed(String... names) {
        return bucket(names);
    }

    private static MetricProducersDTO.Bucket bucket(String... names) {
        List<MetricProducersDTO.Metric> metrics = new ArrayList<>();
        for (String name : names) {
            metrics.add(new MetricProducersDTO.Metric(name, "app", "gauge"));
        }
        return new MetricProducersDTO.Bucket(names.length, List.of(), metrics);
    }

    private static CatalogResource resource(String id, String name, String product,
            TelemetryBinding... bindings) {
        CatalogResource resource = new CatalogResource();
        resource.setId(id);
        resource.setOrg(ORG);
        resource.setCategory(ResourceCategory.APPLICATIONS);
        resource.setResourceName(name);
        resource.setResolvedName(name);
        resource.setProductCode(product);
        resource.setProductLabel(product == null ? null : "Trinity " + product);
        resource.setStatus(ResourceStatus.ENABLED);
        resource.setBindings(List.of(bindings));
        return resource;
    }

    // ── metrics ─────────────────────────────────────────────────────────────

    @Test
    void narrowsMetricsToWhatTheChosenServiceActuallyWrites() {
        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "metrics", null);

        assertTrue(out.isScoped());
        assertEquals(List.of("http_server_duration", "orders_placed_total", "container_cpu_usage"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
        assertFalse(out.getItems().stream()
                .anyMatch(t -> "payments_latency".equals(t.getName())),
                "another service's metric must not be offered");
    }

    @Test
    void putsTheServicesOwnInstrumentationBeforePlatformTelemetryAboutIt() {
        // Platform metrics outnumber a service's own several times over, and
        // somebody scoping to a service almost always means the latter.
        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "metrics", null);

        assertEquals("emitted", out.getItems().get(0).getKind());
        assertEquals("observed", out.getItems().get(out.getItems().size() - 1).getKind());
    }

    @Test
    void canBeAskedForOnlyTheServicesOwnMetrics() {
        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "metrics", "emitted");

        assertEquals(2, out.getItems().size());
        assertTrue(out.getItems().stream().allMatch(t -> "emitted".equals(t.getKind())));
    }

    @Test
    void attachesNoFilterToAMetricsScope() {
        // A metric IS its own stream in OpenObserve, so picking one has already
        // narrowed the rule. A redundant service_name predicate would also break
        // a product spanning several services, where the metric carries them all.
        assertNull(service.listTargets("service", "orders", "metrics", null).getFilter());
    }

    @Test
    void aProductCoversEveryServiceRegisteredUnderIt() {
        when(catalog.findAll(ORG)).thenReturn(List.of(
                resource("r1", "orders", "checkout"),
                resource("r2", "payments", "checkout")));

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("product", "checkout", "metrics", null);

        assertTrue(out.isScoped());
        List<String> names = out.getItems().stream()
                .map(AlertScopeResponseDTO.Target::getName).toList();
        assertTrue(names.contains("orders_placed_total"));
        assertTrue(names.contains("payments_latency"));
        assertEquals(1, names.stream().filter("container_cpu_usage"::equals).count(),
                "a metric shared by both services is offered once, not twice");
    }

    // ── fallbacks ───────────────────────────────────────────────────────────

    @Test
    void fallsBackToEverythingWithScopedFalseWhenTheServiceIsUnknown() {
        // NOT an empty list. Empty is indistinguishable from broken and stops the
        // operator dead; wide plus a caveat lets them finish.
        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "never-seen", "metrics", null);

        assertFalse(out.isScoped());
        assertFalse(out.getItems().isEmpty());
        assertNotNull(out.getNotice());
        assertTrue(out.getNotice().contains("never-seen"));
    }

    @Test
    void survivesAProducerIndexThatThrows() {
        // This feeds a dropdown. An exception would blank the editor over
        // something the operator can neither see nor fix.
        when(metricProducers.getProducers()).thenThrow(new IllegalStateException("index down"));

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "metrics", null);

        assertFalse(out.isScoped());
        assertNotNull(out.getNotice());
        assertEquals(List.of("default", "k8s_logs"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList(),
                "falls all the way back to the plain stream listing");
    }

    // ── logs and traces ─────────────────────────────────────────────────────

    @Test
    void scopesLogsWithAPredicateBecauseTheStreamIsShared() {
        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        AlertScopeResponseDTO.ScopeFilter filter = out.getFilter();
        assertNotNull(filter);
        assertEquals("service_name", filter.getColumn());
        assertEquals("orders", filter.getValue());
        assertEquals("\"service_name\" = 'orders'", filter.getSql());
        assertEquals("orders", filter.getCondition().path("value").asText());
        assertFalse(filter.getCondition().path("ignore_case").asBoolean(),
                "IoTOpsApiSvc and iotopsapisvc are different services here");
    }

    @Test
    void prefersTheColumnAProbeActuallyMatchedOnOverAssumingServiceName() {
        // A binding records a column proven to exist on that stream. Assuming
        // service_name on a stream without it turns a working query into an
        // "unknown field" error the operator reads as a broken preview.
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "mongo", null,
                new TelemetryBinding("logs", "k8s_logs", "k8s_container_name", "mongo"))));

        AlertScopeResponseDTO.ScopeFilter filter =
                service.listTargets("resource", "r1", "logs", null).getFilter();

        assertEquals("k8s_container_name", filter.getColumn());
        assertEquals("\"k8s_container_name\" = 'mongo'", filter.getSql());
    }

    @Test
    void narrowsTheStreamListToProvenBindingsWhenThereAreAny() {
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "mongo", null,
                new TelemetryBinding("logs", "k8s_logs", "k8s_container_name", "mongo"))));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        // The other stream is checked cleanly and does not hold it, so only the
        // bound one survives.
        streamsHolding();

        AlertScopeResponseDTO.TargetList out = service.listTargets("resource", "r1", "logs", null);

        assertTrue(out.isScoped());
        assertEquals(List.of("k8s_logs"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
    }

    @Test
    void stillReturnsThePredicateWhenTheStreamCouldNotBeNarrowed() {
        // The two narrowings are independent. Not knowing WHICH stream carries a
        // service says nothing about whether the predicate for it is correct.
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "orders", null)));

        AlertScopeResponseDTO.TargetList out = service.listTargets("resource", "r1", "logs", null);

        assertFalse(out.isScoped());
        assertNotNull(out.getFilter());
        assertEquals("\"service_name\" = 'orders'", out.getFilter().getSql());
    }

    @Test
    void escapesAQuoteInAServiceNameRatherThanEndingTheLiteral() {
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "o'brien-svc", null)));

        AlertScopeResponseDTO.ScopeFilter filter =
                service.listTargets("resource", "r1", "logs", null).getFilter();

        assertEquals("\"service_name\" = 'o''brien-svc'", filter.getSql());
    }

    @Test
    void usesADisjunctionForAProductSoItDoesNotMatchNothing() {
        // A product's services spliced into an AND group would mean "this row is
        // every one of these services at once", which matches no row ever.
        when(catalog.findAll(ORG)).thenReturn(List.of(
                resource("r1", "orders", "checkout"),
                resource("r2", "payments", "checkout")));

        AlertScopeResponseDTO.ScopeFilter filter =
                service.listTargets("product", "checkout", "logs", null).getFilter();

        assertEquals("or", filter.getOperator());
        assertEquals("\"service_name\" IN ('orders', 'payments')", filter.getSql());
        assertTrue(filter.getCondition().has("or"));
    }

    @Test
    void returnsNoFilterWhenNothingIsSelected() {
        AlertScopeResponseDTO.TargetList out = service.listTargets(null, null, "logs", null);

        assertFalse(out.isScoped());
        assertNull(out.getFilter());
        assertFalse(out.getItems().isEmpty());
    }

    // ── which streams carry the service ─────────────────────────────────────

    /**
     * Makes the named streams answer a probe with a row; everything else is
     * checked cleanly and found empty.
     */
    private void streamsHolding(String... carrying) {
        Set<String> holds = new LinkedHashSet<>(List.of(carrying));
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    boolean hit = holds.stream().anyMatch(s -> sql.contains("FROM \"" + s + "\""));
                    return new OpenObserveClientService.SearchOutcome(
                            hit ? List.of(Map.<String, Object>of("service_name", "orders"))
                                : List.of(),
                            false, null);
                });
    }

    @Test
    void offersOnlyTheStreamsThatActuallyCarryTheService() {
        // The complaint this exists for: picking a service and still being shown
        // every stream in the tenant.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs", "rum_logs"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        streamsHolding("k8s_logs");

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertTrue(out.isScoped());
        assertEquals(List.of("k8s_logs"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
    }

    @Test
    void asksEachStreamRatherThanGuessingFromItsName() {
        // Two streams both carry the service. Any name-based heuristic would pick
        // one of them, and the operator's rule would miss half its data.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs", "rum_logs"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        streamsHolding("default", "rum_logs");

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertTrue(out.isScoped());
        assertEquals(List.of("default", "rum_logs"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
    }

    @Test
    void skipsAStreamWithNoServiceIdentityColumnWithoutQueryingIt() {
        // A stream that cannot express a per-service rule is excluded, and it
        // costs no query to know that — the schema already says so.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "audit_trail"));
        when(streamCatalog.fields("logs", "default")).thenReturn(Set.of("service_name", "body"));
        when(streamCatalog.fields("logs", "audit_trail")).thenReturn(Set.of("actor", "body"));
        streamsHolding("default");

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertEquals(List.of("default"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
        verify(oo, never()).searchDetailed(contains("audit_trail"), anyLong(), anyLong(),
                anyInt(), anyInt(), anyString());
    }

    @Test
    void findsAServiceByItsKubernetesColumnWhenTheStreamHasNoServiceName() {
        // Logs scraped from a pod carry no service.name attribute at all. Only
        // knowing service_name would declare that nothing carries the service and
        // fall back to showing every stream — the bug being fixed.
        when(alerts.listStreams("logs")).thenReturn(List.of("k8s_logs"));
        when(streamCatalog.fields("logs", "k8s_logs"))
                .thenReturn(Set.of("k8s_container_name", "body"));
        streamsHolding("k8s_logs");

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertTrue(out.isScoped());
        assertEquals("k8s_container_name", out.getItems().get(0).getFilterColumn());
    }

    @Test
    void carriesTheIdentityColumnPerStreamBecauseStreamsDisagree() {
        // The SDK writes service_name and the scraper writes k8s_container_name,
        // in the same tenant. One predicate for the whole list is wrong for half.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs"));
        when(streamCatalog.fields("logs", "default")).thenReturn(Set.of("service_name"));
        when(streamCatalog.fields("logs", "k8s_logs")).thenReturn(Set.of("k8s_container_name"));
        streamsHolding("default", "k8s_logs");

        List<AlertScopeResponseDTO.Target> items =
                service.listTargets("service", "orders", "logs", null).getItems();

        assertEquals("service_name", items.get(0).getFilterColumn());
        assertEquals("k8s_container_name", items.get(1).getFilterColumn());
    }

    @Test
    void resolvesTheFilterAgainstTheChosenStreamsOwnSchema() {
        // /fields is the call made once a stream is picked, so it is the one that
        // has to produce a predicate that will actually run against that stream.
        when(streamCatalog.fields("logs", "k8s_logs")).thenReturn(Set.of("k8s_container_name"));
        when(alerts.listStreamSchema(anyString(), anyString())).thenReturn(List.of());

        AlertScopeResponseDTO.FieldList out =
                service.listFields("service", "orders", "logs", "k8s_logs");

        assertEquals("k8s_container_name", out.getFilter().getColumn());
        assertEquals("\"k8s_container_name\" = 'orders'", out.getFilter().getSql());
    }

    @Test
    void keepsAnUncheckableStreamInTheListRatherThanDroppingIt() {
        // An unanswered question must never remove the operator's own stream from
        // the picker — that failure looks exactly like the narrowing working.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("k8s_logs")) {
                        // is_partial: OpenObserve could not scan the window.
                        return new OpenObserveClientService.SearchOutcome(List.of(), true, "20006");
                    }
                    return new OpenObserveClientService.SearchOutcome(
                            List.of(Map.<String, Object>of("service_name", "orders")), false, null);
                });

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        List<String> names = out.getItems().stream()
                .map(AlertScopeResponseDTO.Target::getName).toList();
        assertTrue(names.contains("default"), "the confirmed stream");
        assertTrue(names.contains("k8s_logs"), "and the one that could not be checked");
        assertNotNull(out.getNotice());
    }

    @Test
    void doesNotTreatAnUnscannableWindowAsProofTheStreamIsEmpty() {
        // Every probe came back partial. Nothing was established, so the list must
        // widen and SAY it widened rather than narrow to a guess.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenReturn(new OpenObserveClientService.SearchOutcome(List.of(), true, "20006"));

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertFalse(out.isScoped());
        assertEquals(2, out.getItems().size());
        assertNotNull(out.getNotice());
    }

    @Test
    void narrowsToExpressibleStreamsWhenTheServiceIsSimplyQuiet() {
        // Every check ran and found nothing. That is a fact about the telemetry,
        // so the list stays narrowed to streams where the rule is expressible
        // rather than reverting to everything.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "audit_trail"));
        when(streamCatalog.fields("logs", "default")).thenReturn(Set.of("service_name"));
        when(streamCatalog.fields("logs", "audit_trail")).thenReturn(Set.of("actor"));
        streamsHolding();

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertTrue(out.isScoped());
        assertEquals(List.of("default"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
        assertTrue(out.getNotice().contains("No logs for"));
    }

    @Test
    @SuppressWarnings("java:S2925")
    void probesStreamsInParallelRatherThanOneAfterAnother() {
        // Seventeen log streams asked one after another took 25 seconds against
        // the live instance — for a dropdown, on every cache miss. Nobody waits
        // that long; they conclude the editor is broken.
        List<String> streams = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            streams.add("stream_" + i);
        }
        when(alerts.listStreams("logs")).thenReturn(streams);
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(300);
                    return new OpenObserveClientService.SearchOutcome(List.of(), false, null);
                });

        long t0 = System.nanoTime();
        service.listTargets("service", "orders", "logs", null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Serial execution would take 12 times 300ms, around 3.6s. Eight threads should land near 600ms;
        // the assertion leaves generous headroom so it cannot flake on a busy box
        // while still failing outright if the probes serialise again.
        assertTrue(elapsedMs < 2_000,
                "12 probes of 300ms each took " + elapsedMs + "ms — they are not running in parallel");
    }

    @Test
    @SuppressWarnings("java:S2925")
    void givesUpOnASlowStreamRatherThanMakingTheDropdownWait() {
        // The budget keeps the stream in the list as UNKNOWN. Dropping it would be
        // the one unacceptable outcome; making the operator wait is the other.
        when(alerts.listStreams("logs")).thenReturn(List.of("fast", "glacial"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    if (invocation.<String>getArgument(0).contains("glacial")) {
                        Thread.sleep(30_000);
                    }
                    return new OpenObserveClientService.SearchOutcome(
                            List.of(Map.<String, Object>of("service_name", "orders")), false, null);
                });

        long t0 = System.nanoTime();
        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(elapsedMs < 15_000, "the budget did not cut the wait short: " + elapsedMs + "ms");
        List<String> names = out.getItems().stream()
                .map(AlertScopeResponseDTO.Target::getName).toList();
        assertTrue(names.contains("fast"));
        assertTrue(names.contains("glacial"), "an unanswered stream stays in the picker");
    }

    @Test
    void reusesAProbeInsteadOfReRunningItForEveryDropdownOpen() {
        when(alerts.listStreams("logs")).thenReturn(List.of("default"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        streamsHolding("default");

        service.listTargets("service", "orders", "logs", null);
        service.listTargets("service", "orders", "logs", null);
        service.listTargets("service", "orders", "logs", null);

        verify(oo, times(1)).searchDetailed(anyString(), anyLong(), anyLong(),
                anyInt(), anyInt(), anyString());
    }

    @Test
    void takesAProvenBindingAsReadWithoutProbingForIt() {
        // It came from a query that returned rows at registration time. Re-asking
        // would cost a round trip to confirm something already established.
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "mongo", null,
                new TelemetryBinding("logs", "k8s_logs", "k8s_container_name", "mongo"))));
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));
        streamsHolding();

        AlertScopeResponseDTO.TargetList out = service.listTargets("resource", "r1", "logs", null);

        assertTrue(out.isScoped());
        assertTrue(out.getItems().stream()
                .anyMatch(t -> "k8s_logs".equals(t.getName())));
        verify(oo, never()).searchDetailed(contains("FROM \"k8s_logs\""), anyLong(), anyLong(),
                anyInt(), anyInt(), anyString());
    }

    @Test
    void probesOnlyForTheServicesTheScopeResolvedTo() {
        // A product covering two services must ask for both, or a stream carrying
        // only the second one would be dropped from the picker.
        when(catalog.findAll(ORG)).thenReturn(List.of(
                resource("r1", "orders", "checkout"),
                resource("r2", "payments", "checkout")));
        when(alerts.listStreams("logs")).thenReturn(List.of("default"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));

        List<String> issued = new ArrayList<>();
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    issued.add(invocation.getArgument(0));
                    return new OpenObserveClientService.SearchOutcome(List.of(), false, null);
                });

        service.listTargets("product", "checkout", "logs", null);

        assertEquals(1, issued.size(), "one query per stream, not one per service");
        assertTrue(issued.get(0).contains("'orders'"));
        assertTrue(issued.get(0).contains("'payments'"));
    }

    @Test
    void escapesAQuotedServiceNameInTheProbeItself() {
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "o'brien-svc", null)));
        when(alerts.listStreams("logs")).thenReturn(List.of("default"));
        when(streamCatalog.fields(eq("logs"), anyString())).thenReturn(Set.of("service_name"));

        List<String> issued = new ArrayList<>();
        when(oo.searchDetailed(anyString(), anyLong(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    issued.add(invocation.getArgument(0));
                    return new OpenObserveClientService.SearchOutcome(List.of(), false, null);
                });

        service.listTargets("resource", "r1", "logs", null);

        assertTrue(issued.get(0).contains("'o''brien-svc'"), issued.get(0));
    }

    @Test
    void widensWhenOpenObserveIsOffRatherThanClaimingNothingCarriesTheService() {
        ReflectionTestUtils.setField(service, "openObserveEnabled", false);
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs"));

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "logs", null);

        assertFalse(out.isScoped());
        assertEquals(2, out.getItems().size());
        assertTrue(out.getNotice().contains("OpenObserve is not enabled"));
    }

    @Test
    void narrowsTracesTheSameWayAsLogs() {
        when(alerts.listStreams("traces")).thenReturn(List.of("default", "otel_traces"));
        when(streamCatalog.fields(eq("traces"), anyString())).thenReturn(Set.of("service_name"));
        streamsHolding("otel_traces");

        AlertScopeResponseDTO.TargetList out =
                service.listTargets("service", "orders", "traces", null);

        assertTrue(out.isScoped());
        assertEquals(List.of("otel_traces"),
                out.getItems().stream().map(AlertScopeResponseDTO.Target::getName).toList());
    }

    @Test
    void doesNotProbeAtAllWhenNothingIsSelected() {
        // The unscoped list is the right answer, not a fallback, and it must not
        // cost a sweep of queries.
        when(alerts.listStreams("logs")).thenReturn(List.of("default", "k8s_logs"));

        AlertScopeResponseDTO.TargetList out = service.listTargets(null, null, "logs", null);

        assertEquals(2, out.getItems().size());
        assertFalse(out.isScoped());
        verify(oo, never()).searchDetailed(anyString(), anyLong(), anyLong(),
                anyInt(), anyInt(), anyString());
    }

    // ── the scope list ──────────────────────────────────────────────────────

    @Test
    void listsCatalogEntriesAheadOfBareTelemetryServices() {
        // The catalog is what somebody deliberately registered; a producer-index
        // name is merely something that appeared in telemetry.
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "orders", "checkout")));

        List<AlertScopeResponseDTO.ScopeOption> items = service.listScopes().getItems();

        assertEquals("resource", items.get(0).getType());
        assertEquals("product", items.get(1).getType());
        assertEquals("service", items.get(2).getType());
        assertEquals("payments", items.get(2).getKey(),
                "the catalog already accounts for orders, so only payments is left over");
    }

    @Test
    void reportsAnUnbuiltMetricCountAsMinusOneRatherThanZero() {
        // Zero reads as "this service has no metrics", which is a claim about the
        // service. The truth is a claim about how far we have looked.
        when(catalog.findAll(ORG)).thenReturn(List.of(resource("r1", "never-seen", null)));

        AlertScopeResponseDTO.ScopeOption option = service.listScopes().getItems().stream()
                .filter(o -> "r1".equals(o.getKey())).findFirst().orElseThrow();

        assertEquals(-1, option.getMetricCount());
    }

    @Test
    void hidesDisabledCatalogResources() {
        // They are filtered out of every other screen; offering one here would
        // let somebody alert on telemetry the rest of the product suppresses.
        CatalogResource disabled = resource("r1", "orders", "checkout");
        disabled.setStatus(ResourceStatus.DISABLED);
        when(catalog.findAll(ORG)).thenReturn(List.of(disabled));

        assertTrue(service.listScopes().getItems().stream()
                .noneMatch(o -> "r1".equals(o.getKey())));
    }
}
