package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertPreviewRequestDTO;
import com.trinity.observability.dto.alerts.AlertPreviewResponseDTO;
import com.trinity.observability.dto.alerts.AlertScopeResponseDTO;
import com.trinity.observability.dto.metrics.PromResultDTO;
import com.trinity.observability.service.AlertPreviewService;
import com.trinity.observability.service.AlertScopeService;
import com.trinity.observability.service.PromMetricsService;
import com.trinity.observability.util.AlertConditionSql;
import com.trinity.observability.util.SqlSafety;

/**
 * The stepwise alert preview.
 *
 * <h2>Two queries per step, and why not one</h2>
 *
 * <p>Each rung runs a {@code COUNT(*)} and a small {@code SELECT *}. One query
 * cannot serve both: OpenObserve pages results with a {@code size} parameter
 * rather than SQL {@code LIMIT}, so the rows that come back are a page, and
 * counting them tells you how big the page was, not how much matched. A preview
 * that reported the page size as the match count would say "100 rows" for every
 * rule busy enough to matter.</p>
 *
 * <p>Samples can be switched off with {@code sampleSize=0}, which halves the
 * work for a caller that only wants the shape of the funnel.</p>
 *
 * <h2>Partiality is reported, never swallowed</h2>
 *
 * <p>{@link OpenObserveClientService#searchDetailed} is used rather than
 * {@code search} throughout. OpenObserve answers a window it cannot scan with
 * HTTP 200, zero hits and {@code is_partial} — identical, to a plain search, to
 * "nothing matched". Everywhere else in this service that distinction costs a
 * stale cache; here it would tell an operator their correct rule is broken, and
 * they would change it.</p>
 */
@Service
public class AlertPreviewServiceImpl implements AlertPreviewService {

    private static final Logger log = LogManager.getLogger(AlertPreviewServiceImpl.class);

    /** Alias for the count column, distinctive enough not to collide with a real one. */
    private static final String COUNT_ALIAS = "trinity_preview_count";

    /** Alias for the aggregated value column. */
    private static final String VALUE_ALIAS = "trinity_preview_value";

    private static final int DEFAULT_PERIOD_MINUTES = 10;
    private static final int MAX_PERIOD_MINUTES = 1440;
    private static final int DEFAULT_SAMPLE_SIZE = 5;
    private static final int MAX_SAMPLE_SIZE = 100;

    /** Aggregated groups returned. Beyond this the list stops being readable. */
    private static final int MAX_GROUPS = 200;

    private static final String MODE_PROMQL = "promql";
    private static final String MODE_CUSTOM = "custom";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_OPERATOR = "operator";
    private static final String OVER_THE_LAST = " over the last ";
    private static final String FUNCTION_COUNT = "count";
    private static final String SQL_WHERE = " WHERE ";
    private static final String APPROX_PERCENTILE_CONT = "APPROX_PERCENTILE_CONT(";

    /** The SQL an aggregation function wraps its column in: {@code prefix + column + suffix}. */
    private record AggregateForm(String prefix, String suffix) { }

    private static final Map<String, AggregateForm> AGGREGATE_FORMS = Map.ofEntries(
            Map.entry("avg",          new AggregateForm("AVG(", ")")),
            Map.entry("mean",         new AggregateForm("AVG(", ")")),
            Map.entry("min",          new AggregateForm("MIN(", ")")),
            Map.entry("max",          new AggregateForm("MAX(", ")")),
            Map.entry("sum",          new AggregateForm("SUM(", ")")),
            Map.entry(FUNCTION_COUNT, new AggregateForm("COUNT(", ")")),
            Map.entry("p50",          new AggregateForm(APPROX_PERCENTILE_CONT, ", 0.5)")),
            Map.entry("median",       new AggregateForm(APPROX_PERCENTILE_CONT, ", 0.5)")),
            Map.entry("p90",          new AggregateForm(APPROX_PERCENTILE_CONT, ", 0.9)")),
            Map.entry("p95",          new AggregateForm(APPROX_PERCENTILE_CONT, ", 0.95)")),
            Map.entry("p99",          new AggregateForm(APPROX_PERCENTILE_CONT, ", 0.99)")));

