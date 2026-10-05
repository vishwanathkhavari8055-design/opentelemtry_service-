package com.trinity.observability.clientimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.exception.OpenObserveQueryException;

@Service
public class OpenObserveClientImpl implements OpenObserveClientService {

    private static final Logger log = LogManager.getLogger(OpenObserveClientImpl.class);

    private static final long DISCOVERY_LOOKBACK_MICROS = 24L * 60L * 60L * 1_000_000L;
    private static final int DISCOVERY_SIZE_CAP = 200;
    private static final String API_PREFIX = "/api/";

    private static final TypeReference<List<Map<String, Object>>> HITS_TYPE = new TypeReference<>() {};

    private final RestClient client;
    private final OpenObserveConfig config;
    private final ObjectMapper json = new ObjectMapper();

    public OpenObserveClientImpl(RestClient openObserveRestClient, OpenObserveConfig config) {
        this.client = openObserveRestClient;
        this.config = config;
    }

    @Override
    public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
            int from, int size, String streamType) {
        // Partiality is dropped here on purpose — see searchDetailed's javadoc in
        // OpenObserveClientService. Screen-serving callers want the rows they got.
        return searchDetailed(sql, startMicros, endMicros, from, size, streamType).hits();
    }

    @Override
    public SearchOutcome searchDetailed(String sql, long startMicros, long endMicros,
            int from, int size, String streamType) {
        String body = buildSearchBody(sql, startMicros, endMicros, from, size);
        String path = API_PREFIX + config.getOrg() + "/_search?type=" + streamType;

        log.info("OpenObserve search: type={} from={} size={} sql={}", streamType, from, size, sql);

        long t0 = System.nanoTime();
        String response = client.post()
                .uri(java.net.URI.create(config.getHost() + path))
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String errBody = new String(res.getBody().readAllBytes());
                    throw mapError(res.getStatusCode().value(), errBody);
                })
                .body(String.class);

        SearchOutcome outcome = parseSearch(response);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("OpenObserve search ({}): {} hit(s) in {} ms (partial={})",
                streamType, outcome.hits().size(), elapsedMs, outcome.partial());
        return outcome;
    }

    @Override
    public List<String> discoverServices() {
        log.debug("OpenObserve discoverServices: lookbackMicros={}", DISCOVERY_LOOKBACK_MICROS);

        Instant now = Instant.now();
        long endMicros = now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
        long startMicros = endMicros - DISCOVERY_LOOKBACK_MICROS;

        String sql = "SELECT DISTINCT service_name FROM \"" + config.getLogsStream()
                + "\" WHERE _timestamp >= " + startMicros
                + " AND _timestamp <= " + endMicros
                + " AND service_name IS NOT NULL"
                + " AND service_name != ''"
                + " ORDER BY service_name";

        List<Map<String, Object>> hits = search(sql, startMicros, endMicros, 0, DISCOVERY_SIZE_CAP, "logs");

        List<String> services = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            Object name = hit.get("service_name");
            if (name instanceof String string && !string.isEmpty()) {
                services.add(string);
            }
        }
        log.debug("OpenObserve discoverServices: found {} service(s)", services.size());
        return services;
    }

    @Override
    public JsonNode getJson(String path) {
        log.debug("OpenObserve GET {}", path);

        long t0 = System.nanoTime();
        String response = client.get()
                // The path is ALREADY percent-encoded by the caller, so it is
                // handed over as a URI object. Passing a String instead routes
                // it through RestClient's UriBuilderFactory, which encodes it a
                // second time: a PromQL query containing [5m] arrives as
                // %255B5m%255D and OpenObserve answers 400.
                //
                // uriBuilder.build(true) does NOT avoid this. UriBuilder has no
                // build(boolean) overload, so `true` binds to the varargs
                // build(Object...) and is read as a template VARIABLE — it
                // compiles, changes nothing, and looks like it worked.
                //
                // Only the metrics endpoints send anything needing escapes,
                // which is why this went unnoticed: every other caller here
                // builds a plain ASCII path and is unaffected either way.
                .uri(java.net.URI.create(config.getHost() + path))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String errBody = new String(res.getBody().readAllBytes());
                    throw mapError(res.getStatusCode().value(), errBody);
                })
                .body(String.class);

        try {
            JsonNode root = json.readTree(response);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            log.debug("OpenObserve GET {} -> ok in {} ms", path, elapsedMs);
            return root;
        } catch (Exception e) {
            throw new OpenObserveQueryException(0, "Failed to parse response from " + path, "n/a", e);
        }
    }

    @Override
    public JsonNode sendJson(String method, String path, JsonNode body) {
        HttpMethod verb = HttpMethod.valueOf(method.toUpperCase());
        log.debug("OpenObserve {} {} (body={})", verb, path, body != null);

        long t0 = System.nanoTime();
        RestClient.RequestBodySpec spec = client.method(verb)
                // Absolute URI for the same reason getJson uses one — see the
                // long note there. RestClient would otherwise percent-encode the
                // already-encoded path a second time.
                .uri(java.net.URI.create(config.getHost() + path));

        // A null body is not the same as an empty one: OpenObserve's PATCH
        // toggles (…/enable?value=false, …/trigger) take their input entirely
        // from the query string and answer 400 to an unexpected JSON body.
        RestClient.RequestHeadersSpec<?> ready = (body != null)
                ? spec.body(serialise(body))
                : spec;

        String response = ready
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String errBody = new String(res.getBody().readAllBytes());
                    throw mapError(res.getStatusCode().value(), errBody);
                })
                .body(String.class);

        JsonNode parsed = parseOrEmpty(response, verb + " " + path);
        log.debug("OpenObserve {} {} -> ok in {} ms", verb, path, (System.nanoTime() - t0) / 1_000_000L);
        return parsed;
    }

    @Override
    public JsonNode deleteJson(String path) {
        log.debug("OpenObserve DELETE {}", path);

        long t0 = System.nanoTime();
        String response = client.delete()
                .uri(java.net.URI.create(config.getHost() + path))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String errBody = new String(res.getBody().readAllBytes());
                    throw mapError(res.getStatusCode().value(), errBody);
                })
                .body(String.class);

        JsonNode parsed = parseOrEmpty(response, "DELETE " + path);
        log.debug("OpenObserve DELETE {} -> ok in {} ms", path, (System.nanoTime() - t0) / 1_000_000L);
        return parsed;
    }

    @Override
    public JsonNode ingest(String org, String streamName, JsonNode records) {
        if (records == null || !records.isArray() || records.isEmpty()) {
            throw new IllegalArgumentException("ingest: records must be a non-empty JSON array");
        }
        String path = String.format("%s%s/%s/_json", API_PREFIX, org, streamName);
        log.debug("OpenObserve ingest {} ({} record(s))", path, records.size());

        long t0 = System.nanoTime();
        String response = client.post()
                .uri(java.net.URI.create(config.getHost() + path))
                .body(serialise(records))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String errBody = new String(res.getBody().readAllBytes());
                    throw mapError(res.getStatusCode().value(), errBody);
                })
                .body(String.class);

        JsonNode parsed = parseOrEmpty(response, "INGEST " + path);
        assertNoRejectedRecords(parsed, path, records.size());
        log.debug("OpenObserve ingest {} -> {} record(s) in {} ms",
                path, records.size(), (System.nanoTime() - t0) / 1_000_000L);
        return parsed;
    }

    /**
     * Turns a partially-failed ingest into an exception.
     *
     * <p>OpenObserve answers 200 and puts the real outcome in the body:</p>
     *
     * <pre>{"code":200,"status":[{"name":"alert_events","successful":0,"failed":1,
     *                             "error":"Schema conflict: field x Int64 vs Utf8"}]}</pre>
     *
     * <p>Nothing in the HTTP layer can see that. The usual cause is a schema
     * conflict — a field written as a number on one call and a string on the
     * next — which is silent, permanent for that stream, and looks exactly like
     * "the alert never fired" from the UI. Failing loudly here is the only way
     * the caller learns the write did not happen.</p>
     */
    private void assertNoRejectedRecords(JsonNode parsed, String path, int sent) {
        JsonNode status = parsed.path("status");
        if (!status.isArray()) {
            // Older builds answer {"code":200} with no per-stream breakdown.
            // Nothing to verify — a non-2xx would already have thrown.
            return;
        }
        for (JsonNode s : status) {
            int failed = s.path("failed").asInt(0);
            if (failed > 0) {
                String error = s.path("error").asText("");
                throw new OpenObserveQueryException(0,
                        "Ingest to " + path + " rejected " + failed + " of " + sent
                                + " record(s)" + (error.isBlank() ? "" : ": " + error),
                        "n/a");
            }
        }
    }

    @Override
    public List<String> listMetricStreams() {
        // Same GET the org summary uses; the only difference is what's read out
        // of the envelope, so it goes through the shared helper.
        JsonNode root = getJson(API_PREFIX + config.getOrg() + "/streams?type=metrics");
        JsonNode list = root.path("list");
        List<String> names = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode entry : list) {
                String name = entry.path("name").asText(null);
                if (name != null && !name.isEmpty())
                    names.add(name);
            }
        }
        log.debug("OpenObserve listMetricStreams: {} stream(s)", names.size());
        return names;
    }

    private String serialise(JsonNode body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new OpenObserveQueryException(0, "Failed to serialise request body", "n/a", e);
        }
    }

    /**
     * Parse a write response, tolerating an empty one.
     *
     * <p>Several OpenObserve write endpoints answer 200 with a zero-length body.
     * Treating that as a parse failure would surface a successful delete as an
     * error, so an empty body becomes an empty object instead.</p>
     */
    private JsonNode parseOrEmpty(String response, String what) {
        if (response == null || response.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(response);
        } catch (Exception e) {
            throw new OpenObserveQueryException(0, "Failed to parse response from " + what, "n/a", e);
        }
    }

    private String buildSearchBody(String sql, long start, long end, int from, int size) {
        try {
            long finalStart = start > 0 ? start : 1L;
            long finalEnd = end > 0 ? end : Instant.now().getEpochSecond() * 1_000_000L + Instant.now().getNano() / 1_000L;

            Map<String, Object> queryMap = new java.util.LinkedHashMap<>();
            queryMap.put("sql", sql);
            queryMap.put("start_time", finalStart);
            queryMap.put("end_time", finalEnd);
            queryMap.put("from", from);
            queryMap.put("size", size);
            return json.writeValueAsString(Map.of("query", queryMap));
        } catch (Exception e) {
            throw new OpenObserveQueryException(0, "Failed to serialise request body", "n/a", e);
        }
    }

    private SearchOutcome parseSearch(String response) {
        try {
            JsonNode root = json.readTree(response);

            // OpenObserve reports query-level failures INSIDE a 200 response:
            // `function_error` is populated while `hits` comes back empty (or
            // partial). Nothing above this line can see that — the HTTP status
            // is 200 and no exception is raised — so a broken query used to be
            // indistinguishable from "no matching data". That is exactly how
            // /api/logs/services silently returned zero services for months on
            // a 7d window ({"code":20006,"message":"Search parquet file not
            // found"}), and the UI quietly showed 18 of 106 services.
            //
            // Logged rather than thrown on purpose: OpenObserve also sets this
            // for recoverable partial results ("the response is based on
            // partial data"), where the hits it did return are still worth
            // having. An exception would throw those away.
            JsonNode functionError = root.path("function_error");
            boolean hasFunctionError = isFunctionError(functionError);
            if (hasFunctionError) {
                warnFunctionError(root, functionError);
            }

            // `is_partial` is the flag OpenObserve sets when it could not scan the
            // whole range. It is reported SEPARATELY from function_error and either
            // one alone means the result is not authoritative, so both count.
            boolean partial = hasFunctionError || root.path("is_partial").asBoolean(false);

            JsonNode hits = root.path("hits");
            List<Map<String, Object>> rows = hits.isArray()
                    ? json.convertValue(hits, HITS_TYPE)
                    : List.of();
            return new SearchOutcome(rows, partial,
                    hasFunctionError ? functionError.toString() : null);
        } catch (Exception e) {
            throw new OpenObserveQueryException(0, "Failed to parse search response", "n/a", e);
        }
    }

    /** Whether {@code function_error} is set: present, not null, and not an empty array. */
    private static boolean isFunctionError(JsonNode functionError) {
        return !functionError.isMissingNode() && !functionError.isNull()
                && !(functionError.isArray() && functionError.isEmpty());
    }

    private static void warnFunctionError(JsonNode root, JsonNode functionError) {
        int hitCount = root.path("hits").isArray() ? root.path("hits").size() : 0;
        log.warn("OpenObserve returned function_error with {} hit(s) — results may be "
                + "empty or partial: {}", hitCount, functionError);
    }

    private OpenObserveQueryException mapError(int httpStatus, String body) {
        try {
            JsonNode root = json.readTree(body);
            int code = root.path("code").asInt(httpStatus);
            String message = root.path("message").asText("(no message)");
            String ooTraceId = root.path("trace_id").asText("n/a");
            return new OpenObserveQueryException(code, message, ooTraceId);
        } catch (Exception parseError) {
            return new OpenObserveQueryException(httpStatus, body, "n/a");
        }
    }
}
