-- B-022: stuck APPROVED-but-unexecuted refund reconciler.
--
-- Flyway version V4025 (NOT V4024). All module migrations share ONE global Flyway
-- history with out-of-order DISABLED (B-011 / L-023): two files with the same Vnnnn
-- across any modules is a boot failure. The in-flight fraud request-fingerprint PR
-- (branch perpetua/B-029-hardening) already claims fraud/V4024; this migration was
-- numbered while that file was absent from the tree and collided at V4024, so it is
-- bumped to V4025. The gap at V4024 is INTENTIONAL and reserved for that fraud PR —
-- Flyway tolerates gaps in the version sequence; only duplicates fail.
--
-- The pending_approvals state machine is {PENDING, APPROVED, REJECTED} (V1103:15
-- chk_approval_status; PendingApproval.java:52-54) and carries NO marker for
-- "executed". An APPROVED row that successfully ran executeApprovedRefund is
-- byte-identical to an APPROVED row whose gateway call threw — so a refund that
-- got stuck (approve() committed APPROVED in its own tx, then executeApprovedRefund
-- threw OUTSIDE that tx) cannot be told apart from a completed one, and a retry of
-- approve() throws "not pending".
--
-- This migration adds an execution marker + bounded-retry bookkeeping so a
-- reconciler can re-drive executeApprovedRefund (keyed on the SAME deterministic
-- "refund-approval-<id>" key, which HyperSwitch dedups — B-009) until the gateway
-- confirms success, then stamp executed_at to stop re-driving.
--
-- executed-ness is a NULLABLE COLUMN, deliberately NOT a new status value: adding
-- an EXECUTED status would force relaxing chk_approval_status (V1103:15) and could
-- strand code that switches on the three-state machine. status stays APPROVED.
--
-- Safe on a non-empty DB (baseline-on-migrate:true, application.yml:48): every
-- statement is additive and idempotent. All new columns are nullable or defaulted,
-- so no backfill is needed. Existing pre-B-022 APPROVED refund rows get
-- executed_at = NULL and are (correctly) picked up by the first reconcile cycle:
-- they re-drive the deduped key; an already-refunded one no-ops at the PSP and is
-- then marked.

ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS executed_at          TIMESTAMPTZ;
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS reconcile_attempts   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS next_reconcile_at    TIMESTAMPTZ;
ALTER TABLE pending_approvals ADD COLUMN IF NOT EXISTS last_reconcile_error TEXT;

-- Partial index for the reconciler finder: it always filters executed_at IS NULL,
-- so a partial index keeps it tiny (only the unexecuted tail) and skips every row
-- the common path has already marked done.
CREATE INDEX IF NOT EXISTS idx_approvals_reconcile
    ON pending_approvals (status, action, executed_at)
    WHERE executed_at IS NULL;
