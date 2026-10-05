package com.trinity.observability.dto.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row in {@link ServicesUptimeResponseDTO#getItems()}.
 *
 * <p>Field names are split between camelCase (Java idiom) and OTel-style
 * dotted keys via {@link JsonProperty} — {@code service.name} and
 * {@code host.name}. The UI normalisation layer already handles both spellings
 * (see {@code src/services/api.js}) and other DTOs in this package follow the
 * same convention, so we keep it for consistency.</p>
 *
 * <p>Two health signals are surfaced side-by-side:
 * <ul>
 *   <li>{@link #status} — heartbeat-derived: was a metric sample observed
 *       within the liveness window? Implies "OTel pipeline is intact".</li>
 *   <li>{@link #healthStatus} — Actuator's {@code /health}, surfaced as a
 *       Micrometer gauge ({@code application_health}) and scraped by the
 *       OTel Collector. Implies "the service itself reports healthy".</li>
 * </ul>
 * Disagreement between the two is diagnostic: heartbeat-UP + health-DOWN
 * means a dependency just broke; heartbeat-DOWN + health-UP means the
 * collector or the agent is wedged.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceUptimeItemDTO {

    @JsonProperty("service.name")
    private String serviceName;

    @JsonProperty("host.name")
    private String hostName;

    /** Heartbeat-derived: {@code "UP"} or {@code "DOWN"}. */
    private String status;

    /** ISO-8601 timestamp of the most recent heartbeat metric for this service. */
    private String lastSeenTimestamp;

    /** Seconds since the last heartbeat. Useful for the UI when status=DOWN. */
    private Long lastSeenAgoSec;

    /**
     * Seconds since the process started, when the {@code process_uptime}
     * metric stream exists. {@code null} if the agent doesn't emit it.
     */
    private Long processUptimeSec;

    /** How many heartbeat samples landed in the window — UI surfaces this for debugging. */
    private Long metricCount;

    // ── Actuator health signal (sourced from the application_health gauge) ──
    /**
     * Service-reported health, derived from the latest {@code application_health}
     * gauge sample for this service. {@code "UP"} when the gauge is 1.0,
     * {@code "DOWN"} when 0.0, {@code null} when no sample exists in the window
     * (i.e. the service doesn't expose the health gauge or its Actuator scrape
     * hasn't happened yet).
     */
    private String healthStatus;

    /**
     * ISO-8601 timestamp of the most recent {@code application_health} sample.
     * {@code null} when no sample exists.
     */
    private String healthLastSeenTimestamp;

    /** Default constructor for framework instantiation. */
    public ServiceUptimeItemDTO() {
        // framework instantiation
    }

    public String getServiceName()                  { return serviceName; }
    public void   setServiceName(String s)          { this.serviceName = s; }

    public String getHostName()                     { return hostName; }
    public void   setHostName(String h)             { this.hostName = h; }

    public String getStatus()                       { return status; }
    public void   setStatus(String s)               { this.status = s; }

    public String getLastSeenTimestamp()            { return lastSeenTimestamp; }
    public void   setLastSeenTimestamp(String t)    { this.lastSeenTimestamp = t; }

    public Long   getLastSeenAgoSec()               { return lastSeenAgoSec; }
    public void   setLastSeenAgoSec(Long v)         { this.lastSeenAgoSec = v; }

    public Long   getProcessUptimeSec()             { return processUptimeSec; }
    public void   setProcessUptimeSec(Long v)       { this.processUptimeSec = v; }

    public Long   getMetricCount()                  { return metricCount; }
    public void   setMetricCount(Long v)            { this.metricCount = v; }

    public String getHealthStatus()                 { return healthStatus; }
    public void   setHealthStatus(String v)         { this.healthStatus = v; }

    public String getHealthLastSeenTimestamp()      { return healthLastSeenTimestamp; }
    public void   setHealthLastSeenTimestamp(String v) { this.healthLastSeenTimestamp = v; }
}
