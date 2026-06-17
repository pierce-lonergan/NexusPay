-- =============================================================================
-- V4035: DX-5a — durable test/live mode on subscriptions (MONEY-SAFETY)
--
-- Close a money-safety hole: the @Scheduled @SystemTransactional billing jobs
-- (RenewalScheduler.processRenewals / DunningService.processPendingAttempts)
-- charge on a SYSTEM thread where the request-scoped PaymentMode ThreadLocal is
-- UNSET. With no durable mode, a renewal/dunning charge for a TEST-mode
-- subscription would route to the REAL PSP (the gateway resolves an UNSET system
-- thread to LIVE). We persist the subscription's mode at creation so billing can
-- thread it into the gateway CallContext and route a test subscription's
-- recurring charge to the in-memory mock — never HyperSwitch.
--
-- is_live = true   -> LIVE subscription (charges route to the real PSP)
-- is_live = false  -> TEST subscription (charges route to the deterministic mock)
--
-- DEFAULT true: existing rows are treated as LIVE — the safe-for-existing-prod
-- default (a backfilled NULL/unknown row must NOT silently become test and stop
-- collecting real money). A subscription created in TEST mode AFTER this stamps
-- is_live=false explicitly from the creating caller's server-derived key mode.
--
-- The subscriptions table lives in the default (public) schema, unqualified, as
-- created in V2005__create_billing_schema.sql. No RLS change.
-- =============================================================================

ALTER TABLE subscriptions
    ADD COLUMN is_live BOOLEAN NOT NULL DEFAULT true;
