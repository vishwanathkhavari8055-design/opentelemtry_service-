package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * Everything about reading alerts out of an OpenObserve stream.
 *
 * <p>This replaced the inbound webhook. The webhook required OpenObserve to be
 * able to reach THIS service, which on this deployment meant a free ngrok tunnel
 * whose URL changes on every restart — so alerts stopped arriving silently and
 * regularly. Polling inverts the dependency: only outbound calls, which the
 * service already makes for every other screen.</p>
 *
 * <h2>Why the field mapping is configuration and not code</h2>
 *
 * <p>The rows in {@code alert_events} are produced by an OpenObserve
 * <em>notification template</em> that a user authored, so the field names are a
 * per-deployment fact. That is not theoretical: on this instance the template
 * emits {@code alertname}, {@code current_usage}, {@code matched_records} and
 * {@code endpoint}, none of which match the aliases the old webhook normaliser
 * knew about ({@code alert_name}, {@code value}, {@code count}). Under the old
 * code the alert NAME silently fell through to a truncated description.</p>
 *
 * <p>So each logical field lists the source keys to try, in order, and a
 * deployment whose template differs edits properties rather than Java. The
 * defaults below are the union of the old built-in aliases and the field names
 * actually observed on this instance, so it works out of the box here and stays
 * portable.</p>
 */
@ConfigurationProperties(prefix = "alerts.ingest")
public class AlertIngestProperties {

    private static final Logger log = LogManager.getLogger(AlertIngestProperties.class);

    private static final String DEFAULT_STREAM = "alert_events";
    private static final String FIELD_STREAM = "stream";
    private static final String FIELD_SERVICE_NAME = "serviceName";
    private static final String FIELD_ENTITIES = "entities";
    private static final String FIELD_SEVERITY = "severity";
    private static final String FIELD_NAMESPACE = "namespace";

    /** Stream names land in a URL path and in quoted SQL, so they are validated, not escaped. */
    private static final Pattern STREAM_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * Master switch for polling. Off means no alert ever reaches PostgreSQL —
     * the UI still works, it just has nothing new to show — so this is the knob
     * to reach for if the poller ever needs to be stopped without a redeploy.
     */
    private boolean enabled = true;

    /** The OpenObserve stream alerts are written to by an alert destination. */
    private String stream = DEFAULT_STREAM;

    /**
     * The stream's type, as OpenObserve's {@code ?type=} parameter.
     *
     * <p>Configurable rather than assumed. It is "logs" for any stream fed by the
     * JSON ingest API, which is how an alert destination writes — but the type is
     * a property of how the stream was created, not a law, and it appears in both
     * the search and the stream-metadata URL.</p>
     */
    private String streamType = "logs";

    /**
     * Fast-forward the watermark to where the stream's data actually begins, when
     * the watermark is older than that.
     *
     * <p><b>This is what makes changing {@code openobserve.host} safe.</b> The
     * watermark lives in PostgreSQL, but it describes a position in OpenObserve's
     * timeline — so pointing the service at a different OpenObserve leaves the
     * cursor holding a timestamp that means nothing on the new instance. Chunked
     * catch-up would then crawl the entire gap in {@code max-window-minutes}
     * steps: a cursor six months stale needs ~730 polls, six hours of walking
     * empty windows before the first current alert is read.</p>
     *
     * <p>So the instance is ASKED where its data starts
     * ({@code GET /api/{org}/streams/{stream}/schema} → {@code stats.doc_time_min})
     * and the cursor skips straight there. Nothing is lost by it: the rows being
     * skipped over are rows the instance does not have. The same reasoning covers
     * retention having aged out the gap, and the stream having been recreated.</p>
     *
     * <p>Only ever moves the watermark FORWARD, and only when catch-up would
     * otherwise chunk — the live tail never pays for the extra call.</p>
     */
    private boolean fastForwardToStreamStart = true;

    /**
     * Organizations to poll.
     *
     * <p>Empty means "the configured default org only". It is an explicit list
     * rather than a discovery call because polling every org OpenObserve happens
     * to have would silently multiply the query load by the tenant count, and on
     * this instance one of those orgs has 12,655 streams.</p>
     */
    private List<String> orgs = new ArrayList<>();

    /** How often to poll, milliseconds. */
    private long intervalMs = 30_000L;

