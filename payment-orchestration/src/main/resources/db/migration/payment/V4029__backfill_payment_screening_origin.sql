-- SEC-07 / SEC-09 availability backfill (perpetua/SEC-batch-1b).
--
-- B-029 (committed 2026-06-14) introduced payment_screening_origin (V4022) but shipped NO backfill.
-- SEC-batch-1b then turned that store into a HARD authority:
--   * SEC-07 — ScreeningOriginService.assertOwnedBy() FAILS CLOSED (404) for get/capture/cancel/
--     confirm/refund when no origin row exists. Every payment created BEFORE V4022 (the entire
--     in-flight population: authorized-but-uncaptured intents, anything refundable/voidable) has no
--     row, so its LEGITIMATE owner is 404'd — stranded revenue (uncapturable authorizations) and
--     failed refunds on old payments.
--   * SEC-09 — HyperSwitchWebhookController stamps the outbox event's tenant from this store, falling
--     back to "default" when absent; the webhook consumer fans out ONLY to the owning tenant's
--     endpoints. Pre-V4022 payments keep emitting NEW lifecycle events (capture/refund/dispute) that
--     all stamp "default" forever, so merchants silently stop receiving webhooks for older active
--     payments — NOT a transient gap the 7-day outbox retention clears.
--
-- This migration is finding fix option (a): backfill payment_screening_origin (tenant_id) from the
-- authoritative, server-owned records for every pre-existing payment that has NO origin row yet, so
-- the legitimate owner is recognized again and webhook fan-out resumes for the real tenant.
--
-- AUTHORITATIVE SOURCES (same database — all leaf locations share one Flyway history / one DB):
--   1. event_outbox(aggregate_id, tenant_id) — PRIMARY. aggregate_id IS the gateway payment id, and
--      tenant_id is stamped at create/authorize time from the TRUSTED principal (PaymentController /
--      PaymentActivitiesImpl / GatedPaymentGateway), so it covers authorized-but-uncaptured intents,
--      not just captured ones.
--   2. journal_entries(payment_reference, tenant_id) — SECONDARY, fills any payment that has a ledger
--      row but (for whatever reason) no usable outbox tenant.
-- Rows whose only evidence is tenant_id='default' are NOT backfilled: writing a "default" origin would
-- be indistinguishable from a real default-tenant payment and could mask a genuine mismatch. Leaving
-- them absent keeps the SEC-07 fail-closed posture for the (tiny, un-attributable) residue while
-- restoring every payment that has a real, server-trusted tenant on record.
--
-- screening_mode := 'INTERACTIVE' for all backfilled rows: we cannot recover the original rail for a
-- legacy intent, so we record the STRICTEST rail. This matches ScreeningOriginService.parseMode()'s
-- own fallback and is the safe direction (a legacy payment can never be re-classified into a softer
-- server rail by this backfill).
--
-- SAFETY:
--   * Idempotent: ON CONFLICT (gateway_payment_id) DO NOTHING never overwrites a real B-029 origin
--     written after V4022, and the migration is a no-op on re-run.
--   * Runs as the Flyway owner role, which BYPASSes RLS until the human-gated cutover (same model as
--     the V4023 fraud and V4028 ledger cross-tenant data migrations), so it sees every tenant's rows.
--   * created_at is set to the source record's own create time so the backfilled origin does not look
--     newer than the payment it describes.
--
-- NUMBERING: Flyway versions are GLOBAL across module leaf locations (see V4022's header); V4028 is the
-- current max, so this is V4029.

-- 1) PRIMARY: backfill from event_outbox, one row per payment, preferring the EARLIEST real-tenant
--    (non-'default', non-null, non-blank) outbox row for that aggregate_id.
--    Both 'Payment' AND 'Refund' aggregates are keyed by the gateway payment id (HyperSwitch refund
--    events set aggregate_id = payment_id), so a payment that only has refund events on record is still
--    recovered. The DISTINCT ON + earliest-created ordering picks one trusted tenant per payment id.
INSERT INTO payment_screening_origin (gateway_payment_id, tenant_id, screening_mode, created_at)
SELECT src.aggregate_id,
       src.tenant_id,
       'INTERACTIVE',
       -- event_outbox.created_at is TIMESTAMP WITHOUT TIME ZONE (app writes UTC); make the cast to the
       -- target timestamptz column explicit and session-timezone-independent.
       src.created_at AT TIME ZONE 'UTC'
FROM (
    SELECT DISTINCT ON (o.aggregate_id)
           o.aggregate_id,
           o.tenant_id,
           o.created_at
    FROM event_outbox o
    WHERE o.aggregate_type IN ('Payment', 'Refund')
      AND o.tenant_id IS NOT NULL
      AND o.tenant_id <> ''
      AND o.tenant_id <> 'default'
    ORDER BY o.aggregate_id, o.created_at ASC
) AS src
ON CONFLICT (gateway_payment_id) DO NOTHING;

-- 2) SECONDARY: any payment still missing an origin but with a real-tenant ledger journal entry.
--    journal_entries lives in the same database; payment_reference IS the gateway payment id.
INSERT INTO payment_screening_origin (gateway_payment_id, tenant_id, screening_mode, created_at)
SELECT src.payment_reference,
       src.tenant_id,
       'INTERACTIVE',
       -- journal_entries.posted_at is TIMESTAMP WITHOUT TIME ZONE (app writes UTC); explicit UTC cast.
       src.posted_at AT TIME ZONE 'UTC'
FROM (
    SELECT DISTINCT ON (j.payment_reference)
           j.payment_reference,
           j.tenant_id,
           j.posted_at
    FROM journal_entries j
    WHERE j.payment_reference IS NOT NULL
      AND j.payment_reference <> ''
      AND j.tenant_id IS NOT NULL
      AND j.tenant_id <> ''
      AND j.tenant_id <> 'default'
    ORDER BY j.payment_reference, j.posted_at ASC
) AS src
ON CONFLICT (gateway_payment_id) DO NOTHING;
