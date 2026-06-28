# Changelog — @nexus-pay/node

## 0.1.2

Backward-compatible patch release. Adds test-ergonomics helpers so you can
unit-test your webhook handler and SDK-using code WITHOUT a live delivery or a
network. No removals; nothing existing changes.

### Added

- **`generateTestHeaderString({ payload, secret, timestamp? })`** — generates a
  VALID webhook signature header bag (`x-nexuspay-signature` /
  `x-nexuspay-timestamp`) plus the canonical `body` that was signed, so you can
  drive `constructEvent(body, headers, secret)` / `verifyWebhook(body, sig,
  secret)` in a unit test. Single-sourced HMAC (reuses the same signer the verify
  path uses). A thin companion `generateTestSignature(payload, secret)` returns
  just the bare-hex signature.
- **`testFixtures` + `buildTestEvent(type, overrides?)`** — typed, ready-made
  sample `WebhookEvent`s, one per `WEBHOOK_EVENT_TYPES`, for handler tests.
  `buildTestEvent` clones a fixture and shallow-merges overrides; everything is
  typed so a wrong field is a compile error.
- **`client.ping(opts?)`** — a lightweight authenticated connectivity +
  credentials check (`GET /v1/ping`). Resolves `{ ok, livemode, apiVersion }`;
  `livemode` reflects the authenticated KEY's mode so you can confirm
  test-vs-live. Carries no tenant or anything sensitive.
- **`createTestTransport(handlers, options?)`** — a typed, `typeof fetch`-
  compatible fake transport for the existing injectable `fetch` option. Maps
  `"<METHOD> <path>"` to a canned `Response`, records every call on `.calls`, and
  returns the INT-2 error envelope for non-2xx so `NexusPayError` maps it.
  Unit-test SDK-using code with no network: `new NexusPay({ apiKey, baseUrl,
  fetch: createTestTransport({...}) })`.
- **`Payment.next_action`** (+ a `NextAction` type) — present ONLY on a
  `requires_action` payment (3DS/SCA), carrying `{ type, url }` so you can drive
  your redirect handling. Absent on every other status. Mirrors the gateway wire
  shape (snake_case, no transform). `Payment.status` is unchanged (a plain
  string), so `requires_action` / `processing` / `requires_capture` flow through
  with no enum change. Pairs with the new TEST-MODE forced outcomes
  (`__test_outcome` = `requires_action` / `processing` / `fraud_hold`; see
  `docs/LOCAL_DEV.md`).

## 0.1.1

Backward-compatible patch release. No removals; new behavior is additive or
opt-in, with one documented cancellation-shape correction.

### Added

- **Opt-in automatic retries** via `maxRetries` (on the client constructor and
  per call through `RequestOptions.maxRetries`). Default `0` preserves the
  historical behavior: exactly one fetch and no auto-generated idempotency key.
  When `> 0`, transient failures (429, 5xx, network/transport errors) are
  retried with exponential backoff, honoring a `Retry-After` header on 429s. For
  a mutating (POST) request, a single `Idempotency-Key` is reused across the
  whole attempt sequence (auto-generated when one isn't supplied) so retries are
  safe by construction.
- `createRefund` now guards the `requires_approval` discriminant: a 2xx whose
  body lacks a boolean `requires_approval` throws
  `NexusPayError(code: 'unexpected_refund_response')` instead of being silently
  treated as a non-approval `Refund`.
- `createPayment` reconciles `capture` and `captureMethod`: when both are set and
  disagree it throws `NexusPayError(code: 'capture_conflict')` client-side,
  before any request is sent. When only one is set, the wire body is unchanged.

### Changed (behavioral)

- **Caller `AbortSignal` cancellation now surfaces the raw `AbortError`.** When
  you pass your own signal via the per-call `{ signal }` option and abort it, the
  request rejects with the raw `AbortError` (`DOMException`, `name:
  'AbortError'`) instead of `NexusPayError(type: 'network_error', code:
  'timeout')` as in 0.1.0. A real timeout is **unchanged** — it still rejects
  with that `NexusPayError`. This makes an intentional cancellation
  distinguishable from a timeout (and lets the retry layer treat caller aborts as
  non-retryable). **Action for callers:** if you cancel via `{ signal }` and your
  `catch` only checks `err instanceof NexusPayError`, also handle `err.name ===
  'AbortError'`. Callers that do not use `{ signal }` (including the documented
  `createPayment` / `getPayment` / `createRefund` / `constructEvent` flows) are
  unaffected.
