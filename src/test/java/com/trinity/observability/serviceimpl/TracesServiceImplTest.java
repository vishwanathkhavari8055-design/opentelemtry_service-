package com.trinity.observability.serviceimpl;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.trinity.observability.client.ElasticsearchClientService;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.traces.TraceRequestDTO;
import com.trinity.observability.dto.traces.TraceTreeResponse;
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

@ExtendWith(MockitoExtension.class)
class TracesServiceImplTest {

    @Mock
    private ElasticsearchClientService elasticsearchClientService;

    @Mock
    private OpenObserveClientService openObserveClientService;

    private TracesServiceImpl tracesService;

    @BeforeEach
    void setUp() {
        // Real OpenObserveConfig — Mockito can't mock it on JDK 26+ (Byte Buddy incompatibility).
        // Test exercises the legacy ES path (openObserveEnabled defaults to false).
        OpenObserveConfig config = new OpenObserveConfig();
        tracesService = new TracesServiceImpl(elasticsearchClientService, openObserveClientService, config);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testGetTraces() throws IOException {
        TraceRequestDTO request = new TraceRequestDTO();
        request.setPage(0);
        request.setSize(20);

        // Simulate a raw ES source document
        Map<String, Object> sourceDoc = new HashMap<>();
        sourceDoc.put("trace.id", "abc123");
        sourceDoc.put("span.id", "span456");
        sourceDoc.put("@timestamp", "2026-04-08T06:00:00Z");
        sourceDoc.put("processor.event", "transaction");

        Hit<Map<String, Object>> hit = Hit.of(h -> h.index("traces").id("1").source(sourceDoc));

        SearchResponse<Map> mockResponse = SearchResponse.of(s -> s
            .took(1L)
            .timedOut(false)
            .shards(sh -> sh.failed(0).successful(1).total(1))
            .hits(h -> h.hits(Collections.singletonList((Hit) hit)))
        );

        org.mockito.Mockito.lenient().when(elasticsearchClientService.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), eq(Map.class)))
                .thenReturn(mockResponse);

        List<TraceTreeResponse> result = tracesService.getTraces(request);

        assertNotNull(result);
    }
}
