-- ---------------------------------------------------------------------------
-- OpenObserve Incident Webhook Integration Schema
-- ---------------------------------------------------------------------------

CREATE TABLE oo_incident (
    id UUID PRIMARY KEY,
    openobserve_incident_id VARCHAR(128) UNIQUE NOT NULL,
    event_type VARCHAR(64),
    title VARCHAR(512),
    description TEXT,
    severity VARCHAR(64),
    status VARCHAR(64),
    openobserve_url TEXT,
    raw_payload TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX ix_oo_incident_openobserve_id ON oo_incident (openobserve_incident_id);

CREATE TABLE oo_incident_alert (
    id UUID PRIMARY KEY,
    oo_incident_id UUID NOT NULL REFERENCES oo_incident(id) ON DELETE CASCADE,
    alert_name VARCHAR(255) NOT NULL,
    severity VARCHAR(64),
    status VARCHAR(64),
    first_seen_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_oo_incident_alert_name UNIQUE (oo_incident_id, alert_name)
);

CREATE INDEX ix_oo_incident_alert_name ON oo_incident_alert (alert_name);
