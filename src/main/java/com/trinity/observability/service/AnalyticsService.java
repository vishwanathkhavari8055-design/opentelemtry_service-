package com.trinity.observability.service;

import com.trinity.observability.dto.analytics.AnalyticsResponseDTO;

/**
 * The Analytics Dashboard: log volume, counted against the Product Catalog.
 *
 * <h2>The catalog decides the rows, OpenObserve decides the numbers</h2>
 *
 * <p>Nothing here knows the name of a category, a product or a microservice.
 * The rows come from what is REGISTERED — so a product appears the moment
 * something is registered under it and disappears when the last one is removed,
 * with no list in code to keep in step. The counts come from one grouped query per
 * identifier column against the logs stream.</p>
 *
 * <h2>One request per drill level</h2>
 *
 * <p>Category → product → microservice, selected by which arguments are supplied.
 * Deliberately not one response containing the whole tree: the leaf level is a
 * count per microservice, and pre-computing every microservice of every product to
 * render one collapsed category would query the whole estate to display seven
 * rows.</p>
 *
 * <h2>The product tier can be skipped</h2>
 *
 * <p>A caller that wants every microservice in a category — not its products — asks
 * for {@code level=microservice} with just the category. That is a real requirement
 * rather than a convenience: four of the seven categories have no product tier at
 * all, so a screen that always drilled category → product → microservice would
 * behave differently for Tools than for Applications, for a reason that is an
 * accident of the taxonomy rather than anything the operator cares about.</p>
 */
public interface AnalyticsService {

    /**
     * Log counts for one level of the catalog, with the level inferred.
     *
     * <p>Kept so callers that only ever drill the full path do not have to name a
     * level they are not choosing.</p>
     */
    default AnalyticsResponseDTO.LogCounts logCounts(String category, String product,
            String window) {
        return logCounts(category, product, window, null);
    }

    /**
     * Log counts for one level of the catalog.
     *
     * @param category null for the category level; a category code to break that
     *                 category down
     * @param product  a product code, WITH its category, to break that product down
     *                 by microservice
     * @param window   a relative window expression such as {@code now-24h}; blank
     *                 means the service's default
     * @param level    the grouping to return — {@code category}, {@code product} or
     *                 {@code microservice}. Blank infers it from the arguments above,
     *                 which is the historical behaviour. Supplying
     *                 {@code microservice} with only a category flattens the product
     *                 tier away and returns every microservice in that category.
     * @return rows with total / info / warn / error / debug, never null
     */
    AnalyticsResponseDTO.LogCounts logCounts(String category, String product, String window,
            String level);

    /**
     * The catalog's shape, with NO counts — what the cascading pickers are built from.
     *
     * <p>Separate from {@link #logCounts} because it touches no telemetry. Filling
     * Category → Product → Microservice from the counting endpoint would cost an
     * OpenObserve query per dropdown, before the table ran its own; this reads the
     * registry and returns.</p>
     */
    AnalyticsResponseDTO.Scope scope();
}
