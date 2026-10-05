package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the Alerts table shows in the columns that the alert payload — not the
 * database schema — decides.
 *
 * <h2>Why the limit is read from the payload and not from a column</h2>
 *
 * <p>The table needs a <em>Limit</em> beside <em>Current Usage</em>: 87.4% means
 * little without the 2048 MB it is 87.4% of. The obvious home for that is
 * {@code alerts.threshold}, and it is empty for every alert on this deployment —
 * the notification template renders the ceiling as {@code limit_mb} for a memory
 * alert and {@code total_gb} for a disk one, and neither name is a threshold the
 * ingest mapper recognises.</p>
 *
 * <p>Rather than add columns, the value is read on demand from the
 * {@code raw_payload} that every alert already stores. That choice has one
 * property nothing else does: it works for alerts ALREADY IN THE DATABASE. A new
 * column would be null for all three thousand rows recorded before it existed,
 * and a resolved alert never fires again, so no amount of waiting would fill it
 * in. No migration, no re-ingest, and the column is populated for history too.</p>
 *
 * <h2>Units are declared, never guessed</h2>
 *
 * <p>Each entry is {@code key} or {@code key:unit}. The unit is appended verbatim,
 * so {@code limit_mb:MB} renders "2048 MB". Inferring "MB" from the {@code _mb}
 * suffix would be neat and would also invent a unit for any field that happens to
 * end that way — and a wrong unit on a number somebody pastes into a ticket is
 * worse than no unit at all. A bare key renders the number alone.</p>
 *
 * <p>Order is priority: the first key present in the payload wins, so a template
 * carrying both an explicit threshold and a computed ceiling gets the specific one.
 * Extending this to a new alert shape is a line of configuration.</p>
 */
@ConfigurationProperties(prefix = "alerts.display")
public class AlertDisplayProperties {

    /**
     * Payload keys that carry the ceiling the observed value is measured against,
     * most specific first.
     *
     * <p>Mutable and non-empty by default: Spring's relaxed binding may populate a
     * collection by mutating whatever the getter returns instead of calling the
     * setter, so an immutable {@code List.of(…)} default would make any deployment
     * that overrides this fail at startup.</p>
     */
    private List<String> limitFields = new ArrayList<>(List.of(
            "limit_mb:MB",
            "total_gb:GB",
            "limit_gb:GB",
            "limit_bytes:B",
            "alert_threshold",
            "threshold",
            "limit"));

    /**
     * Payload keys carrying how much is actually IN USE, most specific first, as
     * {@code key} or {@code key:unit}.
     *
     * <p>The companion to {@link #getLimitFields()}, and the reason Current Usage is
     * worth a column. {@code current_usage} is a bare percentage — "78.9" — which
     * says how close to the edge something is but not what it is measuring or by how
     * much. The template carries the absolute figure right next to the ceiling
     * ({@code used_mb: 4040} beside {@code limit_mb: 5120}, {@code used_gb: 74}
     * beside {@code total_gb: 91}), so the row can read "4040 MB of 5120 MB" instead
     * of "78.9".</p>
     *
     * <p>Same declared-unit rule as the limit: {@code used_mb:MB} renders "4040 MB",
     * and units are never inferred from a suffix. And deliberately NOT converted —
     * 5120 MB is shown as the template wrote it rather than rescaled to "5 GB",
     * because {@code display_value} says MB and a table that disagrees with the
     * alert's own text is worse than one that repeats it.</p>
     */
    private List<String> usedFields = new ArrayList<>(List.of(
            "used_mb:MB",
            "used_gb:GB",
            "used_bytes:B",
            "current_bytes:B",
            "used"));

    /**
     * Keys holding the template's own rendered summary of the measurement, e.g.
     * {@code "87.4% (1790.0MB / 2048.0MB)"}.
     *
     * <p>Not a column of its own — it is the tooltip behind Current Usage and
     * Limit. It is the only field that is present on every alert shape here,
     * including the ones with no numeric ceiling at all ({@code "state: Pending"},
     * {@code "20884 restarts / 5m"}), so it is what stops those rows being a bare
     * dash with no explanation.</p>
     */
    private List<String> displayFields = new ArrayList<>(List.of(
            "display_value", "display", "value_display"));

    /**
     * Payload keys naming the RULE that produced a firing, most specific first.
     *
     * <p>Closes the last hole in "every rule and alert is in the database". The rules
     * are all stored now, but an alert can only be attached to one if something says
     * which rule fired it — and on this deployment nothing does. The template writes
     * a friendly display name ({@code alertname: "K8s Pod High Memory Usage"}) while
     * the rule is called {@code k8s-pod-memory-critical}, so matching on the alert's
     * own name links the alerts whose template happens to use the rule name and
     * misses the rest. Verified: {@code alert_events} carries no rule field at all.</p>
     *
     * <p>The fix is one line in the OpenObserve notification template —
     * {@code "rule_name": "{alert_name}"}, where {@code alert_name} is OpenObserve's
     * own variable for the rule — and these keys are read the moment it appears. No
     * code change, no migration, and it applies to alerts ALREADY STORED, because the
     * lookup happens on read against each alert's saved payload.</p>
     */
    private List<String> ruleNameFields = new ArrayList<>(List.of(
            "rule_name", "rule", "alert_rule_name", "alert_rule", "ruleName"));

