package com.trinity.observability.serviceimpl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.org.OrgSummaryResponseDTO;
import com.trinity.observability.service.OrgService;
import com.trinity.observability.util.RefreshingCache;
import com.trinity.observability.util.RequestScopedFanout;

/**
 * Builds the Home screen's org summary from OpenObserve's management endpoints.
 *
 * <p>Five calls, run concurrently:</p>
 * <pre>
 *   GET /api/{org}/summary      → stream count + totals, and the
 *                                 alerts / pipelines trigger_status (health bars)
 *   GET /api/{org}/functions    → {"list":[...]}
 *   GET /api/{org}/dashboards   → {"dashboards":[...]}
 *   GET /api/{org}/pipelines    → {"list":[...]}
 *   GET /api/v2/{org}/alerts    → {"list":[...]}
 * </pre>
 *
 * <p>The stream figures used to be summed from {@code GET /api/{org}/streams},
 * a 19 MB / ~10 s response on this instance, which set the latency of the whole
 * endpoint and left the Home screen blank for 20+ seconds. They now come from
 * the {@code /summary} call that was already being made for trigger health.
 * {@link #fillStreams} is kept as the fallback for an OpenObserve whose summary
 * reports no stream block — see {@link #fillOrgTotals}.</p>
 *
 * <p>Alerts are the odd one out: {@code /api/{org}/alerts} answers 404 on this
 * build and the endpoint moved to the {@code /api/v2} prefix. The v1 path is
 * tried as a fallback so an older OpenObserve still reports a count instead of
 * a permanent null.</p>
 *
 * <p>Each call is isolated — a failure logs and leaves that group's fields
 * null. The contract distinguishes null ("not computed") from 0 ("counted, and
 * it's zero"), so nothing here coalesces a failure to zero.</p>
 */
@Service
public class OrgServiceImpl implements OrgService {

    private static final Logger log = LogManager.getLogger(OrgServiceImpl.class);

    /**
     * OpenObserve reports stream stat sizes in MEGABYTES as floats — a stream
     * with 2,252 documents comes back as {@code "storage_size": 1.179}. The
     * wire contract for this endpoint is raw bytes, so every size is scaled by
     * 1 MiB on the way out. Verified against the live instance: 108,920 MB of
     * storage across 638 streams renders as ~106 GB, which matches what
     * OpenObserve's own UI shows for the same org.
     */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** Cap on how many alert / pipeline names are echoed back in `items`. The
     *  cards show a short list, not an inventory. */
    private static final int ITEMS_CAP = 10;

    private final OpenObserveClientService ooClient;
    private final OpenObserveConfig config;
    private final RequestScopedFanout fanout;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    /**
     * How long a built summary is served from memory, per org. The slowest of the
     * five reads (~1 s measured) set the Home screen's load time on every visit,
     * for figures that are OpenObserve's own delayed aggregates and move over
     * minutes. A stale entry is served at once and rebuilt behind the request.
     * 0 turns the cache off — the default for a service built outside Spring.
     */
    @Value("${org.summary.cache-seconds:30}")
    private int cacheSeconds;

    private static final String API_PREFIX = "/api/";
    private static final String REAL_TIME = "real_time";

    private final AtomicReference<RefreshingCache<OrgSummaryResponseDTO>> summaryCache = new AtomicReference<>();

    public OrgServiceImpl(OpenObserveClientService ooClient, OpenObserveConfig config,
            RequestScopedFanout fanout) {
        this.ooClient = ooClient;
        this.config = config;
        this.fanout = fanout;
    }

    @Override
    public OrgSummaryResponseDTO getOrgSummary() {
        OrgSummaryResponseDTO out = new OrgSummaryResponseDTO();
        out.setOrg(config.getOrg());

        if (!openObserveEnabled) {
            // Parity with getLogsSummary / listServices: the ES path reports
            // unsupported rather than failing, so the UI can say why.
            out.setSupported(false);
            out.setBackend("elasticsearch");
            log.info("getOrgSummary() returning supported=false on ES path");
            return out;
        }

        if (cacheSeconds <= 0) {
            return buildSummary(out);
        }
        try {
            return cache().get(RefreshingCache.orgKey(out.getOrg()), () -> {
                OrgSummaryResponseDTO built = buildSummary(new OrgSummaryResponseDTO());
                built.setOrg(out.getOrg());
                // Every read failed: an outage, not a summary. Thrown so it is
                // returned to this caller but never cached.
                if (nothingComputed(built)) {
                    throw new SummaryUnavailable(built);
                }
                return built;
            });
        } catch (SummaryUnavailable e) {
            return e.summary;
        }
    }

