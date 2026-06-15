# Handoff — 2026-06-14 — session 5 (6-item program shipped + CHARTER ratified L2)
NOW: `main` carries the full 6-item program, each landed CI-green (ci.yml build+test+IT AND
perpetua-gates) and fast-forwarded to main. Tip before this commit = 2e87451 (B-006); this commit
adds the CHARTER L1→L2 governance sync. Ran ultracode throughout — one dedicated workflow per item
(grounded investigate → design → aggressive tests → adversarial/security review → fix → CI → FF main).
ACTIVE ITEM: program complete; phase SELECT for the next backlog item | branch perpetua/bootstrap.

DONE (this session) — 6 dedicated-workflow items, all on main:
- **B-015** RFC-4180 settlement CSV (Jackson CsvMapper; per-row ParseFailure → persisted PARSE_ERROR,
  no silent money-drop; durable in a REQUIRES_NEW tx). ADR-? / L-039. ~33 tests.
- **B-025/B-026** server-authoritative sanctions geography + fail-closed OFAC (real-feed parser vs the
  actual 29-col CSL; injectable feed URL so tests never hit the network). ADR-014 / L-040. ~62 tests.
- **B-029** server-authoritative gate mode+tenant (CallContext + payment_screening_origin) + idempotent
  fraud assess (dedup + unique-index backstop via saveAndFlush). ADR-015 / L-041. (test_count_floor→408)
- **B-014** coverage on the thin money/security modules — aggregate line coverage 16%→35% (5090/14530),
  ~272 meaningful tests; found a REAL ledger NPE-on-null-event-type bug (now guarded). L-042. floor 408→680.
- **B-006** semgrep Java SAST wired into CI (pinned semgrep/semgrep:1.166.0, report-only first per §15.3);
  CI confirmed 100 rules / 703 files / 0 findings. L-043 (report-only steps can still red the build 2 ways).
- **CHARTER L1→L2** synced CHARTER.md + CLAUDE.md core to the human-ratified L2 (Q-001 / ADR-016).

STATE: ratchets test_count_floor=680, coverage_floor_pct=33 (raised only; never silently lowered).
Autonomy now L2 (push freely on perpetua/**; merge to main when ALL CI gates pass; **tier-3 always via PR**).

### ⚠ B-002 CUTOVER CHECKLIST (human-gated; in order) — UNCHANGED, still pending
1. **B-002-cutover Step 0 (MANDATORY PRE-FLIP):** stamp the real tenant on `nexuspay.payments` —
   HyperSwitchWebhookController omits it today (4 payments-topic consumers bind "default" under
   enforcement → real-tenant rows invisible). Resolve merchant→tenant at HS ingest, stamp
   metadata.tenant_id + tenant-aware OutboxEvent ctor; then write the deferred per-site ITs (gateway,
   the 3 sweeps, routing/ledger consumers) against real-tenant fixtures.
2. **B-002-cutover flip:** provision real nexuspay_app/nexuspay_system secrets; staging→canary→fleet
   set app role→nexuspay_app, rls.enforce=true, rlsforce=true. Rollback: enforce=false (+ rlsforce=false).
   RlsEnforceIT is the pre-flip gate. **The agent must NOT do this flip — it is human-gated (L2 carve-out).**

WATCH OUT:
- The "default" tenant fallback in consumers is a fail-closed placeholder until Step 0 — do NOT flip
  prod enforce=true before Step 0 or payment events silently no-op.
- Pre-existing cross-tenant webhook fan-out leak: dormant at enforce=false; FIXED only under enforcement.
- BUILD: JDK 21 + TMP=C:\Temp; no Docker locally AND the Gradle daemon can't fork in this sandbox →
  ALL verification via CI. Confirm the BUILD+TEST workflow ran (not just perpetua-gates) before claiming
  green (L-038). FF pattern: `git push origin perpetua/bootstrap:main` + `git update-ref refs/heads/main <sha>`.

QUEUE (pick by value): B-002-cutover Step 0 (pre-flip, T3) · flip semgrep SAST report-only→blocking +
add a semgrep ratchet once the runner is confirmed 0 (branch on exit codes 0/1/≥2, see L-043) ·
Q-002 branch protection on main (§18.3 backstop, human) · B-012 SHA-pin all actions · B-016/B-017
(testcontainers/regression) · B-022 (stuck-APPROVED refund reconciler, T3) · B-023 (npm vulns) ·
residual hardening: B-026-hardening (Syria program token, region tagging, unknown-geo kill-switch),
B-029-hardening (request fingerprint on dedup hit).
