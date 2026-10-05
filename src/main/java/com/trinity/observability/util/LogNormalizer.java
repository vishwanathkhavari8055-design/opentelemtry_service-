package com.trinity.observability.util;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for normalizing log messages to improve readability, security,
 * and conciseness.
 */
public class LogNormalizer {

    private static final int MAX_LENGTH = 150;

    private static final String CLASSNAME = "classname";
    private static final String CLASS_NAME = "class.name";
    private static final String FILENAME = "filename";
    private static final String FILE_NAME = "file.name";
    private static final String MASKED_REPLACEMENT = "$1[masked]";
    private static final String UNKNOWN = "unknown";
    private static final String FUNCTION = "function";

    private LogNormalizer() {
        // Utility class
    }

    // Masking Patterns
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("accessToken\\s*+[:=]?\\s*+(\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(Authorization\\s*+[:=]?\\s*+Bearer\\s++)(\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_PATTERN = Pattern.compile("(Bearer\\s+)(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(password\\s*[:=]\\s*)(\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(apiKey\\s*[:=]\\s*)(\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_STRING_PATTERN = Pattern.compile("(Cookie\\s*[:=]\\s*)(.*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_PAIR_PATTERN = Pattern.compile("([^=\\s;]+)=([^;\\s]*)");

    // Dynamic extraction (Preserved for context)
    private static final Pattern EXECUTION_TIME_PATTERN = Pattern.compile("completed in (\\d+) ms",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_TABLE_PATTERN = Pattern.compile("Invalid object name '(.*?)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STACKTRACE_FRAME_PATTERN = Pattern.compile(
            "at\\s+([\\w.$]+)\\.[\\w$]+\\(([^:]+):(\\d+)\\)");

    // INFO rule keywords (lower-case), each list probed in order with contains()
    private static final List<String> INFO_DB_QUERY_HINTS = List.of("executing data query", "cypher query",
            "match (");
    private static final List<String> INFO_CACHE_HINTS = List.of("redis token", "published map data");
    private static final List<String> INFO_REQUEST_HINTS = List.of("uri host", "url data", "end point url");

    // Frame-shape keys probed by isFrameLike
    private static final List<String> FRAME_CLASS_KEYS = List.of(CLASSNAME, CLASS_NAME, "className", "class");
    private static final List<String> FRAME_METHOD_KEYS = List.of(FUNCTION, "method", "code.function");
    private static final List<String> FRAME_LINE_KEYS = List.of("line", "line.number", "lineNumber",
            "line_number", "lineno", "code.lineno", "log.file.line");
    private static final List<String> FRAME_FILE_KEYS = List.of("file", FILENAME, FILE_NAME, "log.file.name");

    /** Lower-cased package prefixes of framework / JDK classes that are never business code. */
    private static final List<String> NON_BUSINESS_PREFIXES = List.of("java.", "javax.", "jakarta.", "jdk.",
            "sun.", "oracle.", "org.springframework.", "org.hibernate.", "org.apache.", "org.slf4j.",
            "reactor.", "com.microsoft.", "com.zaxxer.", "io.netty.", "proxy.");

    /** Conventional function name for a class-name suffix. */
    private record SuffixRule(String function, List<String> suffixes) {
    }

    /** Probed in order; the first rule with a matching suffix wins. */
    private static final List<SuffixRule> FUNCTION_BY_CLASS_SUFFIX = List.of(
            new SuffixRule("execute", List.of("ServiceImpl", "Service")),
            new SuffixRule("doFilter", List.of("Filter")),
            new SuffixRule("handleRequest", List.of("Controller", "Api")),
            new SuffixRule("executeDbOperation", List.of("DaoImpl", "Dao", "Repository")));

    /**
     * Normalizes a log message based on predefined severity rules.
     *
     * @param message  The raw log message.
     * @param severity The log severity (INFO, WARN, ERROR).
     * @return The normalized message, or null if the log should be filtered out.
     */
    public static String normalizeMessage(String message, String severity) {
        if (isMissingMessage(message)) {
            return "Unexpected error occurred in the system";
        }

        // Global Security Masking
        String sanitized = sanitizeSensitiveData(message);
        String msgLower = sanitized.toLowerCase();
        String sevUpper = (severity != null) ? severity.toUpperCase() : null;

        // 🟢 INFO LOG RULES (Noise Reduction) - filter noise
        if ("INFO".equals(sevUpper) && isInfoNoise(msgLower))
            return null;

        String ruleMessage = applySeverityRules(sanitized, msgLower, sevUpper);
        if (ruleMessage != null)
            return ruleMessage;

        // Fallback for non-ERROR: Truncate if too long
        if (!"ERROR".equalsIgnoreCase(sevUpper) && sanitized.length() > MAX_LENGTH) {
            return sanitized.substring(0, MAX_LENGTH).trim() + "...";
        }

        return sanitized;
    }

    private static boolean isMissingMessage(String message) {
        return message == null || message.trim().isEmpty() || "[EMPTY]".equalsIgnoreCase(message);
    }

    private static boolean isInfoNoise(String msgLower) {
        return msgLower.contains("cookie data") || msgLower.contains("cookie value");
    }

    /** Returns the rewritten message for the severity's rules, or null if no rule matched. */
    private static String applySeverityRules(String sanitized, String msgLower, String sevUpper) {
        if ("INFO".equals(sevUpper))
            return applyInfoRules(sanitized, msgLower);
        if ("WARN".equals(sevUpper))
            return applyWarnRules(msgLower);
        if ("ERROR".equals(sevUpper))
            return applyErrorRules(sanitized, msgLower);
        return null;
    }

    private static String applyInfoRules(String sanitized, String msgLower) {
        if (containsAny(msgLower, INFO_DB_QUERY_HINTS)) {
            return "Database query executed successfully";
        }
        if (msgLower.contains("completed in")) {
            return describeCompletion(sanitized);
        }
        if (msgLower.contains("generated response checksum"))
            return "Response generated successfully";
        if (containsAny(msgLower, INFO_CACHE_HINTS))
            return "Cache updated successfully";
        if (containsAny(msgLower, INFO_REQUEST_HINTS))
            return "Request processed";
        return null;
    }

    private static String describeCompletion(String sanitized) {
        Matcher m = EXECUTION_TIME_PATTERN.matcher(sanitized);
        if (m.find())
            return "Operation completed in " + m.group(1) + " ms";
        return "Operation completed successfully";
    }

    private static boolean containsAny(String value, List<String> needles) {
        for (String needle : needles) {
            if (value.contains(needle))
                return true;
        }
        return false;
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix))
                return true;
        }
        return false;
    }

    private static boolean endsWithAny(String value, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix))
                return true;
        }
        return false;
    }

