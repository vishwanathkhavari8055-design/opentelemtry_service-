package com.trinity.observability.clientimpl;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.exception.OpenObserveQueryException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenObserveClientImplTest {

    private MockWebServer server;
    private OpenObserveClientImpl impl;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();

        RestClient client = RestClient.builder()
            .baseUrl(server.url("/").toString().replaceAll("/$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic dGVzdDp0ZXN0")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        OpenObserveConfig config = new OpenObserveConfig();
        setField(config, "host",          server.url("/").toString());
        setField(config, "org",           "default");
        setField(config, "authToken",     "Basic dGVzdDp0ZXN0");
        setField(config, "logsStream",    "default");
        setField(config, "tracesStream",  "default");

        impl = new OpenObserveClientImpl(client, config);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void testSearchParsesHitsArray() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"hits\": [" +
                     "{\"_timestamp\": 1778047130081764, \"body\": \"first\",  \"service_name\": \"svc-a\"}," +
                     "{\"_timestamp\": 1778047130081765, \"body\": \"second\", \"service_name\": \"svc-b\"}" +
                     "], \"total\": 2}"));

        List<Map<String, Object>> hits = impl.search(
            "SELECT * FROM \"default\"", 1L, 2L, 0, 10, "logs");

        assertEquals(2, hits.size());
        assertEquals("first",  hits.get(0).get("body"));
        assertEquals("svc-a",  hits.get(0).get("service_name"));
        assertEquals(1778047130081764L, ((Number) hits.get(0).get("_timestamp")).longValue());
        assertEquals("second", hits.get(1).get("body"));
    }

    @Test
    void testSearchSendsCorrectBody() throws Exception {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"hits\": []}"));

        impl.search("SELECT 1", 1000L, 2000L, 5, 25, "traces");

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/default/_search?type=traces", req.getPath());

        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"sql\":\"SELECT 1\""), "body: " + body);
        assertTrue(body.contains("\"start_time\":1000"), "body: " + body);
        assertTrue(body.contains("\"end_time\":2000"),   "body: " + body);
        assertTrue(body.contains("\"from\":5"),           "body: " + body);
        assertTrue(body.contains("\"size\":25"),          "body: " + body);
    }

    @Test
    void testSearchAuthHeaderPassesThrough() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"hits\": []}"));
        impl.search("SELECT 1", 0L, 1L, 0, 1, "logs");

        RecordedRequest req = server.takeRequest();
        assertEquals("Basic dGVzdDp0ZXN0", req.getHeader("Authorization"));
    }

    @Test
    void testSearchEmptyHitsReturnsEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"hits\": []}"));

        List<Map<String, Object>> hits = impl.search("SELECT 1", 0L, 1L, 0, 10, "logs");
        assertNotNull(hits);
        assertEquals(0, hits.size());
    }

    @Test
    void testSearchNon2xxMappedToOpenObserveQueryException() {
        server.enqueue(new MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":20004,\"message\":\"Search field not found\",\"trace_id\":\"019dfbe0\"}"));

        OpenObserveQueryException ex = assertThrows(OpenObserveQueryException.class,
            () -> impl.search("SELECT bogus FROM \"default\"", 0L, 1L, 0, 10, "logs"));

        assertEquals(20004, ex.getCode());
        assertEquals("019dfbe0", ex.getOpenObserveTraceId());
        assertTrue(ex.getMessage().contains("Search field not found"));
    }

    @Test
    void testSearchNon2xxNonJsonBody() {
        server.enqueue(new MockResponse()
            .setResponseCode(500)
            .setBody("Internal Server Error (plain text)"));

        OpenObserveQueryException ex = assertThrows(OpenObserveQueryException.class,
            () -> impl.search("SELECT 1", 0L, 1L, 0, 10, "logs"));

        assertEquals(500, ex.getCode());
        assertTrue(ex.getMessage().contains("Internal Server Error"));
    }

    @Test
    void testDiscoverServicesReturnsSortedDistinctNames() throws Exception {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("{\"hits\":[" +
                     "{\"service_name\":\"order-service\"}," +
                     "{\"service_name\":\"IoTOpsCIMService\"}," +
                     "{\"service_name\":\"downstream-echo\"}" +
                     "]}"));

        List<String> services = impl.discoverServices();
        assertEquals(List.of("order-service", "IoTOpsCIMService", "downstream-echo"), services);

        RecordedRequest req = server.takeRequest();
        assertEquals("/api/default/_search?type=logs", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("SELECT DISTINCT service_name"), "body: " + body);
        assertTrue(body.contains("ORDER BY service_name"),         "body: " + body);
        assertTrue(body.contains("service_name IS NOT NULL"),       "body: " + body);
        assertTrue(body.contains("service_name != ''"),             "body: " + body);
        assertTrue(body.contains("\"size\":200"),                   "body: " + body);
    }

    @Test
    void testDiscoverServicesFiltersEmptyAndNull() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("{\"hits\":[" +
                     "{\"service_name\":\"foo\"}," +
                     "{\"service_name\":\"\"}," +
                     "{\"service_name\":null}," +
                     "{\"service_name\":\"bar\"}" +
                     "]}"));

        List<String> services = impl.discoverServices();
        assertEquals(List.of("foo", "bar"), services);
    }

    @Test
    void testListMetricStreamsExtractsNames() throws Exception {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("{\"list\":[" +
                     "{\"name\":\"jvm_memory_used\",\"stream_type\":\"metrics\"}," +
                     "{\"name\":\"http_server_requests_seconds\",\"stream_type\":\"metrics\"}," +
                     "{\"name\":\"\",\"stream_type\":\"metrics\"}" +
                     "],\"total\":3}"));

        List<String> streams = impl.listMetricStreams();
        assertEquals(List.of("jvm_memory_used", "http_server_requests_seconds"), streams);

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/default/streams?type=metrics", req.getPath());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
