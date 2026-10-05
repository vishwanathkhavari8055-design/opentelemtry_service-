package com.trinity.observability.serviceimpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.AlertIncidentProperties;
import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.alerts.AlertEvent;
import com.trinity.observability.entity.AlertIngestCursorEntity;
import com.trinity.observability.service.AlertIngestService.PollResult;
import com.trinity.observability.service.AlertProcessingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How far the poller reads, and when its watermark is allowed to move.
 *
 * <p>These tests exist because of a live, 66-hour outage that reported itself
 * healthy. The query window is watermark → now, so an outage of any length
 * eventually asks OpenObserve for a range it cannot scan — and OpenObserve does
 * not refuse: it answers HTTP 200 with {@code hits: []} and {@code is_partial}.
 * The poller read that as "no new alerts", recorded a SUCCESS, left the watermark
 * where it was, and re-issued the identical impossible query every 30 seconds
 * indefinitely. Every alert already stored was then auto-resolved as stale, so the
 * Alerts screen went and stayed empty while the stream was receiving rows.</p>
 *
 * <p>So the properties under test are all about not confusing "nothing there" with
 * "could not look", and about guaranteeing forward progress in every case —
 * including the one where a range is permanently unreadable and retrying it can
 * never succeed.</p>
 */
class AlertIngestServiceImplWindowTest {

    private static final String ORG = "default";
    private static final String STREAM = "alert_events";
    private static final long ONE_HOUR = 3_600L * 1_000_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeClient client;
    private FakeCursors cursors;
    private AlertIngestProperties properties;
    private AlertIngestServiceImpl service;

    @BeforeEach
    void setUp() {
        client = new FakeClient();
        cursors = new FakeCursors();
        properties = new AlertIngestProperties();
        properties.setStream(STREAM);
        properties.setMaxWindowMinutes(360);          // 6h chunks
        properties.setPartialRetriesBeforeSkip(3);
        properties.setBatchSize(500);
        properties.setOverlapSeconds(60);

        OpenObserveConfig config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", ORG);

        service = new AlertIngestServiceImpl(cursors, new NoopProcessing(), client, config,
                properties, new AlertIncidentProperties());
        ReflectionTestUtils.setField(service, "openObserveEnabled", true);
    }

    /** Put the cursor this far behind "now". */
    private void cursorBehindBy(long micros) {
        cursors.cursor.setLastEventMicros(nowMicros() - micros);
    }

    // ── the window is bounded ────────────────────────────────────────────────

    /**
     * The bug in one assertion: a 66h gap must NOT become a 66h query.
     */
    @Test
    void aLongGapIsQueriedInBoundedChunks() {
        cursorBehindBy(66 * ONE_HOUR);

        service.pollOnce();

        assertNotNull(client.lastStart);
        long span = client.lastEnd - client.lastStart;
        assertTrue(span <= properties.maxWindowMicros(),
                "window span " + span + " exceeded the configured chunk of "
                        + properties.maxWindowMicros());
    }

    /** Caught up, so there is nothing to chunk and the window runs to now. */
    @Test
    void theLiveTailIsQueriedInOneWindow() {
        cursorBehindBy(2 * ONE_HOUR);

        service.pollOnce();

        long span = client.lastEnd - client.lastStart;
        assertTrue(span > 2 * ONE_HOUR - ONE_HOUR, "expected a single window to now, got " + span);
        assertTrue(span < 3 * ONE_HOUR);
    }

    // ── progress through empty ranges ────────────────────────────────────────

    /**
     * A quiet chunk still has to advance the watermark.
     *
     * <p>Without this the poller stalls one chunk further along than before:
     * chunking bounds the query, but a chunk that matches nothing leaves the
     * watermark untouched, so the same chunk is re-read forever. A chunk served in
     * FULL is proof there is nothing in it.</p>
     */
    @Test
    void anEmptyButCompleteChunkAdvancesTheWatermark() {
        cursorBehindBy(66 * ONE_HOUR);
        long before = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        assertTrue(cursors.cursor.getLastEventMicros() > before,
                "watermark did not move past an empty, fully-served chunk");
        assertEquals(client.lastEnd, cursors.cursor.getLastEventMicros());
    }