    /** Delay before the first poll, so it does not compete with startup. */
    private long initialDelayMs = 20_000L;

    /**
     * How far back a brand-new cursor starts.
     *
     * <p>Deliberately short. A fresh deployment adopting this should pick up
     * what is firing NOW, not replay a week of history into an empty alert list —
     * and a long backfill on first boot is also the slowest possible first
     * OpenObserve query.</p>
     */
    private int backfillMinutes = 60;

    /**
     * Rows fetched per poll.
     *
     * <p>Also the batch size for the transactional write. Bounded because one
     * poll's rows are stored in a single transaction, and an unbounded batch after
     * a long outage would hold a write transaction open for the whole catch-up.</p>
     */
    private int batchSize = 500;

    /**
     * Extra lookback added to the watermark on every poll, in seconds.
     *
     * <p>Not paranoia: OpenObserve assigns {@code _timestamp} at ingest and its
     * search reads from partitioned storage, so a row can become visible to a
     * query slightly after a row with a later timestamp. Without a small overlap
     * the poll would step past it forever. Re-read rows are free — the
     * {@code event_hash} unique index discards them.
     */
    private int overlapSeconds = 60;

    /** How many consecutive failures before the poller logs at ERROR rather than WARN. */
    private int failuresBeforeAlarm = 3;

    /**
     * The widest range one poll may ask for, in minutes. Catch-up is chunked.
     *
     * <p><b>This is what stops the poller wedging itself.</b> The query window is
     * watermark → now, so an outage of any length eventually asks for a range
     * OpenObserve cannot scan — and it does not refuse: it answers 200 with empty
     * hits and {@code is_partial}, which the poller used to read as "no new
     * alerts". It then recorded a success, left the watermark alone, and re-issued
     * the identical unservable query forever while reporting itself healthy. That
     * is exactly what happened here: the cursor stuck at 2026-08-07T13:39Z and
     * still had not moved 66 hours later, with the stream receiving alerts the
     * whole time.</p>
     *
     * <p>Capping the span means a long gap is walked in servable pieces instead of
     * being attempted as one impossible query — a 66h backlog clears in about a
     * dozen polls. 6h is chosen against measurement, not taste: on 2026-08-10 this
     * instance served a 48h scan of {@code alert_events} and failed a 72h one, so
     * 6h sits an order of magnitude inside the limit and leaves room for the
     * stream to grow busier.</p>
     *
     * <p>0 disables chunking and restores the old unbounded window.</p>
     */
    private int maxWindowMinutes = 360;

    /**
     * How far behind the watermark may fall before the poller gives up on the gap
     * and jumps to the present, in hours. 0 disables the bound.
     *
     * <p><b>Chunked catch-up is correct but it is not fast.</b> It advances at most
     * one chunk per poll, and a busy stream puts more rows in front of the cursor
     * than a poll can read. Measured on 2026-08-13: the cursor sat 49.6h back with
     * 3,551,442 rows after it, which at {@code batch-size} 500 every 30s is about 59
     * HOURS of walking before the Alerts screen shows anything current. The screen
     * exists to answer "what is wrong right now", so being correct about the day
     * before yesterday is not a useful thing for it to be.</p>
     *
     * <p>Past this bound the poller stops trying to be complete and starts being
     * current: the watermark moves to {@code now - backfill-minutes} and reading
     * resumes there. That SKIPS whatever fired in the gap, which is why it is logged
     * at WARN naming the range — it is a deliberate trade, not a repair. Raise it, or
     * set it to 0, on a deployment that would rather wait than miss.</p>
     *
     * <p><b>Defaults to 0 — OFF — here, and to 24 in application.properties.</b> The
     * difference is deliberate. Skipping alerts is never the safe default for a
     * library, so code that constructs this class directly gets the complete-at-any-
     * cost behaviour and the existing catch-up contract is untouched (see
     * {@code AlertIngestServiceImplWindowTest.arealBacklogIsStillReadRatherThanSkipped},
     * which asserts a readable 66h backlog is walked rather than jumped). The
     * deployment opts in, because on THIS instance the gap is not walkable in useful
     * time.</p>
     *
     * <p>24h is the shipped value because it sits well clear of any recoverable
     * outage: a poller down for an afternoon still walks its gap normally, and only
     * one wedged or stopped for a full day takes the jump.</p>
     */
    private int maxBacklogHours = 0;

