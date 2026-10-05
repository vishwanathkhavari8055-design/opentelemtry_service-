-- ---------------------------------------------------------------------------
-- The Product Catalog moves into PostgreSQL, and gains a PRODUCT tier.
--
-- Two changes, and the second is why the first was needed:
--
--   1. Registered resources are now rows here rather than an in-memory map made
--      durable by an append-only OpenObserve stream. The stream store still
--      exists and is one property away (product-catalog.store=MEMORY), so this
--      is additive — but PostgreSQL is now the source of truth, which means a
--      registration survives a restart without replaying a change log, and it
--      survives OpenObserve being replaced entirely. That last point is not
--      hypothetical on this deployment: changing the OpenObserve address emptied
--      the catalog, because the change log lived inside the instance that went
--      away.
--
--   2. A resource is no longer just (category, name). It is
--      category -> PRODUCT -> microservice, plus a VERSION. "Applications" held
--      sixteen distinct products, and registering a microservice without saying
--      which product it belongs to loses the one piece of structure an operator
--      needs to navigate them.
--
-- The product list is SEEDED here rather than hardcoded in Java, so a new
-- product is an INSERT and not a release. Codes are stable ASCII slugs: the
-- labels contain spaces, ampersands and en-dashes, none of which belong in a
-- value that travels in a URL or a filter predicate.
-- ---------------------------------------------------------------------------

-- ── 1. The product tier ────────────────────────────────────────────────────
--
-- Reference data, keyed by (category, code). Not org-scoped: the product
-- taxonomy is the same catalogue of software whichever tenant is looking at it,
-- and what differs per org is which microservices of it are registered.
CREATE TABLE catalog_products (
    id           BIGSERIAL    PRIMARY KEY,
    category     VARCHAR(32)  NOT NULL,
    code         VARCHAR(128) NOT NULL,
    label        VARCHAR(255) NOT NULL,
    -- Free-text note carried straight from the source list, e.g. "Covered in
    -- trinityMOBILE". It is guidance for whoever is registering, not a relation
    -- this schema pretends to model.
    note         VARCHAR(255),
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_catalog_products_code UNIQUE (category, code)
);

CREATE INDEX ix_catalog_products_category ON catalog_products (category, sort_order);

-- ── 2. Registered resources ────────────────────────────────────────────────
CREATE TABLE catalog_resources (
    id                   BIGSERIAL    PRIMARY KEY,

    -- The public identifier. The API has always handed out a UUID string, and
    -- keeping that stable matters: the UI holds ids in its table rows and its
    -- drawer, so switching to a bigint would break every client at once.
    resource_uuid        UUID         NOT NULL,

    org                  VARCHAR(64)  NOT NULL,
    category             VARCHAR(32)  NOT NULL,

    -- NULLABLE, deliberately. Only Applications, AI Services and Mobile
    -- Application have a product list; Tools, Databases, Platform and DLH are
    -- registered directly, and forcing a synthetic product onto them would put a
    -- meaningless tier in the tree for four of the seven categories.
    product_code         VARCHAR(128),

    -- The microservice, as the operator typed it.
    resource_name        VARCHAR(255) NOT NULL,
    -- The spelling telemetry actually uses. Compared exactly in filter
    -- predicates, which is why it is stored rather than re-derived per query.
    resolved_name        VARCHAR(255),

    -- Free text on purpose: versions are "1.4.2", "2024.3", "v3-rc1" and
    -- "latest" in the same estate, and a numeric column would reject three of
    -- those. Nothing here orders or compares them.
    version              VARCHAR(64),

    status               VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    source               VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',

    -- Encoded exactly as the OpenObserve stream store encoded them, so the two
    -- stores stay interchangeable: signals comma-separated, bindings one per
    -- line as signal|stream|column|value.
    signals              TEXT,
    bindings             TEXT,

    last_seen_micros     BIGINT,
    registered_at_micros BIGINT       NOT NULL,
    updated_at_micros    BIGINT       NOT NULL,

    created_by           VARCHAR(128),
    updated_by           VARCHAR(128),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Soft delete. Unregistering has to be distinguishable from never having
    -- registered, because the same name can legitimately be registered again
    -- later and the audit trail should not lose the first attempt.
    deleted_at           TIMESTAMPTZ,

    CONSTRAINT ck_catalog_resources_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT uq_catalog_resources_uuid  UNIQUE (resource_uuid)
);