    /**
     * ...but a chunk whose batch filled up has rows past the last one read, so the
     * watermark may only reach the newest ROW. Advancing to the chunk end there
     * would silently drop every alert after row 500.
     */
    @Test
    void aTruncatedChunkOnlyAdvancesToTheNewestRow() {
        cursorBehindBy(66 * ONE_HOUR);
        long rowTime = nowMicros() - 65 * ONE_HOUR;
        for (int i = 0; i < properties.getBatchSize(); i++) {
            client.rows.add(rowAt(rowTime + i));
        }

        service.pollOnce();

        long expected = rowTime + properties.getBatchSize() - 1;
        assertEquals(expected, cursors.cursor.getLastEventMicros());
        assertTrue(cursors.cursor.getLastEventMicros() < client.lastEnd);
    }

    /**
     * The other way the watermark stops moving, and the one that actually happened.
     *
     * <p>The window opens at {@code watermark - overlapSeconds} so a row written just
     * after the last poll is not missed. A burst dense enough to fill the batch inside
     * that overlap therefore returns a FULL page whose newest row is the watermark
     * itself: nothing to advance to, and the fully-served guard does not apply because
     * the page was truncated. Every one of those polls is a success, so the poller
     * reports healthy while re-reading the same 500 duplicates forever — which is how
     * this instance lost 32 hours of alerts behind a green banner.</p>
     */
    @Test
    void aFullBatchEntirelyAtTheWatermarkStillMakesProgress() {
        cursorBehindBy(66 * ONE_HOUR);
        long watermark = cursors.cursor.getLastEventMicros();
        // The case the overlap fix cannot reach: a whole batch sharing the watermark's
        // exact microsecond, so there is no later row to advance to and no earlier
        // window to stop reading.
        for (int i = 0; i < properties.getBatchSize(); i++) {
            client.rows.add(rowAt(watermark));
        }

        service.pollOnce();

        assertEquals(watermark + 1, cursors.cursor.getLastEventMicros(),
                "a full batch of rows at the watermark left it unmoved — the poller is wedged");
    }

    /**
     * And the step never lands past the newest row actually read.
     *
     * <p>One microsecond, not one chunk. A truncated batch means there may be more
     * rows further into the window, so the poller has to resume immediately after the
     * last row it saw — a bigger jump would step over exactly the rows the batch was
     * too small to reach, which is the failure the guard exists to avoid causing.</p>
     */
    @Test
    void theStallStepNeverLandsPastTheNewestRowRead() {
        cursorBehindBy(66 * ONE_HOUR);
        long watermark = cursors.cursor.getLastEventMicros();
        for (int i = 0; i < properties.getBatchSize(); i++) {
            client.rows.add(rowAt(watermark));
        }
        long newestRow = watermark + 5;
        client.rows.add(rowAt(newestRow));

        service.pollOnce();

        // The newest row read — NOT the end of the chunk. The batch was truncated, so
        // there may be more rows further into it, and jumping to the chunk end would
        // step over exactly the ones the batch was too small to reach.
        assertEquals(newestRow, cursors.cursor.getLastEventMicros(),
                "a poll that read rows past the watermark should stop at the newest of them");
    }

    /**
     * The stall as it actually behaved, across repeated polls.
     *
     * <p>Stepping one microsecond past the watermark is not enough on its own: the
     * next window is opened at {@code watermark - overlap} and reaches straight back
     * into the same burst, refills the same batch, and steps one more microsecond.
     * The watermark moves, so nothing looks frozen — and at 1µs per 30s it would take
     * some 57 years to cross the minute the burst occupies.</p>
     *
     * <p>Ten polls is far more than enough to cross a burst that spans 500µs if the
     * poller is making real progress, and nowhere near enough if it is creeping.</p>
     */
    @Test
    void repeatedPollsCrossADenseBurstInsteadOfCreeping() {
        cursorBehindBy(66 * ONE_HOUR);
        long watermark = cursors.cursor.getLastEventMicros();
        // A burst filling the batch, all of it inside the overlap window.
        for (int i = 0; i < properties.getBatchSize(); i++) {
            client.rows.add(rowAt(watermark - i));
        }

        for (int poll = 0; poll < 10; poll++) {
            service.pollOnce();
        }

        assertTrue(cursors.cursor.getLastEventMicros() > watermark + ONE_HOUR,
                "the poller is creeping through the burst rather than moving past it: watermark "
                        + "advanced by only "
                        + (cursors.cursor.getLastEventMicros() - watermark) + "µs");
    }

