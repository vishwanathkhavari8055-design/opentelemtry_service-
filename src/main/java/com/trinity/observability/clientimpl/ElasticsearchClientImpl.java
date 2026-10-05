package com.trinity.observability.clientimpl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.trinity.observability.client.ElasticsearchClientService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

// Same condition as ElasticsearchConfig — if there's no ES host configured,
// the ElasticsearchClient bean doesn't exist and this @Service would otherwise
// fail to construct. Gating here keeps Spring from even trying.
@Service
@ConditionalOnExpression("'${elasticsearch.host:}' != ''")
public class ElasticsearchClientImpl implements ElasticsearchClientService {

    private static final Logger log = LogManager.getLogger(ElasticsearchClientImpl.class);

    private final ElasticsearchClient elasticsearchClient;

    public ElasticsearchClientImpl(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    @Override
    public <T> SearchResponse<T> search(String index, Query query, int from, int size, String sortField,
            co.elastic.clients.elasticsearch._types.SortOrder sortOrder, Class<T> clazz) throws IOException {
        log.debug("Elasticsearch search: index={} from={} size={} sort={} {}",
                index, from, size, sortField, sortOrder);

        long t0 = System.nanoTime();
        SearchResponse<T> response = elasticsearchClient.search(s -> {
            s.index(index).query(query).from(from).size(size);
            if (sortField != null && !sortField.isEmpty()) {
                s.sort(so -> so.field(f -> f.field(sortField).order(sortOrder)));
            }
            return s;
        }, clazz);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        long totalHits = response.hits().total() != null ? response.hits().total().value() : -1L;
        log.debug("Elasticsearch search ({}): {} returned, totalMatching={} in {} ms",
                index, response.hits().hits().size(), totalHits, elapsedMs);
        return response;
    }

    @Override
    public <T> SearchResponse<T> searchWithAggregation(String index, Query query,
            Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggregations, int size,
            Class<T> clazz) throws IOException {
        log.debug("Elasticsearch searchWithAggregation: index={} aggs={} size={}",
                index, aggregations.keySet(), size);

        long t0 = System.nanoTime();
        SearchResponse<T> response = elasticsearchClient.search(s -> s
                .index(index)
                .query(query)
                .size(size)
                .aggregations(aggregations), clazz);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        log.debug("Elasticsearch searchWithAggregation ({}): {} hit(s) in {} ms",
                index, response.hits().hits().size(), elapsedMs);
        return response;
    }
}
