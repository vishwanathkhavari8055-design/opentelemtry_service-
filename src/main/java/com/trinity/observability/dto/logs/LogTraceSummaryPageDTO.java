package com.trinity.observability.dto.logs;

import java.util.List;

/**
 * Paged response for {@code GET /api/logs/groups} (issue #2).
 *
 * Same {@code hasMore} contract as {@link LogPageResponseDTO} — the
 * frontend uses it to know whether to enable the "next page" button.
 * Pagination here is by trace count, not log count: page N contains the
 * (N+1)th batch of size traces ordered by latest activity.
 */
public class LogTraceSummaryPageDTO {
    private List<LogTraceSummaryDTO> items;
    private boolean hasMore;
    private Integer total;

    public LogTraceSummaryPageDTO() {}

    public LogTraceSummaryPageDTO(List<LogTraceSummaryDTO> items, boolean hasMore, Integer total) {
        this.items = items;
        this.hasMore = hasMore;
        this.total = total;
    }

    public List<LogTraceSummaryDTO> getItems() { return items; }
    public void setItems(List<LogTraceSummaryDTO> items) { this.items = items; }

    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }

    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
}
