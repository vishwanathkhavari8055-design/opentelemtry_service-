package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.InfrastructureProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.infrastructure.InfrastructureResponseDTO;
import com.trinity.observability.util.OrgContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind the four Resources tiles.
 *
 * <p>The OpenObserve client is faked with a table of {@code stream → rows}, keyed
 * off the first stream each statement names. Two shapes are being tested and they
 * are not variations of one thing:</p>
 *
 * <ul>
 *   <li>CPU and Memory are POD-scoped and read by running one configured statement
 *       and taking one {@code pct} / {@code detail} row from it. What matters here
 *       is that the row is carried through untouched, that a null percentage is
 *       never rendered as 0%, and that freshness travels with it.</li>
 *   <li>Disk and NFS Storage are CLUSTER-scoped and assembled from grouped rows.
 *       Every behaviour tested there was a way to display a confidently wrong
 *       number — a Disk total dominated by {@code tmpfs}, an NFS capacity
 *       multiplied by the number of pods that mounted it, a cluster average whose
 *       membership changed between polls.</li>
 * </ul>
 */
class InfrastructureMetricsServiceImplTest {

    private static final long MICROS_PER_MINUTE = 60L * 1_000_000L;

    /** First stream named by each shipped statement — how FakeClient routes them. */
    private static final String CPU_STREAM = "k8s_pod_cpu_usage";
    private static final String MEMORY_STREAM = "k8s_pod_memory_limit_utilization";
    private static final String DISK_STREAM = "system_filesystem_usage";

    @AfterEach
    void clearOrg() {
        OrgContext.clear();
    }

    // ── CPU and Memory: one statement, one row ───────────────────────────────

