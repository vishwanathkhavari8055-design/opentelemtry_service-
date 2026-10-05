package com.trinity.observability.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * How far the alert poller has read into an OpenObserve stream.
 *
 * <p>This is what replaced the webhook's push semantics. A poller without a
 * durable watermark has to re-read its whole retention window after every
 * restart — which is slow, and worse, would append a duplicate FIRING row to the
 * append-only audit trail for every alert it re-saw.</p>
 *
 * <h2>Why microseconds, and why a BIGINT</h2>
 *
 * <p>OpenObserve's {@code _timestamp} is epoch MICROSECONDS, and the watermark is
 * compared directly against it. Storing it as a {@code timestamptz} would round
 * trip through PostgreSQL's microsecond truncation and, at the boundary, re-read
 * the last row on every poll forever. Keeping the engine's own unit removes the
 * conversion entirely.</p>
 *
 * <h2>The failure counters are not decoration</h2>
 *
 * <p>A poller that silently stops is indistinguishable from "nothing is firing",
 * which is the worst possible failure for an alerting system. Recording the last
 * error and a consecutive-failure count next to the watermark makes a stalled
 * ingest visible from a query rather than only from a log search.</p>
 */
@Entity
@Table(name = "alert_ingest_cursor")
public class AlertIngestCursorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "org", nullable = false, length = 64)
    private String org;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    /** Exclusive lower bound for the next poll, epoch microseconds. */
    @Column(name = "last_event_micros", nullable = false)
    private long lastEventMicros;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures = 0;

    @Column(name = "events_ingested", nullable = false)
    private Long eventsIngested = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** A fresh cursor starting at {@code fromMicros}. */
    public static AlertIngestCursorEntity startingAt(String org, String streamName, long fromMicros) {
        AlertIngestCursorEntity cursor = new AlertIngestCursorEntity();
        cursor.org = org;
        cursor.streamName = streamName;
        cursor.lastEventMicros = fromMicros;
        return cursor;
    }

    /**
     * Records a successful poll.
     *
     * <p>The watermark only ever moves FORWARD. A poll that legitimately returns
     * nothing must not rewind it, and neither must a late row — otherwise the next
     * poll re-reads a range it has already accounted for.</p>
     */
    public void recordSuccess(long newestEventMicros, int ingested) {
        Instant now = Instant.now();
        this.lastPolledAt = now;
        this.lastSuccessAt = now;
        this.lastError = null;
        this.consecutiveFailures = 0;
        if (newestEventMicros > this.lastEventMicros) {
            this.lastEventMicros = newestEventMicros;
        }
        this.eventsIngested = (this.eventsIngested == null ? 0L : this.eventsIngested) + ingested;
    }

    /**
     * Records a failed poll, leaving the watermark alone.
     *
     * <p>Not advancing it is the whole point: whatever was in that range has not
     * been stored, so the next attempt must cover it again.</p>
     */
    public void recordFailure(String error) {
        this.lastPolledAt = Instant.now();
        this.lastError = error;
        this.consecutiveFailures = (this.consecutiveFailures == null ? 0 : this.consecutiveFailures) + 1;
    }

    public Long getId()                       { return id; }
    public void setId(Long v)                 { this.id = v; }
    public String getOrg()                    { return org; }
    public void setOrg(String v)              { this.org = v; }
    public String getStreamName()             { return streamName; }
    public void setStreamName(String v)       { this.streamName = v; }
    public long getLastEventMicros()          { return lastEventMicros; }
    public void setLastEventMicros(long v)    { this.lastEventMicros = v; }
    public Instant getLastPolledAt()          { return lastPolledAt; }
    public void setLastPolledAt(Instant v)    { this.lastPolledAt = v; }
    public Instant getLastSuccessAt()         { return lastSuccessAt; }
    public void setLastSuccessAt(Instant v)   { this.lastSuccessAt = v; }
    public String getLastError()              { return lastError; }
    public void setLastError(String v)        { this.lastError = v; }
    public Integer getConsecutiveFailures()   { return consecutiveFailures == null ? 0 : consecutiveFailures; }
    public void setConsecutiveFailures(Integer v) { this.consecutiveFailures = v == null ? 0 : v; }
    public Long getEventsIngested()           { return eventsIngested == null ? 0L : eventsIngested; }
    public void setEventsIngested(Long v)     { this.eventsIngested = v == null ? 0L : v; }
    public Instant getCreatedAt()             { return createdAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
    public Long getVersion()                  { return version; }
    public void setVersion(Long v)            { this.version = v; }
}
