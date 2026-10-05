-- ---------------------------------------------------------------------------
-- Unified incident identity
--
-- One source of truth for "what is currently wrong". An alert episode IS the
-- incident: it already carries the aggregated state (occurrences, first/last
-- firing, current severity, current values, thresholds) and alert_events already
-- holds the per-event history. What was missing was a stable notion of the KIND
-- of problem, independent of how severe it currently is.
--
-- incident_type is the rule name with a trailing severity token removed, so a
-- rule set expressing levels as separate rules ("<x>-warning", "<x>-disaster")
-- aggregates into ONE incident that escalates rather than two that look
-- unrelated. Derived in AlertTypeNormalizer; backfilled here for rows that
-- predate the column.
--
-- This migration also drops the fuzzy correlation engine's tables. That engine
-- grouped by dimension-similarity scoring, which is nondeterministic, does not
-- group by service+type, had no idempotency (it counted the same event on every
-- re-read) and wrote back to OpenObserve on every firing. It was a second,
-- disagreeing source of truth for the same question.
--
-- No alert data is deleted: `alerts` and `alert_events` are untouched apart from
-- gaining a column. The oo_incident tables from V8 are also untouched — those
-- back the Incident tab's OpenObserve webhook view, which is a different feature.
-- ---------------------------------------------------------------------------

ALTER TABLE alerts ADD COLUMN IF NOT EXISTS incident_type VARCHAR(255);

-- Backfill with the same rule AlertTypeNormalizer applies: a trailing severity
-- token preceded by a separator. Case-insensitive, and it strips at most one
-- token, so "disaster-recovery-check" keeps its name — the token has to be the
-- SUFFIX, not merely present.
--
-- Kept in sync with alerts.incident.severity-order. A deployment that has
-- customised that list should re-run the UPDATE below with its own levels; the
-- consequence of not doing so is cosmetic and self-correcting, because the next
-- firing of each rule rewrites the column from the Java derivation.
UPDATE alerts
   SET incident_type = regexp_replace(
           alert_name,
           '[-_ .:](disaster|critical|error|warning|info)$',
           '',
           'i')
 WHERE incident_type IS NULL
   AND alert_name IS NOT NULL;

-- Supports the grouping-key lookup (findOpenEpisode is by org+fingerprint, but
-- the incident view filters and groups on these three) and the Service/Type
-- filter dropdowns.
CREATE INDEX IF NOT EXISTS ix_alerts_incident_key
    ON alerts (org, service_name, incident_type);

-- Child tables first: both carry ON DELETE CASCADE back to `incident`, but
-- dropping in dependency order keeps this readable and makes a partial failure
-- diagnosable rather than mysterious.
DROP TABLE IF EXISTS incident_dimension;
DROP TABLE IF EXISTS incident_alert;
DROP TABLE IF EXISTS incident;
