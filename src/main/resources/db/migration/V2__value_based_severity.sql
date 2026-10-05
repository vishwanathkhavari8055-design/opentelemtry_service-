-- ---------------------------------------------------------------------------
-- Value-based severity.
--
-- Severity was a static label chosen when the rule was written. This adds the
-- option of deriving it from the observed value at firing time, the way a
-- template ladder does:
--
--   value > 90 -> disaster
--   value > 80 -> critical
--   value > 70 -> warning
--   otherwise  -> info
--
-- Thresholds are PER RULE, not global, and that is not a convenience — the
-- metrics are on different scales. process_cpu_usage reports a 0..1 fraction
-- while a percentage metric reports 0..100, so a single global 90/80/70 would
-- silently grade every fractional metric as "info" forever.
--
-- Backward compatible: all three columns are NULL for existing rules, and a
-- rule with no thresholds keeps its static severity exactly as before.
-- ---------------------------------------------------------------------------

-- 'disaster' is a new level above 'critical'. The old CHECK constraints reject
-- it, so both have to be replaced before any row can carry it.
ALTER TABLE alert_rules DROP CONSTRAINT ck_alert_rules_severity;
ALTER TABLE alert_rules ADD CONSTRAINT ck_alert_rules_severity
    CHECK (severity IN ('disaster', 'critical', 'error', 'warning', 'info'));

ALTER TABLE alerts DROP CONSTRAINT ck_alerts_severity;
ALTER TABLE alerts ADD CONSTRAINT ck_alerts_severity
    CHECK (severity IN ('disaster', 'critical', 'error', 'warning', 'info'));

-- Inclusive lower bound of each band. NULL means "this tier is not used".
-- All three NULL (the default, and the state of every existing row) means the
-- ladder is off for that rule and its static severity applies unchanged.
ALTER TABLE alert_rules
    ADD COLUMN severity_disaster_at DOUBLE PRECISION,
    ADD COLUMN severity_critical_at DOUBLE PRECISION,
    ADD COLUMN severity_warning_at  DOUBLE PRECISION;

COMMENT ON COLUMN alert_rules.severity_disaster_at IS
    'Observed value at or above which the alert is graded disaster. NULL disables this tier.';
COMMENT ON COLUMN alert_rules.severity_critical_at IS
    'Observed value at or above which the alert is graded critical. NULL disables this tier.';
COMMENT ON COLUMN alert_rules.severity_warning_at IS
    'Observed value at or above which the alert is graded warning. Below it, info. NULL disables this tier.';

-- Records which value produced the grade. Without it a row saying "critical"
-- is unexplainable after the fact — you cannot tell whether it crossed the
-- threshold or inherited the label, which is the first question asked when
-- someone disputes a page.
ALTER TABLE alerts
    ADD COLUMN severity_source VARCHAR(24);

COMMENT ON COLUMN alerts.severity_source IS
    'How severity was decided: url-override | payload | value-threshold | rule | name-guess | default';

CREATE INDEX ix_alerts_severity_source ON alerts (severity_source)
    WHERE severity_source IS NOT NULL;
