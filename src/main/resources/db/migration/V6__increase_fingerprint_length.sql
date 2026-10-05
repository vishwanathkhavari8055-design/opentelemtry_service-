-- Increase alert fingerprint length to 64 characters to support SHA-256 hex hashes
ALTER TABLE alerts ALTER COLUMN fingerprint TYPE VARCHAR(64);
