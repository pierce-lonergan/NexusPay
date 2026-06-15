-- SEC-BATCH-3 / SEC-04 / audit B-004 (HIGH, PCI-DSS): purge legacy unencrypted-PAN rows.
--
-- BACKGROUND
-- The SDK tokenize path historically stored token_data as base64(PAN) — a REVERSIBLE,
-- unencrypted encoding of the live card number — with encryption_key_id IS NULL. A DB
-- dump, backup, read-only SQLi, or insider read could base64-decode token_data straight
-- back to the cleartext PAN: a direct PCI-DSS violation (cardholder data recoverable at
-- rest). The code fix (TokenizationService now AES-256-GCM-encrypts token_data via the
-- EncryptionPort and sets encryption_key_id) stops NEW rows from being written this way,
-- but a code fix does NOT retro-clean data already stored (L-041): pre-existing rows in a
-- non-empty / staging / prod DB still hold the recoverable PAN.
--
-- WHY PURGE, NOT RE-ENCRYPT
-- These legacy rows cannot be safely re-encrypted in raw SQL: we have no
-- master-key-encryption context here, and recovering the PAN to re-encrypt it would itself
-- momentarily handle cleartext cardholder data inside the migration. The only PCI-safe
-- action is to remove them. This is safe in practice because payment_tokens are short-lived
-- single-use tokens (15-minute expiry — see this table's header + TokenizationService
-- singleUseTokenExpiry default PT15M), so any pre-existing legacy row is already expired and
-- unusable. There is intentionally NO rollback for deleted PAN rows — by design they must not
-- be recoverable.
--
-- IDEMPOTENT / SAFE ON EMPTY DB
-- A row written by the secure path always sets encryption_key_id (non-null) for card-bearing
-- token_data, so the predicate below matches ONLY legacy unencrypted rows. On a fresh/empty DB
-- this deletes 0 rows. Running it twice is a no-op the second time.
--
-- NOTE: we deliberately do NOT add a NOT NULL constraint on encryption_key_id. Non-card token
-- types (apple_pay / google_pay / bank_redirect / bnpl) legitimately carry network tokens or
-- no secret at all and may have a null key id; the null-key rejection for CARD tokens is
-- enforced in the service/test layer, not as a blanket column constraint that would break
-- wallet tokens.

DELETE FROM payment_tokens
WHERE encryption_key_id IS NULL
  AND token_data IS NOT NULL;
