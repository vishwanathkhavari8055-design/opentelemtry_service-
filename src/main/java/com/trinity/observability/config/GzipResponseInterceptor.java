package com.trinity.observability.config;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Asks for gzip and transparently inflates it.
 *
 * <p>The JDK {@code HttpClient} behind {@code JdkClientHttpRequestFactory} neither
 * sends {@code Accept-Encoding} nor decodes a compressed body, so every OpenObserve
 * response used to cross the wire as raw JSON. That is the single largest cost on
 * several screens. Measured against the live instance:</p>
 * <pre>
 *   GET /api/default/streams?type=metrics   19.8 MB plain  ->  868 KB gzip  (23x)
 *   GET /api/v2/default/alerts               334 KB plain  ->   20 KB gzip  (16x)
 *   GET /api/default/pipelines               629 KB plain  ->   47 KB gzip  (13x)
 * </pre>
 * <p>The plain metrics listing took anywhere from 3 s to 56 s depending on the link;
 * gzipped it is consistently about 2 s.</p>
 *
 * <p>A server that ignores the header simply answers uncompressed, and the body is
 * passed through untouched — decoding keys off the response's own
 * {@code Content-Encoding}, never off what was asked for.</p>
 */
public class GzipResponseInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        if (!request.getHeaders().containsKey(HttpHeaders.ACCEPT_ENCODING)) {
            request.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        }
        ClientHttpResponse response = execution.execute(request, body);
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && encoding.trim().equalsIgnoreCase("gzip")) {
            return new GunzippedResponse(response);
        }
        return response;
    }

    /** The original response with its body inflated and the encoding headers dropped,
     *  so nothing downstream mistakes the decoded length for the wire length. */
    private static final class GunzippedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final HttpHeaders headers;
        private InputStream body;

        GunzippedResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(delegate.getHeaders());
            copy.remove(HttpHeaders.CONTENT_ENCODING);
            copy.remove(HttpHeaders.CONTENT_LENGTH);
            this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                InputStream raw = new BufferedInputStream(delegate.getBody());
                // Several OpenObserve write endpoints answer with an EMPTY body. A
                // GZIPInputStream over zero bytes throws EOFException in its
                // constructor, so an empty body stays empty instead.
                raw.mark(1);
                if (raw.read() < 0) {
                    body = InputStream.nullInputStream();
                } else {
                    raw.reset();
                    body = new GZIPInputStream(raw, 64 * 1024);
                }
            }
            return body;
        }

        @Override
        public void close() {
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // The delegate's close below releases the connection either way.
                }
            }
            delegate.close();
        }
    }
}
