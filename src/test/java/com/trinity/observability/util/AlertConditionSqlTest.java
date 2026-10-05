package com.trinity.observability.util;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the translation the preview is built on.
 *
 * <p>Two classes of failure are worth a test here, and they are not the same
 * kind of problem. One is SQL that will not run — caught the first time anybody
 * previews anything. The other is SQL that runs and answers a DIFFERENT
 * question: a rebound operator precedence, an OR silently read as an AND, a
 * quote that escapes the value out of its literal. Those come back as a
 * plausible number, and a preview whose numbers are plausible and wrong is
 * worse than no preview at all, because the operator now believes them.</p>
 */
class AlertConditionSqlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── single conditions ───────────────────────────────────────────────────

    @Test
    void rendersAPlainEquality() {
        assertEquals("\"service_name\" = 'IoTOpsApiSvc'",
                AlertConditionSql.toSql(json("""
                        {"column":"service_name","operator":"=","value":"IoTOpsApiSvc"}""")));
    }

    @Test
    void leavesNumbersUnquotedSoComparisonsAreNumeric() {
        // Quoting a number turns "duration >= 500" into a string comparison, in
        // which "1000" sorts below "500" and the rule quietly stops matching the
        // slowest requests — the only ones it was written for.
        assertEquals("\"duration\" >= 500",
                AlertConditionSql.toSql(json("""
                        {"column":"duration","operator":">=","value":500}""")));
    }

    @Test
    void escapesQuotesInValuesRatherThanEndingTheLiteral() {
        String sql = AlertConditionSql.toSql(json("""
                {"column":"message","operator":"=","value":"it's down"}"""));
        assertEquals("\"message\" = 'it''s down'", sql);
    }

    @Test
    void escapesLikeWildcardsExactlyOnce() {
        // escapeLikePattern already doubles single quotes. Running the string
        // escaper over its output as well would produce '''' and match nothing.
        String sql = AlertConditionSql.toSql(json("""
                {"column":"msg","operator":"contains","value":"it's 50%"}"""));
        assertEquals("\"msg\" LIKE '%it''s 50\\%%'", sql);
    }

    @Test
    void acceptsBothTheWireAndTheEnumSpellingOfAnOperator() {
        // Rules imported from older OpenObserve versions carry CamelCase.
        // Rejecting them would break preview for exactly the rules that most
        // need checking.
        assertEquals("\"code\" != 200",
                AlertConditionSql.toSql(json("""
                        {"column":"code","operator":"NotEqualTo","value":200}""")));
        assertEquals("\"code\" >= 500",
                AlertConditionSql.toSql(json("""
                        {"column":"code","operator":"GreaterThanEquals","value":500}""")));
    }

    @Test
    void lowersBothSidesWhenIgnoreCaseIsSet() {
        assertEquals("LOWER(\"severity\") = LOWER('ERROR')",
                AlertConditionSql.toSql(json("""
                        {"column":"severity","operator":"=","value":"ERROR","ignore_case":true}""")));
    }

    @Test
    void doesNotLowerANumericComparison() {
        // LOWER(500) is not a thing, and applying it would fail the query for a
        // flag that has no meaning on a number anyway.
        assertEquals("\"code\" >= 500",
                AlertConditionSql.toSql(json("""
                        {"column":"code","operator":">=","value":500,"ignore_case":true}""")));
    }

    @Test
    void anEmptyInListMatchesNothingRatherThanFailing() {
        // DataFusion rejects `IN ()` outright. "Matches nothing" is the honest
        // reading and keeps the rest of the ladder runnable.
        assertEquals("\"host\" IN ('a', 'b')",
                AlertConditionSql.toSql(json("""
                        {"column":"host","operator":"in","value":["a","b"]}""")));
        assertEquals("1 = 0",
                AlertConditionSql.toSql(json("""
                        {"column":"host","operator":"in","value":[]}""")));
    }

    @Test
    void refusesAColumnThatIsNotAnIdentifier() {
        // This is the injection boundary: the column is interpolated into SQL
        // with no binding available.
        JsonNode injection = json("""
                {"column":"a' OR '1'='1","operator":"=","value":"x"}""");
        assertThrows(IllegalArgumentException.class, () -> AlertConditionSql.toSql(injection));
    }

    @Test
    void refusesAnOperatorItDoesNotUnderstand() {
        // Guessing would mean previewing a rule nobody wrote.
        JsonNode unknownOperator = json("""
                {"column":"a","operator":"matches_regex","value":"x"}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AlertConditionSql.toSql(unknownOperator));
        assertEquals("Unsupported condition operator 'matches_regex'", e.getMessage());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "''|=", "=|=", "==|=", "eq|=", "EqualTo|=", "equals|=",
            "!=|!=", "<>|!=", "ne|!=", "NotEqualTo|!=", "not equals|!=",
            ">|>", "gt|>", "GreaterThan|>",
            ">=|>=", "gte|>=", "GreaterThanEquals|>=", "GreaterThanOrEqual|>=",
            "<|<", "lt|<", "LessThan|<",
            "<=|<=", "lte|<=", "LessThanEquals|<=", "LessThanOrEqual|<="
    })
    void mapsEveryComparisonSpellingToOneOperator(String spelling, String sqlOperator) {
        JsonNode node = MAPPER.createObjectNode()
                .put("column", "code").put("operator", spelling).put("value", 5);
        assertEquals("\"code\" " + sqlOperator + " 5", AlertConditionSql.condition(node));
    }

    static Stream<Arguments> nonComparisonOperators() {
        return Stream.of(
                Arguments.of("contains", "\"msg\" LIKE '%x%'"),
                Arguments.of("not_contains", "\"msg\" NOT LIKE '%x%'"),
                Arguments.of("NotContains", "\"msg\" NOT LIKE '%x%'"),
                Arguments.of("does not contain", "\"msg\" NOT LIKE '%x%'"),
                Arguments.of("starts_with", "\"msg\" LIKE 'x%'"),
                Arguments.of("StartsWith", "\"msg\" LIKE 'x%'"),
                Arguments.of("ends_with", "\"msg\" LIKE '%x'"),
                Arguments.of("EndsWith", "\"msg\" LIKE '%x'"),
                Arguments.of("in", "\"msg\" IN ('x')"),
                Arguments.of("not_in", "\"msg\" NOT IN ('x')"),
                Arguments.of("NotIn", "\"msg\" NOT IN ('x')"),
                Arguments.of("is_null", "\"msg\" IS NULL"),
                Arguments.of("IsNull", "\"msg\" IS NULL"),
                Arguments.of("is_not_null", "\"msg\" IS NOT NULL"),
                Arguments.of("IsNotNull", "\"msg\" IS NOT NULL"),
                Arguments.of("exists", "\"msg\" IS NOT NULL"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("nonComparisonOperators")
    void rendersEveryNonComparisonOperator(String spelling, String expected) {
        JsonNode node = MAPPER.createObjectNode()
                .put("column", "msg").put("operator", spelling).put("value", "x");
        assertEquals(expected, AlertConditionSql.condition(node));
    }

    @Test
    void lowersBothSidesOfALikeWhenIgnoreCaseIsSet() {
        JsonNode node = json("""
                {"column":"msg","operator":"ends_with","value":"Down","ignore_case":true}""");
        assertEquals("LOWER(\"msg\") LIKE LOWER('%Down')", AlertConditionSql.condition(node));
    }

    @Test
    void aMissingOperatorMeansEquality() {
        JsonNode node = json("""
                {"column":"host","value":"h1"}""");
        assertEquals("\"host\" = 'h1'", AlertConditionSql.condition(node));
    }

    @Test
    void refusesAConditionWithNoColumnBeforeLookingAtItsOperator() {
        JsonNode node = json("""
                {"column":" ","operator":"matches_regex","value":"x"}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AlertConditionSql.condition(node));
        assertEquals("A condition has no column", e.getMessage());
    }

    @Test
    void readsAConditionsWrapperAndASingleConditionAsTheTopLevel() {
        JsonNode wrapped = json("""
                {"conditions":{"or":[{"column":"a","value":1},{"column":"b","value":2}]}}""");
        assertEquals("(\"a\" = 1) OR (\"b\" = 2)", AlertConditionSql.toSql(wrapped));

        JsonNode single = json("""
                {"column":"a","operator":"=","value":1}""");
        assertEquals(2, AlertConditionSql.steps(single).size());
        assertEquals("\"a\" = 1", AlertConditionSql.steps(single).get(1).where());

        JsonNode neither = json("""
                {"something":"else"}""");
        assertEquals(1, AlertConditionSql.steps(neither).size());
        assertNull(AlertConditionSql.toSql(neither));
        assertNull(AlertConditionSql.toSql(json("\"text\"")));
    }

    // ── trees ───────────────────────────────────────────────────────────────

    @Test
    void parenthesisesEveryTermSoMixedGroupsCannotRebind() {
        // "a AND b OR c" is not "a AND (b OR c)". Without the brackets the
        // preview reports counts for a rule that was never written.
        String sql = AlertConditionSql.toSql(json("""
                {"and":[
                  {"column":"service_name","operator":"=","value":"svc"},
                  {"or":[
                    {"column":"code","operator":"=","value":500},
                    {"column":"code","operator":"=","value":503}]}]}"""));
        assertEquals("(\"service_name\" = 'svc') AND ((\"code\" = 500) OR (\"code\" = 503))", sql);
    }

    @Test
    void readsABareArrayAsAnAndChain() {
        // The older flat shape. AND is the only reading that cannot accidentally
        // widen a rule into firing more often than its author meant.
        assertEquals("(\"a\" = 1) AND (\"b\" = 2)",
                AlertConditionSql.toSql(json("""
                        [{"column":"a","operator":"=","value":1},
                         {"column":"b","operator":"=","value":2}]""")));
    }

    @Test
    void returnsNullForNoConditionsRatherThanATautology() {
        assertNull(AlertConditionSql.toSql(json("{\"and\":[]}")));
        assertNull(AlertConditionSql.toSql(null));
    }

    // ── the ladder ──────────────────────────────────────────────────────────

    @Test
    void stepZeroIsAlwaysTheUnfilteredWindow() {
        // It is what separates "the conditions are wrong" from "the stream or
        // the window is wrong", which look identical from any later step.
        List<AlertConditionSql.Step> steps = AlertConditionSql.steps(null);
        assertEquals(1, steps.size());
        assertEquals(0, steps.get(0).index());
        assertNull(steps.get(0).where());
    }

    @Test
    void eachStepAddsExactlyOneConditionCumulatively() {
        List<AlertConditionSql.Step> steps = AlertConditionSql.steps(json("""
                {"and":[
                  {"column":"service_name","operator":"=","value":"svc"},
                  {"column":"code","operator":">=","value":500},
                  {"column":"host","operator":"=","value":"h1"}]}"""));

        assertEquals(4, steps.size());
        assertNull(steps.get(0).where());
        assertEquals("\"service_name\" = 'svc'", steps.get(1).where());
        assertEquals("(\"service_name\" = 'svc') AND (\"code\" >= 500)", steps.get(2).where());
        assertEquals("(\"service_name\" = 'svc') AND (\"code\" >= 500) AND (\"host\" = 'h1')",
                steps.get(3).where());
    }

    @Test
    void stepsJoinWithTheGroupsOwnOperator() {
        // An OR rule read as AND would report an intersection where the finished
        // rule takes a union — a smaller number that looks like a stricter rule.
        List<AlertConditionSql.Step> steps = AlertConditionSql.steps(json("""
                {"or":[
                  {"column":"code","operator":"=","value":500},
                  {"column":"code","operator":"=","value":503}]}"""));
        assertEquals("(\"code\" = 500) OR (\"code\" = 503)", steps.get(2).where());
        assertEquals("OR", steps.get(2).joiner());
    }

    @Test
    void rendersANestedGroupWholeAtTheStepThatIntroducesIt() {
        // A partial OR group is not a weaker filter, it is a different one. Its
        // row count would be a number the finished rule never produces.
        List<AlertConditionSql.Step> steps = AlertConditionSql.steps(json("""
                {"and":[
                  {"column":"a","operator":"=","value":1},
                  {"or":[{"column":"b","operator":"=","value":2},
                         {"column":"c","operator":"=","value":3}]}]}"""));
        assertEquals(3, steps.size());
        assertEquals("(\"a\" = 1) AND ((\"b\" = 2) OR (\"c\" = 3))", steps.get(2).where());
    }

    @Test
    void labelsEachStepWithWhatTheOperatorTyped() {
        List<AlertConditionSql.Step> steps = AlertConditionSql.steps(json("""
                {"and":[{"column":"severity","operator":"=","value":"ERROR"}]}"""));
        assertEquals("severity = 'ERROR'", steps.get(1).label());
    }

    @Test
    void capsTheLadderAndSaysSo() {
        StringBuilder conditions = new StringBuilder("{\"and\":[");
        for (int i = 0; i < AlertConditionSql.MAX_STEPS + 3; i++) {
            conditions.append(i == 0 ? "" : ",")
                      .append("{\"column\":\"c").append(i).append("\",\"operator\":\"=\",\"value\":1}");
        }
        conditions.append("]}");
        JsonNode tree = json(conditions.toString());

        assertTrue(AlertConditionSql.isTruncated(tree));
        assertEquals(AlertConditionSql.MAX_STEPS + 1, AlertConditionSql.steps(tree).size());
        // The full tree is still translatable — only the LADDER is capped, so
        // the evaluation at the end still uses every condition.
        assertFalse(AlertConditionSql.toSql(tree).isBlank());
    }
}
