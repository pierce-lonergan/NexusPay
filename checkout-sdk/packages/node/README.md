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
  apiKey: process.env.NEXUSPAY_API_KEY!,   // "sk_test_…" / "sk_live_…"; never logged
  baseUrl: process.env.NEXUSPAY_API_URL!,  // your NexusPay base URL, e.g. http://localhost:8090
});

// `capture: true` is the boolean alias for capture_method: 'automatic'
// (`false` -> 'manual'). Pass an idempotencyKey to make the create safely
// retryable — a retry with the SAME key returns the original payment. Use a fresh
// key per ATTEMPT (not one stable across distinct purchases), or a repeat buy is
// silently served the first attempt's cached response for 24h.
const payment = await nexus.createPayment(
  { amount: 1000, currency: 'USD', capture: true },
  { idempotencyKey: crypto.randomUUID() },
);

console.log(payment.id, payment.status);
```

`amount` is in the currency's minor unit (e.g. cents). Non-2xx responses throw a
`NexusPayError` carrying the gateway's `type` / `code` / `message` / `requestId`.
A request **timeout** rejects with `NexusPayError` (`type: 'network_error'`,
`code: 'timeout'`).

> **0.1.1 behavior change — cancellation vs timeout.** If you pass your own
> `AbortSignal` via the per-call `{ signal }` option and then abort it, the
> request now rejects with the **raw `AbortError`** (a `DOMException`), not a
> `NexusPayError`. In 0.1.0 a caller abort was mis-mapped to
> `NexusPayError(code: 'timeout')`. A genuine timeout is unchanged (still
> `NexusPayError`). If your `catch` checks `err instanceof NexusPayError` and
> you cancel via `{ signal }`, also handle the `AbortError` (`err.name ===
> 'AbortError'`). Callers that don't use `{ signal }` are unaffected.

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

## Testing your integration (0.1.2)

These helpers let you unit-test your webhook handler and any SDK-using code
**without** a live delivery or a network round-trip.

### Generate a valid test signature (`generateTestHeaderString`)

Sign a body with the same HMAC the platform uses, so you can exercise your
handler's `constructEvent` / `verifyWebhook` path in a unit test:

```ts
import { constructEvent, generateTestHeaderString, buildTestEvent } from '@nexus-pay/node';

const { body, ...headers } = generateTestHeaderString({
  payload: buildTestEvent('payment.succeeded'), // an object — stringified once
  secret: 'whsec_test',
});

// `body` is the EXACT bytes that were signed — pass THESE (not a re-stringify):
const event = constructEvent(body, headers, 'whsec_test'); // your handler under test
```

> ⚠️ If `payload` is an object it is `JSON.stringify`-ed **once**; feed the
> returned `body` to your handler. Re-serializing the object yourself reorders
> keys and breaks the signature (same caveat as real webhooks above).
> `generateTestSignature(payload, secret)` returns just the bare-hex signature
> for the `verifyWebhook` boolean path.

### Typed event fixtures (`testFixtures` / `buildTestEvent`)

Ready-made, typed sample events — one per `WEBHOOK_EVENT_TYPES`:

```ts
import { testFixtures, buildTestEvent } from '@nexus-pay/node';

const succeeded = testFixtures['payment.succeeded']; // a full, typed WebhookEvent
const custom = buildTestEvent('payment.succeeded', {
  data: { object: { amount: 999 } }, // shallow-merged; a wrong field is a compile error
});
```

### Connectivity / credentials check (`client.ping`)

A lightweight authenticated check — confirms the base URL is reachable, the key
is valid, and which **mode** the key is (so you can verify test-vs-live):

```ts
const { ok, livemode, apiVersion } = await nexus.ping();
// livemode === false for an sk_test_ key, true for an sk_live_ key.
```

It carries no tenant or anything sensitive — just `{ ok, livemode, apiVersion }`.

### Inject a fake transport (`createTestTransport`)

Unit-test code that uses the client with **no network** by injecting a fake
`fetch` into the existing `fetch` option:

```ts
import { NexusPay, createTestTransport } from '@nexus-pay/node';

