package com.trinity.observability.util;

import com.trinity.observability.dto.traces.TraceLogEvent;
import com.trinity.observability.dto.traces.TraceResponseDTO;
import com.trinity.observability.dto.traces.TraceSummary;
import com.trinity.observability.dto.traces.TraceTreeNode;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a UI-ready hierarchical execution tree from a flat span list.
 */
public class TraceTreeBuilder {

    private static final long SLOW_THRESHOLD_US = 1_000_000L; // 1000ms
    private static final int MAX_MSG_LENGTH = 300;

    private static final Set<String> INFRA_NOISE = new HashSet<>(Arrays.asList(
        "CLIENT SETINFO", "AUTH", "HELLO", "PING", "SELECT", "COMMAND"
    ));

    private static final String TYPE_DATABASE = "DATABASE";
    private static final String TYPE_INTERNAL = "INTERNAL";
    private static final String TYPE_MONGODB = "MONGODB";
    private static final String TYPE_HTTP = "HTTP";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_WARN = "WARN";
    private static final String STATUS_DEBUG = "DEBUG";
    private static final String STATUS_INFO = "INFO";
    private static final String UNKNOWN = "UNKNOWN";

    // normalizeType: upper-cased span-type fragments that mark an HTTP span / a relational DB span
    private static final String[] HTTP_TYPE_MARKERS = {"HTTP", "SERVER", "CLIENT"};
    private static final String[] SQL_TYPE_MARKERS = {"MSSQL", "SQLSERVER", "MYSQL", "POSTGRES", "ORACLE"};

