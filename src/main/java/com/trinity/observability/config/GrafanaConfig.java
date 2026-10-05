package com.trinity.observability.config;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client that talks to Grafana.
 *
 * <p>Built once at startup rather than per request, because the token and the
 * timeouts do not change while the service runs and a fresh {@link HttpClient}
 * per call would give up connection reuse — the dashboard viewer issues one
 * {@code /api/ds/query} per panel, so a twelve-panel dashboard is twelve calls
 * every refresh.</p>
 *
 * <p>The {@code Authorization} header is a default on the client, which is the
 * point: no call site can forget it, and no call site has to be trusted with the
 * token. {@link GrafanaClientImpl} never sees it.</p>
 */
@Component
public class GrafanaConfig {

    private static final Logger log = LogManager.getLogger(GrafanaConfig.class);

    /**
     * JDK system property that turns off hostname verification for
     * {@link HttpClient}. There is no per-client switch — an all-trusting
     * {@link SSLContext} alone still fails the hostname check — so a self-signed
     * certificate whose CN does not match needs this too. Set only when
     * {@code grafana.tls-insecure=true} asks for it, and loudly.
     */
    private static final String DISABLE_HOSTNAME_VERIFICATION =
            "jdk.internal.httpclient.disableHostnameVerification";

    private final GrafanaProperties properties;
    private final RestClient client;
    private final RestClient queryClient;

    public GrafanaConfig(GrafanaProperties properties) {
        this.properties = properties;
        // Two clients, differing ONLY in read timeout. Metadata reads are small
        // and fast and want a tight backstop; a panel query can legitimately run
        // for tens of seconds, and cutting it off at the metadata timeout reported
        // a still-running query as an unreachable host.
        this.client = build(properties, properties.getRequestTimeoutSeconds());
        this.queryClient = build(properties, properties.getQueryTimeoutSeconds());
        announce(properties);
    }

    private static RestClient build(GrafanaProperties properties, int readTimeoutSeconds) {
        AtomicInteger seq = new AtomicInteger();
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "grafana-http-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }));

        if (properties.isTlsInsecure()) {
            log.warn("grafana.tls-insecure=true — TLS certificate AND hostname verification are "
                    + "DISABLED for Grafana calls. Use this only against a trusted internal "
                    + "instance; prefer importing its certificate into the JVM truststore.");
            System.setProperty(DISABLE_HOSTNAME_VERIFICATION, "true");
            http = http.sslContext(trustAllContext());
        }

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // Same reason as the OpenObserve client: the JDK client never
                // asks for compression, and dashboard JSON is large.
                .requestInterceptor(new GzipResponseInterceptor());

        String baseUrl = properties.getBaseUrl();
        if (!baseUrl.isEmpty()) {
            builder = builder.baseUrl(baseUrl);
        }
        String token = properties.getToken();
        if (!token.isEmpty()) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return builder.build();
    }

    @SuppressWarnings("java:S4830")
    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustEverything = { new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Deliberately empty — see the warning logged above.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Deliberately empty — see the warning logged above.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            } };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustEverything, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not build the insecure TLS context grafana.tls-insecure=true asked for",
                    e);
        }
    }

    /**
     * Say what state the feature is in at startup rather than at the first
     * request. "Dashboards are empty" is otherwise indistinguishable from "the
     * token was never configured", and the two need completely different fixes.
     */
    private static void announce(GrafanaProperties properties) {
        if (!properties.isEnabled()) {
            log.info("Grafana dashboards DISABLED (grafana.enabled=false) — the Dashboard "
                    + "Catalog will report supported=false.");
            return;
        }
        if (properties.getBaseUrl().isEmpty()) {
            log.warn("Grafana dashboards enabled but grafana.base-url is blank — set "
                    + "GRAFANA_BASE_URL. Until then the Dashboard Catalog reports "
                    + "supported=false.");
            return;
        }
        if (properties.getToken().isEmpty()) {
            log.warn("Grafana dashboards enabled and pointed at {} but grafana.token is blank — "
                    + "set GRAFANA_SA_TOKEN to a VIEWER service-account token. Until then the "
                    + "Dashboard Catalog reports supported=false.", properties.getBaseUrl());
            return;
        }
        log.info("Grafana dashboards ENABLED — {} (orgId={}, metadata timeout={}s, "
                + "query timeout={}s, registry scope={}, store={})",
                properties.getBaseUrl(), properties.getOrgId(),
                properties.getRequestTimeoutSeconds(), properties.getQueryTimeoutSeconds(),
                properties.getRegistry().getScope(), properties.getRegistry().getStore());
    }

    /** Metadata reads: folders, dashboard search, a dashboard's JSON. */
    public RestClient client() {
        return client;
    }

    /** Panel queries, which are allowed to take much longer. */
    public RestClient queryClient() {
        return queryClient;
    }

    public GrafanaProperties properties() {
        return properties;
    }
}
