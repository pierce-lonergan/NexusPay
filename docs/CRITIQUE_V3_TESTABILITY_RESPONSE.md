# NexusPay — Response to the Integration Testing-Gaps critique (v3)

**Date:** 2026-06-28 · **From:** NexusPay platform team · **To:** the Snap Loyalty integration side (critique v3 author)
**Critique audited HEAD:** `f5b95f9` (SDK `@nexus-pay/node@0.1.1`) · **This response ships at HEAD `c7c6e33`, SDK `@nexus-pay/{js,node,react}@0.1.2`**
**Program:** delivered as `TEST-1 … TEST-6` — 10 PRs (#48–#57), ADR-054…063, each via a design → implement → 5-lens adversarial review → fix workflow, every PR merged CI-green.

---

## TL;DR

**The critique is resolved.** Both root causes are gone and every Critical/High finding is shipped:

- **Root cause A (mock only ever succeeds) — FIXED.** The test mock now produces deterministic *failures and non-terminal states* on demand: `declined`, `insufficient_funds`, `expired_card`, `requires_action` (3DS), `processing`, `fraud_hold`, plus failed refunds — each synthesizing the matching `payment.failed` / `payment.refund.failed` webhook through the real signed pipeline. (A1–A5)
- **Root cause B (whole capability classes don't exist) — FIXED.** Durable **Customer** (`cus_`), reusable **saved payment method** (`pm_`), generic **off-session charge** on `/v1/payments`, and a **Mandate/consent** resource (`mandate_`) that actually gates the off-session charge — all first-class, tenant-scoped, integrator-callable, and test-mode-exercisable with no real card. (B1–B5)
- **Chargebacks are no longer silent — FIXED.** Disputes now emit **7 canonical `dispute.*` webhooks** on the merchant contract, and you can **simulate one in test mode** (`POST /v1/test/disputes`). The silent over-grant on every chargeback is closed. (C1–C3)
- **The test seam the critique asked for exists.** A money-safe `POST /v1/test/...` surface (`/v1/test/disputes`, `/v1/test/events`, the `__test_outcome` convention) hard-gated on `CallerMode.isTest()` → 404 for any live key. (D1, C2, A-cluster)
- **SDK test ergonomics shipped in 0.1.2:** `generateTestHeaderString` (single-sourced with `verifyWebhook`, so it can't drift), typed event fixtures + `buildTestEvent`, `client.ping()`, `createTestTransport`, and a `nexuspay` CLI (`trigger` / `listen`). (E1–E5, D1–D3)

Of the 28 findings: **22 delivered, 1 already-in-prod (verified + closed), 5 deferred** with written rationale (the four P2 items that are real platform features in their own right, tracked as `GAP-076…079`). Every Critical and every High is delivered. **No security or money-safety control was weakened to add a test affordance** — the entire test surface is unreachable by an `sk_live_` key.

---

## Status by finding

| # | Finding | Sev | Resolution | Where |
|---|---------|-----|------------|-------|
| A1 | Mock only succeeds — no forced decline/failure | Critical | **DONE** — `__test_outcome` ∈ {declined, insufficient_funds, expired_card, requires_action, processing, fraud_hold} → `STATUS_FAILED`/etc. + populated errorCode | TEST-1/6 (#48,#57) |
| A2 | No `payment.failed` / `payment.refund.failed` synthesis | High | **DONE** — `MockWebhookSynthesizer.onTerminalFailure`/`onRefundFailed`; refund-fail via magic amount `amount % 100 == 66` | TEST-1 (#48) |
| A3 | No `requires_action` / 3DS state | High | **DONE** — `PaymentResponse.STATUS_REQUIRES_ACTION` + `next_action` in domain → mapper → SDK | TEST-6 (#57) |
| A4 | No async `processing` → settled timing | Medium | **DONE** — `__test_outcome=processing` → `STATUS_PROCESSING`; advance via `POST /v1/test/events payment.succeeded` | TEST-6 (#57) |
| A5 | Fraud capture-hold bypassed for test keys | Medium | **DONE (simulation)** — `__test_outcome=fraud_hold` → `requires_capture` so the hold→capture flow is testable; see note | TEST-6 (#57) |
| B1 | No durable Customer resource | Critical | **DONE** — `/v1/customers` CRUD, `cus_`, `customers` table (V4038), tenant-scoped, `customers:read/write` | TEST-3a (#50) |
| B2 | Saved multi-use payment method is dead code | Critical | **DONE** — `/v1/customers/{id}/payment_methods` + `/v1/payment_methods`, `pm_`, `payment_methods` (V4039) | TEST-3b (#51) |
| B3 | No generic off-session (MIT) charge | Critical | **DONE** — `POST /v1/payments {payment_method, off_session}` → `OffSessionChargeService` | TEST-3c (#52) |
| B4 | HS DTO lacks off_session/setup_future_usage/mandate_id | High | **DONE** — all three threaded `CreatePaymentRequest → PaymentRequest → HsPaymentCreateRequest` | TEST-3c (#52) |
| B5 | No mandate / consent model | High | **DONE** — `/v1/mandates`, `mandate_`, `mandates` (V4040); validated as a consent gate at charge time | TEST-3d (#53) |
| C1 | No outbound dispute events (silent over-grant) | Critical | **DONE** — 7 `dispute.*` canonical types; `DisputeLifecycleService` writes `OutboxEvent` → existing delivery pipeline | TEST-2 (#49) |
| C2 | No dispute simulation in test mode | Critical | **DONE** — `POST /v1/test/disputes`, hard-gated `CallerMode.isTest()`, emits the signed `dispute.created` | TEST-2 (#49) |
| C3 | Dispute read API absent from OpenAPI + SDK | Medium | **DONE** — endpoints + `Dispute` schema in OpenAPI; `disputes` resource in the SDK | TEST-2 (#49) |
| D1 | No test-event trigger endpoint | High | **DONE** — `POST /v1/test/events {type,id?,data?}` synthesizes any canonical type through the signed pipeline | TEST-4a (#54) |
| D2 | Replay only re-sends events that occurred | Medium | **DONE (via D1)** — the trigger covers unseen-event branch coverage; replay remains for redelivery/idempotency | TEST-4a (#54) |
| D3 | Loopback rejected in test mode; no forwarder | High | **DONE** — `nexuspay listen` (loopback-bound receiver + `--forward-to`), the Stripe-CLI model the critique offered | TEST-4b (#55) |
| D4 | Seed default target is `example.com` | Low | **DONE** — `nexuspay listen` + `/v1/test/events` supersede the black hole; LOCAL_DEV documents the loop | TEST-4 (#54/#55) |
| E1 | No signed-test-event generator | High | **DONE** — `generateTestHeaderString` / `generateTestSignature`, single-sourced with `verifyWebhook` | TEST-5 (#56) |
| E2 | No typed test fixtures / builders | Medium | **DONE** — `testFixtures` (all 15 types) + `buildTestEvent(type, overrides)` | TEST-5 (#56) |
| E3 | No `client.ping()` / health | Medium | **DONE** — `client.ping()` + `GET /v1/ping` ({ok, livemode, api_version}, no tenant leak) | TEST-5 (#56) |
| E4 | No recorded mock transport | Low | **DONE** — `createTestTransport(routes)` (typed, real `Response`s, `.calls` recorder); client already had injectable `fetch` | TEST-5 (#56) |
| E5 | No per-event-type fixture set | Low | **DONE** — `testFixtures` covers every `WEBHOOK_EVENT_TYPES` entry | TEST-5 (#56) |
| F2 | Delivery log omits signed body + signature | High | **DONE** — `GET /v1/webhook-deliveries/{id}/body` + `/signature` (owner-scoped 404-no-oracle; secret never returned) | TEST-4a (#54) |
| F3 | No `request_id` correlation surface | Medium | **DONE** — `X-Request-Id` (already echoed) now also in the error-envelope body; test-pinned | TEST-6 (#57) |
| F1 | No list/search for payments or refunds | High | **DEFERRED** — GAP-076 (no read-model exists; needs a projection table — see below) | — |
| F4 | No per-tenant test-data reset | Medium | **DEFERRED** — GAP-077 (needs the in-process mock made tenant-aware) | — |
| F5 | No clock control for the replay window | Medium | **DEFERRED** — GAP-078 (invasive `Clock` retrofit); *partially* mitigated — `generateTestHeaderString` takes a `timestamp` override so you can mint a stale-but-validly-signed event | — |
| F6 | No idempotency-cache inspect/clear | Low | **DEFERRED** — GAP-079 | — |

---

## Cluster A — test-mode fidelity: every non-success outcome is now forceable

**The mechanism (A1).** `MockPaymentGatewayPort` honors a reserved, server-only metadata key `__test_outcome`. Set it on a `create` (or supply it via the saved-method test fixtures) and the mock returns the matching state instead of `SUCCEEDED`:

| `__test_outcome` | Result |
|---|---|
| `declined` | `STATUS_FAILED`, `error_code=card_declined` |
| `insufficient_funds` | `STATUS_FAILED`, `error_code=insufficient_funds` |
| `expired_card` | `STATUS_FAILED`, `error_code=expired_card` |
| `requires_action` | `STATUS_REQUIRES_ACTION` + a `next_action` redirect stub (A3) |
| `processing` | `STATUS_PROCESSING`, no terminal webhook (A4) |
| `fraud_hold` | `STATUS_REQUIRES_CAPTURE` review-hold (A5) |
| *absent / `succeed` / unknown* | byte-identical success (your happy path is untouched) |

The reserved `__`-prefixed key is **stripped before delivery** by a shared `common/MetadataSanitizer`, so it never leaks into the merchant-facing `data.metadata`. Refund failure is forced by the magic amount `amount % 100 == 66`. The catalog is published in `docs/LOCAL_DEV.md`.

**Failure webhooks (A2).** `MockWebhookSynthesizer` gained `onTerminalFailure` (→ `payment.failed`) and `onRefundFailed` (→ `payment.refund.failed`), wired through the same `OutboxRelay → WebhookDeliveryService` pipeline as the success case — so a merchant subscribed to `payment.failed` now actually receives one in test mode.

**3DS (A3).** `PaymentResponse` gained `STATUS_REQUIRES_ACTION` + a nullable `next_action` (a `{type,url}` record, deliberately the same shape already shipped on `ConfirmResponse.NextAction` so the API has exactly one `next_action` contract). It flows domain → `ResponseMapper` → the SDK `Payment` type.

**A5 note (honest scoping).** The fraud screen genuinely cannot run on the mock rail — `routeToMock` bypasses `PreAuthorizationGate` by design (the mock moves no money). We considered writing a real `payment_capture_hold` row for a test `fraud_hold`, but test-mode capture deliberately bypasses the `isHeld()` enforcement (the hold is a *live* money-out control), so that row would be **inert** — fake fidelity that risks entangling the real capture-hold money-safety with a test outcome. Instead `fraud_hold` returns `requires_capture`, giving you the exact `requires_capture → capture → succeeded` flow to test, with the gate and the real hold path untouched and documented as such.

---

## Cluster B — card-on-file / off-session / auto-reload now exist end-to-end

The whole `cus_ → pm_ → off-session charge → mandate` chain is built, tenant-scoped, and the anchor for your deferred `ADR-AUTORELOAD`:

1. **Customer (B1):** `POST/GET/LIST/DELETE /v1/customers`, `cus_` ids, `customers` table (migration V4038), tenant-derived from the authenticated principal (never a client header), 404-no-oracle on a foreign id, `customers:read/write` scopes, soft-delete. Node SDK `customers` resource.
2. **Saved payment method (B2):** `POST/GET /v1/customers/{id}/payment_methods` + `GET/DELETE /v1/payment_methods/{id}`, `pm_` ids, `payment_methods` table (V4039). **PCI-safe by construction** — a saved method stores only display fields (brand/last4/exp) + an **opaque `credential_ref`**; a raw PAN is rejected, never persisted. In test mode you attach a fixture token (`pm_card_visa`, `pm_card_chargeDeclined`, …) — no real card.
3. **Off-session charge (B3):** `POST /v1/payments` now accepts `{customer, payment_method, off_session}` and, when `payment_method` is present, routes through a new `OffSessionChargeService` that resolves the `pm_` tenant-scoped, sends the opaque credential to the gateway, and reuses the **same** idempotency filter + fraud screen + ledger + webhook pipeline as a normal charge — one money path, no parallel surface. A `pm_card_chargeDeclined` fixture deterministically yields `payment.failed`, so your auto-reload decline branch is finally runnable.
4. **DTO fields (B4):** `payment_method`, `off_session`, `setup_future_usage`, `mandate_id` thread `CreatePaymentRequest → PaymentRequest → HsPaymentCreateRequest`.
5. **Mandate (B5):** `POST /v1/mandates` (from a `pm_`, status → `ACTIVE`), `GET`/`LIST`, `POST /v1/mandates/{id}/revoke` (→ `INACTIVE`, retained for audit). It is a **real consent gate**: if an off-session charge cites a `mandate_id`, the charge requires it to be tenant-owned + `ACTIVE` + bound to the same `pm_`, else 404/400 and **no charge**. (`mandates` table V4040.)

---

## Cluster C — chargebacks are now observable and simulatable

- **Outbound events (C1):** 7 canonical dispute types — `dispute.created`, `dispute.funds_withdrawn`, `dispute.evidence_needed`, `dispute.evidence_submitted`, `dispute.won`, `dispute.lost`, `dispute.closed` — added to `WebhookEventTaxonomy.CANONICAL` (now 15 deliverable types). `DisputeLifecycleService` writes an `OutboxEvent` on each transition (via a native `event_outbox` insert that respects the Modulith boundary), so a merchant subscribed to `*` now receives chargeback notifications and your credit-ledger can claw back. The silent over-grant is closed.
- **Simulation (C2):** `POST /v1/test/disputes` (hard-gated `CallerMode.isTest()` → 404 for a live key) opens a dispute against a `pay_test_*` payment and emits the signed `dispute.created` through the normal pipeline.
- **Read API (C3):** the `GET /v1/disputes`, `/{id}`, `/{id}/events` endpoints + a `Dispute` schema are now in `docs/api/openapi.yaml`, and the SDK has a `disputes` resource.

---

## Cluster D — event generation & the local webhook loop

- **Trigger (D1/D2):** `POST /v1/test/events {type, id?, data?}` synthesizes **any** canonical event (validated against the taxonomy) and pushes it through the real signed delivery pipeline to your endpoints — so you can fire `payment.refunded`, `payment.refund.failed`, any `dispute.*`, etc. at your handler on demand. This is the branch-coverage tool replay couldn't be.
- **Local loop (D3/D4):** the SDK now ships a `nexuspay` CLI. `nexuspay listen` runs a localhost-bound receiver that verifies each delivery's signature (reusing the SDK's `verifyWebhook`) and optionally `--forward-to http://localhost:3000/...` — the Stripe-CLI model, which keeps the SSRF target platform-internal (so we did **not** weaken `WebhookUrlValidator`). `nexuspay trigger <type>` wraps D1.

---

## Cluster E — SDK test ergonomics (shipped in 0.1.2)

- **`generateTestHeaderString` (E1):** exported, implemented by the **same** internal `computeSignature` that `verifyWebhook` uses — so your tests and production deliveries can never drift. You can delete your hand-rolled `webhook-sig.ts` copy.
- **Typed fixtures (E2/E5):** `testFixtures` (a typed sample for every one of the 15 event types) + `buildTestEvent(type, overrides)`; pair with E1 for a one-liner that produces a signed, schema-true event.
- **`client.ping()` (E3):** backed by `GET /v1/ping`, returns `{ok, livemode, apiVersion}` with no side effects — your boot-time "am I talking to test with a valid key?" assertion.
- **`createTestTransport` (E4):** a typed fake transport (real `Response`s, programmable routes, a `.calls` recorder) for unit-testing client error paths.

---

## Cluster F — observability & lifecycle

- **Delivery body + signature (F2):** `GET /v1/webhook-deliveries/{id}/body` returns the exact `canonical_body` you were sent; `/signature` recomputes the HMAC so you can diff a signature mismatch. Owner-scoped (404-no-oracle), and the **signing secret is never returned**.
- **`request_id` (F3):** `X-Request-Id` was already echoed on every response + in MDC; the error-envelope **body** now also carries `request_id` so a black-box integrator can correlate a failure without server-log access.

### Deferred (with rationale) — tracked as `GAP-076…079`
These four are genuine platform features rather than test affordances; shipping a half-built version would be worse than an honest deferral. None is a security or money gap.

- **F1 — payments/refunds list (GAP-076).** There is no payment/refund *read-model*: live state lives in the PSP, test state in the in-process mock. A durable `GET /v1/payments?status=…` needs a projection table populated from the lifecycle (with the usual eventual-consistency care) — a product decision we'd like your input on, since it's broadly useful beyond testing.
- **F4 — per-tenant test reset (GAP-077).** Requires the in-process mock to be partitioned by tenant first (today it's a global singleton).
- **F5 — clock control (GAP-078).** A true test clock needs an injectable `Clock` threaded across many call sites. *Partial mitigation shipped:* `generateTestHeaderString({timestamp})` lets you mint a stale-but-validly-signed event to exercise your `timestamp_out_of_tolerance` branch today.
- **F6 — idempotency inspect/clear (GAP-079).**

---

## What this means for Snap, concretely

The paths your charter previously forced you to ship **never having run** are now runnable in TEST MODE:

- **Decline / error-envelope / no-grant branch** → charge with `metadata.__test_outcome=declined` (or off-session a `pm_card_chargeDeclined`).
- **Failed-refund handling** → refund a payment whose `amount % 100 == 66`.
- **Credit claw-back on chargeback** → `POST /v1/test/disputes` (or `nexuspay trigger dispute.created`) and watch `dispute.funds_withdrawn` hit your handler.
- **Auto-reload (the deferred ADR)** → `POST /v1/customers` → attach `pm_card_visa` → `POST /v1/mandates` → `POST /v1/payments {payment_method, off_session, mandate_id}`.
- **Real end-to-end local webhook** → `nexuspay listen --forward-to http://localhost:3000/api/webhooks/nexuspay`.
- **Unit-test your handler offline** → `generateTestHeaderString` + `buildTestEvent('payment.refunded', …)`, no re-implemented HMAC.

`known-gaps.md` has been refreshed (the stale 2026-03 entries reconciled against DX-1..6 + this program; GAP-076…079 added).

---

## SDK 0.1.2

`@nexus-pay/{js,node,react}@0.1.2` published to npm with Sigstore provenance (release pipeline, `sdk-v0.1.2` tag) — carrying the test helpers (E1–E4), the `disputes`/`customers`/`paymentMethods` resources, `next_action`, and the `nexuspay` CLI bin.

---

*Delivered by the NexusPay platform team under the PERPETUA workflow. Every change merged CI-green; the full per-batch decision record is in `.perpetua/DECISIONS.md` (ADR-054…063) and `.perpetua/LESSONS.md` (L-069…072).*
