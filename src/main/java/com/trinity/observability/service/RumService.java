package com.trinity.observability.service;

import com.trinity.observability.dto.rum.RumResponseDTO;

/**
 * Real User Monitoring reads.
 *
 * <p>Four methods, one per screen the UI shows, because each needs a different set of
 * aggregations over the same stream and merging them would mean every screen paying
 * for every other screen's queries. They share a filter signature: any of
 * {@code service}, {@code env} and {@code version} may be null, meaning "all".</p>
 *
 * <p>The RUM stream names are configuration, not constants — see
 * {@code openobserve.rum-data-stream} / {@code openobserve.rum-log-stream}. Nothing
 * here assumes a particular application, service or environment exists.</p>
 */
public interface RumService {

    /**
     * The values the filter dropdowns can offer, for the given window.
     *
     * <p>Scoped to the window on purpose: offering a service that stopped reporting
     * last month means offering a selection whose every panel is empty.</p>
     */
    RumResponseDTO.Scope scope(String window);

    /**
     * Core Web Vitals, error counters and the API breakdown.
     *
     * @param window relative expression such as {@code now-6h}; null means the
     *               service default
     */
    RumResponseDTO.Performance performance(String window, String service, String env,
                                           String version);

    /**
     * Session list plus its summary.
     *
     * @param page 0-based page of the list; the summary always covers the whole window
     * @param size page size, clamped by the implementation
     */
    RumResponseDTO.Sessions sessions(String window, String service, String env, String version,
                                     int page, int size);

    /**
     * Distinct error issues, their occurrence series, and the crash-free rate.
     *
     * @param handling optional filter: {@code handled} | {@code unhandled}; null for both
     */
    RumResponseDTO.Errors errors(String window, String service, String env, String version,
                                 String handling, int page, int size);
}
