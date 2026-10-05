package com.trinity.observability.serviceimpl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.service.TelemetryProbe;
import com.trinity.observability.util.SqlSafety;

/**
 * The config-driven {@link TelemetryProbe}: "is there a row in {@code stream}
 * whose {@code column} equals this name?".
 *
 * <p>One class covers every identifier in
 * {@code product-catalog.validation.probes[*]} — {@code service_name} on logs,
 * {@code db_name} on traces, {@code service_name} on a metric stream — because
 * the only difference between them is three strings.
 * {@code ProductCatalogConfig} builds one instance per configured entry.</p>
 *
 * <h2>Case: looked up loosely, recorded exactly</h2>
 *
 * <p>The lookup folds case, because an operator typing "postgresql" should find
 * {@code PostgreSQL}. What gets RECORDED is the spelling telemetry uses, and
 * everything downstream compares that exactly. The distinction is load-bearing
 * on this deployment: {@code IoTOpsApiSvc} (OTel SDK, production) and
 * {@code iotopsapisvc} (k8s log scraper, dev) are two different services that
 * fold to the same string, so a case-insensitive FILTER would hide both when the
 * operator disabled one. When several case variants exist, the exact spelling
 * wins; failing that, the most recently active one, and the ambiguity is logged.</p>
 *
 * <h2>Two predicates, because HOW the fold is written costs 6x</h2>
 *
 * <p>A predicate over the bare column is pushed down to OpenObserve's index;
 * wrapping the column in a function is not, so the whole column has to be read.
 * Measured against this deployment's logs stream over 24h:</p>
 *
 * <pre>
 *   service_name = 'livy'          →  3.7 s,  scan 1,698
 *   service_name IN (4 spellings)  →  4.2 s,  scan 1,869
 *   LOWER(service_name) = 'livy'   → 19-23 s, scan 383,594
 *   service_name ILIKE 'livy'      → 23 s,    scan 383,764
 * </pre>
 *
 * <p>The same {@code LOWER} scan over an HOUR of that stream costs 0.3 s, so the
 * fold is not expensive in itself — it is expensive over a wide window. So the
 * probe issues exactly ONE query, choosing its predicate from what the caller
 * says it can afford: the exhaustive fold where the window is narrow, and the
 * indexed list of spellings (as typed, folded down, folded up, capitalised)
 * where it is wide. {@link TelemetryDiscoveryService} asks for the exhaustive
 * form only on the narrowest window it searches.</p>
 *
 * <p>One query and not two on purpose. A probe that fails upstream must report a
 * miss rather than throw, so doubling the round trips does not merely double the
 * load — it makes a saturated OpenObserve answer WRONG instead of slowly.</p>
 */
public class ColumnTelemetryProbe implements TelemetryProbe {

    private static final Logger log = LogManager.getLogger(ColumnTelemetryProbe.class);

    /**
     * Case variants pulled back per lookup. More than a handful means the
     * deployment has a naming problem this probe cannot resolve for the operator;
     * the cap keeps a pathological column from returning a large result set.
     */
    private static final int MAX_CASE_VARIANTS = 10;

    private final OpenObserveClientService openObserve;
    private final String signal;

    /**
     * Resolved lazily rather than captured at construction: the logs and traces
     * stream names come from {@code OpenObserveConfig}, and reading them once at
     * bean-creation time would bake in whatever was configured then.
     */
    private final Supplier<String> streamSupplier;

    private final String column;

    public ColumnTelemetryProbe(OpenObserveClientService openObserve, String signal,
            Supplier<String> streamSupplier, String column) {
        this.openObserve = openObserve;
        this.signal = signal;
        this.streamSupplier = streamSupplier;
        // Validated at construction so a typo in configuration fails at startup
        // rather than producing a broken query on the first Validate click.
        this.column = SqlSafety.validateIdentifier(column);
    }

    @Override
    public String signal() {
        return signal;
    }

    @Override
    public String describe() {
        return signal + '.' + column;
    }

    @Override
    public Optional<Hit> probe(String resourceName, long startMicros, long endMicros) {
        return probe(resourceName, startMicros, endMicros, true);
    }

    @Override
    public Optional<Hit> probe(String resourceName, long startMicros, long endMicros,
            boolean exhaustive) {
        if (resourceName == null || resourceName.isBlank()) {
            return Optional.empty();
        }
        String stream;
        try {
            stream = SqlSafety.validateIdentifier(streamSupplier.get());
        } catch (RuntimeException e) {
            log.warn("Probe {} skipped — unusable stream name: {}", describe(), e.getMessage());
            return Optional.empty();
        }

        String typed = resourceName.trim();

        // ONE query either way — never both. Two round trips per probe per window
        // would double the load on OpenObserve, and a probe that fails upstream is
        // indistinguishable from a miss (see the interface contract), so a
        // saturated backend does not just answer slowly, it answers WRONG.
        String predicate = exhaustive ? caseFoldPredicate(typed) : spellingPredicate(typed);
        return lookup(stream, predicate, typed, startMicros, endMicros);
    }

