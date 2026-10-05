package com.trinity.observability.dto.traces;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TraceRequestDTO {
    private int page = 0;
    private int size = 20;
    private String traceId;
    private String spanId;
    private String transactionName;
    private String serviceName;

    /**
     * Multi-service filter, bound from a repeated {@code serviceNames=A&serviceNames=B}
     * query string. Mirrors the same field on LogRequestDTO so both screens'
     * service pickers speak the same wire shape.
     *
     * <p>Semantics differ from the logs one, deliberately: here the services are
     * OR'd into a {@code service_name IN (...)}, because "show me these three
     * services" is the question a span list is asked. The logs filter ANDs at
     * trace grain instead — see LogRequestDTO.</p>
     */
    private List<String> serviceNames = new ArrayList<>();
    private String spanName;
    private String outcome;
    private String dbName;
    private String hostName;
    private String startTime;
    private String endTime;
    private String search;
    private String type;

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
    public String getTransactionName() { return transactionName; }
    public void setTransactionName(String transactionName) { this.transactionName = transactionName; }
    public String getServiceName() {
        // Legacy scalar accessor — first non-blank entry of the list, else the
        // stored single value. Keeps existing single-service callers working
        // unchanged now that the list exists alongside it.
        if (serviceNames != null) {
            for (String s : serviceNames) {
                if (s != null && !s.trim().isEmpty()) return s;
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
    public String getSpanName() { return spanName; }
    public void setSpanName(String spanName) { this.spanName = spanName; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getSearch() { return search; }
    public void setSearch(String search) { this.search = search; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}

