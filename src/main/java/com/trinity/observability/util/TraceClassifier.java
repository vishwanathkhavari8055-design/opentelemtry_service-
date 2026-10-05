package com.trinity.observability.util;

import java.util.Map;

/**
 * Single-responsibility: determines the TYPE of a raw APM trace span.
 *
 * Rule precedence (from the APM data contract):
 *  1. processor.event == "transaction"                → HTTP
 *  2. labels.graphql_operation_type present & non-null → GRAPHQL
 *  3. span.type == "db"                               → DATABASE
 *  4. everything else                                 → INTERNAL
 */
public class TraceClassifier {

    public enum SpanType { HTTP_INCOMING, HTTP_OUTGOING, GRAPHQL, DATABASE, INTERNAL }

    private TraceClassifier() {}

    public static SpanType classify(Map<String, Object> source) {
        String processorEvent = MappingUtil.getStringValue(source, "processor.event");
        String spanType       = MappingUtil.getStringValue(source, "span.type");
        String spanSubtype    = MappingUtil.getStringValue(source, "span.subtype");
        String graphqlOpType  = MappingUtil.getStringValue(source, "labels.graphql_operation_type");

        // 1. Incoming HTTP (service entry)
        if ("transaction".equals(processorEvent)) {
            return SpanType.HTTP_INCOMING;
        }

        // 2. Outgoing HTTP (external call)
        if ("external".equals(spanType) || "http".equalsIgnoreCase(spanType) || "http".equalsIgnoreCase(spanSubtype)) {
            return SpanType.HTTP_OUTGOING;
        }

        // 3. GraphQL (usually identified by labels)
        if (graphqlOpType != null && !graphqlOpType.isEmpty()) {
            return SpanType.GRAPHQL;
        }

        // 4. Database
        if ("db".equals(spanType)) {
            return SpanType.DATABASE;
        }

        return SpanType.INTERNAL;
    }

    private static final String DB_NEO4J = "neo4j";
    private static final String DB_MONGODB = "mongodb";
    private static final String DB_REDIS = "redis";
    private static final String DB_MSSQL = "mssql";

    /**
     * Resolves the specific database technology from span metadata.
     * Returns one of: mongodb, neo4j, redis, sql
     */
    public static String resolveDbTechnology(Map<String, Object> source) {
        // 1. Explicit label (highest trust)
        String labelDbSystem = MappingUtil.getStringValue(source, "labels.db_type", "labels.db_system");
        if (DB_NEO4J.equalsIgnoreCase(labelDbSystem)) return DB_NEO4J;

        // 2. span.subtype / service.target.type
        String rawDbType = MappingUtil.getStringValue(source, "span.subtype", "service.target.type", "span.db.type");
        String declared = declaredDbTechnology(rawDbType, labelDbSystem);
        if (declared != null) return declared;

        String inferred = inferredDbTechnology(source);
        if (inferred != null) return inferred;

        return rawDbType != null ? rawDbType : "sql";
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** Step 2 of {@link #resolveDbTechnology}: the declared type, then the MSSQL label; null when neither decides. */
    private static String declaredDbTechnology(String rawDbType, String labelDbSystem) {
        if (DB_MONGODB.equalsIgnoreCase(rawDbType)) return DB_MONGODB;
        if (DB_NEO4J.equalsIgnoreCase(rawDbType))   return DB_NEO4J;
        if (DB_REDIS.equalsIgnoreCase(rawDbType))   return DB_REDIS;

        // Also check explicit label for MSSQL
        if (isMssql(rawDbType) || isMssql(labelDbSystem)) return DB_MSSQL;
        return null;
    }

    private static boolean isMssql(String dbType) {
        return DB_MSSQL.equalsIgnoreCase(dbType) || "sqlserver".equalsIgnoreCase(dbType);
    }

    /** Steps 3-5 of {@link #resolveDbTechnology}: technology inferred from the span's content; null when unknown. */
    private static String inferredDbTechnology(Map<String, Object> source) {
        // 3. Redis detection via span name
        String spanName = MappingUtil.getStringValue(source, "span.name");
        if (isRedisCommand(spanName)) return DB_REDIS;

        // 4. MongoDB via presence of collection label
        if (MappingUtil.getStringValue(source, "labels.db_mongodb_collection") != null) return DB_MONGODB;
        String connStr = MappingUtil.getStringValue(source, "labels.db_connection_string");
        if (connStr != null && connStr.contains(DB_MONGODB)) return DB_MONGODB;

        // 5. Neo4j via Cypher syntax
        String stmt = DbStatementResolver.find(source);
        if (isNeo4jStatement(stmt)) return DB_NEO4J;
        return null;
    }

    private static final java.util.Set<String> REDIS_CMDS = java.util.Set.of(
            "HGET", "EXISTS", "GET", "SET", "DEL", "HSET", "PUBLISH",
            "EXPIRE", "TTL", "INCR", "DECR", "LPUSH", "RPUSH", "LRANGE");

    private static boolean isRedisCommand(String name) {
        if (name == null) return false;
        return REDIS_CMDS.contains(name.split(" ")[0].toUpperCase());
    }

    private static boolean isNeo4jStatement(String stmt) {
        if (stmt == null) return false;
        String upper = stmt.trim().toUpperCase();
        return upper.startsWith("MATCH") || upper.startsWith("CREATE")
                || upper.startsWith("MERGE") || upper.startsWith("CYPHER");
    }
}
