package com.trinity.observability.dto.infrastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire shapes for the infrastructure utilization tiles.
 *
 * <p>Timestamps are ISO-8601 strings, matching every other response in this
 * service; the caller never has to know the metric layer keeps epoch
 * microseconds.</p>
 *
 * <h2>Every tile always appears</h2>
 *
 * <p>{@link Utilization#getItems()} carries all four kinds on every response,
 * even when a stream is missing — the missing one comes back with
 * {@code available=false} and a {@code message} saying why. Omitting it instead
 * would make the screen silently lose a tile, and a tile that is not on the page
 * cannot be distinguished from one that is reading zero.</p>
 */
public final class InfrastructureResponseDTO {

    private InfrastructureResponseDTO() {
        // Container for the nested response shapes only.
    }

    /** What the four tiles show, plus the per-node breakdown behind them. */
    public static class Utilization {

        private String org;

        /** When this answer was computed. Differs from a tile's lastSeen. */
        private String capturedAt;

        /** False when {@code openobserve.enabled=false} or the feature is off. */
        private boolean supported;

        /** True when this response came from the short-lived cache. */
        private boolean cached;

        /** Seconds until the cached answer is recomputed; 0 when not cached. */
        private long cacheAgeSec;

        private List<Item> items = new ArrayList<>();

        private List<Node> nodes = new ArrayList<>();

        public String getOrg() {
            return org;
        }

        public void setOrg(String org) {
            this.org = org;
        }

        public String getCapturedAt() {
            return capturedAt;
        }

        public void setCapturedAt(String capturedAt) {
            this.capturedAt = capturedAt;
        }

        public boolean isSupported() {
            return supported;
        }

        public void setSupported(boolean supported) {
            this.supported = supported;
        }

        public boolean isCached() {
            return cached;
        }

        public void setCached(boolean cached) {
            this.cached = cached;
        }

        public long getCacheAgeSec() {
            return cacheAgeSec;
        }

        public void setCacheAgeSec(long cacheAgeSec) {
            this.cacheAgeSec = cacheAgeSec;
        }

        public List<Item> getItems() {
            return items;
        }

        public void setItems(List<Item> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }

        public List<Node> getNodes() {
            return nodes;
        }

        public void setNodes(List<Node> nodes) {
            this.nodes = nodes == null ? new ArrayList<>() : nodes;
        }
    }

    /** One tile: CPU, Memory, Disk or NFS Storage. */
    public static class Item {

        /** {@code CPU}, {@code MEMORY}, {@code DISK} or {@code NFS_STORAGE}. */
        private String kind;

        /** Human label, so the UI does not have to carry its own mapping. */
        private String label;

        /** {@code PERCENT} when there are no byte counts, otherwise {@code BYTES}. */
        private String unit;

        /** 0–100, rounded to one decimal. Null when the tile has no data. */
        private Double usedPercent;

        private Long usedBytes;
        private Long freeBytes;
        private Long totalBytes;

        /** False when nothing answered; {@link #getMessage()} then says why. */
        private boolean available;

        /**
         * Newest sample behind this tile, and its age.
         *
         * <p>The age is the honest measure of how live the number is, and it is
         * computed server-side on purpose — a browser clock that is minutes out
         * would otherwise render a current reading as stale, or the reverse.</p>
         */
        private String lastSeen;
        private Long lastSeenAgoSec;

        /** Which rung of the window ladder answered, e.g. {@code now-5m}. */
        private String window;

        /**
         * The numbers behind the percentage, e.g. {@code 1.59 / 5 cores}.
         *
         * <p>Pre-formatted, and deliberately so for the pod tiles: it is produced
         * by the same statement that produced {@link #getUsedPercent()}, which is
         * what keeps the caption and the percentage describing the same
         * arithmetic.</p>
         */
        private String detail;

        /**
         * WHAT was measured, e.g. {@code tiotopenobserve pods} or
         * {@code 12 mounts on 8 nodes}.
         *
         * <p>Carried on the wire rather than written into the screen because the
         * tiles do not all measure the same thing: CPU and Memory are scoped to a
         * set of pods, Disk and NFS Storage to the cluster. A card labelled from a
         * hard-coded string in the UI would keep claiming the old scope after the
         * query behind it was repointed — which is precisely how this screen came
         * to show node figures under pod headings.</p>
         */
        private String scope;

        /** Why the tile is unavailable, or null when it is fine. */
        private String message;

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public Double getUsedPercent() {
            return usedPercent;
        }

        public void setUsedPercent(Double usedPercent) {
            this.usedPercent = usedPercent;
        }

        public Long getUsedBytes() {
            return usedBytes;
        }

        public void setUsedBytes(Long usedBytes) {
            this.usedBytes = usedBytes;
        }

        public Long getFreeBytes() {
            return freeBytes;
        }

        public void setFreeBytes(Long freeBytes) {
            this.freeBytes = freeBytes;
        }

        public Long getTotalBytes() {
            return totalBytes;
        }

        public void setTotalBytes(Long totalBytes) {
            this.totalBytes = totalBytes;
        }

        public boolean isAvailable() {
            return available;
        }

        public void setAvailable(boolean available) {
            this.available = available;
        }

        public String getLastSeen() {
            return lastSeen;
        }

        public void setLastSeen(String lastSeen) {
            this.lastSeen = lastSeen;
        }

        public Long getLastSeenAgoSec() {
            return lastSeenAgoSec;
        }

        public void setLastSeenAgoSec(Long lastSeenAgoSec) {
            this.lastSeenAgoSec = lastSeenAgoSec;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * One node's disk, so the cluster Disk figure can be opened up.
     *
     * <p>A single averaged figure hides the case the operator actually cares about
     * — seven roomy nodes and one nearly full average to something comfortable — so
     * the rows behind the tile travel with it rather than needing a second
     * request.</p>
     *
     * <p>DISK ONLY. CPU and Memory are read per POD now, and a pod's reading does
     * not belong to a node, so those columns were removed rather than left to
     * serialize as null on every row — a null column that used to hold a number is
     * read as a broken backend, not as a changed scope.</p>
     */
    public static class Node {

        private String nodeName;

        private Double diskPercent;
        private Long diskUsedBytes;
        private Long diskTotalBytes;

        private String lastSeen;
        private Long lastSeenAgoSec;

        public String getNode() {
            return nodeName;
        }

        public void setNode(String node) {
            this.nodeName = node;
        }

        public Double getDiskPercent() {
            return diskPercent;
        }

        public void setDiskPercent(Double diskPercent) {
            this.diskPercent = diskPercent;
        }

        public Long getDiskUsedBytes() {
            return diskUsedBytes;
        }

        public void setDiskUsedBytes(Long diskUsedBytes) {
            this.diskUsedBytes = diskUsedBytes;
        }

        public Long getDiskTotalBytes() {
            return diskTotalBytes;
        }

        public void setDiskTotalBytes(Long diskTotalBytes) {
            this.diskTotalBytes = diskTotalBytes;
        }

        public String getLastSeen() {
            return lastSeen;
        }

        public void setLastSeen(String lastSeen) {
            this.lastSeen = lastSeen;
        }

        public Long getLastSeenAgoSec() {
            return lastSeenAgoSec;
        }

        public void setLastSeenAgoSec(Long lastSeenAgoSec) {
            this.lastSeenAgoSec = lastSeenAgoSec;
        }
    }
}
