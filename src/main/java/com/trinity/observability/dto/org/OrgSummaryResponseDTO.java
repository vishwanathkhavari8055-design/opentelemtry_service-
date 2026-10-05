package com.trinity.observability.dto.org;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/org/summary} — the whole-org ingest posture
 * behind the UI's Home screen.
 *
 * <p>Every numeric field is a boxed type on purpose. {@code null} means "we
 * could not compute this", which the UI renders as an em dash; {@code 0} means
 * "we asked and the answer was zero". Coalescing a failed sub-query to zero
 * would make "no streams" and "we failed to count the streams" look identical,
 * and those lead an operator to opposite conclusions — so a sub-query that
 * throws leaves its field null and the rest of the response still returns.</p>
 *
 * <p>All sizes are RAW BYTES. OpenObserve reports stream stats in megabytes as
 * floats ({@code "storage_size": 1.179}); the conversion happens in the service
 * so the wire format carries one unambiguous unit and the display unit stays a
 * presentation choice on the client.</p>
 */
public class OrgSummaryResponseDTO {

    /** Five headline figures for the Streams panel. */
    public static class Streams {
        private Integer count;
        private Long events;
        private Long ingestedBytes;
        private Long compressedBytes;
        private Long indexBytes;

        public Integer getCount()                    { return count; }
        public void    setCount(Integer v)           { this.count = v; }

        public Long getEvents()                      { return events; }
        public void setEvents(Long v)                { this.events = v; }

        public Long getIngestedBytes()               { return ingestedBytes; }
        public void setIngestedBytes(Long v)         { this.ingestedBytes = v; }

        public Long getCompressedBytes()             { return compressedBytes; }
        public void setCompressedBytes(Long v)       { this.compressedBytes = v; }

        public Long getIndexBytes()                  { return indexBytes; }
        public void setIndexBytes(Long v)            { this.indexBytes = v; }
    }

    /** A plain count card (Functions, Dashboards). */
    public static class Count {
        private Integer total;

        public Count() {}
        public Count(Integer count) { this.total = count; }

        public Integer getCount()          { return total; }
        public void    setCount(Integer v) { this.total = v; }
    }

    /**
     * Trigger outcomes for a Split card — how many alerts / pipelines last ran
     * healthy, failed or with a warning. Straight from OpenObserve's
     * {@code trigger_status} on {@code GET /api/{org}/summary}; the whole object
     * stays null when that call fails, so the UI can say "no data" instead of
     * drawing three zero-height bars.
     */
    public static class Health {
        private Integer healthy;
        private Integer failed;
        private Integer warning;

        public Integer getHealthy()          { return healthy; }
        public void    setHealthy(Integer v) { this.healthy = v; }

        public Integer getFailed()           { return failed; }
        public void    setFailed(Integer v)  { this.failed = v; }

        public Integer getWarning()          { return warning; }
        public void    setWarning(Integer v) { this.warning = v; }
    }

    /**
     * A card split into scheduled / real-time totals, with an optional listing.
     * Used by Alerts and Pipelines, which OpenObserve models the same way.
     */
    public static class Split {
        private Integer scheduled;
        private Integer realTime;
        private List<Map<String, Object>> items;
        private Health health;

        public Integer getScheduled()          { return scheduled; }
        public void    setScheduled(Integer v) { this.scheduled = v; }

        public Integer getRealTime()           { return realTime; }
        public void    setRealTime(Integer v)  { this.realTime = v; }

        public List<Map<String, Object>> getItems()          { return items; }
        public void setItems(List<Map<String, Object>> v)    { this.items = v; }

        public Health getHealth()            { return health; }
        public void   setHealth(Health v)    { this.health = v; }
    }

    private boolean supported;
    private String backend;
    private String org;
    private Streams streams = new Streams();
    private Count functions = new Count();
    private Count dashboards = new Count();
    private Split alerts = new Split();
    private Split pipelines = new Split();

    public boolean isSupported()             { return supported; }
    public void    setSupported(boolean v)   { this.supported = v; }

    public String getBackend()               { return backend; }
    public void   setBackend(String v)       { this.backend = v; }

    public String getOrg()                   { return org; }
    public void   setOrg(String v)           { this.org = v; }

    public Streams getStreams()              { return streams; }
    public void    setStreams(Streams v)     { this.streams = v; }

    public Count getFunctions()              { return functions; }
    public void  setFunctions(Count v)       { this.functions = v; }

    public Count getDashboards()             { return dashboards; }
    public void  setDashboards(Count v)      { this.dashboards = v; }

    public Split getAlerts()                 { return alerts; }
    public void  setAlerts(Split v)          { this.alerts = v; }

    public Split getPipelines()              { return pipelines; }
    public void  setPipelines(Split v)       { this.pipelines = v; }
}
