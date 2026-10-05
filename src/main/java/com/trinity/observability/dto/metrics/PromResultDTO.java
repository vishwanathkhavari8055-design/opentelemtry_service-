package com.trinity.observability.dto.metrics;

import java.util.List;
import java.util.Map;

/**
 * The result of a PromQL query, flattened for the UI.
 *
 * Prometheus returns points as two-element arrays of mixed type —
 * {@code [1785838140, "8388608"]}, number then STRING. That shape is preserved
 * rather than normalised away: the string is how Prometheus avoids losing
 * precision on int64 counters, and re-encoding it as a JSON number here would
 * quietly round large values. The frontend parses it once, at the point of
 * plotting, where the precision no longer matters.
 *
 * {@code resultType} is "matrix" for a range query (points over time) and
 * "vector" for an instant query (one point per series).
 */
public class PromResultDTO {

    private String resultType;
    private List<Series> series;
    private boolean supported = true;
    private String error;

    public static class Series {
        /** All labels including __name__, exactly as returned. */
        private Map<String, String> labels;
        /** [[epochSeconds, "value"], ...] — see the note above. */
        private List<List<Object>> values;

        public Series() {}

        public Series(Map<String, String> labels, List<List<Object>> values) {
            this.labels = labels;
            this.values = values;
        }

        public Map<String, String> getLabels()   { return labels; }
        public List<List<Object>> getValues()    { return values; }
        public void setLabels(Map<String, String> v) { this.labels = v; }
        public void setValues(List<List<Object>> v)  { this.values = v; }
    }

    public String getResultType()   { return resultType; }
    public List<Series> getSeries() { return series; }
    public boolean isSupported()    { return supported; }
    public String getError()        { return error; }
    public void setResultType(String v)     { this.resultType = v; }
    public void setSeries(List<Series> v)   { this.series = v; }
    public void setSupported(boolean v)     { this.supported = v; }
    public void setError(String v)          { this.error = v; }
}