    // 🟡 WARN LOG RULES (Meaningful)
    private static String applyWarnRules(String msgLower) {
        if (msgLower.contains("cookie rejected"))
            return "Invalid or rejected cookie detected";
        if (msgLower.contains("illegal 'domain' attribute"))
            return "Invalid cookie configuration detected";
        if (msgLower.contains("extra keys found"))
            return "Unexpected fields found in request data";
        return null;
    }

    // 🔴 ERROR LOG RULES (Meaningful & Clear)
    private static String applyErrorRules(String sanitized, String msgLower) {
        if (msgLower.contains("null orderno"))
            return "Some data missing required order information";
        if (msgLower.contains("uploading relationship excel"))
            return "Failed to upload relationship data";
        if (msgLower.contains("cannot invoke"))
            return "System error due to missing required data";
        if (msgLower.contains("save_excel_data_service_error"))
            return "Failed to save data";
        if (msgLower.contains("failed to process gis layer"))
            return "Failed to process geographic data";

        // Dynamic Table Name Extraction (Error only)
        Matcher sqlMatcher = SQL_TABLE_PATTERN.matcher(sanitized);
        if (sqlMatcher.find())
            return "Database table " + sqlMatcher.group(1) + " not found";
        return null;
    }

    /**
     * Enriches the log message with stack trace information (fileName, className,
     * lineNumber)
     * extracted from the Elasticsearch APM stacktrace or OTel exception fields.
     *
     * @param message The original log message.
     * @param source  The source map from Elasticsearch.
     * @return The enriched message, or the original message if data is missing or
     *         invalid.
     */
    public static String enrichMessageWithStackTrace(String message, Map<String, Object> source) {
        if (source == null || message == null) {
            return message;
        }

        // 1. Try structured exception data (Elastic APM style)
        // "exception" is the fallback to OTel style
        Object exceptionObj = firstRobustValue(source, "error.exception", "exception");

        if (exceptionObj instanceof List<?> exceptions) {
            String result = enrichFromStructuredList(message, exceptions);
            if (result != null)
                return result;
        }

        // 2. Try raw stacktrace string (OTel default or single exception field)
        Object rawStack = firstRobustValue(source, "error.stacktrace", "exception.stacktrace",
                "error.log.stacktrace");

        if (rawStack instanceof String str) {
            String result = enrichFromStackTraceString(message, str);
            if (result != null)
                return result;
        }

        return message;
    }

