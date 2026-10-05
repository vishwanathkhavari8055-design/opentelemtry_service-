package com.trinity.observability.dto.services;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/services/{name}/vitals}.
 *
 * <p>A small fixed grid of "vital signs" for one service, sourced from the
 * Spring Boot Actuator + Micrometer + Prometheus metric streams that the OTel
 * Collector lands in OpenObserve. Each entry in {@link #tiles} is independent:
 * a missing metric stream produces an absent key (rather than a zero), so the
 * UI can distinguish "no signal" from "zero traffic".</p>
 *
 * <p>Stable tile keys the UI iterates in order:</p>
 * <ul>
 *   <li>{@code reqRate}        — req/s from {@code http_server_requests_seconds_count}</li>
 *   <li>{@code errorRate}      — 5xx share, % of total requests</li>
 *   <li>{@code latencyMs}      — recent max latency from {@code http_server_requests_seconds_max}</li>
 *   <li>{@code poolActive}     — Hikari active connections (raw, not a %)</li>
 *   <li>{@code heapUsedMb}     — JVM heap used in MB, filtered to {@code area='heap'}</li>
 *   <li>{@code errorLogRate}   — error logs/min from {@code logback_events_total{level='ERROR'}}</li>
 * </ul>
 *
 * <p>{@link #downstream} aggregates outbound HTTP calls (RestTemplate / WebClient)
 * by {@code client_name}, giving a coarse "who does this service depend on"
 * view alongside the request/error/latency signals per dependency.</p>
 */
public class ServiceVitalsResponseDTO {

    public static class Point {
        private String timestamp;
        private double value;

        public Point() {}
        public Point(String t, double v) { this.timestamp = t; this.value = v; }

        public String getTimestamp()             { return timestamp; }
        public void   setTimestamp(String t)     { this.timestamp = t; }
        public double getValue()                 { return value; }
        public void   setValue(double v)         { this.value = v; }
    }

    public static class Tile {
        /** Most recent value (already rate-adjusted for counters). */
        private Double latest;
        /** Bucketed series — for counters these are per-bucket rates, not cumulative. */
        private List<Point> sparkline;
        /** Display unit: {@code "req/s"}, {@code "%"}, {@code "ms"}, {@code "MB"}, {@code "/min"}, {@code ""}. */
        private String unit;
        /** Short human label for the tile header. */
        private String label;

        public Tile() {}
        public Tile(String label, String unit, Double latest, List<Point> sparkline) {
            this.label = label; this.unit = unit; this.latest = latest; this.sparkline = sparkline;
        }

        public Double       getLatest()                 { return latest; }
        public void         setLatest(Double v)         { this.latest = v; }
        public List<Point>  getSparkline()              { return sparkline; }
        public void         setSparkline(List<Point> v) { this.sparkline = v; }
        public String       getUnit()                   { return unit; }
        public void         setUnit(String v)           { this.unit = v; }
        public String       getLabel()                  { return label; }
        public void         setLabel(String v)          { this.label = v; }
    }

    public static class DownstreamCall {
        private String clientName;
        private Double reqRate;
        private Double errorRatePct;
        private Double maxLatencyMs;

        /** Default constructor for JSON deserialization. */
        public DownstreamCall() {
            // Jackson deserialization
        }

        public String getClientName()                  { return clientName; }
        public void   setClientName(String v)          { this.clientName = v; }
        public Double getReqRate()                     { return reqRate; }
        public void   setReqRate(Double v)             { this.reqRate = v; }
        public Double getErrorRatePct()                { return errorRatePct; }
        public void   setErrorRatePct(Double v)        { this.errorRatePct = v; }
        public Double getMaxLatencyMs()                { return maxLatencyMs; }
        public void   setMaxLatencyMs(Double v)        { this.maxLatencyMs = v; }
    }

    private String  serviceName;
    private String  windowStart;
    private String  windowEnd;
    private int     bucketSec;
    private String  backend;
    private boolean supported;

    private Map<String, Tile>      tiles;
    private List<DownstreamCall>   downstream;
    /** Diagnostic strings — surface in the UI footer when a stream was unavailable. */
    private List<String>           notes;

    public String getServiceName()                  { return serviceName; }
    public void   setServiceName(String v)          { this.serviceName = v; }

    public String getWindowStart()                  { return windowStart; }
    public void   setWindowStart(String v)          { this.windowStart = v; }

    public String getWindowEnd()                    { return windowEnd; }
    public void   setWindowEnd(String v)            { this.windowEnd = v; }

    public int    getBucketSec()                    { return bucketSec; }
    public void   setBucketSec(int v)               { this.bucketSec = v; }

    public String getBackend()                      { return backend; }
    public void   setBackend(String v)              { this.backend = v; }

    public boolean isSupported()                    { return supported; }
    public void    setSupported(boolean v)          { this.supported = v; }

    public Map<String, Tile>   getTiles()                       { return tiles; }
    public void                setTiles(Map<String, Tile> v)    { this.tiles = v; }

    public List<DownstreamCall> getDownstream()                 { return downstream; }
    public void                 setDownstream(List<DownstreamCall> v) { this.downstream = v; }

    public List<String>        getNotes()                       { return notes; }
    public void                setNotes(List<String> v)         { this.notes = v; }
}
