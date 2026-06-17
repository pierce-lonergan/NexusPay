# NexusPay Integration Guide

An end-to-end merchant integration, in both **cURL** and **Node** (`@nexus-pay/node`).
Everything here runs in **test mode** by default (`sk_test_` keys never move real
money — see §6). All keys and secrets shown are placeholders.

Base URL throughout: `http://localhost:8090` (the lite stack — see
[LOCAL_DEV.md](LOCAL_DEV.md)).

The flow: **onboard → create a payment → checkout → receive & verify the webhook
→ reconcile and credit.**

---

## 1. Onboard — get an `sk_test_` key

API keys are minted by an admin (`POST /v1/api-keys`, admin role). In local dev,
get a Keycloak admin token via the seeded `admin@nexuspay.test` account, then mint
an operator test key. The bundled `scripts/dev/seed-local.sh` does exactly this
(see LOCAL_DEV.md §4); the equivalent cURL is:

```bash
# 1a. Keycloak admin token (Direct Access Grant)
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/nexuspay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=nexuspay-api \
  -d client_secret=nexuspay-api-secret \
  -d username=admin@nexuspay.test -d password=test123 | jq -r .access_token)

# 1b. Mint a test (live:false) operator key — the full `key` is shown ONCE
curl -X POST http://localhost:8090/v1/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"my integration","role":"operator","live":false}'
```

The response carries the full `key` (an `sk_test_…` value) exactly once. Store it
as `NEXUSPAY_API_KEY`. `live:false` mints a **test** key; `live:true` mints
`sk_live_`.

---

## 2. Create a payment

The `mode` in the response ("test"/"live") is **server-derived** from the key —
never from the request body. Amounts are in **minor units** (cents). Send an
`Idempotency-Key` so a retry can't double-charge. Put your own correlation data in
`metadata` — it round-trips onto the webhook (§4).

### cURL

```bash
curl -X POST http://localhost:8090/v1/payments \
  -H "Authorization: Bearer sk_test_xxx" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1234" \
  -d '{
        "amount": 600,
        "currency": "USD",
        "capture": true,
        "metadata": { "userId": "usr_xxx", "packId": "starter" }
      }'
```

Response (note `mode: "test"`, and `capture: true` resolved to
`capture_method: "automatic"`):

```json
{
  "id": "pay_test_xxx",
  "status": "succeeded",
  "amount": 600,
  "currency": "USD",
  "capture_method": "automatic",
  "connector": "mock",
  "created_at": "2026-06-16T12:00:00Z",
  "metadata": { "userId": "usr_xxx", "packId": "starter" },
  "mode": "test"
}
```

> `capture` is the INT-2 convenience alias: `true` → `automatic`, `false` →
> `manual`. If you send `capture_method` explicitly, it wins and `capture` is
> ignored.

### Node (`@nexus-pay/node`)

```js
import { NexusPay } from '@nexus-pay/node';

const client = new NexusPay({
  apiKey: process.env.NEXUSPAY_API_KEY, // sk_test_xxx
  baseUrl: 'http://localhost:8090',
});

const payment = await client.createPayment(
  {
    amount: 600,
    currency: 'USD',
    capture: true,
    metadata: { userId: 'usr_xxx', packId: 'starter' },
  },
  { idempotencyKey: 'order-1234' },
);
// payment.id, payment.status, payment.mode === 'test'
```

camelCase params map to the snake_case wire body automatically; the
`idempotencyKey` option becomes the `Idempotency-Key` header.

**Hosted checkout variant.** If you want the browser SDK to collect the payment
method, create a **payment session** instead and hand its `client_secret` to the
front end:

```js
const session = await client.createPaymentSession({
  amount: 600,
  currency: 'USD',
  successUrl: 'https://merchant.example.com/success',
  cancelUrl: 'https://merchant.example.com/cancel',
  metadata: { userId: 'usr_xxx', packId: 'starter' },
});
// session.client_secret  → pass to the browser SDK (see §3)
```

`client_secret` is returned **only** on session create (never on GET).

---

## 3. Checkout (browser)

Either render your own UI and call the REST API server-side, or use the React
bindings with the session `client_secret` from §2:

```jsx
import { NexusPayProvider, PaymentElement, useConfirmPayment } from '@nexus-pay/react';

function Checkout({ clientSecret }) {
  return (
    <NexusPayProvider publishableKey="pk_test_xxx" clientSecret={clientSecret}>
      <PaymentForm />
    </NexusPayProvider>
  );
}

function PaymentForm() {
  const { confirm } = useConfirmPayment(); // handles 3DS / next-action
  return (
    <form onSubmit={async (e) => { e.preventDefault(); await confirm(); }}>
      <PaymentElement />
      <button type="submit">Pay</button>
    </form>
  );
}
```

