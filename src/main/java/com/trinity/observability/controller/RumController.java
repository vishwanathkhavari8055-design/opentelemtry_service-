package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.rum.RumResponseDTO;
import com.trinity.observability.service.RumService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Real User Monitoring — what the browser actually experienced.
 *
 * <p>Four endpoints, one per panel the UI draws, because each is a different
 * aggregation over the same RUM stream and a combined endpoint would make every
 * screen pay for every other screen's queries.</p>
 *
 * <p>All four take the same optional filters — {@code window}, {@code service},
 * {@code env}, {@code version} — and all four report a {@code meta} block saying
 * which organization and resolved time range the numbers came from, plus whether
 * OpenObserve could scan the whole window. Nothing is cached: RUM is read live.</p>
 *
 * <p>There is deliberately no source-map endpoint. Symbolicating a minified stack
 * needs the build's source maps uploaded and stored, which is a build-pipeline
 * concern rather than a read; the Error Tracking screen reports the raw
 * {@code file:line} the SDK sent.</p>
 */
@RestController
@RequestMapping("/api/rum")
@Tag(name = "RUM", description = "Real User Monitoring — sessions, Core Web Vitals and frontend errors")
public class RumController {

    private static final Logger log = LogManager.getLogger(RumController.class);

    /** Named `rumService`, not `service`: three endpoints below take a `service`
     *  REQUEST PARAMETER, which would shadow the field and silently turn every call
     *  on it into a compile error against String. */
    private final RumService rumService;

    public RumController(RumService rumService) {
        this.rumService = rumService;
    }

    @GetMapping("/scope")
    @Operation(summary = "Values the RUM filters can offer",
            description = "Distinct services, environments, versions and application ids seen in "
                    + "the window. Derived with SELECT DISTINCT over the RUM stream, never from a "
                    + "list in code, so a frontend appears here as soon as it reports its first "
                    + "event. Scoped to the window on purpose — offering a service that stopped "
                    + "reporting last month is offering a selection whose every panel is empty.")
    public RumResponseDTO.Scope scope(
            @RequestParam(name = "window", required = false) String window) {
        log.info("GET /api/rum/scope?window={} received", window);
        return rumService.scope(window);
    }

    @GetMapping("/performance")
    @Operation(summary = "Core Web Vitals, error counters and API timings",
            description = "Vitals are reported at the 75th percentile, which is how Core Web "
                    + "Vitals are defined — a mean would let one slow cold load misrepresent what "
                    + "most visitors saw. Every duration is MILLISECONDS: the browser SDK writes "
                    + "nanoseconds and the conversion happens server-side so no consumer has to "
                    + "know the wire unit. CLS carries no unit, being a dimensionless score. Each "
                    + "vital also reports the sample it was taken over, because they are not "
                    + "populated together — INP only exists once a user has interacted. The API "
                    + "list covers xhr/fetch resources only; static assets are excluded as they "
                    + "would bury the calls that matter.")
    public RumResponseDTO.Performance performance(
            @RequestParam(name = "window", required = false) String window,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "version", required = false) String version) {
        log.info("GET /api/rum/performance?window={}&service={}&env={}&version={} received",
                window, service, env, version);
        return rumService.performance(window, service, env, version);
    }

    @GetMapping("/sessions")
    @Operation(summary = "Session list with its summary",
            description = "One row per RUM session: who, on what, how long, how many errors and "
                    + "frustration signals. The summary describes EVERY session in the window "
                    + "while the list is paged — a bounce rate computed over one page would not be "
                    + "a bounce rate. Duration is the span from a session's first event to its "
                    + "last, in milliseconds, and the reported average is a MEDIAN: one tab left "
                    + "open overnight moves a mean and says nothing about a typical visit.")
    public RumResponseDTO.Sessions sessions(
            @RequestParam(name = "window", required = false) String window,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "50") int size) {
        log.info("GET /api/rum/sessions?window={}&service={}&page={}&size={} received",
                window, service, page, size);
        return rumService.sessions(window, service, env, version, page, size);
    }

    @GetMapping("/errors")
    @Operation(summary = "Distinct frontend error issues, with a crash-free rate",
            description = "Occurrences are grouped into ISSUES by (type, message, source): the "
                    + "same message thrown from two files is two bugs, and one file raising two "
                    + "messages is also two bugs, so grouping on the message alone would report a "
                    + "single issue nobody can fix in one place. `crashFreeSessionsPct` is "
                    + "measured against all sessions in the window, not against errored ones — "
                    + "the latter would read 0% whenever any error existed. The series bucket "
                    + "width is derived from the window so the chart keeps its shape at any span.")
    public RumResponseDTO.Errors errors(
            @RequestParam(name = "window", required = false) String window,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "handling", required = false) String handling,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "50") int size) {
        log.info("GET /api/rum/errors?window={}&service={}&handling={}&page={} received",
                window, service, handling, page);
        return rumService.errors(window, service, env, version, handling, page, size);
    }
}