    /**
     * Consecutive partial reads of the SAME catch-up chunk before it is skipped.
     *
     * <p>Chunking alone is not enough, because a range can be permanently
     * unreadable rather than merely too wide — OpenObserve's file list still
     * references parquet files that are gone, so the scan fails identically on
     * every attempt. Retrying such a chunk forever is the original bug with extra
     * steps: no alert after it would ever be read. Verified here — the 6h chunk
     * containing 2026-08-07T13:39Z is partial on every attempt while the nine
     * chunks after it are served cleanly.</p>
     *
     * <p>So a chunk is retried this many times and then stepped over, at ERROR
     * with the range named, because skipping it may drop alerts that genuinely
     * fired in it. That trade is deliberate: the alternative is dropping every
     * alert from then on. Only ever applies while catching up — the live tail
     * retries indefinitely, since skipping there would discard alerts that are
     * about to become readable.</p>
     */
    private int partialRetriesBeforeSkip = 3;

    /** Cap on the raw payload copy kept per alert, in characters. */
    private int maxPayloadChars = 8_000;

    /** Source label written to {@code alerts.source} for rows this poller stores. */
    private String sourceLabel = FIELD_STREAM;

    /**
     * Which fields identify one alert, i.e. what the fingerprint is built from.
     *
     * <p><b>This is the last source of duplicate rows.</b> The fingerprint used to
     * include {@code streamType} and {@code streamName}, and the notification
     * template emits {@code stream} INCONSISTENTLY — verified on this deployment:
     * {@code k8s-pod-cpu-disaster} for the same pod exists twice, once with
     * {@code streamName=null} and once with
     * {@code streamName=k8s_pod_cpu_limit_utilization}. Two hashes, two identities,
     * two rows for one problem, and collapsing by fingerprint cannot merge them
     * because they genuinely are different fingerprints.</p>
     *
     * <p>Worse than the duplicate: it breaks EPISODE CONTINUITY. An alert whose
     * template starts emitting {@code stream} does not get a new episode when it
     * fires again — it gets a whole new identity, so its history splits in two and
     * the row reads as a first-time alert.</p>
     *
     * <p>So identity is now the rule, not the payload's optional decorations:
     * {@code org} plus {@code alertName}. An OpenObserve alert name identifies the
     * rule, and the rule is what fires; the stream a rule reads is derived from it,
     * not independent of it. Add {@code streamName} back only on a deployment whose
     * template emits it on every single firing.</p>
     *
     * <p>Recognised tokens: {@code org}, {@code alertName}, {@code streamType},
     * {@code streamName}, {@code severity}, {@code serviceName}, {@code namespace},
     * {@code entities}. Unknown tokens are refused at startup rather than silently
     * ignored, because a typo here would quietly re-identify every alert.</p>
     *
     * <p>ONE-TIME EFFECT WHEN CHANGED: existing rows keep their old fingerprints, so
     * the next firing of each alert opens a fresh episode 1 under the new identity
     * and the old episode is closed by the staleness sweep. Expected, and it settles
     * after one cycle.</p>
     */
    private List<String> identityFields =
            mutableList("org", FIELD_SERVICE_NAME, "incidentType");

    /** Tokens {@link #identityFields} accepts. */
    private static final Set<String> IDENTITY_TOKENS = Set.of(
            "org", "alertname", "incidenttype", "streamtype", "streamname",
            FIELD_SEVERITY, "servicename", FIELD_NAMESPACE, FIELD_ENTITIES);

    private static List<String> mutableList(String... values) {
        return new ArrayList<>(List.of(values));
    }

    private Mapping mapping = new Mapping();

    @PostConstruct
    void validate() {
        validateStream();
        if (intervalMs <= 0) {
            throw new IllegalStateException("alerts.ingest.interval-ms must be > 0");
        }
        validateIdentityFields();
        log.info("Alert identity (fingerprint) is built from: {}", identityFields);
        if (!enabled) {
            log.warn("alerts.ingest.enabled=false — alerts will NOT be ingested from the "
                    + "'{}' stream. The Alerts screen will show only what is already stored.", stream);
        } else {
            log.info("Alert ingest: polling stream '{}' every {} ms (batch {}, overlap {}s)",
                    stream, intervalMs, batchSize, overlapSeconds);
        }
    }

