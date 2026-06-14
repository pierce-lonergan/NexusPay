-- B-027b: idempotent fraud assessment. A retried Idempotency-Key (network retry, billing dunning
-- re-run, Temporal activity retry) must produce exactly ONE assessment row + ONE event + ONE
-- velocity increment per logical (tenant, idempotency-key). FraudAssessmentService.assess dedups
-- via a read-through on (tenant_id, payment_id); this UNIQUE index is the concurrency backstop --
-- a truly-concurrent retry that slips past the read is rejected at insert (the loser returns the
-- deterministic result instead of writing a duplicate row / publishing a second event).
--
-- payment_id carries the idempotency key on every gate path (PreAuthorizationGate sets
-- ref = paymentRef = idempotency key). The pre-existing idx_fraud_assessments_payment (V3002) is a
-- non-unique lookup index; this adds the (tenant, key) uniqueness constraint. RLS already scopes
-- the table by tenant -- the unique index is global per (tenant_id, payment_id).
--
-- NOTE on numbering: all module migration locations share ONE global Flyway schema history, so
-- this must exceed the current global max (V4022 from the payment B-029 migration); out-of-order
-- is disabled. It is the fraud module's leaf but a globally-ordered version.
--
-- MUST_FIX 1 (pre-flight dedupe): the table predates this constraint, so a DB upgraded in place
-- (application.yml has baseline-on-migrate: true -> this runs on EXISTING databases) can already
-- hold duplicate (tenant_id, payment_id) rows -- exactly the pre-B-027b state the constraint is
-- meant to forbid. CREATE UNIQUE INDEX does NOT dedupe data; Postgres would reject the index build
-- and Flyway would abort the WHOLE shared migration set, bricking boot. So FIRST collapse any
-- pre-existing duplicates, keeping exactly ONE row per group (the latest assessment), THEN create
-- the index. The whole migration is idempotent / safe to re-run: the DELETE is a no-op once the
-- table is clean, and the index uses IF NOT EXISTS.

-- Collapse pre-existing duplicates: keep the LATEST row per (tenant_id, payment_id), delete the
-- rest. Tiebreak: when assessed_at is equal (or both NULL), keep the larger id so EXACTLY one row
-- survives per group and the delete is fully deterministic. The join predicate is anchored on
-- a.tenant_id = b.tenant_id AND a.payment_id = b.payment_id, so a row can only be deleted by
-- ANOTHER row in the SAME (tenant, payment) group -- it can never delete across different groups.
-- A singleton group has no matching b (the id-inequality / timestamp ordering can never be
-- satisfied against itself), so unique rows are never touched.
DELETE FROM fraud_assessments a
      USING fraud_assessments b
      WHERE a.tenant_id  = b.tenant_id
        AND a.payment_id = b.payment_id
        AND a.id <> b.id
        AND (
              a.assessed_at < b.assessed_at
           OR (a.assessed_at = b.assessed_at AND a.id < b.id)
           OR (a.assessed_at IS NULL AND b.assessed_at IS NOT NULL)
           OR (a.assessed_at IS NULL AND b.assessed_at IS NULL AND a.id < b.id)
        );

-- Now the table is guaranteed to hold at most one row per (tenant_id, payment_id); the unique
-- index builds cleanly on both fresh and upgraded-in-place databases.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fraud_assessments_tenant_idem
    ON fraud_assessments (tenant_id, payment_id);
