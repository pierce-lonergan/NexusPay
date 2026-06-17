# @nexus-pay/node

Typed NexusPay **server** SDK for Node.js (>=18): a payments API client plus
webhook signature verification. Zero runtime dependencies (uses the global
`fetch`/`AbortController`).

```bash
npm install @nexus-pay/node
```

## Client

```ts
import { NexusPay } from '@nexus-pay/node';

const nexus = new NexusPay({
  apiKey: process.env.NEXUSPAY_SECRET_KEY!, // "sk_..."; never logged
  baseUrl: 'https://api.nexuspay.io',
});

// `capture: true` is the boolean alias for capture_method: 'automatic'
// (`false` -> 'manual'). Pass an idempotencyKey to make the create safely
// retryable — a retry with the same key returns the original payment.
const payment = await nexus.createPayment(
  { amount: 1000, currency: 'usd', capture: true },
  { idempotencyKey: 'order_42_attempt_1' },
);

console.log(payment.id, payment.status);
```

`amount` is in the currency's minor unit (e.g. cents). Non-2xx responses throw a
`NexusPayError` carrying the gateway's `type` / `code` / `message` / `requestId`.

## Verifying webhooks

> ⚠️ **Pass the EXACT raw request body** — the bytes received off the wire, as a
> `string` or `Buffer`. The signature is an HMAC over those exact bytes;
> re-serializing the parsed JSON (`JSON.stringify(req.body)`) reorders/reformats
> keys and **will break verification**. This is the #1 webhook integration
> failure. Mount a raw-body parser on the webhook route and never let JSON
> middleware touch it first.

```ts
import express from 'express';
import { constructEvent, SignatureVerificationError } from '@nexus-pay/node';

const app = express();

// Raw body ONLY on the webhook route — `req.body` is a Buffer here.
app.post(
  '/webhooks/nexuspay',
  express.raw({ type: 'application/json' }),
  (req, res) => {
    try {
      const event = constructEvent(
        req.body, // the raw Buffer — NOT a parsed object, NOT re-stringified
        req.headers, // case-insensitive X-NexusPay-Signature / -Timestamp lookup
        process.env.NEXUSPAY_WEBHOOK_SECRET!, // "whsec_..."
        // OPTIONAL hardened replay window — see note below.
        { createdToleranceSeconds: 300 },
      );

      switch (event.type) {
        case 'payment.succeeded':
          // event.data.object is the payment; event.data.metadata is your map.
          break;
        default:
          break;
      }
      res.sendStatus(200);
    } catch (err) {
      if (err instanceof SignatureVerificationError) {
        return res.status(400).send(`Webhook error: ${err.code}`);
      }
      throw err;
    }
  },
);
```

`constructEvent` verifies the signature (timing-safe), optionally enforces a
replay window, then parses the body into a typed `WebhookEvent`. It throws
`SignatureVerificationError` (with `code`: `missing_signature`,
`invalid_signature`, `timestamp_out_of_tolerance`, or `invalid_payload`) on any
failure. Use `verifyWebhook(rawBody, signature, secret)` if you only need the
boolean.

### Replay protection

There are two replay-window options, and they are **not** equivalent:

- `createdToleranceSeconds` — **hardened.** Anchors on the signed `created`
  field inside the verified envelope, which is covered by the HMAC and cannot be
  forged or rewritten by a replayer. **Prefer this.**
- `toleranceSeconds` — **advisory only.** Anchors on the `X-NexusPay-Timestamp`
  header, which the platform does **not** include in the HMAC. An attacker who
  captures a valid delivery can replay the exact body + signature while
  rewriting that header to "now", and the check (and signature) will still pass.
  Treat it as a coarse freshness hint, not a security control.