    /**
     * The overlap still applies where it earns its keep.
     *
     * <p>It is dropped only while catching up. In the live tail a row can still become
     * queryable after one with a later timestamp, and that is the case the overlap
     * exists for — removing it everywhere would trade a stall for silently missed
     * alerts, which is the worse of the two.</p>
     */
    @Test
    void theLiveTailStillReachesBackByTheOverlap() {
        cursorBehindBy(2 * ONE_HOUR);
        long watermark = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        long expected = watermark - (long) properties.getOverlapSeconds() * 1_000_000L;
        assertEquals(expected, client.lastStart,
                "the live tail must still re-read the overlap window");
    }

    /** The live tail must not jump to "now" on an empty poll — late rows land there. */
    @Test
    void anEmptyLiveTailPollDoesNotAdvanceTheWatermark() {
        cursorBehindBy(2 * ONE_HOUR);
        long before = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        assertEquals(before, cursors.cursor.getLastEventMicros());
    }

    // ── partial results are failures, not emptiness ──────────────────────────

    @Test
    void aPartialResultIsRecordedAsAFailureAndDoesNotAdvance() {
        cursorBehindBy(66 * ONE_HOUR);
        client.partial = true;
        long before = cursors.cursor.getLastEventMicros();

        List<PollResult> results = service.pollOnce();

        assertEquals(before, cursors.cursor.getLastEventMicros());
        assertEquals(1, cursors.failures);
        assertNotNull(results.get(0).error());
    }

    /**
     * The other half of the deadlock. Some ranges are permanently unreadable —
     * OpenObserve's file list naming parquet files that are gone — and fail
     * identically on every attempt. Retrying one of those forever is the original
     * bug with extra steps, so the chunk is stepped over once the budget is spent.
     */
    @Test
    void aPermanentlyPartialChunkIsSkippedOnceTheRetryBudgetIsSpent() {
        cursorBehindBy(66 * ONE_HOUR);
        client.partial = true;
        long before = cursors.cursor.getLastEventMicros();

        for (int attempt = 0; attempt < properties.getPartialRetriesBeforeSkip(); attempt++) {
            service.pollOnce();
        }

        assertTrue(cursors.cursor.getLastEventMicros() > before,
                "an unreadable chunk blocked ingest forever");
        assertEquals(client.lastEnd, cursors.cursor.getLastEventMicros());
    }

    /**
     * Skipping is a catch-up-only concession. In the live tail the window ends at
     * "now", and rows there are usually about to become readable — stepping over
     * them would discard alerts that are still arriving.
     */
    @Test
    void thePartialLiveTailIsNeverSkipped() {
        cursorBehindBy(2 * ONE_HOUR);
        client.partial = true;
        long before = cursors.cursor.getLastEventMicros();

        for (int attempt = 0; attempt < properties.getPartialRetriesBeforeSkip() + 3; attempt++) {
            service.pollOnce();
        }

        assertEquals(before, cursors.cursor.getLastEventMicros());
    }

    // ── surviving a change of OpenObserve address ────────────────────────────

