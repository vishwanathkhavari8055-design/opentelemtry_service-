package com.trinity.observability.dto.logs;

import java.util.List;

/**
 * Paged log response (Phase 2 / issue #8).
 *
 * Returns the requested page of log records together with a {@code hasMore}
 * flag the UI uses to enable/disable the "next page" button. Previously the
 * UI inferred end-of-results from {@code items.size() &lt; pageSize}, which
 * fails when the backend always returns exactly {@code pageSize} rows: the
 * "next" button stayed enabled even when the next page was empty.
 *
 * The {@code total} field is optional. Computing it would require an extra
 * COUNT query against OpenObserve / Elasticsearch on every request, so it is
 * left {@code null} by default and reserved for callers that explicitly need
 * it (none today).
 */
public class LogPageResponseDTO {
    private List<LogResponseDTO> items;
    private boolean hasMore;
    private Integer total;

    public LogPageResponseDTO() {}

    public LogPageResponseDTO(List<LogResponseDTO> items, boolean hasMore, Integer total) {
        this.items = items;
        this.hasMore = hasMore;
        this.total = total;
    }

    public List<LogResponseDTO> getItems() { return items; }
    public void setItems(List<LogResponseDTO> items) { this.items = items; }

    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }

    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
}