    private final OpenObserveClientService oo;
    private final OpenObserveConfig config;
    private final AlertScopeService scopes;
    private final PromMetricsService prom;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public AlertPreviewServiceImpl(OpenObserveClientService oo,
                                   OpenObserveConfig config,
                                   AlertScopeService scopes,
                                   PromMetricsService prom) {
        this.oo = oo;
        this.config = config;
        this.scopes = scopes;
        this.prom = prom;
    }

    @Override
    public AlertPreviewResponseDTO.Preview preview(AlertPreviewRequestDTO.Preview request) {
        long t0 = System.nanoTime();

        AlertPreviewResponseDTO.Preview out = new AlertPreviewResponseDTO.Preview();
        out.setOrg(config.getOrg());
        out.setStreamType(normaliseStreamType(request.getStreamType()));
        out.setStreamName(request.getStreamName());

        if (!openObserveEnabled) {
            out.setSupported(false);
            out.setError("OpenObserve is not enabled, so a rule cannot be run against real data. "
                    + "The rule can still be saved; it just has not been checked.");
            return out;
        }

        JsonNode queryCondition = request.getQueryCondition();
        String mode = mode(queryCondition);
        out.setMode(mode);

        int period = resolvePeriod(request);
        out.setPeriodMinutes(period);

        Instant end = Instant.now();
        Instant start = end.minusSeconds(period * 60L);
        out.setWindowStart(start.toString());
        out.setWindowEnd(end.toString());
        out.setWindowStartMicros(micros(start));
        out.setWindowEndMicros(micros(end));

        try {
            if (MODE_PROMQL.equals(mode)) {
                previewPromql(out, queryCondition);
            } else {
                previewSql(out, request, queryCondition, mode);
            }
        } catch (IllegalArgumentException e) {
            // A caller error — an unusable column or operator. Rethrown so the
            // handler turns it into a 400: this is the request being wrong, not
            // a finding about the data, and dressing it up as a finding would
            // have the operator looking at their telemetry for a typo of theirs.
            throw e;
        } catch (Exception e) {
            log.error("Alert preview failed for {}/{}: {}",
                    out.getStreamType(), out.getStreamName(), e.getMessage(), e);
            out.setError("The preview could not be run: " + e.getMessage());
        }

        out.setDurationMs((System.nanoTime() - t0) / 1_000_000L);
        log.info("preview(streamType={}, stream={}, mode={}, period={}m) -> {} step(s), "
                        + "wouldFire={} in {} ms",
                out.getStreamType(), out.getStreamName(), mode, period,
                out.getSteps().size(), out.getEvaluation().isWouldFire(), out.getDurationMs());
        return out;
    }

    // ── SQL-backed preview (custom conditions, and raw SQL) ─────────────────

    private void previewSql(AlertPreviewResponseDTO.Preview out,
            AlertPreviewRequestDTO.Preview request, JsonNode queryCondition, String mode) {

        String stream = request.getStreamName();
        if (stream == null || stream.isBlank()) {
            throw new IllegalArgumentException("streamName is required to preview a "
                    + out.getStreamType() + " rule");
        }
        // The stream name is interpolated into FROM, where no parameter binding
        // exists. Validated rather than escaped: an identifier that needs
        // escaping to be safe is an identifier OpenObserve would reject anyway.
        SqlSafety.validateIdentifier(stream);

        String scopeSql = scopeSql(request, out);
        int sampleSize = resolveSampleSize(request.getSampleSize());

        if ("sql".equals(mode)) {
            previewRawSql(out, queryCondition, sampleSize);
            return;
        }
        previewLadder(out, request, queryCondition, stream, scopeSql, sampleSize);
    }

