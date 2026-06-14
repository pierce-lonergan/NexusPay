# DIGEST — human-facing summaries (newest first)

## 2026-06-13 — B-002 RLS runtime machinery C5-C7 DONE (dormant, CI-green; ultracode)
**Shipped:** the full RLS *enforcement* machinery, landed DORMANT behind `rls.enforce=false`
(prod flip stays human-gated), 296 tests green. Three parts:
- **C5 — mechanism:** the app can now connect as the non-owner `nexuspay_app` role and set the
  tenant GUC per transaction. One EntityManagerFactory over a role-routing datasource (app +
  system Hikari pools) + a tx-manager that runs `set_config('app.current_tenant_id',…)` at
  transaction begin. Contributed only when enforcement is on; the broken old decorator (which set
  the GUC at the wrong moment, making RLS a no-op) is deleted.
- **C6 — background jobs:** an ultracode workflow classified all 26 cross-tenant entry points
  (tracing every table each touches, then adversarially trying to refute each verdict). The 15
  genuinely all-tenant jobs (rollups, retention, outbox relay, reconciliation, payouts, dunning…)
  are marked `@SystemTransactional` so they run on a BYPASSRLS role; the 6 single-tenant Kafka
  consumers were deliberately left alone (marking them would open a cross-tenant hole) and are
  documented as the mandatory pre-flip fix. Required relocating the marker to the shared `common`
  module (the jobs live in modules that can't depend on `app`).
- **C7 — owner hardening:** a repeatable migration that FORCEs RLS on the owner too, gated by a
  placeholder defaulting OFF (so nothing is forced while the app still connects as owner — the
  failure mode that would otherwise lock the app out of every row).
**Proven:** a dormancy test (everything absent + nothing forced when off) and an enforcement test
(boots on the locked-down role; tenant A sees only A, an unbound request sees zero rows, a system
job sees all, every table forced). The CI log even shows a real scheduled payment job routing
through the system role under enforcement.
**Caught by self-critique / the workflow:** (a) the enforce test tripped the B-004 default-secret
guard because its profile reads as production — fixed by supplying managed test secrets, NOT by
weakening the guard (L-033); (b) the role pin is a thread-local that doesn't cross async callbacks
(L-034); (c) a system job bypasses the write-side tenant check, so all-tenant sweeps still need
per-row tenant binding on their writes (L-035). ADR-012 records the three deviations from the
original sketch.
**Post-ship adversarial review (ultracode): SHIP-WITH-MINORS** — 0 blockers, 0 must-fix, 8 minors,
all fixed/documented: a missed sibling job (TrialExpiration) annotated → 16 SYSTEM jobs; analytics
RLS policies made fail-closed (migration V3022); the FORCE re-trigger runbook corrected (flipping
the flag alone re-runs it — no file bump; the reviewer disassembled the actual Flyway to confirm);
DLQ-async + superuser-FORCE caveats documented; the FORCE/enforce interlock and a cross-module
routing assertion folded into the activation gate.
**Remaining before any prod flip:** B-002-activation-tenant (bind tenant in the 6
consumers + billing batch writes) then B-002-cutover (provision secrets, flip the flags
staging→fleet). Both in BACKLOG + the HANDOFF checklist.

## 2026-06-10 — B-024 gate coverage DONE + B-002 RLS hardened (ultracode, multi-agent)
Ran this as a multi-agent (ultracode) effort: a design+mapping workflow (exhaustive
payment-entrypoint + 16-@Scheduled-job inventory → judge panels → ordered plan), then
implemented, then a 4-lens adversarial+security review workflow that **caught real
bugs I'd shipped** and drove fixes.
**B-024 (gate coverage) — DONE, CI-green, reviewed+fixed.** Moved the fraud+sanctions
gate to a `@Primary PaymentGatewayPort` decorator in payment-orchestration, so EVERY
PSP caller is screened (gateway REST, billing renewals/dunning, workflow — all inherit
it; b2b is a different rail, correctly excluded) with one new `payment→fraud` edge.
Flow-aware: sanctions hard-block in all modes; a fraud BLOCK rejects interactive but
DOWNGRADES to capture-held REVIEW on server rails. A `payment_capture_hold` table makes
REVIEW enforceable at capture + links the payment to its assessment (closes B-027). The
review then found three live holes I fixed: a confirm-with-new-PM could auto-capture a
flagged payment before the hold (B1); confirm skipped sanctions when no new PM (B2); and
a held *billing* charge was misclassified as a failure and dunned (M1 — fixed by making
hold INTERACTIVE-only: server mandates capture + flag for review, never held).
**B-002 (RLS) — write-leak CLOSED + activation foundation laid (dormant, CI-green).**
Decided (judge panel, ADR-010): Option B — a physically-isolated `nexuspay_system`
BYPASSRLS role for cross-tenant jobs, NO in-policy escape hatch. Landed migration set 1
(system role, cross-schema grants, MV ownership) + a **security fix**: 36 RLS policies
were USING-only, so a mislabeled write could land in another tenant — a `pg_policies`
loop now copies each policy's USING verbatim into WITH CHECK (no expression rewrite →
child-table policies stay correct). Proven by the extended RlsIsolationIntegrationTest.
**Remaining (staged):** the RLS runtime cutover (tx-manager + system EMF + 16-job
re-routing + FORCE) is dormant, high-blast-radius, and human-gated (needs the system
secret + a staging canary) — specified in rfc-b002 / B-002-activation, deliberately not
rushed. 292 tests green; floor 250→285. ADR-010/011, L-031/032, B-029/B-030 opened.

