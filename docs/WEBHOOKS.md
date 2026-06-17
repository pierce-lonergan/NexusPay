# NexusPay Webhooks

NexusPay delivers signed, retried HTTP webhooks to a registered endpoint when a
payment or refund changes state. This document is the integration contract:
event taxonomy, the canonical envelope, signature headers, verification, retries,
and the SSRF rules that govern delivery targets.

Register an endpoint with `POST /v1/webhook-endpoints`.

> **Auth: these management endpoints require an `admin`-role API key.** Register, rotate-secret, and
> replay are `@PreAuthorize("hasRole('admin')")`; an `operator`/`viewer` key gets a **403**. Note the
> lite-stack `seed-local.sh` mints an **operator** `sk_test_` key — to call these, use an admin key
> (mint one as admin, or `seed-local.sh` prints an admin re-register command). Listing deliveries is
> `admin`/`operator`. Below, `sk_test_xxx` denotes an admin-role key.

```bash
curl -X POST http://localhost:8090/v1/webhook-endpoints \
  -H "Authorization: Bearer sk_test_xxx" \
  -H "Content-Type: application/json" \
  -d '{
        "url": "https://merchant.example.com/webhooks/nexuspay",
        "description": "prod receiver",
        "events": ["payment.succeeded", "payment.refunded"]
      }'
```

The response contains the signing secret (`whsec_…`) **once** — store it now.

---

## 1. Event taxonomy

A merchant subscribes to canonical, dotted, lowercase event names (or the `"*"`
wildcard). These are the only deliverable types:

| Event type (dotted) | When it fires |
|---|---|
| `payment.created` | Payment intent created |
| `payment.authorized` | Payment authorized (funds held) |
| `payment.succeeded` | Payment **captured** (money state terminal) |
| `payment.failed` | Payment failed |
| `payment.canceled` | Payment authorization voided (note: one "l") |
| `payment.refund.created` | Refund initiated |
| `payment.refunded` | Refund **completed** |
| `payment.refund.failed` | Refund failed |
| `*` | Wildcard — subscribe to all of the above |

Two names are worth calling out explicitly because they do not match the
internal verb:

- **`payment.succeeded`** is emitted on **capture** (not on create). For an
  auto-capture payment the platform synthesizes it at capture time.
- **`payment.refunded`** is emitted when a refund **completes** (not when it is
  created — that is `payment.refund.created`).

Any internal event type without a canonical dotted mapping is **not deliverable**
on this contract.

---

## 2. Canonical envelope

Every delivery is a single JSON object. The emitted top-level key order is fixed:
`id`, `type`, `livemode`, `created`, `api_version`, `data` (with
`data.object` then `data.metadata`). The HMAC signature is computed over the
**exact serialized bytes** of this envelope, so do not re-serialize before
verifying.

```json
{
  "id": "evt_xxx",
  "type": "payment.succeeded",
  "livemode": false,
  "created": 1750000000,
  "api_version": "2026-06-16",
  "data": {
    "object": { "id": "pay_test_xxx", "object": "payment", "amount": 600, "currency": "USD", "status": "succeeded" },
    "metadata": { "userId": "usr_xxx", "packId": "starter" }
  }
}
```

Field notes:

- **`id`** — the stable event id and your **dedupe key**. It is stable across
  redelivery and replay (never minted per send).
- **`type`** — one of the dotted names in §1.
- **`livemode`** — boolean, server-derived. `false` in test mode (`sk_test_`),
  `true` in live. The reserved internal `__livemode` key is stripped from
  `data.metadata` and surfaced here.
- **`created`** — **epoch SECONDS** (UTC), not milliseconds and not ISO-8601.
- **`api_version`** — the constant string `"2026-06-16"`.
- **`data.object`** — the normalized payment/refund object: PSP keys pass through
  (minus any card subtree) plus an `object` discriminator (`"payment"` or
  `"refund"`) and an `id`.
- **`data.metadata`** — the merchant correlation map **round-tripped from your
  create call** (`{}` if you sent none). This is NOT the PSP echo — it is exactly
  the `metadata` you supplied to `POST /v1/payments` (or the payment session).
  Use it to correlate the event back to your own records.

---

## 3. Signature headers

Each delivery carries three headers:

| Header | Value |
|---|---|
| `X-NexusPay-Signature` | HMAC-SHA256 of the raw body, **lowercase hex, bare** (NO `sha256=` prefix) |
| `X-NexusPay-Timestamp` | `Instant.now().toString()` — ISO-8601, **UNSIGNED** (not part of the HMAC) |
| `X-NexusPay-Event` | the dotted event type |

The signature is `HMAC-SHA256(secret, rawBody)` rendered as lowercase hex
(`HexFormat.of().formatHex`). The secret is the endpoint's **current** secret,
read per attempt — so a rotated secret takes effect on the next attempt.

> The `X-NexusPay-Timestamp` header is **not** covered by the signature. Do not
> use it for cryptographic replay protection — see §5.

---

## 4. Verification

### 4a. With the Node SDK (recommended)

`@nexus-pay/node` ships `constructEvent`, which verifies the signature, optionally
enforces a replay window, then JSON-parses the body. Pass the **raw** request
body — re-serializing the JSON changes the bytes and invalidates the signature.