    /**
     * Local robust value extractor based on MappingUtil.getValue logic.
     * Ensures this class remains self-contained and binary-compatible.
     */
    private static Object getRobustValue(Map<?, ?> map, String path) {
        if (map == null || path == null)
            return null;
        Object current = map;
        String remainingPath = path;

        while (current instanceof Map<?, ?> currentMap && !remainingPath.isEmpty()) {
            if (currentMap.containsKey(remainingPath))
                return currentMap.get(remainingPath);

            String foundKey = resolveKey(currentMap, remainingPath);
            if (foundKey == null)
                return null;

            current = currentMap.get(foundKey);
            remainingPath = remainingPath.substring(foundKey.length() + 1);
        }
        return (remainingPath.isEmpty()) ? current : null;
    }

    /** First non-null {@link #getRobustValue} among {@code paths}, probed in order, or null. */
    private static Object firstRobustValue(Map<?, ?> map, String... paths) {
        for (String path : paths) {
            Object value = getRobustValue(map, path);
            if (value != null)
                return value;
        }
        return null;
    }

    /** Dotted-prefix key if any, otherwise the first-dot segment key, or null. */
    private static String resolveKey(Map<?, ?> currentMap, String remainingPath) {
        String foundKey = findPrefixKey(currentMap, remainingPath);
        return (foundKey != null) ? foundKey : findFirstSegmentKey(currentMap, remainingPath);
    }

    /** First key (as a String) that is a dotted prefix of {@code remainingPath}, or null. */
    private static String findPrefixKey(Map<?, ?> currentMap, String remainingPath) {
        for (Object keyObj : currentMap.keySet()) {
            String key = String.valueOf(keyObj);
            if (remainingPath.startsWith(key + "."))
                return key;
        }
        return null;
    }

    /** Segment before the first dot if it is a key of {@code currentMap}, or null. */
    private static String findFirstSegmentKey(Map<?, ?> currentMap, String remainingPath) {
        int dotIdx = remainingPath.indexOf('.');
        if (dotIdx > 0) {
            String part = remainingPath.substring(0, dotIdx);
            if (currentMap.containsKey(part))
                return part;
        }
        return null;
    }

    private static String enrichFromStructuredList(String message, List<?> exceptions) {
        Map<?, ?> candidateFrame = null;

        for (Object excObj : exceptions) {
            Object stacktraceObj = getExceptionStacktrace(excObj);
            String result = enrichFromExceptionStacktrace(message, stacktraceObj);
            if (result != null)
                return result;

            if (candidateFrame == null && stacktraceObj instanceof List<?> stacktrace)
                candidateFrame = findFirstBasicInfoFrame(stacktrace);
        }

        return (candidateFrame != null) ? formatEnrichedMessage(message, candidateFrame) : null;
    }

    /**
     * The enriched message from one exception's stacktrace: its business frame when
     * the stacktrace is a list, or the parsed string form otherwise. Null when
     * neither yields a frame. {@code message} is never null here, so a found
     * business frame always formats to a non-null result.
     */
    private static String enrichFromExceptionStacktrace(String message, Object stacktraceObj) {
        if (stacktraceObj instanceof List<?> stacktrace) {
            Map<?, ?> businessFrame = findBusinessFrameInStacktrace(stacktrace);
            return (businessFrame != null) ? formatEnrichedMessage(message, businessFrame) : null;
        }
        // If stacktrace is a string instead of a list inside the map
        return enrichFromNestedStackTraceString(message, stacktraceObj);
    }

    /** The "stacktrace" entry of an exception map, or null if the item is not a map. */
    private static Object getExceptionStacktrace(Object excObj) {
        if (!(excObj instanceof Map))
            return null;
        return ((Map<?, ?>) excObj).get("stacktrace");
    }

