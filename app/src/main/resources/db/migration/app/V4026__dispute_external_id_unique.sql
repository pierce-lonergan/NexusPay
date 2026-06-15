-- =============================================================================
-- V4026: Dispute external-id idempotency backstop (SEC-BATCH-2, audit SEC-01 / B-001)
--
-- The dispute webhook (POST /internal/webhooks/disputes) drives a money-moving
-- chargeback reserve via DisputeLifecycleService.openDispute. To make that
-- exactly-once under PSP retry/replay AND a concurrent first-delivery race, we
-- add a UNIQUE constraint on (tenant_id, external_dispute_id) as the
-- AUTHORITATIVE in-transaction backstop (enforced at COMMIT inside the same
-- @Transactional as the ledger posting — a rolled-back tx leaves NO durable
-- suppression mark, so a legitimate redelivery stays reprocessable; this avoids
-- the B-015 pre-commit-mark-vs-rollback defect that a bare Valkey SET-NX would
-- reintroduce). The service-level lookup-then-no-op is the primary guard; this
-- constraint is the race backstop.
--
-- L-041: a UNIQUE INDEX on dirty data fails and BRICKS baseline-on-migrate, so
-- we first ASSERT no existing (tenant_id, external_dispute_id) duplicate exists.
-- Postgres treats multiple NULLs as DISTINCT, so pre-existing rows with a NULL
-- external_dispute_id (the column has always been nullable, no prior unique) are
-- fine and do NOT collide — only non-NULL duplicates would, and there should be
-- none on a healthy table.
-- =============================================================================

-- Guard: refuse to proceed (and surface a loud, actionable error) if dirty data
-- would make the UNIQUE constraint creation fail. NULL external ids are ignored
-- by the GROUP BY HAVING below (NULLs do not group into a COUNT(*) > 1 bucket per
-- the WHERE filter), matching the Postgres multi-NULL-distinct semantics.
DO $$
DECLARE
    dup_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO dup_count
    FROM (
        SELECT tenant_id, external_dispute_id
        FROM disputes
        WHERE external_dispute_id IS NOT NULL
        GROUP BY tenant_id, external_dispute_id
        HAVING COUNT(*) > 1
    ) dups;

    IF dup_count > 0 THEN
        RAISE EXCEPTION
            'V4026: cannot add UNIQUE(tenant_id, external_dispute_id) — % duplicate (tenant_id, external_dispute_id) group(s) exist in disputes. Pre-dedup these rows before migrating.',
            dup_count;
    END IF;
END $$;

-- Authoritative idempotency / replay backstop for the dispute webhook.
ALTER TABLE disputes
    ADD CONSTRAINT uq_disputes_tenant_external UNIQUE (tenant_id, external_dispute_id);

-- Lookup index for the openDispute idempotency no-op
-- (findByTenantIdAndExternalDisputeId). The UNIQUE constraint above already
-- creates a (tenant_id, external_dispute_id) index, but an explicit index on
-- external_dispute_id alone supports external-id-only lookups.
CREATE INDEX idx_disputes_external_id ON disputes(external_dispute_id);
