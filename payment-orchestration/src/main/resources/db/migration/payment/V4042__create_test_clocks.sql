-- V4042: TEST CLOCK (critique v3 F5 / GAP-078). A per-tenant FROZEN INSTANT consulted ONLY on the
-- mock/test rail to make the createdAt stamped on TEST-created payment/refund artifacts deterministic.
-- HONEST SCOPE: this controls ONLY that creation timestamp (and, via the GAP-076 projection inheriting
-- created_at from the response, the GET /v1/payments|/v1/refunds list ordering). It does NOT control
-- mandate expiry, idempotency-key TTL, webhook retry/backoff, api-key expiry, projection updated_at, or
-- ANY live-rail behavior.
-- NO livemode column: the clock is keyed by tenant and read ONLY on the mock rail (a live charge never
-- consults it -- GatedPaymentGateway only re-stamps inside its routeToMock branches), so a per-mode key is
-- unnecessary. Row ABSENT = real time (Instant.now()).
-- NUMBERING: Flyway versions are GLOBAL across all leaf locations (out-of-order DISABLED). V4041 is the
-- current global max, so this is V4042. Lives in the `classpath:db/migration/payment` leaf.

CREATE TABLE IF NOT EXISTS test_clocks (
    tenant_id   VARCHAR(64)  PRIMARY KEY,
    fixed_at    TIMESTAMP WITH TIME ZONE NOT NULL,   -- the frozen instant; row absent = real time
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tenant isolation (RLS), dialect identical to V4040/V4041 (current_tenant_id() from V2001).
-- DORMANT by default (app connects as owner, RLS bypassed); the rls-enforce profile activates it.
ALTER TABLE test_clocks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_test_clocks ON test_clocks
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());

-- (PK is tenant_id so no separate idx_ needed; the by-id read is the PK lookup.)