    /**
     * One row per fingerprint — the newest episode — instead of one row per episode.
     *
     * <p>An alert that fires, is resolved by the staleness sweep, and fires again
     * produces a row per cycle. Correct as history, wrong as a worklist: one pod's
     * memory alert showed three times in a 24h window, which reads as three
     * problems. Collapsed by default; the full episode history for one alert is
     * still on its details panel, and this can be turned off per request.</p>
     */
    private boolean collapseEpisodes = true;

    /**
     * Read Limit from the {@code alert_events} stream rather than the stored payload.
     *
     * <p>OpenObserve is the source of truth for what an alert measured. The
     * {@code raw_payload} column is a snapshot taken at ingest and capped by
     * {@code alerts.ingest.max-payload-chars}, so a template that outgrows that cap
     * loses fields from the copy while the stream still has them; reading the stream
     * also means a template change takes effect without re-ingesting.</p>
     *
     * <p>The payload remains the per-row fallback either way — for alerts older than
     * the lookback, for rows retention has dropped, and for a window OpenObserve
     * serves only partially. Setting this false uses the payload for everything,
     * which needs no OpenObserve call at all.</p>
     */
    private boolean limitFromStream = true;

    /**
     * How far back the measurement lookup reads, in minutes.
     *
     * <p>360 for the same measured reason the ingest chunk is 6h: this instance
     * serves a 48h scan of {@code alert_events} and fails a 72h one, so 6h sits well
     * inside what it can answer. Alerts whose last firing predates this fall back to
     * their stored payload, which is why a short window is safe.</p>
     */
    private int streamLookbackMinutes = 360;

    /**
     * Rows fetched per lookup.
     *
     * <p>Bounded because it is one query serving a whole page. Measured here: 2,000
     * rows over 6 hours collapse to 61 distinct (alert, entity) keys, so this covers
     * a 250-row page many times over.</p>
     */
    private int streamMaxRows = 2_000;

    /**
     * How long an indexed read is reused, in seconds.
     *
     * <p>The table polls — every 30 seconds by default, and it asks for rows and
     * summary counts together. Without a short cache each of those rebuilds the index
     * from OpenObserve for data that cannot have changed meaningfully in between.</p>
     */
    private int streamCacheSeconds = 15;

    public List<String> getLimitFields() {
        return limitFields;
    }

    public void setLimitFields(List<String> limitFields) {
        this.limitFields = limitFields == null ? new ArrayList<>() : new ArrayList<>(limitFields);
    }

    public List<String> getRuleNameFields() {
        return ruleNameFields;
    }

    public void setRuleNameFields(List<String> ruleNameFields) {
        this.ruleNameFields = ruleNameFields == null
                ? new ArrayList<>() : new ArrayList<>(ruleNameFields);
    }

    public List<String> getUsedFields() {
        return usedFields;
    }

    public void setUsedFields(List<String> usedFields) {
        this.usedFields = usedFields == null ? new ArrayList<>() : new ArrayList<>(usedFields);
    }

    public List<String> getDisplayFields() {
        return displayFields;
    }

    public void setDisplayFields(List<String> displayFields) {
        this.displayFields = displayFields == null ? new ArrayList<>() : new ArrayList<>(displayFields);
    }

    public boolean isCollapseEpisodes() {
        return collapseEpisodes;
    }

    public void setCollapseEpisodes(boolean collapseEpisodes) {
        this.collapseEpisodes = collapseEpisodes;
    }

    public boolean isLimitFromStream() {
        return limitFromStream;
    }

    public void setLimitFromStream(boolean limitFromStream) {
        this.limitFromStream = limitFromStream;
    }

    public int getStreamLookbackMinutes() {
        return streamLookbackMinutes;
    }

    public void setStreamLookbackMinutes(int streamLookbackMinutes) {
        this.streamLookbackMinutes = Math.max(1, streamLookbackMinutes);
    }

    public int getStreamMaxRows() {
        return streamMaxRows;
    }

    public void setStreamMaxRows(int streamMaxRows) {
        this.streamMaxRows = Math.max(1, streamMaxRows);
    }

    public int getStreamCacheSeconds() {
        return streamCacheSeconds;
    }

    public void setStreamCacheSeconds(int streamCacheSeconds) {
        this.streamCacheSeconds = Math.max(0, streamCacheSeconds);
    }
}
