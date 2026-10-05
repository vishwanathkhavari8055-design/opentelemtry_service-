package com.trinity.observability.clientimpl;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test against a live OpenObserve at http://localhost:15080.
 * Disabled by default; enable via env var OO_LIVE_SMOKE=1 and remove @Disabled.
 *
 * <p>Purpose: catch issues that mocks can't surface — DataFusion SQL syntax errors,
 * schema name typos, auth-header mistakes, real-world error response shapes.</p>
 */
@Disabled("Live smoke test — requires a running OpenObserve instance (OO_LIVE_SMOKE=1)")
class OpenObserveClientImplLiveSmokeTest {

    @Test
    void placeholder() {
        // This smoke test suite requires a live OpenObserve instance.
        // Individual test methods should be added and enabled as needed.
        assertTrue(true, "placeholder");
    }
}
