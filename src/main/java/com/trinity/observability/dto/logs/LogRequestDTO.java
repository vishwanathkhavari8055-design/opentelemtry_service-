package com.trinity.observability.dto.logs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogRequestDTO {
    private int page = 0;
    private int size = 20;
    /**
     * Which stream to read, or null/blank for the configured default
     * ({@code openobserve.logs-stream}).
     *
     * <p>Added because the UI's stream picker used to be decorative: it listed a
     * hardcoded {@code ['default']} and this DTO had nowhere to put a choice, so
     * selecting anything changed nothing. The picker is now populated from
     * OpenObserve, and this is what makes the selection real.</p>
     *
     * <p>A stream whose schema is not the OTel logs schema is read adaptively — see
     * LogsServiceImpl. That is what lets {@code _rumdata} and {@code _rumlog} be
     * selected at all: the fixed projection fails on them with
     * {@code unknown field 'severity'}.</p>
     */
    private String stream;

    private String severity;
    private String serviceName;
    // Multi-service filter (issue: cross-service log filtering). When two or
    // more services are selected the backend returns only logs whose trace_id
    // touches EVERY selected service — service_name IN (selected) AND
    // trace_id IN (traces whose distinct services ⊇ selected). A single
    // entry (or none) keeps legacy single-service behaviour. Wire shape is
    // repeated `serviceName=A&serviceName=B` so the existing single-service
    // callers keep working; Spring binds both into the same list via the
    // `setServiceName` adapter below.
    private List<String> serviceNames = new ArrayList<>();
    private String hostName;
    private String osType;
    private String scopeName;
    private String startTime;
    private String endTime;
    private String search;
    private String traceId;
    private String sort;
    private String severityMode;

    /**
     * How a multi-service selection is combined.
     *
     * <ul>
     *   <li>{@code ANY} (default) — {@code service_name IN (selected)}. Logs
     *       from any of the chosen services.</li>
     *   <li>{@code ALL} — only logs whose {@code trace_id} touches EVERY
     *       chosen service. Cross-service correlation; this was the old
     *       default and is preserved for callers that want it.</li>
     * </ul>
     *
     * Ignored when fewer than two services are selected.
     */
    private String serviceMatch;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getServiceName() {
        // Legacy single-value accessor — first non-blank entry of the list,
        // or the stored single value if the list was never populated. Kept so
        // existing log lines and ES-path code that read getServiceName() keep
        // compiling and returning a sensible scalar.
        if (serviceNames != null) {
            for (String s : serviceNames) {
                if (s != null && !s.trim().isEmpty())
                    return s;
            }
        }
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
        if (serviceName != null && !serviceName.trim().isEmpty()
                && (serviceNames == null || serviceNames.isEmpty())) {
            this.serviceNames = new ArrayList<>();
            this.serviceNames.add(serviceName);
        }
    }

    public List<String> getServiceNames() {
        return serviceNames == null ? Collections.emptyList() : serviceNames;
    }

    public void setServiceNames(List<String> serviceNames) {
        this.serviceNames = serviceNames == null ? new ArrayList<>() : serviceNames;
        // Keep the scalar in sync with the head of the list so legacy reads
        // via getServiceName() stay consistent.
        if (!this.serviceNames.isEmpty()) {
            this.serviceName = this.serviceNames.get(0);
        }
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getOsType() {
        return osType;
    }

    public void setOsType(String osType) {
        this.osType = osType;
    }

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getSeverityMode() {
        return severityMode;
    }

    public void setSeverityMode(String severityMode) {
        this.severityMode = severityMode;
    }

    public String getServiceMatch() {
        return serviceMatch;
    }

    public void setServiceMatch(String serviceMatch) {
        this.serviceMatch = serviceMatch;
    }

    /** True only for an explicit {@code serviceMatch=ALL}; anything else —
     *  including absent — means ANY. */
    public boolean isServiceMatchAll() {
        return serviceMatch != null && "ALL".equalsIgnoreCase(serviceMatch.trim());
    }
}