    /** Refuses an empty or malformed stream name; warns when it is not the default. */
    private void validateStream() {
        if (stream == null || stream.isBlank()) {
            throw new IllegalStateException("alerts.ingest.stream must not be empty. Expected '" + DEFAULT_STREAM + "'.");
        }
        String trimmedStream = stream.trim();
        if (!STREAM_RE.matcher(trimmedStream).matches()) {
            throw new IllegalStateException("alerts.ingest.stream='" + stream
                    + "' is not a valid stream name (expected " + STREAM_RE.pattern() + ")");
        }
        if (!DEFAULT_STREAM.equalsIgnoreCase(trimmedStream)) {
            log.warn("alerts.ingest.stream is configured as '{}'. The required source of truth is '" + DEFAULT_STREAM + "'.", trimmedStream);
        }
    }

    private void validateIdentityFields() {
        // Refused, not ignored: a typo here would silently re-identify every alert,
        // splitting each one's history at the moment of the typo.
        for (String field : identityFields) {
            String token = field == null ? "" : field.trim().toLowerCase();
            if (!IDENTITY_TOKENS.contains(token)) {
                throw new IllegalStateException("alerts.ingest.identity-fields contains '" + field
                        + "', which is not a recognised field. Expected one or more of "
                        + IDENTITY_TOKENS);
            }
        }
    }

    /**
     * Source keys for each logical field, most specific first.
     *
     * <p>Every list is mutable and non-empty by default. Spring's relaxed binding
     * may populate a collection property by mutating whatever the getter returns
     * rather than calling the setter, so an immutable {@code List.of(…)} default
     * would make any deployment that overrides one of these fail at startup with
     * an UnsupportedOperationException.</p>
     */
    public static class Mapping {

        /** Fields whose defaults include names observed on this deployment. */
        private Map<String, List<String>> fields = defaultFields();

        private static Map<String, List<String>> defaultFields() {
            Map<String, List<String>> defaults = new LinkedHashMap<>();
            // `alertname` first: it is what this instance's template emits, and
            // the old normaliser did not know it.
            defaults.put("alertName", mutable("alertname", "alert_name", "alertName", "alert", "name", "title"));
            defaults.put("org", mutable("org", "org_name", "orgName", "organization"));
            defaults.put(FIELD_SEVERITY, mutable(FIELD_SEVERITY, "level", "priority", "urgency"));
            defaults.put("alertType", mutable("alert_type", "alertType", "type"));
            defaults.put("streamName", mutable(FIELD_STREAM, "stream_name", "streamName"));
            defaults.put("streamType", mutable("stream_type", "streamType"));
            defaults.put("description", mutable("description", "detail", "details"));
            defaults.put("summary", mutable("summary", "title"));
            // `endpoint` is this template's affected-entity list.
            defaults.put(FIELD_ENTITIES, mutable("endpoint", FIELD_ENTITIES, "affected", "instances"));
            defaults.put(FIELD_SERVICE_NAME, mutable("service_name", FIELD_SERVICE_NAME, "service", "k8s_deployment_name"));
            defaults.put("hostName", mutable("host_name", "hostName", "host", "node", "instance", "k8s_node_name"));
            defaults.put(FIELD_NAMESPACE, mutable(FIELD_NAMESPACE, "k8s_namespace_name", "k8s_namespace"));
            defaults.put("environment", mutable("environment", "env", "cluster", "k8s_cluster"));
            // `current_usage` is this template's observed value.
            defaults.put("aggValue", mutable("current_usage", "alert_agg_value", "agg_value", "value"));
            // `matched_records` is this template's row count.
            defaults.put("observedCount", mutable("matched_records", "alert_count", "observed_count", "count"));
            defaults.put("threshold", mutable("alert_threshold", "threshold", "limit"));
            defaults.put("operator", mutable("alert_operator", "operator"));
            defaults.put("period", mutable("alert_period", "period"));
            defaults.put("windowStart", mutable("alert_start_time", "start_time", "startsAt", "startTime"));
            defaults.put("windowEnd", mutable("alert_end_time", "end_time", "endsAt", "endTime"));
            defaults.put("alertUrl", mutable("alert_url", "url", "generatorURL", "link"));
            defaults.put("traceId", mutable("trace_id", "traceId", "trace"));
            return defaults;
        }

