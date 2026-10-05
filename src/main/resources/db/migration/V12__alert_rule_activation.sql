-- ─────────────────────────────────────────────────────────────────────────────
-- V12 — "enable now, or later"
--
-- The gap this closes
-- -------------------
-- Until now the only expression of "not live yet" was `enabled = false`, and
-- that single boolean conflates three different situations an operator is in:
--
--   * the rule is live                                  (enabled, on purpose)
--   * the rule was written ahead of time and is not yet  (staged, on purpose)
--   * the rule was TURNED OFF because it was misfiring   (suppressed, on purpose)
--
-- The middle one is what the alert editor is being asked for: write the rule
-- now, decide when it starts acting. Read off `enabled` alone, a staged rule is
-- indistinguishable from a suppressed one, so the rules table cannot tell an
-- operator "this starts on Monday" — it can only say "off", which is exactly
-- the state somebody will "helpfully" flip back on.
--
-- So intent is stored next to the boolean rather than inferred from it.
-- `enabled` stays the single source of truth for whether OpenObserve evaluates
-- the rule; these columns say WHY it is where it is, and when that changes.
--
-- Backward compatible
-- -------------------
-- Every existing row becomes activation_mode = 'NOW' with a NULL activate_at,
-- which describes what those rules already are: decided at write time, no
-- pending transition. Nothing reads differently and nothing is rescheduled.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE alert_rules
    ADD COLUMN IF NOT EXISTS activation_mode VARCHAR(16) NOT NULL DEFAULT 'NOW',
    ADD COLUMN IF NOT EXISTS activate_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS activated_at    TIMESTAMPTZ;

ALTER TABLE alert_rules
    DROP CONSTRAINT IF EXISTS ck_alert_rules_activation_mode;

ALTER TABLE alert_rules
    ADD CONSTRAINT ck_alert_rules_activation_mode
    CHECK (activation_mode IN ('NOW', 'LATER', 'SCHEDULED'));

-- SCHEDULED without a time would never fire and never be visible as a mistake:
-- the sweep below selects on activate_at, so a NULL one is silently skipped
-- forever. Rejected at the column rather than trusted to the service, because
-- the importer and a hand-written UPDATE both reach this table too.
ALTER TABLE alert_rules
    DROP CONSTRAINT IF EXISTS ck_alert_rules_activate_at;

ALTER TABLE alert_rules
    ADD CONSTRAINT ck_alert_rules_activate_at
    CHECK (activation_mode <> 'SCHEDULED' OR activate_at IS NOT NULL);

-- Partial index: the activation sweep runs on a timer and asks one question —
-- "is anything due?" — which for almost every deployment is answered by an
-- empty set. Indexing only the rows that can possibly qualify keeps that poll
-- off the table proper no matter how many rules accumulate.
CREATE INDEX IF NOT EXISTS ix_alert_rules_activate_at
    ON alert_rules (activate_at)
    WHERE activation_mode = 'SCHEDULED' AND deleted_at IS NULL;

COMMENT ON COLUMN alert_rules.activation_mode IS
    'Why the rule is in its current enabled state: NOW = decided at write time; '
    'LATER = staged, waiting for somebody to turn it on; SCHEDULED = staged, '
    'waiting for activate_at to pass.';
COMMENT ON COLUMN alert_rules.activate_at IS
    'When a SCHEDULED rule becomes enabled. NULL for every other mode.';
COMMENT ON COLUMN alert_rules.activated_at IS
    'When the rule actually went live, whether by schedule or by hand. NULL while staged.';