    private static String enrichFromNestedStackTraceString(String message, Object stacktraceObj) {
        if (stacktraceObj instanceof String str)
            return enrichFromStackTraceString(message, str);
        return null;
    }

    /** First map frame whose classname / class.name is a business class, or null. */
    @SuppressWarnings("java:S1168")
    private static Map<?, ?> findBusinessFrameInStacktrace(List<?> stacktrace) {
        for (Object frameObj : stacktrace) {
            if (!(frameObj instanceof Map))
                continue;
            Map<?, ?> frame = (Map<?, ?>) frameObj;
            Object className = getFrameField(frame, CLASSNAME, CLASS_NAME);
            if (className instanceof String str && isBusinessClass(str))
                return frame;
        }
        return null;
    }

    /** First map frame carrying both file and class info, or null. */
    @SuppressWarnings("java:S1168")
    private static Map<?, ?> findFirstBasicInfoFrame(List<?> stacktrace) {
        for (Object frameObj : stacktrace) {
            if (frameObj instanceof Map && hasBasicInfo((Map<?, ?>) frameObj))
                return (Map<?, ?>) frameObj;
        }
        return null;
    }

    private static String enrichFromStackTraceString(String message, String stacktrace) {
        if (stacktrace == null || stacktrace.isEmpty())
            return null;

        Matcher matcher = STACKTRACE_FRAME_PATTERN.matcher(stacktrace);
        String firstValidFrame = null;

        while (matcher.find()) {
            String className = matcher.group(1);
            String fileName = matcher.group(2);
            String lineNumber = matcher.group(3);

            String frameInfo = String.format("file name:%s, class name:%s, line number:%s, message:%s",
                    fileName, className, lineNumber, extractMeaningfulMessage(message));

            if (isBusinessClass(className)) {
                return frameInfo; // Prioritize business frame
            }
            if (firstValidFrame == null) {
                firstValidFrame = frameInfo; // Fallback to first valid frame
            }
        }

        return firstValidFrame;
    }

    private static boolean hasBasicInfo(Map<?, ?> frame) {
        return getFrameField(frame, FILENAME, FILE_NAME) != null
                && getFrameField(frame, CLASSNAME, CLASS_NAME) != null;
    }

    private static Object getFrameField(Map<?, ?> frame, String... keys) {
        for (String key : keys) {
            Object val = frame.get(key);
            if (val != null)
                return val;
        }
        return null;
    }

    private static String formatEnrichedMessage(String message, Map<?, ?> frame) {
        Object fileName = getFrameField(frame, FILENAME, FILE_NAME, "file");
        Object className = getFrameField(frame, CLASSNAME, CLASS_NAME, "class");

        // Specific line number extraction as requested
        Object lineObj = frame.get("line");
        Integer lineNumber = null;

        if (lineObj instanceof Map<?, ?> lineMap) {
            Object num = lineMap.get("number");
            if (num instanceof Integer numInt) {
                lineNumber = numInt;
            }
        } else if (lineObj instanceof Integer lineInt) {
            lineNumber = lineInt;
        } else {
            // Fallback to existing logic if direct "line" retrieval fails but other keys
            // exist
            Object altLine = getFrameField(frame, "line.number", "line_number");
            if (altLine instanceof Integer altInt) {
                lineNumber = altInt;
            }
        }

        if (fileName == null || className == null) {
            return message;
        }

        return String.format(
                "file name:%s, class name:%s, line number:%s, message:%s",
                fileName,
                className,
                (lineNumber != null ? lineNumber : "-"),
                extractMeaningfulMessage(message));
    }

    /**
     * Masks secrets (tokens, passwords, API keys, cookies, bearer auth) in an
     * arbitrary string. Public so callers can sanitize the full log body and
     * stack trace without going through {@link #normalizeMessage} (which also
     * truncates and rewrites). Length is preserved — only secrets are masked.
     */
    public static String sanitizeSensitiveData(String message) {
        if (message == null)
            return null;
        String sanitized = message;
        sanitized = ACCESS_TOKEN_PATTERN.matcher(sanitized).replaceAll("Authentication token processed [masked]");
        sanitized = AUTHORIZATION_PATTERN.matcher(sanitized).replaceAll(MASKED_REPLACEMENT);
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll(MASKED_REPLACEMENT);
        sanitized = PASSWORD_PATTERN.matcher(sanitized).replaceAll(MASKED_REPLACEMENT);
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll(MASKED_REPLACEMENT);

        Matcher cookieMatcher = COOKIE_STRING_PATTERN.matcher(sanitized);
        if (cookieMatcher.find()) {
            String prefix = cookieMatcher.group(1);
            String pairs = cookieMatcher.group(2);
            String maskedPairs = COOKIE_PAIR_PATTERN.matcher(pairs).replaceAll("$1=[masked]");
            sanitized = sanitized.substring(0, cookieMatcher.start()) + prefix + maskedPairs
                    + sanitized.substring(cookieMatcher.end());
        }
        return sanitized;
    }

