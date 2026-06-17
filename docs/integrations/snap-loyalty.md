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
  // Use a FRESH key per checkout ATTEMPT (persist it with the attempt so a retry of THAT
  // attempt reuses it). NEVER a key that's stable across distinct purchases of the same pack:
  // the platform's Idempotency-Key cache (24h TTL, keyed on key + caller, NOT the request body)
  // would serve the FIRST purchase's cached response and skip the charge + webhook — a silent
  // "checkout succeeded but no credit" on every repeat buy of that pack. See the security note below.
  { idempotencyKey: attemptId /* e.g. crypto.randomUUID() minted per attempt */ },
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

// Next.js App Router: `req.headers` is a WHATWG `Headers`. Convert it to a plain bag —
// passing the `Headers` object directly reads as EMPTY on @nexus-pay/node ≤0.1.0 and rejects
// every delivery as `missing_signature` (0.1.1+ also accepts a `Headers` instance directly).
const event = constructEvent(
  rawBody,
  Object.fromEntries(req.headers),  // or: { 'x-nexuspay-signature': req.headers.get('x-nexuspay-signature') }
  process.env.NEXUSPAY_WEBHOOK_SECRET,
);
// then read event.data.metadata.userId / event.data.metadata.packId  (see GAP-01)
```

---

## Platform security posture (what Snap gets for free)

NexusPay completed a security-hardening pass (SEC-1b…28, 2026-06-16) that strengthens this
integration. These guarantees hold on the **authenticated request path** Snap uses (a one-shot
`createPayment` on the request thread); two are request-scoped rather than universal invariants (noted),
which matters only if Snap later adds server-initiated flows such as subscriptions.

- **Tenant is server-authoritative.** Snap's tenant is derived from its API key, never from a
  client-supplied header — every controller now ignores `X-Tenant-Id` (a repo-wide audit confirmed zero
  header-trust). Snap sends only the API key, so cross-tenant access on its request path is closed.
- **Money mutations are idempotent — with a per-ATTEMPT key.** `createPayment` (and capture/void/refund)
  dedupe on the `Idempotency-Key`, so a retry of the SAME attempt won't double-charge. **Critical:** the
  key must be unique per checkout *attempt* (e.g. `crypto.randomUUID()` persisted with the attempt), **not**
  stable across distinct purchases of the same pack — a stable key makes the platform serve the first buy's
  cached 2xx and skip the charge + webhook for 24h (silent "no credit"). *(An earlier draft of this guide
  wrongly recommended a stable `checkout:${userId}:${pack.id}` key — corrected. Snap itself already uses a
  per-attempt key.)*
- **Webhooks are signed, replay-safe, and rotatable.** HMAC over the raw body, at-least-once delivery, a
  stable `event.id` for dedupe, and secret rotation (INT-4). Verify the signature before parsing and dedupe
  on `event.id` (GAP-07). For replay freshness use the SDK's **signed** `createdToleranceSeconds`, never the
  unsigned `toleranceSeconds` (which the JSDoc itself flags as non-cryptographic).
- **Test mode routes to the in-process mock — on the request path.** An `sk_test_` key on the authenticated
  request thread routes to the mock gateway (INT-3); no real PSP. **Note (request-scoped):** a future
  server-initiated / `@Scheduled` charge under a test key is not yet covered by this thread-local routing
  heuristic (tracked as a platform hardening item). For defense-in-depth, assert `event.livemode === false`
  (webhook) / the REST mode flag before granting credits — cheap and CHARTER-aligned.

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
| **GAP-17** Partial-refund over-claw | on `payment.refunded`, claws back the **full pack** `creditsMicros` from its own pack table (the GAP-13 pattern), ignoring the event amount | **GAP (refund path)** — correct for *grants*, wrong for *partial* refunds. A partial refund returns only part of the charge, and the refund event's `data.object.amount` carries that **server-derived refunded minor amount**. Clawing the whole pack over-debits the user. **Action:** on `payment.refunded` scale the claw-back by `event.data.object.amount` (not the pack price) — the one refund-path exception to GAP-13's "ignore the event amount" rule, because here the *platform* computed the figure (see WEBHOOKS.md §6 refund carve-out + INTEGRATION.md §5). |

### Residual checklist (action items)

1. **GAP-01** — update the Zod schema to read user/pack from `data.metadata`, not flat `data`.
2. **GAP-06** — either drop the inert `timestamp` replay guard (idempotency suffices) or anchor it on `created` (epoch seconds × 1000) / the SDK's `createdToleranceSeconds`.
3. **GAP-09** — drop `clientSecret` from the checkout response, or migrate to payment sessions (`client_secret`).
4. **GAP-12** — for local end-to-end webhook testing, register an ngrok HTTPS URL or verify offline.

Everything else is already aligned with the NexusPay contracts.
