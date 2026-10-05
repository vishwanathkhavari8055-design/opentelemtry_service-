-- ─────────────────────────────────────────────────────────────────────────────
-- V11 — keep the whole OpenObserve alert document alongside the modelled columns
--
-- Why this column has to exist
-- ----------------------------
-- `alert_rules` models the parts of an alert this service reasons about: name,
-- stream, the two condition trees, destinations, context attributes. An
-- OpenObserve alert document carries a good deal more — row_template,
-- row_template_type, template, priority, tags, deduplication, creates_incident,
-- composite_condition, anomaly_config, workflows, search_event_type — and the
-- product keeps adding to that list.
--
-- Until now those keys survived only as long as nobody pushed the rule back.
-- `AlertRuleServiceImpl.toOpenObserveDocument` REBUILDS the document from the
-- modelled columns, so a rule imported from OpenObserve (AlertRuleImporter) and
-- then re-pushed by a sync would come back stripped of every field in that list.
-- Nothing errors: the save succeeds and the alert quietly loses its priority, its
-- tags, its deduplication window and — for a composite — the expression that is
-- the entire rule.
--
-- Storing the document as received closes that. The push now starts from this
-- copy and overlays the modelled columns on top, so a field this service has
-- never heard of round-trips untouched, which is the same contract the read-only
-- pass-through API and the browser's own editor already keep.
--
-- Nullable on purpose
-- -------------------
-- Rows written before this migration have no source document and must keep
-- working. A NULL here simply means "no captured document", and the push falls
-- back to the previous rebuild-from-columns behaviour for that row — no worse
-- than it is today, and repaired for real the next time the rule is saved or
-- imported.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE alert_rules
    ADD COLUMN IF NOT EXISTS source_document JSONB;

COMMENT ON COLUMN alert_rules.source_document IS
    'Last known full OpenObserve alert document for this rule, stored verbatim. '
    'The push to OpenObserve starts from this and overlays the modelled columns, '
    'so fields this service does not model are not dropped on a re-sync. '
    'NULL for rows created before V11; those fall back to a rebuilt document.';
