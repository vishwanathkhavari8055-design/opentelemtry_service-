package com.trinity.observability.service;

import java.util.List;
import java.util.Set;

/**
 * What streams exist, and what columns each one has.
 *
 * <h2>Why this is its own service</h2>
 *
 * <p>Two unrelated callers need it. The stream pickers on Logs and Traces need the
 * LIST, so the dropdown offers what the tenant actually has rather than a constant
 * in the UI. And the log reader needs the FIELDS, because a stream the OTel exporter
 * did not write does not have {@code severity} or {@code service_name} — selecting
 * one used to fail with {@code unknown field 'severity'} rather than return rows.</p>
 *
 * <p>{@code AlertsService} has its own copy of the listing for the alert editor's
 * stream select. It is deliberately left alone: it works, it is on the alert
 * screen's critical path, and rewiring it to delegate here would be a change to
 * working code for no behavioural gain.</p>
 *
 * <h2>Cached, because the log reader asks on every query</h2>
 *
 * <p>The adaptive projection needs a stream's column set before it can build its
 * SELECT. Fetching that from OpenObserve on every page of logs would add a
 * round-trip to the screen's critical path for information that changes when
 * somebody deploys a new exporter — i.e. almost never. Entries expire so a genuinely
 * new column still appears without a restart.</p>
 */
public interface StreamCatalogService {

    /**
     * Stream names of one signal type, alphabetically.
     *
     * @param streamType {@code logs} | {@code traces} | {@code metrics}; anything
     *                   else is normalised to {@code logs}
     * @return never null; empty when OpenObserve is disabled or unreachable, which
     *         the caller must treat as "could not ask", not as "none exist"
     */
    List<String> list(String streamType);

    /**
     * The column names on one stream.
     *
     * <p>Lower-cased for comparison, because a projection decision must not depend
     * on how OpenObserve happened to spell a field.</p>
     *
     * @return never null; EMPTY means unknown — either the stream has never been
     *         written to or the lookup failed. A caller must not read emptiness as
     *         "this stream has no columns" and adapt a query to nothing.
     */
    Set<String> fields(String streamType, String streamName);

    /**
     * True when the stream is known to carry every one of {@code required}.
     *
     * <p>False when the field set is unknown, which is the safe answer: the caller
     * uses this to decide whether the schema-specific fast path is safe, and
     * guessing yes on no evidence is how {@code unknown field} errors reach a
     * screen.</p>
     */
    boolean hasAll(String streamType, String streamName, String... required);
}