    @Test
    @DisplayName("CPU reports the statement's own percentage and caption, unaltered")
    void cpuIsWhatTheStatementSaid() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(31.8, "1.59 / 5 cores")));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        assertTrue(cpu.isAvailable());
        assertEquals(31.8, cpu.getUsedPercent(), 0.001);
        // Not reformatted here. The caption is the numerator and denominator the
        // percentage came from, and re-deriving it from separate queries is how a
        // caption ends up disagreeing with the number above it.
        assertEquals("1.59 / 5 cores", cpu.getDetail());
        assertEquals("PERCENT", cpu.getUnit());
        assertNull(cpu.getTotalBytes(), "declaring BYTES with no sizes renders 'used 0 of 0'");
    }

    @Test
    @DisplayName("Memory reports the statement's own percentage and caption, unaltered")
    void memoryIsWhatTheStatementSaid() {
        FakeClient client = new FakeClient();
        client.rows(MEMORY_STREAM, List.of(scalarRow(65.3, "6.05 / 15 GB")));

        InfrastructureResponseDTO.Item memory = tile(service(client), "MEMORY");

        assertTrue(memory.isAvailable());
        assertEquals(65.3, memory.getUsedPercent(), 0.001);
        assertEquals("6.05 / 15 GB", memory.getDetail());
    }

    @Test
    @DisplayName("a null percentage is no reading at all, never a reading of 0%")
    void aNullPercentageIsNotZero() {
        FakeClient client = new FakeClient();
        // What a cross join against an empty aggregate returns: a row exists, but
        // the number does not. Rendering it as 0% would announce an idle cluster.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pct", null);
        row.put("detail", "— / 5 cores");
        client.rows(CPU_STREAM, List.of(row));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        assertFalse(cpu.isAvailable());
        assertNull(cpu.getUsedPercent());
        assertNotNull(cpu.getMessage(), "an unavailable tile has to say why");
    }

    @Test
    @DisplayName("a caption of pure punctuation is dropped, not published as the headline")
    void aDetailWithNoNumbersIsNotACaption() {
        FakeClient client = new FakeClient();
        // Observed on the target deployment: with the limits stream unwritten inside
        // the window, CONCAT of NULLs did not eliminate the row - it returned
        // " /  cores", which the card printed as its headline and which reads as a
        // broken page rather than as "not known".
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pct", 29.7);
        row.put("detail", " /  cores");
        row.put("last_seen", nowMicros());
        client.rows(CPU_STREAM, List.of(row));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        assertTrue(cpu.isAvailable(), "the percentage is still a real reading");
        assertEquals(29.7, cpu.getUsedPercent(), 0.001);
        assertNull(cpu.getDetail(), "an em dash beats ' /  cores'");
    }

    @Test
    @DisplayName("a pod burst past its limit is reported, not clamped to 100%")
    void overLimitIsNotHidden() {
        FakeClient client = new FakeClient();
        // Throttling can push limit utilization past 1.0. That is the one reading
        // worth acting on, so clamping it away would hide the alarm.
        client.rows(CPU_STREAM, List.of(scalarRow(118.4, "5.92 / 5 cores")));

        assertEquals(118.4, tile(service(client), "CPU").getUsedPercent(), 0.001);
    }

    @Test
    @DisplayName("the pod tiles contribute no node rows, because a pod is not a node")
    void podTilesAreNotAttributedToNodes() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(20.0, "1.0 / 5 cores")));
        client.rows(MEMORY_STREAM, List.of(scalarRow(40.0, "6.0 / 15 GB")));

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertTrue(item(out, "CPU").isAvailable());
        assertTrue(item(out, "MEMORY").isAvailable());
        assertTrue(out.getNodes().isEmpty(),
                "no disk rows were scripted, so nothing should claim a node breakdown");
    }

    @Test
    @DisplayName("each tile says what it measured, so pod and cluster figures are not read as peers")
    void everyTileCarriesItsScope() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(20.0, "1.0 / 5 cores")));

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertEquals("tiotopenobserve pods", item(out, "CPU").getScope());
        assertEquals("tiotopenobserve pods", item(out, "MEMORY").getScope(),
                "stated even when the tile has no reading — the heading still has to be true");
        assertNotNull(item(out, "DISK").getScope());
        assertNotNull(item(out, "NFS_STORAGE").getScope());
    }

    @Test
    @DisplayName("the statement is sent verbatim, so operators can repoint it without a rebuild")
    void theConfiguredStatementIsWhatRuns() {
        FakeClient client = new FakeClient();
        InfrastructureProperties properties = properties();
        properties.getCpu().setSql("SELECT 1 AS pct FROM \"my_own_stream\"");
        client.rows("my_own_stream", List.of(scalarRow(7.0, "custom")));

        InfrastructureResponseDTO.Item cpu =
                item(service(client, properties).utilization(null, true), "CPU");

        assertEquals(7.0, cpu.getUsedPercent(), 0.001);
        assertTrue(client.sql.stream().anyMatch("SELECT 1 AS pct FROM \"my_own_stream\""::equals),
                "the property should reach OpenObserve untouched; " + client.sql);
    }

    @Test
    @DisplayName("the shipped statements select the tiotopenobserve pods against their limits")
    void theShippedStatementsMeasureWhatTheCardsClaim() {
        InfrastructureProperties properties = properties();

        String cpu = properties.getCpu().getSql();
        assertTrue(cpu.contains(CPU_STREAM), cpu);
        assertTrue(cpu.contains("kube_pod_container_resource_limits"), "the limit it divides by");
        assertTrue(cpu.contains("LIKE 'tiotopenobserve%'"), cpu);

        String memory = properties.getMemory().getSql();
        assertTrue(memory.contains(MEMORY_STREAM), memory);
        assertTrue(memory.contains("LIKE 'tiotopenobserve%'"), memory);

        for (String sql : List.of(cpu, memory)) {
            // The newest sample, not a window mean. A mean over a window wide enough
            // to survive a missed scrape does not move between polls, which is the
            // whole of "the screen is not updating".
            assertTrue(sql.contains("LAST_VALUE(value ORDER BY _timestamp)"),
                    "a window mean would look frozen; " + sql);
            // ...of the newest POD. tiotopenobserve is replaced many times a day, so
            // a wide window contains dead pods whose readings must not be mixed in.
            assertTrue(sql.contains("GROUP BY k8s_pod_name"), sql);
            assertTrue(sql.contains("ORDER BY MAX(_timestamp) DESC LIMIT 1"),
                    "must pin to the live pod; " + sql);
            // The window is applied by the search API from start_time/end_time. A
            // predicate here would pin the statement to one rung of the ladder.
            assertFalse(sql.contains("_timestamp >="), "the ladder sets the window, not the SQL");
        }
    }

    // ── Org scoping ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a tenant-scoped request still reads the org the cluster metrics live in")
    void theTilesDoNotFollowTheRequestingOrg() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(30.0, "1.5 / 5 cores")));
        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 50_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 50_000_000_000d)));

        // What OrgContextFilter sets from ?org= — here the DLH tenant. Measured on
        // the target deployment: NONE of the seven source streams exist outside
        // `default`, so honouring this blanked all four cards on three of four
        // tenants while every collector was running fine.
        OrgContext.set("3H5HQAR6OLxkWivdKZE0hg3YBF4");

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertEquals("default", out.getOrg(), "the response reports the org actually queried");
        assertTrue(client.orgs.stream().allMatch("default"::equals),
                "every tile query must run in the metrics org, not the caller's; " + client.orgs);
        assertTrue(item(out, "CPU").isAvailable());
        assertTrue(item(out, "DISK").isAvailable());
    }

    @Test
    @DisplayName("a deployment that ingests cluster metrics elsewhere can say so")
    void theMetricsOrgIsConfigurable() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(30.0, "1.5 / 5 cores")));

        InfrastructureProperties properties = properties();
        properties.setMetricsOrg("platform");
        OrgContext.set("some-tenant");

        InfrastructureResponseDTO.Utilization out =
                service(client, properties).utilization(null, true);

        assertEquals("platform", out.getOrg());
        assertTrue(client.orgs.stream().allMatch("platform"::equals), client.orgs.toString());
    }

    @Test
    @DisplayName("two tenants viewing the screen share one cached answer")
    void theCacheIsNotSplitPerTenant() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(30.0, "1.5 / 5 cores")));
        InfrastructureMetricsServiceImpl service = service(client);

        OrgContext.set("tenant-a");
        service.utilization(null, false);
        int afterFirst = client.queries.size();

        // Same numbers, different viewer. Keying the cache by the caller's org
        // would hold one copy of identical data per tenant and query for each.
        OrgContext.set("tenant-b");
        assertTrue(service.utilization(null, false).isCached());
        assertEquals(afterFirst, client.queries.size(), "the second tenant cost no queries");
    }

    // ── Freshness ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the tile reports how old its newest sample is, not just that it has one")
    void freshnessTravelsWithTheNumber() {
        FakeClient client = new FakeClient();
        long fiveMinutesAgo = nowMicros() - 5 * MICROS_PER_MINUTE;
        client.rows(CPU_STREAM, List.of(scalarRow(50.0, "2.5 / 5 cores", fiveMinutesAgo)));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        assertNotNull(cpu.getLastSeen());
        assertTrue(cpu.getLastSeenAgoSec() >= 295 && cpu.getLastSeenAgoSec() <= 310,
                "expected roughly 300s, got " + cpu.getLastSeenAgoSec());
    }

    @Test
    @DisplayName("an old pod reading is shown with its age rather than blanked")
    void thePodTilesDoNotApplyTheStalenessCutoff() {
        FakeClient client = new FakeClient();
        // Well past infrastructure.stale-after-seconds. That cutoff exists to keep a
        // decommissioned node out of a cluster AVERAGE; there is no average here to
        // poison, and blanking the card would leave the screen unable to say "the
        // pods went quiet 90 minutes ago" rather than "these streams do not exist".
        long ninetyMinutesAgo = nowMicros() - 90 * MICROS_PER_MINUTE;
        client.rows(CPU_STREAM, List.of(scalarRow(44.0, "2.2 / 5 cores", ninetyMinutesAgo)));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        assertTrue(cpu.isAvailable(), "an old number with its age beats no number");
        assertEquals(44.0, cpu.getUsedPercent(), 0.001);
        assertTrue(cpu.getLastSeenAgoSec() > 5_000, "and it has to say how old it is");
    }

    // ── Disk ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pseudo filesystems are excluded, so Disk is about the disk that can fill up")
    void diskExcludesPseudoFilesystems() {
        FakeClient client = new FakeClient();
        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/dm-0", "/", "ext4", "used", 70_000_000_000d),
                diskRow("nodeA", "/dev/dm-0", "/", "ext4", "free", 30_000_000_000d),
                // A node carries dozens of these. Counted, they would dominate the
                // total and make the tile a statement about container images.
                diskRow("nodeA", "tmpfs", "/run", "tmpfs", "used", 1_000_000_000d),
                diskRow("nodeA", "tmpfs", "/run", "tmpfs", "free", 9_000_000_000d),
                diskRow("nodeA", "overlay", "/var/lib/x", "overlay", "used", 5_000_000_000d)));

        InfrastructureResponseDTO.Item disk = tile(service(client), "DISK");

        assertEquals(100_000_000_000L, disk.getTotalBytes());
        assertEquals(70_000_000_000L, disk.getUsedBytes());
        assertEquals(70.0, disk.getUsedPercent(), 0.01);
    }

    @Test
    @DisplayName("a device bind-mounted twice is one disk, not two")
    void diskCountsEachDeviceOnce() {
        FakeClient client = new FakeClient();
        // The same device reported under two mountpoints. Summing the mounts
        // doubles the machine's disk.
        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 40_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 60_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/mnt/alias", "ext4", "used", 40_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/mnt/alias", "ext4", "free", 60_000_000_000d)));

        InfrastructureResponseDTO.Item disk = tile(service(client), "DISK");

        assertEquals(100_000_000_000L, disk.getTotalBytes(), "one device, one disk");
        assertEquals(40.0, disk.getUsedPercent(), 0.01);
    }

    @Test
    @DisplayName("the per-node rows behind the Disk figure travel with it")
    void diskOpensUpPerNode() {
        FakeClient client = new FakeClient();
        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 90_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 10_000_000_000d),
                diskRow("nodeB", "/dev/sda1", "/", "ext4", "used", 10_000_000_000d),
                diskRow("nodeB", "/dev/sda1", "/", "ext4", "free", 90_000_000_000d)));

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertEquals(50.0, item(out, "DISK").getUsedPercent(), 0.01);
        // The averaged figure hides the case that matters: one node nearly full.
        assertEquals(2, out.getNodes().size());
        assertEquals(90.0, node(out, "nodeA").getDiskPercent(), 0.01);
        assertEquals(10.0, node(out, "nodeB").getDiskPercent(), 0.01);
    }

    // ── NFS storage ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("claims sharing one export report one capacity, not one per claim")
    void nfsFoldsEqualCapacityClaims() {
        FakeClient client = new FakeClient();
        // Three pods mounting the same NFS export. The kubelet reports the whole
        // export's size against each of them; summing announces 300 GB of storage
        // that does not exist.
        client.rows("k8s_volume_capacity", List.of(
                volRow("default", "podA", "shared", 100_000_000_000d),
                volRow("default", "podB", "shared", 100_000_000_000d),
                volRow("default", "podC", "shared", 100_000_000_000d)));
        client.rows("k8s_volume_available", List.of(
                volRow("default", "podA", "shared", 40_000_000_000d),
                volRow("default", "podB", "shared", 40_000_000_000d),
                volRow("default", "podC", "shared", 40_000_000_000d)));

        InfrastructureResponseDTO.Item nfs = tile(service(client), "NFS_STORAGE");

        assertTrue(nfs.isAvailable());
        assertEquals(100_000_000_000L, nfs.getTotalBytes());
        assertEquals(40_000_000_000L, nfs.getFreeBytes());
        assertEquals(60_000_000_000L, nfs.getUsedBytes());
        assertEquals(60.0, nfs.getUsedPercent(), 0.01);
        assertEquals("3 claims on 1 backing store", nfs.getDetail());
    }

    @Test
    @DisplayName("dedupe off keeps every claim as its own volume")
    void nfsWithoutDedupeSumsClaims() {
        FakeClient client = new FakeClient();
        client.rows("k8s_volume_capacity", List.of(
                volRow("default", "podA", "one", 10_000_000_000d),
                volRow("default", "podB", "two", 10_000_000_000d)));
        client.rows("k8s_volume_available", List.of(
                volRow("default", "podA", "one", 4_000_000_000d),
                volRow("default", "podB", "two", 4_000_000_000d)));

        InfrastructureProperties properties = properties();
        properties.getNfs().setDedupeByCapacity(false);

        InfrastructureResponseDTO.Item nfs =
                item(service(client, properties).utilization(null, true), "NFS_STORAGE");

        assertEquals(20_000_000_000L, nfs.getTotalBytes());
        assertEquals(8_000_000_000L, nfs.getFreeBytes());
    }

    @Test
    @DisplayName("capacity without availability reports a size, never 0% used")
    void nfsWithoutAvailabilityDoesNotClaimZeroUsed() {
        FakeClient client = new FakeClient();
        client.rows("k8s_volume_capacity", List.of(
                volRow("default", "podA", "shared", 100_000_000_000d)));
        // k8s_volume_available absent — the scraper writes capacity only.

        InfrastructureResponseDTO.Item nfs = tile(service(client), "NFS_STORAGE");

        assertTrue(nfs.isAvailable());
        assertEquals(100_000_000_000L, nfs.getTotalBytes());
        assertNull(nfs.getUsedPercent(), "0% used would be an alarming thing to invent");
        assertNotNull(nfs.getMessage());
    }

    // ── Resilience ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("one missing stream costs one tile, never the other three")
    void aMissingStreamDoesNotBlankTheScreen() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(25.0, "1.25 / 5 cores")));
        client.fail(MEMORY_STREAM);

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertEquals(4, out.getItems().size(), "every tile is always present");
        assertTrue(item(out, "CPU").isAvailable());
        assertEquals(25.0, item(out, "CPU").getUsedPercent(), 0.01);

        InfrastructureResponseDTO.Item memory = item(out, "MEMORY");
        assertFalse(memory.isAvailable());
        assertNotNull(memory.getMessage(), "an unavailable tile has to say why");
        assertTrue(memory.getMessage().contains("infrastructure.memory.sql"),
                "and point at the property to fix; got: " + memory.getMessage());
    }

    @Test
    @DisplayName("an empty narrow window widens rather than reporting the tile dead")
    void theWindowLadderWidens() {
        FakeClient client = new FakeClient();
        // Nothing inside the first rung; the volume scraper is slower than that.
        // This is the measured shape of k8s_volume_* on the target deployment. The
        // threshold sits BETWEEN two rungs (nfs uses now-30m, now-2h) rather than on
        // one: FakeClient truncates the window to whole minutes, so a threshold
        // equal to a rung would flip on sub-minute rounding.
        client.minimumWindowMinutes("k8s_volume_capacity", 45);
        client.rows("k8s_volume_capacity", List.of(
                volRow("default", "podA", "shared", 100_000_000_000d)));

        InfrastructureResponseDTO.Item nfs = tile(service(client), "NFS_STORAGE");

        assertTrue(nfs.isAvailable());
        assertEquals("now-2h", nfs.getWindow(), "reports which rung actually answered");
    }

    @Test
    @DisplayName("a pod statement that answers nothing narrow also widens")
    void theScalarLadderWidensToo() {
        FakeClient client = new FakeClient();
        // Between the now-2h and now-6h rungs, not on either — see the note in
        // theWindowLadderWidens.
        client.minimumWindowMinutes(CPU_STREAM, 200);
        client.rows(CPU_STREAM, List.of(scalarRow(12.0, "0.6 / 5 cores")));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        assertTrue(cpu.isAvailable());
        assertEquals("now-6h", cpu.getWindow());
    }

    @Test
    @DisplayName("the pod tiles get a wide ladder, because their answer cannot depend on it")
    void thePodTilesUseTheirOwnWideLadder() {
        InfrastructureProperties properties = properties();

        // Not infrastructure.windows, whose narrow rungs exist to keep a window MEAN
        // current. These read the newest sample, so a narrow rung cannot produce a
        // fresher number - it can only fail to find one.
        assertEquals(List.of("now-2h", "now-6h", "now-24h"), properties.getCpu().getWindows());
        assertEquals(List.of("now-2h", "now-6h", "now-24h"), properties.getMemory().getWindows());

        // Clearing the property must not silently hand these the narrow shared ladder.
        properties.getCpu().setWindows(List.of());
        assertEquals(List.of("now-2h", "now-6h", "now-24h"), properties.getCpu().getWindows());
    }

    @Test
    @DisplayName("the pod statement stops at the first rung that answers, not the widest")
    void theScalarLadderDoesNotKeepWidening() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(12.0, "0.6 / 5 cores")));

        service(client).utilization(null, true);

        // One row is one row however wide the window is, so a wider rung would only
        // average in older samples. Nothing to gain, and a query to pay for.
        assertEquals(1, client.queries.stream().filter(CPU_STREAM::equals).count(),
                "the narrowest rung answered; " + client.queries);
    }

    @Test
    @DisplayName("the grouped value is the newest sample per series, which is what makes a tile move")
    void theGroupedValueIsTheLatestSampleNotTheWindowMean() {
        FakeClient client = new FakeClient();
        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 50_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 50_000_000_000d)));

        service(client).utilization(null, true);

        assertTrue(client.sql.stream().anyMatch(s -> s.contains("LAST_VALUE(value ORDER BY _timestamp)")),
                "a window mean over a coverage-sized window does not budge; " + client.sql);
    }

    @Test
    @DisplayName("an OpenObserve without LAST_VALUE falls back to the mean rather than going blank")
    void anUnsupportedAggregateFallsBackInsteadOfFailing() {
        FakeClient client = new FakeClient();
        client.rejectLatest = true;
        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 30_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 70_000_000_000d)));

        InfrastructureMetricsServiceImpl service = service(client);
        InfrastructureResponseDTO.Item disk = item(service.utilization(null, true), "DISK");

        assertTrue(disk.isAvailable(), "a working tile beats a responsive one");
        assertEquals(30.0, disk.getUsedPercent(), 0.01);

        // Sticky FOR THE GROUPED TILES: the answer is a property of the server
        // build, so a second call must not pay to rediscover it. Scoped to the
        // generated disk SQL, because the pod statements are opaque operator
        // properties and carry their own fallback (below) rather than being
        // rewritten.
        client.sql.clear();
        service.utilization(null, true);
        assertTrue(client.sql.stream()
                        .filter(q -> q.contains(DISK_STREAM))
                        .noneMatch(q -> q.contains("LAST_VALUE")),
                "the unsupported form should not be retried on every request");
    }

    @Test
    @DisplayName("a build without LAST_VALUE gets the mean from the fallback, not a blank card")
    void thePodTilesFallBackWhenLastValueIsUnsupported() {
        FakeClient client = new FakeClient();
        client.rejectLatest = true;
        client.rows(CPU_STREAM, List.of(scalarRow(30.0, "1.5 / 5 cores")));

        InfrastructureResponseDTO.Item cpu = tile(service(client), "CPU");

        // The primary statement is opaque to the service, so there is nothing to
        // rewrite — without a whole fallback statement this tile would simply die on
        // an OpenObserve that will not plan LAST_VALUE, which is a portability
        // regression against the window-mean version it replaced.
        assertTrue(cpu.isAvailable(), "a slower reading beats no reading");
        assertEquals(30.0, cpu.getUsedPercent(), 0.001);
        assertTrue(client.sql.stream().anyMatch(q -> q.contains("AVG(value)") && !q.contains("LAST_VALUE")),
                "the fallback should be a window mean; " + client.sql);
    }

    @Test
    @DisplayName("the fallback can be switched off, leaving the tile on one statement")
    void theFallbackIsOptional() {
        FakeClient client = new FakeClient();
        client.rejectLatest = true;
        client.rows(CPU_STREAM, List.of(scalarRow(30.0, "1.5 / 5 cores")));

        InfrastructureProperties properties = properties();
        properties.getCpu().setFallbackSql("");

        InfrastructureResponseDTO.Item cpu =
                item(service(client, properties).utilization(null, true), "CPU");

        assertFalse(cpu.isAvailable());
        assertNotNull(cpu.getMessage());
    }

    @Test
    @DisplayName("widening continues while it reveals more nodes, so the denominator is stable")
    void theLadderWidensForCoverageNotJustForAnyRow() {
        FakeClient client = new FakeClient();
        // The measured shape of system_filesystem_usage on the target deployment:
        // inside five minutes only one node has reported; fifteen minutes has all
        // three. Stopping at the first rung with ANY rows averages one node and
        // then three on the next poll, which reads as a 10-point swing in disk
        // usage that never happened.
        client.rowsAtWindow(DISK_STREAM, 5, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 90_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 10_000_000_000d)));
        client.rowsAtWindow(DISK_STREAM, 15, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 90_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 10_000_000_000d),
                diskRow("nodeB", "/dev/sda1", "/", "ext4", "used", 10_000_000_000d),
                diskRow("nodeB", "/dev/sda1", "/", "ext4", "free", 90_000_000_000d),
                diskRow("nodeC", "/dev/sda1", "/", "ext4", "used", 50_000_000_000d),
                diskRow("nodeC", "/dev/sda1", "/", "ext4", "free", 50_000_000_000d)));

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);
        InfrastructureResponseDTO.Item disk = item(out, "DISK");

        assertEquals("now-15m", disk.getWindow(), "should widen until the cluster is covered");
        assertEquals(3, out.getNodes().size());
        assertEquals(300_000_000_000L, disk.getTotalBytes());
        assertEquals(50.0, disk.getUsedPercent(), 0.01, "(90 + 10 + 50) of 300");
    }

    @Test
    @DisplayName("a node that stopped reporting is excluded, not averaged in at its last value")
    void churnedNodesAreDroppedByTheStalenessCutoff() {
        FakeClient client = new FakeClient();
        // nodeA is live and half full. nodeGone last reported 90 minutes ago —
        // renamed or removed. Averaging its final 100% into the cluster figure is
        // not a stale number, it is a number about a machine that no longer exists.
        Map<String, Object> goneUsed =
                diskRow("nodeGone", "/dev/sda1", "/", "ext4", "used", 100_000_000_000d);
        goneUsed.put("last_seen", nowMicros() - 90 * MICROS_PER_MINUTE);

        client.rows(DISK_STREAM, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 50_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 50_000_000_000d),
                goneUsed));

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertEquals(50.0, item(out, "DISK").getUsedPercent(), 0.01,
                "nodeA alone, not 150 of 200");
        assertEquals(1, out.getNodes().size());
    }

    @Test
    @DisplayName("the cutoff bounds the coverage climb instead of letting it reach every old node")
    void theStalenessCutoffTerminatesTheClimb() {
        FakeClient client = new FakeClient();
        // Widening keeps revealing rows, but everything past 30m is churn. Without
        // the cutoff the climb would run to the last rung and average 3 nodes.
        client.rowsAtWindow(DISK_STREAM, 15, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 25_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 75_000_000_000d)));

        Map<String, Object> old1 =
                diskRow("nodeOld1", "/dev/sda1", "/", "ext4", "used", 100_000_000_000d);
        old1.put("last_seen", nowMicros() - 45 * MICROS_PER_MINUTE);
        Map<String, Object> old2 =
                diskRow("nodeOld2", "/dev/sda1", "/", "ext4", "used", 100_000_000_000d);
        old2.put("last_seen", nowMicros() - 55 * MICROS_PER_MINUTE);

        client.rowsAtWindow(DISK_STREAM, 30, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 25_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 75_000_000_000d),
                old1, old2));

        InfrastructureResponseDTO.Utilization out = service(client).utilization(null, true);

        assertEquals(25.0, item(out, "DISK").getUsedPercent(), 0.01);
        assertEquals(1, out.getNodes().size(), "only the node still reporting");
        assertEquals("now-15m", item(out, "DISK").getWindow(),
                "the wider rung added no live coverage, so it did not win");
    }

    @Test
    @DisplayName("widening stops at the first rung that adds nothing")
    void theLadderStopsWhenCoverageStopsGrowing() {
        FakeClient client = new FakeClient();
        client.rowsAtWindow(DISK_STREAM, 5, List.of(
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "used", 50_000_000_000d),
                diskRow("nodeA", "/dev/sda1", "/", "ext4", "free", 50_000_000_000d)));

        service(client).utilization(null, true);

        long diskQueries = client.queries.stream().filter(DISK_STREAM::equals).count();
        assertEquals(2, diskQueries,
                "one query to read, one to confirm widening adds nothing — not the whole ladder");
    }

    // ── Caching ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a second poll inside the TTL is served from cache; refresh=true is not")
    void theCacheAbsorbsPollingWithoutHidingRefresh() {
        FakeClient client = new FakeClient();
        client.rows(CPU_STREAM, List.of(scalarRow(50.0, "2.5 / 5 cores")));

        InfrastructureMetricsServiceImpl service = service(client);

        service.utilization(null, false);
        int afterFirst = client.queries.size();
        assertTrue(afterFirst > 0);

        InfrastructureResponseDTO.Utilization second = service.utilization(null, false);
        assertEquals(afterFirst, client.queries.size(), "the second poll cost no queries");
        assertTrue(second.isCached());

        InfrastructureResponseDTO.Utilization forced = service.utilization(null, true);
        assertTrue(client.queries.size() > afterFirst, "refresh=true has to reach OpenObserve");
        assertFalse(forced.isCached());
    }

    @Test
    @DisplayName("with OpenObserve disabled every tile says so instead of reading zero")
    void disabledBackendIsStatedNotFaked() {
        FakeClient client = new FakeClient();
        InfrastructureMetricsServiceImpl service = new InfrastructureMetricsServiceImpl(
                client, properties(), openObserveConfig());
        ReflectionTestUtils.setField(service, "openObserveEnabled", false);

        InfrastructureResponseDTO.Utilization out = service.utilization(null, true);

        assertFalse(out.isSupported());
        assertEquals(4, out.getItems().size());
        for (InfrastructureResponseDTO.Item item : out.getItems()) {
            assertFalse(item.isAvailable());
            assertNull(item.getUsedPercent(), "0% would be a claim about a cluster we cannot see");
            assertNotNull(item.getMessage());
        }
        assertTrue(client.queries.isEmpty(), "nothing should be queried at all");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static InfrastructureMetricsServiceImpl service(FakeClient client) {
        return service(client, properties());
    }

    private static InfrastructureMetricsServiceImpl service(FakeClient client,
            InfrastructureProperties properties) {
        InfrastructureMetricsServiceImpl service = new InfrastructureMetricsServiceImpl(
                client, properties, openObserveConfig());
        ReflectionTestUtils.setField(service, "openObserveEnabled", true);
        return service;
    }

    private static InfrastructureProperties properties() {
        return new InfrastructureProperties();
    }

    private static OpenObserveConfig openObserveConfig() {
        OpenObserveConfig config = new OpenObserveConfig();
        ReflectionTestUtils.setField(config, "org", "default");
        return config;
    }

    private static InfrastructureResponseDTO.Item tile(InfrastructureMetricsServiceImpl service,
            String kind) {
        return item(service.utilization(null, true), kind);
    }

    private static InfrastructureResponseDTO.Item item(InfrastructureResponseDTO.Utilization out,
            String kind) {
        for (InfrastructureResponseDTO.Item item : out.getItems()) {
            if (kind.equals(item.getKind())) {
                return item;
            }
        }
        throw new AssertionError("no " + kind + " tile in the response");
    }

    private static InfrastructureResponseDTO.Node node(InfrastructureResponseDTO.Utilization out,
            String name) {
        for (InfrastructureResponseDTO.Node node : out.getNodes()) {
            if (name.equals(node.getNode())) {
                return node;
            }
        }
        throw new AssertionError("no node '" + name + "' in the response");
    }

    /** The single row a CPU or Memory statement returns. */
    private static Map<String, Object> scalarRow(double pct, String detail) {
        return scalarRow(pct, detail, nowMicros());
    }

    private static Map<String, Object> scalarRow(double pct, String detail, long lastSeenMicros) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pct", pct);
        row.put("detail", detail);
        row.put("last_seen", lastSeenMicros);
        return row;
    }

    private static Map<String, Object> diskRow(String node, String device, String mount,
            String type, String state, double value) {
        Map<String, Object> row = baseRow();
        row.put("node_name", node);
        row.put("device_name", device);
        row.put("mount_point", mount);
        row.put("fs_type", type);
        row.put("state_name", state);
        row.put("agg_value", value);
        return row;
    }

    private static Map<String, Object> volRow(String namespace, String pod, String volume,
            double value) {
        Map<String, Object> row = baseRow();
        row.put("ns_name", namespace);
        row.put("pod_name", pod);
        row.put("vol_name", volume);
        row.put("agg_value", value);
        return row;
    }

    private static Map<String, Object> baseRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("last_seen", nowMicros());
        return row;
    }

    private static long nowMicros() {
        java.time.Instant now = java.time.Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    /**
     * Answers by stream name — the FIRST stream the statement names, which for the
     * pod statements is the utilization stream inside the leading subquery.
     *
     * <p>A stream with no scripted rows returns empty, which is exactly how
     * OpenObserve reports a stream this deployment does not have — the tiles have
     * to cope with that rather than with an exception.</p>
     */
    private static final class FakeClient implements OpenObserveClientService {

        private final Map<String, List<Map<String, Object>>> byStream = new LinkedHashMap<>();
        private final List<String> failing = new ArrayList<>();
        private final Map<String, Integer> minWindowMinutes = new LinkedHashMap<>();

        /** stream → (window minutes from which it applies) → rows. */
        private final Map<String, java.util.TreeMap<Integer, List<Map<String, Object>>>> byWindow =
                new LinkedHashMap<>();

        private final List<String> queries = new CopyOnWriteArrayList<>();

        /** Every SQL string issued, for asserting on the shape of the query. */
        private final List<String> sql = new CopyOnWriteArrayList<>();

        /**
         * The org in force on the pool thread for each query.
         *
         * <p>Read from the same ThreadLocal the real client reads through
         * {@code OpenObserveConfig.getOrg()} when it builds the search path, so
         * this is the org the query would actually have gone to.</p>
         */
        private final List<String> orgs = new CopyOnWriteArrayList<>();

        /** Reject LAST_VALUE, as an older OpenObserve build does. */
        private boolean rejectLatest;

        void rows(String stream, List<Map<String, Object>> rows) {
            byStream.put(stream, rows);
        }

        /**
         * Rows visible only once the window reaches {@code fromMinutes}, which is
         * how a collector that scrapes more slowly than the first rung behaves.
         */
        void rowsAtWindow(String stream, int fromMinutes, List<Map<String, Object>> rows) {
            byWindow.computeIfAbsent(stream, unused -> new java.util.TreeMap<>())
                    .put(fromMinutes, rows);
        }

        void fail(String stream) {
            failing.add(stream);
        }

        /** Below this window the stream answers empty, as a slow scraper does. */
        void minimumWindowMinutes(String stream, int minutes) {
            minWindowMinutes.put(stream, minutes);
        }

        @Override
        public List<Map<String, Object>> search(String sql, long startMicros, long endMicros,
                int from, int size, String streamType) {
            String stream = between(sql, "FROM \"", "\"");
            queries.add(stream);
            this.sql.add(sql);
            orgs.add(String.valueOf(OrgContext.get()));

            if (failing.contains(stream)) {
                throw new IllegalStateException("stream '" + stream + "' does not exist");
            }
            if (rejectLatest && sql.contains("LAST_VALUE")) {
                throw new IllegalStateException("unknown function 'last_value'");
            }
            long windowMinutes = (endMicros - startMicros) / MICROS_PER_MINUTE;

            Integer minimum = minWindowMinutes.get(stream);
            if (minimum != null && windowMinutes < minimum) {
                return List.of();
            }

            java.util.TreeMap<Integer, List<Map<String, Object>>> staged = byWindow.get(stream);
            if (staged != null) {
                Map.Entry<Integer, List<Map<String, Object>>> applicable =
                        staged.floorEntry((int) windowMinutes);
                return applicable == null ? List.of() : applicable.getValue();
            }
            return byStream.getOrDefault(stream, List.of());
        }

        private static String between(String text, String open, String close) {
            int from = text.indexOf(open);
            if (from < 0) {
                return "";
            }
            int start = from + open.length();
            int to = text.indexOf(close, start);
            return to < 0 ? "" : text.substring(start, to);
        }

        @Override
        public JsonNode getJson(String path) {
            throw new UnsupportedOperationException();
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
}
