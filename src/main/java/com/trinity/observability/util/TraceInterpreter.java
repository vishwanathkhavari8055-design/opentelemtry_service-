package com.trinity.observability.util;

import com.trinity.observability.dto.traces.TraceResponseDTO;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-responsibility: maps a classified APM span (by SpanType) to
 * human-readable fields on a {@link TraceResponseDTO}.
 *
 * Delegates to type-specific mapping methods to stay focused.
 * Mongo parsing is further delegated to {@link MongoStatementParser}.
 */
public class TraceInterpreter {

    // Extracts operationName from "{assetDetails(filters:?,tenantCode:?,...)}"
    private static final Pattern GRAPHQL_OP_PATTERN = Pattern.compile("\\{\\s*(\\w+)\\s*[({]");

    // Extracts table name from SQL: "FROM schema.tableName" or "FROM tableName"
    private static final Pattern MSSQL_TABLE_PATTERN = Pattern.compile(
            "(?i)\\bfrom\\s+([\\w.]+)");

    private static final String EVENT_OUTCOME = "event.outcome";
    private static final String SPAN_NAME = "span.name";
    private static final String SPAN_DURATION_US = "span.duration.us";
    private static final String REDIS = "redis";
    private static final String REDIS_PREFIX = "Redis ";
    private static final String ERROR_TYPE = "error.type";
    private static final String ERROR_MESSAGE = "error.message";
    private static final String FAILED_SUFFIX = " Failed";
    private static final String ERROR = "error";
    private static final String FAILURE = "failure";
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");

    // Words sqlOpLine skips when looking for the object an SQL statement acts on (compared lower-cased)
    private static final Set<String> SQL_FILLER_WORDS = Set.of("*", "top", "distinct", "from");

