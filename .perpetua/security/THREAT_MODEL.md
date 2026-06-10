# Threat Model — NexusPay          (reviewed: 2026-06-09, every deep audit)

STRIDE-lite. This is a multi-tenant payments platform; the crown jewels are
money movement, card data (PAN), and tenant isolation.

ASSETS
- Stored PANs + tokens (vault) — PCI scope.
- The ledger (source of truth for money owed/held) — integrity is paramount.
- API keys (sk_live_/sk_test_), session tokens, webhook secrets, vault master key.
- Tenant data isolation (one merchant must never read/act on another's data).
- Availability of the payment + outbox path.

TRUST BOUNDARIES
- Internet → gateway-api (REST; API-key or Keycloak JWT auth).
- HyperSwitch → payment webhook (HMAC-signed) and PSP settlement files.
- App → PostgreSQL (RLS boundary), Kafka, Valkey, Vault, HyperSwitch.
- App ↔ FRM providers (Sift/Signifyd), card networks, OFAC/ECB feeds.

ENTRY POINTS
- `/v1/**` REST (payments, refunds, ledger, fraud, fx, vault, marketplace, b2b,
  approvals, api-keys, webhook-endpoints).
- Payment + dispute webhooks. Settlement file ingestion. Kafka consumers.
- Env/config (secrets), Flyway migrations, dependency install scripts.

ABUSE CASES (top, STRIDE-lite)
- **Tamper/Elevation** — cross-tenant read/write because RLS is inert at runtime
  (SET LOCAL outside tx + app runs as table owner). OPEN → B-002. HIGH.
- **Spoofing** — webhook signature forgery: was timing-leaky (FIXED, L-007);
  dispute webhook has NO signature/idempotency (OPEN → audit, BACKLOG candidate).
- **Info-leak** — PAN recovery from unkeyed fingerprint + 8-digit BIN (FIXED,
  L-009); idempotency cross-merchant response leak (FIXED, L-010).
- **Tamper (money)** — double-billing via lockless schedulers (OPEN → B-001);
  duplicate refund via maker-checker race (OPEN → B-009); cross-currency
  "balanced" entries (FIXED, L-001).
- **Elevation/RCE-adjacent** — dependency install scripts; Kafka JSON
  deserialization (ErrorHandlingDeserializer present); prompt-injection via repo/
  issue/web content (agent self-defense, §15.5).
- **DoS** — unbounded pagination (`limit=0` div-by-zero, dispute repo), regex/
  allocation in parsers; settlement file parsing.
- **Repudiation** — audit log integrity; gaps where ledger side-effects fire
  without a confirmed state transition (dispute expire FIXED, L-? expire guard).

DANGER ZONES (mirrors MAP.md + ratchets.risk_map)
ledger/**, vault/**, iam/** (auth+RLS), payment webhook+HMAC, payment/fx/**,
*Idempotency*, marketplace Split/Payout, billing Dunning/Scheduler, db/migration/**.

ACCEPTED RISKS (explicit, with revisit date)
- Local-dev secrets defaulted in source — ACCEPTED only for `local` profile;
  must fail-fast elsewhere (B-004). Revisit: next deep audit.
- Many provider adapters are documented stubs (KYC, payout, card-issuing, HSM,
  network tokens) — accepted per docs/gaps/known-gaps.md; not in scope as bugs.
