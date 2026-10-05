-- ---------------------------------------------------------------------------
-- Webhook-free alert ingest, and the full enterprise lifecycle.
--
-- Three things change:
--
--   1. Alerts arrive by POLLING an OpenObserve stream instead of by webhook, so
--      the poller needs a durable cursor (alert_ingest_cursor).
--   2. The lifecycle gains CLOSED and the REOPENED transition, so an alert can
--      be taken through open → acknowledged → resolved → closed and reopened.
--   3. Alerts gain the resource columns the enterprise table shows (service,
--      host, namespace, environment, source) which until now were only
--      recoverable by re-reading raw_payload.
--
-- BACKWARD COMPATIBLE by construction. Every added column is nullable or has a
-- default, the widened CHECK constraints only ACCEPT more values than before,
-- and no existing column is dropped or retyped — so the pre-existing webhook
-- rows, the running sweeper and the current UI all keep working untouched while
-- the new ingest path is adopted.
-- ---------------------------------------------------------------------------

-- ── 1. Lifecycle: add CLOSED and REOPENED ──────────────────────────────────
--
-- CLOSED is distinct from RESOLVED on purpose. RESOLVED means "the condition
-- cleared" and is frequently inferred from silence by the sweeper; CLOSED means
-- "a human is finished with this" and is never inferred. Collapsing them would
-- make it impossible to tell an incident somebody actually reviewed from one
-- that merely went quiet, which is the difference an on-call handover turns on.
ALTER TABLE alerts DROP CONSTRAINT ck_alerts_status;
ALTER TABLE alerts ADD CONSTRAINT ck_alerts_status
    CHECK (status IN ('FIRING', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED'));

ALTER TABLE alert_events DROP CONSTRAINT ck_alert_events_type;
ALTER TABLE alert_events ADD CONSTRAINT ck_alert_events_type
    CHECK (event_type IN ('FIRING', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED', 'REOPENED'));

-- The open-episode uniqueness index has to learn about CLOSED too, or a closed
-- alert would keep reserving its fingerprint and the next genuine firing would
-- fold into it instead of opening a new episode.
--
-- This index is what makes de-duplication correct under concurrency, so it is
-- replaced rather than edited: CREATE UNIQUE INDEX ... IF NOT EXISTS cannot
-- change a predicate, and dropping without recreating would leave a window in
-- which duplicates could be inserted. Both statements are in one migration, so
-- they are one transaction.
DROP INDEX uq_alerts_open_episode;
CREATE UNIQUE INDEX uq_alerts_open_episode
    ON alerts (org, fingerprint)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');

-- Same reasoning for the sweeper's index.
DROP INDEX ix_alerts_stale_sweep;
CREATE INDEX ix_alerts_stale_sweep
    ON alerts (status, last_fired_at)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');

ALTER TABLE alerts
    ADD COLUMN closed_by  VARCHAR(128),
    ADD COLUMN closed_at  TIMESTAMPTZ,
    -- How many times this episode has been reopened. An alert reopened three
    -- times is a different conversation from one resolved cleanly, and without
    -- a counter that history is only visible by reading the event trail.
    ADD COLUMN reopen_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_reopened_by VARCHAR(128),
    ADD COLUMN last_reopened_at TIMESTAMPTZ;

COMMENT ON COLUMN alerts.closed_by IS
    'Who closed the alert. CLOSED is always a human decision — the sweeper only ever sets RESOLVED.';
COMMENT ON COLUMN alerts.reopen_count IS
    'Times this episode was reopened after being resolved or closed.';

-- ── 2. Resource identity, for the enterprise table and its filters ─────────
--
-- These are extracted from the alert payload at ingest. Nullable because the
-- payload is rendered by a user-authored OpenObserve template: which of these
-- it carries is a per-deployment fact, not something this schema can require.
ALTER TABLE alerts
    ADD COLUMN service_name VARCHAR(255),
    ADD COLUMN host_name    VARCHAR(255),
    ADD COLUMN namespace    VARCHAR(255),
    ADD COLUMN environment  VARCHAR(64),
    -- Where the row came from: which ingest path produced it. Existing rows are
    -- backfilled to 'webhook' below, so history stays truthful about the fact
    -- that it arrived a different way.
    ADD COLUMN source       VARCHAR(32),
    -- Free-text description and summary as rendered by the template. Kept
    -- separate from `note`, which is what an OPERATOR wrote.
    ADD COLUMN description  TEXT,
    ADD COLUMN summary      TEXT,
    -- The affected entities, as the template listed them ("kubeworker11 : /,
    -- kubeworker15 : /"). Stored verbatim: it is a rendered string, not a list
    -- this schema can meaningfully normalise.
    ADD COLUMN entities     TEXT,
    -- Correlation handle for the telemetry panel.
    ADD COLUMN trace_id     VARCHAR(64);

COMMENT ON COLUMN alerts.source IS
    'Ingest path that produced this row: stream (polled from OpenObserve) or webhook (legacy inbound POST).';
COMMENT ON COLUMN alerts.entities IS
    'Affected entities exactly as the OpenObserve notification template rendered them.';

-- Everything already stored arrived over the webhook, by definition — it is the
-- only path that existed. Saying so explicitly keeps the Source filter honest
-- instead of showing a column of blanks for all historical alerts.
UPDATE alerts SET source = 'webhook' WHERE source IS NULL;

-- Filter indexes. Partial on NOT NULL because a template that never sets a
-- field would otherwise have the whole table in the index under one null key.
CREATE INDEX ix_alerts_service ON alerts (org, service_name) WHERE service_name IS NOT NULL;
CREATE INDEX ix_alerts_source  ON alerts (org, source)       WHERE source IS NOT NULL;
CREATE INDEX ix_alerts_stream  ON alerts (org, stream_name)  WHERE stream_name IS NOT NULL;
CREATE INDEX ix_alerts_trace   ON alerts (trace_id)          WHERE trace_id IS NOT NULL;

-- Keyset pagination and the default sort both order by (last_fired_at DESC, id
-- DESC). id is the tiebreaker: last_fired_at is not unique — a burst of alerts
-- can share a timestamp — and a keyset cursor over a non-unique key silently
-- skips or repeats rows at page boundaries.
CREATE INDEX ix_alerts_keyset ON alerts (org, last_fired_at DESC, id DESC);

-- ── 3. Ingest cursor: where the poller got to ─────────────────────────────
--
-- One row per (org, stream). Durable because the alternative is re-reading the
-- whole retention window on every restart, which would be both slow and — since
-- the episode upsert is idempotent but the FIRING event trail is append-only —
-- would duplicate audit rows.
CREATE TABLE alert_ingest_cursor (
    id                  BIGSERIAL     PRIMARY KEY,
    org                 VARCHAR(64)   NOT NULL,
    stream_name         VARCHAR(255)  NOT NULL,

    -- Exclusive lower bound for the next poll, in epoch MICROSECONDS to match
    -- OpenObserve's own _timestamp resolution. Storing this as a timestamptz
    -- would round-trip through microsecond truncation and re-read a row.
    last_event_micros   BIGINT        NOT NULL,

    -- Diagnostics, so a stalled poller is visible without reading logs.
    last_polled_at      TIMESTAMPTZ,
    last_success_at     TIMESTAMPTZ,
    last_error          TEXT,
    consecutive_failures INTEGER      NOT NULL DEFAULT 0,
    events_ingested     BIGINT        NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_alert_ingest_cursor ON alert_ingest_cursor (org, stream_name);

COMMENT ON TABLE alert_ingest_cursor IS
    'Watermark for the alert_events stream poller that replaced the inbound webhook.';

-- ── 4. Per-event de-duplication ───────────────────────────────────────────
--
-- The cursor alone is not enough. OpenObserve can return a row whose
-- _timestamp equals the watermark (the bound has to be inclusive somewhere, and
-- several alerts can share a microsecond), and a poll that fails after writing
-- some rows will re-read them. So each stream row carries a hash of its
-- identifying content, and re-seeing one is a no-op rather than another
-- occurrence on the episode.
--
-- This is the difference between "the episode is de-duplicated" (already true
-- via uq_alerts_open_episode) and "the same firing is not counted twice", which
-- is what makes the occurrence count and the audit trail trustworthy.
ALTER TABLE alert_events
    ADD COLUMN event_hash VARCHAR(32);

COMMENT ON COLUMN alert_events.event_hash IS
    'Hash of the source stream row, for idempotent re-ingest. NULL for events '
    'created by an operator action or by the legacy webhook path.';

CREATE UNIQUE INDEX uq_alert_events_hash
    ON alert_events (org, event_hash)
    WHERE event_hash IS NOT NULL;

-- ── 5. Rule audit trail ───────────────────────────────────────────────────
--
-- alert_rules carries created_by/updated_by, which answers "who touched it
-- last" but not "what happened to it". Enable, disable, clone and a failed
-- synchronisation are all things somebody later needs to account for, and each
-- one currently overwrites the evidence of the one before it.
CREATE TABLE alert_rule_events (
    id              BIGSERIAL     PRIMARY KEY,
    alert_rule_id   BIGINT        NOT NULL REFERENCES alert_rules (id) ON DELETE CASCADE,
    org             VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(24)   NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor           VARCHAR(128),
    detail          TEXT,
    -- Outcome of the OpenObserve push that accompanied this change, when there
    -- was one. A rule that saved but failed to synchronise is the case people
    -- most need to reconstruct.
    sync_status     VARCHAR(24),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_alert_rule_events_type CHECK (event_type IN (
        'CREATED', 'UPDATED', 'DELETED', 'ENABLED', 'DISABLED',
        'CLONED', 'SYNCED', 'SYNC_FAILED'))
);

CREATE INDEX ix_alert_rule_events_rule ON alert_rule_events (alert_rule_id, occurred_at DESC);
CREATE INDEX ix_alert_rule_events_org  ON alert_rule_events (org, occurred_at DESC);

COMMENT ON TABLE alert_rule_events IS
    'Append-only audit trail for alert rule changes, including synchronisation outcomes.';
