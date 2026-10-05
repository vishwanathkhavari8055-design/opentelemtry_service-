package com.trinity.observability.dto.metrics;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/metrics/summary}.
 *
 * <p>{@code sparklines} keys are stable identifiers the UI knows about:
 * {@code jvmHeap}, {@code cpuUsage}, {@code threadsLive}, {@code httpReqsCount}.
 * Each value is a list of {@code {t, v}} samples bucketed at the requested
 * step. {@code latestValues} carries the most recent sample per series so the
 * UI can render a number alongside the sparkline without scanning the points
 * list.</p>
 *
 * <p>If a particular stream is missing (e.g. {@code process_cpu_usage} isn't
 * emitted) its key is simply absent from both maps — the UI renders an
 * empty placeholder for that card slot.</p>
 */
public class MetricsSummaryResponseDTO {

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

    private Map<String, List<Point>> sparklines;
    private Map<String, Double>      latestValues;
    private String  windowStart;
    private String  windowEnd;
    private int     bucketSec;
    private String  backend;
    private boolean supported;

    public Map<String, List<Point>> getSparklines()             { return sparklines; }
    public void                     setSparklines(Map<String, List<Point>> v) { this.sparklines = v; }

    public Map<String, Double>      getLatestValues()           { return latestValues; }
    public void                     setLatestValues(Map<String, Double> v) { this.latestValues = v; }

    public String getWindowStart()                              { return windowStart; }
    public void   setWindowStart(String v)                      { this.windowStart = v; }

    public String getWindowEnd()                                { return windowEnd; }
    public void   setWindowEnd(String v)                        { this.windowEnd = v; }

    public int    getBucketSec()                                { return bucketSec; }
    public void   setBucketSec(int v)                           { this.bucketSec = v; }

    public String getBackend()                                  { return backend; }
    public void   setBackend(String v)                          { this.backend = v; }

    public boolean isSupported()                                { return supported; }
    public void    setSupported(boolean v)                      { this.supported = v; }
}
