package com.trinity.observability.service;

import com.trinity.observability.dto.alerts.AlertPreviewRequestDTO;
import com.trinity.observability.dto.alerts.AlertPreviewResponseDTO;

/**
 * Runs an alert's conditions against real data before the rule is saved.
 *
 * <h2>The class of bug this catches</h2>
 *
 * <p>Everything about an alert rule is checkable except the only thing that
 * matters. Names, operators, thresholds and stream types are all validated on
 * the way in; whether the rule will ever match a row is not, and cannot be —
 * that depends on what the telemetry actually contains. So a rule with a typo in
 * a column name, or a threshold on the wrong scale, or a filter on a service
 * that spells its name differently, passes every check this service makes,
 * syncs to OpenObserve, reports itself SYNCED and healthy, and never fires.</p>
 *
 * <p>Nobody finds out until the incident it was written for. This closes that
 * gap the only way it can be closed: by running the query.</p>
 *
 * <h2>Stepwise, because "0 rows" is not a diagnosis</h2>
 *
 * <p>The preview evaluates the conditions cumulatively — the bare window first,
 * then one condition, then two — so the step where the count collapses is the
 * condition at fault. A single final number would say the rule matches nothing
 * and leave the operator to bisect it by hand, which is exactly the work this is
 * supposed to save.</p>
 *
 * <h2>Read-only</h2>
 *
 * <p>Nothing is stored, nothing is pushed to OpenObserve, and no rule has to
 * exist. A preview of a rule that was never saved is the common case — the
 * operator is still typing it.</p>
 */
public interface AlertPreviewService {

    /**
     * Evaluate a draft rule against the window it would watch.
     *
     * <p><b>Never throws for a query problem.</b> A column the stream does not
     * have, a stream that does not exist, a window OpenObserve cannot scan —
     * each is reported on the step or in the notice it belongs to, and the rest
     * of the ladder still runs. That is the whole point: a mistyped column is
     * THE common mistake, and the steps either side of it are the context that
     * makes it obvious. An exception page would throw that context away.</p>
     *
     * <p><b>Bad input still throws</b>, and the line between the two is whether
     * a query could be built at all. A request naming no stream, a column that
     * is not a plain identifier, or an operator this service cannot translate
     * produce no SQL to run, so there is no finding to report — the request
     * itself is wrong, and it comes back as a 400 naming the part at fault.</p>
     */
    AlertPreviewResponseDTO.Preview preview(AlertPreviewRequestDTO.Preview request);
}