    /** True when not one of the five groups produced a figure. */
    private static boolean nothingComputed(OrgSummaryResponseDTO s) {
        return s.getStreams().getCount() == null
                && s.getFunctions().getCount() == null
                && s.getDashboards().getCount() == null
                && s.getAlerts().getScheduled() == null && s.getAlerts().getRealTime() == null
                && s.getPipelines().getScheduled() == null && s.getPipelines().getRealTime() == null;
    }

    /** Carries an all-failed summary past the cache without caching it. */
    private static final class SummaryUnavailable extends RuntimeException {
        private final transient OrgSummaryResponseDTO summary;
        SummaryUnavailable(OrgSummaryResponseDTO summary) {
            super(null, null, false, false);
            this.summary = summary;
        }
    }

    private RefreshingCache<OrgSummaryResponseDTO> cache() {
        return summaryCache.updateAndGet(existing -> {
            if (existing != null) {
                return existing;
            }
            return new RefreshingCache<>("org summary",
                    Duration.ofSeconds(cacheSeconds), Duration.ofSeconds(15));
        });
    }

    /** The five reads, filled into {@code out}. */
    private OrgSummaryResponseDTO buildSummary(OrgSummaryResponseDTO out) {
        out.setSupported(true);
        out.setBackend("openobserve");

        String org = config.getOrg();

        // Five independent reads against OpenObserve — none consumes another's
        // output, and each writes a DIFFERENT field of `out` (the org totals,
        // functions, dashboards, alerts, pipelines), so there is no shared
        // collection to corrupt and no ordering to preserve. In series this
        // endpoint paid the sum of all five; now it pays the slowest. The
        // fan-out carries the request's org onto each pool thread — these reads
        // build their URLs from `org`, which is read from a ThreadLocal.
        fanout.runAll(List.of(
                () -> fillOrgTotals(out, org),
                () -> out.setFunctions(new OrgSummaryResponseDTO.Count(
                        guard("functions", () -> countList(ooClient.getJson(API_PREFIX + org + "/functions"), "list")))),
                () -> out.setDashboards(new OrgSummaryResponseDTO.Count(
                        guard("dashboards", () -> countList(ooClient.getJson(API_PREFIX + org + "/dashboards"), "dashboards")))),
                () -> fillAlerts(out, org),
                () -> fillPipelines(out, org)));

        // Degraded path, and the ONLY remaining caller of the per-stream walk:
        // an OpenObserve whose /summary carries no stream block would otherwise
        // leave all five headline figures null. Deliberately sequential and
        // after the fan-out — it is the expensive read this method exists to
        // avoid, so it runs only once the cheap one is known to have failed.
        if (out.getStreams().getCount() == null) {
            log.info("getOrgSummary(): /summary carried no stream totals — "
                    + "falling back to summing /streams, which is slow on a large org");
            fillStreams(out, org);
        }

        log.info("getOrgSummary(): streams={} events={} functions={} dashboards={}",
                out.getStreams().getCount(), out.getStreams().getEvents(),
                out.getFunctions().getCount(), out.getDashboards().getCount());
        return out;
    }

    /**
     * One count plus four sums over a single /streams response. The endpoint
     * returns every stream type (logs, traces, metrics, metadata) and the Home
     * screen's figure is the org-wide total, so no type filter is applied.
     *
     * <p>FALLBACK ONLY — see {@link #fillOrgTotals}, which answers the same five
     * figures from a 432-byte response. This walks the full stream list, which
     * is ~19 MB and ~10 seconds on an org with 19,735 streams, so it runs only
     * when the cheap read came back without them.</p>
     */
    private void fillStreams(OrgSummaryResponseDTO out, String org) {
        OrgSummaryResponseDTO.Streams s = out.getStreams();
        try {
            JsonNode list = ooClient.getJson(API_PREFIX + org + "/streams").path("list");
            if (!list.isArray()) {
                log.warn("getOrgSummary(): /streams returned no 'list' array; leaving stream figures null");
                return;
            }

            int count = 0;
            long docs = 0L;
            double storageMb = 0d;
            double compressedMb = 0d;
            double indexMb = 0d;

            for (JsonNode stream : list) {
                count++;
                JsonNode stats = stream.path("stats");
                docs         += stats.path("doc_num").asLong(0L);
                storageMb    += stats.path("storage_size").asDouble(0d);
                compressedMb += stats.path("compressed_size").asDouble(0d);
                indexMb      += stats.path("index_size").asDouble(0d);
            }

            s.setCount(count);
            s.setEvents(docs);
            s.setIngestedBytes(Math.round(storageMb * BYTES_PER_MB));
            s.setCompressedBytes(Math.round(compressedMb * BYTES_PER_MB));
            s.setIndexBytes(Math.round(indexMb * BYTES_PER_MB));
        } catch (Exception e) {
            log.warn("getOrgSummary(): stream stats unavailable — {}", e.toString());
        }
    }

