package com.trinity.observability.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Elasticsearch is an OPTIONAL backend. Spring only registers this config (and
// therefore the ElasticsearchClient bean) when `elasticsearch.host` is set and
// non-empty. With `openobserve.enabled=true` and no ES props, this class is
// skipped entirely — the lib starts cleanly with OpenObserve as the sole
// backend instead of failing on unresolved @Value placeholders.
@Configuration
@ConditionalOnExpression("'${elasticsearch.host:}' != ''")
public class ElasticsearchConfig {

    // Defaults on each placeholder are defensive — once @ConditionalOnExpression
    // gates the class, an empty host means we never get here. The fallbacks
    // just prevent a partial config (host set, port missing) from blowing up
    // before Spring's condition evaluation completes.
    @Value("${elasticsearch.host:}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Value("${elasticsearch.scheme:http}")
    private String scheme;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        RestClient restClient = RestClient.builder(
                new HttpHost(host, port, scheme)
        ).build();

        return new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper())
        );
    }
}
