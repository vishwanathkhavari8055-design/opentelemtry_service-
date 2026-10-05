package com.trinity.observability.service;

import com.trinity.observability.dto.alerts.AlertScopeResponseDTO;

/**
 * Narrows the alert editor's choices to one service, tool or product.
 *
 * <h2>What goes wrong without it</h2>
 *
 * <p>The condition builder's stream select is fed by
 * {@link AlertsService#listStreams(String)}, which answers with everything the
 * tenant has. For logs and traces that is a short list. For metrics it is every
 * metric name in the instance — around three thousand here — and an operator who
 * has just chosen a service still has to find, unaided, which of those three
 * thousand that service actually writes.</p>
 *
 * <p>The failure that produces is silent, which is what makes it worth code.
 * A rule written against a metric the chosen service does not emit is valid in
 * every checkable sense: it validates, it stores, it syncs, it shows SYNCED in
 * the rules table. It simply never matches a row, so it never fires — and
 * nobody discovers that until the incident it was supposed to catch.</p>
 *
 * <h2>Reads, never writes</h2>
 *
 * <p>Everything here is derived from indexes other services already maintain —
 * {@link MetricProducerService} for which service emits which metric, the
 * Product Catalog registry for which resources exist and what telemetry they
 * were proven against. Nothing is stored and nothing is cached again on top,
 * so a service deployed after this answer was built appears as soon as those
 * indexes see it.</p>
 */
public interface AlertScopeService {

    /**
     * Everything the operator can scope an alert to.
     *
     * <p>Three kinds in one list — services seen in telemetry, Product Catalog
     * products, and individual registered resources — because from the editor's
     * point of view they are one question ("what is this alert about?") and
     * three separate dropdowns would make the user answer it three times.</p>
     *
     * <p>Never throws for a missing index. A producer index that is still
     * building comes back as {@code building=true} with whatever it has, which
     * the UI shows with a caveat rather than as an empty picker.</p>
     */
    AlertScopeResponseDTO.ScopeList listScopes();

    /**
     * What a rule scoped to this can be pointed at.
     *
     * <p>For {@code metrics}, the metric names that scope actually writes — each
     * one is its own stream in OpenObserve, so this IS the stream list.</p>
     *
     * <p>For {@code logs} and {@code traces} the streams are shared, so each
     * candidate is asked directly whether it holds a row for the scope over a
     * lookback window, and only those that answer are offered. Asked rather than
     * inferred, because nothing about a log stream's name says what is in it: a
     * tenant here has streams written by the OTel SDK, by the Kubernetes log
     * scraper and by a RUM exporter, carrying different identity columns, and
     * which one holds a given service is a fact about what is deployed. The
     * per-stream identity column comes back on each target, and the predicate
     * that confines a query to the scope comes back as {@code filter}.</p>
     *
     * <p><b>Falls back to the unscoped list rather than returning nothing.</b>
     * If the scope cannot be resolved — an index still building, a resource with
     * no proven bindings, OpenObserve unreachable — the answer is the full list
     * with {@code scoped=false} and a notice saying why. An empty picker is
     * indistinguishable from a broken one, and it blocks the operator entirely;
     * a wide picker with a caveat at least lets them finish.</p>
     *
     * @param scopeType  service | product | resource; null or blank means unscoped
     * @param scopeKey   the option's {@code key}; ignored when unscoped
     * @param streamType logs | metrics | traces
     * @param include    {@code all} (default), {@code emitted} or {@code observed} —
     *                   metrics only; see {@link AlertScopeResponseDTO.Target#getKind()}
     */
    AlertScopeResponseDTO.TargetList listTargets(String scopeType, String scopeKey,
                                                 String streamType, String include);

    /**
     * Columns on one stream, with the scope predicate alongside.
     *
     * <p>The column set itself does not depend on the scope — a stream has the
     * schema it has. The predicate is returned with it so the builder can seed
     * the first condition from one call instead of two, which is what makes
     * "pick a service, get a condition that is already correctly scoped"
     * possible rather than a thing the UI has to remember to do.</p>
     */
    AlertScopeResponseDTO.FieldList listFields(String scopeType, String scopeKey,
                                               String streamType, String streamName);
}