    /**
     * Alerts, from the v2 path with a v1 fallback. Split by whether each alert
     * fires in real time or on a schedule.
     */
    private void fillAlerts(OrgSummaryResponseDTO out, String org) {
        JsonNode list = null;
        try {
            list = ooClient.getJson("/api/v2/" + org + "/alerts").path("list");
        } catch (Exception v2Error) {
            log.debug("getOrgSummary(): /api/v2/{}/alerts unavailable ({}), trying v1", org, v2Error.toString());
            try {
                list = ooClient.getJson(API_PREFIX + org + "/alerts").path("list");
            } catch (Exception v1Error) {
                log.warn("getOrgSummary(): alerts unavailable on both v2 and v1 — {}", v1Error.toString());
                return;
            }
        }
        applySplit(out.getAlerts(), list, OrgServiceImpl::isRealTimeAlert);
    }

    private void fillPipelines(OrgSummaryResponseDTO out, String org) {
        try {
            JsonNode list = ooClient.getJson(API_PREFIX + org + "/pipelines").path("list");
            applySplit(out.getPipelines(), list, OrgServiceImpl::isRealTimePipeline);
        } catch (Exception e) {
            log.warn("getOrgSummary(): pipelines unavailable — {}", e.toString());
        }
    }

    /**
     * The five Streams figures and the two trigger-health blocks, from
     * OpenObserve's own pre-aggregated org summary.
     *
     * <pre>
     *   {"streams":{"num_streams":19730,"total_records":495430420,
     *               "total_storage_size":488729.94,      // MB, as everywhere here
     *               "total_compressed_size":8298.44,
     *               "total_index_size":2887.01},
     *    "alerts":{...,"trigger_status":{"healthy":0,"failed":0,"warning":0}},
     *    "pipelines":{...,"trigger_status":{...}}}
     * </pre>
     *
     * <p><b>Why this replaced summing /streams.</b> The stream figures used to
     * be a count and four sums over {@code GET /api/{org}/streams}, which on
     * this instance is a <b>19 MB, ~10 second</b> response — 19,735 streams,
     * each with its full schema — and it alone set the latency of the whole
     * endpoint, because the fan-out pays its slowest task. The Home screen
     * showed nothing until it landed, so the page took 20+ seconds cold. This
     * endpoint answers the same five questions in ~1 s and 432 bytes, and it
     * was already being fetched here for {@code trigger_status} — the totals
     * next to it were simply being thrown away.</p>
     *
     * <p>The figures shift slightly against the old per-stream sum (OpenObserve
     * aggregates these itself, on its own cadence). That is the right direction:
     * these are now literally the numbers OpenObserve's own Home shows for the
     * org, which is the screen this one mirrors.</p>
     *
     * <p>Writes only fields no other fan-out task touches — the Streams block,
     * and the {@code health} of each Split, never the counts fillAlerts /
     * fillPipelines own — so it stays safe to run alongside them.</p>
     */
    private void fillOrgTotals(OrgSummaryResponseDTO out, String org) {
        try {
            JsonNode summary = ooClient.getJson(API_PREFIX + org + "/summary");

            JsonNode streams = summary.path("streams");
            if (streams.path("num_streams").isNumber()) {
                OrgSummaryResponseDTO.Streams s = out.getStreams();
                s.setCount(streams.path("num_streams").asInt());
                s.setEvents(longOrNull(streams, "total_records"));
                s.setIngestedBytes(megabytesToBytes(streams, "total_storage_size"));
                s.setCompressedBytes(megabytesToBytes(streams, "total_compressed_size"));
                s.setIndexBytes(megabytesToBytes(streams, "total_index_size"));
            } else {
                // Left null, not zero — the caller checks for exactly this and
                // falls back to the per-stream walk.
                log.warn("getOrgSummary(): /summary carried no stream totals");
            }

            out.getAlerts().setHealth(toHealth(summary.path("alerts").path("trigger_status")));
            out.getPipelines().setHealth(toHealth(summary.path("pipelines").path("trigger_status")));
        } catch (Exception e) {
            log.warn("getOrgSummary(): org totals unavailable — {}", e.toString());
        }
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asLong() : null;
    }

