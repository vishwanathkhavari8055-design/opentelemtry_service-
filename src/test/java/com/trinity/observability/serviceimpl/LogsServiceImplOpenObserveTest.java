package com.trinity.observability.serviceimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.logs.LogRequestDTO;
import com.trinity.observability.dto.logs.LogResponseDTO;
import com.trinity.observability.dto.logs.LogServicesResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsServiceImplOpenObserveTest {

    private RecordingClient client;
    private OpenObserveConfig config;
    private LogsServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingClient();
        config = new OpenObserveConfig();
        setField(config, "host", "http://localhost:15080");
        setField(config, "org", "default");
        setField(config, "authToken", "Basic test");
        setField(config, "logsStream", "default");
        setField(config, "tracesStream", "default");

        ElasticsearchClientService esClient = new EsClientThatNeverGetsCalled();

        service = new LogsServiceImpl(esClient, client, config);
        setField(service, "openObserveEnabled", true);
    }

    @Test
    void testSingleServicePathBuildsExpectedSql() {
        client.respondWith(List.of(
            hit(1778045072_000_000L, "INFO",  "first")
        ));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("order-service");
        req.setPage(0);
        req.setSize(10);

        List<LogResponseDTO> out = service.getLogs(req).getItems();
        assertEquals(1, out.size());

        String sql = client.lastSql.get();
        assertTrue(sql.contains("FROM \"default\""), "uses configured logs stream: " + sql);
        // Single-service path now uses IN(...) for uniformity with the new
        // multi-service filter; semantically equivalent to = for one entry.
        assertTrue(sql.contains("service_name IN ('order-service')"), "filters by service: " + sql);
        assertTrue(sql.contains("ORDER BY _timestamp DESC"), "sorts desc: " + sql);
        assertFalse(sql.contains("LIMIT"), "no SQL LIMIT (uses body from/size): " + sql);
        assertEquals("logs", client.lastStreamType.get());
        assertEquals(0,  client.lastFrom.get().intValue());
        // Phase 2: backend fetches size+1 to derive hasMore (issue #8).
        assertEquals(11, client.lastSize.get().intValue());
    }

    @Test
    void testSeverityFilterMatchesSeverityColumn() {
        client.respondWith(List.of(hit(1L, "WARN", "msg")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSeverity("WARN");
        req.setSize(5);

        service.getLogs(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("severity IN ('WARN')"),
            "Filter only severity column. SQL: " + sql);
        assertFalse(sql.contains("level IN"),
            "level column must not be used: " + sql);
    }

    @Test
    void testSeverityAtLeastModeExpandsToHigherLevels() {
        client.respondWith(List.of(hit(1L, "WARN", "msg")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSeverity("WARN");
        req.setSeverityMode("AT_LEAST");
        req.setSize(5);

        service.getLogs(req);

        String sql = client.lastSql.get();
        // Issue #14: AT_LEAST WARN should match WARN, ERROR, FATAL.
        assertTrue(sql.contains("'WARN'"),  "AT_LEAST WARN includes WARN: "  + sql);
        assertTrue(sql.contains("'ERROR'"), "AT_LEAST WARN includes ERROR: " + sql);
        assertTrue(sql.contains("'FATAL'"), "AT_LEAST WARN includes FATAL: " + sql);
        assertFalse(sql.contains("'INFO'"), "AT_LEAST WARN excludes INFO: "  + sql);
    }

    @Test
    void testSortAscendingHonored() {
        client.respondWith(List.of(hit(1L, "INFO", "msg")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSort("timestamp,asc");
        req.setSize(5);

        service.getLogs(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("ORDER BY _timestamp ASC"), "sort=timestamp,asc honored: " + sql);
    }

    @Test
    void testUnknownSortFieldFallsBackToDefault() {
        client.respondWith(List.of(hit(1L, "INFO", "msg")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSort("dropTable; --,evil");
        req.setSize(5);

        service.getLogs(req);

        String sql = client.lastSql.get();
        // Whitelist must reject unknown fields/directions and fall back to the default.
        assertTrue(sql.contains("ORDER BY _timestamp DESC"), "unknown sort field falls back: " + sql);
        assertFalse(sql.toLowerCase().contains("droptable"), "user input must not reach SQL: " + sql);
    }

    @Test
    void testSeverityAllSkipsTheFilter() {
        client.respondWith(List.of(hit(1L, "INFO", "msg")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSeverity("ALL");

        service.getLogs(req);

        String sql = client.lastSql.get();
        // Check for the severity FILTER specifically ("AND severity IN (...)"),
        // not a bare "severity IN" — the stacktrace projection now legitimately
        // emits `CASE WHEN severity IN ('ERROR','FATAL','SEVERE')`, which is a
        // SELECT-list expression, not a row filter.
        assertFalse(sql.contains("AND severity IN"), "ALL severity must not produce a filter: " + sql);
    }

    @Test
    void testInjectionAttemptInServiceNameRejected() {
        client.respondWith(List.of());

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("evil'; DROP TABLE; --");

        // SqlSafety.validateIdentifier should throw before any client call
        try {
            service.getLogs(req);
        } catch (IllegalArgumentException expected) {
            // OK
            return;
        }
        // If no exception was thrown, the SQL must NOT contain the injection
        assertNull(client.lastSql.get(),
            "Either reject upfront with IllegalArgumentException OR never call the client");
    }

    @Test
    void testSearchTermLikeEscapingActive() {
        client.respondWith(List.of());

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSearch("100% O'Brien_data");

        service.getLogs(req);

        String sql = client.lastSql.get();
        // %, _, ', \ must all be escaped
        assertTrue(sql.contains("LIKE '%100\\% O''Brien\\_data%' ESCAPE '\\'"),
            "LIKE pattern must escape wildcards and quotes. SQL: " + sql);
    }

    @Test
    void testSearchMatchesMultipleColumns() {
        // Phase 3 (issue #10): search should OR across body, service_name,
        // trace_id, severity, scope name, and code attributes — same
        // columns the UI used to re-filter client-side.
        client.respondWith(List.of());

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSearch("hello");

        service.getLogs(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("body LIKE '%hello%'"),                          "body included: "       + sql);
        assertTrue(sql.contains("service_name LIKE '%hello%'"),                  "service_name: "        + sql);
        assertTrue(sql.contains("trace_id LIKE '%hello%'"),                      "trace_id: "            + sql);
        assertTrue(sql.contains("severity LIKE '%hello%'"),                      "severity: "            + sql);
        assertFalse(sql.contains("level LIKE"),                                  "level excluded: "      + sql);
        assertTrue(sql.contains("code_namespace LIKE '%hello%'"),                "code_namespace: "      + sql);
        assertTrue(sql.contains("code_function LIKE '%hello%'"),                 "code_function: "       + sql);
        assertTrue(sql.contains("instrumentation_library_name LIKE '%hello%'"),  "scope name: "          + sql);
    }

    @Test
    void testTraceIdAndOptionalFiltersApplied() {
        client.respondWith(List.of());

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setHostName("host-1");
        req.setOsType("linux");
        req.setScopeName("logback");
        req.setTraceId("abc123");

        service.getLogs(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("AND host_name = 'host-1'"),                    sql);
        assertTrue(sql.contains("AND os_type = 'linux'"),                       sql);
        assertTrue(sql.contains("AND instrumentation_library_name = 'logback'"), sql);
        assertTrue(sql.contains("AND trace_id = 'abc123'"),                     sql);
    }

    @Test
    void testDtoMapperReadsFlatHits() {
        Map<String, Object> hitWithSeverity = new HashMap<>();
        hitWithSeverity.put("_timestamp", 1778045072_123456L);
        hitWithSeverity.put("body", "test from severity");
        hitWithSeverity.put("severity", "ERROR");
        hitWithSeverity.put("service_name", "svc");
        hitWithSeverity.put("host_name", "h1");
        hitWithSeverity.put("trace_id", "t1");

        client.respondWith(List.of(hitWithSeverity));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");

        List<LogResponseDTO> out = service.getLogs(req).getItems();
        assertEquals(1, out.size());

        LogResponseDTO dto = out.get(0);
        assertEquals("ERROR", dto.getSeverity());
        assertEquals("2026-05-06T05:24:32.123456Z", dto.getTimestamp(), "µs precision preserved");
        assertEquals("svc",  dto.getServiceName());
        assertEquals("h1",   dto.getHostName());
        assertEquals("t1",   dto.getTraceId());
        assertNotNull(dto.getMessage(), "body propagated");
    }

    @Test
    void testInfoNoiseFilteredOutByLogNormalizer() {
        Map<String, Object> noise = new HashMap<>();
        noise.put("_timestamp", 1L);
        noise.put("body", "Cookie value: session=abc");
        noise.put("severity", "INFO");
        noise.put("service_name", "svc");

        Map<String, Object> real = new HashMap<>();
        real.put("_timestamp", 2L);
        real.put("body", "Order processed");
        real.put("severity", "INFO");
        real.put("service_name", "svc");

        client.respondWith(List.of(noise, real));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");

        List<LogResponseDTO> out = service.getLogs(req).getItems();
        assertEquals(1, out.size(), "noise filtered, real log retained");
    }

    @Test
    void testSelectIncludesExceptionColumnsWithGatedStacktrace() {
        client.respondWith(List.of(hit(1L, "INFO", "x")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        service.getLogs(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("exception_type, exception_message,"),
                "short exception cols selected for all rows: " + sql);
        // The heavy stacktrace is gated to error-class severities so OO doesn't
        // ship ~19-36 KB on every TRACE/INFO row.
        assertTrue(sql.contains("CASE WHEN severity IN ('ERROR', 'FATAL', 'SEVERE') "
                        + "THEN exception_stacktrace ELSE NULL END AS exception_stacktrace"),
                "stacktrace gated to error-class via CASE: " + sql);
        // Searchable by exception class/message, but never LIKE-scanned on the
        // 19 KB stacktrace.
        assertFalse(sql.contains("exception_stacktrace LIKE"),
                "stacktrace excluded from search LIKE: " + sql);
    }

    @Test
    void testMapperReadsExceptionFieldsUntruncated() {
        // Build a realistic long stacktrace (well over the old 150-char message cap).
        StringBuilder st = new StringBuilder("java.lang.NullPointerException: boom\n");
        for (int i = 0; i < 200; i++) {
            st.append("\tat com.trinity.foo.Bar.baz(Bar.java:").append(i).append(")\n");
        }
        String stacktrace = st.toString();

        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp", 1778045072_000_000L);
        h.put("severity", "ERROR");
        h.put("body", "request failed");
        h.put("service_name", "svc");
        h.put("exception_type", "java.lang.NullPointerException");
        h.put("exception_message", "boom");
        h.put("exception_stacktrace", stacktrace);

        client.respondWith(List.of(h));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");

        LogResponseDTO dto = service.getLogs(req).getItems().get(0);
        assertEquals("java.lang.NullPointerException", dto.getExceptionType());
        assertEquals("boom", dto.getExceptionMessage());
        assertNotNull(dto.getStackTrace(), "stacktrace propagated");
        assertEquals(stacktrace.length(), dto.getStackTrace().length(),
                "stacktrace must NOT be truncated");
    }

    @Test
    void testLongMessageNotTruncated() {
        // 400-char non-ERROR body — previously truncated to 150 + "..." by
        // LogNormalizer; now the full body is sent and clamped in the UI.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) body.append("0123456789");
        String longBody = body.toString();

        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp", 2L);
        h.put("severity", "INFO");
        h.put("body", longBody);
        h.put("service_name", "svc");

        client.respondWith(List.of(h));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");

        LogResponseDTO dto = service.getLogs(req).getItems().get(0);
        assertEquals(longBody.length(), dto.getMessage().length(),
                "long message must not be truncated");
        assertFalse(dto.getMessage().endsWith("..."), "no truncation ellipsis: " + dto.getMessage());
    }

    @Test
    void testStacktraceSecretsMasked() {
        String stacktrace = """
                java.lang.RuntimeException: auth failed
                \tcontext: password=hunter2 token continues
                \tat com.trinity.Foo.bar(Foo.java:1)""";

        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp", 3L);
        h.put("severity", "ERROR");
        h.put("body", "auth failed");
        h.put("service_name", "svc");
        h.put("exception_stacktrace", stacktrace);

        client.respondWith(List.of(h));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");

        LogResponseDTO dto = service.getLogs(req).getItems().get(0);
        assertFalse(dto.getStackTrace().contains("hunter2"), "password masked in stacktrace");
    }

    @Test
    void testAllServicesPathIssuesSingleUnifiedQuery() {
        // Phase 1 (issue #3 + #4): the fan-out was replaced with a single SQL
        // query that omits the service_name filter. Discovery is irrelevant on
        // this path now — populate it anyway to confirm we don't accidentally
        // re-introduce per-service queries.
        client.discoverServicesResponse = List.of("alpha", "beta", "gamma");
        client.respondWith(List.of(
            hit(1778000000_000_003L, "INFO", "from any svc — c"),
            hit(1778000000_000_002L, "INFO", "from any svc — b"),
            hit(1778000000_000_001L, "INFO", "from any svc — a")
        ));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("ALL");
        req.setPage(0);
        req.setSize(5);

        List<LogResponseDTO> out = service.getLogs(req).getItems();

        assertEquals(1, client.searchInvocationCount.get(),
            "ALL-services must issue exactly one search call (no fan-out)");
        String sql = client.lastSql.get();
        assertFalse(sql.contains("service_name IN ("),
            "ALL-services SQL must omit the service_name filter: " + sql);
        assertEquals(3, out.size(), "all hits returned, sorted by backend");
    }

    @Test
    void testAllServicesPaginationDelegatedToBackend() {
        // The backend now handles LIMIT/OFFSET — verify we pass the right
        // (from, size) regardless of how many services exist. Phase 2 adds
        // the size+1 trick so the upstream response can carry hasMore.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(String.format("svc%02d", i));
        client.discoverServicesResponse = many;
        client.respondWith(List.of(hit(1L, "INFO", "x")));

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("ALL");
        req.setPage(3);
        req.setSize(10);

        service.getLogs(req);

        assertEquals(1, client.searchInvocationCount.get(),
            "single query regardless of service count (no MAX_FANOUT_SERVICES cap)");
        assertEquals(30, client.lastFrom.get().intValue(), "from = page * size");
        assertEquals(11, client.lastSize.get().intValue(),
            "fetch size+1 to derive hasMore (issue #8)");
    }

    @Test
    void testHasMoreTrueWhenBackendReturnsExtraRow() {
        // Backend returns size+1 rows → hasMore=true, response trimmed to size.
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            hits.add(hit(1778000000_000_000L + i, "INFO", "row-" + i));
        }
        client.respondWith(hits);

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSize(5);

        com.trinity.observability.dto.logs.LogPageResponseDTO page = service.getLogs(req);

        assertTrue(page.isHasMore(), "got size+1 rows → hasMore must be true");
        assertEquals(5, page.getItems().size(), "items must be trimmed to requested size");
    }

    @Test
    void testHasMoreFalseWhenBackendReturnsExactlySizeRows() {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            hits.add(hit(1778000000_000_000L + i, "INFO", "row-" + i));
        }
        client.respondWith(hits);

        LogRequestDTO req = new LogRequestDTO();
        req.setServiceName("svc");
        req.setSize(5);

        com.trinity.observability.dto.logs.LogPageResponseDTO page = service.getLogs(req);

        assertFalse(page.isHasMore(), "got <= size rows → hasMore must be false");
        assertEquals(5, page.getItems().size());
    }

    @Test
    void testTraceGroupsReturnsServerSideCountsAndTopSeverity() {
        // Backend returns three traces ordered by latest_ts DESC, each with
        // a count, CASE-ranked top severity, and the distinct services that
        // emitted any log in the trace (sorted alphabetically by the mapper).
        client.respondWith(List.of(
            traceGroupHitWithServices("trace-a", 12L, 1778000000_010_000L, 1778000000_500_000L, 4,
                List.of("order-service", "iotops-cim-service")),
            traceGroupHitWithServices("trace-b",  3L, 1778000000_100_000L, 1778000000_300_000L, 3,
                List.of("iotops-cim-service")),
            traceGroupHitWithServices("trace-c",  7L, 1778000000_001_000L, 1778000000_050_000L, 2,
                List.of("payment-service"))
        ));

        LogRequestDTO req = new LogRequestDTO();
        req.setSize(10);

        com.trinity.observability.dto.logs.LogTraceSummaryPageDTO page = service.getTraceGroups(req);

        assertEquals(3, page.getItems().size(), "all three traces returned");
        assertEquals("trace-a", page.getItems().get(0).getTraceId());
        assertEquals(12L,       page.getItems().get(0).getLogCount());
        assertEquals("ERROR",   page.getItems().get(0).getTopSeverity());
        assertEquals("WARN",    page.getItems().get(1).getTopSeverity());
        assertEquals("INFO",    page.getItems().get(2).getTopSeverity());
        // Services list de-duplicated and sorted alphabetically:
        assertEquals(List.of("iotops-cim-service", "order-service"),
                     page.getItems().get(0).getServices());
        assertEquals(List.of("iotops-cim-service"),
                     page.getItems().get(1).getServices());
        assertFalse(page.isHasMore(), "fewer than size+1 → hasMore=false");
    }

    @Test
    void testTraceGroupsHasMoreWhenBackendReturnsExtraTrace() {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            hits.add(traceGroupHit("t-" + i, i + 1L, 1L, 100L, 2));
        }
        client.respondWith(hits);

        LogRequestDTO req = new LogRequestDTO();
        req.setSize(10);

        com.trinity.observability.dto.logs.LogTraceSummaryPageDTO page = service.getTraceGroups(req);

        assertTrue(page.isHasMore(), "got size+1 traces → hasMore=true");
        assertEquals(10, page.getItems().size(), "items trimmed to size");
    }

    @Test
    void testTraceGroupsSqlShapeNoSearch() {
        client.respondWith(List.of());

        LogRequestDTO req = new LogRequestDTO();
        req.setSize(10);
        service.getTraceGroups(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("GROUP BY trace_id"),               "uses GROUP BY: "                       + sql);
        assertTrue(sql.contains("COUNT(*) AS log_count"),           "matching count = total when no search: "+ sql);
        assertTrue(sql.contains("COUNT(*) AS total_log_count"),     "always emits total_log_count: "         + sql);
        assertTrue(sql.contains("MAX(_timestamp) AS latest_ts"),    "tracks latest activity per trace: "    + sql);
        assertTrue(sql.contains("MIN(_timestamp) AS earliest_ts"),  "tracks earliest activity per trace: "  + sql);
        assertTrue(sql.contains("trace_id IS NOT NULL"),            "skips trace-less rows: "               + sql);
        assertTrue(sql.contains("CASE"),                            "uses CASE-rank for top severity: "     + sql);
        assertTrue(sql.contains("array_agg(DISTINCT service_name)"),"collects distinct services per trace: "+ sql);
        assertTrue(sql.contains("ORDER BY MAX(_timestamp) DESC"),   "orders by latest activity: "           + sql);
        assertFalse(sql.contains("HAVING"),                         "no HAVING when search is empty: "      + sql);
        assertFalse(sql.contains("FILTER (WHERE"),                  "no FILTER clause when search is empty: " + sql);
    }

    @Test
    void testTraceGroupsSqlShapeWithSearchUsesFilterAndHaving() {
        // When search is active, the matching count uses FILTER (WHERE …)
        // so the OTHER aggregates can still summarize the whole trace, and
        // HAVING drops traces with zero matches.
        client.respondWith(List.of());

        LogRequestDTO req = new LogRequestDTO();
        req.setSize(10);
        req.setSearch("OutOfMemory");
        service.getTraceGroups(req);

        String sql = client.lastSql.get();
        assertTrue(sql.contains("COUNT(*) FILTER (WHERE"),
            "matching count uses FILTER predicate: " + sql);
        assertTrue(sql.contains("COUNT(*) AS total_log_count"),
            "total still computed over the whole trace: " + sql);
        assertTrue(sql.contains("HAVING COUNT(*) FILTER (WHERE"),
            "HAVING drops traces with zero matches: " + sql);
        // Search predicate must NOT also be in the outer WHERE — that would
        // restrict the OTHER aggregates and defeat the whole point.
        // (We check this indirectly: the SQL contains the term inside FILTER
        //  and inside HAVING, but the outer WHERE has no LIKE clause.)
        int firstWhere = sql.indexOf("WHERE");
        int firstFilter = sql.indexOf("FILTER (WHERE");
        // The FILTER (WHERE …) sits inside the SELECT list, before the outer
        // WHERE keyword. Just confirming both keywords appear:
        assertTrue(firstFilter >= 0 && firstWhere > firstFilter,
            "FILTER appears in SELECT before the outer WHERE: " + sql);
    }

    @Test
    void testTraceGroupsReportsMatchingAndTotal() {
        // Backend returns 5 matches out of 190 total for one trace.
        client.respondWith(List.of(
            traceGroupHitFull("trace-x", 5L, 190L, 1L, 100L, 4, List.of("svc-a"))
        ));

        LogRequestDTO req = new LogRequestDTO();
        req.setSize(10);
        req.setSearch("OutOfMemory");

        com.trinity.observability.dto.logs.LogTraceSummaryPageDTO page = service.getTraceGroups(req);

        assertEquals(1, page.getItems().size());
        assertEquals(5L,   page.getItems().get(0).getLogCount());
        assertEquals(190L, page.getItems().get(0).getTotalLogCount());
    }

    // ---------- listServices ----------

    @Test
    void listServicesDefaultsToA24HourWindowAndCountsNonEmptyNames() {
        Map<String, Object> a = new HashMap<>();
        a.put("service_name", "order-svc");
        a.put("log_count", 5L);
        Map<String, Object> blank = new HashMap<>();
        blank.put("service_name", "");
        blank.put("log_count", 9L);
        client.respondWith(List.of(a, blank));

        LogServicesResponseDTO out = service.listServices(null, null, null);

        assertTrue(out.isSupported());
        assertEquals("openobserve", out.getBackend());
        assertEquals(List.of("order-svc"), out.getItems());
        assertEquals(1, out.getServices().size());
        assertEquals(5L, out.getServices().get(0).getCount());
        String sql = client.lastSql.get();
        assertEquals(86_400_000_000L, sqlBound(sql, "_timestamp <= ") - sqlBound(sql, "_timestamp >= "),
                "default window spans 24h: " + sql);
        assertNotNull(out.getWindowStart());
        assertNotNull(out.getWindowEnd());
    }

    @Test
    void listServicesHonoursAnExplicitWindowExpression() {
        service.listServices(" now-2h ", " ", null);

        String sql = client.lastSql.get();
        assertEquals(7_200_000_000L, sqlBound(sql, "_timestamp <= ") - sqlBound(sql, "_timestamp >= "),
                "window expression spans 2h: " + sql);
    }

    @Test
    void listServicesExplicitRangeWinsOverWindow() {
        LogServicesResponseDTO out = service.listServices("now-7d",
                " 2026-01-01T00:00:00Z ", "2026-01-01T01:00:00Z");

        String sql = client.lastSql.get();
        assertEquals(1_767_225_600_000_000L, sqlBound(sql, "_timestamp >= "), sql);
        assertEquals(1_767_229_200_000_000L, sqlBound(sql, "_timestamp <= "), sql);
        assertTrue(out.getWindowStart().startsWith("2026-01-01T00:00"), out.getWindowStart());
        assertTrue(out.getWindowEnd().startsWith("2026-01-01T01:00"), out.getWindowEnd());
    }

    @Test
    void listServicesExplicitStartWithoutEndRunsToNow() {
        long before = java.time.Instant.now().getEpochSecond() * 1_000_000L;

        service.listServices(null, "2026-01-01T00:00:00Z", null);

        String sql = client.lastSql.get();
        assertEquals(1_767_225_600_000_000L, sqlBound(sql, "_timestamp >= "), sql);
        assertTrue(sqlBound(sql, "_timestamp <= ") >= before, sql);
    }

    @Test
    void listServicesReportsUnsupportedOnTheElasticsearchPath() throws Exception {
        setField(service, "openObserveEnabled", false);

        LogServicesResponseDTO out = service.listServices(null, null, null);

        assertFalse(out.isSupported());
        assertEquals("elasticsearch", out.getBackend());
        assertTrue(out.getItems().isEmpty());
        assertNull(client.lastSql.get());
    }

    @Test
    void listServicesSwallowsAQueryFailure() {
        client.searchHandler = (sql, from, size, streamType) -> {
            throw new IllegalStateException("boom");
        };

        LogServicesResponseDTO out = service.listServices(null, null, null);

        assertTrue(out.isSupported());
        assertTrue(out.getItems().isEmpty());
        assertTrue(out.getServices().isEmpty());
    }

    /** The numeric literal that follows {@code marker} in {@code sql}. */
    private static long sqlBound(String sql, String marker) {
        int start = sql.indexOf(marker) + marker.length();
        int end = sql.indexOf(' ', start);
        return Long.parseLong(sql.substring(start, end));
    }

    // ---------- helpers ----------

    private static Map<String, Object> traceGroupHit(String traceId, long count,
                                                     long earliestMicros, long latestMicros, int rank) {
        return traceGroupHitWithServices(traceId, count, earliestMicros, latestMicros, rank, List.of());
    }

    private static Map<String, Object> traceGroupHitWithServices(String traceId, long count,
                                                                 long earliestMicros, long latestMicros,
                                                                 int rank, List<String> services) {
        // Without an explicit total, mock backends seed total_log_count = count
        // (the no-search-active case where every log in the trace matches).
        return traceGroupHitFull(traceId, count, count, earliestMicros, latestMicros, rank, services);
    }

    private static Map<String, Object> traceGroupHitFull(String traceId, long matchingCount, long totalCount,
                                                         long earliestMicros, long latestMicros,
                                                         int rank, List<String> services) {
        Map<String, Object> h = new HashMap<>();
        h.put("trace_id", traceId);
        h.put("log_count", matchingCount);
        h.put("total_log_count", totalCount);
        h.put("earliest_ts", earliestMicros);
        h.put("latest_ts", latestMicros);
        h.put("top_rank", rank);
        h.put("services", services);
        return h;
    }

    private static Map<String, Object> hit(long tsMicros, String severity, String body) {
        Map<String, Object> h = new HashMap<>();
        h.put("_timestamp", tsMicros);
        h.put("severity", severity);
        h.put("body", body);
        h.put("service_name", "stub-svc");
        return h;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    /** Captures the last search call args; supports either a fixed response or a per-call handler. */
    private static class RecordingClient implements OpenObserveClientService {
        // getJson was added to OpenObserveClientService for the org, IAM,
        // trace-analytics and PromQL endpoints. These doubles exercise the
        // search() paths only, so an empty object satisfies the contract
        // without handing callers a null to dereference.
        @Override
        public JsonNode getJson(String path) {
            return JsonNodeFactory.instance.objectNode();
        }

        // Write-path methods, added to OpenObserveClientService for the alerts API
        // and the alert-event stream. Every double here exercises READ paths only,
        // so a write arriving at one means the code under test took a path it was
        // not meant to. Throwing surfaces that; returning an empty node would let
        // the test pass while hiding it.
        @Override
        public JsonNode sendJson(String method, String path, JsonNode body) {
            throw new UnsupportedOperationException("sendJson is not expected on this path");
        }

        @Override
        public JsonNode deleteJson(String path) {
            throw new UnsupportedOperationException("deleteJson is not expected on this path");
        }

        @Override
        public JsonNode ingest(String org, String streamName, JsonNode records) {
            throw new UnsupportedOperationException("ingest is not expected on this path");
        }

        final AtomicReference<String>  lastSql        = new AtomicReference<>();
        final AtomicReference<Integer> lastFrom       = new AtomicReference<>();
        final AtomicReference<Integer> lastSize       = new AtomicReference<>();
        final AtomicReference<String>  lastStreamType = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger searchInvocationCount = new java.util.concurrent.atomic.AtomicInteger();
        List<Map<String, Object>> fixedResponse = List.of();
        List<String> discoverServicesResponse = List.of();
        SearchHandler searchHandler;

        void respondWith(List<Map<String, Object>> hits) { this.fixedResponse = hits; }

        @Override
        public synchronized List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                                                int from, int size, String streamType) {
            lastSql.set(sql);
            lastFrom.set(from);
            lastSize.set(size);
            lastStreamType.set(streamType);
            searchInvocationCount.incrementAndGet();
            if (searchHandler != null) return searchHandler.handle(sql, from, size, streamType);
            return fixedResponse;
        }

        @Override
        public List<String> discoverServices() { return discoverServicesResponse; }

        @Override
        public List<String> listMetricStreams() { return List.of(); }
    }

    @FunctionalInterface
    private interface SearchHandler {
        List<Map<String, Object>> handle(String sql, int from, int size, String streamType);
    }

    /** Stand-in for ES client that explodes if called — proves the OO path is exercised. */
    private static class EsClientThatNeverGetsCalled implements ElasticsearchClientService {
        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> search(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                int from, int size, String sortField,
                co.elastic.clients.elasticsearch._types.SortOrder order, Class<T> clazz) {
            throw new AssertionError("ES client should not be invoked when openObserveEnabled=true");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <T> co.elastic.clients.elasticsearch.core.SearchResponse<T> searchWithAggregation(
                String index, co.elastic.clients.elasticsearch._types.query_dsl.Query query,
                java.util.Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggs,
                int size, Class<T> clazz) {
            throw new AssertionError("ES client should not be invoked when openObserveEnabled=true");
        }
    }
}