    /** Steps through the rule's conditions one at a time, then evaluates the whole rule. */
    private void previewLadder(AlertPreviewResponseDTO.Preview out,
            AlertPreviewRequestDTO.Preview request, JsonNode queryCondition, String stream,
            String scopeSql, int sampleSize) {

        JsonNode conditions = conditionsOf(queryCondition);
        List<AlertConditionSql.Step> ladder = AlertConditionSql.steps(conditions);
        if (AlertConditionSql.isTruncated(conditions)) {
            out.setNotice("Only the first " + AlertConditionSql.MAX_STEPS + " conditions are "
                    + "stepped through. The evaluation below still uses all of them.");
        }

        long previous = -1;
        String lastWhere = null;

        for (AlertConditionSql.Step rung : ladder) {
            AlertPreviewResponseDTO.Step step = new AlertPreviewResponseDTO.Step();
            step.setIndex(rung.index());
            step.setLabel(rung.label());

            String where = combine(scopeSql, rung.where());
            step.setWhere(where);
            lastWhere = where;

            try {
                Counted counted = count(stream, where, out);
                recordCount(step, counted, previous);
                previous = counted.rows();

                if (sampleSize > 0 && counted.rows() > 0) {
                    step.setRows(sample(stream, where, sampleSize, out));
                }
            } catch (Exception e) {
                // Recorded on the step and the ladder continues. A condition that
                // names a column the stream does not have is precisely what the
                // operator is here to find, and blanking the other steps would
                // remove the context that makes it obvious.
                step.setError(shortMessage(e));
                step.setComplete(false);
                log.warn("Preview step {} failed on {}: {}", rung.index(), stream, e.getMessage());
            }
            out.getSteps().add(step);
        }

        out.setSql(selectAll(stream, lastWhere));
        evaluate(out, request, queryCondition, stream, lastWhere);
    }

    /** Puts a step's row count on it, and how many rows it removed from the step before. */
    private static void recordCount(AlertPreviewResponseDTO.Step step, Counted counted, long previous) {
        step.setMatched(counted.rows());
        step.setComplete(counted.complete());
        // Step 0 has nothing before it, so nothing was removed by it.
        step.setRemoved(previous < 0 ? 0 : Math.max(0, previous - counted.rows()));
    }

    /** A rule written as raw SQL: one step, because there is nothing to decompose. */
    private void previewRawSql(AlertPreviewResponseDTO.Preview out, JsonNode queryCondition,
            int sampleSize) {

        String sql = queryCondition.path("sql").asText("").trim();
        if (sql.isEmpty()) {
            throw new IllegalArgumentException(
                    "queryCondition.type is 'sql' but queryCondition.sql is empty");
        }
        out.setSql(sql);
        out.setNotice("This rule is written as raw SQL, so there are no separate conditions to "
                + "step through. The single result below is what it returns over the window.");

        AlertPreviewResponseDTO.Step step = new AlertPreviewResponseDTO.Step();
        step.setIndex(0);
        step.setLabel("The rule's own SQL");

        // Passed through verbatim. It is the operator's query, not one this
        // service composed, and rewriting it to add a count would preview
        // something other than what the rule will run.
        OpenObserveClientService.SearchOutcome outcome = oo.searchDetailed(
                sql, out.getWindowStartMicros(), out.getWindowEndMicros(),
                0, Math.max(sampleSize, 1), out.getStreamType());

        step.setMatched(outcome.hits().size());
        step.setComplete(outcome.isComplete());
        if (sampleSize > 0) {
            step.setRows(new ArrayList<>(outcome.hits()));
        }
        if (outcome.hits().size() >= Math.max(sampleSize, 1)) {
            // The count IS the page size here, because the query is the
            // operator's and cannot be rewritten into a COUNT without previewing
            // something other than what the rule runs. Said out loud so the
            // number is not read as a total.
            out.setNotice(append(out.getNotice(), "The row count is capped at the sample size for "
                    + "a raw-SQL preview — there may be more."));
        }
        out.getSteps().add(step);

        AlertPreviewResponseDTO.Evaluation evaluation = out.getEvaluation();
        evaluation.setEvaluated(false);
        evaluation.setSummary("A raw-SQL rule is evaluated by OpenObserve against its own result "
                + "set; this preview shows that result but does not re-apply the trigger.");
    }

    // ── PromQL preview ──────────────────────────────────────────────────────

