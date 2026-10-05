package com.trinity.observability.filter;

import java.io.IOException;

import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.trinity.observability.util.OrgContext;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Pulls the requested OpenObserve organization off each request and publishes
 * it for the duration of that request.
 *
 * <p>Accepted, in order of precedence:</p>
 * <ol>
 *   <li>{@code ?org=<identifier>} — how the UI sends it, so a shared link
 *       carries its org.</li>
 *   <li>{@code X-Org-Id} header — for API clients that would rather not add a
 *       query param to every call.</li>
 * </ol>
 *
 * <p>Absent or malformed, nothing is set and OpenObserveConfig falls back to
 * the configured default org — so every existing caller keeps working
 * unchanged.</p>
 *
 * <p>Ordered after RequestIdFilter so the org lands in the logging context
 * alongside the request id, which makes "why did this query return nothing"
 * answerable from the logs alone.</p>
 */
@Component
@Order(2)
public class OrgContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String org = httpRequest.getParameter("org");
                if (org == null || org.trim().isEmpty()) {
                    org = httpRequest.getHeader("X-Org-Id");
                }
                if (org != null && !org.trim().isEmpty()) {
                    String trimmed = org.trim();
                    OrgContext.set(trimmed);
                    // Only echo it into the log context once it passed
                    // validation, so a rejected value is visibly absent rather
                    // than appearing to have been honoured.
                    if (OrgContext.isValid(trimmed)) {
                        ThreadContext.put("org", trimmed);
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            // Request threads are pooled: without this, one request's org
            // would leak onto the next request served by the same thread.
            OrgContext.clear();
        }
    }
}
