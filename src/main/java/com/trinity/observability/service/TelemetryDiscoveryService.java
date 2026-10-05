package com.trinity.observability.service;

import java.util.List;
import java.util.Map;

import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;

/**
 * Asks OpenObserve whether a resource exists, and where.
 *
 * <p>The Product Catalog's only source of truth. Registration is refused unless
 * this service can point at real telemetry, which is what stops the catalog from
 * becoming a wish list of resources nobody is actually monitoring.</p>
 */
public interface TelemetryDiscoveryService {

    /**
     * The outcome of running the probe chain over one name.
     *
     * @param found        true when at least one probe matched
     * @param resolvedName the spelling telemetry uses, or null when nothing matched
     * @param bindings     one entry per matched (signal, stream, column)
     * @param signals      matched signals, in probe order: {@code logs}, {@code traces}, {@code metrics}
     * @param lastSeenMicros newest timestamp across all matches, or null
     * @param windowStartMicros lower bound of the searched window
     * @param windowEndMicros   upper bound of the searched window
     */
    record Discovery(boolean found, String resolvedName, List<TelemetryBinding> bindings,
            List<String> signals, Long lastSeenMicros,
            long windowStartMicros, long windowEndMicros) {
    }

    /**
     * Find every signal this resource emits, resolving each to ONE identifier.
     *
     * <p>A window's probes are issued together rather than one after another, and
     * narrower windows are retried for whichever signals are still missing. Which
     * probe wins for a signal is decided by configured order, not by which query
     * returned first, so the answer does not depend on network timing.</p>
     *
     * @param category used only to skip probes that declare themselves
     *                 inapplicable; it never narrows the result
     */
    Discovery discover(ResourceCategory category, String resourceName);

    /**
     * {@link #discover}, but allowed to answer from a recent successful lookup.
     *
     * <p>Exists because registration validates twice. The operator clicks Validate,
     * the server runs the probe chain, and then Register runs the identical chain
     * again a second later — so the flow paid the full cost twice, which is what
     * made registering feel slow. Register calls this; Validate populates it.</p>
     *
     * <p>Only successful lookups are reusable. A miss must always be re-checked,
     * because the whole reason someone retries after "not found" is that they have
     * just started the agent, and serving them a cached refusal would make the
     * catalog look broken.</p>
     */
    default Discovery discoverCached(ResourceCategory category, String resourceName) {
        return discover(category, resourceName);
    }

    /**
     * Newest telemetry timestamp for a set of already-registered resources,
     * answered in ONE query per distinct (stream, column) pair.
     *
     * <p>Separate from {@link #discover} because the two have opposite shapes.
     * Discovery is one name against many identifiers; a Last Seen refresh is many
     * names against one identifier each — and the catalog list endpoint is polled,
     * so doing it per resource would mean a query per row per poll.</p>
     *
     * @param bindingsByResourceId the identifiers to check, per resource id
     * @return resource id → newest timestamp in epoch micros, omitting resources
     *         with no recent telemetry; never null
     */
    Map<String, Long> lastSeen(Map<String, List<TelemetryBinding>> bindingsByResourceId);

    /** Whether discovery can run at all — false when {@code openobserve.enabled=false}. */
    boolean isSupported();

    /** ISO-8601 description of the window {@link #discover} searches, for messages. */
    String windowExpression();
}
