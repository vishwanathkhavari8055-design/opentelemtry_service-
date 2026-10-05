package com.trinity.observability.service;

import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.dto.alerts.AlertQueryResponseDTO;

/**
 * Alerts read straight out of the OpenObserve stream, bypassing PostgreSQL.
 *
 * <h2>Why this exists next to a perfectly good stored path</h2>
 *
 * <p>The stored path is better in every way that matters day to day: it has the
 * lifecycle, the episode grouping, the audit trail and a database that answers in
 * milliseconds. It has one weakness, and it is structural — everything it can show
 * has to have been INGESTED first, so any fault between OpenObserve and the poller
 * empties the screen. An empty alerts table is the most dangerous thing this
 * application can display, because it is indistinguishable from good news.</p>
 *
 * <p>This path has the opposite shape. It asks OpenObserve directly, every time, and
 * therefore cannot be stale — but it has no ids, no acknowledgement, no history and
 * no auto-resolve, because none of that exists in the stream. It is a way to SEE the
 * firings when the pipeline is down, not a second implementation of the pipeline.</p>
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a fallback the stored path silently degrades into. The caller asks for it
 * explicitly and the response says {@code source: "live-stream"}, so the screen can
 * state which one it is showing. Rows that look identical but cannot be acknowledged
 * would be worse than no rows at all.</p>
 */
public interface AlertLiveStreamService {

    /**
     * Firings in the window, grouped by fingerprint.
     *
     * <p>Grouped rather than raw because the stream holds one row per firing: a rule
     * that fired 150 times in five seconds is 150 rows, and a table of those is not
     * readable. Grouping reproduces what an episode looks like — first seen, last
     * seen, how many — from the only material available.</p>
     *
     * <p>Never throws for an unreachable OpenObserve. A fallback that fails when the
     * thing it is a fallback for has failed is not a fallback; the page comes back
     * empty with {@code partial} set, and the caller says so.</p>
     *
     * @param request window, size, page, search and severity; other filters are
     *                ignored because the stream does not carry the columns they name
     * @return a page whose {@code source} is {@code live-stream}
     */
    AlertQueryResponseDTO.Page live(AlertQueryRequestDTO.Search request);
}