    /**
     * A metrics rule written in PromQL.
     *
     * <p>Routed through the Prometheus-compatible endpoint rather than SQL
     * because that is what OpenObserve will evaluate it with. Translating PromQL
     * into SQL to preview it would be previewing a different query, and the two
     * disagree exactly where it matters — rate windows, staleness, and how an
     * absent series is counted.</p>
     */
    private void previewPromql(AlertPreviewResponseDTO.Preview out,
            JsonNode queryCondition) {

        String promql = queryCondition.path(MODE_PROMQL).asText("").trim();
        if (promql.isEmpty()) {
            throw new IllegalArgumentException(
                    "queryCondition.type is 'promql' but queryCondition.promql is empty");
        }
        out.setSql(promql);

        PromResultDTO result = prom.query(promql, null);
        AlertPreviewResponseDTO.Step step = new AlertPreviewResponseDTO.Step();
        step.setIndex(0);
        step.setLabel(promql);

        if (result == null || !result.isSupported()) {
            step.setError(promUnavailableMessage(result));
            step.setComplete(false);
            out.getSteps().add(step);
            return;
        }

        List<PromResultDTO.Series> series = result.getSeries() == null
                ? List.of() : result.getSeries();
        step.setMatched(series.size());

        List<Map<String, Object>> rows = new ArrayList<>();
        List<AlertPreviewResponseDTO.Group> groups = new ArrayList<>();
        for (PromResultDTO.Series one : series) {
            addSeries(one, rows, groups);
        }
        step.setRows(rows);
        out.getSteps().add(step);

        // The PromQL condition lives in query_condition.promql_condition, not in
        // trigger_condition, and it grades each SERIES rather than a row count.
        JsonNode promCondition = queryCondition.get("promql_condition");
        AlertPreviewResponseDTO.Evaluation evaluation = out.getEvaluation();
        if (promCondition == null || promCondition.isNull()) {
            describeUngradedSeries(evaluation, series, groups);
            return;
        }
        gradeSeries(evaluation, promCondition, groups);
    }

    private static String promUnavailableMessage(PromResultDTO result) {
        return result == null || result.getError() == null
                ? "The PromQL endpoint is unavailable" : result.getError();
    }

    /** One PromQL series as a preview row and as a group. */
    private void addSeries(PromResultDTO.Series one, List<Map<String, Object>> rows,
            List<AlertPreviewResponseDTO.Group> groups) {
        Double value = lastValue(one);
        Map<String, Object> row = new LinkedHashMap<>();
        if (one.getLabels() != null) {
            row.putAll(one.getLabels());
        }
        row.put(FIELD_VALUE, value);
        rows.add(row);

        AlertPreviewResponseDTO.Group group = new AlertPreviewResponseDTO.Group();
        group.setKey(one.getLabels() == null ? Map.of() : new LinkedHashMap<>(one.getLabels()));
        group.setValue(value);
        groups.add(group);
    }

    /** No promql_condition: report what came back without deciding whether it fires. */
    private static void describeUngradedSeries(AlertPreviewResponseDTO.Evaluation evaluation,
            List<PromResultDTO.Series> series, List<AlertPreviewResponseDTO.Group> groups) {
        evaluation.setEvaluated(false);
        evaluation.setObserved((double) series.size());
        evaluation.setObservedLabel("series returned");
        evaluation.setGroups(groups);
        evaluation.setSummary(series.isEmpty()
                ? "The query returned no series over this window — the rule would have "
                  + "nothing to grade."
                : series.size() + " series returned. No promql_condition was supplied, so "
                  + "whether that fires cannot be decided here.");
    }

    /** Grades each series against the promql_condition. */
    private void gradeSeries(AlertPreviewResponseDTO.Evaluation evaluation, JsonNode promCondition,
            List<AlertPreviewResponseDTO.Group> groups) {
        String operator = promCondition.path(FIELD_OPERATOR).asText(">=");
        Double threshold = numeric(promCondition.get(FIELD_VALUE));
        int firing = 0;
        for (AlertPreviewResponseDTO.Group group : groups) {
            boolean satisfies = threshold != null && group.getValue() != null
                    && compare(group.getValue(), operator, threshold);
            group.setSatisfies(satisfies);
            if (satisfies) {
                firing++;
            }
        }
        evaluation.setEvaluated(threshold != null);
        evaluation.setOperator(operator);
        evaluation.setThreshold(threshold);
        evaluation.setObserved((double) firing);
        evaluation.setObservedLabel("series crossing the threshold");
        evaluation.setWouldFire(firing > 0);
        evaluation.setGroups(groups);
        evaluation.setSummary(firing > 0
                ? firing + " of " + groups.size() + " series are " + operator + " " + threshold
                  + " right now, so the rule WOULD fire."
                : "No series is " + operator + " " + threshold + " right now, so the rule would "
                  + "not fire on this window.");
    }

