package com.trinity.observability.service;

import com.trinity.observability.dto.services.ServiceVitalsRequestDTO;
import com.trinity.observability.dto.services.ServiceVitalsResponseDTO;
import com.trinity.observability.dto.services.ServicesUptimeRequestDTO;
import com.trinity.observability.dto.services.ServicesUptimeResponseDTO;

public interface ServicesService {

    /**
     * Aggregate per-service uptime/liveness over the requested window.
     *
     * <p>OpenObserve path (the only supported path today): two SQL queries
     * against the metrics streams — a heartbeat scan against
     * {@code jvm_memory_used} (universal) and a best-effort lookup against
     * {@code process_uptime} for seconds-since-start. Results are merged by
     * {@code service_name}. Services whose last heartbeat is older than
     * {@code livenessSec} are tagged {@code DOWN}.</p>
     *
     * <p>ES path: returns an empty list with {@code supported=false}. The
     * design intentionally scopes this feature to OpenObserve only; the ES
     * branch exists so the UI doesn't crash if someone flips the flag back.</p>
     */
    ServicesUptimeResponseDTO getUptime(ServicesUptimeRequestDTO request);

    /**
     * Aggregate "vital signs" for a single service over the requested window.
     *
     * <p>Pulls a small fixed set of Spring Boot Actuator-derived metric streams
     * from OpenObserve (request rate, error rate, latency, DB pool, JVM heap,
     * error log rate) plus a downstream-calls roll-up from
     * {@code http_client_requests_seconds_count}. Each tile is fetched
     * independently — a service that doesn't expose Hikari or Logback will
     * simply have those tiles absent. ES path returns {@code supported=false}.
     * </p>
     */
    ServiceVitalsResponseDTO getServiceVitals(String serviceName, ServiceVitalsRequestDTO request);
}
