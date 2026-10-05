package com.trinity.observability.serviceimpl;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.metrics.MetricRequestDTO;
import com.trinity.observability.dto.metrics.MetricResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsServiceImplTest {

    @Mock
    private ElasticsearchClientService elasticsearchClientService;

    @Mock
    private OpenObserveClientService openObserveClientService;

    private MetricsServiceImpl metricsService;

    @BeforeEach
    void setUp() {
        // Real OpenObserveConfig — Mockito can't mock it on JDK 26+ (Byte Buddy incompat).
        // Test exercises legacy ES path (openObserveEnabled defaults to false).
        OpenObserveConfig config = new OpenObserveConfig();
        metricsService = new MetricsServiceImpl(elasticsearchClientService, openObserveClientService, config);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetMetrics() throws IOException {
        MetricRequestDTO request = new MetricRequestDTO();
        request.setPage(0);
        request.setSize(20);

        Map<String, Object> source = new HashMap<>();
        source.put("@timestamp", "2026-04-20T06:00:00Z");
        source.put("metric.name", "jvm.memory");
        source.put("value", 100.0);

        Hit<Map> hit = Hit.of(h -> h.index("metrics").id("1").source(source));

        @SuppressWarnings("rawtypes")
        SearchResponse<Map> mockResponse = SearchResponse.of(s -> s
                .took(1L)
                .timedOut(false)
                .shards(sh -> sh.failed(0).successful(1).total(1))
                .hits(h -> h.hits(Collections.singletonList(hit))));

        when(elasticsearchClientService.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), eq(Map.class)))
                .thenReturn(mockResponse);

        List<MetricResponseDTO> result = metricsService.getMetrics(request);

        assertNotNull(result);
    }
}
