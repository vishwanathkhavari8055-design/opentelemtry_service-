package com.trinity.observability.dto.services;

/**
 * Query params for {@code GET /api/services/uptime}.
 *
 * <p>Spring binds these via setter — keep field names matching the public
 * query-string keys ({@code window}, {@code livenessSec}). Defaults are
 * intentionally absent here so the service layer can read them from
 * {@link com.trinity.observability.constants.AppConstants}; that keeps the
 * "what is the default window?" decision in one place rather than scattered
 * across DTO + service.</p>
 */
public class ServicesUptimeRequestDTO {

    /** Relative ({@code now-1h}) or absolute ISO-8601 start of the lookback window. */
    private String window;

    /** Status flips to {@code DOWN} when last_seen is older than this many seconds. */
    private Integer livenessSec;

    public String getWindow()             { return window; }
    public void   setWindow(String w)     { this.window = w; }

    public Integer getLivenessSec()             { return livenessSec; }
    public void    setLivenessSec(Integer s)    { this.livenessSec = s; }
}
