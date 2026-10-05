-- Increase fingerprint and event_hash column lengths on alert_events table to support 64-character SHA-256 hashes
ALTER TABLE alert_events ALTER COLUMN fingerprint TYPE VARCHAR(64);
ALTER TABLE alert_events ALTER COLUMN event_hash TYPE VARCHAR(64);
