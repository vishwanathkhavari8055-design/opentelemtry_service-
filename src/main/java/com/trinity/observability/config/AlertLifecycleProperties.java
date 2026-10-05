package com.trinity.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How an alert episode behaves once it exists.
 *
 * <p>Replaces the settings that used to live under {@code alerts.webhook.*}. The
 * webhook is gone, and leaving its prefix on properties that govern the alert
 * LIFECYCLE — staleness, payload retention — would have made the configuration
 * describe an ingest mechanism the service no longer has.</p>
 */
@ConfigurationProperties(prefix = "alerts.lifecycle")
public class AlertLifecycleProperties {

    /**
     * How long after its last firing an alert is treated as over.
     *
     * <p>OpenObserve does not emit a "recovered" event — it evaluates on a
     * schedule and notifies when the condition matches, so silence on the next
     * evaluation is the only signal that it cleared. Resolution is therefore
     * inferred from staleness, and the effective window is
     * {@code max(this, 3 × the alert's own period)}: a rule evaluated hourly must
     * not be declared over fifteen minutes after it fires.</p>
     */
    private int staleAfterMinutes = 15;

    /** Cap on the raw payload copy kept per alert, in characters. */
    private int maxPayloadChars = 8_000;

    /** How often stale alerts are swept, milliseconds. Must be > 0. */
    private long sweeperIntervalMs = 60_000L;

    /** Delay before the first sweep, so it does not compete with startup. */
    private long sweeperInitialDelayMs = 30_000L;

    /** Alerts closed per sweep, so a large backlog cannot hold a transaction open. */
    private int sweeperBatchSize = 500;

    /**
     * Whether the sweeper may auto-resolve at all.
     *
     * <p>A deployment whose rules all notify on recovery as well as on breach
     * would want this off, since inference would then be strictly worse than the
     * signal it already has.</p>
     */
    private boolean autoResolveEnabled = true;

    /**
     * Refuse to acknowledge an alert without a named person.
     *
     * <p>Acknowledging means "I own this now", and the audit trail existed to record
     * WHO. It was recording {@code unknown} — the UI sent that literal string as a
     * fallback whenever it could not find a stored session, so the one field people
     * consult when apportioning responsibility was reliably useless.</p>
     *
     * <p>Fixing the caller alone is not enough: the next caller inherits the same
     * trap. So a blank, missing, or literally-{@code unknown} actor is now a 400 on
     * acknowledge, and the UI asks for a name instead of inventing one. Resolve,
     * close and reopen are unaffected — resolution is also performed by the
     * staleness sweep, which has no person to name and legitimately records itself.</p>
     */
    private boolean requireActorOnAcknowledge = true;

    public int getStaleAfterMinutes() {
        return staleAfterMinutes;
    }

    public void setStaleAfterMinutes(int staleAfterMinutes) {
        this.staleAfterMinutes = Math.max(1, staleAfterMinutes);
    }

    public int getMaxPayloadChars() {
        return maxPayloadChars;
    }

    public void setMaxPayloadChars(int maxPayloadChars) {
        this.maxPayloadChars = Math.max(0, maxPayloadChars);
    }

    public long getSweeperIntervalMs() {
        return sweeperIntervalMs;
    }

    public void setSweeperIntervalMs(long sweeperIntervalMs) {
        this.sweeperIntervalMs = Math.max(1L, sweeperIntervalMs);
    }

    public long getSweeperInitialDelayMs() {
        return sweeperInitialDelayMs;
    }

    public void setSweeperInitialDelayMs(long sweeperInitialDelayMs) {
        this.sweeperInitialDelayMs = Math.max(0L, sweeperInitialDelayMs);
    }

    public int getSweeperBatchSize() {
        return sweeperBatchSize;
    }

    public void setSweeperBatchSize(int sweeperBatchSize) {
        this.sweeperBatchSize = Math.max(1, sweeperBatchSize);
    }

    public boolean isAutoResolveEnabled() {
        return autoResolveEnabled;
    }

    public void setAutoResolveEnabled(boolean autoResolveEnabled) {
        this.autoResolveEnabled = autoResolveEnabled;
    }

    public boolean isRequireActorOnAcknowledge() {
        return requireActorOnAcknowledge;
    }

    public void setRequireActorOnAcknowledge(boolean requireActorOnAcknowledge) {
        this.requireActorOnAcknowledge = requireActorOnAcknowledge;
    }
}
