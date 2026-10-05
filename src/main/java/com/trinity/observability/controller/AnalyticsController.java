package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.analytics.AnalyticsResponseDTO;
import com.trinity.observability.service.AnalyticsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The Analytics Dashboard's read API.
 *
 * <p>One endpoint, three levels. Which level you get is decided by what you supply:
 * nothing for categories, a category for its products, a category and a product for
 * its microservices. Three endpoints would have been three copies of the same
 * aggregation differing only in a GROUP BY.</p>
 */
@RestController
@RequestMapping("/api/analytics")
@Tag(name = "Analytics", description = "Log volume, aggregated over the Product Catalog")
public class AnalyticsController {

    private static final Logger log = LogManager.getLogger(AnalyticsController.class);

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/scope")
    @Operation(summary = "The catalog shape behind the dashboard's pickers",
            description = "Categories, their products, and the microservices registered under "
                    + "each — with NO counts, so it runs no telemetry query. This is what fills "
                    + "the Category -> Product -> Microservice dropdowns in one call; deriving "
                    + "them from /log-counts would cost an OpenObserve query per dropdown. "
                    + "Only ENABLED registrations appear, and a category or product with nothing "
                    + "under it is omitted rather than offered as a selection whose table would "
                    + "always be empty.")
    public AnalyticsResponseDTO.Scope scope() {
        log.info("GET /api/analytics/scope received");
        return service.scope();
    }

    @GetMapping("/log-counts")
    @Operation(summary = "Log counts per catalog level",
            description = "Total, info, warn, error and debug counts for whatever the Product "
                    + "Catalog currently holds. Supply nothing for a row per CATEGORY, a `category` for a "
                    + "row per PRODUCT within it, or a `category` and `product` for a row per "
                    + "MICROSERVICE. "
                    + "The rows are derived from what is REGISTERED, never from a list in code, so "
                    + "a product appears as soon as something is registered under it. "
                    + "Only ENABLED resources are counted, matching the scoping the rest of the "
                    + "application applies to Logs, Traces and Metrics. "
                    + "Total is every matching log line, so it is deliberately NOT "
                    + "info+warn+error+debug — this stream also carries TRACE and a large "
                    + "unspecified bucket, which are reported as `other` so each row reconciles. "
                    + "`level` overrides the grouping the arguments above would imply: pass "
                    + "`microservice` with only a `category` to skip the product tier and get "
                    + "EVERY microservice in that category, each carrying the product it came "
                    + "from as `parentLabel`. Rows also report `leafCount`, the number of "
                    + "microservices counted, alongside `childCount`, the number of things at "
                    + "the next drill level.")
    public AnalyticsResponseDTO.LogCounts logCounts(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "product", required = false) String product,
            @RequestParam(name = "window", required = false) String window,
            @RequestParam(name = "level", required = false) String level) {
        log.info("GET /api/analytics/log-counts?category={}&product={}&window={}&level={} received",
                category, product, window, level);
        return service.logCounts(category, product, window, level);
    }
}