    /**
     * The cursor describes a position in OpenObserve's timeline, not in ours.
     *
     * <p>So repointing {@code openobserve.host} at a different instance leaves a
     * watermark that means nothing there. Chunking alone would be correct and
     * unusable — a six-month-stale cursor needs ~730 polls to reach the present.
     * Asking the instance where its data starts collapses that to one.</p>
     */
    @Test
    void aWatermarkFromAnotherInstanceJumpsToWhereThisOnesDataBegins() {
        cursorBehindBy(180L * 24L * ONE_HOUR);          // ~6 months, as after a swap
        long streamStart = nowMicros() - 3 * ONE_HOUR;  // the new instance is young
        client.docTimeMin = streamStart;

        service.pollOnce();

        long watermark = cursors.cursor.getLastEventMicros();
        assertTrue(watermark >= streamStart - 2 * properties.getOverlapSeconds() * 1_000_000L,
                "watermark did not reach the stream start: " + watermark);
        // And having jumped, it is no longer behind — so this is the live tail.
        assertTrue(nowMicros() - watermark < 4 * ONE_HOUR);
    }

    /**
     * A genuine backlog must NOT be skipped. When the instance still holds the gap,
     * doc_time_min is older than the cursor and every chunk is read as normal.
     */
    @Test
    void arealBacklogIsStillReadRatherThanSkipped() {
        cursorBehindBy(66 * ONE_HOUR);
        client.docTimeMin = nowMicros() - 200 * ONE_HOUR;   // the data is all there
        long before = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        // Advanced by one chunk, not teleported to the present.
        long watermark = cursors.cursor.getLastEventMicros();
        assertTrue(watermark > before);
        assertTrue(watermark <= before + properties.maxWindowMicros() + ONE_HOUR,
                "a readable backlog was skipped instead of being read");
    }

    /**
     * The backlog bound is OFF unless a deployment asks for it.
     *
     * <p>The pairing with the test above is the point: the same 66h backlog is walked
     * by default and jumped once the bound is set. Skipping alerts is an opt-in, which
     * is why the Java default is 0 while application.properties ships 24.</p>
     */
    @Test
    void aBacklogPastTheBoundJumpsToThePresentOnlyWhenTheBoundIsSet() {
        properties.setMaxBacklogHours(24);
        cursorBehindBy(66 * ONE_HOUR);
        client.docTimeMin = nowMicros() - 200 * ONE_HOUR;   // the gap is readable, and skipped anyway

        service.pollOnce();

        // Landed in the live tail rather than one 6h chunk further along.
        long watermark = cursors.cursor.getLastEventMicros();
        assertTrue(nowMicros() - watermark < 2 * ONE_HOUR,
                "the bound should have moved the watermark to the present, but it is "
                        + ((nowMicros() - watermark) / ONE_HOUR) + "h behind");
    }

    /** Inside the bound, a backlog is still walked — the bound is a ceiling, not a mode. */
    @Test
    void aBacklogInsideTheBoundIsStillWalked() {
        properties.setMaxBacklogHours(24);
        cursorBehindBy(12 * ONE_HOUR);
        client.docTimeMin = nowMicros() - 200 * ONE_HOUR;
        long before = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        long watermark = cursors.cursor.getLastEventMicros();
        assertTrue(watermark > before);
        assertTrue(watermark <= before + properties.maxWindowMicros() + ONE_HOUR,
                "a 12h backlog is inside the 24h bound and should have been read, not skipped");
    }

    /** An empty or silent stream reports 0, which is not a reason to move. */
    @Test
    void anUnreportedStreamStartDoesNotMoveTheWatermark() {
        cursorBehindBy(66 * ONE_HOUR);
        client.docTimeMin = 0L;
        long before = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        // Normal chunked catch-up, no jump.
        assertTrue(cursors.cursor.getLastEventMicros() <= before + properties.maxWindowMicros() + 1);
    }

    /** Stream metadata being unavailable must not stop the poll. */
    @Test
    void aFailedStreamStartLookupFallsBackToChunkedCatchUp() {
        cursorBehindBy(66 * ONE_HOUR);
        client.failSchema = true;
        long before = cursors.cursor.getLastEventMicros();

        service.pollOnce();

        assertTrue(cursors.cursor.getLastEventMicros() > before,
                "the poll should still have advanced one chunk");
    }

