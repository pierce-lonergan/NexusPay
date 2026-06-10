# DIGEST — human-facing summaries (newest first)

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
