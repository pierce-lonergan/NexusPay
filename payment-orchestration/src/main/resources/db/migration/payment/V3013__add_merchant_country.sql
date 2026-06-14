-- B-025: server-authoritative merchant (destination) country for the OFAC/cross-border screen.
--
-- The sanctions screen must NOT trust client-supplied source/destination countries. The only
-- server-authoritative geography that exists today is the tenant's own configuration, so the
-- destination country is derived from this column. It is intentionally NULLABLE: a tenant with
-- no configured country is "unknown", which the gate fails CLOSED to REVIEW/EDD (never ALLOW)
-- on a cross-border-capable flow. Backfill real merchant countries BEFORE enabling
-- nexuspay.fx.compliance.unknown-geo-review-enabled in an environment, or nearly all traffic
-- would REVIEW (documented rollout sequencing in the PR).
--
-- ISO 3166-1 alpha-2 (VARCHAR(2)). RLS / grant pattern mirrors V3007.

ALTER TABLE merchant_currency_prefs
    ADD COLUMN IF NOT EXISTS merchant_country VARCHAR(2);

COMMENT ON COLUMN merchant_currency_prefs.merchant_country IS
    'Server-authoritative ISO-2 merchant/destination country for the OFAC sanctions screen (B-025). '
    'NULL = unknown → screen fails closed to REVIEW on cross-border-capable flows.';

-- Seed the development "default" tenant with a sensible value so local/dev traffic does not
-- universally REVIEW. Production merchants are backfilled separately before the flip is enabled.
UPDATE merchant_currency_prefs SET merchant_country = 'US' WHERE tenant_id = 'default' AND merchant_country IS NULL;
