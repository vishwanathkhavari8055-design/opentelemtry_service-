package com.trinity.observability.util;

import org.junit.jupiter.api.Test;
import com.trinity.observability.dto.logs.LogResponseDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogNormalizerTest {

    @Test
    void testInfoLogNormalization() {
        // DB Query
        assertEquals("Database query executed successfully", LogNormalizer.normalizeMessage("MATCH (n) RETURN n", "INFO"));
        
        // Execution
        assertEquals("Operation completed in 101 ms", LogNormalizer.normalizeMessage("Operation completed in 101 ms", "INFO"));
        
        // Redis
        assertEquals("Cache updated successfully", LogNormalizer.normalizeMessage("published map data to redis", "INFO"));
        
        // Response
        assertEquals("Response generated successfully", LogNormalizer.normalizeMessage("Generated Response Checksum: xyz", "INFO"));
    }

    @Test
    void testSanitizeMasksSecretsWithoutTruncating() {
        // Long input with an embedded secret: secrets masked, length preserved
        // (sanitizeSensitiveData must NOT truncate — that's normalizeMessage's job).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("frame line ").append(i).append(' ');
        String body = "password=hunter2 " + sb;

        String out = LogNormalizer.sanitizeSensitiveData(body);
        assertFalse(out.contains("hunter2"), "password masked");
        assertTrue(out.length() > 150, "no 150-char truncation applied: len=" + out.length());
        assertTrue(out.contains("frame line 49"), "tail preserved (not cut)");
    }

    @Test
    void testSanitizeNullSafe() {
        assertNull(LogNormalizer.sanitizeSensitiveData(null));
    }

    @Test
    void testInfoNoiseFiltering() {
        // Cookie logs should return null for INFO
        assertNull(LogNormalizer.normalizeMessage("Cookie value: session=123", "INFO"));
        assertNull(LogNormalizer.normalizeMessage("Cookie data processed", "INFO"));
    }

    @Test
    void testWarnLogNormalization() {
        assertEquals("Invalid or rejected cookie detected", LogNormalizer.normalizeMessage("Cookie rejected by domain", "WARN"));
        assertEquals("Unexpected fields found in request data", LogNormalizer.normalizeMessage("Extra keys found in JSON", "WARN"));
    }

    @Test
    void testErrorLogNormalization() {
        assertEquals("Some data missing required order information", LogNormalizer.normalizeMessage("Found null orderNo in payload", "ERROR"));
        assertEquals("Failed to upload relationship data", LogNormalizer.normalizeMessage("Error uploading relationship excel file", "ERROR"));
        assertEquals("System error due to missing required data", LogNormalizer.normalizeMessage("Cannot invoke method on null object", "ERROR"));
        
        // Global masking still applied
        String errorWithToken = "Error with accessToken: secret123";
        String normalized = LogNormalizer.normalizeMessage(errorWithToken, "ERROR");
        assertTrue(normalized.contains("[masked]"));
    }

    @Test
    void testDynamicTableExtractionInError() {
        String log = "Invalid object name 'global.inletStatus'";
        String result = LogNormalizer.normalizeMessage(log, "ERROR");
        assertEquals("Database table global.inletStatus not found", result);
    }

    @Test
    void testFallbackAndGlobalMasking() {
        String log = "Application started with Bearer eyJhbGci...";
        String result = LogNormalizer.normalizeMessage(log, "INFO");
        assertEquals("Application started with Bearer [masked]", result);
    }

    @Test
    void testEnrichmentPriority() {
        Map<String, Object> source = createBaseSource();
        List<Map<String, Object>> stacktrace = getStacktrace(source);

        // A framework class that should be dynamically skipped by isBusinessClass
        stacktrace.add(createFrame("ThreadPoolExecutor.java", "java.util.concurrent.ThreadPoolExecutor", 10));
        // A business class that should be prioritized and selected
        stacktrace.add(createFrame("Trinity.java", "com.trinity.Service", 20)); 

        String result = LogNormalizer.enrichMessageWithStackTrace("Error", source);
        assertEquals("file name:Trinity.java, class name:com.trinity.Service, line number:20, message:Error", result);
    }

    @Test
    void testEnrichmentFallback() {
        Map<String, Object> source = createBaseSource();
        List<Map<String, Object>> stacktrace = getStacktrace(source);

        stacktrace.add(createFrame("External.java", "org.external.Lib", 10)); // Fallback

        String result = LogNormalizer.enrichMessageWithStackTrace("Error", source);
        assertEquals("file name:External.java, class name:org.external.Lib, line number:10, message:Error", result);
    }

    @Test
    void testEnrichmentNullSafety() {
        // Missing line number
        Map<String, Object> source = createBaseSource();
        List<Map<String, Object>> stacktrace = getStacktrace(source);
        stacktrace.add(createFrame("Test.java", "com.trinity.Test", null));

        String result = LogNormalizer.enrichMessageWithStackTrace("msg", source);
        assertEquals("file name:Test.java, class name:com.trinity.Test, line number:-, message:msg", result);

        // Missing filename or classname -> original message
        Map<String, Object> source2 = createBaseSource();
        getStacktrace(source2).add(createFrame(null, "com.trinity.Test", 10));
        assertEquals("msg", LogNormalizer.enrichMessageWithStackTrace("msg", source2));

        // Invalid structure (not a Map)
        Map<String, Object> source3 = new HashMap<>();
        source3.put("error", "not a map");
        assertEquals("msg", LogNormalizer.enrichMessageWithStackTrace("msg", source3));
    }

    @Test
    void testEnrichmentFromRawStackTraceString() {
        String rawStack = """
                java.lang.NullPointerException: null
                    at com.trinity.service.MyService.doThings(MyService.java:42)
                    at org.external.Lib.invoke(Lib.java:100)""";

        Map<String, Object> source = new HashMap<>();
        source.put("exception", Map.of("stacktrace", rawStack));

        String result = LogNormalizer.enrichMessageWithStackTrace("Original Error", source);
        assertEquals("file name:MyService.java, class name:com.trinity.service.MyService, line number:42, message:Original Error", result);
    }

    private Map<String, Object> createBaseSource() {
        Map<String, Object> source = new HashMap<>();
        Map<String, Object> error = new HashMap<>();
        List<Map<String, Object>> exceptions = new ArrayList<>();
        Map<String, Object> exception = new HashMap<>();
        List<Map<String, Object>> stacktrace = new ArrayList<>();

        exception.put("stacktrace", stacktrace);
        exceptions.add(exception);
        error.put("exception", exceptions);
        source.put("error", error);
        return source;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getStacktrace(Map<String, Object> source) {
        return (List<Map<String, Object>>) ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) source.get("error")).get("exception")).get(0)).get("stacktrace");
    }

    private Map<String, Object> createFrame(String filename, String classname, Integer line) {
        Map<String, Object> frame = new HashMap<>();
        if (filename != null) frame.put("filename", filename);
        if (classname != null) frame.put("classname", classname);
        if (line != null) frame.put("line.number", line);
        return frame;
    }

    @Test
    void testNightlyNestedLineNumberExtraction() {
        // Simulate EXACT structure from Nightly logs
        Map<String, Object> source = new HashMap<>();
        Map<String, Object> error = new HashMap<>();
        List<Map<String, Object>> exceptions = new ArrayList<>();
        Map<String, Object> exception = new HashMap<>();
        List<Map<String, Object>> stacktrace = new ArrayList<>();

        // Framework Proxy Frame (should be skipped)
        Map<String, Object> proxyFrame = new HashMap<>();
        proxyFrame.put("classname", "com.trinity.daoimpl.QueryDataDaoImpl$$SpringCGLIB$$0");
        proxyFrame.put("function", "executeSqlStatementFromIotOpsAdmin");
        stacktrace.add(proxyFrame);

        // Real Business Frame (should be selected, nested line number should be extracted)
        Map<String, Object> businessFrame = new HashMap<>();
        businessFrame.put("classname", "com.trinity.daoimpl.QueryDataDaoImpl");
        businessFrame.put("function", "executeSqlStatementFromIotOpsAdmin");
        
        // Nested line number structure exactly as in Nightly!
        Map<String, Object> lineMap = new HashMap<>();
        lineMap.put("number", 33);
        businessFrame.put("line", lineMap);
        
        stacktrace.add(businessFrame);

        exception.put("stacktrace", stacktrace);
        exceptions.add(exception);
        error.put("exception", exceptions);
        source.put("error", error);

        // Verify we pull fields dynamically
        LogResponseDTO dto = new LogResponseDTO();
        LogNormalizer.extractCodeMetadata(source, dto);

        assertEquals("com.trinity.daoimpl.QueryDataDaoImpl", dto.getCodeNamespace());
        assertEquals("executeSqlStatementFromIotOpsAdmin", dto.getCodeFunction());
    }
}
