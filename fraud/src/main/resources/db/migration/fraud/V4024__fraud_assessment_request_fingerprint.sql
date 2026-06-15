-- B-029-hardening (SHOULD_FIX C from B-027b): persist a KEYED request fingerprint on each fraud
-- assessment so the idempotent dedup-hit path can verify that a retried Idempotency-Key actually
-- describes the SAME charge (amount/currency/customer/card-token). Without it, a reused idempotency
-- key on a DIFFERENT charge is served the prior (stale) fraud decision -- a money/data-integrity
-- and security hole. FraudAssessmentService.assess() recomputes the fingerprint on a dedup hit:
--   stored == recomputed  -> short-circuit, return prior decision (existing idempotent behavior)
--   stored != recomputed  -> RE-ASSESS (key reused for a different request: client bug or attack), WARN
--   stored IS NULL (legacy pre-migration row) -> fall back to prior behavior (return prior)
--
-- The value is an HMAC-SHA256 hex digest (64 lowercase hex chars) keyed by a domain-separated key
-- derived from nexuspay.vault.encryption.master-key with label "fraud-request-fingerprint" -- it is
-- ONE-WAY and KEYED, so this column can NEVER be brute-forced/rainbow-tabled back to amount+customer
-- and NEVER contains a raw PAN (the card input is the already-tokenized cardHash, folded through the
-- HMAC regardless). VARCHAR(64) matches HMAC-SHA256 hex width and the device_fingerprints.
-- fingerprint_hash precedent.
--
-- NOTE on numbering: all module migration locations share ONE global Flyway schema history, so this
-- must exceed the current global max (V4023, the fraud idempotency migration); out-of-order is
-- disabled. It is the fraud module's leaf but a globally-ordered version.
--
-- Safe on a NON-EMPTY / upgraded-in-place DB (application.yml baseline-on-migrate:true): the column
-- is NULLABLE with no default, so legacy rows stay NULL (-> the "fall back to prior" branch) and no
-- backfill is attempted. ADD COLUMN IF NOT EXISTS makes the migration idempotent / re-runnable,
-- consistent with V4023. The column lives on the existing fraud_assessments table, which already has
-- ENABLE ROW LEVEL SECURITY + tenant_isolation_fraud_assessments (V3002); ADD COLUMN inherits that
-- policy -- do NOT re-declare RLS.

ALTER TABLE fraud_assessments
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64); -- HMAC-SHA256 hex of the canonical request tuple; NULL = legacy row (pre-B-029-hardening)

COMMENT ON COLUMN fraud_assessments.request_fingerprint IS
    'B-029-hardening: keyed HMAC-SHA256 (hex) of the canonical (amount|currency|customer|cardToken) request tuple, domain-separated label "fraud-request-fingerprint". Used on the idempotent dedup hit to detect idempotency-key reuse across a DIFFERENT charge. One-way + keyed: never reversible, never a raw PAN. NULL on legacy rows.';
