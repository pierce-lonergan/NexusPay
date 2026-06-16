-- SEC-20: idempotent split payments. A retried create-split request (network retry, PSP redelivery,
-- workflow activity retry) must produce exactly ONE split_payments row + its child split_rules /
-- platform_fees per logical (tenant_id, payment_id). SplitPaymentService.createSplitPayment now dedups
-- via a read-through on (tenant_id, payment_id); this UNIQUE index is the concurrency backstop -- a
-- truly-concurrent retry that slips past the read is rejected at insert (the loser re-fetches and
-- returns the existing split instead of writing a duplicate row tree).
--
-- The pre-existing idx_split_payments_payment (V4002) is a non-unique lookup index; this adds the
-- (tenant_id, payment_id) uniqueness constraint. RLS already scopes split_payments by tenant (V4002);
-- this migration runs under Flyway's migration role and does NOT add or alter any RLS policy.
--
-- NOTE on numbering: all module migration locations share ONE global Flyway schema history, so this
-- must exceed the current global max (V4033 from the analytics processed-events migration); out-of-order
-- is disabled. It is the marketplace module's leaf but a globally-ordered version (V4034).
--
-- Pre-flight dedupe (mirrors fraud V4023): the table predates this constraint, so a DB upgraded in
-- place (application.yml has baseline-on-migrate: true -> this runs on EXISTING databases) can already
-- hold duplicate (tenant_id, payment_id) rows -- exactly the pre-SEC-20 state the constraint forbids.
-- CREATE UNIQUE INDEX does NOT dedupe data; Postgres would reject the index build and Flyway would abort
-- the WHOLE shared migration set, bricking boot. So FIRST collapse any pre-existing duplicates, keeping
-- exactly ONE split per group (the latest), THEN create the index. Unlike fraud (which had no child
-- tables), split_payments has FK children (split_rules, platform_fees both REFERENCES split_payments(id)
-- in V4002), so the orphaned children of the to-be-deleted parents MUST be removed FIRST or the parent
-- DELETE violates the FK. The whole migration is idempotent / safe to re-run: once the table is clean
-- the deletes are no-ops and the index uses IF NOT EXISTS.

-- Identify the split_payments rows that are NOT the survivor of their (tenant_id, payment_id) group.
-- Survivor = latest created_at, tie-broken on the larger id so EXACTLY one row per group survives and
-- the selection is fully deterministic. The self-join is anchored on a.tenant_id = b.tenant_id AND
-- a.payment_id = b.payment_id, so a row can only be marked-for-delete by ANOTHER row in the SAME group --
-- never across groups. A singleton group has no matching b (the ordering can never be satisfied against
-- itself), so unique rows are never touched.
--
-- Step 1: delete the CHILD rows of every to-be-deleted parent first (FK: split_rules.split_payment_id
-- and platform_fees.split_payment_id both REFERENCES split_payments(id)). Same loser-selection predicate
-- as the parent delete below, expressed as a subquery over split_payments.
DELETE FROM split_rules
      WHERE split_payment_id IN (
          SELECT a.id
            FROM split_payments a
            JOIN split_payments b
              ON a.tenant_id  = b.tenant_id
             AND a.payment_id = b.payment_id
             AND a.id <> b.id
           WHERE a.created_at <  b.created_at
              OR (a.created_at =  b.created_at AND a.id < b.id)
      );

DELETE FROM platform_fees
      WHERE split_payment_id IN (
          SELECT a.id
            FROM split_payments a
            JOIN split_payments b
              ON a.tenant_id  = b.tenant_id
             AND a.payment_id = b.payment_id
             AND a.id <> b.id
           WHERE a.created_at <  b.created_at
              OR (a.created_at =  b.created_at AND a.id < b.id)
      );

-- Step 2: now that their children are gone, delete the loser parents. Same predicate, expressed as a
-- self-join (USING) so the survivor (latest created_at, larger id on a tie) is the only row left per
-- (tenant_id, payment_id) group.
DELETE FROM split_payments a
      USING split_payments b
      WHERE a.tenant_id  = b.tenant_id
        AND a.payment_id = b.payment_id
        AND a.id <> b.id
        AND (
              a.created_at < b.created_at
           OR (a.created_at = b.created_at AND a.id < b.id)
        );

-- Now the table is guaranteed to hold at most one row per (tenant_id, payment_id); the unique index
-- builds cleanly on both fresh and upgraded-in-place databases.
CREATE UNIQUE INDEX IF NOT EXISTS uq_split_payments_tenant_payment
    ON split_payments (tenant_id, payment_id);
