# Handoff — 2026-06-10 — session 1 (B-011 bring-up, B-006 scans, B-003 gate, B-002 proof)
NOW: **CI fully green** (CI + perpetua-gates) on perpetua/bootstrap / PR #1 (MERGEABLE).
App boots end-to-end on real Postgres/Kafka/Redis; full integration suite passes.
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (this session): B-011 (Flyway + the whole never-run integration-suite bring-up →
first green CI); B-006 (gitleaks + osv-scanner as pinned binaries, both gates green;
gitleaks allowlist for the B-004 default-secret literals); **B-003** fraud+sanctions
pre-auth gate FIRST CUT (wired on the interactive create path, unit+IT tested, CI-green;
also fixed a pre-existing PaymentException arg-swap) — T3 review found real gaps, all
tracked B-024..B-028 + ADR-009; **B-002** acceptance gate PROVEN (RlsIsolationIntegration
Test green — V2001 policies isolate tenants + fail-closed via the non-owner role), prod
activation scoped + deferred (see rfc-b002 + below). Latest commit ~366d291.
STATE: CI green; ~265 tests (250 unit + integration incl. the 2 new gate/RLS ITs);
coverage floor 16; test floor 250.
WATCH OUT:
- **B-002 is PROVEN-correct but NOT YET ENFORCING in prod.** The app connects as the
  table OWNER (bypasses RLS). Activation = non-owner role + per-tx GUC + a system path
  for 16 cross-tenant @Scheduled jobs — HIGH blast radius, gated on an A-vs-B security
  ADR (system-tenant policy bypass vs separate owner datasource). Full plan in
  research/rfc-b002. Do it as a STAGED change, not a blind flip. RlsIsolationIntegration
  Test is the gate.
- **B-003 is a SCOPED first cut, NOT a complete sanctions control.** Only the REST
  create path is gated (not confirm/capture or billing/b2b/workflow callers → B-024);
  sanctions geography is client-supplied metadata (B-025); OFAC parser is broken →
  4-country static fallback (B-026). Don't advertise OFAC coverage. ADR-009, L-031.
- These migrations had never run before B-011 → effectively unreleased; edited in place
  (safe: fresh Testcontainers DB per CI run).
- BUILD: JDK 21 + TMP=C:\Temp, then .\gradlew.bat. No Docker locally → ITs run only in CI.
  Diagnose CI failures via the `test-results` artifact (JUnit HTML has the real stack/SQL).
- B-022 (stuck-APPROVED refund recovery) still open. Q-006/Q-002 open.
BUDGET: very heavy session. limit status: ok.
QUEUE (no build/boot blockers; pick by value):
- B-002 activation (staged; needs the A-vs-B ADR) · B-024 gate coverage (BLOCKER-class) ·
  B-025/B-026 sanctions authority + OFAC parser · B-027 REVIEW/idempotent fraud ·
  B-023 checkout-sdk npm vulns (then OSV→blocking) · B-014 coverage on thin modules ·
  update CHARTER L1→L2.
