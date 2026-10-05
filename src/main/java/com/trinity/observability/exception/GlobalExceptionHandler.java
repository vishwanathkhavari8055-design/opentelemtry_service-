package com.trinity.observability.exception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

/**
 * Turns exceptions into responses.
 *
 * <h2>Response shape</h2>
 *
 * <p>Every body keeps the original {@code error} key. That is deliberate: the
 * existing UI reads it, and the handlers below were added underneath a
 * catch-all that had been answering 500 to everything, so the shape had to stay
 * backward compatible while the status codes became accurate.</p>
 *
 * <h2>Why the specific handlers matter</h2>
 *
 * <p>Before these, a missing rule, a duplicate name, a malformed id and a
 * genuine server fault were all indistinguishable 500s. A client cannot act on
 * that — it cannot tell "fix your request" from "retry later" from "this is
 * gone" — and neither can a dashboard counting error rates, which is an
 * unfortunate property for an observability service to have.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e) {
        log.info("404: {}", e.getMessage());
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ResourceConflictException e) {
        log.warn("409: {}", e.getMessage());
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Someone else changed the row between read and write.
     *
     * <p>409 rather than 500 because the correct client behaviour is specific:
     * re-read and re-apply. Silently letting the last writer win would discard
     * an edit the other operator believes they made.</p>
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("409 optimistic lock: {}", e.getMessage());
        return build(HttpStatus.CONFLICT,
                "This record was modified by someone else. Reload it and re-apply your change.");
    }

    /** Unique-constraint and FK violations — a duplicate name, most often. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException e) {
        String detail = rootMessage(e);
        log.warn("409 data integrity: {}", detail);
        return build(HttpStatus.CONFLICT, "Request conflicts with existing data: " + detail);
    }

    /** {@code @Valid} on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(MethodArgumentNotValidException e) {
        List<String> problems = new ArrayList<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) {
            problems.add(f.getField() + ": " + f.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors()
                .forEach(g -> problems.add(g.getObjectName() + ": " + g.getDefaultMessage()));
        log.info("400 validation: {}", problems);

        ResponseEntity<Map<String, Object>> response =
                build(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, Object> body = response.getBody();
        if (body != null) {
            body.put("violations", problems);
        }
        return response;
    }

    /** {@code @Validated} on path variables and request params. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleParamValidation(ConstraintViolationException e) {
        log.info("400 constraint: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Bad input the services reject by hand — an unparseable id, an unsupported
     * stream type. These predate bean validation in this codebase and are still
     * the idiom in the read-only services, so they are mapped rather than
     * rewritten.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.info("400: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A feature asked for while its backend is switched off. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("409 illegal state: {}", e.getMessage());
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * OpenObserve failed us.
     *
     * <p>502, not 500: the fault is upstream, and conflating "our bug" with
     * "the evaluation engine is down" makes an outage impossible to triage from
     * status codes alone.</p>
     */
    @ExceptionHandler(OpenObserveQueryException.class)
    public ResponseEntity<Map<String, Object>> handleUpstream(OpenObserveQueryException e) {
        log.error("502 upstream: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * Grafana failed us.
     *
     * <p>502 for the same reason as OpenObserve above — except for 404, which is
     * passed through as a 404. A stale dashboard UID is a normal outcome the
     * Dashboards screen has to distinguish from an outage: one means "unregister
     * it or restore it in Grafana", the other means "Grafana is down", and a
     * client that saw 502 for both would have to parse the message to tell.</p>
     *
     * <p>{@code upstreamStatus} is included in the body so the UI can say which
     * of the three it hit — bad token, missing dashboard, unreachable host —
     * without matching on prose.</p>
     */
    @ExceptionHandler(GrafanaException.class)
    public ResponseEntity<Map<String, Object>> handleGrafana(GrafanaException e) {
        int upstream = e.getUpstreamStatus();
        HttpStatus status;
        if (upstream == 404) {
            status = HttpStatus.NOT_FOUND;
            log.info("404 grafana: {}", e.getMessage());
        } else if (upstream == 504) {
            // A slow panel query, not a fault. 504 so the UI can say "too slow,
            // narrow the range" instead of "Grafana is broken", and WARN so a
            // capacity observation does not sit in the error log next to outages.
            status = HttpStatus.GATEWAY_TIMEOUT;
            log.warn("504 grafana timeout: {}", e.getMessage());
        } else {
            status = HttpStatus.BAD_GATEWAY;
            log.error("502 grafana (upstream={}): {}", upstream, e.getMessage());
        }
        ResponseEntity<Map<String, Object>> response = build(status, e.getMessage());
        Map<String, Object> body = response.getBody();
        if (body != null) {
            body.put("upstreamStatus", upstream);
        }
        return response;
    }

    /**
     * No handler matched the path.
     *
     * <p>Previously swallowed by the catch-all and reported as 500, which is
     * how a simple "this endpoint is not deployed yet" looked identical to a
     * crash — including to OpenObserve's own webhook delivery errors.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.info("404 no handler: {}", e.getResourcePath());
        return build(HttpStatus.NOT_FOUND, "No endpoint at /" + e.getResourcePath());
    }

    /**
     * The path exists but not for this verb.
     *
     * <p>405, not 500, and the difference is operational rather than cosmetic:
     * <b>5xx means "my fault, retry later" and clients act on it.</b> OpenObserve
     * retries a 5xx delivery, so a destination pointing at a verb this service
     * does not serve would retry forever instead of failing once — which is
     * exactly what the removed alert webhook produced. Its old path is still
     * matched by {@code GET /api/alerts/{alertId}} (with "webhook" read as an
     * alert id), so a POST to it reached this handler as a method mismatch and
     * came back as a retryable server error.</p>
     *
     * <p>{@code Allow} is set from what Spring reports the path does support, so
     * the response tells the caller what to use instead of only what not to.</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        log.info("405: {} not supported (supported: {})", e.getMethod(), supported);

        ResponseEntity<Map<String, Object>> response = build(HttpStatus.METHOD_NOT_ALLOWED,
                e.getMethod() + " is not supported on this path"
                        + (supported == null || supported.isEmpty()
                                ? "" : " (try " + supported + ")"));
        if (supported == null || supported.isEmpty()) {
            return response;
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(supported.toArray(new HttpMethod[0]))
                .body(response.getBody());
    }

    /** Everything else. Unchanged behaviour: 500 with the message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception e) {
        log.error("500 unhandled: {}", e.getMessage(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        // "error" first and always — the existing UI reads this key.
        body.put("error", message == null ? status.getReasonPhrase() : message);
        body.put("status", status.value());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }

    /** Deepest cause message — the useful half of a wrapped JDBC exception. */
    private String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? t.toString() : message.lines().findFirst().orElse(message);
    }
}
