-- ---------------------------------------------------------------------------
-- The Dashboard Catalog: which Grafana dashboards this application exposes.
--
-- METADATA ONLY. No panel, query, threshold or layout is copied here, and that
-- is the whole design of the feature: Grafana stays the source of truth and a
-- dashboard's definition is re-read from it every time one is opened. A panel
-- edited in Grafana therefore changes in this application on the next open,
-- with no migration, no rebuild and no second copy to drift.
--
-- What a row IS: an administrator's decision that dashboard <uid> should be
-- reachable in this application, filed under a particular folder tile, in a
-- particular position, switched on or off.
--
-- ── Two folder columns, not one ────────────────────────────────────────────
--
-- folder_uid is where the dashboard appears HERE — picked at registration, and
-- what the folder tiles group by. grafana_folder_uid is where it actually lives
-- in Grafana, captured at registration and refreshed by a sync.
--
-- They are usually the same. Keeping them apart is what makes the difference
-- visible when they are not: a dashboard moved to another folder in Grafana
-- keeps working and keeps its tile, and the UI can say where it went instead of
-- the tile quietly describing something else. Collapsing them into one column
-- would force a choice between "the tile silently moves" and "the tile lies".
--
-- ── Hard delete, unlike catalog_resources ──────────────────────────────────
--
-- catalog_resources uses a soft delete because unregistering a resource there
-- CHANGES WHAT TELEMETRY IS VISIBLE across Logs, Traces and Metrics, so the
-- attempt is worth an audit trail. Unregistering a dashboard hides nothing and
-- deletes nothing — the dashboard is untouched in Grafana and one click puts it
-- back — so a tombstone would buy a partial-unique-index and nothing else.
-- ---------------------------------------------------------------------------

CREATE TABLE grafana_dashboard_registrations (
    id                   BIGSERIAL    PRIMARY KEY,

    -- Tenancy boundary. '*' when registrations are shared across the deployment
    -- (grafana.registry.scope=GLOBAL, the default), otherwise the OpenObserve
    -- organization identifier.
    --
    -- GLOBAL is the default because Grafana knows nothing about OpenObserve
    -- organizations: its folders are one shared set, and a dashboard worth
    -- registering is worth registering once. The column exists anyway, and is
    -- STORED rather than derived at read time, so flipping the scope property
    -- cannot silently reinterpret rows that were written under the other one.
    scope_key            VARCHAR(64)  NOT NULL DEFAULT '*',

    -- The Grafana dashboard UID, exactly as it appears in /d/<uid>/<slug>.
    dashboard_uid        VARCHAR(64)  NOT NULL,

    -- Cached for the tiles and the registry table, so listing 40 registrations
    -- is one SELECT rather than 40 calls to Grafana. Refreshed on sync, and the
    -- dashboard's own title always wins on open.
    title                VARCHAR(512) NOT NULL DEFAULT '',

    -- Where it appears in THIS application. Empty string, not NULL: it is a
    -- grouping key, and in SQL NULL never equals NULL — a nullable column would
    -- make the unique index below allow unlimited duplicates of every ungrouped
    -- row, and would need COALESCE at every comparison.
    folder_uid           VARCHAR(64)  NOT NULL DEFAULT '',
    folder_title         VARCHAR(512) NOT NULL DEFAULT '',

    -- Where it lives in Grafana, as of registration or the last sync. Nullable
    -- because a registration made while Grafana was unreachable legitimately
    -- knows the UID and not the folder.
    grafana_folder_uid   VARCHAR(64),
    grafana_folder_title VARCHAR(512),

    -- Disabled hides it from the folder tile without forgetting the placement or
    -- the order, so switching a dashboard off for a while is not a re-registration.
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Tile position within its folder. Not UNIQUE: two dashboards sharing a
    -- position is a cosmetic tie broken by title, and enforcing uniqueness would
    -- make "move this one up" a multi-row transaction.
    display_order        INTEGER      NOT NULL DEFAULT 0,

    registered_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One registration per dashboard per scope. This is what makes a duplicate
-- registration a 409 the UI can explain rather than a second tile for the same
-- dashboard.
CREATE UNIQUE INDEX uq_grafana_dashboard_scope_uid
    ON grafana_dashboard_registrations (scope_key, dashboard_uid);

-- Backs the two reads the screen makes: the whole list for one scope, and one
-- folder tile's contents.
CREATE INDEX ix_grafana_dashboard_scope_folder
    ON grafana_dashboard_registrations (scope_key, folder_uid, display_order);
