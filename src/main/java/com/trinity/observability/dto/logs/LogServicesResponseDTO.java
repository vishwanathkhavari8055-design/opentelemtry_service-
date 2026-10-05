package com.trinity.observability.dto.logs;

import java.util.Collections;
import java.util.List;

/**
 * Response for {@code GET /api/logs/services} — the distinct set of
 * {@code service_name} values present in the logs stream within the requested
 * window. Used by the UI's logs service filter dropdown so it reflects every
 * service that produced logs (not just whichever names happen to fall inside
 * a paginated row fetch).
 *
 * <p>The window metadata is echoed back so the UI can display "services
 * producing logs in the last 7d" without re-deriving it. {@link #supported}
 * mirrors the convention from {@link LogsSummaryResponseDTO}: false on the
 * Elasticsearch path, where this endpoint isn't implemented (Phase 1 D8(a)
 * keeps new aggregation endpoints OpenObserve-only).</p>
 */
public class LogServicesResponseDTO {

    /**
     * One service and how many log records it produced in the window.
     *
     * <p>The count is what lets the dropdown read "OrderService (18.2K)". It is
     * computed by the same GROUP BY that produces the names, so the two can
     * never disagree, and it respects the window — a service that has been
     * quiet all day reads as quiet rather than looking identical to a busy
     * one.</p>
     */
    public static class ServiceCount {
        private String name;
        private Long count;

        public ServiceCount() {}
        public ServiceCount(String name, Long count) {
            this.name = name;
            this.count = count;
        }

        public String getName()             { return name; }
        public void   setName(String v)     { this.name = v; }

        public Long getCount()              { return count; }
        public void setCount(Long v)        { this.count = v; }
    }

    private List<String> items;
    private List<ServiceCount> services;
    private String  windowStart;
    private String  windowEnd;
    private String  backend;
    private boolean supported;

    public LogServicesResponseDTO() {
        this.items = Collections.emptyList();
        this.services = Collections.emptyList();
    }

    /**
     * Plain names, kept for every existing caller. {@link #getServices()}
     * carries the same list with counts attached; both are emitted so adding
     * counts did not become a breaking change.
     */
    public List<String> getItems()            { return items; }
    public void setItems(List<String> items)  { this.items = items == null ? Collections.emptyList() : items; }

    public List<ServiceCount> getServices()   { return services; }
    public void setServices(List<ServiceCount> v) {
        this.services = v == null ? Collections.emptyList() : v;
    }

    public String getWindowStart()            { return windowStart; }
    public void setWindowStart(String v)      { this.windowStart = v; }

    public String getWindowEnd()              { return windowEnd; }
    public void setWindowEnd(String v)        { this.windowEnd = v; }

    public String getBackend()                { return backend; }
    public void setBackend(String v)          { this.backend = v; }

    public boolean isSupported()              { return supported; }
    public void setSupported(boolean v)       { this.supported = v; }
}
