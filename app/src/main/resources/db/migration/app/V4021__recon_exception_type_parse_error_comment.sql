-- ============================================================================
-- B-015: document the PARSE_ERROR reconciliation exception type.
--
-- exception_type is a VARCHAR(32) (NOT an enum/check-constrained column), so the
-- new MatchResult.ExceptionType.PARSE_ERROR value persists with no schema change
-- — 'PARSE_ERROR' is 11 chars and fits comfortably. This migration only refreshes
-- the cosmetic COMMENT so the column documentation lists every value the
-- application writes, including the unparseable-settlement-row exceptions
-- introduced by the RFC-4180-correct Stripe CSV parser.
-- ============================================================================

COMMENT ON COLUMN reconciliation_exceptions.exception_type IS
    'AMOUNT_MISMATCH, MISSING_PAYMENT, MISSING_SETTLEMENT, MISSING_LEDGER_ENTRY, FEE_DISCREPANCY, CURRENCY_MISMATCH, DUPLICATE_SETTLEMENT, PARSE_ERROR';
