package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The widening ladder behind "Open trace".
 *
 * <p>The behaviour worth pinning is not "it queries" but WHICH window ends up
 * being used, because that is the thing that decides whether a trace older than
 * the default range can be opened at all.</p>
 */
class TraceLookupWindowTest {

    private static final Map<String, Object> ROW = Map.of("span_id", "s1");

    @Test
    void firstWindowThatReturnsRowsWins() {
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                List.of("now-7d", "now-1h"),
                (s, e) -> { starts.add(s); return List.of(ROW); });

        assertEquals(1, starts.size(), "a hit on the widest window must not trigger a retry");
        assertEquals(1, out.hits().size());
        assertEquals(starts.get(0).longValue(), out.startMicros(), "returns the window that answered");
    }

    @Test
    void narrowerWindowIsTriedWhenTheWiderOneComesBackEmpty() {
        // The measured failure mode this exists for: OpenObserve answers a scan it
        // cannot serve with 200 and zero hits, which looks exactly like "no such
        // trace". Only the narrow window returns anything here.
        long cutoff = TimeRangeParser.toMicros("now-2h", 0L);
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                List.of("now-7d", "now-24h", "now-1h"),
                (s, e) -> {
                    starts.add(s);
                    return s >= cutoff ? List.of(ROW) : List.of();
                });

        assertEquals(3, starts.size(), "should widen through the ladder until something answers");
        assertEquals(1, out.hits().size(), "the narrow window's rows are returned");
        assertTrue(out.startMicros() >= cutoff, "the answering window is the one reported back");
    }

    @Test
    void everyAttemptSharesOneEndInstant() {
        List<Long> ends = new ArrayList<>();

        TraceLookupWindow.firstNonEmpty(
                List.of("now-7d", "now-24h", "now-1h"),
                (s, e) -> { ends.add(e); return List.of(); });

        assertEquals(3, ends.size());
        assertEquals(1, ends.stream().distinct().count(),
                "re-reading the clock per attempt would let the window creep between retries");
    }

    @Test
    void allEmptyReportsTheWidestWindowTried() {
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                List.of("now-7d", "now-1h"),
                (s, e) -> { starts.add(s); return List.of(); });

        assertTrue(out.hits().isEmpty());
        assertEquals(starts.get(0).longValue(), out.startMicros(),
                "an empty answer belongs to the widest range actually attempted");
    }

    @Test
    void unparseableWindowIsSkippedRatherThanFailingTheRequest() {
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                List.of("not-a-window", "", "now-1h"),
                (s, e) -> { starts.add(s); return List.of(ROW); });

        assertEquals(1, starts.size(), "only the one usable expression should be queried");
        assertEquals(1, out.hits().size(), "a typo in configuration must not 500 the trace view");
    }

    @Test
    void theStreamsOwnExtentIsTriedBeforeAnyConfiguredWindow() {
        // The point of the derived window: no configured expression gets a say in
        // whether a trace is old enough to be unopenable.
        long streamMin = TimeRangeParser.toMicros("now-30d", 0L);
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                streamMin, List.of("now-1h"),
                (s, e) -> { starts.add(s); return List.of(ROW); });

        assertEquals(1, starts.size(), "the derived window answered, so no fallback was tried");
        assertTrue(out.startMicros() <= streamMin,
                "searches from the stream's oldest record, not from a configured lookback");
        assertEquals(1, out.hits().size());
    }

    @Test
    void configuredWindowsBackStopADerivedWindowThatCannotBeServed() {
        // A full-extent scan this instance answers with 200-and-zero-hits must not
        // be mistaken for "no such trace".
        long streamMin = TimeRangeParser.toMicros("now-30d", 0L);
        long cutoff = TimeRangeParser.toMicros("now-2h", 0L);
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                streamMin, List.of("now-7d", "now-1h"),
                (s, e) -> {
                    starts.add(s);
                    return s >= cutoff ? List.of(ROW) : List.of();
                });

        assertEquals(3, starts.size(), "derived window, then each fallback, until one answers");
        assertEquals(1, out.hits().size());
        assertTrue(out.startMicros() >= cutoff);
    }

    @Test
    void absentStreamMetadataFallsStraightThroughToTheConfiguredWindows() {
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                null, List.of("now-7d"),
                (s, e) -> { starts.add(s); return List.of(ROW); });

        assertEquals(1, starts.size(), "a null extent must not cost an extra query");
        assertEquals(1, out.hits().size());
    }

    @Test
    void noUsableWindowFallsBackToTheSharedDefaultRange() {
        TraceLookupWindow.Resolved out = TraceLookupWindow.firstNonEmpty(
                List.of("nonsense"), (s, e) -> List.of(ROW));

        assertTrue(out.hits().isEmpty(), "nothing was queryable, so nothing was queried");
        assertTrue(out.endMicros() > out.startMicros(), "still hands back a usable range");
    }

    // ── narrowestComplete: the fast path for "Open trace" ────────────────────

    private static final long MARGIN = 300_000_000L;

    private static Map<String, Object> span(String parent, Object timestamp) {
        Map<String, Object> h = new HashMap<>();
        h.put("span_id", "s");
        h.put("reference_parent_span_id", parent);
        h.put("_timestamp", timestamp);
        return h;
    }

    private static long ago(String expr) {
        return TimeRangeParser.toMicros(expr, 0L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aRecentTraceIsAnsweredByTheNarrowestWindowInOneQuery(String rootParent) {
        long streamMin = ago("now-30d");
        List<Long> starts = new ArrayList<>();
        Map<String, Object> root = span(rootParent, ago("now-10m"));

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                streamMin, List.of("now-7d", "now-24h", "now-1h"), MARGIN,
                (s, e) -> { starts.add(s); return List.of(root); });

        assertEquals(1, starts.size(), "the root is inside the first window, so nothing wider is scanned");
        assertTrue(out.startMicros() >= ago("now-61m"), "the 1h window answered, not the 30-day extent");
        assertEquals(1, out.hits().size());
    }

    @Test
    void windowsAreTriedNarrowestFirstWhateverTheConfiguredOrder() {
        long streamMin = ago("now-30d");
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.narrowestComplete(
                streamMin, List.of("now-1h", "now-7d", "", "not-a-window", "now-24h", "now-1h"), MARGIN,
                (s, e) -> { starts.add(s); return List.of(); });

        assertEquals(4, starts.size(), "1h, 24h, 7d and the extent once each; blanks, typos and repeats skipped");
        for (int i = 1; i < starts.size(); i++) {
            assertTrue(starts.get(i) < starts.get(i - 1), "each attempt must be wider than the last: " + starts);
        }
        assertEquals(streamMin - 1_000_000L, starts.get(3).longValue(), "the stream's extent is the widest");
    }

    @Test
    void aRootTooCloseToTheWindowStartWidensForClockSkew() {
        List<Long> starts = new ArrayList<>();
        Map<String, Object> root = span(null, ago("now-58m"));

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                null, List.of("now-6h", "now-1h"), MARGIN,
                (s, e) -> { starts.add(s); return List.of(root); });

        assertEquals(2, starts.size(), "a root 2 minutes inside the window could have a skewed child outside it");
        assertEquals(starts.get(1).longValue(), out.startMicros(), "the 6h window is the answer");
    }

    @Test
    void withoutARootTheMostCompleteAttemptWins() {
        // The wide scans fail (200, zero hits) as on this deployment; the narrow ones
        // each return part of a trace whose root was never ingested.
        long cutoff = ago("now-7h");
        long threeHoursAgo = ago("now-3h");
        Map<String, Object> child = span("missing-root", ago("now-10m"));
        Map<String, Object> olderChild = span("missing-root", ago("now-5h"));
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                ago("now-30d"), List.of("now-7d", "now-6h", "now-1h"), MARGIN,
                (s, e) -> {
                    starts.add(s);
                    if (s < cutoff) return List.of();
                    return s <= threeHoursAgo ? List.of(child, olderChild) : List.of(child);
                });

        assertEquals(4, starts.size(), "no root anywhere, so every window is tried");
        assertEquals(2, out.hits().size(), "the attempt with the most spans is returned");
        assertEquals(starts.get(1).longValue(), out.startMicros());
    }

    @Test
    void aTieGoesToTheWiderWindow() {
        Map<String, Object> child = span("missing-root", ago("now-10m"));
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                null, List.of("now-6h", "now-1h"), MARGIN,
                (s, e) -> { starts.add(s); return List.of(child); });

        assertEquals(starts.get(1).longValue(), out.startMicros(),
                "equal answers: the wider range is the one that cannot have missed anything");
    }

    @Test
    void aRootWithoutANumericTimestampDoesNotEndTheSearch() {
        Map<String, Object> root = span(" ", "not-a-number");
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.narrowestComplete(
                null, List.of("now-6h", "now-1h"), MARGIN,
                (s, e) -> { starts.add(s); return List.of(root); });

        assertEquals(2, starts.size(), "an unplaceable root proves nothing about the window");
    }

    @Test
    void allEmptyReportsTheWidestWindowTriedByNarrowestComplete() {
        long streamMin = ago("now-30d");
        List<Long> ends = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                streamMin, List.of("now-1h"), MARGIN,
                (s, e) -> { ends.add(e); return null; });

        assertTrue(out.hits().isEmpty());
        assertEquals(streamMin - 1_000_000L, out.startMicros(), "an empty answer belongs to the widest range");
        assertEquals(1, ends.stream().distinct().count(), "every attempt shares one end instant");
    }

    @Test
    void narrowestCompleteWithNoUsableWindowFallsBackToTheDefaultRange() {
        List<Long> starts = new ArrayList<>();

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                0L, null, MARGIN, (s, e) -> { starts.add(s); return List.of(ROW); });

        assertTrue(starts.isEmpty(), "nothing was queryable, so nothing was queried");
        assertTrue(out.hits().isEmpty());
        assertTrue(out.endMicros() > out.startMicros(), "still hands back a usable range");
    }

    @Test
    void theRootFoundIsReturnedUnchanged() {
        Map<String, Object> root = span(null, ago("now-10m"));

        TraceLookupWindow.Resolved out = TraceLookupWindow.narrowestComplete(
                null, List.of("now-1h"), MARGIN, (s, e) -> List.of(root));

        assertSame(root, out.hits().get(0));
    }
}
