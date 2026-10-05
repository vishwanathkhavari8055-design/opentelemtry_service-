package com.trinity.observability.serviceimpl;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.logs.LogPageResponseDTO;
import com.trinity.observability.dto.logs.LogRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogsServiceImplTest {

    @Mock
    private ElasticsearchClientService elasticsearchClientService;

    @Mock
    private OpenObserveClientService openObserveClientService;

    private LogsServiceImpl logsService;

    @BeforeEach
    void setUp() {
        // Real OpenObserveConfig — Mockito can't mock it on JDK 26+ (Byte Buddy incompatibility).
        // Test exercises the legacy ES path (openObserveEnabled defaults to false).
        OpenObserveConfig config = new OpenObserveConfig();
        logsService = new LogsServiceImpl(elasticsearchClientService, openObserveClientService, config);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetLogs() throws IOException {
        LogRequestDTO request = new LogRequestDTO();
        request.setPage(0);
        request.setSize(20);

        Map<String, Object> source = new HashMap<>();
        source.put("@timestamp", "2026-04-20T06:00:00Z");
        source.put("body.text", "Test log message");
        source.put("severity_text", "INFO");

        Hit<Map> hit = Hit.of(h -> h.index("logs").id("1").source(source));
        
        @SuppressWarnings("rawtypes")
        SearchResponse<Map> mockResponse = SearchResponse.of(s -> s
            .took(1L)
            .timedOut(false)
            .shards(sh -> sh.failed(0).successful(1).total(1))
            .hits(h -> h.hits(Collections.singletonList(hit)))
        );

        org.mockito.Mockito.lenient().when(elasticsearchClientService.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), eq(Map.class)))
                .thenReturn(mockResponse);

        LogPageResponseDTO result = logsService.getLogs(request);

        assertNotNull(result);
        assertNotNull(result.getItems());
    }
    @Test
    @SuppressWarnings("rawtypes")
    void specificServiceAndFieldFiltersReachTheElasticsearchQuery() throws IOException {
        LogRequestDTO request = new LogRequestDTO();
        request.setPage(0);
        request.setSize(10);
        request.setServiceName(" order-svc ");
        request.setHostName("host-1");
        request.setOsType("");
        request.setTraceId("abc123");

        SearchResponse<Map> emptyResponse = SearchResponse.of(s -> s
            .took(1L)
            .timedOut(false)
            .shards(sh -> sh.failed(0).successful(1).total(1))
            .hits(h -> h.hits(Collections.emptyList()))
        );
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        when(elasticsearchClientService.search(anyString(), query.capture(), anyInt(), anyInt(), anyString(), any(),
                eq(Map.class))).thenReturn(emptyResponse);

        LogPageResponseDTO result = logsService.getLogs(request);

        assertTrue(result.getItems().isEmpty());
        String json = query.getValue().toString();
        assertTrue(json.contains("\"resource.attributes.service.name\""), json);
        assertTrue(json.contains("\"order-svc\""), json);
        assertTrue(json.contains("\"host-1\""), json);
        assertTrue(json.contains("\"abc123\""), json);
        assertFalse(json.contains("host.os.type"), "empty filters are skipped: " + json);
    }
}
