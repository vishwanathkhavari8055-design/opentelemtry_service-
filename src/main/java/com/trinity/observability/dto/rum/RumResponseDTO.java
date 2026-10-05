package com.trinity.observability.dto.rum;

import java.util.List;

/**
 * Real User Monitoring read models.
 *
 * <h2>Records, not bean classes</h2>
 *
 * <p>The older DTOs here are mutable beans because they are assembled field by field
 * across a long method. These are not: every RUM response is built once, from the
 * result of a fixed set of queries, and handed straight to Jackson. A record says
 * that — the payload is a value, and nothing downstream may edit it.</p>
 *
 * <h2>Units are normalised HERE, once</h2>
 *
 * <p>OpenObserve's RUM browser SDK writes every duration in <b>nanoseconds</b>
 * ({@code view_largest_contentful_paint: 1820000000} is 1.82 seconds). Every
 * duration below is <b>milliseconds</b>, converted in the service. The conversion
 * deliberately does not happen in the browser: two screens dividing by 1e6
 * independently is how one of them ends up reporting microseconds, and a Core Web
 * Vital that is wrong by 1000x still looks plausible.</p>
 *
 * <p>{@code cls} is the exception and carries no unit — Cumulative Layout Shift is a
 * dimensionless score, not a duration.</p>
 *
 * <h2>Every list is derived, never enumerated</h2>
 *
 * <p>Services, environments and versions come from {@code SELECT DISTINCT} over the
 * RUM stream, so a new frontend appears in the filters as soon as it reports once.
 * Nothing in this file or its service names a specific application.</p>
 */
public final class RumResponseDTO {

    private RumResponseDTO() {
    }

    /**
     * Fields shared by every RUM response, so a screen can always say what it is
     * showing and why it might be empty.
     *
     * @param supported   false when OpenObserve is disabled or the RUM stream is
     *                    absent — the UI explains the gap instead of drawing zeros
     * @param message     why it is unsupported, or null
     * @param org         the OpenObserve organization the numbers came from
     * @param window      the relative window as requested, e.g. "now-6h"
     * @param windowStart ISO-8601 resolved lower bound
     * @param windowEnd   ISO-8601 resolved upper bound
     * @param partial     true when OpenObserve could not scan the whole window, so
     *                    every count below is a floor rather than a total. Reported
     *                    rather than swallowed: this deployment answers an
     *                    unservable range with HTTP 200 and an empty result set.
     */
    public record Meta(boolean supported, String message, String org, String window,
                       String windowStart, String windowEnd, boolean partial) {
    }

    // ── /scope ───────────────────────────────────────────────────────────────

    /**
     * What the filter dropdowns offer.
     *
     * <p>Its own endpoint because it runs three cheap DISTINCT queries and nothing
     * else. Deriving the dropdowns from a performance response instead would tie the
     * list of selectable services to the currently selected service — pick one, and
     * you could never pick another.</p>
     */
    public record Scope(Meta meta, List<String> services, List<String> environments,
                        List<String> versions, List<String> applications) {
    }

    // ── /performance ─────────────────────────────────────────────────────────

    /**
     * One Core Web Vital.
     *
     * @param key    stable identifier: lcp | inp | cls | fcp | loadingTime | ttfb
     * @param label  what the operator reads
     * @param value  p75 across the window, in ms — or the raw score for CLS. Null
     *               when no view in the window reported this vital, which is normal:
     *               INP only exists once a user has interacted.
     * @param unit   "ms" | "s" | "score" — what {@code value} is in, so the browser
     *               formats rather than guesses
     * @param rating good | needs-improvement | poor, per the published Core Web
     *               Vitals thresholds. Null when {@code value} is.
     * @param sample how many views the percentile was taken over. A p75 over three
     *               views is not a percentile, and the UI needs to be able to say so.
     */
    public record Vital(String key, String label, Double value, String unit,
                        String rating, long sample) {
    }

    /**
     * Error and session counters for the performance summary.
     *
     * @param totalErrors        every error event in the window
     * @param unhandledErrors    those the page did not catch — the ones that break a
     *                           session rather than get logged
     * @param sessionsWithErrors distinct sessions that hit at least one error
     * @param totalSessions      distinct sessions in the window
     */
    public record PerfCounts(long totalErrors, long unhandledErrors, long sessionsWithErrors,
                             long totalSessions) {
    }

    /**
     * One API endpoint the frontend called, aggregated over the window.
     *
     * <p>Built from RUM {@code resource} events of type xhr/fetch — i.e. what the
     * BROWSER measured, including the network it sat on. These numbers are expected
     * to exceed the server-side latency for the same route, and that difference is
     * the reason this table exists.</p>
     *
     * @param url        the resource URL, grouped as reported
     * @param method     HTTP method, when the SDK recorded one
     * @param calls      how many times it was called
     * @param avgMs      mean duration in ms
     * @param p95Ms      95th percentile duration in ms
     * @param errorCalls calls whose status code was >= 400
     */
    public record ApiCall(String url, String method, long calls, Double avgMs, Double p95Ms,
                          long errorCalls) {
    }

