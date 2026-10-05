package com.trinity.observability.util;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.constants.AppConstants;
import com.trinity.observability.dto.traces.TraceLogEvent;
import com.trinity.observability.dto.traces.TraceTreeNode;
// LogNormalizer is in this same package (com.trinity.observability.util), so no
// import is needed; referenced directly below for secret-masking the stacktrace.
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Correlates log events to trace-tree nodes for the Severity-Aware Trace Panel.
 */
public class TraceLogCorrelator {

    private static final Logger log = LogManager.getLogger(TraceLogCorrelator.class);
    private static final int MAX_LOGS_PER_TRACE = 500;

    /**
     * Shared by the single-trace and batched reads so the two can never drift.
     *
     * <p>code_namespace/code_function/code_filepath/code_lineno come from OTel
     * code.* attributes and let the trace view show "which class/method/file/line
     * emitted this log" — without them the trace inspector only carries the
     * logger name and the user has to flip back to the logs tab to find the call
     * site.</p>
     *
     * <p>Exception type/message are short; the stacktrace is ~19-36 KB so it is
     * gated to error-class severities via CASE — non-error logs in the trace
     * don't carry it.</p>
     */
    private static final String SELECT_CLAUSE =
            "SELECT _timestamp, body, severity, service_name, trace_id, span_id, "
            + "code_namespace, code_function, code_filepath, code_lineno, "
            + "exception_type, exception_message, "
            + "CASE WHEN severity IN ('ERROR', 'FATAL', 'SEVERE') "
            + "THEN exception_stacktrace ELSE NULL END AS exception_stacktrace";

    private static final String TYPE_MONGODB = "MONGODB";
    private static final String TYPE_DATABASE = "DATABASE";
    private static final String TYPE_QUEUE = "QUEUE";

    private static final Map<String, String> KEYWORD_TO_TYPE;
    static {
        KEYWORD_TO_TYPE = new LinkedHashMap<>();
        KEYWORD_TO_TYPE.put("mongo",     TYPE_MONGODB);
        KEYWORD_TO_TYPE.put("mongodb",   TYPE_MONGODB);
        KEYWORD_TO_TYPE.put("collection",TYPE_MONGODB);
        KEYWORD_TO_TYPE.put("mssql",     "MSSQL");
        KEYWORD_TO_TYPE.put("sql",       TYPE_DATABASE);
        KEYWORD_TO_TYPE.put("jdbc",      TYPE_DATABASE);
        KEYWORD_TO_TYPE.put("database",  TYPE_DATABASE);
        KEYWORD_TO_TYPE.put("redis",     "CACHE");
        KEYWORD_TO_TYPE.put("cache",     "CACHE");
        KEYWORD_TO_TYPE.put("kafka",     TYPE_QUEUE);
        KEYWORD_TO_TYPE.put("rabbit",    TYPE_QUEUE);
        KEYWORD_TO_TYPE.put("queue",     TYPE_QUEUE);
        KEYWORD_TO_TYPE.put("cookie",    "HTTP");
        KEYWORD_TO_TYPE.put("auth",      "HTTP");
        KEYWORD_TO_TYPE.put("token",     "HTTP");
        KEYWORD_TO_TYPE.put("http",      "HTTP");
    }

    private TraceLogCorrelator() {}

