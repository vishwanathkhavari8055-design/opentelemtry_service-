package com.trinity.observability.service;

/**
 * Makes sure every rule this application manages routes its firings into the
 * stream the poller reads.
 *
 * <h2>What this replaced, and why it is better</h2>
 *
 * <p>The old design registered an OpenObserve destination pointing at THIS
 * service's {@code /api/alerts/webhook}. That required OpenObserve to be able to
 * reach us, which on this deployment meant a free ngrok tunnel — a URL that
 * changes every restart, so alerts stopped being recorded silently and often. It
 * also meant a shared secret to manage and an unauthenticated-by-default inbound
 * endpoint to get wrong.</p>
 *
 * <p>The destination created here points at <b>OpenObserve's own ingest API</b>
 * ({@code /api/{org}/{stream}/_json}) instead. OpenObserve calls itself; the
 * alert lands in a stream; this service polls that stream over the same outbound
 * path it already uses for every other screen. No inbound reachability, no
 * tunnel, no webhook secret, and nothing to re-register when this service moves.</p>
 *
 * <h2>Why the application provisions it rather than the operator</h2>
 *
 * <p>A rule with no history destination fires into the void: it will page
 * whatever else it is wired to, but it never appears in this UI. That failure is
 * invisible — the rule looks healthy and synchronised — so leaving the wiring as a
 * manual step somebody must remember for every new rule is a guarantee that some
 * rule will be missing from the alert list and nobody will know which.</p>
 */
public interface AlertHistoryDestinationService {

    /** What {@link #ensureProvisioned(String)} did. */
    record Provisioned(String templateName, String destinationName, String url,
            boolean templateCreated, boolean destinationCreated, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Ensure the template and destination exist in an organization.
     *
     * <p>Idempotent — safe to call before every rule push, which is exactly how it
     * is used. Never throws: failing to provision the destination must not fail the
     * operator's rule save, because the rule itself is still valid and useful.
     * The outcome is in {@link Provisioned#error()}.</p>
     */
    Provisioned ensureProvisioned(String org);

    /**
     * The destination name to attach to a rule, or null when provisioning failed.
     *
     * <p>Separate from {@link #ensureProvisioned} so the rule sync can ask the
     * cheap question ("what should I attach?") without expressing an opinion about
     * whose job it is to create it.</p>
     */
    String destinationName();

    /** Whether automatic provisioning and attaching is switched on. */
    boolean isEnabled();
}
