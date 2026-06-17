# Changelog — @nexus-pay/node

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
