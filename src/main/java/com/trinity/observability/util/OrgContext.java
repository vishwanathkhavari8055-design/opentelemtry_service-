package com.trinity.observability.util;

import java.util.regex.Pattern;

/**
 * The OpenObserve organization the CURRENT REQUEST is scoped to.
 *
 * <p>Every OpenObserve URL this service builds embeds an org identifier
 * ({@code /api/{org}/_search}), and until now that came from a single static
 * config value — so the whole deployment could only ever see one org. The UI
 * needs to switch orgs at runtime, and the difference is not cosmetic: on this
 * instance {@code default} has 638 streams while {@code DLH} has 12,655.</p>
 *
 * <p>Rather than thread an {@code org} argument through LogsService,
 * TracesService, MetricsService, ServicesService and every DTO — a large,
 * error-prone change where one missed call site silently queries the wrong
 * org — the value is held per request and read at the single point that
 * already knows it: {@link com.trinity.observability.config.OpenObserveConfig#getOrg()}.
 * Existing services become org-aware without being touched.</p>
 *
 * <p>Set by {@link com.trinity.observability.filter.OrgContextFilter} and
 * ALWAYS cleared in a finally block — a ThreadLocal left set on a pooled
 * request thread would leak one user's org onto the next request.</p>
 */
public final class OrgContext {

    /**
     * Identifiers are interpolated into an OpenObserve URL PATH, so they are
     * validated rather than escaped. OpenObserve's own identifiers are
     * alphanumeric ({@code 3H5HQAR6OLxkWivdKZE0hg3YBF4}) plus the reserved
     * {@code _meta} and {@code default}, so this character class covers every
     * real value while making path traversal ("../") impossible.
     */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private OrgContext() {}

    /** @return true when the value is safe to place in a URL path. */
    public static boolean isValid(String org) {
        return org != null && VALID.matcher(org).matches();
    }

    /** Sets the request's org. Invalid values are ignored, falling back to the
     *  configured default rather than failing the request. */
    public static void set(String org) {
        if (isValid(org)) {
            CURRENT.set(org);
        }
    }

    /** @return the request's org, or null when none was supplied. */
    public static String get() {
        return CURRENT.get();
    }

    /** MUST be called in a finally block — see the class note on thread reuse. */
    public static void clear() {
        CURRENT.remove();
    }
}
