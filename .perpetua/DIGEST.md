# DIGEST — human-facing summaries (newest first)

## 2026-06-10 — Token-aware adaptive scheduling (B-019, human-directed)
**Shipped:** the harness now spends the 5-hour token window deliberately instead of
on a fixed timer. Each cycle it reads local usage (ccusage), compares against the
window's budget, and paces: **run back-to-back when behind, cool down when ahead,
pause near the cap** — so the window fills with productive work and quota isn't left
unspent when it rolls. A `rigor=MAX/NORMAL/LEAN/PAUSE` hint rides the session prompt so
the agent also scales DEPTH (more reviewers/audits/research/mutation when budget is
plentiful) — productive burn, never churn. Pure controller is unit-tested (11 tests);
usage reader degrades gracefully without ccusage. Anthropic's server-side 5h/weekly caps
remain the hard backstop. **To use:** `npm i -g ccusage`, then run the loop with
`PERPETUA_MODEL=claude-fable-5`. ADR-007; follow-ups B-020 (ccusage-fixture test), B-021
(weekly-cap awareness).

## 2026-06-10 — Session 0 iterations (B-010, B-001)
**Shipped (2 items, full §4 loop each, both reviewed by subagents):**
- **B-010** settlement-ingest jsonb mapping — `raw_data` was a String on a jsonb
  column with no `@JdbcTypeCode`, so every settlement-file INSERT aborted; the
  Stripe parser also stored a non-JSON line. Fixed + first-ever reconciliation
  tests (4). Adversarial review: SHIP.
- **B-001** billing scheduler double-billing — the renewal/dunning/trial crons
  fired on every replica with no coordination. Added `SchedulerLock` (Valkey,
  fail-closed, self-renewing lease, atomic Lua release, reentrancy guard) on all
  3 crons (10 tests). Adversarial review caught a real TTL-expiry-mid-run
  double-charge BLOCKER → fixed with lease renewal; security review SHIP.
**Tests:** 188→202 executed pass (+14), 0 fail, build green. **Threat model:**
double-billing OPEN → PARTIALLY CLOSED. **Decided:** ADR-006 (fail-closed +
renew). **Honesty note:** corrected the ratchet test-count floor (was an
un-measured 201 → measured 202). **Follow-ups:** B-015..B-018.
**Next:** B-004 (secrets fail-fast), B-006 (baseline scans), B-005 (coverage).

## 2026-06-09 — Bootstrap + first fixes
**Shipped:** Took NexusPay from *not compiling* to a green build — 201 unit tests
pass, the executable boot jar assembles, and the app now boots through full Spring
bean wiring (verified: it fails only on `Connection refused` to Postgres, i.e. no
infra, not a code defect). Committed as `4a1c6ea` on branch `perpetua/bootstrap`.
Fixed: 3 app-startup blockers, ledger double-entry integrity (per-currency
balancing, real optimistic locking, account-id convention), a batch of money bugs
(billing idempotency/dunning/date-math, fraud score polarity + currency exponent,
marketplace split reconciliation + payout gating, dispute double-post), and a
security batch (idempotency cross-tenant key, constant-time HMAC, evidence path
traversal, vault PAN fingerprint/BIN).

**Decided:** Bootstrap PERPETUA at L1 (ADR-001). Modulith OPEN modules (ADR-002).
App-class relocation (ADR-003). DataSource BeanPostProcessor (ADR-004).
Ledger-backed reconciliation/dispute ports (ADR-005).

**Learned:** 14 root-cause lessons (LESSONS.md). Recurring classes: `startup/*`
(×3 → systemic guardrail = CI context-load smoke test) and `money/*` (×3 →
systemic guardrail = mutation testing on ledger/billing/fraud).

**Security/quality trend:** 6 open HIGH design findings (RLS inert, fraud/sanctions
not gating, defaulted secrets, maker-checker refund, dispute webhook). Coverage +
mutation UNMEASURED — wiring them is queued (B-005). No automated scans run yet
(B-006).

**Open questions (BLOCKING):** ratify CHARTER + autonomy level + push/PR
permission (Q-001); branch protection (Q-002). Until answered: local-only L1.

**Next focus:** cheap correctness defects (settlement-ingest jsonb B-010,
reconciliation PARTIAL B-008) and double-billing locks (B-001).
