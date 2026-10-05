package com.trinity.observability.dto.traces;

/**
 * Query params for {@code GET /api/traces/summary}.
 */
public class TracesSummaryRequestDTO {
    private String window;

    public String getWindow()         { return window; }
    public void   setWindow(String w) { this.window = w; }
}
