-- SEC-25: recover payouts stuck PROCESSING after a crash between the atomic
-- PENDING->PROCESSING claim (SEC-11) and a confirmed disbursement.
--
-- Flyway version V4032 (NOT lower). All module migrations share ONE global Flyway
-- history with out-of-order DISABLED (B-011 / L-023): two files with the same Vnnnn
-- across any modules is a boot failure. The latest claimed version in the tree is
-- gateway/V4031__create_webhook_deliveries.sql, so this is V4032. Flyway tolerates
-- gaps in the sequence; only DUPLICATES fail.
--
-- The defect: PayoutService.processPendingPayouts claims a payout (UPDATE ... WHERE
-- status='PENDING', committable) and only THEN disburses + writes the terminal
-- markPaid/markFailed. A crash anywhere after the claim commits and before that
-- terminal save strands the row in PROCESSING forever — and the scheduler's finder
-- selects only status='PENDING', so it is NEVER re-selected. Money may be UN-disbursed
-- and never retried.
--
-- The payouts table (V4002:58-71) has created_at / scheduled_at / paid_at but NO
-- timestamp recording WHEN a row entered PROCESSING. created_at is creation time, not
-- claim time (a payout can sit PENDING for hours), so "PROCESSING older than T" cannot
-- be derived from it. Add a claim timestamp the SEC-11 claim UPDATE stamps, plus
-- bounded-retry bookkeeping for the reconciler (mirrors V4025's pending_approvals
-- execution-marker columns). executed-ness reuses the EXISTING PayoutStatus values
-- PAID / FAILED — NO new status column-as-status (the reconciler leaves a row in
-- PROCESSING only between bounded retries, never as a terminal state).
--
-- Safe on a non-empty DB (baseline-on-migrate): every statement is additive and
-- idempotent; all new columns are nullable or defaulted, so no backfill is needed.
-- Pre-SEC-25 rows already stranded in PROCESSING get processing_since = NULL; the
-- finder treats NULL as "infinitely old" (COALESCE(..., 'epoch')) so they are still
-- recovered on the first reconcile cycle.
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS processing_since      TIMESTAMPTZ;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS reconcile_attempts    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS next_reconcile_at     TIMESTAMPTZ;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS last_reconcile_error  TEXT;

-- Partial index for the stuck-finder: it always filters status='PROCESSING'. Keeps the
-- scan to the (normally tiny) in-flight tail and skips every terminal PAID/FAILED row.
CREATE INDEX IF NOT EXISTS idx_payouts_reconcile
    ON payouts (status, processing_since)
    WHERE status = 'PROCESSING';