```js
import { constructEvent } from '@nexus-pay/node';

export async function POST(req) {
  const rawBody = await req.text(); // RAW bytes — do not JSON.parse first
  let event;
  try {
    event = constructEvent(
      rawBody,
      req.headers, // case-insensitive lookup of X-NexusPay-Signature / -Timestamp
      process.env.NEXUSPAY_WEBHOOK_SECRET,
      { createdToleranceSeconds: 300 }, // hardened replay window (signed `created`)
    );
  } catch (err) {
    // SignatureVerificationError: bad signature, missing header, stale, or non-JSON
    return new Response('invalid signature', { status: 401 });
  }
  // event.id, event.type, event.data.object, event.data.metadata ...
}
```

### 4b. Dependency-free (Node crypto)

If you cannot add the SDK, verify the bare-hex signature directly. This mirrors
the SDK's `computeSignature` byte-for-byte:

```js
const crypto = require('node:crypto');

function verify(rawBody, signatureHeader, secret) {
  const expected = crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
  const provided = signatureHeader.replace(/^sha256=/i, ''); // tolerate an optional prefix
  return crypto.timingSafeEqual(
    Buffer.from(expected, 'hex'),
    Buffer.from(provided, 'hex'),
  );
}
```

- Compute the HMAC over the **raw body string** (UTF-8), not a re-serialized object.
- The platform sends a **bare** hex signature; stripping a `sha256=` prefix is
  belt-and-suspenders so the same verifier works if you ever proxy through a
  gateway that adds one.
- Compare in constant time (`timingSafeEqual`).

---

## 5. Idempotency

Deliveries are **at-least-once**: a transient failure, a replay, or a leader
failover can deliver the same event more than once. **Dedupe on the stable
`id`** — process the first occurrence and acknowledge (2xx) any repeat without
re-applying the side effect.

For replay protection, prefer the **hardened** window
(`createdToleranceSeconds`) which anchors on the **signed** `created` field
(epoch seconds, covered by the HMAC). The advisory `toleranceSeconds` window
checks the **unsigned** `X-NexusPay-Timestamp` header, which an attacker who
captured a valid delivery could rewrite — treat it only as a coarse freshness
hint behind a trusted transport.

---

## 6. Correlation

Correlate an event back to your own system via `data.metadata`, which is
round-tripped verbatim from the `metadata` you supplied at create time. For
example, if you create a payment with
`metadata: { "userId": "usr_xxx", "packId": "starter" }`, the corresponding
`payment.succeeded` and `payment.refunded` events both carry that same
`data.metadata`, letting you credit/claw-back the right account. Do not rely on
the amount in `data.object` for entitlement decisions — resolve it from your own
catalog keyed by your metadata.

---

## 7. Retries, backoff, DLQ, replay, rotation

**Delivery state machine** — every matching event is first recorded as a
`PENDING` delivery row (idempotent on `(endpoint_id, event_id)`), then attempted:

- **Delivered** (2xx) → `DELIVERED`. Never auto-re-sent.
- **Transient** (5xx, 408, 429, network/refused-3xx) → `FAILED` with an
  exponential-backoff retry, until `max_attempts` is reached → `DEAD` (a DLQ —
  never dropped).
- **Permanent** (4xx except 408/429, or the target now resolving to a private
  address) → `DEAD` immediately. Recover via replay.

**Backoff** — base 30s × 2^(attempt−1), capped at 1h, with half-to-full jitter.
With `max_attempts = 8` the cumulative window is roughly
30s, 1m, 2m, 4m, 8m, 16m, 32m ≈ **1h05m** before a row is parked `DEAD`. A
leader-locked retrier polls due rows every ~5s.

**Inspect deliveries** (admin/operator):

```bash
curl "http://localhost:8090/v1/webhook-deliveries?endpointId=we_xxx" \
  -H "Authorization: Bearer sk_test_xxx"
```

The delivery record exposes `status`, `attempt_count`/`max_attempts`,
`last_status_code`, `last_error`, and `next_attempt_at`. It carries **no event
body and no secret** by construction.

**Replay** a `DELIVERED`/`DEAD`/`FAILED` row (admin) — the only way a delivered
row is re-sent:

```bash
curl -X POST http://localhost:8090/v1/webhook-deliveries/whd_xxx/replay \
  -H "Authorization: Bearer sk_test_xxx"   # admin-role key (operator → 403)
```

**Rotate the signing secret** (admin) — returns the new `whsec_…` once; the next
attempt signs with it automatically:

```bash
curl -X POST http://localhost:8090/v1/webhook-endpoints/we_xxx/rotate-secret \
  -H "Authorization: Bearer sk_test_xxx"   # admin-role key (operator → 403)
```

---

## 8. SSRF rules (SEC-4b)

The delivery target must be a **public HTTPS** URL. The URL validator is
fail-closed and rejects, at **both registration and delivery**:

- non-`https` schemes
- loopback / any-local / link-local
- private (RFC1918) / ULA (`fc00::/7`) / CGNAT (`100.64.0.0/10`)
- multicast and cloud-metadata (`169.254.169.254`)
- IPv4-mapped-internal addresses
- hosts that fail to resolve (NXDOMAIN)

At delivery the URL is re-validated and the TCP connection is **IP-pinned** to the
validated address (anti-DNS-rebinding), and redirects are disabled. Because of
this, **you cannot deliver to `localhost`** in local dev. To receive webhooks
locally, either run a public HTTPS tunnel (e.g. `ngrok http 4000`) and register
the tunnel URL, or verify signatures offline against events you construct
yourself with the `whsec_…` secret. See `docs/LOCAL_DEV.md` §6.
