# Handoff — 2026-06-14 — session 4 (B-002 RLS fully built: machinery + tenant-binding, dormant)
NOW: `main` carries everything through the C5-C7 machinery (FF'd to a696742, PR #1 merged, CI-green).
The B-002-activation-tenant work (helper + 6 consumers + 3 sweeps + fixes + IT) is on
**perpetua/bootstrap via PR #2**, build+test verified — pending FF to `main` once the final CI run is
green. Ran ultracode throughout (design + 2 adversarial-review workflows + agent fan-outs).
ACTIVE ITEM: FF main to the PR #2 tip after CI green | branch perpetua/bootstrap | phase SELECT.
LATEST commits: 79937ea (helper) → e5b9ea8 (consumers) → f91a8ab (sweeps) → 98e102a (review fixes) →
6cfebc3 (analytics IT) → bookkeeping.

DONE (this session): **B-002 RLS — the whole feature is built and dormant behind rls.enforce=false.**
- C5-C7 (prior part of session, on main): routing datasource + RlsRoutingTransactionManager GUC +
  16 @SystemTransactional jobs + repeatable FORCE + analytics fail-closed policies (V3022). ADR-010/012.
- B-002-activation-tenant (this part, PR #2): `TenantWorkRunner` (runInTenant/callInTenant open a
  REQUIRES_NEW APP+tenant tx; bindTenant binds APP+tenant with NO outer tx so an inner @Transactional
  keeps its own isolation). 6 consumers bind before their tx; 3 billing sweeps split SYSTEM discovery
  from per-item APP writes; gateway tenant-scoping gated on the enforce flag. ADR-013, L-036/037/038.
- Adversarial review SHIP-WITH-FIXES → fixed: gateway dormancy (L-037), ledger SERIALIZABLE (L-036).

STATE: CI build+test green via PR #2; coverage floor 16. Process guardrail: ci.yml now also builds on
push:[perpetua/**] (L-038 — branch pushes used to skip build+test once the PR merged).

### ⚠ B-002 CUTOVER CHECKLIST (human-gated; in order)
1. **B-002-cutover Step 0 (MANDATORY PRE-FLIP):** stamp the real tenant on `nexuspay.payments` —
   HyperSwitchWebhookController omits it today (4 payments-topic consumers bind "default" under
   enforcement → real-tenant rows invisible). Resolve merchant→tenant at HS ingest, stamp
   metadata.tenant_id + tenant-aware OutboxEvent ctor; then write the deferred per-site ITs (gateway,
   the 3 sweeps, routing/ledger consumers) against real-tenant fixtures.
2. **B-002-cutover flip:** provision real nexuspay_app/nexuspay_system secrets; staging→canary→fleet
   set app role→nexuspay_app, rls.enforce=true, rlsforce=true. Rollback: enforce=false (+ rlsforce=false).
   RlsEnforceIT is the pre-flip gate.

WATCH OUT:
- The "default" tenant fallback in consumers is a fail-closed placeholder until Step 0 — do NOT flip
  prod enforce=true before Step 0 or payment events silently no-op.
- Pre-existing cross-tenant webhook fan-out leak: preserved (dormant) at enforce=false; FIXED only under
  enforcement (tenant-scoped delivery) — coupled to Step 0.
- B-029 (gate mode/tenant authority, HIGH latent) ties into the per-job tenant binding. B-025/B-026
  (sanctions geography + OFAC parser) still open. B-015 (RFC-4180 CSV money-drop) is the top open defect.
- BUILD: JDK 21 + TMP=C:\Temp; no Docker locally AND the Gradle daemon can't fork in this sandbox →
  ALL verification via CI. Diagnose via the test-results artifact. Confirm the BUILD+TEST workflow ran
  (not just perpetua-gates) before claiming green (L-038).

BUDGET: very heavy this session (C5-C7 + full B-002-activation-tenant + 4 ultracode workflows + agent
fan-outs). limit: ok.
QUEUE (pick by value): FF main after CI green · B-002-cutover Step 0 (pre-flip) · B-015 (RFC-4180 CSV) ·
B-025/B-026 (sanctions) · B-029 (gate authority) · B-014 (coverage) · B-006 (semgrep) · CHARTER L1→L2.
