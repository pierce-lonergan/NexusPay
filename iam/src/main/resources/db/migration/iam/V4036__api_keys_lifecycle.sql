-- DX-5c: API key lifecycle hardening — expiry, last-used observability, rotation overlap.
--
-- Flyway version V4036 (NOT V4035). All module migrations share ONE global Flyway
-- history with out-of-order DISABLED (B-011 / L-023): two files with the same Vnnnn
-- across any modules is a boot failure. V4035 (app/...subscriptions_is_live) is the
-- current global max, so V4036 is the next free GLOBAL version. The iam dir already
-- mixes V1101 and V40xx numbers, so a V40xx number here is correct.
--
-- All three columns are NULLABLE and additive, so this is safe on a non-empty DB
-- (baseline-on-migrate:true): every existing api_keys row keeps working unchanged.
--   * expires_at   — absolute expiry deadline. NULL = NEVER expires (back-compat:
--                    every pre-DX-5c row keeps its perpetual lifetime). authenticate()
--                    fails CLOSED at-or-after this instant (same terminal 401 as an
--                    invalid key — no oracle). Same SQL type as created_at (TIMESTAMP,
--                    mapped to java.time.Instant) so ddl-auto=validate still passes.
--   * last_used_at — observability only (fail-OPEN). Best-effort, throttled stamp set
--                    on a successful authenticate; a write failure NEVER denies a valid
--                    key. NULL = never authenticated since this column was added.
--   * replaced_by  — set to the NEW key's id when this key is rotated (rotate-with-
--                    overlap). A non-NULL value marks a superseded key that must not be
--                    re-rotated. VARCHAR(64) matches the api_keys.id column width.
--
-- No index needed: expiry/last-used/replaced-by checks all happen on the already-matched
-- row (matched by key_prefix + bcrypt, then by id), never as a standalone lookup.

ALTER TABLE api_keys ADD COLUMN expires_at   TIMESTAMP NULL;
ALTER TABLE api_keys ADD COLUMN last_used_at TIMESTAMP NULL;
ALTER TABLE api_keys ADD COLUMN replaced_by  VARCHAR(64) NULL;
