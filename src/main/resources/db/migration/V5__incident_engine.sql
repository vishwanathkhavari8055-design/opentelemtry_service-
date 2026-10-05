-- ---------------------------------------------------------------------------
-- Incident Management Engine Schema
-- ---------------------------------------------------------------------------

CREATE TABLE incident (
    id UUID PRIMARY KEY,
    org VARCHAR(128) NOT NULL,
    title VARCHAR(512) NOT NULL,
    description TEXT,
    severity VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    
    -- OpenObserve Sync tracking
    openobserve_incident_id VARCHAR(128),
    sync_status VARCHAR(64),
    
    correlation_score INTEGER DEFAULT 0,
    
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    
    -- Denormalized stats
    alert_count INTEGER NOT NULL DEFAULT 0,
    unique_alert_count INTEGER NOT NULL DEFAULT 0,
    occurrence_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX ix_incident_org_status ON incident (org, status);
CREATE INDEX ix_incident_openobserve_id ON incident (org, openobserve_incident_id);


CREATE TABLE incident_alert (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    alert_fingerprint VARCHAR(128) NOT NULL,
    alert_name VARCHAR(255) NOT NULL,
    severity VARCHAR(64),
    status VARCHAR(64),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_incident_alert_fingerprint UNIQUE (incident_id, alert_fingerprint)
);

CREATE INDEX ix_incident_alert_fingerprint ON incident_alert (alert_fingerprint);


CREATE TABLE incident_dimension (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    dimension_name VARCHAR(128) NOT NULL,
    dimension_value VARCHAR(512) NOT NULL,
    
    CONSTRAINT uq_incident_dimension UNIQUE (incident_id, dimension_name, dimension_value)
);

CREATE INDEX ix_incident_dimension_name_value ON incident_dimension (dimension_name, dimension_value);
