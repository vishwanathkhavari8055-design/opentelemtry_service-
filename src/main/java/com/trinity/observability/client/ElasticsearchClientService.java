package com.trinity.observability.client;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import java.io.IOException;
import java.util.Map;

public interface ElasticsearchClientService {
    <T> SearchResponse<T> search(String index, Query query, int from, int size, String sortField, SortOrder sortOrder, Class<T> clazz) throws IOException;
    
    <T> SearchResponse<T> searchWithAggregation(String index, Query query, Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggregations, int size, Class<T> clazz) throws IOException;
}
