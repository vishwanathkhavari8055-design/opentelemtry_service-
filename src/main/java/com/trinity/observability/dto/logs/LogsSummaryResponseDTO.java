package com.trinity.observability.dto.logs;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/logs/summary} — aggregate log counts over a
 * fixed window.
 *
 * <ul>
 *   <li>{@code totalCount}: every log in the window.</li>
 *   <li>{@code countsBySeverity}: {@code ERROR}/{@code WARN}/{@code INFO}/
 *       {@code DEBUG}/{@code TRACE}/{@code FATAL} → count. Unknown severity
 *       values are folded under {@code "UNKNOWN"}.</li>
 *   <li>{@code topErrorServices}: services with the most ERROR-or-worse logs,
 *       sorted DESC, capped at 5.</li>
 * </ul>
 */
public class LogsSummaryResponseDTO {

    public static class TopErrorService {
        private String serviceName;
        private long   errorCount;

        public TopErrorService() {}
        public TopErrorService(String serviceName, long errorCount) {
            this.serviceName = serviceName;
            this.errorCount = errorCount;
        }
        public String getServiceName()              { return serviceName; }
        public void   setServiceName(String s)      { this.serviceName = s; }
        public long   getErrorCount()               { return errorCount; }
        public void   setErrorCount(long v)         { this.errorCount = v; }
    }

    private long totalCount;
    private Map<String, Long> countsBySeverity;
    private List<TopErrorService> topErrorServices;
    private String windowStart;
    private String windowEnd;
    private String backend;
    private boolean supported;

    public long   getTotalCount()                 { return totalCount; }
    public void   setTotalCount(long v)           { this.totalCount = v; }

    public Map<String, Long> getCountsBySeverity()           { return countsBySeverity; }
    public void              setCountsBySeverity(Map<String, Long> v) { this.countsBySeverity = v; }

    public List<TopErrorService> getTopErrorServices()                { return topErrorServices; }
    public void                  setTopErrorServices(List<TopErrorService> v) { this.topErrorServices = v; }

    public String getWindowStart()                   { return windowStart; }
    public void   setWindowStart(String v)           { this.windowStart = v; }

    public String getWindowEnd()                     { return windowEnd; }
    public void   setWindowEnd(String v)             { this.windowEnd = v; }

    public String getBackend()                       { return backend; }
    public void   setBackend(String v)               { this.backend = v; }

    public boolean isSupported()                     { return supported; }
    public void    setSupported(boolean v)           { this.supported = v; }
}
