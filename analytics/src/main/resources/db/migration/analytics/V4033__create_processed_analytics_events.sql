-- SEC-18: idempotent analytics rollups. The additive rollup upserts (auth_rate_hourly,
-- revenue_hourly, decline_daily) apply += EXCLUDED, so a Kafka redelivery / DLT replay of the SAME
-- event DOUBLE-COUNTS revenue / auth / decline metrics. This dedup table is keyed by the event's
-- STABLE id + the rollup kind; each consumer saveAndFlush-inserts a marker BEFORE the additive
-- upsert and treats a dup-key as a deterministic NO-OP (skip the upsert) — the L-041 / SEC-10
-- (uq_journal_entries_payment_ref_desc) precedent. Per (event_id, rollup_kind) so an event that
-- legitimately updates SEVERAL distinct rollups still updates each ONCE, and a redelivery updates none.
--
-- NEW TABLE: no pre-flight dedupe needed (unlike V4028/V4023, which retrofit a constraint onto a
-- pre-existing table). The UNIQUE is on the natural key directly.
--
-- NOTE on numbering: all module migration locations share ONE global Flyway history, out-of-order
-- disabled. Global max is V4032 (marketplace payouts); V4031 is gateway webhook_deliveries. V4033 is
-- the analytics leaf, globally ordered. No other V4033 exists.

CREATE TABLE analytics.processed_analytics_events (
    event_id     VARCHAR(128) NOT NULL,
    rollup_kind  VARCHAR(40)  NOT NULL,
    tenant_id    VARCHAR(36)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_processed_analytics_events UNIQUE (event_id, rollup_kind)
);

-- Tenant column present for RLS parity with the other analytics tables (it is tenant-scoped data),
-- but the idempotency UNIQUE is GLOBAL on (event_id, rollup_kind): event_id is globally unique, so a
-- redelivery dedups regardless of tenant binding, and a malicious cross-tenant collision on an
-- opaque evt_ id is not a metric-correctness concern (the rollup write itself is RLS WITH CHECK
-- guarded by tenant). The UNIQUE constraint already provides the natural-key index; no extra index.

-- RLS fail-closed via current_tenant_id() — the V2001 / V3022-corrected helper
-- (current_setting('app.current_tenant_id', true) → NULL when unbound → zero rows, NOT an error).
-- NOT the bare current_setting form V3017-V3020 originally shipped. New table ⇒ no V3022-style
-- retrofit needed. Grants for nexuspay_app / nexuspay_system over the analytics schema auto-inherit
-- from V4020's ALTER DEFAULT PRIVILEGES — no grant migration needed.
ALTER TABLE analytics.processed_analytics_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.processed_analytics_events
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