    private static String extractMeaningfulMessage(String message) {
        if (message == null)
            return null;

        // 1. Cut at "The full response is" (most specific)
        int respIdx = message.indexOf("The full response is");
        if (respIdx != -1) {
            message = message.substring(0, respIdx);
        }

        // 2. Cut at JSON start ONLY if it's following a space or starts with '{'
        // This avoids cutting strings like '$geometry' while still hitting raw dumps
        int braceIdx = message.indexOf(" {");
        if (braceIdx == -1 && message.startsWith("{")) {
            braceIdx = 0;
        }

        if (braceIdx != -1) {
            message = message.substring(0, braceIdx);
        }

        // 3. Final Sanity Check (Trim + Length Limit)
        message = message.trim();
        // Limit length for UI safety
        int maxLen = 180;
        if (message.length() > maxLen) {
            message = message.substring(0, maxLen) + "...";
        }

        return message;
    }

    /**
     * OpenObserve variant — flat-hit code metadata extraction.
     *
     * <p>When the OTel Java agent is started with
     * {@code -Dotel.instrumentation.log4j-appender.experimental.capture-code-attributes=true}
     * the logs records carry these as flat columns (dots replaced with
     * underscores by OO):</p>
     * <ul>
     *   <li>{@code code_namespace} — fully qualified class name</li>
     *   <li>{@code code_function}  — method name</li>
     *   <li>{@code code_lineno}    — line number (Integer)</li>
     *   <li>{@code code_filepath}  — source file</li>
     * </ul>
     *
     * <p>When the flag is off the agent emits these as the literal string
     * "unknown"; we treat that as absent and fall back to {@code scope_name}
     * (= SLF4J/Log4j logger name) for the namespace, matching the previous
     * behaviour. {@code log_logger} is also probed as a final legacy fallback
     * for older ingestion pipelines.</p>
     */
    public static void extractCodeMetadataOo(Map<String, Object> hit,
            com.trinity.observability.dto.logs.LogResponseDTO dto) {
        if (hit == null || dto == null) return;

        // Class / method / line / file from OTel code attributes (preferred).
        // Fall back chain matches what's actually in OO's logs schema:
        //   1. code_namespace            (set by the agent's code-attrs flag)
        //   2. instrumentation_library_name (= OTel scope name = logger class)
        //   3. log_logger                (legacy ingestion paths)
        String namespace = readFirstNonUnknownString(hit, "code_namespace", "instrumentation_library_name",
                "log_logger");
        if (namespace != null) dto.setCodeNamespace(namespace);

        String function = readNonUnknownString(hit, "code_function");
        if (function != null) dto.setCodeFunction(function);

        applyOoLineNo(hit.get("code_lineno"), dto);

        String filepath = readNonUnknownString(hit, "code_filepath");
        if (filepath != null) dto.setCodeFilepath(filepath);
    }

    /** Sets the DTO's line number from a numeric or numeric-string {@code code_lineno}. */
    private static void applyOoLineNo(Object lineno, com.trinity.observability.dto.logs.LogResponseDTO dto) {
        if (lineno instanceof Number number) {
            dto.setCodeLineNo(number.intValue());
        } else if (lineno instanceof String str) {
            try { dto.setCodeLineNo(Integer.parseInt(str.trim())); }
            catch (NumberFormatException ignore) { /* leave null */ }
        }
    }

    /** First {@link #readNonUnknownString} result among {@code keys}, probed in order, or null. */
    private static String readFirstNonUnknownString(Map<String, Object> hit, String... keys) {
        for (String key : keys) {
            String value = readNonUnknownString(hit, key);
            if (value != null)
                return value;
        }
        return null;
    }

