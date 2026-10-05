package com.trinity.observability.service;

import com.trinity.observability.dto.logs.LogPageResponseDTO;
import com.trinity.observability.dto.logs.LogRequestDTO;
import com.trinity.observability.dto.logs.LogServicesResponseDTO;
import com.trinity.observability.dto.logs.LogTraceSummaryPageDTO;
import com.trinity.observability.dto.logs.LogsSummaryRequestDTO;
import com.trinity.observability.dto.logs.LogsSummaryResponseDTO;

public interface LogsService {
    /**
     * Returns one page of logs plus a {@code hasMore} flag the UI uses to
     * decide whether to enable the "next page" button (issue #8). The wrapper
     * shape replaces the previous bare {@code List<LogResponseDTO>} return.
     */
    LogPageResponseDTO getLogs(LogRequestDTO request);

    /**
     * Returns logs grouped by trace ID — one row per distinct trace within
     * the filter window. Used by the LogsView "Grouped" toggle (issue #2).
     * Counts and top-severity are computed server-side so they reflect the
     * whole window, not just the page of logs currently loaded in the UI.
     */
    LogTraceSummaryPageDTO getTraceGroups(LogRequestDTO request);

    /**
     * Aggregate count breakdown over the requested window — feeds the Logs
     * card on the Summary screen. One SQL groups by severity + service so
     * we get total, per-severity, and top-error-services in a single round
     * trip. OpenObserve-only; the ES path returns {@code supported=false}.
     */
    LogsSummaryResponseDTO getLogsSummary(LogsSummaryRequestDTO request);

    /**
     * Distinct {@code service_name} values present in the logs stream over
     * the requested window. Feeds the UI's logs service-filter dropdown so
     * it reflects every service that produced logs in the window — not just
     * whichever names happened to fall inside a paginated row fetch.
     *
     * <p>OpenObserve-only (parity with {@link #getLogsSummary}). The ES path
     * returns {@code supported=false} with an empty list.</p>
     *
     * <p>Each service is returned with its per-service count.</p>
     *
     * <p>The explicit range exists so the UI can ask about EXACTLY the window the
     * table is showing. With a fixed window the two disagreed badly: a 24h
     * discovery offered 111 services while a "Past 15 Minutes" table only had
     * 35 with any data, so three quarters of the list returned nothing when
     * selected and read as phantom entries.</p>
     *
     * @param window    relative-time expression (e.g. {@code now-7d}); used only when
     *                  startTime/endTime are absent
     * @param startTime explicit range start (ISO or relative), optional
     * @param endTime   explicit range end, optional
     */
    LogServicesResponseDTO listServices(String window, String startTime, String endTime);
}
