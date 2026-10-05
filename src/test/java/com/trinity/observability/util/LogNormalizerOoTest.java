package com.trinity.observability.util;

import com.trinity.observability.dto.logs.LogResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LogNormalizerOoTest {

    @Test
    void testExtractCodeMetadataOoFromLogLogger() {
        Map<String, Object> hit = Map.of("log_logger", "com.example.OrderController");
        LogResponseDTO dto = new LogResponseDTO();
        dto.setCodeNamespace("unknown");

        LogNormalizer.extractCodeMetadataOo(hit, dto);

        assertEquals("com.example.OrderController", dto.getCodeNamespace());
    }

    @Test
    void testExtractCodeMetadataOoLeavesUnknownWhenLoggerMissing() {
        LogResponseDTO dto = new LogResponseDTO();   // codeNamespace defaults to "unknown"
        LogNormalizer.extractCodeMetadataOo(new HashMap<>(), dto);

        // codeNamespace stays at the DTO default — extractor doesn't overwrite when no signal
        assertEquals("unknown", dto.getCodeNamespace());
    }

    @Test
    void testExtractCodeMetadataOoIgnoresEmptyLogger() {
        Map<String, Object> hit = new HashMap<>();
        hit.put("log_logger", "   ");
        LogResponseDTO dto = new LogResponseDTO();

        LogNormalizer.extractCodeMetadataOo(hit, dto);
        assertEquals("unknown", dto.getCodeNamespace(), "blank logger value must not overwrite default");
    }

    @Test
    void testExtractCodeMetadataOoNullSafe() {
        // Should not throw on either null
        LogNormalizer.extractCodeMetadataOo(null, new LogResponseDTO());
        LogNormalizer.extractCodeMetadataOo(Map.of("log_logger", "x"), null);
        // No assertion needed — survival is the test
        assertNotNull(LogNormalizer.class);
    }
}