    // ── evaluation against the trigger ──────────────────────────────────────

    /**
     * Decides whether the rule would fire, which is a separate question from
     * what it matches.
     *
     * <p>Two shapes, because OpenObserve has two. A plain rule compares the ROW
     * COUNT against the trigger threshold. An aggregating rule computes one
     * value per group, keeps the groups its HAVING clause admits, and compares
     * how many survived. Previewing the first and calling it the second is how a
     * rule that matches ten thousand rows and fires on none looks healthy.</p>
     */
    private void evaluate(AlertPreviewResponseDTO.Preview out,
            AlertPreviewRequestDTO.Preview request, JsonNode queryCondition,
            String stream, String where) {

        AlertPreviewResponseDTO.Evaluation evaluation = out.getEvaluation();
        JsonNode trigger = request.getTriggerCondition();
        JsonNode aggregation = queryCondition == null ? null : queryCondition.get("aggregation");

        Observation observation = isAggregating(aggregation)
                ? observeAggregation(out, evaluation, aggregation, stream, where)
                : observeLastStep(out, evaluation);
        if (observation == null) {
            // Why it could not be observed is already on the evaluation.
            return;
        }
        Double observed = observation.observed();
        String label = observation.label();

        evaluation.setObserved(observed);
        evaluation.setObservedLabel(label);

        if (trigger == null || trigger.isNull()) {
            evaluation.setEvaluated(false);
            evaluation.setSummary(format(observed) + " " + label + OVER_THE_LAST
                    + out.getPeriodMinutes() + " minutes. No trigger condition was supplied, so "
                    + "whether that fires is not decided here.");
            return;
        }

        String operator = trigger.path(FIELD_OPERATOR).asText(">=");
        Double threshold = numeric(trigger.get("threshold"));
        if (threshold == null) {
            evaluation.setEvaluated(false);
            evaluation.setSummary(format(observed) + " " + label + OVER_THE_LAST
                    + out.getPeriodMinutes() + " minutes. The trigger condition has no numeric "
                    + "threshold, so whether that fires cannot be decided.");
            return;
        }

        boolean fires = compare(observed, operator, threshold);
        evaluation.setEvaluated(true);
        evaluation.setOperator(operator);
        evaluation.setThreshold(threshold);
        evaluation.setWouldFire(fires);
        evaluation.setSummary(format(observed) + " " + label + OVER_THE_LAST
                + out.getPeriodMinutes() + " minutes, against a trigger of " + operator + " "
                + format(threshold) + " — the rule "
                + (fires ? "WOULD fire right now." : "would NOT fire on this window."));
    }

    /** What the rule observed over the window, and what the number counts. */
    private record Observation(Double observed, String label) { }

    private static boolean isAggregating(JsonNode aggregation) {
        return aggregation != null && aggregation.isObject()
                && !aggregation.path("function").asText("").isBlank();
    }

    /** The number of qualifying groups, or null (with the reason recorded) when it failed. */
    private Observation observeAggregation(AlertPreviewResponseDTO.Preview out,
            AlertPreviewResponseDTO.Evaluation evaluation, JsonNode aggregation,
            String stream, String where) {
        try {
            return new Observation((double) aggregate(out, evaluation, aggregation, stream, where),
                    "groups crossing the aggregation's HAVING clause");
        } catch (Exception e) {
            evaluation.setEvaluated(false);
            evaluation.setSummary("The aggregation could not be previewed: " + shortMessage(e));
            log.warn("Preview aggregation failed on {}: {}", stream, e.getMessage());
            return null;
        }
    }

    /** The last step's row count, or null (with the reason recorded) when it did not run. */
    private static Observation observeLastStep(AlertPreviewResponseDTO.Preview out,
            AlertPreviewResponseDTO.Evaluation evaluation) {
        AlertPreviewResponseDTO.Step last = out.getSteps().isEmpty()
                ? null : out.getSteps().get(out.getSteps().size() - 1);
        if (last == null || last.getError() != null) {
            evaluation.setEvaluated(false);
            evaluation.setSummary("The rule's conditions could not be run, so whether it "
                    + "would fire cannot be decided.");
            return null;
        }
        return new Observation((double) last.getMatched(), "matching rows");
    }

