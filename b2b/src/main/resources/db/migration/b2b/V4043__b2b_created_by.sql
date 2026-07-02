-- V4043: GAP-068 maker-checker creator stamping. vendor_payments/purchase_orders record the
-- authenticated principal that CREATED them so the b2b approval review path can enforce
-- creator != approver FAIL-CLOSED (B2bApprovalService). Nullable: legacy rows have no recorded
-- creator — for those, only the always-on requester != reviewer check applies (documented in the
-- domain javadoc; the fail-closed scope is explicit).
--
-- NO new table => NO new RLS policy needed: the reused pending_approvals table already carries the
-- dormant-RLS idiom (V1103/V2001/V4025), and a column-add does not change the tenant-isolation
-- posture of vendor_payments/purchase_orders.
--
-- NUMBERING: Flyway versions are GLOBAL across all leaf locations (out-of-order DISABLED). V4042
-- (payment-orchestration test_clocks) is the current global max, so this is V4043. Lives in the
-- `classpath:db/migration/b2b` leaf (the OWNING module's dir — the columns are on b2b tables).

ALTER TABLE vendor_payments ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
