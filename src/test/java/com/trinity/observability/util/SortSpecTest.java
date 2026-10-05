package com.trinity.observability.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SortSpecTest {

    /** Column and direction for inputs that parse, and the default for ones that do not. */
    @ParameterizedTest(name = "\"{0}\" -> {1} {2}")
    @CsvSource({
            // null falls back to the default
            ", _timestamp, DESC",
            "'serviceName,desc', service_name, DESC",
            // an unknown field falls back to the default
            "'password,asc', _timestamp, DESC",
            // an unknown direction falls back to DESC
            "'timestamp,sideways', _timestamp, DESC"
    })
    void parsesColumnAndDirection(String raw, String column, SortSpec.Direction direction) {
        SortSpec s = SortSpec.parseLogs(raw);
        assertEquals(column, s.column());
        assertEquals(direction, s.direction());
    }

    @Test
    void emptyFallsBackToDefault() {
        assertEquals("ORDER BY _timestamp DESC", SortSpec.parseLogs("").toSqlClause());
        assertEquals("ORDER BY _timestamp DESC", SortSpec.parseLogs("   ").toSqlClause());
    }

    @Test
    void timestampAscParses() {
        SortSpec s = SortSpec.parseLogs("timestamp,asc");
        assertEquals("_timestamp", s.column());
        assertEquals(SortSpec.Direction.ASC, s.direction());
        assertEquals("ORDER BY _timestamp ASC", s.toSqlClause());
    }

    @Test
    void injectionAttemptIsScrubbed() {
        SortSpec s = SortSpec.parseLogs("timestamp; DROP TABLE logs--,asc");
        // Field "timestamp; DROP TABLE logs--" is not in the whitelist → default.
        String sql = s.toSqlClause();
        assertEquals("ORDER BY _timestamp DESC", sql);
        assertFalse(sql.toLowerCase().contains("drop"), "injection rejected: " + sql);
    }
}
