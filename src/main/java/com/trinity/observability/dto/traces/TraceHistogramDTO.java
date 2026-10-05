package com.trinity.observability.dto.traces;

import java.util.List;

/**
 * Response for {@code GET /api/traces/histogram} — the three chart panels
 * above the Spans / Traces tables.
 *
 * <ul>
 *   <li>{@code buckets} drive both the Rate line and the Errors bars: one
 *       bucket per time slice carrying total and error counts. They share a
 *       series because they come from one {@code GROUP BY histogram(...)} —
 *       two queries would risk the two charts disagreeing about the same
 *       instant.</li>
 *   <li>The same buckets carry p50/p95/max duration, which drives the
 *       Duration panel.</li>
 * </ul>
 *
 * <p>All three panels therefore read from ONE series, so they cannot disagree
 * about the x axis. The Duration panel used to plot a raw scatter of the
 * newest N spans instead, and that was measurably wrong: on a 6-hour window
 * the newest 2,000 spans covered 2.5 minutes, so the whole cloud sat in the
 * last 0.7% of the axis while Rate and Errors spanned the full width. Per
 * bucket percentiles cover the window by construction, and say more than a
 * sample of individual spans would.</p>
 */
public class TraceHistogramDTO {

    public static class Bucket {
        private String timestamp;
        private Long count;
        private Long errors;
        /** Duration percentiles for this bucket, MICROSECONDS. */
        private Long p50Us;
        private Long p95Us;
        private Long maxUs;

        public Bucket() {}
        public Bucket(String timestamp, Long count, Long errors,
                Long p50Us, Long p95Us, Long maxUs) {
            this.timestamp = timestamp;
            this.count = count;
            this.errors = errors;
            this.p50Us = p50Us;
            this.p95Us = p95Us;
            this.maxUs = maxUs;
        }

        public Long getP50Us()             { return p50Us; }
        public void setP50Us(Long v)       { this.p50Us = v; }

        public Long getP95Us()             { return p95Us; }
        public void setP95Us(Long v)       { this.p95Us = v; }

        public Long getMaxUs()             { return maxUs; }
        public void setMaxUs(Long v)       { this.maxUs = v; }

        public String getTimestamp()               { return timestamp; }
        public void   setTimestamp(String v)       { this.timestamp = v; }

        public Long getCount()                     { return count; }
        public void setCount(Long v)               { this.count = v; }

        public Long getErrors()                    { return errors; }
        public void setErrors(Long v)              { this.errors = v; }
    }

    private List<Bucket> buckets;
    /** The bucket width actually used, e.g. {@code "1 minute"} — the client
     *  labels the axis with it rather than guessing from point spacing. */
    private String interval;
    private boolean supported;
    private String backend;

    public List<Bucket> getBuckets()             { return buckets; }
    public void         setBuckets(List<Bucket> v) { this.buckets = v; }

    public String getInterval()             { return interval; }
    public void   setInterval(String v)     { this.interval = v; }

    public boolean isSupported()            { return supported; }
    public void    setSupported(boolean v)  { this.supported = v; }

    public String getBackend()              { return backend; }
    public void   setBackend(String v)      { this.backend = v; }
}
