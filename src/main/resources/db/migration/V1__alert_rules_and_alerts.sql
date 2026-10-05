-- ---------------------------------------------------------------------------
-- Alert rules and the alerts they produce.
--
-- PostgreSQL is the source of truth. OpenObserve is the evaluation engine: it
-- holds a COPY of each rule, addressed by openobserve_id, and can be rebuilt
-- from this schema at any time (see AlertRuleService#syncAll). Nothing here
-- depends on OpenObserve retaining anything.
-- ---------------------------------------------------------------------------

-- ── alert_rules ────────────────────────────────────────────────────────────
CREATE TABLE alert_rules (
    id                  BIGSERIAL     PRIMARY KEY,

    -- Stable identifier handed to clients. Separate from the surrogate key so
    -- a rule keeps its identity across a database restore into fresh sequences.
    uuid                UUID          NOT NULL,

    org                 VARCHAR(64)   NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    folder_id           VARCHAR(64)   NOT NULL DEFAULT 'default',

    stream_type         VARCHAR(32)   NOT NULL,
    stream_name         VARCHAR(255)  NOT NULL,
    alert_type          VARCHAR(32)   NOT NULL DEFAULT 'scheduled',
    severity            VARCHAR(16)   NOT NULL DEFAULT 'warning',
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,

    -- Held verbatim as JSON rather than shredded into columns. An OpenObserve
    -- alert's condition tree is recursive with untagged variants, and the
    -- product keeps adding fields to it; modelling it column-by-column would
    -- silently drop anything unrecognised on the next save. This mirrors the
    -- lossless pass-through the read-only alerts API already relies on.
    query_condition     JSONB         NOT NULL,
    trigger_condition   JSONB         NOT NULL,
    destinations        JSONB         NOT NULL DEFAULT '[]'::jsonb,
    context_attributes  JSONB,

    -- ── mapping to the evaluation engine ──
    openobserve_id      VARCHAR(64),
    sync_status         VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    sync_error          TEXT,
    last_synced_at      TIMESTAMPTZ,

    -- ── audit ──
    created_by          VARCHAR(128),
    updated_by          VARCHAR(128),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Soft delete: a rule removed here must still be removable from
    -- OpenObserve on the next sync, and an audit trail of what used to page
    -- people is worth more than the row it occupies.
    deleted_at          TIMESTAMPTZ,

    -- JPA optimistic locking. Two operators editing one rule concurrently is
    -- rare but the loser must be told, not silently overwritten.
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ck_alert_rules_stream_type CHECK (stream_type IN ('logs', 'metrics', 'traces')),
    CONSTRAINT ck_alert_rules_severity    CHECK (severity IN ('critical', 'error', 'warning', 'info')),
    CONSTRAINT ck_alert_rules_sync_status CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED', 'DELETE_PENDING'))
);

CREATE UNIQUE INDEX uq_alert_rules_uuid ON alert_rules (uuid);

-- Partial, so a deleted rule does not reserve its name forever. Recreating a
-- rule you deleted last week is a normal thing to want to do.
CREATE UNIQUE INDEX uq_alert_rules_org_name
    ON alert_rules (org, name)
    WHERE deleted_at IS NULL;

-- One local rule per OpenObserve rule. Guards the mapping itself: two rows
-- claiming the same openobserve_id would make sync non-deterministic.
CREATE UNIQUE INDEX uq_alert_rules_oo_id
    ON alert_rules (org, openobserve_id)
    WHERE openobserve_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_alert_rules_org      ON alert_rules (org) WHERE deleted_at IS NULL;
CREATE INDEX ix_alert_rules_sync     ON alert_rules (sync_status) WHERE deleted_at IS NULL;
CREATE INDEX ix_alert_rules_deleted  ON alert_rules (deleted_at) WHERE deleted_at IS NOT NULL;


-- ── alerts ─────────────────────────────────────────────────────────────────
-- One row per EPISODE, not per notification. A rule that fires every minute
-- for an hour is one row with occurrences=60, not sixty rows: a feed that
-- shows the same ongoing problem sixty times is a feed people stop reading.
-- When an episode resolves and the rule fires again, that is a new row.
CREATE TABLE alerts (
    id                  BIGSERIAL     PRIMARY KEY,

    -- Identity of "the same alert" across firings: a hash of
    -- org + name + stream. Deliberately excludes the observed value.
    fingerprint         VARCHAR(32)   NOT NULL,
    org                 VARCHAR(64)   NOT NULL,

    -- Nullable on purpose. Alerts also arrive from rules authored directly in
    -- OpenObserve, which have no row in alert_rules. Dropping those would hide
    -- real firings because of a bookkeeping gap.
    alert_rule_id       BIGINT        REFERENCES alert_rules (id) ON DELETE SET NULL,

    alert_name          VARCHAR(255)  NOT NULL,
    alert_type          VARCHAR(32),
    stream_type         VARCHAR(32),
    stream_name         VARCHAR(255),
    severity            VARCHAR(16)   NOT NULL DEFAULT 'warning',
    status              VARCHAR(16)   NOT NULL DEFAULT 'FIRING',

    first_fired_at      TIMESTAMPTZ   NOT NULL,
    last_fired_at       TIMESTAMPTZ   NOT NULL,
    occurrences         INTEGER       NOT NULL DEFAULT 1,
    episode             INTEGER       NOT NULL DEFAULT 1,

    acknowledged_by     VARCHAR(128),
    acknowledged_at     TIMESTAMPTZ,
    -- Occurrence count at the moment of acknowledgement, so the UI can say
    -- "4 more firings since you picked this up".
    occurrences_at_ack  INTEGER,

    resolved_by         VARCHAR(128),
    resolved_at         TIMESTAMPTZ,
    -- True when resolution was inferred from silence. OpenObserve sends no
    -- "recovered" callback, so staleness is the only signal available.
    auto_resolved       BOOLEAN       NOT NULL DEFAULT FALSE,

    note                TEXT,

    period_minutes      INTEGER,
    operator            VARCHAR(16),
    threshold           BIGINT,
    observed_count      BIGINT,
    agg_value           DOUBLE PRECISION,
    window_start        TIMESTAMPTZ,
    window_end          TIMESTAMPTZ,
    alert_url           TEXT,

    -- Set when the notification body could not be parsed. The alert is still
    -- recorded: a broken template must surface as a visible broken alert
    -- rather than as silence.
    parse_error         TEXT,
    raw_payload         TEXT,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ck_alerts_status   CHECK (status IN ('FIRING', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('critical', 'error', 'warning', 'info'))
);

-- At most one OPEN episode per fingerprint. This is what makes de-duplication
-- correct under concurrency: two webhook calls racing for the same alert
-- cannot both insert, so one updates instead of creating a duplicate row.
CREATE UNIQUE INDEX uq_alerts_open_episode
    ON alerts (org, fingerprint)
    WHERE status <> 'RESOLVED';

CREATE INDEX ix_alerts_org_status    ON alerts (org, status);
CREATE INDEX ix_alerts_last_fired    ON alerts (org, last_fired_at DESC);
CREATE INDEX ix_alerts_fingerprint   ON alerts (org, fingerprint);
CREATE INDEX ix_alerts_rule          ON alerts (alert_rule_id) WHERE alert_rule_id IS NOT NULL;
CREATE INDEX ix_alerts_stale_sweep   ON alerts (status, last_fired_at) WHERE status <> 'RESOLVED';


-- ── alert_events ───────────────────────────────────────────────────────────
-- Append-only audit trail. `alerts` answers "what is happening now" and is
-- mutated in place; this answers "what happened", and is never updated.
-- Acknowledgements and resolutions are decisions people are accountable for,
-- so the record of who did what has to survive the row being overwritten by
-- the next firing.
CREATE TABLE alert_events (
    id              BIGSERIAL     PRIMARY KEY,
    alert_id        BIGINT        NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    fingerprint     VARCHAR(32)   NOT NULL,
    org             VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(16)   NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL,
    actor           VARCHAR(128),
    note            TEXT,
    observed_count  BIGINT,
    agg_value       DOUBLE PRECISION,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_alert_events_type CHECK (event_type IN ('FIRING', 'ACKNOWLEDGED', 'RESOLVED'))
);

CREATE INDEX ix_alert_events_alert       ON alert_events (alert_id, occurred_at DESC);
CREATE INDEX ix_alert_events_org_time    ON alert_events (org, occurred_at DESC);
CREATE INDEX ix_alert_events_fingerprint ON alert_events (org, fingerprint, occurred_at DESC);
