# Handoff — 2026-06-10 — session 2 (B-024 gate coverage DONE, B-002 RLS hardened)
NOW: **CI green** (CI + perpetua-gates) on perpetua/bootstrap / PR #1 (MERGEABLE). App
boots end-to-end on real Postgres/Kafka/Redis; 292 tests pass. Ran ultracode (multi-agent
design + adversarial-review workflows).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (this session): **B-024 gate coverage** — @Primary GatedPaymentGateway decorator at
the PaymentGatewayPort boundary screens ALL PSP callers; flow-aware (sanctions hard-block
all rails; fraud BLOCK→capture-held REVIEW interactive / capture+flag server rails);
payment_capture_hold table enforces REVIEW at capture + links assessment (closes B-027).
T3 review caught + I fixed B1/B2 (confirm auto-capture + skipped-sanctions), M1 (billing
dunning regression), M3, L1. **B-002 set 1** (dormant, CI-green): nexuspay_system BYPASSRLS
role, cross-schema grants, MV ownership, and the USING→WITH CHECK write-leak fix (36
policies). ADR-010/011, L-031/032. Latest commit d92a2dd.
STATE: CI green; 292 tests (floor 250→285); coverage floor 16.
WATCH OUT:
- **B-002 is NOT YET ENFORCING in prod** — app still connects as owner (bypasses RLS). The
  write-leak fix + role/grants are dormant prep. Runtime activation = **B-002-activation**
  (rfc-b002 C5-C7): RlsAwareJpaTransactionManager GUC + 2nd systemDataSource/EMF/txManager +
  re-route 16 @Scheduled jobs + FORCE migration. HIGH blast radius (2nd EMF boot + 16 jobs);
  HUMAN-GATED cutover (provision nexuspay_system secret, flip NEXUSPAY_DB_USER→nexuspay_app +
  rls.enforce=true, staging→canary→fleet). RlsIsolationIntegrationTest is the gate. Deferred
  this session: not safe to rush + can't validate the cutover without staging.
- **B-024 changed billing/workflow behavior NOW** (intended): those charges are screened;
  a flagged server-rail charge CAPTURES + is logged for review (not held, not declined) —
  sanctions still hard-block. Emitting a durable review event/analyst queue = B-030.
- **B-029 (HIGH, latent):** gate mode + tenant come from request metadata; current callers
  stamp server-side (safe today) but a future client-metadata-forwarding caller could claim
  the softer rail / spoof tenant. Derive from trusted call-site identity (ties to activation).
- **B-025/B-026 still open:** sanctions geography is client-metadata-derived; the OFAC CSV
  parser is broken (4-country static fallback). Do NOT advertise OFAC coverage.
- BUILD: JDK 21 + TMP=C:\Temp, then .\gradlew.bat. No Docker locally → ITs run only in CI;
  diagnose CI via the `test-results` artifact (JUnit HTML has the real stack/SQL).
- B-022 (stuck-APPROVED refund) open. Q-002/Q-006 open. CHARTER still L1 (update to L2).
BUDGET: very heavy (B-011 saga + B-006 + B-003 + B-024 full + B-002 set 1, two ultracode
workflows). limit status: ok.
QUEUE (pick by value): B-002-activation (staged, T3, needs staging) · B-029 (gate authority,
HIGH) · B-025/B-026 (sanctions authority + OFAC parser) · B-030 (review event) · B-014
(coverage on thin modules) · B-023 (checkout-sdk npm vulns) · CHARTER L1→L2.
