package com.trinity.observability.service;

import java.util.Optional;

import com.trinity.observability.dto.productcatalog.ResourceCategory;

/**
 * One way of recognising a resource in OpenObserve.
 *
 * <p>This is the extensibility point the requirement asks for. "Validation
 * should match available telemetry using identifiers such as service_name,
 * resource.service.name, database name, tool name, platform name" is not a fixed
 * list — it is a growing one, and Kafka, Redis, Kubernetes and Trino will each
 * want their own. So validation is a CHAIN of probes rather than a method with a
 * switch in it.</p>
 *
 * <h2>Two ways to add one</h2>
 *
 * <ol>
 *   <li><b>Configuration</b> — add a {@code product-catalog.validation.probes[*]}
 *       entry naming a signal, stream and column. No code, no deploy of this
 *       module. Covers every "the identifier is in a column" case, which is
 *       almost all of them.</li>
 *   <li><b>Code</b> — declare a {@code @Component} implementing this interface
 *       for anything that is not a plain column comparison (a name embedded in a
 *       label map, a resource identified by a metric's presence rather than its
 *       value). {@code TelemetryDiscoveryServiceImpl} appends every such bean
 *       after the configured ones.</li>
 * </ol>
 *
 * <h2>Contract</h2>
 *
 * <p>A probe MUST NOT throw for "this column does not exist on this stream" or
 * "this stream has never been written to" — those are ordinary misses on a
 * heterogeneous deployment, not errors, and a chain that aborts on the first
 * absent stream would fail to validate anything. Return {@link Optional#empty()}.</p>
 */
public interface TelemetryProbe {

    /**
     * Evidence that a resource exists.
     *
     * @param signal         {@code logs}, {@code traces} or {@code metrics}
     * @param stream         the OpenObserve stream the match was found in
     * @param column         the identifier column that matched
     * @param matchedValue   the value AS TELEMETRY SPELLS IT — not as the caller
     *                       typed it. This is what later filtering compares
     *                       against, and the two spellings can differ (a lookup
     *                       is case-insensitive; a filter is not)
     * @param lastSeenMicros newest matching row's timestamp, epoch micros
     * @param sampleCount    matching rows in the window, for the details view
     */
    record Hit(String signal, String stream, String column, String matchedValue,
            long lastSeenMicros, long sampleCount) {
    }

    /** {@code logs}, {@code traces} or {@code metrics}. */
    String signal();

    /** Short label for logs and the details view, e.g. {@code logs.service_name}. */
    String describe();

    /**
     * Whether this probe is worth running for a category. Default true: a
     * PostgreSQL filed under Tools rather than Databases should still validate,
     * so category is a hint, not a gate. Override only for a probe that would be
     * actively misleading outside its category.
     */
    default boolean supports(ResourceCategory category) {
        return true;
    }

    /**
     * Look for the resource.
     *
     * @param resourceName as the operator typed it; matching is expected to be
     *                     case-insensitive, with the exact spelling returned
     * @return the evidence, or empty for a miss (including any upstream failure)
     */
    Optional<Hit> probe(String resourceName, long startMicros, long endMicros);

    /**
     * The same lookup, told whether it may pay for an exhaustive scan.
     *
     * <p>Case-insensitive matching is the expensive half of a probe. On
     * OpenObserve a predicate over the bare column is served from the index
     * ({@code service_name = 'livy'} scanned 1,698 units of the large logs
     * stream over 24h), while wrapping the column in a function is not
     * ({@code LOWER(service_name) = 'livy'} scanned 383,594 for the same answer
     * — 3.7 s against 19-23 s). A probe that folds case therefore has two very
     * different costs, and only the caller knows which window it is being asked
     * about.</p>
     *
     * <p>{@code exhaustive=false} means "answer from whatever you can look up
     * cheaply, and report a miss rather than scanning the whole column".
     * {@link TelemetryDiscoveryService} passes true only for the NARROWEST
     * window it searches, which is where a full scan is affordable — the same
     * fold over an hour of that stream costs 0.3 s. See
     * {@code TelemetryDiscoveryServiceImpl.discover}.</p>
     *
     * <p>Either way this is ONE lookup, not a cheap one followed by an expensive
     * one. A probe reports upstream failure as a miss, so extra round trips make
     * a saturated backend answer wrong rather than slowly.</p>
     *
     * <p>Defaulted to the exhaustive lookup, so a probe contributed in code
     * keeps working unchanged and simply pays the old cost.</p>
     */
    default Optional<Hit> probe(String resourceName, long startMicros, long endMicros,
            boolean exhaustive) {
        return probe(resourceName, startMicros, endMicros);
    }
}