    /**
     * The spellings an index can be asked about directly: as typed, folded down,
     * folded up, and capitalised — the variants produced when one identifier is
     * written by an SDK, a Helm chart and a person.
     */
    private String spellingPredicate(String typed) {
        Set<String> spellings = new LinkedHashSet<>();
        spellings.add(typed);
        spellings.add(typed.toLowerCase(Locale.ROOT));
        spellings.add(typed.toUpperCase(Locale.ROOT));
        spellings.add(typed.substring(0, 1).toUpperCase(Locale.ROOT)
                + typed.substring(1).toLowerCase(Locale.ROOT));

        StringBuilder values = new StringBuilder();
        for (String spelling : spellings) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append('\'').append(SqlSafety.escapeStringLiteral(spelling)).append('\'');
        }
        // A name that folds to itself (all-lowercase, or a single character) is
        // written as equality rather than a one-element IN, which is the form the
        // planner prunes most reliably.
        return spellings.size() == 1
                ? column + " = " + values
                : column + " IN (" + values + ")";
    }

    /** The exhaustive fold: correct for any spelling, and unable to use an index. */
    private String caseFoldPredicate(String typed) {
        return "LOWER(" + column + ") = '"
                + SqlSafety.escapeStringLiteral(typed.toLowerCase(Locale.ROOT)) + "'";
    }

    /**
     * One lookup. {@code predicate} is a complete boolean expression built here
     * over the already-validated column, so nothing user-supplied reaches the
     * statement unescaped.
     */
    private Optional<Hit> lookup(String stream, String predicate, String typed,
            long startMicros, long endMicros) {
        // GROUP BY the column so every case variant comes back once with its own
        // recency, rather than one arbitrary row. COUNT(*) is free here and gives
        // the details view something concrete to show.
        String sql = "SELECT " + column + " AS matched_value, "
                + "MAX(_timestamp) AS last_seen, "
                + "COUNT(*) AS sample_count "
                + "FROM \"" + stream + "\" "
                + "WHERE _timestamp >= " + startMicros + " "
                + "AND _timestamp <= " + endMicros + " "
                + "AND " + predicate + " "
                + "GROUP BY " + column + " "
                + "ORDER BY MAX(_timestamp) DESC";

        if (log.isDebugEnabled()) {
            log.debug("Product Catalog probe {} SQL: {}", describe(), sql);
        }

        List<Map<String, Object>> hits;
        try {
            hits = openObserve.search(sql, startMicros, endMicros, 0, MAX_CASE_VARIANTS, signal);
        } catch (Exception e) {
            // Absent stream, absent column, or an upstream blip. All are ordinary
            // misses on a heterogeneous deployment — see the interface contract.
            log.debug("Probe {} miss on stream '{}': {}", describe(), stream, e.getMessage());
            return Optional.empty();
        }

        return pickBest(hits, typed, stream);
    }

    /**
     * Choose among the case variants: the exact spelling the operator typed if
     * telemetry has it, otherwise the most recently active variant (the query
     * already ordered by recency, so that is the first row).
     */
    private Optional<Hit> pickBest(List<Map<String, Object>> hits, String typed, String stream) {
        Map<String, Object> chosen = chooseVariant(hits, typed);
        if (chosen == null) {
            return Optional.empty();
        }

        String matchedValue = (String) chosen.get("matched_value");
        if (hits.size() > 1 && !matchedValue.equals(typed) && log.isWarnEnabled()) {
            log.warn("Product Catalog probe {} found {} case variants of '{}' on stream '{}' — "
                    + "registering the most recently active one, '{}'. Register the others "
                    + "separately if they are distinct deployments.",
                    describe(), hits.size(), typed, stream, matchedValue);
        }

        Number lastSeen = (Number) chosen.get("last_seen");
        Number count = (Number) chosen.get("sample_count");
        return Optional.of(new Hit(signal, stream, column, matchedValue,
                lastSeen == null ? 0L : lastSeen.longValue(),
                count == null ? 0L : count.longValue()));
    }

    /**
     * The row whose value is exactly {@code typed}, else the first row with a
     * non-blank value, else null.
     */
    private static Map<String, Object> chooseVariant(List<Map<String, Object>> hits, String typed) {
        Map<String, Object> chosen = null;
        for (Map<String, Object> hit : hits) {
            String matched = nonBlankMatch(hit);
            if (matched != null && matched.equals(typed)) {
                return hit;
            }
            if (matched != null && chosen == null) {
                chosen = hit;
            }
        }
        return chosen;
    }

    /** The row's matched value when it is a non-blank string, otherwise null. */
    private static String nonBlankMatch(Map<String, Object> hit) {
        return hit.get("matched_value") instanceof String matched && !matched.isBlank()
                ? matched : null;
    }
}