const transport = createTestTransport({
  'GET /v1/ping': () => ({ status: 200, body: { ok: true, livemode: false, api_version: '2026-06-16' } }),
  'POST /v1/payments': (req) => ({ status: 201, body: { id: 'pay_test_1', status: 'succeeded' } }),
});

const nexus = new NexusPay({ apiKey: 'sk_test_x', baseUrl: 'https://api.test', fetch: transport });
await nexus.ping();
transport.calls; // recorded: [{ method, path, url, headers, body }]
```

Handlers are keyed by `"<METHOD> <path>"`; an unmatched route returns a 404
`NexusPayError`. Non-2xx bodies should be the `{ error: { type, code, message } }`
envelope so `NexusPayError` maps them.

## `nexuspay` CLI

Installing the package adds a `nexuspay` command — a Stripe-CLI-style local
webhook test loop. Run `nexuspay listen` in one terminal to receive + verify
webhooks locally (and optionally forward them to your app), and
`nexuspay trigger <event_type>` in another to fire a TEST-MODE event through the
platform's signed delivery pipeline.

```bash
# Terminal A — receive, verify, and forward to your running app:
nexuspay listen --forward-to http://localhost:3000/webhooks/nexuspay

# Terminal B — fire a signed test event to your tenant's endpoints:
nexuspay trigger payment.succeeded
```

See [`docs/LOCAL_DEV.md`](./docs/LOCAL_DEV.md) for the full walkthrough.

### `nexuspay trigger <event_type>`

Fires a TEST-MODE webhook (`POST /v1/test/events`) to your tenant's endpoints.

| Flag | Env | Description |
| --- | --- | --- |
| `--key` | `NEXUSPAY_SECRET_KEY` (alias `NEXUSPAY_API_KEY`) | Test secret key (`sk_test_…`) |
| `--base-url` | `NEXUSPAY_BASE_URL` (alias `NEXUSPAY_API_URL`) | API base URL, e.g. `http://localhost:8090` |
| `--id` | — | Optional test aggregate id |
| `--data` | — | Optional JSON object merged onto `data.object` |

```bash
NEXUSPAY_SECRET_KEY=sk_test_… NEXUSPAY_BASE_URL=http://localhost:8090 \
  nexuspay trigger payment.succeeded --data '{"amount":1000}'
# -> {"id":"evt_…","type":"payment.succeeded","livemode":false}
```

A **live key (`sk_live_`) is refused** client-side — the trigger is test-mode
only. The API key is never printed, even on error.

The `<event_type>` is validated client-side against the canonical
`WEBHOOK_EVENT_TYPES` list, so a typo (e.g. `payment.succeded`) fails fast with a
"did you mean" suggestion instead of a slow, opaque server error after a network
round-trip.

### `nexuspay listen`

Starts a local HTTP server **bound to `127.0.0.1` only** (loopback — it must not
be reachable from the network), verifies each incoming POST with the SDK's
`verifyWebhook`/`constructEvent`, prints a concise per-event line plus the pretty
body, and responds `200` on a good signature / `400` on a bad one.

| Flag | Env | Description |
| --- | --- | --- |
| `--port` | — | Port to listen on (default `4242`) |
| `--secret` | `NEXUSPAY_WEBHOOK_SECRET` | Endpoint signing secret (`whsec_…`) |
| `--forward-to` | — | Relay verified deliveries to a local app URL |
| `--allow-remote` | — | Allow a non-loopback `--forward-to` target (open-relay risk) |

```bash
NEXUSPAY_WEBHOOK_SECRET=whsec_… \
  nexuspay listen --port 4242 --forward-to http://localhost:3000/webhooks/nexuspay
```

Security stance:

- **Loopback-only bind.** The receiver never listens on `0.0.0.0`.
- **The signing secret and API key are never printed** — not on success, not on
  error, not in any verbose path.
- **Signature verification reuses the SDK** (`verifyWebhook`, constant-time) — the
  CLI does not hand-roll HMAC, so it can't drift from the platform signer.
- **`--forward-to` relays ONLY after a successful verify**, and refuses a
  non-loopback target unless you pass `--allow-remote` (so it can't become an
  open relay).

`Ctrl-C` (SIGINT) shuts the server down cleanly.