    @Test
    void fastForwardCanBeDisabled() {
        properties.setFastForwardToStreamStart(false);
        cursorBehindBy(180L * 24L * ONE_HOUR);
        client.docTimeMin = nowMicros() - 3 * ONE_HOUR;

        service.pollOnce();

        // Still crawling in chunks, nowhere near the present.
        assertTrue(nowMicros() - cursors.cursor.getLastEventMicros() > 100L * 24L * ONE_HOUR);
    }

    /** The stream type reaches both the search and the metadata URL. */
    @Test
    void theConfiguredStreamTypeIsUsed() {
        properties.setStreamType("logs");
        cursorBehindBy(2 * ONE_HOUR);

        service.pollOnce();

        assertEquals("logs", client.lastStreamType);
    }

    /** Chunking off restores the old single-window behaviour, gap and all. */
    @Test
    void chunkingCanBeDisabled() {
        properties.setMaxWindowMinutes(0);
        cursorBehindBy(66 * ONE_HOUR);

        service.pollOnce();

        assertTrue(client.lastEnd - client.lastStart > 60 * ONE_HOUR);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    private static Map<String, Object> rowAt(long micros) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_timestamp", micros);
        row.put("alertname", "K8s Pod High Memory Usage");
        row.put("severity", "Warning");
        return row;
    }

    /** Serves canned rows and remembers the window it was asked for. */
    private static final class FakeClient implements OpenObserveClientService {

        private final List<Map<String, Object>> rows = new ArrayList<>();
        private boolean partial;
        private Long lastStart;
        private long lastEnd;
        private String lastStreamType;
        /** What the stream reports as its first row; 0 = not reported. */
        private long docTimeMin;
        private boolean failSchema;

        @Override
        public JsonNode getJson(String path) {
            if (failSchema) {
                throw new IllegalStateException("simulated stream-metadata failure");
            }
            if (!path.contains("/schema")) {
                throw new UnsupportedOperationException(path);
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.putObject("stats").put("doc_time_min", docTimeMin);
            return root;
        }

        @Override
        public SearchOutcome searchDetailed(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            lastStart = startMicros;
            lastEnd = endMicros;
            lastStreamType = streamType;
            if (partial) {
                // As OpenObserve actually answers an unservable window: 200, no
                // rows, and the flag that says so.
                return new SearchOutcome(List.of(), true,
                        "{\"code\":20006,\"message\":\"Search parquet file not found\"}");
            }
            List<Map<String, Object>> inWindow = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                long at = ((Number) row.get("_timestamp")).longValue();
                if (at >= startMicros && at <= endMicros) {
                    inWindow.add(row);
                }
            }
            return new SearchOutcome(inWindow, false, null);
        }

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            return searchDetailed(sql, startMicros, endMicros, from, size, streamType).hits();
        }

        @Override
        public JsonNode sendJson(String method, String path, JsonNode body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode deleteJson(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode ingest(String org, String streamName, JsonNode records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> discoverServices() {
            return List.of();
        }

        @Override
        public List<String> listMetricStreams() {
            return List.of();
        }
    }

    /** One in-memory cursor, with the entity's real forward-only semantics. */
    private static final class FakeCursors extends AlertIngestCursorStore {

        private final AlertIngestCursorEntity cursor =
                AlertIngestCursorEntity.startingAt(ORG, STREAM, 0L);
        private int failures;

        FakeCursors() {
            super(null);
            cursor.setId(1L);
        }

        @Override
        public AlertIngestCursorEntity loadOrCreate(String org, String stream, long backfillMicros) {
            return cursor;
        }

        @Override
        public void recordSuccess(Long cursorId, long newestMicros, int stored) {
            failures = 0;
            cursor.recordSuccess(newestMicros, stored);
        }

        @Override
        public int recordFailure(Long cursorId, String error) {
            cursor.recordFailure(error);
            return ++failures;
        }

        @Override
        public List<AlertIngestCursorEntity> findAll() {
            return List.of(cursor);
        }
    }

    /** Storing is another class's job; these tests are about the window. */
    private static final class NoopProcessing implements AlertProcessingService {
        @Override
        public boolean store(AlertEvent event, String source) {
            return true;
        }
    }
}
