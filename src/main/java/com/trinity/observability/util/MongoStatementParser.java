package com.trinity.observability.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Single-responsibility: parse a raw MongoDB BSON/JSON statement string
 * into a human-readable English description.
 *
 * Examples:
 *  {"find": "AHU", "filter": {"tenantCode":"?"}, ...}
 *      → "Find from AHU where tenantCode = ?"
 *  {"aggregate": "AHU", "pipeline": [...]}
 *      → "Aggregate on AHU (pipeline)"
 *  {"ping": 1}
 *      → "Ping database"
 */
public class MongoStatementParser {

    private static final Logger log = LogManager.getLogger(MongoStatementParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String FIELD_FILTER = "filter";

    private MongoStatementParser() {}

    /**
     * Converts a raw MongoDB statement JSON string to a human-readable operation.
     * Falls back to a generic description using operation + collection if parsing fails.
     */
    public static String parse(String statement, String operation, String collection) {
        if (statement != null && statement.trim().startsWith("{")) {
            try {
                JsonNode root = mapper.readTree(statement);
                String result = parseNode(root);
                if (result != null) return result;
            } catch (Exception e) {
                log.warn("Failed to parse MongoDB statement JSON");
            }
        }
        // Fallback: build from operation + collection labels
        if (operation != null && collection != null) {
            return capitalize(operation) + " from " + collection;
        }
        return statement != null ? statement : "MongoDB operation";
    }

    // ── private ───────────────────────────────────────────────────────────────

    private static String parseNode(JsonNode root) {
        if (root.has("ping"))       return "Ping database";
        if (root.has("find"))       return parseFindNode(root);
        if (root.has("aggregate"))  return "Aggregate on " + root.get("aggregate").asText() + " (pipeline)";
        if (root.has("update"))     return "Update " + root.get("update").asText();
        if (root.has("delete"))     return "Delete from " + root.get("delete").asText();
        if (root.has("insert"))     return "Insert into " + root.get("insert").asText();
        return null;
    }

    private static String parseFindNode(JsonNode root) {
        String collection = root.get("find").asText();
        StringBuilder sb = new StringBuilder("Find from ").append(collection);
        if (root.has(FIELD_FILTER) && root.get(FIELD_FILTER).isObject()) {
            String where = parseFilter(root.get(FIELD_FILTER));
            if (where != null && !where.isEmpty()) sb.append(" where ").append(where);
        }
        return sb.toString();
    }

    private static String parseFilter(JsonNode filterNode) {
        StringBuilder sb = new StringBuilder();
        var fields = filterNode.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (!sb.isEmpty()) sb.append(" AND ");
            if (value.isObject()) {
                var ops = value.fields();
                if (ops.hasNext()) {
                    var op = ops.next();
                    sb.append(key).append(" ").append(mapOperator(op.getKey(), op.getValue()));
                } else {
                    sb.append(key).append(" = ").append(value.asText());
                }
            } else {
                sb.append(key).append(" = ").append(value.asText());
            }
        }
        return sb.toString();
    }

    private static String mapOperator(String op, JsonNode val) {
        switch (op) {
            case "$in":  return "IN (" + (val.isArray() ? "..." : val.asText()) + ")";
            case "$eq":  return "= "  + val.asText();
            case "$gt":  return "> "  + val.asText();
            case "$lt":  return "< "  + val.asText();
            case "$ne":  return "!= " + val.asText();
            default:     return op + " " + val.asText();
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
