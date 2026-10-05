package com.trinity.observability.util;

import com.trinity.observability.dto.traces.TraceResponseDTO;

import java.util.Map;

/**
 * Populates a {@link TraceResponseDTO} from a flat OpenObserve hit, given the
 * already-classified span type. OTel-native counterpart to {@link TraceInterpreter}.
 *
 * <p>Each span type dispatches to a small per-type helper. Type-agnostic fields
 * (HTTP, status) are applied unconditionally so HTTP-bearing DB or internal spans
 * still surface those columns.</p>
 */
public final class OoTraceInterpreter {

    private OoTraceInterpreter() {}

    /** Static lookup keyed by SpanType: {label, direction}. Built once at class init. */
    private static final java.util.EnumMap<TraceClassifier.SpanType, String[]> TYPE_LOOKUP =
        buildTypeLookup();

    private static java.util.EnumMap<TraceClassifier.SpanType, String[]> buildTypeLookup() {
        java.util.EnumMap<TraceClassifier.SpanType, String[]> m = new java.util.EnumMap<>(TraceClassifier.SpanType.class);
        m.put(TraceClassifier.SpanType.HTTP_INCOMING, new String[]{"HTTP",     "INCOMING"});
        m.put(TraceClassifier.SpanType.HTTP_OUTGOING, new String[]{"HTTP",     "OUTGOING"});
        m.put(TraceClassifier.SpanType.DATABASE,      new String[]{"DATABASE", "OUTGOING"});
        m.put(TraceClassifier.SpanType.GRAPHQL,       new String[]{"GRAPHQL",  null});
        m.put(TraceClassifier.SpanType.INTERNAL,      new String[]{"INTERNAL", null});
        return m;
    }

    /**
     * Apply enrichment to {@code dto} based on the classified span {@code type}.
     * Cyclomatic complexity: 5.
     */
    public static void interpret(TraceClassifier.SpanType type,
                                 Map<String, Object> hit,
                                 TraceResponseDTO dto) {
        if (type == null || hit == null || dto == null) return;

        String[] labels = TYPE_LOOKUP.getOrDefault(type, new String[]{"INTERNAL", null});
        dto.setType(labels[0]);
        dto.setDirection(labels[1]);

        applyHttpFields(hit, dto);
        applyStatus(hit, dto);

        if (type == TraceClassifier.SpanType.DATABASE) {
            applyDbFields(hit, dto);
        }
    }

    /** Cyclomatic complexity: 5. */
    private static void applyHttpFields(Map<String, Object> hit, TraceResponseDTO dto) {
        dto.setHttpMethod((String) hit.get("http_request_method"));
        applyHttpStatus(hit, dto);
        applyEndpoint(hit, dto);
        // OTel HTTP instrumentation can attach request/response bodies as
        // span attributes when capture-bodies is enabled — surface them so
        // the UI inspector pane can render them as inline JSON sections.
        dto.setHttpRequestBody((String) hit.get("http_request_body"));
        dto.setHttpResponseBody((String) hit.get("http_response_body"));
    }

    /** Cyclomatic complexity: 4. */
    private static void applyHttpStatus(Map<String, Object> hit, TraceResponseDTO dto) {
        String raw = (String) hit.get("http_response_status_code");
        if (raw == null || raw.isEmpty()) return;
        try {
            dto.setHttpStatus(Integer.parseInt(raw));
        } catch (NumberFormatException ignore) {
            // non-numeric — leave null; SonarQube allows empty catch with a comment
        }
    }

    /** Cyclomatic complexity: 2. */
    private static void applyEndpoint(Map<String, Object> hit, TraceResponseDTO dto) {
        String url = (String) hit.get("url_full");
        if (url == null) url = (String) hit.get("url_path");
        dto.setEndpoint(url);
    }

    /**
     * Status coalesce per Decision D3(c):
     * {@code span_status > "code N" formatted from status_code > null}.
     * Cyclomatic complexity: 4.
     */
    private static void applyStatus(Map<String, Object> hit, TraceResponseDTO dto) {
        String status = (String) hit.get("span_status");
        if (status == null) {
            Number code = (Number) hit.get("status_code");
            if (code != null) status = "code " + code.intValue();
        }
        dto.setStatus(status);
    }

    /** Cyclomatic complexity: 1 (just delegates). */
    private static void applyDbFields(Map<String, Object> hit, TraceResponseDTO dto) {
        dto.setDatabaseType(OoTraceClassifier.resolveDbTechnology(hit));
        dto.setDbStatement(OoDbStatementResolver.find(hit));
        dto.setTarget(resolveTarget(hit));
    }

    /** Cyclomatic complexity: 3. */
    private static String resolveTarget(Map<String, Object> hit) {
        String t = (String) hit.get("db_mongodb_collection");
        if (t == null) t = (String) hit.get("db_sql_table");
        if (t == null) t = (String) hit.get("db_name");
        return t;
    }
}