    public static void correlate(String traceId, TraceTreeNode rootNode, ElasticsearchClientService esClient) {
        if (traceId == null || traceId.isEmpty() || rootNode == null || esClient == null) return;

        try {
            Map<String, String> filters = new HashMap<>();
            filters.put("trace.id", traceId);

            Query query = QueryBuilderUtil.buildBoolQuery(filters, null, null, null, AppConstants.TIMESTAMP_FIELD);

            @SuppressWarnings("rawtypes")
            SearchResponse<Map> response = esClient.search(
                    AppConstants.LOGS_INDEX, query, 0, MAX_LOGS_PER_TRACE,
                    AppConstants.TIMESTAMP_FIELD, SortOrder.Asc, Map.class);

            if (response.hits() == null || response.hits().hits() == null) return;

            List<Map<String, Object>> logSources = response.hits().hits().stream()
                    .filter(h -> h.source() != null)
                    .map(h -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> src = h.source();
                        return src;
                    })
                    .toList();

            if (logSources.isEmpty()) return;

            attachLogSources(traceId, rootNode, logSources);

        } catch (IOException e) {
            log.warn("TraceLogCorrelator: failed to fetch logs for traceId={}: {}", traceId, e.getMessage());
        } catch (Exception e) {
            log.warn("TraceLogCorrelator: unexpected error for traceId={}: {}", traceId, e.getMessage());
        }
    }

    /** Elasticsearch counterpart of {@link #applyHits}: maps each log document and hangs it off its node. */
    private static void attachLogSources(String traceId, TraceTreeNode rootNode,
                                         List<Map<String, Object>> logSources) {
        String rootServiceName = rootNode.getServiceName();
        Map<String, TraceTreeNode> spanIdToNode = new HashMap<>();
        List<TraceTreeNode> allNodes = new ArrayList<>();
        collectNodesRecursively(rootNode, spanIdToNode, allNodes);

        Set<String> seenKeys = new HashSet<>();
        Map<TraceTreeNode, List<TraceLogEvent>> eventsPerNode = new LinkedHashMap<>();

        for (Map<String, Object> src : logSources) {
            TraceLogEvent event = toEvent(src, rootNode, traceId, rootServiceName);
            if (event != null && seenKeys.add(buildDedupKey(event))) {
                TraceTreeNode target = findTarget(event, spanIdToNode, allNodes, rootNode);
                eventsPerNode.computeIfAbsent(target, k -> new ArrayList<>()).add(event);
            }
        }

        eventsPerNode.forEach((node, events) -> {
            events.sort(Comparator.comparing(TraceLogEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())));
            node.setEvents(events);
        });

        ensureEventsList(rootNode);
    }

    /**
     * OpenObserve variant — uses SQL search via OpenObserveClientService.
     *
     * SELECTs both `severity` AND `level` columns. The mapper resolves to whichever is
     * populated — matches the symmetric handling in Step 1.4 / 1.9b of MIGRATION-PLAN.md
     * (a deployment that populates `level` instead of `severity` would otherwise have
     * the Java fallback never fire because no rows would come back).
     *
     * @param logsStream    OpenObserve stream name (typically "default")
     * @param startMicros   inclusive lower bound, microseconds since epoch
     * @param endMicros     inclusive upper bound, microseconds since epoch
     */
    public static void correlate(String traceId, TraceTreeNode rootNode,
                                 OpenObserveClientService ooClient, String logsStream,
                                 long startMicros, long endMicros) {
        if (traceId == null || traceId.isEmpty() || rootNode == null || ooClient == null) return;

        try {
            String safeStream = SqlSafety.validateIdentifier(logsStream);
            String safeTraceId = SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(traceId));

            // code_namespace/code_function/code_filepath/code_lineno come from OTel
            // code.* attributes and let the trace view show "which class/method/file/line
                       // emitted this log" — without them the trace inspector only carries the
                       // logger name and the user has to flip back to the logs tab to find the
                       // call site. log_logger stays in the SELECT as a fallback for older
                       // pipelines that don't populate code.namespace.
            String sql = SELECT_CLAUSE
                       + " FROM \"" + safeStream + "\""
                       + " WHERE trace_id = '" + safeTraceId + "'"
                       + " AND _timestamp >= " + startMicros
                       + " AND _timestamp <= " + endMicros
                       + " ORDER BY _timestamp ASC";

            List<Map<String, Object>> hits = ooClient.search(
                sql, startMicros, endMicros, 0, MAX_LOGS_PER_TRACE, "logs");

            applyHits(traceId, rootNode, hits);

        } catch (Exception e) {
            log.warn("TraceLogCorrelator (OpenObserve): unexpected error for traceId={}: {}",
                     traceId, e.getMessage());
        }
    }

    /**
     * One OpenObserve read for a whole page of traces, replacing one read per
     * trace.
     *
     * <p>The per-trace form issues 1 + N round trips for an N-trace page — at
     * page size 20 that measured ~19.6 s, nearly all of it round-trip latency
     * rather than query time. This mirrors the batched pattern already used by
     * the alert measurement lookup: fetch once with {@code trace_id IN (...)},
     * group by trace_id in Java, and let each trace read its logs out of the
     * map.</p>
     *
     * <p>Returns an empty map (never null) on any failure, which leaves callers
     * behaving exactly as they do when a per-trace read fails: the trace still
     * renders, with an empty events list.</p>
     *
     * <p>Each trace keeps its own {@link #MAX_LOGS_PER_TRACE} budget, enforced
     * by a {@code ROW_NUMBER() OVER (PARTITION BY trace_id)} rank rather than by
     * a shared LIMIT — see the comment on the SQL. Verified set-identical to 20
     * per-trace reads on traces of ~2,600 logs each, at 0.99 s against 6.47 s.</p>
     */
    public static Map<String, List<Map<String, Object>>> fetchLogsForTraces(
            Collection<String> traceIds, OpenObserveClientService ooClient,
            String logsStream, long startMicros, long endMicros) {

        if (traceIds == null || traceIds.isEmpty() || ooClient == null) return Map.of();

        List<String> distinct = traceIds.stream()
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .toList();
        if (distinct.isEmpty()) return Map.of();

        try {
            String safeStream = SqlSafety.validateIdentifier(logsStream);
            String inList = distinct.stream()
                    .map(id -> "'" + SqlSafety.escapeStringLiteral(SqlSafety.validateIdentifier(id)) + "'")
                    .collect(Collectors.joining(", "));

            // ROW_NUMBER() OVER (PARTITION BY trace_id) is what makes this a true
            // drop-in for N per-trace reads. A plain "trace_id IN (...) ORDER BY
            // _timestamp ASC LIMIT n" shares ONE row budget across every trace, so
            // the earliest traces eat it and the rest come back empty — measured
            // here on 20 traces of ~2,600 logs each, 16 of the 20 got zero rows
            // where per-trace reads gave each of them 500. Ranking inside the
            // partition gives every trace its own MAX_LOGS_PER_TRACE window, which
            // is exactly the per-trace `size` cap this replaces.
            String sql = SELECT_CLAUSE
                       + " FROM (SELECT *, ROW_NUMBER() OVER ("
                       + "PARTITION BY trace_id ORDER BY _timestamp ASC) AS _rn"
                       + " FROM \"" + safeStream + "\""
                       + " WHERE trace_id IN (" + inList + ")"
                       + " AND _timestamp >= " + startMicros
                       + " AND _timestamp <= " + endMicros
                       + ") WHERE _rn <= " + MAX_LOGS_PER_TRACE
                       + " ORDER BY _timestamp ASC";

            // The partition cap already bounds each trace; this is the matching
            // overall ceiling so the client never has to hold more than the sum of
            // the per-trace budgets it replaces.
            int cap = MAX_LOGS_PER_TRACE * distinct.size();
            List<Map<String, Object>> hits =
                    ooClient.search(sql, startMicros, endMicros, 0, cap, "logs");

            return hits.stream()
                    .filter(h -> h.get("trace_id") instanceof String tid && !tid.isEmpty())
                    .collect(Collectors.groupingBy(h -> (String) h.get("trace_id"),
                            LinkedHashMap::new, Collectors.toList()));

        } catch (Exception e) {
            log.warn("TraceLogCorrelator (OpenObserve): batched log read failed for {} trace(s): {}",
                     distinct.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Attach already-fetched log rows to a trace tree — the batched counterpart
     * of {@link #correlate(String, TraceTreeNode, OpenObserveClientService, String, long, long)}.
     * Both run the identical {@link #applyHits} body, so a trace enriched this
     * way is indistinguishable from one enriched by its own query.
     */
    public static void correlate(String traceId, TraceTreeNode rootNode,
                                 List<Map<String, Object>> preFetchedHits) {
        if (traceId == null || traceId.isEmpty() || rootNode == null) return;
        try {
            applyHits(traceId, rootNode, preFetchedHits == null ? List.of() : preFetchedHits);
        } catch (Exception e) {
            log.warn("TraceLogCorrelator (OpenObserve): unexpected error for traceId={}: {}",
                     traceId, e.getMessage());
        }
    }

    /**
     * Turn raw log rows into events and hang them off the matching tree nodes.
     *
     * <p>Extracted so the per-trace query path and the batched path share one
     * implementation; behaviour is identical either way. {@code hits} is
     * expected in {@code _timestamp ASC} order, and is truncated to
     * {@link #MAX_LOGS_PER_TRACE} here so the batched path applies the same
     * per-trace cap the single-trace query applied via its {@code size}.</p>
     */
    private static void applyHits(String traceId, TraceTreeNode rootNode,
                                  List<Map<String, Object>> hits) {
        if (rootNode == null) return;
        if (hits.isEmpty()) {
            ensureEventsList(rootNode);
            return;
        }
        if (hits.size() > MAX_LOGS_PER_TRACE) {
            hits = hits.subList(0, MAX_LOGS_PER_TRACE);
        }

        Map<String, TraceTreeNode> spanIdToNode = new HashMap<>();
        List<TraceTreeNode> allNodes = new ArrayList<>();
        collectNodesRecursively(rootNode, spanIdToNode, allNodes);

        String rootServiceName = rootNode.getServiceName();
        Set<String> seenKeys = new HashSet<>();
        Map<TraceTreeNode, List<TraceLogEvent>> eventsPerNode = new LinkedHashMap<>();

        for (Map<String, Object> hit : hits) {
            TraceLogEvent event = toEventFromOpenObserve(hit, rootNode, traceId, rootServiceName);
            if (event != null && seenKeys.add(buildDedupKey(event))) {
                TraceTreeNode target = findTarget(event, spanIdToNode, allNodes, rootNode);
                eventsPerNode.computeIfAbsent(target, k -> new ArrayList<>()).add(event);
            }
        }

        eventsPerNode.forEach((node, events) -> {
            events.sort(Comparator.comparing(TraceLogEvent::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
            node.setEvents(events);
        });

        ensureEventsList(rootNode);
    }

    private static TraceLogEvent toEventFromOpenObserve(Map<String, Object> hit, TraceTreeNode rootNode,
                                                        String traceId, String rootServiceName) {
        String message = (String) hit.get("body");
        if (message == null || message.trim().isEmpty()) return null;

        String rawSeverity = (String) hit.get("severity");

        // _timestamp is Int64 microseconds; convert via TimeFormatter (defensive Number cast per r7).
        Number tsRaw = (Number) hit.get("_timestamp");
        String timestamp = tsRaw == null ? null : TimeFormatter.microsToIso(tsRaw.longValue());

        String logSpanId = (String) hit.get("span_id");
        String serviceName = (String) hit.get("service_name");
        if (serviceName == null || serviceName.isEmpty()) serviceName = rootServiceName;

        TraceLogEvent event = new TraceLogEvent();
        event.setSeverity(normalizeSeverity(rawSeverity));
        event.setMessage(message);
        event.setTimestamp(timestamp);
        event.setSpanId(logSpanId);
        event.setTraceId(traceId);
        event.setServiceName(serviceName);
        applyOoCodeLocation(event, hit);
        applyOoException(event, hit);

        event.setId(buildEventId(traceId, logSpanId, timestamp, message));

        Double relativeMs = computeRelativeMsDouble(timestamp, rootStartTime(rootNode));
        event.setRelativeDurationMs(relativeMs);
        event.setRelativeTimeMs(relativeMs);

        return event;
    }

    private static void applyOoCodeLocation(TraceLogEvent event, Map<String, Object> hit) {
        // Prefer OTel code.* attributes when present. Was previously `className = log_logger, methodName = null`
        // because the SELECT didn't pull code_namespace/code_function — the columns
        // exist in the default stream when the agent's source-locator is on.
        String codeNamespace = (String) hit.get("code_namespace");
        String codeFunction  = (String) hit.get("code_function");
        event.setClassName(codeNamespace != null && !codeNamespace.isEmpty()
            ? codeNamespace : null);
        event.setMethodName(codeFunction);
        event.setFilePath((String) hit.get("code_filepath"));
        Object linenoRaw = hit.get("code_lineno");
        if (linenoRaw instanceof Number number) {
            event.setLineNumber(number.intValue());
        }
    }

    private static void applyOoException(TraceLogEvent event, Map<String, Object> hit) {
        // Exception attributes — type/message for any severity; the stacktrace is
        // only non-null for error-class rows (gated in the SELECT's CASE). Mask
        // secrets in the stacktrace but do NOT truncate it.
        event.setExceptionType((String) hit.get("exception_type"));
        event.setExceptionMessage((String) hit.get("exception_message"));
        String stack = (String) hit.get("exception_stacktrace");
        if (stack != null && !stack.isEmpty()) {
            event.setStackTrace(LogNormalizer.sanitizeSensitiveData(stack));
        }
    }

    /** The instant log offsets are measured from: the root's start time, else its timestamp. */
    private static String rootStartTime(TraceTreeNode rootNode) {
        return rootNode.getStartTime() != null ? rootNode.getStartTime() : rootNode.getTimestamp();
    }

    private static void collectNodesRecursively(TraceTreeNode node, Map<String, TraceTreeNode> spanMap, List<TraceTreeNode> allNodes) {
        if (node == null) return;
        allNodes.add(node);
        if (node.getSpanId() != null) spanMap.put(node.getSpanId(), node);
        if (node.getChildren() != null) {
            for (TraceTreeNode child : node.getChildren()) collectNodesRecursively(child, spanMap, allNodes);
        }
    }

    private static TraceTreeNode findTarget(TraceLogEvent event, Map<String, TraceTreeNode> spanIdToNode, List<TraceTreeNode> allNodes, TraceTreeNode rootNode) {
        String logSpanId = event.getSpanId();
        if (logSpanId != null && spanIdToNode.containsKey(logSpanId)) return spanIdToNode.get(logSpanId);

        if (event.getTimestamp() != null) {
            Instant logInstant = parseInstant(event.getTimestamp());
            if (logInstant != null) {
                TraceTreeNode bestMatch = findTightestEnclosingNode(logInstant, allNodes);
                if (bestMatch != null) return bestMatch;
            }
        }

        TraceTreeNode typeMatch = findByMessageKeyword(event.getMessage(), allNodes);
        return typeMatch != null ? typeMatch : rootNode;
    }

    /** The node with the shortest time range that contains {@code logInstant}, or null. */
    private static TraceTreeNode findTightestEnclosingNode(Instant logInstant, List<TraceTreeNode> allNodes) {
        TraceTreeNode bestMatch = null;
        double bestRange = Double.MAX_VALUE;
        for (TraceTreeNode node : allNodes) {
            Double range = enclosingRangeMs(node, logInstant);
            if (range != null && range < bestRange) {
                bestRange = range;
                bestMatch = node;
            }
        }
        return bestMatch;
    }

    /** The node's duration in ms when its [start, end] contains {@code logInstant}; null otherwise. */
    private static Double enclosingRangeMs(TraceTreeNode node, Instant logInstant) {
        if (node.getStartTime() == null || node.getEndTime() == null) return null;
        Instant nodeStart = parseInstant(node.getStartTime());
        Instant nodeEnd = parseInstant(node.getEndTime());
        if (nodeStart == null || nodeEnd == null) return null;
        if (logInstant.isBefore(nodeStart) || logInstant.isAfter(nodeEnd)) return null;
        return Duration.between(nodeStart, nodeEnd).toNanos() / 1_000_000.0;
    }

    /** First node whose type matches the first keyword (in map order) found in the message that has a match. */
    private static TraceTreeNode findByMessageKeyword(String message, List<TraceTreeNode> allNodes) {
        String msgLower = message != null ? message.toLowerCase() : "";
        for (Map.Entry<String, String> entry : KEYWORD_TO_TYPE.entrySet()) {
            if (msgLower.contains(entry.getKey())) {
                final String finalType = entry.getValue();
                TraceTreeNode typeMatch = allNodes.stream()
                        .filter(n -> finalType.equals(n.getType()) || finalType.equals(TraceTreeBuilder.normalizeType(n.getType())))
                        .findFirst().orElse(null);
                if (typeMatch != null) return typeMatch;
            }
        }
        return null;
    }

    private static TraceLogEvent toEvent(Map<String, Object> src, TraceTreeNode rootNode, String traceId, String rootServiceName) {
        String message = MappingUtil.getStringValue(src, "body.text", "message");
        if (message == null || message.trim().isEmpty()) return null;

        String rawSeverity = MappingUtil.getStringValue(src, "severity_text", "severityText", "log.severity");
        String timestamp = MappingUtil.getStringValue(src, "@timestamp");
        String logSpanId = MappingUtil.getStringValue(src, "span.id", "span_id", "spanId");
        String serviceName = MappingUtil.getStringValue(src, "resource.attributes.service.name", "service.name");
        if (serviceName == null || serviceName.isEmpty()) serviceName = rootServiceName;

        TraceLogEvent event = new TraceLogEvent();
        event.setSeverity(normalizeSeverity(rawSeverity));
        event.setMessage(message);
        event.setTimestamp(timestamp);
        event.setSpanId(logSpanId);
        event.setTraceId(traceId);
        event.setServiceName(serviceName);
        event.setClassName(MappingUtil.getStringValue(src, "code.namespace", "classname", "class.name", "scope.name"));
        event.setMethodName(MappingUtil.getStringValue(src, "code.function", "function", "method"));
        event.setFilePath(MappingUtil.getStringValue(src, "code.filepath", "filepath", "file.path"));
        String linenoStr = MappingUtil.getStringValue(src, "code.lineno", "lineno", "line.number");
        if (linenoStr != null && !linenoStr.isEmpty()) {
            try { event.setLineNumber(Integer.parseInt(linenoStr.trim())); }
            catch (NumberFormatException ignored) { /* malformed lineno — leave null */ }
        }

        event.setId(buildEventId(traceId, logSpanId, timestamp, message));
        
        Double relativeMs = computeRelativeMsDouble(timestamp, rootNode.getStartTime() != null ? rootNode.getStartTime() : rootNode.getTimestamp());
        event.setRelativeDurationMs(relativeMs);
        event.setRelativeTimeMs(relativeMs);

        return event;
    }

    private static String normalizeSeverity(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "INFO";
        switch (raw.trim().toUpperCase()) {
            case "FATAL": return "FATAL";
            case "ERROR", "ERR": return "ERROR";
            case "WARN", "WARNING": return "WARN";
            case "DEBUG", "DBG": return "DEBUG";
            case "TRACE": return "TRACE";
            default: return "INFO";
        }
    }

    private static Double computeRelativeMsDouble(String logTimestamp, String rootTs) {
        if (logTimestamp == null || rootTs == null) return null;
        try {
            Instant logInstant = parseInstant(logTimestamp);
            Instant rootInstant = parseInstant(rootTs);
            if (logInstant == null || rootInstant == null) return null;
            return Duration.between(rootInstant, logInstant).toNanos() / 1_000_000.0;
        } catch (Exception e) { return null; }
    }

    private static String normalizeTimestamp(String ts) {
        if (ts == null) return null;
        ts = ts.trim();
        if (ts.contains("T") && (ts.endsWith("Z") || ts.contains("+"))) return ts;
        try {
            double doubleVal = Double.parseDouble(ts);
            long millis = (long) doubleVal;
            long micros = (long) ((doubleVal - millis) * 1000);
            return Instant.ofEpochMilli(millis).plusNanos(micros * 1000).toString();
        } catch (NumberFormatException e) { return null; }
    }

    private static Instant parseInstant(String ts) {
        try {
            String normalized = normalizeTimestamp(ts);
            return normalized != null ? Instant.parse(normalized) : null;
        } catch (Exception e) { return null; }
    }

    private static String buildDedupKey(TraceLogEvent event) {
        return String.join("|", nullSafe(event.getTimestamp()), nullSafe(event.getSeverity()), nullSafe(event.getMessage()), nullSafe(event.getSpanId()));
    }

    private static String buildEventId(String traceId, String spanId, String timestamp, String message) {
        long msgHash = 0L;
        if (message != null) {
            int h = message.hashCode();
            // Widened before abs so Integer.MIN_VALUE maps to 2147483648 rather than overflowing.
            msgHash = Math.abs((long) h);
        }
        return String.format("%s-%s-%s-%d", nullSafe(traceId), nullSafe(spanId), nullSafe(timestamp), msgHash);
    }

    private static void ensureEventsList(TraceTreeNode node) {
        if (node == null) return;
        if (node.getEvents() == null) node.setEvents(new ArrayList<>());
        if (node.getChildren() != null) {
            for (TraceTreeNode child : node.getChildren()) ensureEventsList(child);
        }
    }

    private static String nullSafe(String s) { return s != null ? s : ""; }
}
