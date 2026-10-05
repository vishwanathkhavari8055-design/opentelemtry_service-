package com.trinity.observability.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.trinity.observability.util.OrgContext;

@Configuration
public class OpenObserveConfig {

    @Value("${openobserve.host}")
    private String host;

    @Value("${openobserve.org}")
    private String org;

    @Value("${openobserve.auth-token}")
    private String authToken;

    @Value("${openobserve.logs-stream:default}")
    private String logsStream;

    @Value("${openobserve.traces-stream:default}")
    private String tracesStream;

    /**
     * How long a single OpenObserve call may take before it is abandoned.
     *
     * <p>Generous on purpose. A 24h aggregate over a high-cardinality metric stream
     * has been measured on this deployment at 3–11 s, and cutting it off would turn
     * a slow-but-correct answer into a wrong one — Product Catalog validation would
     * report a resource as having no metrics. This is a stuck-connection backstop,
     * not a latency budget.</p>
     */
    @Value("${openobserve.request-timeout-seconds:60}")
    private int requestTimeoutSeconds;

    /**
     * The HTTP client every OpenObserve call goes through.
     *
     * <p>The request factory is now EXPLICIT. Left to auto-detection, Spring picks
     * whichever client happens to be on the classpath, and the fallback
     * ({@code HttpURLConnection}) caps itself at five keep-alive connections per
     * host — which silently serialises anything that fans out, including the
     * Product Catalog's parallel probes and the batched Last Seen refresh. The JDK
     * client has no such per-host cap and negotiates HTTP/2 where the server offers
     * it, so concurrent queries actually run concurrently.</p>
     *
     * <p>Its executor is a shared daemon pool rather than the default one, so
     * response handling cannot leave non-daemon threads holding JVM shutdown
     * open.</p>
     */
    @Bean
    public RestClient openObserveRestClient() {
        AtomicInteger seq = new AtomicInteger();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "openobserve-http-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)));

        return RestClient.builder()
            .requestFactory(factory)
            .baseUrl(host)
            // authToken value INCLUDES the "Basic " prefix already — pass it as-is.
            // Do NOT write `"Basic " + authToken` here; that produces "Basic Basic ..." → 401.
            .defaultHeader(HttpHeaders.AUTHORIZATION, authToken)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            // The JDK client never asks for compression, so every response came
            // back as raw JSON — 19.8 MB for the metrics stream list. See the
            // interceptor for the measurements.
            .requestInterceptor(new GzipResponseInterceptor())
            .build();
    }

    /**
     * The organization to query.
     *
     * <p>Returns the org the CURRENT REQUEST asked for when there is one (see
     * {@link com.trinity.observability.filter.OrgContextFilter}), otherwise the
     * configured default. This one method is what makes the whole service
     * org-switchable: every OpenObserve URL in the codebase is built from it,
     * so callers get per-request scoping without any of them changing.</p>
     *
     * <p>The context value is already validated against
     * {@code ^[A-Za-z0-9_-]{1,64}$} before being stored — it lands in a URL
     * path, so it must not be able to carry a traversal sequence.</p>
     */
    public String getOrg() {
        String requested = OrgContext.get();
        return requested != null ? requested : org;
    }

    /** Base URL, no trailing slash — for callers that must build an absolute
     *  URI to bypass RestClient's URI-template encoding. */
    public String getHost() {
        if (host == null) {
            return "";
        }
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '/') {
            end--;
        }
        return host.substring(0, end);
    }

    /** The configured default, ignoring any per-request override. */
    public String getDefaultOrg()    { return org; }
    public String getLogsStream()    { return logsStream; }
    public String getTracesStream()  { return tracesStream; }
}
