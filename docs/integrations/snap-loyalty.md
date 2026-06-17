# Snap Loyalty × NexusPay — path forward

Snap Loyalty is a Next.js app that buys analysis credits through NexusPay in
**test mode** (its CHARTER: never move real money). It already integrates against
the NexusPay contracts with hand-rolled code. This document maps Snap's current
integration to the NexusPay contracts, calls out the residual gaps, and gives a
concrete, low-risk path to adopt the official SDK.

> **The SDK is now published.** As of 2026-06-16 the client SDKs are **live on npm at
> `0.1.0`**, built and signed from CI with Sigstore provenance: [`@nexus-pay/js`](https://www.npmjs.com/package/@nexus-pay/js),
> [`@nexus-pay/node`](https://www.npmjs.com/package/@nexus-pay/node),
> [`@nexus-pay/react`](https://www.npmjs.com/package/@nexus-pay/react). The npm scope is
> **`@nexus-pay`** (with a hyphen) — `@nexuspay` was unavailable. The SDKs are MIT-licensed
> and embeddable in client apps even though the platform itself is PolyForm-Noncommercial.

> Snap files are referenced **read-only** below for orientation. This document
> proposes changes; it does not modify any Snap code.

Relevant Snap files (in the Snap repo):

- `src/app/api/credits/checkout/route.ts` — raw `fetch` to `POST /v1/payments`.
- `src/app/api/webhooks/nexuspay/route.ts` — webhook receiver (Zod-validated, HMAC-verified).
- `src/lib/http/webhook-sig.ts` — HMAC-SHA256 hex verifier (accepts bare or `=`-suffixed).
- `src/lib/services/credits.ts` — credit packs (`starter`/`plus`/`pro`), `addCredit` (idempotent), `refundableMicros`.
- `src/lib/services/pricing.ts` — `usdToMicros`.
- `.env.local.example` — `NEXUSPAY_WEBHOOK_SECRET`, `NEXUSPAY_API_URL`, `NEXUSPAY_API_KEY`.

---

## Environment

Snap reads three env vars (`.env.local.example`, the "Payments (NexusPay, TEST
MODE)" block). Set them to point at the lite stack:

```bash
NEXUSPAY_API_URL=http://localhost:8090
NEXUSPAY_API_KEY=sk_test_xxx
NEXUSPAY_WEBHOOK_SECRET=whsec_xxx
```

If `NEXUSPAY_API_URL`/`NEXUSPAY_API_KEY` are unset, Snap's checkout route returns
a clean 503 ("payments not connected yet") rather than failing — keep that
behavior.

---

## Recommended adoption of `@nexus-pay/node`

Snap only needs the **server** SDK (the credit checkout + webhook receiver run on Next.js API
routes, not in the browser). Install it and pin a range so a future major can't silently change
the contract:

```bash
npm i @nexus-pay/node@^0.1.0
```

### Checkout — replace the raw `fetch`

Snap's `src/app/api/credits/checkout/route.ts` currently builds the
`POST /v1/payments` request by hand. Swap it for the typed client; the contract is
identical, but you gain typed errors (INT-2 envelope) and idempotency:

```js
import { NexusPay } from '@nexus-pay/node';

const client = new NexusPay({
  apiKey: process.env.NEXUSPAY_API_KEY,   // sk_test_xxx
  baseUrl: process.env.NEXUSPAY_API_URL,  // http://localhost:8090
});

const payment = await client.createPayment(
  {
    amount: Math.round(pack.priceUsd * 100), // minor units (cents)
    currency: 'USD',
    capture: true,
    metadata: { userId, packId: pack.id },
  },
  { idempotencyKey: `checkout:${userId}:${pack.id}` },
);
// return { paymentId: payment.id }   // see GAP-09 re: clientSecret
```

### Webhook — verify with `constructEvent`

Snap's `src/app/api/webhooks/nexuspay/route.ts` already verifies the HMAC over the
raw body with its own `verifyWebhookSignature`, which matches the platform signer
byte-for-byte. Adopting `constructEvent` is **optional**; if you do, keep reading
the raw body and keep dedupe on `event.id`:

```js
import { constructEvent } from '@nexus-pay/node';

const event = constructEvent(rawBody, req.headers, process.env.NEXUSPAY_WEBHOOK_SECRET);
// then read event.data.metadata.userId / event.data.metadata.packId  (see GAP-01)
```

---

## Platform security posture (what Snap gets for free)

NexusPay completed a security-hardening pass (SEC-1b…28, 2026-06-16) that strengthens this
integration without any change on Snap's side:

- **Tenant is server-authoritative.** Snap's tenant is derived from its `sk_test_` **API key**, never
  from a client-supplied header — the platform now rejects/ignores any `X-Tenant-Id` header on every
  endpoint (a repo-wide audit confirmed zero header-trust). Snap already sends only the API key, so
  there is nothing to change; cross-tenant access is impossible by construction.
- **Money mutations are idempotent.** `createPayment` (and capture/void/refund) dedupe on the
  idempotency key, so a retried or double-fired checkout cannot double-charge or double-credit. Snap's
  `idempotencyKey: checkout:${userId}:${pack.id}` is exactly the right shape — keep it stable per
  (user, pack) attempt.
- **Webhooks are signed, replay-safe, and rotatable.** Deliveries to Snap are HMAC-signed over the raw
  body with at-least-once delivery, a stable `event.id` for dedupe, and secret rotation support (INT-4).
  Verify the signature before parsing (Snap already does) and dedupe on `event.id` (GAP-07).
- **Test mode never moves real money.** An `sk_test_` key routes to the in-process mock gateway (INT-3) —
  no real PSP call — satisfying Snap's CHARTER.

---

## GAP-01..16 — divergences and their status

The table maps each known divergence between Snap's code and the NexusPay
contracts to its current status. **CLOSED** = the contract already satisfies Snap;
**PARTIAL/GAP** = a residual action remains on the Snap side.

| Gap | Snap reality | Status / action |
|---|---|---|
| **GAP-01** Event field shape | webhook route's Zod `Event.data` expects flat `{ userId, packId, creditsMicros }`; the platform nests `data.object` + `data.metadata` | **CLOSED** — your create `metadata` round-trips to `data.metadata`. Read `event.data.metadata.userId` / `event.data.metadata.packId` (update the Zod schema to nest under `data.metadata`). |
| **GAP-02** Event type names | route matches `payment.succeeded` and `payment.refunded` (route.ts:73-74) | **CLOSED** — both are exact platform dotted names. The extra `charge.refunded` branch is dead (the platform never emits it) but harmless. |
| **GAP-03** Signature header name | reads `x-nexuspay-signature` (route.ts:52) | **CLOSED** — exact platform header. |
| **GAP-04** HMAC algorithm/encoding | HMAC-SHA256 hex, accepts bare or `=`-prefixed (webhook-sig.ts) | **CLOSED** — matches the platform signer (lowercase bare hex, no `sha256=` prefix). The prefix tolerance is harmless. |
| **GAP-05** Signed-over bytes | verifies the raw body (route.ts:45,53) | **CLOSED** — the platform signs the exact raw envelope bytes. Keep reading the raw body before JSON-parsing. |
| **GAP-06** Replay timestamp | expects `timestamp` (epoch **ms**) inside the body (route.ts:33,67) | **PARTIAL** — the platform's signed freshness field is `created` (epoch **seconds**) at the top level, not a `timestamp` ms field in `data`. Snap's `event.timestamp` is never populated, so its replay guard is inert (idempotency remains the primary guard). To activate it, read `event.created * 1000`, or use the SDK's `createdToleranceSeconds`. |
| **GAP-07** Idempotency dedupe | dedupes on `event.id` via `addCredit` idempotency key (route.ts:120) | **CLOSED** — the platform `id` is stable across redelivery and replay. |
| **GAP-08** Capture intent | sends `capture: true` (route.ts:62) | **CLOSED** — INT-2 alias maps `true` → `automatic`. |
| **GAP-09** `clientSecret` field | reads `intent.clientSecret` from the `POST /v1/payments` response (route.ts:69-70) | **GAP** — `POST /v1/payments` returns **no** client secret (only `id`). Either drop `clientSecret` (server-only flow), or switch to **payment sessions** (`POST /v1/payment-sessions` → `client_secret`, snake_case) and pass that to the browser SDK. |
| **GAP-10** Error envelope | maps any non-2xx to a generic 502 (route.ts:66-67) | **CLOSED** — `@nexus-pay/node` surfaces the INT-2 `{ type, code, message, request_id }` via `NexusPayError`, so you can branch on real error categories instead of collapsing to 502. |
| **GAP-11** Test-mode safety | CHARTER: never move real money | **CLOSED** — an `sk_test_` key routes to the in-process mock (INT-3); no real PSP, no real money. |
| **GAP-12** localhost webhook delivery | local dev | **GAP** — SEC-4b rejects loopback at registration AND delivery. Use an HTTPS tunnel (ngrok) and register the public URL, or verify signatures offline with the `whsec_` secret (LOCAL_DEV.md §6). |
| **GAP-13** Amount units | sends cents; credits from its own pack table (route.ts:62, webhook route.ts:91-93) | **CLOSED** — the platform amount is minor units, and Snap correctly resolves the credit amount from its own pack table (ignoring the event amount). |
| **GAP-14** Refund correlation | a refund must carry `userId` to claw back (route.ts:34, schema) | **CLOSED** — the refund webhook's `data.metadata` round-trips from the original payment's create metadata, so `userId`/`packId` are present on `payment.refunded`. |
| **GAP-15** livemode awareness | Snap ignores `livemode` | **CLOSED (informational)** — the envelope carries `livemode: false` in test mode; Snap may optionally assert it as defense-in-depth. |
| **GAP-16** Refund-without-purchase drain | guarded by `refundableMicros` (credits.ts:109) | **CLOSED on Snap's side** — Snap already caps refunds at un-refunded purchase headroom. The platform adds defense-in-depth: large refunds are gated behind maker-checker approval (the 202 `ApprovalResponse` with `approval_threshold`). |

### Residual checklist (action items)

1. **GAP-01** — update the Zod schema to read user/pack from `data.metadata`, not flat `data`.
2. **GAP-06** — either drop the inert `timestamp` replay guard (idempotency suffices) or anchor it on `created` (epoch seconds × 1000) / the SDK's `createdToleranceSeconds`.
3. **GAP-09** — drop `clientSecret` from the checkout response, or migrate to payment sessions (`client_secret`).
4. **GAP-12** — for local end-to-end webhook testing, register an ngrok HTTPS URL or verify offline.

Everything else is already aligned with the NexusPay contracts.