    /** Returns the trimmed string at {@code key}, or {@code null} if the value
     *  is missing, blank, or the OTel-emitted placeholder "unknown". */
    private static String readNonUnknownString(Map<String, Object> hit, String key) {
        Object v = hit.get(key);
        if (!(v instanceof String)) return null;
        String s = ((String) v).trim();
        if (s.isEmpty() || UNKNOWN.equalsIgnoreCase(s)) return null;
        return s;
    }

    /**
     * Extracts and normalizes OTel code metadata fields (code.function,
     * code.lineno, code.namespace)
     * from the raw Elasticsearch source map into the LogResponseDTO.
     */
    public static void extractCodeMetadata(Map<String, Object> source,
            com.trinity.observability.dto.logs.LogResponseDTO dto) {
        if (source == null || dto == null)
            return;

        // --------------------------------------------------
        // EXISTING FUNCTION & NAMESPACE LOGIC (PRESERVED)
        // --------------------------------------------------
        CodeMetadata meta = new CodeMetadata();
        meta.function = firstValidString(source, "code.function", FUNCTION, "attributes.code.function");
        meta.namespace = firstValidString(source, "code.namespace", CLASSNAME, CLASS_NAME, "scope.name",
                "resource.attributes.scope.name", "attributes.code.namespace");

        meta.functionValid = isValidValue(meta.function);
        meta.namespaceValid = isValidValue(meta.namespace);

        String msgObj = firstValidString(source, "body.text", "message");
        applyMessageHints(meta, msgObj);

        // Find business frame early if needed for Function/Namespace
        Map<?, ?> businessFrame = null;
        if (!meta.functionValid || !meta.namespaceValid) {
            businessFrame = findBusinessFrame(source);
        }

        // --------------------------------------------------
        // FINALIZE FUNCTION & NAMESPACE
        // --------------------------------------------------
        applyBusinessFrame(meta, businessFrame);

        if (!meta.functionValid) {
            meta.function = meta.namespaceValid ? defaultFunctionForNamespace(meta.namespace) : "process";
        }

        if (!meta.namespaceValid) {
            meta.namespace = UNKNOWN;
        }

        dto.setCodeFunction(meta.function);
        dto.setCodeNamespace(meta.namespace);
    }

    /** Mutable working state for {@link #extractCodeMetadata}. */
    private static final class CodeMetadata {
        String function;
        String namespace;
        boolean functionValid;
        boolean namespaceValid;
    }

    /** Returns the first valid string among {@code paths}, probed in order, or null. */
    private static String firstValidString(Map<String, Object> source, String... paths) {
        for (String path : paths) {
            String value = extractValidString(source, path);
            if (value != null)
                return value;
        }
        return null;
    }