    /**
     * Runs the rule's aggregation and counts the groups its HAVING clause keeps.
     *
     * <p>HAVING is applied here rather than in SQL on purpose: every group is
     * returned to the caller with a {@code satisfies} flag, so the preview can
     * show the ones that just missed. A group at 79 against a threshold of 80 is
     * the most useful row on the screen, and pushing HAVING into the query would
     * throw it away before anyone saw it.</p>
     */
    private int aggregate(AlertPreviewResponseDTO.Preview out,
            AlertPreviewResponseDTO.Evaluation evaluation, JsonNode aggregation,
            String stream, String where) {

        String function = aggregation.path("function").asText(FUNCTION_COUNT).trim().toLowerCase(Locale.ROOT);
        JsonNode having = aggregation.get("having");
        String valueColumn = having == null ? null : having.path("column").asText(null);

        List<String> groupBy = groupByColumns(aggregation.get("group_by"));

        String aggregate = aggregateExpression(function, valueColumn);

        String sql = aggregateSql(groupBy, aggregate, stream, where);

        OpenObserveClientService.SearchOutcome outcome = oo.searchDetailed(
                sql, out.getWindowStartMicros(), out.getWindowEndMicros(),
                0, MAX_GROUPS, out.getStreamType());

        String operator = having == null ? ">=" : having.path(FIELD_OPERATOR).asText(">=");
        Double threshold = having == null ? null : numeric(having.get(FIELD_VALUE));

        int qualifying = 0;
        for (Map<String, Object> row : outcome.hits()) {
            if (addAggregateGroup(evaluation, row, groupBy, operator, threshold)) {
                qualifying++;
            }
        }

        if (!outcome.isComplete()) {
            out.setNotice(append(out.getNotice(), "OpenObserve could not scan the whole window for "
                    + "the aggregation, so the group list is incomplete"
                    + (outcome.error() == null ? "" : " (" + outcome.error() + ")") + "."));
        }
        return qualifying;
    }

    /** The aggregation's group_by columns, validated, blanks skipped. */
    private static List<String> groupByColumns(JsonNode groups) {
        List<String> groupBy = new ArrayList<>();
        if (groups == null || !groups.isArray()) {
            return groupBy;
        }
        for (JsonNode column : groups) {
            String name = column.asText("");
            if (!name.isBlank()) {
                groupBy.add(SqlSafety.validateIdentifier(name.trim()));
            }
        }
        return groupBy;
    }

