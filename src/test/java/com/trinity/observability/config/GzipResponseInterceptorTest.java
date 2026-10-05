package com.trinity.observability.config;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interceptor sits under every OpenObserve call, so each response shape the
 * client meets has to come through it intact: compressed, uncompressed, empty, and
 * an error body read by an onStatus handler.
 */
class GzipResponseInterceptorTest {

    private MockWebServer server;
    private RestClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // The same request factory production uses: the JDK client, which neither
        // asks for nor decodes gzip on its own.
        client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))
                .baseUrl(server.url("/").toString())
                .requestInterceptor(new GzipResponseInterceptor())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static Buffer gzip(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return new Buffer().write(bytes.toByteArray());
    }

    @Test
    void asksForGzip() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        client.get().uri("/x").retrieve().body(String.class);
        RecordedRequest request = server.takeRequest();
        assertEquals("gzip", request.getHeader("Accept-Encoding"));
    }

    @Test
    void inflatesACompressedBody() throws Exception {
        String json = "{\"list\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
        server.enqueue(new MockResponse()
                .setHeader("Content-Encoding", "gzip")
                .setHeader("Content-Type", "application/json")
                .setBody(gzip(json)));
        assertEquals(json, client.get().uri("/x").retrieve().body(String.class));
    }

    @Test
    void passesAnUncompressedBodyThrough() {
        server.enqueue(new MockResponse().setBody("{\"plain\":true}"));
        assertEquals("{\"plain\":true}", client.get().uri("/x").retrieve().body(String.class));
    }

    @Test
    void anEmptyGzipLabelledBodyIsEmptyNotAnError() {
        // Several OpenObserve write endpoints answer 200 with nothing at all.
        server.enqueue(new MockResponse().setHeader("Content-Encoding", "gzip").setBody(""));
        assertNull(client.post().uri("/x").body("{}").retrieve().body(String.class));
    }

    @Test
    void errorBodiesAreInflatedForOnStatusHandlers() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzip("{\"message\":\"bad sql\"}")));
        String[] seen = new String[1];
        // retrieve() is lazy: the exchange, and so the handler, runs on body().
        RestClient.ResponseSpec response = client.get().uri("/x").retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    seen[0] = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new HttpClientErrorException(res.getStatusCode());
                });
        assertThrows(HttpClientErrorException.class, () -> response.body(String.class));
        assertTrue(seen[0].contains("bad sql"), seen[0]);
    }
}