    /** Derives function (and possibly namespace) from the log message when function is missing. */
    private static void applyMessageHints(CodeMetadata meta, String msgObj) {
        if (meta.functionValid || msgObj == null)
            return;

        Matcher mTime = Pattern.compile("Execution time for (\\S+) was \\d+ ms for method: (\\S+)").matcher(msgObj);
        if (mTime.find()) {
            meta.namespace = mTime.group(1);
            meta.namespaceValid = true;
            meta.function = mTime.group(2);
            meta.functionValid = true;
        } else {
            Matcher mMethod = Pattern
                    .compile("method[:=]\\s*([^\\s,]+)|method name:\\s*([^\\s,]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(msgObj);
            if (mMethod.find()) {
                meta.function = mMethod.group(mMethod.group(1) != null ? 1 : 2);
                meta.functionValid = true;
            }
        }
    }

    /** Fills missing function / namespace from the business stack frame, if any. */
    private static void applyBusinessFrame(CodeMetadata meta, Map<?, ?> businessFrame) {
        if (businessFrame == null)
            return;
        if (!meta.functionValid) {
            String frameFunc = getFrameString(businessFrame, FUNCTION);
            if (isValidValue(frameFunc)) {
                meta.function = frameFunc;
                meta.functionValid = true;
            }
        }
        if (!meta.namespaceValid) {
            String frameClass = getFrameString(businessFrame, CLASSNAME, CLASS_NAME);
            if (isValidValue(frameClass)) {
                meta.namespace = frameClass;
                meta.namespaceValid = true;
            }
        }
    }

    /** Infers a conventional function name from the class-name suffix. */
    private static String defaultFunctionForNamespace(String rootNamespace) {
        for (SuffixRule rule : FUNCTION_BY_CLASS_SUFFIX) {
            if (endsWithAny(rootNamespace, rule.suffixes()))
                return rule.function();
        }
        return "process";
    }

    private static String extractValidString(Map<String, Object> source, String path) {
        Object val = getRobustValue(source, path);
        if (val instanceof String str) {
            return isValidValue(str) ? str : null;
        }
        return val != null && isValidValue(String.valueOf(val)) ? String.valueOf(val) : null;
    }

    private static boolean isValidValue(String val) {
        return val != null && !val.trim().isEmpty() && !UNKNOWN.equalsIgnoreCase(val.trim());
    }

    @SuppressWarnings("java:S1168")
    private static Map<?, ?> findBusinessFrame(Map<String, Object> source) {
        return searchForBusinessFrameRecursively(source);
    }

    @SuppressWarnings("java:S1168")
    private static Map<?, ?> searchForBusinessFrameRecursively(Object node) {
        if (node instanceof Map) {
            return searchMapForBusinessFrame((Map<?, ?>) node);
        } else if (node instanceof List) {
            return searchListForBusinessFrame((List<?>) node);
        }
        return null;
    }

    @SuppressWarnings("java:S1168")
    private static Map<?, ?> searchMapForBusinessFrame(Map<?, ?> map) {
        for (Object value : map.values()) {
            Map<?, ?> frame = searchContainerForBusinessFrame(value);
            if (frame != null)
                return frame;
        }
        return null;
    }

    @SuppressWarnings("java:S1168")
    private static Map<?, ?> searchListForBusinessFrame(List<?> list) {
        // First try to see if the list itself contains frames
        Map<?, ?> frame = extractBusinessFrameFromList(list);
        if (frame != null)
            return frame;

        // Otherwise recurse into items
        for (Object item : list) {
            Map<?, ?> childFrame = searchContainerForBusinessFrame(item);
            if (childFrame != null)
                return childFrame;
        }
        return null;
    }

    /** Recurses into {@code value} only when it is a List or Map; otherwise null. */
    @SuppressWarnings("java:S1168")
    private static Map<?, ?> searchContainerForBusinessFrame(Object value) {
        if (value instanceof List || value instanceof Map)
            return searchForBusinessFrameRecursively(value);
        return null;
    }

    @SuppressWarnings("java:S1168")
    private static Map<?, ?> extractBusinessFrameFromList(List<?> list) {
        if (list == null || list.isEmpty())
            return null;

        // Scan for the first business frame
        for (Object item : list) {
            if (isFrameLike(item)) {
                Map<?, ?> frame = (Map<?, ?>) item;
                String className = getFrameString(frame, CLASSNAME, CLASS_NAME, "className");
                if (className != null && isBusinessClass(className)) {
                    return frame;
                }
            }
        }
        return null;
    }

    private static boolean isFrameLike(Object item) {
        if (!(item instanceof Map<?, ?> map))
            return false;
        boolean hasClass = containsAnyKey(map, FRAME_CLASS_KEYS);
        boolean hasMethod = containsAnyKey(map, FRAME_METHOD_KEYS);
        boolean hasLine = containsAnyKey(map, FRAME_LINE_KEYS);
        boolean hasFile = containsAnyKey(map, FRAME_FILE_KEYS);
        return hasClass && (hasMethod || hasLine || hasFile);
    }

    private static boolean containsAnyKey(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            if (map.containsKey(key))
                return true;
        }
        return false;
    }

    private static boolean isBusinessClass(String className) {
        if (className == null)
            return false;

        // Explicitly include our own packages first
        if (className.startsWith("com.trinity.")) {
            return isOwnBusinessClass(className);
        }

        String lower = className.toLowerCase();

        return !className.contains("$$SpringCGLIB$$")
                && !className.contains("$Proxy")
                && !startsWithAny(lower, NON_BUSINESS_PREFIXES);
    }

    /** One of our own classes counts unless it is a CGLIB / proxy wrapper. */
    private static boolean isOwnBusinessClass(String className) {
        String lower = className.toLowerCase();
        return !className.contains("$$") && !lower.contains("cglib") && !lower.contains("proxy");
    }

    private static String getFrameString(Map<?, ?> frame, String... keys) {
        for (String key : keys) {
            Object val = frame.get(key);
            if (val != null)
                return String.valueOf(val);
        }
        return null;
    }
}