-- One live registration per (org, category, product, microservice).
--
-- COALESCE on product_code is load-bearing: in SQL, NULL never equals NULL, so a
-- plain unique index would allow unlimited duplicates of every product-less
-- resource — exactly the categories where duplicates are most likely, since
-- Databases and Tools are registered by bare name.
CREATE UNIQUE INDEX uq_catalog_resources_live
    ON catalog_resources (org, category, COALESCE(product_code, ''), LOWER(resource_name))
    WHERE deleted_at IS NULL;

CREATE INDEX ix_catalog_resources_org     ON catalog_resources (org) WHERE deleted_at IS NULL;
CREATE INDEX ix_catalog_resources_product ON catalog_resources (org, category, product_code)
    WHERE deleted_at IS NULL;

-- ── 3. Seed the product list ───────────────────────────────────────────────
--
-- Verbatim from the agreed inventory. Labels keep their original punctuation
-- (including the en-dashes) because they are what an operator recognises; codes
-- are slugs derived once, here, so they can never drift from a label edit.

-- Applications
INSERT INTO catalog_products (category, code, label, note, sort_order) VALUES
 ('APPLICATIONS', 'trinityiot-iot-hub-system-and-iot-sdk',      'trinityIoT - IoT Hub System and IoT SDK',      NULL, 10),
 ('APPLICATIONS', 'trinityiot-complex-event-processing-system', 'trinityIoT - Complex Event Processing System', NULL, 20),
 ('APPLICATIONS', 'trinityiot-datalakehouse',                   'trinityIoT - Datalakehouse',                  NULL, 30),
 ('APPLICATIONS', 'trinityiot-api-and-esb-system',              'trinityIoT - API and ESB System',              NULL, 40),
 ('APPLICATIONS', 'trinityiot-gis-integration-with-3d-engine',  'trinityIoT - GIS Integration with 3D Engine',  NULL, 50),
 ('APPLICATIONS', 'trinityiot-security-system',                 'trinityIoT - Security System',                 NULL, 60),
 ('APPLICATIONS', 'trinitynotify-notification-system',          'trinityNOTIFY - Notification System',          NULL, 70),
 ('APPLICATIONS', 'trinityiot-iot-provisioning-admin-tool',     'trinityIoT - IoT Provisioning & Administration Tool', NULL, 80),
 ('APPLICATIONS', 'trinitydpa-dpa-tool',                        'trinityDPA - DPA tool',                        NULL, 90),
 ('APPLICATIONS', 'trinitydashboard',                           'trinityDashBoard',                             NULL, 100),
 ('APPLICATIONS', 'trinity-report',                             'trinity Report',                               NULL, 110),
 ('APPLICATIONS', 'trinityiccc-operator-and-administrator',     'trinityICCC – ICCC Operator & Administrator',   NULL, 120),
 ('APPLICATIONS', 'trinityiccc-digital-twin-application',       'trinityICCC - Digital Twin Application',        NULL, 130),
 ('APPLICATIONS', 'trinityengage-case-management-system',       'trinityENGAGE - Case Management System',        NULL, 140),
 ('APPLICATIONS', 'trinityengage-citizen-portal',               'trinityENGAGE - Citizen Portal',                NULL, 150),
 ('APPLICATIONS', 'trinitydevconnect-partner-portal',           'trinityDEVCONNECT - Partner Portal',
   'Covered in trinityIoT - API and ESB System', 160);

-- AI Services (the source list calls this "AI platform"; the category the UI
-- and the enum already use is AI Services, and inventing a seventh category for
-- the same three products would split them from the filter that scopes them).
INSERT INTO catalog_products (category, code, label, note, sort_order) VALUES
 ('AI_SERVICES', 'trinityiot-ai-engine',              'trinityIoT - AI Engine',              NULL, 10),
 ('AI_SERVICES', 'trinityiot-genai-and-agentic-ai',   'trinityIoT – GenAI and Agentic AI',    NULL, 20),
 ('AI_SERVICES', 'trinityanalytika-ml-composer-tool', 'trinityANALYTIKA - ML Composer Tool', NULL, 30);

-- Mobile Application
INSERT INTO catalog_products (category, code, label, note, sort_order) VALUES
 ('MOBILE_APPLICATION', 'trinitymobile-super-app',               'trinityMOBILE - Super App',               NULL, 10),
 ('MOBILE_APPLICATION', 'trinitymobile-super-app-administrator', 'trinityMOBILE - Super App Administrator', NULL, 20),
 ('MOBILE_APPLICATION', 'trinityengage-city-app',                'trinityENGAGE - City App',                NULL, 30),
 ('MOBILE_APPLICATION', 'trinityiot-mbaas',                      'trinityIoT – MbaaS',
   'Covered in trinityMOBILE', 40);
