# NexusPay

A source-available, enterprise payment platform built on
[HyperSwitch](https://github.com/juspay/hyperswitch) — the operations layer that
turns payment orchestration into a CFO-ready system: full payment lifecycle,
refunds with maker-checker approval, a double-entry ledger, fraud and sanctions
screening, dispute management, subscription billing, and signed-and-retried
webhooks, with a first-class **test mode** that never moves real money.

## Key features

- **Payment lifecycle** — create, confirm, capture, cancel, and retrieve
  payments (`/v1/payments`), with idempotent creates and a server-derived
  `mode` (test/live).
- **Refunds with maker-checker approval** — refunds within threshold are created
  directly (201); refunds above the threshold return a pending approval (202).
- **Double-entry ledger** — every money movement posts balanced journal entries;
  queryable accounts and journal entries (`/v1/ledger`).
- **Fraud & sanctions screening** — fraud rules engine + FRM integration, with
  cross-border and sanctions-geography checks at the payment boundary.
- **Dispute management** — dispute lifecycle, evidence collection, and chargeback
  ledger postings.
- **Subscription billing** — multi-pricing models, smart-retry dunning, invoicing
  and proration.
- **Signed, retried webhooks** — canonical events, HMAC-SHA256 signatures,
  exponential-backoff retries, a dead-letter queue, admin replay, and secret
  rotation. See [docs/WEBHOOKS.md](docs/WEBHOOKS.md).
- **Test mode** — `sk_test_` keys route every payment to an in-process mock with
  zero network I/O; a test key can never reach a real processor (enforced by an
  architecture test).

## SDKs

| Package | Purpose |
|---|---|
| [`@nexuspay/js`](checkout-sdk/packages/js) | Browser checkout SDK (session load, tokenize, confirm, 3DS) |
| [`@nexuspay/react`](checkout-sdk/packages/react) | React bindings — `NexusPayProvider`, `useConfirmPayment`, `PaymentElement` |
| [`@nexuspay/node`](checkout-sdk/packages/node) | Zero-dependency server SDK (typed client + `verifyWebhook`/`constructEvent`) |

## Quickstart

Spin up the lite stack, seed a test key, create a payment, receive a webhook
(all in **test mode** — no real money moves):

```bash
# 1. Start the lite infra (Postgres, Kafka, Valkey, Keycloak)
docker compose -f docker/docker-compose.lite.yml up -d

# 2. Boot the app (test-mode, no HyperSwitch/Vault needed)
SPRING_PROFILES_ACTIVE=local SPRING_CLOUD_VAULT_ENABLED=false \
  KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :app:bootRun

# 3. Seed an sk_test_ key + a webhook endpoint (prints both secrets once)
bash scripts/dev/seed-local.sh

# 4. Create a payment (routes to the in-process mock → emits payment.succeeded)
curl -X POST http://localhost:8090/v1/payments \
  -H "Authorization: Bearer sk_test_xxx" \
  -H "Content-Type: application/json" \
  -d '{"amount":1000,"currency":"USD"}'
```

Full walkthrough (cURL + Node) in [docs/INTEGRATION.md](docs/INTEGRATION.md);
the local sandbox runbook is in [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md).

**Tech stack**: Java 21, Spring Boot 3.2, Spring Modulith, PostgreSQL 16, Kafka
(KRaft), Valkey 8, Keycloak 26, Resilience4j, Temporal, Prometheus, Grafana,
HashiCorp Vault, Debezium.

## Documentation

- [Integration guide](docs/INTEGRATION.md) — end-to-end quickstart in cURL and Node, plus the merchant/platform responsibility matrix.
- [Webhooks](docs/WEBHOOKS.md) — event taxonomy, canonical envelope, signature verification, retries/DLQ/replay.
- [Local dev sandbox](docs/LOCAL_DEV.md) — the lite stack, seeding, and the SEC-4b localhost caveat.
- [OpenAPI spec](docs/api/openapi.yaml) — curated merchant surface (the live `GET /v1/api-docs` is the complete authoritative spec).
- [SDK publishing](checkout-sdk/PUBLISHING.md) — release runbook for the three npm packages.
- [Architecture overview](docs/architecture/system-overview.md) — modules, data flows, decision records.

## Authentication

Requests authenticate with one of three `Authorization: Bearer <token>` schemes,
all producing a uniform principal:

1. **Merchant API keys** (`sk_test_` / `sk_live_`) — programmatic merchant access.
2. **Session tokens** (short-lived JWT) — the browser checkout SDK (`/v1/checkout/**`).
3. **Keycloak OIDC** (JWT) — the dashboard / back office.

```bash
curl -H "Authorization: Bearer sk_test_xxx" http://localhost:8090/v1/payments
```

## Security posture

- **`sk_` bearer auth** with a server-derived `mode`/`livemode` (never trusted
  from the request body).
- **HMAC-SHA256 signed webhooks** with a per-attempt secret read (rotation-safe)
  and a hardened replay window over the signed `created` field.
- **SSRF guard** on webhook targets (SEC-4b): public-HTTPS only, with delivery-time
  re-validation and IP-pinned connections (anti-DNS-rebinding).
- **Row-level multi-tenancy** — every query is tenant-scoped; cross-tenant ids
  return 404/204 with no existence oracle.
- **Test-mode mock** — `sk_test_` payments are fully in-process and never reach a
  real processor (architecture-test enforced).

## License

**Source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE).**
Free for noncommercial use (personal, research, education, evaluation, nonprofits).

**Commercial use requires a separate commercial license** (fees and/or royalties) —
see [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md) or contact
Pierce Lonergan &lt;lonerganpierce@gmail.com&gt;.

The browser checkout SDKs under `checkout-sdk/packages/*` (`@nexuspay/js`,
`@nexuspay/react`) are separately licensed **MIT** so they can be embedded freely.

💜 Support development via [GitHub Sponsors](https://github.com/sponsors/pierce-lonergan).
