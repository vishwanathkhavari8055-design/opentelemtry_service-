package com.trinity.observability.client;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public interface OpenObserveClientService {

    /**
     * GET an arbitrary OpenObserve path and return the parsed JSON body.
     *
     * Escape hatch for OpenObserve's management endpoints — /streams,
     * /functions, /dashboards, /pipelines, /v2/{org}/alerts — which are not
     * SQL searches and each return a differently-shaped envelope
     * ({@code list} for most, {@code dashboards} for dashboards). Wrapping each
     * in its own typed client method would be five near-identical methods whose
     * only real content is the path, so the interpretation is left to the
     * caller (see OrgServiceImpl).
     *
     * Prefer {@link #search} for anything that is a query over stream data.
     *
     * @param path absolute path on the OpenObserve host, including any query
     *             string, e.g. {@code "/api/default/streams"}
     * @return the parsed response body; never null
     * @throws com.trinity.observability.exception.OpenObserveQueryException on
     *         a non-2xx response or an unparseable body
     */
    JsonNode getJson(String path);

    /**
     * Send a body-carrying write to an arbitrary OpenObserve path and return the
     * parsed JSON response.
     *
     * <p>Companion to {@link #getJson} for the management endpoints that are
     * not read-only — {@code /api/v2/{org}/alerts} and friends. The body is
     * forwarded VERBATIM rather than being remapped through a DTO, which is the
     * whole point: an alert document is a recursive structure with untagged
     * enums (condition groups, anomaly config) and OpenObserve keeps adding
     * fields to it. Anything this service does not model would be silently
     * dropped on the next save, so an alert created in OpenObserve's own UI
     * would come back subtly different after being edited here. Passing the
     * document through keeps the round-trip lossless.</p>
     *
     * @param method one of {@code POST}, {@code PUT}, {@code PATCH} — the verb
     *               to use
     * @param path   absolute path on the OpenObserve host, including any query
     *               string, e.g. {@code "/api/v2/default/alerts"}
     * @param body   the request body; {@code null} sends no body, which is what
     *               OpenObserve's PATCH toggles (…/enable?value=false) expect
     * @return the parsed response body, or an empty object for a 2xx with no
     *         content; never null
     * @throws com.trinity.observability.exception.OpenObserveQueryException on
     *         a non-2xx response or an unparseable body
     */
    JsonNode sendJson(String method, String path, JsonNode body);

    /**
     * DELETE an arbitrary OpenObserve path.
     *
     * @param path absolute path on the OpenObserve host, including any query string
     * @return the parsed response body, or an empty object for an empty 2xx
     * @throws com.trinity.observability.exception.OpenObserveQueryException on
     *         a non-2xx response
     */
    JsonNode deleteJson(String path);

    /**
     * Append records to an OpenObserve stream via the JSON ingest endpoint
     * ({@code POST /api/{org}/{stream}/_json}).
     *
     * <p>The write counterpart to {@link #search}. This is a separate method
     * rather than another {@link #sendJson} call site for one reason that
     * matters: <b>ingest reports per-record failures inside a 200 response</b>.
     * A body of {@code {"code":200,"status":[{"successful":0,"failed":1,
     * "error":"schema conflict"}]}} is an HTTP success and a data loss, so a
     * caller using the generic write helper would log "ok" and drop the record.
     * That check lives here so no caller can forget it — the same hazard the
     * {@code function_error} handling in {@link #search} exists for.</p>
     *
     * <p>The organization is an explicit parameter instead of being read from
     * {@link com.trinity.observability.config.OpenObserveConfig#getOrg()}
     * because the callers that write are not always serving a browser request:
     * the alert webhook is called by OpenObserve itself, and the org it must
     * write to comes from the alert payload, not from a {@code ?org=} the
     * caller never sent.</p>
     *
     * @param org        organization to write into; validated by the caller
     * @param streamName target stream; created on first write if absent
     * @param records    a JSON ARRAY of flat objects, one per record. Field
     *                   types must stay stable across calls — OpenObserve
     *                   infers the stream schema from the first write and
     *                   rejects later records that conflict with it.
     * @return the parsed response body; never null
     * @throws com.trinity.observability.exception.OpenObserveQueryException on
     *         a non-2xx response, an unparseable body, or any record the
     *         endpoint reported as failed
     */
    JsonNode ingest(String org, String streamName, JsonNode records);

    /**
     * Run a SQL query against an OpenObserve stream.
     *
     * Returns hits as flat maps of column → value (the shape OpenObserve actually emits).
     * Each consumer does its own DTO mapping — there's no Class<T> for typed deserialisation
     * because every consumer needs custom mapping (severity → level fallback, status coalesce,
     * stream-name passthrough, TimeFormatter conversion).
     *
     * @param sql          DataFusion SQL — caller is responsible for SQL safety (use SqlSafety helpers)
     * @param startMicros  inclusive lower bound, microseconds since epoch
     * @param endMicros    inclusive upper bound, microseconds since epoch
     * @param from         pagination offset (0-based)
     * @param size         page size — use this, NOT SQL LIMIT/OFFSET
     * @param streamType   "logs" | "traces" | "metrics" — passed as ?type= query param
     * @return list of hit maps; never null
     * @throws com.trinity.observability.exception.OpenObserveQueryException on non-2xx response
     */
    List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                     int from, int size, String streamType);

    /**
     * The same query as {@link #search}, but reporting whether OpenObserve could
     * actually serve the whole window.
     *
     * <p><b>Why this exists.</b> OpenObserve answers a window it cannot scan with
     * HTTP 200, an empty {@code hits} array, {@code is_partial: true} and a
     * {@code function_error} of {@code {"code":20006,"message":"Search parquet
     * file not found"}}. To {@link #search} that is indistinguishable from "no
     * matching data", because both are an empty list — and a caller that treats
     * emptiness as authoritative then draws a false conclusion it cannot recover
     * from. Two such conclusions were live on this deployment:</p>
     *
     * <ul>
     *   <li>the alert poller read "no new alerts" from an unservable catch-up
     *       window, recorded a SUCCESS, left its watermark where it was, and so
     *       re-issued the identical unservable query every 30s indefinitely
     *       while reporting itself healthy;</li>
     *   <li>the Product Catalog read "nothing was ever registered" from an
     *       unservable replay window and cached that, silently emptying the
     *       catalog.</li>
     * </ul>
     *
     * <p>Verified live on 2026-08-10: this instance serves a 48h scan of
     * {@code alert_events} and fails a 72h one. The span at which it breaks is a
     * property of how many parquet files the range touches, not a fixed
     * duration, so it cannot be avoided by picking a "safe" window once — a
     * caller that must not mistake failure for emptiness has to ask.</p>
     *
     * <p>{@link #search} is deliberately left alone. Most callers serve a screen
     * where partial data beats an exception, and that is the existing, wanted
     * behaviour; only a caller that PERSISTS a conclusion drawn from emptiness
     * needs this.</p>
     *
     * @return hits plus the partiality flag; never null
     * @throws com.trinity.observability.exception.OpenObserveQueryException on a
     *         non-2xx response or an unparseable body, exactly as {@link #search}
     */
    /*
     * Defaulted rather than abstract so that adding it does not break every
     * existing implementor — the hand-written test doubles in particular, which
     * only ever cared about rows. The default is the OLD behaviour exactly: the
     * hits from {@link #search}, reported as complete. That is the honest default
     * for a double that cannot simulate partiality, and it means this addition
     * cannot change what any current caller sees. The real client overrides it;
     * anything that needs to detect partiality must too.
     */
    default SearchOutcome searchDetailed(String sql, long startMicros, long endMicros,
                                         int from, int size, String streamType) {
        return new SearchOutcome(
                search(sql, startMicros, endMicros, from, size, streamType), false, null);
    }

    /**
     * A search result and whether it can be trusted to be complete.
     *
     * @param hits    the rows returned; never null, possibly empty
     * @param partial true when OpenObserve reported {@code is_partial} or a
     *                {@code function_error} — meaning {@code hits} may be missing
     *                rows that exist, so an empty list proves nothing
     * @param error   the reported {@code function_error}, for logging; may be null
     */
    record SearchOutcome(List<Map<String, Object>> hits, boolean partial, String error) {

        /** True when the window was served in full, whether or not it matched anything. */
        public boolean isComplete() {
            return !partial;
        }
    }

    /**
     * Distinct service names from the logs stream over a fixed 24h lookback window.
     *
     * The 24h window is hard-coded in the implementation, deliberately decoupled from
     * the user's filter window so the dropdown stays populated when a service has been
     * quiet for the last 15 min. Result is capped at 200 by the impl as defense-in-depth.
     *
     * @return alphabetically sorted distinct service names; never null
     * @throws com.trinity.observability.exception.OpenObserveQueryException on non-2xx response
     */
    List<String> discoverServices();

    /**
     * Enumerate metric stream names from /api/{org}/streams?type=metrics.
     *
     * Stream metadata is global — no time bounds. Streams that received metrics weeks ago
     * are still listed; if you need to filter by recency, do so at the call site.
     *
     * @return metric stream names; never null
     */
    List<String> listMetricStreams();
}
