package com.trinity.observability.service;

import com.trinity.observability.dto.alerts.AlertQueryRequestDTO;
import com.trinity.observability.dto.alerts.AlertQueryResponseDTO;

/**
 * Reads and transitions alerts for the enterprise screen.
 *
 * <p>Split from {@link AlertNotificationsService} rather than bolted onto it. That
 * interface is the contract the current UI calls and has to keep answering exactly
 * as it does; this one is free to have multi-select filters, server-side sorting,
 * real pagination and the CLOSED/REOPENED transitions without renegotiating it.</p>
 *
 * <h2>Everything is filtered, sorted and paged in the database</h2>
 *
 * <p>Not a preference — a correctness requirement at the scale asked for. Fetching
 * a page and then filtering it in the client gives short pages and a total that
 * disagrees with the rows; sorting a page rather than the table sorts the wrong
 * set entirely. Both look like they work on a hundred alerts and are wrong on
 * thousands.</p>
 */
public interface AlertQueryService {

    /** A filtered, sorted, paged slice of the alert table. */
    AlertQueryResponseDTO.Page search(AlertQueryRequestDTO.Search request);

    /**
     * Counts for the summary cards, over the SAME window as {@link #search}.
     *
     * <p>Sharing the window matters: cards counting all time above a table showing
     * one hour would disagree, and nothing on screen would say which to believe.</p>
     */
    AlertQueryResponseDTO.Summary summary(AlertQueryRequestDTO.Search request);

    /** One alert with its audit trail, its rule and its raw event. */
    AlertQueryResponseDTO.Detail detail(Long id);

    /** Distinct values for the filter dropdowns, derived from stored alerts. */
    AlertQueryResponseDTO.FilterOptions filterOptions();

    /** A human takes ownership. Survives re-firing. */
    AlertQueryResponseDTO.WriteResult acknowledge(Long id, AlertQueryRequestDTO.Transition body);

    /** The condition is over. */
    AlertQueryResponseDTO.WriteResult resolve(Long id, AlertQueryRequestDTO.Transition body);

    /** A human is finished with it — never inferred, unlike resolution. */
    AlertQueryResponseDTO.WriteResult close(Long id, AlertQueryRequestDTO.Transition body);

    /** It was not actually over. Returns to FIRING and clears the prior sign-off. */
    AlertQueryResponseDTO.WriteResult reopen(Long id, AlertQueryRequestDTO.Transition body);

    /**
     * One transition over many alerts.
     *
     * <p>Per-id outcomes, not one boolean: acknowledging forty alerts where three
     * had already resolved is a partial success, and reporting it as a failure would
     * make the operator redo the thirty-seven that worked.</p>
     */
    AlertQueryResponseDTO.BulkResult bulk(AlertQueryRequestDTO.BulkTransition body);
}
