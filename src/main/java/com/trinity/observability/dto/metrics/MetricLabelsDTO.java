package com.trinity.observability.dto.metrics;

import java.util.List;

/** Label names, or the values of one label — both are just a sorted list. */
public class MetricLabelsDTO {

    private List<String> items;
    private boolean supported = true;
    private String error;

    public MetricLabelsDTO() {}

    public MetricLabelsDTO(List<String> items) { this.items = items; }

    public List<String> getItems()  { return items; }
    public boolean isSupported()    { return supported; }
    public String getError()        { return error; }
    public void setItems(List<String> v)  { this.items = v; }
    public void setSupported(boolean v)   { this.supported = v; }
    public void setError(String v)        { this.error = v; }
}