## 2026-06-10 — B-011 + the whole integration-test bring-up: FIRST-EVER green CI
The headline: the app now **boots end-to-end in CI against real Postgres, Kafka and
Redis, and all 13 integration tests pass** (263 tests green) — something that had
**never happened** in this repo's history. B-011 ("Flyway version collision") turned
out to be the cork in the bottle: that one error had aborted the schema before any
integration test could ever boot the context, so ~10 *layers* of latent drift had
piled up unverified behind it. Clearing it let CI surface them one boot-layer at a
time, and I fixed each with ground-truth from the CI artifacts (never guessing):
- **Flyway** (B-011 core): renumbered the colliding V1×4/V2×2 migrations into a
  unique band; replaced the unreliable bare-`db/migration` scan with explicit
  per-module leaf locations + `fail-on-missing-locations`; **and** found the real
  smoking gun — the test yaml pinned a 4-leaf location list that silently dropped
  the `app` leaf defining `current_tenant_id()`, so every RLS policy failed.
- **Schema↔entity drift** (never validated before): created the Spring Modulith
  `event_publication` table; added `@JdbcTypeCode(JSON)` to **23** jsonb columns
  (also fixes runtime INSERTs); fixed `List`/array mappings, a `Double` vs DECIMAL
  type, and two RLS policies referencing a non-existent `tenant_id` on child tables.
- **Wiring/runtime**: enabled nested Spring Data repos; qualified duplicate Kafka
  `ConsumerFactory` beans; fixed the tenant-context SQL (`SET … = ?` is illegal →
  `set_config()`); translated `AccessDeniedException` to **403 not 500**; made the
  PSP/Keycloak health probes disableable (they were dragging `/actuator/health` to
  503); disabled Vault in tests.
Every one of these was a real, shippable bug. CI is now the standing guardrail the
lessons (L-011/12/13) had been asking for. 8 new lessons (L-023–L-030); test floor
234→250; B-011 closed. **Caveat:** the deeper B-002 RLS *effectiveness* (the tenant
set runs pre-transaction; tests run as the RLS-exempt owner) is unchanged and still
tracked — this work made the SQL valid and the app boot, not RLS enforcement.

## 2026-06-10 — Pushed to GitHub (L2); CI iteration cleared 4 hidden blockers
You set autonomy to **L2 + push**, so I pushed `perpetua/bootstrap` and opened
**PR #1**. CI (which has Postgres/Kafka this sandbox lacks) immediately earned its
keep — it surfaced FOUR real repo-health blockers that no local Windows run could
catch, and I fixed each: (1) `gradlew` lost its executable bit (exit 126); (2)
`gradle-wrapper.jar` was gitignored and never committed (no clean clone could
build); (3) Testcontainers↔commons-compress incompatibility; (4) the catalog
pinned Flyway 10.15.0 + a Flyway-10-only artifact that broke against Boot 3.2.5's
Flyway-9 autoconfig. After those, CI runs the **250 unit tests green** and the 13
integration tests now reach — and reproduce — the real **B-011** Flyway
duplicate-version collision (CompositeMigrationResolver). So B-011 is no longer a
"needs a DB to verify" RFC: CI is now the verifier, and B-011 is the clear next
fix. Lessons L-020/021/022.

## 2026-06-10 — Coverage, subtraction, scans wired, gated items designed
**Shipped:** billing `SubscriptionTest` (10) covering the state machine + the
calendar billing-period math (Jan-31→Feb-28 clamp); **deleted** the dead routing
A/B framework (−509 LOC, Q-007 approved, ADR-008); **B-006** wired OSV dependency
scanning into CI + ran a real local secret baseline (clean) — honest that full
gitleaks/OSV/semgrep are CI-gated, not faked locally.
**Designed (RFCs, ready to execute):** the items that genuinely can't be verified
without a database got concrete RFCs instead of blind code —
- **B-011 Flyway**: confirmed 4×V1/2×V2 collisions on one shared history + a
  suspected base-recursion×explicit-location double-scan; fix + FlywayMigrationIT.
