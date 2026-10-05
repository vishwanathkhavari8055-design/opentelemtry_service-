package com.trinity.observability.dto.metrics;

/**
 * Query params for {@code GET /api/metrics/summary}.
 *
 * <p>{@code bucketSec} controls the sparkline granularity. Default in the
 * service layer is 60 (one point per minute) — enough resolution for an at-a-
 * glance sparkline without paying the network cost of a per-second series.</p>
 *
 * <p>{@code serviceName} is required on the OpenObserve path. Without it the
 * sparklines would average across every service emitting JVM metrics, which
 * is operationally meaningless (the "JVM heap" line would be an average of
 * unrelated processes). When absent, the service returns supported=true with
 * an empty payload and a note prompting the UI to pick a service.</p>
 */
public class MetricsSummaryRequestDTO {
    private String window;
    private Integer bucketSec;
    private String serviceName;

    public String  getWindow()         { return window; }
    public void    setWindow(String w) { this.window = w; }

    public Integer getBucketSec()              { return bucketSec; }
    public void    setBucketSec(Integer v)     { this.bucketSec = v; }

    public String  getServiceName()              { return serviceName; }
    public void    setServiceName(String s)      { this.serviceName = s; }
}
