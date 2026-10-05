package com.trinity.observability.dto.services;

/**
 * Query params for {@code GET /api/services/{name}/vitals}.
 *
 * <p>The {@code name} path variable is bound separately by the controller and
 * not represented here. This DTO carries only the optional query-string
 * tuning knobs: lookback {@link #window} and the sparkline {@link #bucketSec}.
 * Defaults are read from {@link com.trinity.observability.constants.AppConstants}
 * by the service so they live in one place.</p>
 */
public class ServiceVitalsRequestDTO {

    /** Relative ({@code now-1h}) or absolute ISO-8601 start of the lookback window. */
    private String window;

    /** Sparkline bucket width in seconds. Defaults to 60 when null/0. */
    private Integer bucketSec;

    public String getWindow()                { return window; }
    public void   setWindow(String w)        { this.window = w; }

    public Integer getBucketSec()            { return bucketSec; }
    public void    setBucketSec(Integer v)   { this.bucketSec = v; }
}
