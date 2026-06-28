# Local webhook development with the `nexuspay` CLI

This is the Stripe-CLI-style loop for developing and debugging your NexusPay
webhook handler **locally**, without deploying anything or driving real
payments:

1. `nexuspay listen` runs a small loopback HTTP server that **receives**, **verifies**,
   and optionally **forwards** webhooks to your app.
2. `nexuspay trigger <event_type>` asks the platform to **synthesize and deliver**
   a signed TEST-MODE webhook to your tenant's endpoints — through the exact same
   signed delivery pipeline a real event uses.

Because the trigger goes through the real delivery pipeline, the signature your
listener verifies is the real thing: if it verifies here, your production handler
will verify it too.

## Prerequisites

- Node.js >= 18.
- `@nexus-pay/node` installed (the `nexuspay` bin ships with the package). For a
  local checkout you can run it via `node dist/bin/nexuspay.cjs …` after a build,
  or `npx nexuspay …` once installed.
- A **test** secret key (`sk_test_…`).
- A registered, **enabled** webhook endpoint on your tenant, subscribed to the
  event type you want (or `*`). The trigger only delivers if such an endpoint
  exists — otherwise nothing is sent.
- That endpoint's **signing secret** (`whsec_…`).

> The endpoint you register must point at where `nexuspay listen` is reachable.
> For a fully local loop, expose the listener with a tunnel (e.g. an SSH/ngrok
> tunnel) and register that tunnel URL as your endpoint, or register your
> listener's public address. `listen` itself always binds loopback; the tunnel is
> what exposes it.

## The loop

### 1. Start the listener (terminal A)

```bash
export NEXUSPAY_WEBHOOK_SECRET=whsec_your_endpoint_secret
nexuspay listen --port 4242 --forward-to http://localhost:3000/webhooks/nexuspay
# listening on http://127.0.0.1:4242
# forwarding verified deliveries to http://localhost:3000/webhooks/nexuspay
```

`listen`:

- binds **`127.0.0.1` only** (loopback);
- reads the **raw bytes** of each POST (byte-exact — required for HMAC);
- verifies the `x-nexuspay-signature` header with the SDK's `verifyWebhook`;
- prints `type=<t> id=<id> verified=true` plus the pretty-printed event, and
  responds `200`;
- on a bad/missing signature prints `verified=false` and responds `400`;
- if `--forward-to` is set, relays the **raw body + original signature/timestamp/
  event/content-type headers** to your app — **only after a successful verify** —
  so your app verifies the same untouched bytes.

### 2. Fire a test event (terminal B)

```bash
export NEXUSPAY_SECRET_KEY=sk_test_your_key
export NEXUSPAY_BASE_URL=http://localhost:8090   # or your NexusPay base URL
nexuspay trigger payment.succeeded --data '{"amount":1000,"currency":"USD"}'
# -> {"id":"evt_…","type":"payment.succeeded","livemode":false}
```

The platform synthesizes a `payment.succeeded` webhook (merging your `--data`
overlay onto `data.object`), signs it, and delivers it to your endpoint. Over in
terminal A you should see the delivery arrive, verify, and forward to your app.

### 3. Debug

- **`verified=false` in the listener** — your `--secret` doesn't match the
  endpoint's signing secret, or a proxy mangled the body. The signing secret is
  per endpoint; copy it from the endpoint you registered.
- **Nothing arrives** — the trigger only delivers to an **enabled** endpoint
  subscribed to that type (or `*`). Register/enable one first.
- **`error: refusing a live key`** — `trigger` refuses `sk_live_` keys; use your
  `sk_test_` key.

## Security model

The listener is the network-facing surface, so it is deliberately conservative:

- **Loopback-only bind.** `listen` binds `127.0.0.1` and never `0.0.0.0`. A
  non-loopback bind would expose your local receiver to the network. If you need
  external reach, put it behind a tunnel you control rather than binding wide.
- **Secrets are never printed.** Neither the webhook signing secret nor the API
  key is ever written to stdout, stderr, or logs — not on success, not on error,
  not in any verbose mode. Error output is the error message only.
- **Verification reuses the SDK.** Signature checks go through the library's
  `verifyWebhook`/`constructEvent` (constant-time `timingSafeEqual`), so the CLI
  can't drift from the platform signer. The CLI does not implement its own HMAC.
- **`sk_live_` is refused.** `trigger` fails fast client-side on a live key — a
  test tool must never be pointed at live.
- **Forward only after verify, loopback by default.** `--forward-to` relays a
  delivery **only** once its signature verifies, and **only** to a loopback
  target (`127.0.0.1` / `localhost` / `::1`) unless you explicitly pass
  `--allow-remote`. This prevents the listener from becoming an open relay that
  forwards arbitrary (or unverified) traffic to a remote host.
