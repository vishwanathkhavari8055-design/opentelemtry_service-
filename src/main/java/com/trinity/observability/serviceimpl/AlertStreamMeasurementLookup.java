package com.trinity.observability.serviceimpl;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.AlertDisplayProperties;
import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.util.AlertMeasurementReader;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.RefreshingCache;
import com.trinity.observability.util.SqlSafety;

/**
 * Reads the Limit column straight out of the {@code alert_events} stream.
 *
 * <h2>Why the stream and not the stored copy</h2>
 *
 * <p>OpenObserve is the source of truth for what an alert measured. The
 * {@code raw_payload} column is a snapshot taken at ingest, and it is capped by
 * {@code alerts.ingest.max-payload-chars} — so a template that grows past that cap
 * loses fields from the copy while the stream still has them. Reading the stream
 * means the column shows what OpenObserve currently says, and a template change
 * takes effect without re-ingesting anything.</p>
 *
 * <h2>One query per page, not one per row</h2>
 *
 * <p>A page holds up to 250 alerts and a per-row lookup would be 250 round trips at
 * roughly 750 ms each. Instead one query fetches recent rows once, indexes them by
 * (alert name, endpoint) keeping the newest per key, and every row on the page is
 * then a hash lookup. Measured on this instance: 2,000 stream rows over 6 hours
 * collapse to 61 distinct keys, which covers the page many times over.</p>
 *
 * <h2>It always falls back, and never fails the request</h2>
 *
 * <p>Three things make the stream the wrong answer sometimes: an alert older than
 * the lookback, retention having dropped its rows, and OpenObserve serving the
 * window only partially — the empty-plus-{@code is_partial} answer that this
 * codebase has been bitten by twice. In all three the stored payload is used
 * instead, per row, so a partial stream read degrades to the previous behaviour
 * rather than blanking a column. The Alerts table must not fail to render because a
 * measurement lookup did.</p>
 */
@Component
public class AlertStreamMeasurementLookup {

    private static final Logger log = LogManager.getLogger(AlertStreamMeasurementLookup.class);

    /** Cap on the cached index, so a long-running instance cannot grow it without bound. */
    private static final int MAX_CACHED_KEYS = 20_000;

    private final OpenObserveClientService openObserve;
    private final AlertDisplayProperties display;
    private final AlertIngestProperties ingest;
    private final OpenObserveConfig config;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * org -> last index built.
     *
     * <p>Stale-while-revalidate rather than a plain TTL. With a plain TTL, one table
     * poll in every {@code stream-cache-seconds} paid for the rebuild on the request
     * thread — measured at 1.6 s to ~19 s — and did so inside the caller's read-only
     * transaction, holding a database connection the whole time. Now an expired
     * index is served as-is and rebuilt in the background; only the very first read
     * for an org waits.</p>
     */
    private final RefreshingCache<Map<String, Measurement>> cache;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public AlertStreamMeasurementLookup(OpenObserveClientService openObserve,
            AlertDisplayProperties display,
            AlertIngestProperties ingest,
            OpenObserveConfig config) {
        this.openObserve = openObserve;
        this.display = display;
        this.ingest = ingest;
        this.config = config;
        this.cache = new RefreshingCache<>("alert measurement",
                Duration.ofSeconds(Math.max(1, display.getStreamCacheSeconds())), Duration.ofSeconds(30));
    }

    /** What one stream row contributes to the table. */
    public record Measurement(String usedLabel, String limitLabel, String displayValue) {
    }

    /**
     * Builds the default org's index once the application is up, off the startup
     * thread. Without it the first Alerts screen after a restart paid the cold build
     * (1.6 s to ~19 s measured) on its request thread. A request that arrives while
     * this is still running waits on the same load rather than starting its own, and
     * a failure only leaves the cache cold — exactly the state it started in.
     */
    @EventListener(ApplicationReadyEvent.class)
    void prewarm() {
        if (!display.isLimitFromStream() || !openObserveEnabled) {
            return;
        }
        final String org = config.getOrg();
        Thread warm = new Thread(() -> {
            try {
                OrgContext.set(org);
                long t0 = System.currentTimeMillis();
                int keys = forOrg(org).size();
                log.info("Alert measurement lookup: prewarmed org '{}' ({} key(s)) in {} ms",
                        org, keys, System.currentTimeMillis() - t0);
            } catch (RuntimeException e) {
                log.debug("Alert measurement prewarm failed; the first request will build it: {}",
                        e.toString());
            } finally {
                OrgContext.clear();
            }
        }, "alert-measurement-prewarm");
        warm.setDaemon(true);
        warm.start();
    }

    /**
     * The measurements for one org, keyed by {@link #key}.
     *
     * <p>Cached for a few seconds because the table polls: the UI refreshes every 30
     * seconds and asks for rows and summary counts together, and rebuilding the
     * index per request would issue an OpenObserve query for data that cannot have
     * changed meaningfully. Empty map when the lookup is off or unavailable, which
     * the caller reads as "use the stored payload".</p>
     */
    public Map<String, Measurement> forOrg(String org) {
        if (!display.isLimitFromStream() || !openObserveEnabled) {
            return Map.of();
        }
        return cache.get(RefreshingCache.orgKey(org), () -> build(org));
    }