    // Standard reason phrases for the status codes this UI names explicitly
    private static final Map<Integer, String> HTTP_STATUS_TEXT = Map.ofEntries(
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(204, "No Content"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(304, "Not Modified"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"));

    private TraceInterpreter() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public dispatch
    // ─────────────────────────────────────────────────────────────────────────

    public static void interpret(TraceClassifier.SpanType type,
                                 Map<String, Object> source,
                                 TraceResponseDTO dto) {
        // 1. Run type-specific mappers first (Baseline)
        switch (type) {
            case HTTP_INCOMING: mapHttpIncoming(source, dto); break;
            case HTTP_OUTGOING: mapHttpOutgoing(source, dto); break;
            case GRAPHQL:      mapGraphql(source, dto);      break;
            case DATABASE:     mapDatabase(source, dto);     break;
            default:           mapInternal(source, dto);     break;
        }

        // 2. Resolve Dynamic Operation (Enrichment Hook)
        resolveDynamicOperation(source, dto);
    }

    /**
     * Stateless enhancement layer that resolves the most descriptive operation
     * from refined telemetry, prioritizing statements over generic labels.
     */
    private static void resolveDynamicOperation(Map<String, Object> source, TraceResponseDTO dto) {
        // Priority 1: Valid Database Statement / Operation
        String statement = DbStatementResolver.find(source);
        if (hasText(statement)) {
            dto.setActualOperation(statement.trim());
            return;
        }

        // Priority 2: Structured HTTP Protocol (Method + Path + Status)
        String method = dto.getHttpMethod();
        String path = dto.getEndpoint();
        String status = dto.getStatus();

        // Safety Rule: Only construct if ALL three core fields exist to prevent malformed info
        if (hasText(method) && hasText(path) && hasText(status)) {
            
            // Format: GET /api/users → 200 OK
            // We preserve the existing 'status' which already includes duration/statusText
            dto.setActualOperation(method + " " + path + " → " + status);
        }

        // Priority 3: Final Fallback
        // Do nothing - preserve whatever was set by the specialized mappers (usually span.name)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Incoming (Transaction)
    // ─────────────────────────────────────────────────────────────────────────

    private static void mapHttpIncoming(Map<String, Object> source, TraceResponseDTO dto) {
        String method    = MappingUtil.getStringValue(source, "http.request.method", "http.method");
        String path      = MappingUtil.getStringValue(source, "url.path", "url.original");
        String txResult  = MappingUtil.getStringValue(source, "transaction.result");
        Long   durationUs = MappingUtil.getLongValue(source, "transaction.duration.us");
        String outcome   = MappingUtil.getStringValue(source, EVENT_OUTCOME);

        Integer httpStatus = responseStatusCode(source);

        // spanName: "Inbound HTTP: POST /api/..."
        dto.setSpanName(incomingSpanName(method, path));

        // actualOperation: "POST /api/... → 200 OK (103ms)"
        StringBuilder op = new StringBuilder(incomingBaseOperation(method, path));
        if (txResult != null)   op.append(" → ").append(txResult);
        appendDurationMs(op, durationUs);
        dto.setActualOperation(op.toString());

        // Enriched fields
        dto.setType("HTTP");
        dto.setDirection("INCOMING");
        dto.setHttpMethod(method);
        dto.setEndpoint(path);
        dto.setHttpStatus(httpStatus);
        dto.setTarget(path);
        dto.setStatus(buildHttpStatus(txResult, httpStatus, outcome, durationUs));
    }

    /** {@code http.response.status_code} as an int, or null when absent or not numeric. */
    private static Integer responseStatusCode(Map<String, Object> source) {
        Object statusCodeObj = nestedGet(source, "http", "response", "status_code");
        return statusCodeObj instanceof Number number ? number.intValue() : null;
    }

    private static String incomingSpanName(String method, String path) {
        if (method != null && path != null) {
            return "Inbound HTTP: " + method + " " + path;
        } else if (path != null) {
            return "Inbound HTTP: " + path;
        } else {
            return "Incoming Request";
        }
    }

    private static String incomingBaseOperation(String method, String path) {
        if (method != null) {
            return method + " " + path;
        } else if (path != null) {
            return path;
        } else {
            return "Request";
        }
    }

    /** Appends " (Nms)" — whole milliseconds — when the duration is known. */
    private static void appendDurationMs(StringBuilder op, Long durationUs) {
        if (durationUs != null) op.append(" (").append(durationUs / 1000).append("ms)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Outgoing (External Span)
    // ─────────────────────────────────────────────────────────────────────────

    private static void mapHttpOutgoing(Map<String, Object> source, TraceResponseDTO dto) {
        String method     = MappingUtil.getStringValue(source, "http.request.method", "http.method");
        String fullUrl    = MappingUtil.getStringValue(source, "url.full", "url.original");
        String path       = MappingUtil.getStringValue(source, "url.path");
        String destName   = MappingUtil.getStringValue(source, "span.destination.service.name", "destination.service.name");
        String spanNameRaw = MappingUtil.getStringValue(source, SPAN_NAME);
        Long   durationUs  = MappingUtil.getLongValue(source, SPAN_DURATION_US, "duration");
        String outcome    = MappingUtil.getStringValue(source, EVENT_OUTCOME);

        Integer httpStatus = responseStatusCode(source);

        // Determine a friendly target name
        String target = outgoingTarget(destName, path, fullUrl);

        // spanName: "Outbound Call: other-service"
        dto.setSpanName("Outbound Call: " + target);

        // actualOperation: "GET http://other-service/api → 200 (15ms)"
        StringBuilder op = new StringBuilder(method != null ? method + " " : "");
        op.append(outgoingRequestText(fullUrl, spanNameRaw));
        if (httpStatus != null) op.append(" → ").append(httpStatus);
        appendDurationMs(op, durationUs);
        dto.setActualOperation(op.toString());

        // Enriched fields
        dto.setType("HTTP");
        dto.setDirection("OUTGOING");
        dto.setHttpMethod(method);
        dto.setEndpoint(fullUrl);
        dto.setHttpStatus(httpStatus);
        dto.setTarget(target);
        dto.setStatus(buildHttpStatus(null, httpStatus, outcome, durationUs));
    }

    /** Destination service, else the path, else the URL's host; "External Service" when none is known. */
    private static String outgoingTarget(String destName, String path, String fullUrl) {
        String target = destName;
        if (target == null) {
            target = path != null ? path : extractHost(fullUrl);
        }
        if (target == null) {
            target = "External Service";
        }
        return target;
    }

    private static String outgoingRequestText(String fullUrl, String spanNameRaw) {
        if (fullUrl != null) {
            return fullUrl;
        } else if (spanNameRaw != null) {
            return spanNameRaw;
        } else {
            return "External request";
        }
    }

    private static String extractHost(String url) {
        if (url == null) return null;
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GraphQL
    // ─────────────────────────────────────────────────────────────────────────

    private static void mapGraphql(Map<String, Object> source, TraceResponseDTO dto) {
        String opType    = MappingUtil.getStringValue(source, "labels.graphql_operation_type");
        String document  = MappingUtil.getStringValue(source, "labels.graphql_document");
        String outcome   = MappingUtil.getStringValue(source, EVENT_OUTCOME);
        Long   durationUs = MappingUtil.getLongValue(source, SPAN_DURATION_US);

        String opName = extractGraphqlOpName(document);
        if (opName == null) opName = opType; // fallback: "query" / "mutation"

        // spanName: "GraphQL Query: assetDetails"
        dto.setSpanName("GraphQL " + capitalize(opType) + ": " + opName);

        // actualOperation: "query { assetDetails(filters, tenantCode, ...) }"
        String shortDoc = shortenGraphqlDoc(document, opName);
        dto.setActualOperation(opType + " { " + shortDoc + " }");

        // Enriched fields
        dto.setType("GRAPHQL");
        dto.setTarget(opName);
        dto.setStatus(buildGraphqlStatus(opType, outcome, durationUs, source));
        if (durationUs != null) dto.setDuration(durationUs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Database (dispatches to sub-mappers)
    // ─────────────────────────────────────────────────────────────────────────

    private static void mapDatabase(Map<String, Object> source, TraceResponseDTO dto) {
        String dbTech  = TraceClassifier.resolveDbTechnology(source);
        String outcome = MappingUtil.getStringValue(source, EVENT_OUTCOME);
        Long   durationUs = MappingUtil.getLongValue(source, SPAN_DURATION_US);

        dto.setType("DATABASE");
        dto.setDatabaseType(dbTech);
        dto.setStatus(buildDbStatus(dbTech, outcome, durationUs, source));
        if (durationUs != null) dto.setDuration(durationUs);

        switch (dbTech) {
            case "mongodb": mapMongo(source, dto);  break;
            case "neo4j":   mapNeo4j(source, dto);  break;
            case REDIS:     mapRedis(source, dto);   break;
            case "mssql":   mapMssql(source, dto);   break;
            default:        mapSql(source, dto);     break;
        }

        // Append failure marker
        markDbFailure(source, dto, dbTech);
    }

    private static void markDbFailure(Map<String, Object> source, TraceResponseDTO dto, String dbTech) {
        if (isFailed(source) && dto.getSpanName() != null
                && !dto.getSpanName().endsWith("(Failed)")) {
            dto.setSpanName(dto.getSpanName() + " (Failed)");
            String errorMsg = MappingUtil.getStringValue(source, ERROR_MESSAGE, ERROR_TYPE);
            dto.setStatus(capitalize(dbTech) + FAILED_SUFFIX + (errorMsg != null ? " — " + truncate(errorMsg, 80) : ""));
        }
    }

    // ┌── MongoDB ──────────────────────────────────────────────────────────────
    private static void mapMongo(Map<String, Object> source, TraceResponseDTO dto) {
        String op          = MappingUtil.getStringValue(source,
                                 "labels.db_operation", "span.labels.db_operation");
        String collection  = MappingUtil.getStringValue(source,
                                 "labels.db_mongodb_collection", "span.labels.db_mongodb_collection");
        String statement   = DbStatementResolver.find(source);
        String rawSpanName = MappingUtil.getStringValue(source, SPAN_NAME);

        // ── Ping detection: mark as infrastructure, not a real data operation ──
        if (isMongoPing(op, rawSpanName, statement)) {
            dto.setSpanName("MongoDB Health Check (Ping)");
            dto.setActualOperation("Connectivity check — not a data operation");
            dto.setTarget("mongodb");
            dto.setDbStatement(null); // no meaningful statement
            return;
        }

        // spanName: "MongoDB aggregate → AHU"
        String spanName = "MongoDB " + (op != null ? op : "operation")
                + (collection != null ? " → " + collection : "");
        dto.setSpanName(spanName);

        // actualOperation: delegated to MongoStatementParser
        String humanQuery = MongoStatementParser.parse(statement, op, collection);
        dto.setActualOperation(humanQuery != null ? humanQuery : spanName);
        dto.setDbStatement(humanQuery);
        dto.setTarget(collection);
    }

    private static boolean isMongoPing(String op, String rawSpanName, String statement) {
        return "ping".equalsIgnoreCase(op)
                || (rawSpanName != null && rawSpanName.toLowerCase().contains("ping"))
                || (statement != null && statement.contains("\"ping\""));
    }

    // ┌── Neo4j ────────────────────────────────────────────────────────────────
    private static void mapNeo4j(Map<String, Object> source, TraceResponseDTO dto) {
        String statement = DbStatementResolver.find(source);
        String op        = cypherOp(statement);

        dto.setSpanName("Neo4j " + op);
        dto.setActualOperation(statement != null ? statement : "Neo4j query");
        dto.setTarget("neo4j");
        dto.setDbStatement(statement);
    }

    // ┌── Redis ────────────────────────────────────────────────────────────────
    private static void mapRedis(Map<String, Object> source, TraceResponseDTO dto) {
        // 1. Detect if we have a full multi-word statement (Priority)
        String statement = MappingUtil.getStringValue(source,
                "db.statement", "labels.db_statement", "span.db.statement");
        
        // 2. Fallback to command + key if no full statement
        String command = MappingUtil.getStringValue(source,
                "db.operation", "labels.db_operation", "db.redis.command", "labels.db_redis_command");
        String key = MappingUtil.getStringValue(source,
                "db.redis.key", "labels.db_redis_key");

        String rawSpanName = MappingUtil.getStringValue(source, SPAN_NAME);

        // Rule: If we have a multi-word statement, preserve it untruncated as the primary operation
        if (statement != null && statement.trim().contains(" ")) {
            String cleanStmt = statement.trim();
            dto.setActualOperation(cleanStmt);
            // spanName still uses short command for tree row (e.g. Redis HSET)
            dto.setSpanName(REDIS_PREFIX + cleanStmt.split("\\s+")[0].toUpperCase());
            dto.setTarget(REDIS);
            return;
        }

        // Component extraction from raw span name if labels are missing
        RedisCommand redisCommand = redisCommandOf(command, key, rawSpanName);

        // Absolute fallback
        if (redisCommand.command() == null) {
            String fallback = rawSpanName != null ? rawSpanName : "Redis operation";
            dto.setSpanName(fallback);
            dto.setActualOperation(fallback);
            dto.setTarget(REDIS);
            return;
        }

        applyRedisCommand(redisCommand, dto);
    }

    /** A Redis command and its (optional) key. */
    private record RedisCommand(String command, String key) {}

    /** The labelled command/key, filling whichever is missing from the raw span name ("HGET key"). */
    private static RedisCommand redisCommandOf(String command, String key, String rawSpanName) {
        if (command != null || rawSpanName == null) {
            return new RedisCommand(command, key);
        }
        String[] parts = rawSpanName.trim().split("\\s+");
        String parsedCommand = parts.length > 0 ? parts[0] : null;
        String parsedKey = key == null && parts.length > 1 ? parts[1] : key;
        return new RedisCommand(parsedCommand, parsedKey);
    }

    private static void applyRedisCommand(RedisCommand redisCommand, TraceResponseDTO dto) {
        String command = redisCommand.command().toUpperCase();
        String key = redisCommand.key();
        String spanName = key != null ? REDIS_PREFIX + command + " (" + key + ")" : REDIS_PREFIX + command;
        String actualOp = key != null ? command + " " + key : command;

        dto.setSpanName(spanName);
        dto.setActualOperation(actualOp);
        dto.setTarget(REDIS);
    }

    // ┌── MSSQL ─────────────────────────────────────────────────────────────────
    private static void mapMssql(Map<String, Object> source, TraceResponseDTO dto) {
        String statement    = DbStatementResolver.find(source);
        String originalName = MappingUtil.getStringValue(source, SPAN_NAME);

        String fallbackName = originalName != null ? originalName : "MSSQL Query";

        // Always preserve full statement as actualOperation
        dto.setDbStatement(statement);
        dto.setActualOperation(statement != null ? statement : fallbackName);

        // Try to extract operation type + table name for spanName
        if (hasText(statement) && applyMssqlSpanName(statement, dto)) {
            return;
        }

        // Fallback: don't modify existing behavior
        dto.setSpanName(fallbackName);
    }

    /** Sets "MSSQL OP (table)" span name and target; false when the statement could not be parsed. */
    private static boolean applyMssqlSpanName(String statement, TraceResponseDTO dto) {
        try {
            String operationType = statement.trim().split("\\s+")[0].toUpperCase();
            Matcher tableMatcher = MSSQL_TABLE_PATTERN.matcher(statement);
            String tableName = tableMatcher.find() ? tableMatcher.group(1) : null;

            if (tableName != null) {
                dto.setSpanName("MSSQL " + operationType + " (" + tableName + ")");
            } else {
                dto.setSpanName("MSSQL " + operationType);
            }
            dto.setTarget(tableName);
            return true;
        } catch (Exception ignored) {
            // Fallback in the caller
            return false;
        }
    }

    // ┌── Generic SQL ──────────────────────────────────────────────────────────
    private static void mapSql(Map<String, Object> source, TraceResponseDTO dto) {
        String statement   = DbStatementResolver.find(source);
        String originalName = MappingUtil.getStringValue(source, SPAN_NAME);
        String opLine       = sqlOpLine(statement);

        String spanName = opLine;
        if (spanName == null) {
            spanName = originalName != null ? originalName : "DB Query";
        }
        dto.setSpanName(spanName);
        dto.setActualOperation(statement != null ? statement : spanName);
        dto.setDbStatement(statement);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal / fallback
    // ─────────────────────────────────────────────────────────────────────────

    private static void mapInternal(Map<String, Object> source, TraceResponseDTO dto) {
        String name    = MappingUtil.getStringValue(source, SPAN_NAME);
        String outcome = MappingUtil.getStringValue(source, EVENT_OUTCOME);

        Long   durationUs = MappingUtil.getLongValue(source, SPAN_DURATION_US, "duration");

        dto.setType("INTERNAL");
        dto.setSpanName(name != null ? name : "Internal");
        dto.setActualOperation(name != null ? name : "Internal span");
        dto.setStatus(buildInternalStatus(outcome, durationUs, source));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Extract operationName from "{assetDetails(filters:?,...)}" → "assetDetails" */
    private static String extractGraphqlOpName(String document) {
        if (document == null || document.isEmpty()) return null;
        Matcher m = GRAPHQL_OP_PATTERN.matcher(document);
        if (m.find()) return m.group(1);
        // Trim braces, take first word
        String trimmed = document.trim().replaceAll("^\\{\\s*", "").split("[(){}\\s]", 2)[0];
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Shorten "{assetDetails(filters:?,tenantCode:?,pageNumber:?,...)}"
     * → "assetDetails(filters, tenantCode, pageNumber, ...)"
     */
    private static String shortenGraphqlDoc(String document, String opName) {
        if (document == null) return opName != null ? opName : "operation";
        String params = document.replaceAll(":([^,)]+)", "")  // strip values
                                .replaceAll("[{}]", "").trim();
        return params.isEmpty() ? opName : params;
    }

    private static String cypherOp(String statement) {
        if (statement == null) return "Query";
        String upper = statement.trim().toUpperCase();
        if (upper.startsWith("MATCH"))  return "Read";
        if (upper.startsWith("CREATE")) return "Create";
        if (upper.startsWith("MERGE"))  return "Merge";
        if (upper.startsWith("DELETE")) return "Delete";
        return "Query";
    }

    private static String sqlOpLine(String sql) {
        if (sql == null || sql.isEmpty()) return null;
        try {
            String[] parts = sql.trim().replaceAll("\\s+", " ").split(" ");
            String op = parts[0].toUpperCase();
            for (int i = 1; i < parts.length; i++) {
                if (!isSqlFiller(parts[i].toLowerCase())) {
                    return op + " " + parts[i];
                }
            }
            return op;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** True for words that never name the object of an SQL statement: *, TOP, DISTINCT, FROM, numbers. */
    private static boolean isSqlFiller(String lowerWord) {
        return SQL_FILLER_WORDS.contains(lowerWord) || DIGITS.matcher(lowerWord).matches();
    }

    private static boolean isFailed(Map<String, Object> source) {
        String outcome = MappingUtil.getStringValue(source, EVENT_OUTCOME, "outcome");
        if (FAILURE.equalsIgnoreCase(outcome) || ERROR.equalsIgnoreCase(outcome)) return true;
        Long code = MappingUtil.getLongValue(source, "span.status.code", "status.code");
        if (code != null && (code == 2L || code == 500L)) return true;
        return source.containsKey(ERROR) || source.containsKey(ERROR_MESSAGE);
    }

    @SuppressWarnings("unchecked")
    private static Object nestedGet(Map<String, Object> source, String... keys) {
        Object cur = source;
        for (String k : keys) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contextual status builders
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * HTTP status with code + category + duration.
     * Examples:
     *   "HTTP 2xx — 200 OK (103ms)"
     *   "HTTP 5xx — 500 Internal Server Error"
     *   "Connection Failed"
     */
    private static String buildHttpStatus(String txResult, Integer httpStatus,
                                          String outcome, Long durationUs) {
        StringBuilder sb = new StringBuilder();

        if (httpStatus != null) {
            sb.append(httpStatus).append(" ").append(httpStatusText(httpStatus));
        } else if (txResult != null) {
            sb.append(txResult);
        } else if (outcome != null) {
            sb.append(FAILURE.equalsIgnoreCase(outcome) ? "Failed" : capitalize(outcome));
        } else {
            sb.append("Unknown");
        }

        if (durationUs != null) {
            sb.append(" (").append(formatDuration(durationUs)).append(")");
        }
        return sb.toString();
    }

    /**
     * GraphQL status with operation type + outcome + duration.
     * Examples:
     *   "Query Succeeded (45ms)"
     *   "Mutation Failed — TimeoutException"
     */
    private static String buildGraphqlStatus(String opType, String outcome,
                                             Long durationUs, Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        sb.append(capitalize(opType != null ? opType : "Operation"));

        boolean failed = FAILURE.equalsIgnoreCase(outcome) || ERROR.equalsIgnoreCase(outcome);
        sb.append(failed ? FAILED_SUFFIX : " Succeeded");

        if (failed) {
            String errMsg = MappingUtil.getStringValue(source, ERROR_MESSAGE, ERROR_TYPE);
            if (errMsg != null) sb.append(" — ").append(truncate(errMsg, 60));
        }

        if (durationUs != null) {
            sb.append(" (").append(formatDuration(durationUs)).append(")");
        }
        return sb.toString();
    }

    /**
     * Database status with technology + outcome + duration.
     * Examples:
     *   "MongoDB Success (12ms)"
     *   "Redis Success (0.3ms)"
     *   "Neo4j Failed — Connection refused"
     */
    private static String buildDbStatus(String dbTech, String outcome,
                                        Long durationUs, Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        sb.append(capitalize(dbTech));

        boolean failed = FAILURE.equalsIgnoreCase(outcome) || ERROR.equalsIgnoreCase(outcome);
        sb.append(failed ? FAILED_SUFFIX : " Success");

        if (failed) {
            String errMsg = MappingUtil.getStringValue(source, ERROR_MESSAGE, ERROR_TYPE);
            if (errMsg != null) sb.append(" — ").append(truncate(errMsg, 60));
        }

        if (durationUs != null) {
            sb.append(" (").append(formatDuration(durationUs)).append(")");
        }
        return sb.toString();
    }

    /**
     * Internal span status.
     * Examples:
     *   "Completed (8ms)"
     *   "Error — NullPointerException"
     */
    private static String buildInternalStatus(String outcome, Long durationUs,
                                              Map<String, Object> source) {
        boolean failed = FAILURE.equalsIgnoreCase(outcome) || ERROR.equalsIgnoreCase(outcome);
        StringBuilder sb = new StringBuilder(failed ? "Error" : "Completed");

        if (failed) {
            String errMsg = MappingUtil.getStringValue(source, ERROR_MESSAGE, ERROR_TYPE);
            if (errMsg != null) sb.append(" — ").append(truncate(errMsg, 60));
        }

        if (durationUs != null) {
            sb.append(" (").append(formatDuration(durationUs)).append(")");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Format microseconds into human-friendly duration: <1ms, 45ms, 1.2s, etc. */
    private static String formatDuration(long us) {
        if (us < 1000)        return us + "µs";
        if (us < 1_000_000)   return (us / 1000) + "ms";
        double secs = us / 1_000_000.0;
        if (secs < 60)        return String.format("%.1fs", secs);
        return String.format("%.0fm %.0fs", Math.floor(secs / 60), secs % 60);
    }

    /** Map HTTP status code to standard text */
    private static String httpStatusText(int code) {
        String known = HTTP_STATUS_TEXT.get(code);
        if (known != null) return known;
        if (code >= 200 && code < 300) return "OK";
        if (code >= 400 && code < 500) return "Client Error";
        if (code >= 500) return "Server Error";
        return "";
    }
}