    private static String aggregateSql(List<String> groupBy, String aggregate, String stream, String where) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (String column : groupBy) {
            sql.append('"').append(column).append("\", ");
        }
        sql.append(aggregate).append(" AS ").append(VALUE_ALIAS)
           .append(" FROM \"").append(stream).append('"');
        if (where != null && !where.isBlank()) {
            sql.append(SQL_WHERE).append(where);
        }
        if (!groupBy.isEmpty()) {
            sql.append(" GROUP BY ");
            for (int i = 0; i < groupBy.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append('"').append(groupBy.get(i)).append('"');
            }
        }
        return sql.toString();
    }

    /** Adds one aggregated row as a group and reports whether it passes the HAVING clause. */
    private boolean addAggregateGroup(AlertPreviewResponseDTO.Evaluation evaluation,
            Map<String, Object> row, List<String> groupBy, String operator, Double threshold) {
        AlertPreviewResponseDTO.Group group = new AlertPreviewResponseDTO.Group();
        Map<String, Object> key = new LinkedHashMap<>();
        for (String column : groupBy) {
            key.put(column, row.get(column));
        }
        group.setKey(key);
        Double value = toDouble(row.get(VALUE_ALIAS));
        group.setValue(value);
        // No HAVING clause means every group qualifies — the aggregation is
        // being used to shape the result, not to filter it.
        boolean satisfies = threshold == null || (value != null
                && compare(value, operator, threshold));
        group.setSatisfies(satisfies);
        evaluation.getGroups().add(group);
        return satisfies;
    }

    private static String aggregateExpression(String function, String valueColumn) {
        if (FUNCTION_COUNT.equals(function) || valueColumn == null || valueColumn.isBlank()) {
            return "COUNT(*)";
        }
        String column = "\"" + SqlSafety.validateIdentifier(valueColumn.trim()) + "\"";
        AggregateForm form = AGGREGATE_FORMS.get(function);
        if (form == null) {
            throw new IllegalArgumentException("Unsupported aggregation function '"
                    + function + "' (expected count, avg, min, max, sum or p50/p90/p95/p99)");
        }
        return form.prefix() + column + form.suffix();
    }


    // ── queries ─────────────────────────────────────────────────────────────

    /** A row count plus whether the window behind it was fully scanned. */
    private record Counted(long rows, boolean complete) { }

    private Counted count(String stream, String where, AlertPreviewResponseDTO.Preview out) {
        String sql = "SELECT COUNT(*) AS " + COUNT_ALIAS + " FROM \"" + stream + "\""
                + (where == null || where.isBlank() ? "" : SQL_WHERE + where);

        OpenObserveClientService.SearchOutcome outcome = oo.searchDetailed(
                sql, out.getWindowStartMicros(), out.getWindowEndMicros(), 0, 1,
                out.getStreamType());

        long rows = 0L;
        if (!outcome.hits().isEmpty()) {
            Double value = toDouble(outcome.hits().get(0).get(COUNT_ALIAS));
            rows = value == null ? 0L : value.longValue();
        }
        return new Counted(rows, outcome.isComplete());
    }

    private List<Map<String, Object>> sample(String stream, String where, int size,
            AlertPreviewResponseDTO.Preview out) {

        // Newest first: a preview is read to answer "is this happening now", and
        // the oldest rows in the window are the least informative answer to it.
        String sql = selectAll(stream, where) + " ORDER BY _timestamp DESC";
        OpenObserveClientService.SearchOutcome outcome = oo.searchDetailed(
                sql, out.getWindowStartMicros(), out.getWindowEndMicros(), 0, size,
                out.getStreamType());
        return new ArrayList<>(outcome.hits());
    }

    private static String selectAll(String stream, String where) {
        return "SELECT * FROM \"" + stream + "\""
                + (where == null || where.isBlank() ? "" : SQL_WHERE + where);
    }

    // ── scope ───────────────────────────────────────────────────────────────

    /**
     * The scope predicate, folded into every step.
     *
     * <p>Applied to step 0 as well as the rest. Step 0 exists to answer "does
     * this stream have anything in it", and for a logs preview the honest form
     * of that question is "does it have anything for THIS SERVICE" — the shared
     * stream always has rows, so an unscoped step 0 would say yes regardless and
     * the one thing it is there to rule out would never be ruled out.</p>
     */
    private String scopeSql(AlertPreviewRequestDTO.Preview request,
            AlertPreviewResponseDTO.Preview out) {

        if (request.getScopeType() == null || request.getScopeType().isBlank()
                || request.getScopeKey() == null || request.getScopeKey().isBlank()) {
            return null;
        }
        // Metrics scope by stream, not by predicate — the metric name IS the
        // narrowing — so adding one here would filter a metric stream by a column
        // it may not carry.
        if ("metrics".equals(out.getStreamType())) {
            return null;
        }
        try {
            AlertScopeResponseDTO.FieldList fields = scopes.listFields(
                    request.getScopeType(), request.getScopeKey(),
                    out.getStreamType(), request.getStreamName());
            AlertScopeResponseDTO.ScopeFilter filter = fields.getFilter();
            if (filter == null || filter.getSql() == null) {
                return null;
            }
            out.setScopeLabel(String.join(", ", filter.getValues()));
            out.setScopeSql(filter.getSql());
            return filter.getSql();
        } catch (Exception e) {
            // A preview without the scope is still worth showing — it is simply
            // wider than the rule will be — so the failure becomes a caveat
            // rather than an error. Silently dropping it would be the bad case:
            // the numbers would be too high and nothing would say why.
            log.warn("Could not resolve preview scope {}:{} — {}",
                    request.getScopeType(), request.getScopeKey(), e.getMessage());
            out.setNotice(append(out.getNotice(), "The selected scope could not be applied ("
                    + shortMessage(e) + "), so these counts cover every service on the stream "
                    + "and will be higher than the finished rule's."));
            return null;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String combine(String scopeSql, String conditionSql) {
        if (scopeSql == null || scopeSql.isBlank()) {
            return conditionSql;
        }
        if (conditionSql == null || conditionSql.isBlank()) {
            return scopeSql;
        }
        return "(" + scopeSql + ") AND (" + conditionSql + ")";
    }

    /** {@code custom} unless the rule says otherwise. */
    private static String mode(JsonNode queryCondition) {
        if (queryCondition == null || queryCondition.isNull()) {
            return MODE_CUSTOM;
        }
        String declared = queryCondition.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if ("sql".equals(declared) || MODE_PROMQL.equals(declared) || MODE_CUSTOM.equals(declared)) {
            return declared;
        }
        // Older stored rules leave `type` unset and carry only the field they
        // use, so the presence of one is the answer when nothing declared it.
        if (!queryCondition.path(MODE_PROMQL).asText("").isBlank()) {
            return MODE_PROMQL;
        }
        if (!queryCondition.path("sql").asText("").isBlank()) {
            return "sql";
        }
        return MODE_CUSTOM;
    }

    private static JsonNode conditionsOf(JsonNode queryCondition) {
        if (queryCondition == null || queryCondition.isNull()) {
            return null;
        }
        JsonNode conditions = queryCondition.get("conditions");
        // A caller that posts the conditions node itself rather than the whole
        // query_condition is accepted. The editor legitimately has only that
        // while the rest of the rule is still blank.
        if (conditions == null && (queryCondition.isArray() || queryCondition.has("column")
                || queryCondition.has("or") || queryCondition.has("and"))) {
            return queryCondition;
        }
        return conditions;
    }

    private int resolvePeriod(AlertPreviewRequestDTO.Preview request) {
        if (request.getPeriodMinutes() != null && request.getPeriodMinutes() > 0) {
            return Math.min(request.getPeriodMinutes(), MAX_PERIOD_MINUTES);
        }
        JsonNode trigger = request.getTriggerCondition();
        if (trigger != null && trigger.path("period").isNumber()) {
            int period = trigger.path("period").asInt();
            if (period > 0) {
                return Math.min(period, MAX_PERIOD_MINUTES);
            }
        }
        return DEFAULT_PERIOD_MINUTES;
    }

    private static int resolveSampleSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SAMPLE_SIZE;
        }
        if (requested <= 0) {
            return 0;
        }
        return Math.min(requested, MAX_SAMPLE_SIZE);
    }

    private static boolean compare(double observed, String operator, double threshold) {
        String normalised = operator == null ? ">=" : operator.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case ">", "gt", "greaterthan"                               -> observed > threshold;
            case ">=", "gte", "greaterthanequals", "greaterthanorequal" -> observed >= threshold;
            case "<", "lt", "lessthan"                                  -> observed < threshold;
            case "<=", "lte", "lessthanequals", "lessthanorequal"       -> observed <= threshold;
            case "!=", "<>", "ne", "notequalto"                         -> observed != threshold;
            // '=' last and as the default: an operator this service does not
            // recognise must not silently become "greater than", which would
            // report a rule as firing when it does not.
            default -> observed == threshold;
        };
    }

    private static Double lastValue(PromResultDTO.Series series) {
        if (series == null || series.getValues() == null || series.getValues().isEmpty()) {
            return null;
        }
        List<Object> latest = series.getValues().get(series.getValues().size() - 1);
        // Prometheus pairs are [timestamp, value]; the value is the second slot
        // and arrives as a string in the JSON wire format.
        return latest.size() < 2 ? null : toDouble(latest.get(1));
    }

    private static Double numeric(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return toDouble(node.asText(null));
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whole numbers without a trailing ".0", which reads as a precision nobody meant. */
    private static String format(Double value) {
        if (value == null) {
            return "0";
        }
        if (value == Math.rint(value) && !value.isInfinite()) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static String normaliseStreamType(String raw) {
        if (raw == null) {
            return "logs";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "metrics", "traces", "logs" -> value;
            default -> "logs";
        };
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 400 ? message : message.substring(0, 400) + "…";
    }

    private static String append(String existing, String addition) {
        return existing == null || existing.isBlank() ? addition : existing + " " + addition;
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }
}