    /**
     * The lookup key: alert name plus the entity the alert is about.
     *
     * <p>Both halves are needed. One rule fires for many pods, so the alert name
     * alone would map every pod's row to whichever limit happened to be newest — and
     * these pods genuinely differ, 2048 MB against 5120 MB on the same rule. The
     * entity alone is not enough either, since one pod can trip a memory rule and a
     * CPU rule at once.</p>
     *
     * <p>Lower-cased because it is a join key across two systems that do not agree
     * on case, and a near-miss here shows up as a silently empty column.</p>
     */
    public static String key(String alertName, String entity) {
        return (alertName == null ? "" : alertName.trim().toLowerCase(Locale.ROOT))
                + "\0"
                + (entity == null ? "" : entity.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, Measurement> build(String org) {
        String stream;
        try {
            stream = SqlSafety.validateIdentifier(ingest.getStream());
        } catch (Exception e) {
            log.debug("Alert measurement lookup skipped — invalid stream name: {}", e.getMessage());
            return Map.of();
        }

        long end = System.currentTimeMillis() * 1000L;
        long start = end - display.getStreamLookbackMinutes() * 60L * 1_000_000L;

        // SELECT * rather than a projection of the configured keys. A column named in
        // SELECT that does not exist on the stream is a DataFusion error, i.e. the
        // whole lookup fails — and the whole point of limit-fields being configurable
        // is that a deployment can name keys this one has never seen.
        String sql = "SELECT * FROM \"" + stream + "\" "
                + "WHERE " + com.trinity.observability.util.AlertStreamRowMapper.TIMESTAMP_COLUMN
                + " >= " + start + " ORDER BY "
                + com.trinity.observability.util.AlertStreamRowMapper.TIMESTAMP_COLUMN + " DESC";

        OpenObserveClientService.SearchOutcome outcome;
        try {
            outcome = openObserve.searchDetailed(sql, start, end, 0,
                    display.getStreamMaxRows(), ingest.getStreamType());
        } catch (Exception e) {
            log.debug("Alert measurement lookup failed for org '{}' — the Limit column will fall "
                    + "back to each alert's stored payload: {}", org, e.getMessage());
            return Map.of();
        }

        // Partial is NOT treated as "no measurements": the rows it did return are
        // real, and every row it did not is covered by the payload fallback. Unlike
        // the ingest poller, nothing here persists a conclusion drawn from emptiness.
        if (outcome.partial()) {
            log.debug("Alert measurement lookup for org '{}' returned partial data; using what "
                    + "came back and falling back to stored payloads for the rest", org);
        }

        Map<String, Measurement> byKey = index(outcome.hits());

        log.debug("Alert measurement lookup (org={}, stream={}): {} row(s) -> {} key(s)",
                org, stream, outcome.hits().size(), byKey.size());
        return byKey;
    }

    /** The rows' measurements keyed by {@link #key}, capped at {@link #MAX_CACHED_KEYS}. */
    private Map<String, Measurement> index(List<Map<String, Object>> hits) {
        Map<String, Measurement> byKey = new HashMap<>();
        for (Map<String, Object> row : hits) {
            if (byKey.size() >= MAX_CACHED_KEYS) {
                break;
            }
            addFirstSighting(byKey, row);
        }
        return byKey;
    }

    /** Records one row's measurement unless its key has already been seen. */
    private void addFirstSighting(Map<String, Measurement> byKey, Map<String, Object> row) {
        String alertName = text(row, "alertname", "alert_name", "alertName", "alert", "name");
        String entity = text(row, "endpoint", "entities", "affected", "instances");
        // Rows arrive newest-first, so the first sighting of a key is the current
        // one — putIfAbsent rather than put, or an older limit would overwrite it.
        String k = alertName == null ? null : key(alertName, entity);
        if (k != null && !byKey.containsKey(k)) {
            Measurement measurement = measure(row);
            if (measurement != null) {
                byKey.put(k, measurement);
            }
        }
    }

    /** What one row says about usage, limit and display value; null when it says nothing. */
    private Measurement measure(Map<String, Object> row) {
        var payload = json.valueToTree(row);
        String usedLabel = AlertMeasurementReader.usedLabel(payload, display.getUsedFields());
        String limitLabel = AlertMeasurementReader.limitLabel(payload, display.getLimitFields());
        String displayValue = AlertMeasurementReader.displayValue(payload,
                display.getDisplayFields());
        if (usedLabel == null && limitLabel == null && displayValue == null) {
            return null;
        }
        return new Measurement(usedLabel, limitLabel, displayValue);
    }

    /** First of the candidate keys that holds non-blank text. */
    private static String text(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Test seam and operational escape hatch — drops the cached index. */
    public void invalidate() {
        cache.clear();
    }

    /** Which org's index to build; mirrors the rest of the query service. */
    String defaultOrg() {
        return config.getOrg();
    }

    /** Candidate entity spellings for a stored alert, most specific first. */
    public static List<String> entityCandidates(String entities, String serviceName) {
        return List.of(entities == null ? "" : entities, serviceName == null ? "" : serviceName);
    }
}
