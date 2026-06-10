# LESSONS — root-cause classes & guardrails

Format: `L-NNN | date | class | bug | guardrail | link`

L-001 | 2026-06-09 | money/cross-currency-aggregation | Journal entry "balanced" check summed minor units across currencies, accepting +10000 JPY / −10000 USD as balanced | Per-currency zero-sum in `JournalEntry`; regression test `JournalEntryTest`; GUARDRAIL TODO: DB CHECK constraint (B backlog) | 4a1c6ea
L-002 | 2026-06-09 | persistence/no-op-optimistic-lock | Bulk JPQL balance UPDATE checked `version` but never incremented it → lost updates undetectable | UPDATE now `SET version = version+1`; `@Modifying(clearAutomatically,flushAutomatically)` | 4a1c6ea
L-003 | 2026-06-09 | identifier-convention-drift | Two different account-id conventions (`la_merchant_recv_` vs slug) → every non-USD payment dead-lettered | Single canonical `EnsureAccountsExistUseCase.accountId()` + typed helpers used everywhere | 4a1c6ea
L-004 | 2026-06-09 | idempotency/wall-clock-key | Billing idempotency key embedded `System.currentTimeMillis()` → every retry a fresh key → double charges | Deterministic key per (invoice, logical attempt); GUARDRAIL TODO: lint for currentTimeMillis in idempotency keys | 4a1c6ea
L-005 | 2026-06-09 | fraud/score-polarity | Signifyd TRUST score (higher=safer) used directly as RISK score → approved fraud, blocked good orders | Inverted mapping with documented polarity; GUARDRAIL TODO: provider-scale unit tests (B-014) | 4a1c6ea
L-006 | 2026-06-09 | money/currency-exponent | FRM amount conversions hardcoded ÷100 / ×10000 → 10–100× wrong for JPY/BHD | `FrmAmounts` derives factor from `Currency.getDefaultFractionDigits()` | 4a1c6ea
L-007 | 2026-06-09 | security/non-constant-time-compare | Webhook HMAC compared with `String.equalsIgnoreCase` (timing side-channel) | `MessageDigest.isEqual`; GUARDRAIL TODO: semgrep rule for HMAC string compare | 4a1c6ea
L-008 | 2026-06-09 | security/path-traversal | Evidence storage joined attacker-controlled filename into a path | Sanitize each segment + `normalize().startsWith(root)` check | 4a1c6ea
L-009 | 2026-06-09 | pci/unkeyed-fingerprint | PAN fingerprint was unkeyed SHA-256 (brute-forceable low-entropy PAN) | HMAC-SHA256 keyed from master key; store 6-digit BIN not 8 | 4a1c6ea
L-010 | 2026-06-09 | security/cross-tenant-cache-key | Idempotency cache key was the raw client header → cross-merchant response leakage; 5xx cached 24h | Scope key per caller credential hash; never cache 5xx | 4a1c6ea
L-011 | 2026-06-09 | startup/component-scan-root | @SpringBootApplication under io.nexuspay.app hid sibling modules' entities + all Modulith modules | Moved to io.nexuspay root; ModulithVerificationTest now meaningful | 4a1c6ea
L-012 | 2026-06-09 | startup/unimplemented-port | Port interfaces injected into @Service beans with no implementation → context fails (reconciliation, dispute) | Implemented adapters; GUARDRAIL: a context-load smoke test would catch this class (IDEAS) | 4a1c6ea
L-013 | 2026-06-09 | startup/bean-cycle | DataSource-decorating @Bean that consumed a DataSource created a Flyway↔datasource cycle | Decorate via BeanPostProcessor (no new bean edge) | 4a1c6ea
L-014 | 2026-06-09 | outbox/ack-ordering | Relay marked events published before Kafka ack → events lost on broker outage | Await send ack before markPublished | 4a1c6ea

L-017 | 2026-06-10 | security/dev-default-secret-in-prod | Session HMAC key, webhook secret, vault master key defaulted to committed dev values with no prod guard — a missing env var would sign/encrypt with a public key | StartupSecretsValidator fail-fast under prod profile + application-production.yml forcing it + a drift-guard test asserting KNOWN_DEFAULTS stays in sync with application.yml (control can't fail open) | B-004
L-016 | 2026-06-10 | concurrency/lease-shorter-than-work | A fixed-TTL distributed lock over an unbounded long-running (≤500 PSP charges) job can expire mid-run → second replica starts → double-charge (a new invoice per cycle means no downstream idempotency saves it) | Lease renewal at ttl/3 while work runs + atomic owner-checked Lua release; SchedulerLockTest; caught by adversarial review, not initial tests | B-001/ADR-006
L-015 | 2026-06-10 | persistence/jsonb-as-varchar | `settlement_records.raw_data` jsonb column mapped as a plain String (no @JdbcTypeCode) → every ingest INSERT aborts; parser also stored a non-JSON CSV line | @JdbcTypeCode(SqlTypes.JSON) + parser emits valid JSON; StripeCsvParserTest; GUARDRAIL TODO: a grep/arch test for `columnDefinition="jsonb"` String fields lacking the annotation | B-010
- (root-cause class "persistence/*" now 2× with L-002 — meta-review watch)

RECURRING-CLASS WATCH (for meta-review): "startup/*" appears 3× (L-011/12/13) — a
CI context-load smoke test (no external infra, mocked) is the systemic guardrail.
"money/*" appears 3× (L-001/04/06) — mutation testing on ledger/billing/fraud is
the systemic guardrail (B-005/B-014).
