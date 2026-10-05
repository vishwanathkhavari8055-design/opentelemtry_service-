package com.trinity.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How alert rules are kept in step with OpenObserve.
 *
 * <h2>Create is atomic, update is not — and that asymmetry is deliberate</h2>
 *
 * <p>{@link #isFailCreateOnSyncError()} defaults to true: if the push to
 * OpenObserve fails, the PostgreSQL insert is rolled back and the caller gets an
 * error. Nothing half-made survives, so there is never a rule that exists here and
 * is not evaluating anywhere.</p>
 *
 * <p>{@link #isFailUpdateOnSyncError()} defaults to FALSE, which is not an
 * oversight. On create there is nothing to lose — the operator still has the form
 * in front of them and can press save again. On update there is: the edit is
 * already in the system, and rolling it back to punish a transient OpenObserve
 * outage discards work that was successfully stored. So an update keeps the change,
 * marks the rule out of sync, and the retry sweep pushes it when OpenObserve
 * returns. The rule list shows that state explicitly rather than implying the rule
 * is live.</p>
 */
@ConfigurationProperties(prefix = "alerts.rule-sync")
public class AlertRuleSyncProperties {

    /** Reject a rule creation whose OpenObserve push fails, rolling back the insert. */
    private boolean failCreateOnSyncError = true;

    /** Reject a rule update whose OpenObserve push fails. Off — see the class note. */
    private boolean failUpdateOnSyncError = false;

    /**
     * Ensure a history destination exists and attach it to every rule pushed.
     *
     * <p>This is what makes a rule created here actually appear in the Alerts
     * screen. Turning it off means the operator is responsible for attaching a
     * destination that writes to the ingest stream — and a rule without one fires
     * invisibly, so the failure is silent.</p>
     */
    private boolean autoAttachHistoryDestination = true;

    private static final String DEFAULT_HISTORY_NAME = "trinity_alert_history";

    /** Name of the destination that routes firings into the ingest stream. */
    private String historyDestinationName = DEFAULT_HISTORY_NAME;

    /** Name of the notification template that destination uses. */
    private String historyTemplateName = DEFAULT_HISTORY_NAME;

    /**
     * Explicit URL for the history destination.
     *
     * <p>Empty means "derive it from {@code openobserve.host} and the ingest
     * stream", which is right whenever OpenObserve can reach itself on the same
     * address this service uses. Set it for a split-horizon deployment where the
     * in-cluster address differs from the one configured here.</p>
     */
    private String historyDestinationUrl = "";

    /** Whether the destination skips TLS verification. Off — it should not need to. */
    private boolean historyDestinationSkipTlsVerify = false;

    /** How often the retry sweep pushes rules left out of sync, milliseconds. */
    private long retryIntervalMs = 300_000L;

    /** Delay before the first retry sweep. */
    private long retryInitialDelayMs = 60_000L;

    /** Rules pushed per retry sweep, so a large backlog cannot monopolise a poll. */
    private int retryBatchSize = 50;

    /**
     * How often staged rules are checked for a start time that has arrived.
     *
     * <p>Shorter than the retry interval on purpose. A retry landing late costs
     * nothing that was not already broken; a rule scheduled for 09:00 that
     * starts at 09:05 is five minutes of coverage somebody explicitly asked for
     * and did not get. One indexed query per minute is a cheap way not to owe
     * them that.</p>
     */
    private long activationIntervalMs = 60_000L;

    /** Delay before the first activation sweep, so startup is not contended. */
    private long activationInitialDelayMs = 30_000L;

    public boolean isFailCreateOnSyncError() {
        return failCreateOnSyncError;
    }

    public void setFailCreateOnSyncError(boolean failCreateOnSyncError) {
        this.failCreateOnSyncError = failCreateOnSyncError;
    }

    public boolean isFailUpdateOnSyncError() {
        return failUpdateOnSyncError;
    }

    public void setFailUpdateOnSyncError(boolean failUpdateOnSyncError) {
        this.failUpdateOnSyncError = failUpdateOnSyncError;
    }

    public boolean isAutoAttachHistoryDestination() {
        return autoAttachHistoryDestination;
    }

    public void setAutoAttachHistoryDestination(boolean autoAttachHistoryDestination) {
        this.autoAttachHistoryDestination = autoAttachHistoryDestination;
    }

    public String getHistoryDestinationName() {
        return historyDestinationName;
    }

    public void setHistoryDestinationName(String historyDestinationName) {
        this.historyDestinationName = blankTo(historyDestinationName, DEFAULT_HISTORY_NAME);
    }

    public String getHistoryTemplateName() {
        return historyTemplateName;
    }

    public void setHistoryTemplateName(String historyTemplateName) {
        this.historyTemplateName = blankTo(historyTemplateName, DEFAULT_HISTORY_NAME);
    }

    public String getHistoryDestinationUrl() {
        return historyDestinationUrl;
    }

    public void setHistoryDestinationUrl(String historyDestinationUrl) {
        this.historyDestinationUrl = historyDestinationUrl == null ? "" : historyDestinationUrl.trim();
    }

    public boolean isHistoryDestinationSkipTlsVerify() {
        return historyDestinationSkipTlsVerify;
    }

    public void setHistoryDestinationSkipTlsVerify(boolean historyDestinationSkipTlsVerify) {
        this.historyDestinationSkipTlsVerify = historyDestinationSkipTlsVerify;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public long getRetryInitialDelayMs() {
        return retryInitialDelayMs;
    }

    public void setRetryInitialDelayMs(long retryInitialDelayMs) {
        this.retryInitialDelayMs = retryInitialDelayMs;
    }

    public long getActivationIntervalMs() {
        return activationIntervalMs;
    }

    public void setActivationIntervalMs(long activationIntervalMs) {
        this.activationIntervalMs = activationIntervalMs;
    }

    public long getActivationInitialDelayMs() {
        return activationInitialDelayMs;
    }

    public void setActivationInitialDelayMs(long activationInitialDelayMs) {
        this.activationInitialDelayMs = activationInitialDelayMs;
    }

    public int getRetryBatchSize() {
        return retryBatchSize;
    }

    public void setRetryBatchSize(int retryBatchSize) {
        this.retryBatchSize = Math.max(1, retryBatchSize);
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
