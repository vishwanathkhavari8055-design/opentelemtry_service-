package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns an OpenObserve {@code query_condition.conditions} tree into SQL, and
 * into the sequence of partial trees a preview steps through.
 *
 * <h2>Why this exists rather than asking OpenObserve</h2>
 *
 * <p>OpenObserve evaluates a rule's conditions internally; it has no endpoint
 * that says "how many rows would this match right now", and none at all that
 * answers it for a rule that has not been saved yet. A preview shown while the
 * operator is still typing therefore has to build the query itself, which means
 * this translation has to exist somewhere.</p>
 *
 * <h2>What "one after another" means here</h2>
 *
 * <p>{@link #steps} decomposes the tree into cumulative prefixes: the window
 * with no conditions, then the first condition, then the first two, and so on.
 * Each step is a complete, runnable query. That is the difference between a
 * preview that says "your rule matches nothing" and one that says "your rule
 * matched 4,000 rows until condition 3, which matched none" — the first sends
 * somebody hunting through the whole rule, the second points at the line.</p>
 *
 * <h2>SQL safety</h2>
 *
 * <p>Every column goes through {@link SqlSafety#validateIdentifier} and every
 * string value through {@link SqlSafety#escapeStringLiteral}. The input here is
 * whatever a browser posted, and it is interpolated into SQL that runs against
 * the telemetry store — so this is one of the few places in this service where
 * an unvalidated field would be a genuine injection, not merely a broken query.
 * A rejected column throws, and the preview reports it as a condition-level
 * error rather than failing the whole request.</p>
 */
public final class AlertConditionSql {

    private AlertConditionSql() { }

    /**
     * One rung of the preview ladder.
     *
     * @param index   0 for the unfiltered window, then 1..n
     * @param label   what this step ADDED, in the operator's own terms
     * @param where   the cumulative WHERE clause, or null for the whole window
     * @param joiner  {@code AND} or {@code OR} — how this step joined the previous
     */
    public record Step(int index, String label, String where, String joiner) { }

    /** How many conditions a single preview will step through. */
    public static final int MAX_STEPS = 12;

    private static final String FIELD_COLUMN = "column";
    private static final String OP_IS_NULL = "is_null";
    private static final String OP_IS_NOT_NULL = "is_not_null";
    private static final String OP_CONTAINS = "contains";
    private static final String OP_NOT_CONTAINS = "not_contains";
    private static final String OP_STARTS_WITH = "starts_with";
    private static final String OP_ENDS_WITH = "ends_with";
    private static final String OP_NOT_IN = "not_in";
    private static final String LOWER_PREFIX = "LOWER(";

    /** Group keys, in the order they are looked for on a condition object. */
    private static final List<String> GROUP_KEYS = List.of("and", "or", "AND", "OR");

    /** How a LIKE-based operator wraps its value, and whether it is negated. */
    private record LikeShape(String prefix, String suffix, boolean negated) { }

    private static final Map<String, LikeShape> LIKE_SHAPES = Map.of(
            OP_CONTAINS,     new LikeShape("%", "%", false),
            OP_NOT_CONTAINS, new LikeShape("%", "%", true),
            OP_STARTS_WITH,  new LikeShape("",  "%", false),
            OP_ENDS_WITH,    new LikeShape("%", "",  false));

    /** Every accepted operator spelling, mapped to the one this class works with. */
    private static final Map<String, String> OPERATOR_ALIASES = operatorAliases();

    private static Map<String, String> operatorAliases() {
        Map<String, String> aliases = new HashMap<>();
        alias(aliases, "=", "", "=", "==", "eq", "equalto", "equals");
        alias(aliases, "!=", "!=", "<>", "ne", "notequalto", "not_equals");
        alias(aliases, ">", ">", "gt", "greaterthan");
        alias(aliases, ">=", ">=", "gte", "greaterthanequals", "greaterthanorequal");
        alias(aliases, "<", "<", "lt", "lessthan");
        alias(aliases, "<=", "<=", "lte", "lessthanequals", "lessthanorequal");
        alias(aliases, OP_CONTAINS, OP_CONTAINS);
        alias(aliases, OP_NOT_CONTAINS, OP_NOT_CONTAINS, "notcontains", "does_not_contain");
        alias(aliases, OP_STARTS_WITH, OP_STARTS_WITH, "startswith");
        alias(aliases, OP_ENDS_WITH, OP_ENDS_WITH, "endswith");
        alias(aliases, "in", "in");
        alias(aliases, OP_NOT_IN, OP_NOT_IN, "notin");
        alias(aliases, OP_IS_NULL, OP_IS_NULL, "isnull");
        alias(aliases, OP_IS_NOT_NULL, OP_IS_NOT_NULL, "isnotnull", "exists");
        return Map.copyOf(aliases);
    }

    private static void alias(Map<String, String> aliases, String canonical, String... spellings) {
        for (String spelling : spellings) {
            aliases.put(spelling, canonical);
        }
    }

    // ── decomposition ───────────────────────────────────────────────────────

    /**
     * The cumulative prefixes of a condition tree, outermost level only.
     *
     * <p>Only the TOP level is stepped through. Nested groups are rendered whole
     * at the step that introduces them, because a partial group is not a weaker
     * filter — it is a different one. Stepping "into" an OR group would show
     * intermediate row counts that the finished rule never produces, which is
     * worse than not showing them: it is a number that looks like an answer.</p>
     *
     * <p>Step 0 is always the unfiltered window. It earns its place by
     * separating the two failures that look identical from the bottom of the
     * ladder — conditions that match nothing, and a stream or time range that
     * has nothing in it to match.</p>
     *
     * @return at least one step; never null
     */
    public static List<Step> steps(JsonNode conditions) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(0, "Everything in the window", null, null));

        List<JsonNode> children = topLevelChildren(conditions);
        if (children.isEmpty()) {
            return steps;
        }

        String joiner = groupJoiner(conditions);
        List<String> rendered = new ArrayList<>();

        int limit = Math.min(children.size(), MAX_STEPS);
        for (int i = 0; i < limit; i++) {
            JsonNode child = children.get(i);
            String sql = toSql(child);
            if (sql == null || sql.isBlank()) {
                continue;
            }
            rendered.add(sql);
            // Parenthesised per term. Without it, mixing an OR group into an AND
            // chain silently rebinds — "a AND b OR c" is not "a AND (b OR c)" —
            // and the preview would confidently report counts for a rule nobody
            // wrote.
            String where = rendered.size() == 1
                    ? rendered.get(0)
                    : "(" + String.join(") " + joiner + " (", rendered) + ")";
            steps.add(new Step(steps.size(), describe(child), where, joiner));
        }
        return steps;
    }

    /** True when the tree carries more conditions than {@link #MAX_STEPS} shows. */
    public static boolean isTruncated(JsonNode conditions) {
        return topLevelChildren(conditions).size() > MAX_STEPS;
    }

    /**
     * The whole tree as one WHERE clause, or null when there are no conditions.
     *
     * <p>Null rather than {@code "1=1"}: the caller appends a WHERE only when
     * there is something to put in it, and a tautology in the generated SQL is
     * one more thing to explain when somebody reads the preview's query.</p>
     */
    public static String toSql(JsonNode node) {
        if (isAbsent(node)) {
            return null;
        }

        if (node.isArray()) {
            // A bare array is the older flat shape. AND is the only reading that
            // cannot widen a rule by accident.
            return joinChildren(node, "AND");
        }

        if (!node.isObject()) {
            return null;
        }
        return objectToSql(node);
    }

    /** A condition object: a group, a wrapper around {@code conditions}, or one condition. */
    private static String objectToSql(JsonNode node) {
        String key = groupKey(node);
        if (key != null) {
            return joinChildren(node.get(key), key.toUpperCase(Locale.ROOT));
        }

        JsonNode conditions = node.get("conditions");
        if (conditions != null && !conditions.isNull()) {
            return toSql(conditions);
        }

        if (node.hasNonNull(FIELD_COLUMN)) {
            return condition(node);
        }
        return null;
    }

    private static String joinChildren(JsonNode array, String joiner) {
        List<String> parts = new ArrayList<>();
        for (JsonNode child : array) {
            String sql = toSql(child);
            if (sql != null && !sql.isBlank()) {
                parts.add(sql);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return "(" + String.join(") " + joiner + " (", parts) + ")";
    }

    // ── one condition ───────────────────────────────────────────────────────

    /**
     * One {@code {column, operator, value}} as SQL.
     *
     * @throws IllegalArgumentException for a column that is not a plain
     *         identifier, or an operator this service does not know — both
     *         reported per condition so the rest of the preview still runs
     */
    public static String condition(JsonNode node) {
        String column = requireColumn(node);

        String operator = normaliseOperator(text(node, "operator"));
        JsonNode value = node.get("value");
        boolean ignoreCase = node.path("ignore_case").asBoolean(false);

        String quoted = "\"" + column + "\"";

        LikeShape shape = LIKE_SHAPES.get(operator);
        if (shape != null) {
            return like(quoted, value, ignoreCase, shape.prefix(), shape.suffix(), shape.negated());
        }
        return switch (operator) {
            case OP_IS_NULL     -> quoted + " IS NULL";
            case OP_IS_NOT_NULL -> quoted + " IS NOT NULL";
            case "in"     -> in(quoted, value, false);
            case OP_NOT_IN -> in(quoted, value, true);
            default -> comparison(quoted, operator, value, ignoreCase);
        };
    }

    /** The condition's column, validated as a plain identifier. */
    private static String requireColumn(JsonNode node) {
        String column = text(node, FIELD_COLUMN);
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("A condition has no column");
        }
        SqlSafety.validateIdentifier(column);
        return column;
    }

    /** {@code =}, {@code !=} and the ordering operators. */
    private static String comparison(String quoted, String operator, JsonNode value, boolean ignoreCase) {
        String literal = literal(value);
        if (ignoreCase && isStringy(value)) {
            // LOWER on both sides rather than ILIKE: DataFusion supports
            // it everywhere, and it keeps = and != symmetric with the
            // comparison operators, which have no ILIKE equivalent.
            return LOWER_PREFIX + quoted + ") " + operator + " " + LOWER_PREFIX + literal + ")";
        }
        return quoted + " " + operator + " " + literal;
    }

    /**
     * The same condition as something an operator recognises as their own.
     *
     * <p>Deliberately close to the SQL rather than prose. The person reading a
     * preview step is the person who just typed the condition, and "severity =
     * 'ERROR'" is what they typed — a sentence about it would be a translation
     * they then have to translate back.</p>
     */
    public static String describe(JsonNode node) {
        if (node == null) {
            return "(empty condition)";
        }
        for (String key : GROUP_KEYS) {
            JsonNode group = node.get(key);
            if (group != null && group.isArray()) {
                List<String> parts = new ArrayList<>();
                group.forEach(child -> parts.add(describe(child)));
                return "(" + String.join(" " + key.toLowerCase(Locale.ROOT) + " ", parts) + ")";
            }
        }
        String column = text(node, FIELD_COLUMN);
        if (column == null) {
            return "(empty condition)";
        }
        String operator = text(node, "operator");
        JsonNode value = node.get("value");
        String shown = formatShownValue(value);
        return (column + " " + (operator == null ? "=" : operator) + " " + shown).trim();
    }

    private static String formatShownValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return "'" + value.asText() + "'";
        }
        return value.asText();
    }

    // ── pieces ──────────────────────────────────────────────────────────────

    private static String like(String column, JsonNode value, boolean ignoreCase,
            String prefix, String suffix, boolean negated) {

        String raw = value == null || value.isNull() ? "" : value.asText("");
        // escapeLikePattern already doubles single quotes as well as escaping the
        // wildcards, so it is the WHOLE escape for a LIKE operand. Running
        // escapeStringLiteral over the result as well would turn the '' it just
        // produced into '''', and a search for "it's" would match nothing.
        //
        // The wildcards are appended AFTER escaping, on purpose: they are ours,
        // not the user's, and escaping them would search for a literal percent.
        String pattern = prefix + SqlSafety.escapeLikePattern(raw) + suffix;
        String literal = "'" + pattern + "'";
        String target = ignoreCase ? LOWER_PREFIX + column + ")" : column;
        String compared = ignoreCase ? LOWER_PREFIX + literal + ")" : literal;
        return target + (negated ? " NOT LIKE " : " LIKE ") + compared;
    }

    private static String in(String column, JsonNode value, boolean negated) {
        List<String> literals = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(element -> literals.add(literal(element)));
        } else if (value != null && !value.isNull()) {
            literals.add(literal(value));
        }
        if (literals.isEmpty()) {
            // An empty IN list is a syntax error in DataFusion, and "matches
            // nothing" is the honest reading of a condition with no values.
            return negated ? "1 = 1" : "1 = 0";
        }
        return column + (negated ? " NOT IN (" : " IN (") + String.join(", ", literals) + ")";
    }

    private static String literal(JsonNode value) {
        if (value == null || value.isNull()) {
            return "NULL";
        }
        if (value.isNumber()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "true" : "false";
        }
        return "'" + SqlSafety.escapeStringLiteral(value.asText("")) + "'";
    }

    private static boolean isStringy(JsonNode value) {
        return value != null && !value.isNumber() && !value.isBoolean() && !value.isNull();
    }

    /**
     * Maps every spelling OpenObserve has used to one SQL operator.
     *
     * <p>Both the snake_case wire form and the CamelCase enum name appear in
     * stored rules on this deployment, depending on which version wrote them —
     * an importer reading an older alert gets {@code EqualTo} where the current
     * UI sends {@code =}. Rejecting either would break preview for exactly the
     * rules most in need of it.</p>
     */
    private static String normaliseOperator(String raw) {
        String value = raw == null ? "=" : raw.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        String canonical = OPERATOR_ALIASES.get(value);
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "Unsupported condition operator '" + raw + "'");
        }
        return canonical;
    }

    // ── tree shape ──────────────────────────────────────────────────────────

    private static List<JsonNode> topLevelChildren(JsonNode conditions) {
        List<JsonNode> children = new ArrayList<>();
        if (isAbsent(conditions)) {
            return children;
        }
        if (conditions.isArray()) {
            conditions.forEach(children::add);
            return children;
        }
        if (!conditions.isObject()) {
            return children;
        }
        String key = groupKey(conditions);
        if (key != null) {
            conditions.get(key).forEach(children::add);
        } else if (conditions.hasNonNull(FIELD_COLUMN)) {
            children.add(conditions);
        }
        return children;
    }

    /** The first of {@link #GROUP_KEYS} that holds an array on {@code node}, or null. */
    private static String groupKey(JsonNode node) {
        for (String key : GROUP_KEYS) {
            JsonNode group = node.get(key);
            if (group != null && group.isArray()) {
                return key;
            }
        }
        return null;
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    /** {@code AND} unless the top level says otherwise. */
    private static String groupJoiner(JsonNode conditions) {
        if (conditions != null && conditions.isObject()
                && (conditions.has("or") || conditions.has("OR"))) {
            return "OR";
        }
        return "AND";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