`useConfirmPayment` drives `POST /v1/checkout/confirm`, whose result is
status-accurate (`succeeded` | `processing` | `requires_action` | `failed`) and
includes `paymentId`, `mode`, and `livemode`.

---

## 4. Receive and verify the webhook

When the payment captures, NexusPay POSTs a signed `payment.succeeded` event to
your registered endpoint (register one with `POST /v1/webhook-endpoints` — see
WEBHOOKS.md). Verify with the **raw** body:

```js
import { constructEvent } from '@nexus-pay/node';

export async function POST(req) {
  const rawBody = await req.text(); // RAW — do not parse first
  let event;
  try {
    event = constructEvent(
      rawBody,
      req.headers,
      process.env.NEXUSPAY_WEBHOOK_SECRET, // whsec_xxx
      { createdToleranceSeconds: 300 },
    );
  } catch {
    return new Response('invalid signature', { status: 401 });
  }
  // event.type === 'payment.succeeded'
  // event.data.metadata === { userId: 'usr_xxx', packId: 'starter' }
}
```

The full envelope shape, headers, and a dependency-free verifier are in
[WEBHOOKS.md](WEBHOOKS.md).

---

## 5. Reconcile and credit

Deliveries are at-least-once. **Dedupe on `event.id`** (process once, ack repeats
with 2xx) and resolve the entitlement from **your** records keyed by
`event.data.metadata` — not from the event amount:

```js
if (await alreadyProcessed(event.id)) return ack();        // idempotent
const { userId, packId } = event.data.metadata;            // your correlation
await creditAccount(userId, packId);                       // your catalog is the source of truth
await markProcessed(event.id);
```

A `payment.refunded` event carries the same `data.metadata` (round-tripped from
the original create), so the claw-back path can target the same account. The
metadata tells you **which** account; for **how much** to claw back, read
`event.data.object.amount` — on a refund event that is the **server-derived
amount actually refunded** (a partial refund carries the partial amount). So
debit by `data.object.amount`, not your full catalog price:

```js
const { userId, packId } = event.data.metadata;            // which account
const refundedMinor = event.data.object.amount;            // how much (server-derived)
await clawBack(userId, packId, refundedMinor);             // partial-safe
```

This is the one place `data.object.amount` is the source of truth (the platform,
not the client, computed it) — everywhere else, resolve *what to grant* from your
own catalog by metadata, never from the request-time amount.

---

## Responsibility matrix

| Concern | Merchant (you) | Platform (NexusPay) |
|---|---|---|
| Create payment sessions / payments | ✅ | — |
| Create refunds | ✅ (request) | ✅ (maker-checker approval above threshold) |
| Verify webhook signatures | ✅ | — (signs + delivers) |
| Dedupe events / hold your own balance or credit projection | ✅ | — |
| Double-entry ledger, settlement, reconciliation | — | ✅ |
| Dispute lifecycle & chargeback postings | — | ✅ |
| Fraud / sanctions screening | — | ✅ |
| Webhook delivery, retries, DLQ, replay | — | ✅ |
| Test-mode isolation (no real money) | — | ✅ |

In short: the **merchant** owns its checkout UX, its own balance/entitlement
projection, and webhook verification + idempotency; the **platform** owns money
movement, the ledger, screening, disputes, and reliable webhook delivery.

---

## 6. Test-mode guarantee (INT-3)

A key whose value starts with `sk_test_` is a **test key**: every payment it
creates is routed through `GatedPaymentGateway` to the in-process
`MockPaymentGatewayPort`, which performs **zero network I/O** (no HyperSwitch, no
HTTP client) and produces `pay_test_*` ids with connector `mock`. A test key can
**never** reach the real PSP adapter — this is an architecture-test-enforced hard
invariant, not a runtime flag. The response `mode`/`livemode` and the webhook
envelope's `livemode` all reflect `test`/`false`, so your downstream code can
assert on them too.

## 7. Versioning — three numbers, two meanings

You will see three version-like identifiers. They are **not** the same thing:

| Identifier | What it is | Current value |
|---|---|---|
| **API contract version** | Date-based version of the wire contract. Stamped as `api_version` on every webhook envelope, and defaulted/echoed via the `X-API-Version` request header. | `2026-06-16` |
| **`X-API-Version` request header** | Lets you pin the contract version per request. There is exactly **one** supported version today, so this is **informational** — it is recorded and echoed back, but no per-version request/response transformation is applied yet. Omit it and you get the current version. | `2026-06-16` |
| **SDK / library version** | The independent npm **semver** of each `@nexus-pay/*` package. Tracks client-library releases — **orthogonal** to the API contract version. | `0.1.x` |

The API contract version (`api_version`) and the SDK semver are deliberately
distinct: upgrading the SDK package does not change the wire contract, and a
future contract version will not force an SDK major bump. Both the webhook
`api_version` and the `X-API-Version` default derive from a **single** server-side
source of truth, so they can never disagree.