    /** The Performance tab: vitals, counters and the API breakdown in one call. */
    public record Performance(Meta meta, List<Vital> vitals, PerfCounts counts,
                              List<ApiCall> api) {
    }

    // ── /sessions ────────────────────────────────────────────────────────────

    /**
     * The five headline numbers above the session list.
     *
     * @param sessions      distinct sessions in the window
     * @param withErrors    sessions that hit at least one error
     * @param frustrated    sessions with a rage or dead click — the SDK's own
     *                      frustration signal, not a threshold invented here
     * @param medianDurationMs median session length in ms. Median, not mean: one
     *                      tab left open overnight moves a mean and tells you
     *                      nothing about a typical visit.
     * @param bounceRatePct percentage of sessions with a single view
     */
    public record SessionSummary(long sessions, long withErrors, long frustrated,
                                 Double medianDurationMs, Double bounceRatePct) {
    }

    /**
     * One session.
     *
     * @param sessionId       the RUM session id
     * @param user            anonymous id when present, else null — the SDK reports
     *                        no identity unless the host app sets one
     * @param sessionType     "user" | "synthetic", as reported
     * @param source          the SDK source, e.g. "browser"
     * @param browser         user agent family
     * @param os              OS family
     * @param device          device family
     * @param views           view events in the session
     * @param events          all RUM events in the session
     * @param errorCount      error events
     * @param frustrationCount rage/dead clicks
     * @param durationMs      last event minus first, in ms
     * @param startedAt       ISO-8601 of the first event
     * @param lastSeenAt      ISO-8601 of the last event
     * @param lastViewUrl     the most recent view URL, for orientation
     */
    public record SessionRow(String sessionId, String user, String sessionType, String source,
                             String browser, String os, String device, long views, long events,
                             long errorCount, long frustrationCount, Double durationMs,
                             String startedAt, String lastSeenAt, String lastViewUrl) {
    }

    /**
     * The Sessions tab.
     *
     * @param total how many sessions matched, which may exceed {@code items} — the
     *              list is paged and the summary is not
     */
    public record Sessions(Meta meta, SessionSummary summary, List<SessionRow> items, long total) {
    }

    // ── /errors ──────────────────────────────────────────────────────────────

    /**
     * Error-tracking headline numbers.
     *
     * @param totalErrors           every error event in the window
     * @param uniqueIssues          distinct (type, message) pairs — what an operator
     *                              actually has to triage
     * @param usersAffected         distinct users who saw at least one
     * @param sessionsAffected      distinct sessions that hit at least one
     * @param totalSessions         distinct sessions overall, the denominator
     * @param crashFreeSessionsPct  share of sessions that hit NO error
     */
    public record ErrorSummary(long totalErrors, long uniqueIssues, long usersAffected,
                               long sessionsAffected, long totalSessions,
                               Double crashFreeSessionsPct) {
    }

    /**
     * One bucket of the errors-over-time chart.
     *
     * @param bucketStart ISO-8601 start of the bucket
     * @param handled     errors the page caught
     * @param unhandled   errors it did not
     */
    public record ErrorBucket(String bucketStart, long handled, long unhandled) {
    }

    /**
     * One distinct issue, not one occurrence.
     *
     * @param type       the error class, e.g. "TypeError". Null when the SDK
     *                   reported none, which is common for thrown strings.
     * @param message    the error message, truncated by the service to keep a
     *                   pathological payload from dominating the response
     * @param handling   "handled" | "unhandled" | null
     * @param source     where it came from, e.g. "main.js:1834"
     * @param sourceType the SDK's classification, e.g. "custom" | "source"
     * @param service    the frontend that reported it
     * @param events     occurrences in the window
     * @param users      distinct users affected
     * @param sessions   distinct sessions affected
     * @param firstSeen  ISO-8601 of the earliest occurrence IN THE WINDOW. Not the
     *                   all-time first: a wider window can only move it earlier, and
     *                   claiming otherwise would make "new" mean nothing.
     * @param lastSeen   ISO-8601 of the latest occurrence
     */
    public record ErrorIssue(String type, String message, String handling, String source,
                             String sourceType, String service, long events, long users,
                             long sessions, String firstSeen, String lastSeen) {
    }

    /** The Error Tracking tab. */
    public record Errors(Meta meta, ErrorSummary summary, List<ErrorBucket> series,
                         List<ErrorIssue> items, long total) {
    }
}
