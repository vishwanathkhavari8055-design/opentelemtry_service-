package com.trinity.observability.constants;

public class AppConstants {
    public static final String LOGS_INDEX = ".ds-logs-*,logs-*";
    public static final String METRICS_INDEX = ".ds-metrics-*,.ds-logs-apm*";
    public static final String TRACES_INDEX = ".ds-traces-*,traces-*";

    public static final String TIMESTAMP_FIELD = "@timestamp";

    // ── Summary / uptime defaults ────────────────────────────────────────────
    // The heartbeat stream is the metric whose presence within the liveness
    // window means "the service is alive". jvm_memory_used is emitted by every
    // OTel-instrumented JVM every 10 s, so any service down for >60 s will
    // stop appearing.
    public static final String HEARTBEAT_METRIC_STREAM = "jvm_memory_used";
    // Optional uptime-in-seconds source. The OTel agent emits this when the
    // process metrics instrumentation is on; absent rigs gracefully degrade
    // (the response field is left null).
    public static final String PROCESS_UPTIME_STREAM   = "process_uptime";
    // Spring Boot Actuator's /health surfaced as a Micrometer gauge,
    // scraped by the OTel Collector's prometheus/actuator receiver. Value
    // is 1.0 for UP, 0.0 for anything else. Absent stream means no service
    // in this lab exposes the health gauge yet — degrades to null.
    public static final String APPLICATION_HEALTH_STREAM = "application_health";
    // Defaults for /api/services/uptime — short window so the discovery query
    // stays cheap, threshold sized at 6× the 10 s OTel push interval to absorb
    // jitter without false DOWNs.
    public static final String DEFAULT_UPTIME_WINDOW   = "now-1h";
    public static final int    DEFAULT_LIVENESS_SEC    = 60;

    // Summary window defaults for the Summary screen aggregation endpoints.
    public static final String DEFAULT_SUMMARY_WINDOW  = "now-1h";

    // Sparkline streams for /api/metrics/summary. Streams use OTel semantic-
    // convention names emitted natively by the OTel Java agent (the primary
    // metric source in this lab). The collector's prometheus/actuator scrape
    // lands the older Micrometer-style names (process_cpu_usage, jvm_threads_live,
    // http_server_requests_seconds_count) alongside, but those rows are tagged
    // with the scrape job name rather than service.name, so a per-service
    // summary query against them returns nothing.
    public static final String SPARK_JVM_HEAP_STREAM        = "jvm_memory_used";
    public static final String SPARK_CPU_USAGE_STREAM       = "jvm_cpu_recent_utilization";
    public static final String SPARK_THREADS_LIVE_STREAM    = "jvm_thread_count";
    public static final String SPARK_HTTP_REQS_COUNT_STREAM = "http_server_request_duration_count";

    // ── Service vitals streams ───────────────────────────────────────────────
    // /api/services/{name}/vitals fans out to these Actuator-derived streams.
    // Any of them may be absent for a given service (e.g. Hikari isn't on the
    // classpath, error log appender uses a different name) — the service
    // implementation is defensive and degrades each tile independently.
    public static final String VITALS_HTTP_REQ_COUNT_STREAM   = "http_server_request_duration_count";
    public static final String VITALS_HTTP_REQ_MAX_STREAM     = "http_server_request_duration_max";
    public static final String VITALS_HIKARI_ACTIVE_STREAM    = "hikaricp_connections_active";
    public static final String VITALS_JVM_MEMORY_USED_STREAM  = "jvm_memory_used";
    public static final String VITALS_LOGBACK_EVENTS_STREAM   = "log4j2_events_total";
    public static final String VITALS_HTTP_CLIENT_COUNT_STREAM = "http_client_request_duration_count";
    public static final String VITALS_HTTP_CLIENT_MAX_STREAM   = "http_client_request_duration_max";

    /** Default bucket width (seconds) for vitals sparklines. */
    public static final int    DEFAULT_VITALS_BUCKET_SEC = 60;

    private AppConstants() {
        // Private constructor to prevent instantiation
    }
}
