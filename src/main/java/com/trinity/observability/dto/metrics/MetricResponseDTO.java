package com.trinity.observability.dto.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricResponseDTO {
    @JsonProperty("metric.name")
    private String name;
    
    private Double value;
    
    @JsonProperty("@timestamp")
    private String timestamp;
    
    @JsonProperty("service.name")
    private String serviceName;
    
    @JsonProperty("host.name")
    private String hostName;
    
    @JsonProperty("thread.state")
    private String threadState;
    
    @JsonProperty("thread.type")
    private String threadType;
    
    private Long duration;

    public MetricResponseDTO() {
        // Jackson deserialization
    }


    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public String getThreadState() { return threadState; }
    public void setThreadState(String threadState) { this.threadState = threadState; }
    public String getThreadType() { return threadType; }
    public void setThreadType(String threadType) { this.threadType = threadType; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }

    public static class MetricResponseDTOBuilder {
        private String name;
        public MetricResponseDTOBuilder name(String name) { this.name = name; return this; }
        public MetricResponseDTO build() {
            MetricResponseDTO dto = new MetricResponseDTO();
            dto.setName(name);
            return dto;
        }
    }
    public static MetricResponseDTOBuilder builder() { return new MetricResponseDTOBuilder(); }
}