    private static final Pattern[] MASK_PATTERNS = {
        Pattern.compile("(?i)(Bearer\\s+)[\\w\\-.~+/]+=*",              Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(Authorization[:\\s=]+)[^\\s,;\"']+",       Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(jwt[:\\s=]+)[\\w\\-.~+/]+=*",            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(session[_-]?id[:\\s=]+)[^\\s,;\"']+",     Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(cookie[:\\s=]+)[^\\n]+",                   Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(token[:\\s=]+)[\\w\\-.~+/]{20,}",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("([\\w.%+-])([\\w.%+-]*)@([\\w.-]+\\.[a-zA-Z]{2,})"),
        Pattern.compile("(\\d{2})(\\d+)(\\d{2})"),
        Pattern.compile("[0-9a-fA-F]{32,}"),
    };

    private TraceTreeBuilder() {}

    public static TraceTreeNode build(List<TraceResponseDTO> spans) {
        if (spans == null || spans.isEmpty()) return null;
        try {
            List<TraceResponseDTO> sorted = spans.stream()
                    .sorted(Comparator.comparing(TraceResponseDTO::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            List<TraceResponseDTO> meaningful = sorted.stream()
                    .filter(s -> !isNoise(s))
                    .toList();

            TraceResponseDTO rootSpan = findRoot(meaningful);
            if (rootSpan == null && !sorted.isEmpty()) rootSpan = sorted.get(0);
            if (rootSpan == null) return null;

            final TraceResponseDTO finalRoot = rootSpan;
            TraceTreeNode root = toNode(finalRoot);
            
            root.setRequestMethod(finalRoot.getHttpMethod());
            root.setRequestPath(finalRoot.getTarget());
            root.setRequestLabel(cleanRequestLabel(finalRoot.getActualOperation()));
            if (finalRoot.getDuration() != null) {
                root.setTotalDurationMs(normalizeDurationMs(finalRoot.getDuration()));
            }

            root.setTimestamp(finalRoot.getTimestamp());
            root.setStartTime(finalRoot.getTimestamp());
            root.setRootCategory(determineRootCategory(finalRoot));

            List<TraceResponseDTO> childSpans = meaningful.stream()
                    .filter(s -> s != finalRoot).toList();

            List<TraceTreeNode> children = groupSpans(childSpans, finalRoot.getServiceName());
            children.sort(Comparator.comparing(TraceTreeNode::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())));
            root.setChildren(children);
            
            root.setStatus(mapHttpStatus(finalRoot.getHttpStatus(), root.getStatus()));
            return root;

        } catch (Exception e) {
            return null;
        }
    }

    public static TraceSummary buildSummary(TraceTreeNode root) {
        if (root == null) return null;
        try {
            TraceSummary summary = new TraceSummary();
            summary.setTotalDurationMs(root.getTotalDurationMs());

            Map<String, Integer> byType = new LinkedHashMap<>();
            Map<String, Integer> bySeverity = new LinkedHashMap<>();
            int[] counters = {0, 0, 0, 0, 0}; // totalGroups, totalLogs, totalDbCalls, warnings, errors

            walkNodes(root, byType, bySeverity, counters);

            summary.setTotalGroups(counters[0]);
            summary.setTotalLogs(counters[1]);
            summary.setTotalDbCalls(counters[2]);
            summary.setWarnings(counters[3]);
            summary.setErrors(counters[4]);
            
            summary.setCountsByType(byType.isEmpty() ? null : byType);
            summary.setCountsBySeverity(bySeverity.isEmpty() ? null : bySeverity);
            return summary;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<TraceTreeNode> groupSpans(List<TraceResponseDTO> childSpans, String rootServiceName) {
        List<TraceTreeNode> children = new ArrayList<>();
        Set<TraceResponseDTO> categorized = new HashSet<>();

        // Database
        Map<String, List<TraceResponseDTO>> dbGroups = childSpans.stream()
                .filter(s -> TYPE_DATABASE.equals(s.getType()))
                .collect(Collectors.groupingBy(
                        s -> s.getDatabaseType() != null ? s.getDatabaseType().toLowerCase() : "unknown",
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<TraceResponseDTO>> e : dbGroups.entrySet()) {
            children.add(buildDatabaseGroup(e.getKey(), e.getValue(), rootServiceName));
            categorized.addAll(e.getValue());
        }

        // External
        List<TraceResponseDTO> outbound = filter(childSpans, s -> TYPE_HTTP.equals(s.getType()) && "OUTGOING".equals(s.getDirection()));
        if (!outbound.isEmpty()) {
            children.add(buildExternalGroup(outbound));
            categorized.addAll(outbound);
        }

        // Internal
        List<TraceResponseDTO> remaining = childSpans.stream().filter(s -> !categorized.contains(s)).toList();
        if (!remaining.isEmpty()) {
            children.add(buildInternalGroup(remaining, rootServiceName));
        }

        return children;
    }

    private static TraceTreeNode buildDatabaseGroup(String dbType, List<TraceResponseDTO> spans, String rootSvc) {
        TraceTreeNode group = new TraceTreeNode();
        group.setType(normalizeDbType(dbType));
        group.setName(formatDbGroupName(dbType));
        group.setCount(spans.size());
        long totalUs = sumDurationsUs(spans);
        group.setTotalDuration(totalUs);
        group.setDurationMs(normalizeDurationMs(totalUs));
        group.setServiceName(resolveServiceName(spans, rootSvc));

        List<TraceTreeNode> kids = spans.stream().map(s -> {
            TraceTreeNode n = toNode(s);
            n.setTarget(s.getTarget());
            n.setIsSlow(s.getDuration() != null && s.getDuration() > SLOW_THRESHOLD_US);
            return n;
        }).toList();

        group.setChildren(kids);
        group.setStatus(aggregateStatus(kids, Collections.emptyList()));
        return group;
    }

    private static TraceTreeNode buildExternalGroup(List<TraceResponseDTO> spans) {
        TraceTreeNode group = new TraceTreeNode();
        group.setType("EXTERNAL");
        group.setName("External API Calls");
        group.setCount(spans.size());
        long totalUs = sumDurationsUs(spans);
        group.setTotalDuration(totalUs);
        group.setDurationMs(normalizeDurationMs(totalUs));
        group.setServiceName(resolveServiceName(spans, null));

        List<TraceTreeNode> kids = spans.stream().map(s -> {
            TraceTreeNode n = toNode(s);
            n.setIsSlow(s.getDuration() != null && s.getDuration() > SLOW_THRESHOLD_US);
            return n;
        }).toList();

        group.setChildren(kids);
        group.setStatus(aggregateStatus(kids, Collections.emptyList()));
        return group;
    }

    private static TraceTreeNode buildInternalGroup(List<TraceResponseDTO> spans, String rootSvc) {
        TraceTreeNode group = new TraceTreeNode();
        group.setType(TYPE_INTERNAL);
        group.setName("Internal Processing");
        group.setCount(spans.size());
        long totalUs = sumDurationsUs(spans);
        group.setTotalDuration(totalUs);
        group.setDurationMs(normalizeDurationMs(totalUs));
        group.setServiceName(resolveServiceName(spans, rootSvc));

        List<TraceTreeNode> kids = spans.stream().map(TraceTreeBuilder::toNode).toList();
        group.setChildren(kids);
        group.setStatus(aggregateStatus(kids, Collections.emptyList()));
        return group;
    }

    public static void finalizeGroups(TraceTreeNode root) {
        if (root == null) return;
        try {
            moveRootEventsToInternalGroup(root);

            // Walk every node (not just top-level groups) and convert
            // attached events into LOG TreeNode children. Logs that the
            // correlator matched to a deep span by spanId previously stayed
            // as `events` arrays and never reached the tree; converting them
            // here is what makes the UI's "Logs by span" view show logs
            // under the span where they actually fired.
            convertEventsToLogNodesRecursively(root, root.getStartTime());

            refreshGroupStatuses(root);
            escalateRootStatus(root);

            maskAllRecursively(root);

        } catch (Exception ignored) {
            // Ignored by design: errors during finalization should not abort tree return
        }
    }

    /** Moves events attached to the root onto the INTERNAL group (created if absent), time-ordered. */
    private static void moveRootEventsToInternalGroup(TraceTreeNode root) {
        List<TraceLogEvent> rootEvents = root.getEvents();
        if (rootEvents == null || rootEvents.isEmpty()) return;
        TraceTreeNode internalGroup = findGroupByType(root, TYPE_INTERNAL);
        if (internalGroup == null) {
            internalGroup = buildInternalGroup(Collections.emptyList(), root.getServiceName());
            root.getChildren().add(internalGroup);
        }
        List<TraceLogEvent> existing = internalGroup.getEvents();
        if (existing == null) existing = new ArrayList<>();
        existing.addAll(rootEvents);
        existing.sort(Comparator.comparing(TraceLogEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        internalGroup.setEvents(existing);
        root.setEvents(new ArrayList<>());
    }

    private static void refreshGroupStatuses(TraceTreeNode root) {
        for (TraceTreeNode group : root.getChildren()) {
            String status = aggregateStatus(group.getChildren(), group.getEvents());
            if (TYPE_INTERNAL.equals(group.getType())) status = aggregateInternalStatus(group.getEvents(), status);
            group.setStatus(status);
        }
    }

    /** A SUCCESS root takes its children's WARN/ERROR; any other root status is kept. */
    private static void escalateRootStatus(TraceTreeNode root) {
        String finalStatus = aggregateStatus(root.getChildren(), Collections.emptyList());
        if (STATUS_SUCCESS.equals(root.getStatus()) && (STATUS_WARN.equals(finalStatus) || STATUS_ERROR.equals(finalStatus))) {
            root.setStatus(finalStatus);
        }
    }

    /**
     * Recursively walk the tree and convert each node's attached `events`
     * into LOG-type TreeNode children. The correlator targets events to
     * whichever node best matches (deep span by spanId, time-containment,
     * keyword, or root) — but historically only top-level groups had their
     * events promoted to children, so logs that matched real deep spans
     * stayed invisible to the UI. This walk fixes that.
     *
     * Skips LOG nodes during recursion (they have no events of their own
     * and were just created by this walk). Only group wrappers (no spanId,
     * pre-existing count field) have their count updated to reflect the
     * new total — real spans keep their own count semantics.
     */
    private static void convertEventsToLogNodesRecursively(TraceTreeNode node, String rootStart) {
        if (node == null) return;

        List<TraceLogEvent> events = node.getEvents();
        if (events != null && !events.isEmpty()) {
            attachEventsAsLogNodes(node, events, rootStart);
        }

        List<TraceTreeNode> kids = node.getChildren();
        if (kids != null) {
            // Snapshot — we may have just appended LOG nodes; recursing into
            // them would be wasted work (they have no events).
            List<TraceTreeNode> snapshot = new ArrayList<>(kids);
            for (TraceTreeNode child : snapshot) {
                if (!"LOG".equalsIgnoreCase(child.getType())) {
                    convertEventsToLogNodesRecursively(child, rootStart);
                }
            }
        }
    }

    /** Replaces the node's events with their processed form and merges them in as LOG children. */
    private static void attachEventsAsLogNodes(TraceTreeNode node, List<TraceLogEvent> events, String rootStart) {
        List<TraceLogEvent> processed = processEvents(events, rootStart);
        node.setEvents(processed);

        List<TraceTreeNode> logNodes = processed.stream()
                .map(TraceTreeBuilder::toLogNode)
                .toList();

        List<TraceTreeNode> existing = node.getChildren();
        if (existing == null) existing = new ArrayList<>();
        List<TraceTreeNode> allKids = new ArrayList<>(existing);
        allKids.addAll(logNodes);
        allKids.sort(Comparator.comparing(TraceTreeNode::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        node.setChildren(allKids);

        // Only group wrappers (spanId-less nodes that already carried a
        // count) display a child count in the UI. Don't tag real spans
        // with a count or the UI's isVirtualLibGroup heuristic would
        // misclassify them.
        if (node.getSpanId() == null && node.getCount() != null) {
            node.setCount(allKids.size());
        }
    }

    private static List<TraceLogEvent> processEvents(List<TraceLogEvent> raw, String rootStart) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<TraceLogEvent> res = new ArrayList<>();
        int seq = 1;
        TraceLogEvent last = null;
        for (TraceLogEvent cur : raw) {
            applyMessagePreview(cur);
            cur.setRelativeTimeMs(computeRelativeMsDouble(cur.getTimestamp(), rootStart));
            cur.setSequence(seq++);

            if (last != null && isDuplicateLog(cur, last)) {
                last.setCount(last.getCount() + 1);
            } else {
                res.add(cur);
                last = cur;
            }
        }
        return res;
    }

    /** Keeps the full message and sets a preview, truncating message and preview past MAX_MSG_LENGTH. */
    private static void applyMessagePreview(TraceLogEvent cur) {
        String msg = cur.getMessage();
        if (msg == null) return;
        cur.setFullMessage(msg);
        if (msg.length() > MAX_MSG_LENGTH) {
            cur.setMessagePreview(msg.substring(0, MAX_MSG_LENGTH) + "...");
            cur.setMessage(cur.getMessagePreview());
            cur.setIsTruncated(true);
        } else {
            cur.setMessagePreview(msg);
            cur.setIsTruncated(false);
        }
    }

    private static boolean isDuplicateLog(TraceLogEvent cur, TraceLogEvent last) {
        if (!Objects.equals(cur.getSeverity(), last.getSeverity())) return false;
        String m1 = cur.getFullMessage();
        String m2 = last.getFullMessage();
        if (m1 == null || m2 == null) return false;
        if (m1.equals(m2)) return true;
        // Host/Port noise collapse
        return (m1.contains("URI Host") && m2.contains("URI Host"))
                || (m1.contains("URI Port") && m2.contains("URI Port"));
    }

    private static TraceTreeNode toLogNode(TraceLogEvent e) {
        TraceTreeNode ln = new TraceTreeNode();
        ln.setType("LOG");
        // Carry the owning span's ID so the UI's "Logs by span" view can
        // place this log under its real parent span. Without it, the LOG
        // node looks like a span-less leaf and the hierarchy collapses.
        ln.setSpanId(e.getSpanId());
        ln.setName(e.getMessage());
        ln.setOperation(e.getFullMessage());
        ln.setSeverity(e.getSeverity());
        ln.setStatus(e.getSeverity());
        ln.setServiceName(e.getServiceName());
        ln.setDurationMs(e.getRelativeDurationMs());
        ln.setRelativeTimeMs(e.getRelativeTimeMs());
        ln.setSequence(e.getSequence());
        ln.setMessagePreview(e.getMessagePreview());
        ln.setFullMessage(e.getFullMessage());
        ln.setIsTruncated(e.getIsTruncated());
        ln.setStartTime(e.getTimestamp());
        ln.setCount(e.getCount());
        // Carry the OTel code.* source-location onto the LOG node so the UI's
        // trace-log row can render "ClassName.method():lineno" without having
        // to flip back to the logs tab to find where the line came from.
        ln.setClassName(e.getClassName());
        ln.setMethodName(e.getMethodName());
        ln.setFilePath(e.getFilePath());
        ln.setLineNumber(e.getLineNumber());
        // Carry exception attributes (already secret-masked in the correlator)
        // so the trace inspector can render the full stack trace for error logs.
        ln.setExceptionType(e.getExceptionType());
        ln.setExceptionMessage(e.getExceptionMessage());
        ln.setStackTrace(e.getStackTrace());
        return ln;
    }

    private static TraceTreeNode toNode(TraceResponseDTO span) {
        TraceTreeNode node = new TraceTreeNode();
        node.setSpanId(span.getSpanId());
        // Carry parent linkage from the underlying trace store. The category
        // groupSpans() rearrangement discards the natural span hierarchy, so
        // without parentId on each leaf the UI cannot rebuild a real tree.
        node.setParentId(span.getParentSpanId());
        node.setType(normalizeType(span.getType()));
        node.setName(span.getSpanName());
        node.setStatus(deriveStatus(span.getStatus()));
        node.setOperation(span.getActualOperation() != null ? span.getActualOperation() : span.getSpanName());
        node.setServiceName(span.getServiceName());
        node.setStartTime(span.getTimestamp());
        Long us = span.getDuration();
        node.setDuration(us);
        if (us != null) {
            Double ms = us / 1000.0;
            node.setDurationMs(ms);
            if (span.getTimestamp() != null) node.setEndTime(computeEndTime(span.getTimestamp(), (long)ms.doubleValue()));
            node.setIsSlow(us > SLOW_THRESHOLD_US);
        }
        // Mirror type-specific fields onto the tree node so the UI's inspector
        // pane and tree row see them. Without this, the lib extracted these
        // fields in OoTraceInterpreter but dropped them on the floor before
        // they could reach the tree response.
        node.setHttpMethod(span.getHttpMethod());
        node.setHttpStatus(span.getHttpStatus());
        node.setHttpRequestBody(span.getHttpRequestBody());
        node.setHttpResponseBody(span.getHttpResponseBody());
        node.setEndpoint(span.getEndpoint());
        node.setDbStatement(span.getDbStatement());
        node.setDatabaseType(span.getDatabaseType());
        node.setTarget(span.getTarget());
        node.setDirection(span.getDirection());
        node.setThreadName(span.getThreadName());
        return node;
    }

    public static String aggregateStatus(List<TraceTreeNode> childNodes, List<TraceLogEvent> events) {
        int level = 0; // SUCCESS:0, INFO:1, DEBUG:2, WARN:3, ERROR:4
        if (childNodes != null) {
            for (TraceTreeNode n : childNodes) level = Math.max(level, statusLevel(n.getStatus()));
        }
        if (events != null) {
            for (TraceLogEvent e : events) level = Math.max(level, severityLevel(e.getSeverity()));
        }
        return mapLevelToStatus(level);
    }

    private static String aggregateInternalStatus(List<TraceLogEvent> events, String currentStatus) {
        if (events == null || events.isEmpty()) return currentStatus;
        boolean onlyInfo = events.stream().allMatch(e -> "INFO".equalsIgnoreCase(e.getSeverity()));
        return onlyInfo ? STATUS_SUCCESS : currentStatus;
    }

    private static String mapHttpStatus(Integer code, String currentStatus) {
        if (code == null) return currentStatus;
        if (code >= 500) return STATUS_ERROR;
        if (code >= 400) return "WARN";
        return (code >= 200 && code < 400) ? STATUS_SUCCESS : currentStatus;
    }

    private static int statusLevel(String s) {
        if (s == null) return 0;
        switch (s.toUpperCase()) {
            case "FATAL", STATUS_ERROR: return 4;
            case "WARN", "WARNING": return 3;
            case STATUS_DEBUG: return 2;
            case "INFO":  return 1;
            default:      return 0;
        }
    }

    private static int severityLevel(String s) {
        if (s == null) return 0;
        switch (s.toUpperCase()) {
            case "FATAL", STATUS_ERROR: return 4;
            case "WARN", "WARNING": return 3;
            case STATUS_DEBUG, "TRACE": return 2;
            case "INFO":                return 1;
            default:                    return 0;
        }
    }

    private static String mapLevelToStatus(int level) {
        switch (level) {
            case 4: return STATUS_ERROR;
            case 3: return STATUS_WARN;
            case 2: return STATUS_DEBUG;
            case 1: return STATUS_INFO;
            default: return STATUS_SUCCESS;
        }
    }

    public static String maskSensitiveData(String input) {
        if (input == null) return null;
        String res = input;
        for (Pattern p : MASK_PATTERNS) {
            res = p.matcher(res).replaceAll(mr -> {
                try {
                    if (mr.groupCount() >= 3) return mr.group(1) + "***" + mr.group(3);
                    return mr.group(1) + "[REDACTED]";
                } catch (Exception e) { return "[REDACTED]"; }
            });
        }
        return res;
    }

    private static void maskAllRecursively(TraceTreeNode node) {
        if (node == null) return;
        node.setName(maskSensitiveData(node.getName()));
        node.setOperation(maskSensitiveData(node.getOperation()));
        node.setMessagePreview(maskSensitiveData(node.getMessagePreview()));
        node.setFullMessage(maskSensitiveData(node.getFullMessage()));
        if (node.getChildren() != null) {
            for (TraceTreeNode child : node.getChildren()) maskAllRecursively(child);
        }
    }

    private static void walkNodes(TraceTreeNode node, Map<String, Integer> byType, Map<String, Integer> bySeverity, int[] counters) {
        if (node == null) return;
        String t = node.getType() != null ? node.getType().toUpperCase() : UNKNOWN;
        byType.merge(t.toLowerCase(), 1, Integer::sum);
        
        if (isDbSummaryType(t)) counters[2] += dbCallCount(node);

        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            counters[0]++;
            for (TraceTreeNode child : node.getChildren()) walkNodes(child, byType, bySeverity, counters);
        }
        if (node.getEvents() != null) {
            countEvents(node.getEvents(), bySeverity, counters);
        }
    }

    /** A DB group counts one call per child; a lone DB span counts as one. */
    private static int dbCallCount(TraceTreeNode node) {
        return node.getChildren() != null ? node.getChildren().size() : 1;
    }

    private static boolean isDbSummaryType(String t) {
        return TYPE_MONGODB.equals(t) || TYPE_DATABASE.equals(t) || "MSSQL".equals(t);
    }

    /** counters[1] = events, [3] = warnings, [4] = errors; bySeverity is per lower-cased severity. */
    private static void countEvents(List<TraceLogEvent> events, Map<String, Integer> bySeverity, int[] counters) {
        for (TraceLogEvent e : events) {
            counters[1]++;
            String sev = e.getSeverity() != null ? e.getSeverity().toLowerCase() : "info";
            bySeverity.merge(sev, 1, Integer::sum);
            if ("error".equals(sev) || "fatal".equals(sev)) counters[4]++;
            else if ("warn".equals(sev) || "warning".equals(sev)) counters[3]++;
        }
    }

    private static String determineRootCategory(TraceResponseDTO span) {
        if (span == null) return "BUSINESS_REQUEST";
        String name = span.getSpanName() != null ? span.getSpanName().toUpperCase() : "";
        if (INFRA_NOISE.contains(name) || name.contains("HEALTH") || name.contains("PING")) return "INFRASTRUCTURE";
        return "BUSINESS_REQUEST";
    }

    private static String cleanRequestLabel(String label) { return label == null ? null : label.replace("Inbound HTTP: ", ""); }

    public static Double normalizeDurationMs(long us) { return us / 1000.0; }

    private static Double computeRelativeMsDouble(String ts, String start) {
        if (ts == null || start == null) return null;
        try {
            long tsMs = Instant.parse(normalizeTimestamp(ts)).toEpochMilli();
            long startMs = Instant.parse(normalizeTimestamp(start)).toEpochMilli();
            return (double)(tsMs - startMs);
        } catch (Exception e) { return null; }
    }

    private static String normalizeTimestamp(String ts) {
        if (ts == null) return null;
        ts = ts.trim();
        if (ts.contains("T") && (ts.endsWith("Z") || ts.contains("+"))) return ts;
        try { return Instant.ofEpochMilli((long) Double.parseDouble(ts)).toString(); } catch (Exception e) { return null; }
    }

    private static String computeEndTime(String start, long ms) {
        try { return Instant.parse(normalizeTimestamp(start)).plusMillis(ms).toString(); } catch (Exception e) { return null; }
    }

    public static String normalizeType(String raw) {
        if (raw == null) return TYPE_INTERNAL;
        String u = raw.toUpperCase();
        if (containsAny(u, HTTP_TYPE_MARKERS)) return TYPE_HTTP;
        if (u.contains("MONGO")) return TYPE_MONGODB;
        if (containsAny(u, SQL_TYPE_MARKERS)) return TYPE_DATABASE;
        return TYPE_INTERNAL;
    }

    /** True when {@code text} contains at least one of {@code markers}, checked in order. */
    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    private static String normalizeDbType(String dbType) {
        if (dbType == null) return TYPE_DATABASE;
        String l = dbType.toLowerCase();
        if (l.contains("mongo")) return TYPE_MONGODB;
        if (l.contains("sql") || l.contains("postgres") || l.contains("oracle")) return TYPE_DATABASE;
        return TYPE_DATABASE;
    }

    private static String formatDbGroupName(String dbType) {
        if (dbType == null) return "Database Queries";
        String l = dbType.toLowerCase();
        if (l.contains("mongo")) return "MongoDB Queries";
        return (l.contains("sql") || l.contains("postgres") || l.contains("oracle")) ? "SQL Queries" : dbType.toUpperCase() + " Queries";
    }

    private static long sumDurationsUs(List<TraceResponseDTO> spans) { return spans.stream().filter(s -> s.getDuration() != null).mapToLong(TraceResponseDTO::getDuration).sum(); }

    private static String resolveServiceName(List<TraceResponseDTO> spans, String fallback) {
        if (spans != null) { for (TraceResponseDTO s : spans) { if (s.getServiceName() != null && !s.getServiceName().isEmpty()) return s.getServiceName(); } }
        return fallback;
    }

    private static TraceTreeNode findGroupByType(TraceTreeNode root, String type) {
        if (root.getChildren() == null) return null;
        return root.getChildren().stream().filter(n -> type.equals(n.getType())).findFirst().orElse(null);
    }

    private static TraceResponseDTO findRoot(List<TraceResponseDTO> spans) { return spans.stream().filter(s -> TYPE_HTTP.equals(s.getType()) && "INCOMING".equals(s.getDirection())).findFirst().orElse(null); }

    private static boolean isNoise(TraceResponseDTO span) {
        if (span == null || span.getSpanName() == null) return false;
        String n = span.getSpanName().toUpperCase();
        return INFRA_NOISE.contains(n) || n.contains("HEALTH") || n.contains("PING");
    }

    private static String deriveStatus(String raw) {
        if (raw == null) return UNKNOWN;
        String l = raw.toLowerCase();
        if (l.contains("success") || l.contains("completed") || l.contains(" ok")) {
            return STATUS_SUCCESS;
        }
        if (l.contains("error") || l.contains("fail")) {
            return STATUS_ERROR;
        }
        return UNKNOWN;
    }

    private static List<TraceResponseDTO> filter(List<TraceResponseDTO> list, java.util.function.Predicate<TraceResponseDTO> pred) { return list.stream().filter(pred).toList(); }
}