        private static List<String> mutable(String... keys) {
            return new ArrayList<>(List.of(keys));
        }

        public Map<String, List<String>> getFields() {
            return fields;
        }

        public void setFields(Map<String, List<String>> fields) {
            if (fields == null || fields.isEmpty()) {
                this.fields = defaultFields();
                return;
            }
            // MERGE rather than replace. Overriding one field's keys in properties
            // must not silently delete the mapping for the other twenty — that
            // would turn a one-line tweak into a fully unmapped alert.
            Map<String, List<String>> merged = defaultFields();
            merged.putAll(fields);
            this.fields = merged;
        }

        /** Source keys to try for a logical field, in order. Never null. */
        public List<String> keysFor(String logicalField) {
            List<String> keys = fields.get(logicalField);
            return keys == null ? List.of() : keys;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = (stream == null || stream.isBlank()) ? DEFAULT_STREAM : stream.trim();
    }

    public String getStreamType() {
        return streamType;
    }

    public void setStreamType(String streamType) {
        this.streamType = (streamType == null || streamType.isBlank()) ? "logs" : streamType.trim();
    }

    public boolean isFastForwardToStreamStart() {
        return fastForwardToStreamStart;
    }

    public void setFastForwardToStreamStart(boolean fastForwardToStreamStart) {
        this.fastForwardToStreamStart = fastForwardToStreamStart;
    }

    public List<String> getOrgs() {
        return orgs;
    }

    public void setOrgs(List<String> orgs) {
        this.orgs = orgs == null ? new ArrayList<>() : new ArrayList<>(orgs);
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getBackfillMinutes() {
        return backfillMinutes;
    }

    public void setBackfillMinutes(int backfillMinutes) {
        this.backfillMinutes = Math.max(1, backfillMinutes);
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public int getOverlapSeconds() {
        return overlapSeconds;
    }

    public void setOverlapSeconds(int overlapSeconds) {
        this.overlapSeconds = Math.max(0, overlapSeconds);
    }

    public int getFailuresBeforeAlarm() {
        return failuresBeforeAlarm;
    }

    public void setFailuresBeforeAlarm(int failuresBeforeAlarm) {
        this.failuresBeforeAlarm = Math.max(1, failuresBeforeAlarm);
    }

    public int getMaxWindowMinutes() {
        return maxWindowMinutes;
    }

    public void setMaxWindowMinutes(int maxWindowMinutes) {
        this.maxWindowMinutes = Math.max(0, maxWindowMinutes);
    }

    public int getPartialRetriesBeforeSkip() {
        return partialRetriesBeforeSkip;
    }

    public void setPartialRetriesBeforeSkip(int partialRetriesBeforeSkip) {
        this.partialRetriesBeforeSkip = Math.max(1, partialRetriesBeforeSkip);
    }

    /** The chunk span in microseconds, or 0 when chunking is disabled. */
    public long maxWindowMicros() {
        return maxWindowMinutes * 60L * 1_000_000L;
    }

    public int getMaxBacklogHours() {
        return maxBacklogHours;
    }

    public void setMaxBacklogHours(int maxBacklogHours) {
        this.maxBacklogHours = Math.max(0, maxBacklogHours);
    }

    /** The backlog bound in microseconds, or 0 when the bound is disabled. */
    public long maxBacklogMicros() {
        return maxBacklogHours * 3_600L * 1_000_000L;
    }

    public int getMaxPayloadChars() {
        return maxPayloadChars;
    }

    public void setMaxPayloadChars(int maxPayloadChars) {
        this.maxPayloadChars = Math.max(0, maxPayloadChars);
    }

    public List<String> getIdentityFields() {
        return identityFields;
    }

    public void setIdentityFields(List<String> identityFields) {
        if (identityFields == null || identityFields.isEmpty()) {
            this.identityFields = mutableList("org", FIELD_SERVICE_NAME, "incidentType");
            return;
        }
        this.identityFields = new ArrayList<>(identityFields);
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = (sourceLabel == null || sourceLabel.isBlank()) ? FIELD_STREAM : sourceLabel.trim();
    }

    public Mapping getMapping() {
        return mapping;
    }

    public void setMapping(Mapping mapping) {
        this.mapping = mapping == null ? new Mapping() : mapping;
    }
}