- **B-002 RLS**: confirmed inert (SET LOCAL fires pre-transaction; app runs as the
  RLS-exempt owner); fix = set_config(...,true) in-tx + non-owner role + isolation IT.
- **B-003 gate**: fraud/sanctions are built but never called in the payment path;
  RFC for a synchronous pre-auth gate (implementable here next — no DB needed).
**Why RFCs not code:** shipping unverified RLS or migration-location changes can
silently leak tenants / break boot — worse than the known state. They need the CI/
Docker run that **Q-001** (push) unblocks. Tests 245→250, 0 fail.

## 2026-06-10 — Concurrency hardening, ledger invariants, dead-code triage
**Shipped 3:**
- **B-018** — OutboxRelay's leader-lock release was a non-atomic GET-then-DELETE
  (could delete another instance's lock); now an atomic owner-checked Lua
  compare-and-delete, mirroring the reviewed SchedulerLock pattern.
- **B-014b** — regression tests locking two earlier money fixes that lacked them:
  per-currency journal balancing (L-001 — a +JPY/−USD entry that only nets to zero
  as raw longs is now proven rejected) and the canonical account-id convention
  (L-003). First application-layer tests for the ledger.
- **B-007** — confirmed the routing A/B framework is dead (selectConfig/recordOutcome
  have no callers; the correct z-test is never fed). Locked the statistics with a
  test, and **escalated** the wire-vs-delete product decision to Q-007 rather than
  unilaterally remove a built API at L1.
**Tests 234→245, 0 fail.** Three Valkey locks across the system (outbox, billing
schedulers, approvals) are now race-safe/atomic. New question: Q-007 (A/B wire/delete).

## 2026-06-10 — FX exponent money bug + coverage honesty (B-014a)
**Shipped:** fixed a still-open **HIGH** money bug — FX conversion multiplied raw
minor units by a major-unit rate with no currency exponent, so **USD→JPY was 100×
too high** (BHD/KWD 10× off). Found in 3 sites (FxRate, FxRateLock, and — caught by
the reviewer — CurrencyConversion.fxGainLoss); all now delegate to one
exponent-aware `CurrencyMath`. Added the **first tests** for the fraud and
payment-orchestration modules (11 tests). Review SHIP.
**Honesty correction:** wiring tests into those two modules completed the JaCoCo
denominator and revealed the real aggregate coverage is **17%, not the 24%** first
reported (which had silently excluded those untested modules). Covered lines
actually rose. Corrected the ratchet floor 23→16 and flagged it for ratification
(**Q-006**) rather than fudging the number to stay green. Tests 223→234, 0 fail.

## 2026-06-10 — Backlog full-send (B-004, B-008, B-009, B-013, B-005)
**Shipped 5 items, each a full §4 loop with subagent review(s):**
- **B-004** (security): app refuses to boot with built-in DEV default secrets under
  a production profile; added a real `application-production.yml` + a drift-guard
  test so the control can't fail open. Adversarial review SHIP.
- **B-008** (correctness): reconciliation "payment matched, ledger entry missing"
  (PARTIAL) is now a tracked MISSING_LEDGER_ENTRY exception and counted, not dropped.
- **B-009** (T3 money): maker-checker refund is now execute-once — atomic conditional
  approve (closes the concurrent double-approve race) + tenant-ownership check (closes
  cross-tenant approve) + deterministic gateway idempotency key (dedups duplicate
  execution). Security review SHIP; adversarial flagged a *pre-existing* stuck-approval
  recovery gap → tracked as B-022 (not a regression). Threat model: duplicate-refund
  race CLOSED.
- **B-005** (test-strength): JaCoCo wired across all modules; measured aggregate line
  coverage **24%** and made it a CI-enforced ratchet (floor 23%). Exposed the thin
  modules (gateway-api 2%, billing 4%, common 7%) for B-014.
- **B-013** (docs): CONTRIBUTING now documents the real build-env gotchas (JDK-21
  JAVA_HOME, gradlew.bat, the temp-dir loopback error, Docker-skip integration tests).
**Tests:** 202 → 223 pass (+21), 0 fail, build green. **Open HIGH findings: 6 → 3.**
Branch `perpetua/bootstrap`, still local (Q-001). **Next:** B-006 (baseline scans),
B-014 (coverage on thin modules).

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