    /** A megabyte figure from OpenObserve as raw bytes; null when it isn't there. */
    private static Long megabytesToBytes(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? Math.round(v.asDouble() * BYTES_PER_MB) : null;
    }

    /** Null when OpenObserve sent no trigger_status object at all. */
    private static OrgSummaryResponseDTO.Health toHealth(JsonNode status) {
        if (status == null || !status.isObject()) return null;
        OrgSummaryResponseDTO.Health h = new OrgSummaryResponseDTO.Health();
        h.setHealthy(intOrNull(status, "healthy"));
        h.setFailed(intOrNull(status, "failed"));
        h.setWarning(intOrNull(status, "warning"));
        return h;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asInt() : null;
    }

    /** Counts a list into scheduled / real-time buckets and echoes the first
     *  few entries for the card's listing. */
    private void applySplit(OrgSummaryResponseDTO.Split target, JsonNode list,
            java.util.function.Predicate<JsonNode> isRealTime) {
        if (list == null || !list.isArray()) {
            log.warn("getOrgSummary(): expected a 'list' array; leaving this card's counts null");
            return;
        }

        int realTime = 0;
        int scheduled = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (JsonNode entry : list) {
            if (isRealTime.test(entry)) realTime++; else scheduled++;
            addListingItem(items, entry);
        }

        target.setRealTime(realTime);
        target.setScheduled(scheduled);
        target.setItems(items);
    }

    /** Echoes {@code entry} into the card's listing while it holds fewer than {@link #ITEMS_CAP}. */
    private static void addListingItem(List<Map<String, Object>> items, JsonNode entry) {
        if (items.size() < ITEMS_CAP) {
            Map<String, Object> item = listingItem(entry);
            if (!item.isEmpty()) items.add(item);
        }
    }

    /** The name / streamName / type an entry carries, for the card's listing. */
    private static Map<String, Object> listingItem(JsonNode entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        String name = firstText(entry, "name", "title", "id");
        if (name != null) item.put("name", name);
        String streamName = firstText(entry, "stream_name", "streamName");
        if (streamName != null) item.put("streamName", streamName);
        String type = firstText(entry, "stream_type", "type");
        if (type != null) item.put("type", type);
        return item;
    }

    /**
     * OpenObserve has spelled this flag several ways across versions
     * ({@code is_real_time} in v1 alerts, a {@code type} discriminator in v2),
     * and this org currently has no alerts to observe the live shape from — so
     * every spelling seen in their API docs is accepted, and anything
     * unrecognised counts as scheduled, which is the commoner kind.
     */
    private static boolean isRealTimeAlert(JsonNode alert) {
        if (alert.hasNonNull("is_real_time")) return alert.path("is_real_time").asBoolean(false);
        if (alert.hasNonNull("isRealTime"))   return alert.path("isRealTime").asBoolean(false);
        if (alert.hasNonNull(REAL_TIME))      return alert.path(REAL_TIME).asBoolean(false);
        String type = alert.path("type").asText("");
        return "realtime".equalsIgnoreCase(type) || REAL_TIME.equalsIgnoreCase(type);
    }

    /** Pipelines carry the discriminator on their source node. */
    private static boolean isRealTimePipeline(JsonNode pipeline) {
        String sourceType = pipeline.path("source").path("source_type").asText("");
        if (sourceType.isEmpty()) sourceType = pipeline.path("source_type").asText("");
        if (sourceType.isEmpty()) sourceType = pipeline.path("type").asText("");
        return "realtime".equalsIgnoreCase(sourceType) || REAL_TIME.equalsIgnoreCase(sourceType);
    }

    /** Length of the array at {@code field}, or null if it isn't an array. */
    private static Integer countList(JsonNode root, String field) {
        JsonNode list = root.path(field);
        return list.isArray() ? list.size() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** Runs a sub-fetch, returning null (never zero) if it fails. */
    @SuppressWarnings("java:S4276") // Specialized IntSupplier cannot be used because return value is nullable Integer
    private static Integer guard(String what, Supplier<Integer> call) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("getOrgSummary(): {} unavailable — {}", what, e.toString());
            return null;
        }
    }
}
