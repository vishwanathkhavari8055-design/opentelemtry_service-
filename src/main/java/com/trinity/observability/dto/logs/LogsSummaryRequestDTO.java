package com.trinity.observability.dto.logs;

/**
 * Query params for {@code GET /api/logs/summary}.
 *
 * <p>Single field: a relative ({@code now-1h}) or absolute ISO-8601 start of
 * the aggregation window. Defaults are resolved in the service layer (see
 * {@link com.trinity.observability.constants.AppConstants#DEFAULT_SUMMARY_WINDOW}).</p>
 */
public class LogsSummaryRequestDTO {
    private String window;

    public String getWindow()         { return window; }
    public void   setWindow(String w) { this.window = w; }
}
