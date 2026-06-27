# DECISIONS — append-only ADR log

## ADR-001 | 2026-06-09 | Bootstrap PERPETUA into NexusPay at L1
Context: repo did not build/run; large uncommitted audit-fix body. Decision:
adopt PERPETUA v2; operate L1 (branch+local commits, no push/PR/merge) until the
human ratifies CHARTER (Q-001). Alternatives: L0 advisor (rejected — fixes
already exist and must be preserved); L2 (rejected — no ratification yet).

## ADR-002 | 2026-06-09 | Declare common/payment/ledger/iam as Modulith OPEN
Context: these modules' subpackages (ports, domain, id, exceptions, avro) are
consumed across the system; default base-package-only exposure made Modulith
verification fail. Decision: mark them `Type.OPEN` (transitional). Alternative:
`@NamedInterface` on each exposed package (deferred — larger, per-package
curation). Metric: ModulithVerificationTest passes; revisit at a meta-review to
tighten OPEN → NamedInterface.

## ADR-003 | 2026-06-09 | Relocate @SpringBootApplication to io.nexuspay root
Context: under io.nexuspay.app, Boot auto-config packages + Modulith saw no
sibling modules. Decision: move to io.nexuspay. Alternative: explicit
`scanBasePackages`/`@EntityScan` lists (rejected — brittle, must enumerate 16
modules). Metric: app boots through bean wiring; Modulith detects all modules.

## ADR-004 | 2026-06-09 | DataSource decoration via BeanPostProcessor
Context: a `@Bean DataSource` that consumed a `DataSource` created a
Flyway↔datasource dependency cycle that blocked startup. Decision: wrap the
existing DataSource bean in a `BeanPostProcessor` (no new bean edge).
Alternative: `@DependsOn`/`@Primary` juggling (rejected — fragile). Note: the
RLS `SET LOCAL`-on-checkout mechanism itself is still semantically broken
(B-002); this ADR only fixes the boot cycle.

## ADR-008 | 2026-06-10 | Delete the dead routing A/B framework (Q-007, human-approved)
Context (B-007): `RoutingAbTestService`/`RoutingAbTestController` (REST
`/v1/routing/ab-tests`) were unreachable — the routing engine never assigned a
group or recorded an outcome, so the (correct) z-test was never fed. Decision:
DELETE the service + controller + its test (subtraction, §7). Wiring was rejected
because it requires the routing engine to be on the live payment path, which it
is not (B-003) — wiring A/B into not-yet-live routing is dead-code-into-dead-code.
The inert `abTestId`/`abTestGroup` fields + columns on RoutingConfig/RoutingDecision
are LEFT in place (harmless; removing them touches entities + migrations). Trivially
restorable from git history when routing goes live. Verified: payment-orchestration
compiles + tests green; no dangling references.

## ADR-007 | 2026-06-10 | Token-aware adaptive pacing (human-directed, B-019)
Context: the human wants to spend as much of the per-5h-window Fable-5 token
budget as possible on productive work while away, without (a) idling with quota
unspent when the window rolls, or (b) slamming the cap early and sitting blocked.
Decision: a pacing controller (`scripts/perpetua-pace.sh`) drives the supervisor
to keep cumulative usage on a steady line from 0→budget across the window —
AGGRESSIVE (gap 0 + deeper sessions) when behind, COOLDOWN (sleep to re-cross the
line) when ahead, BLOCKED (wait for the window to free) near the cap, STEADY in a
deadband. Usage is read locally via ccusage (`scripts/perpetua-usage.sh`); the
budget ceiling is `PERPETUA_TOKEN_BUDGET` or ccusage's historical-max-block proxy.
A `rigor=MAX/NORMAL/LEAN/PAUSE` hint rides the prompt so the AGENT scales DEPTH
(reviewers, audits, research, mutation) — productive burn, never churn (§0/§11.1).
Alternatives: fixed MIN_GAP (rejected — can't fill or protect the window); a
hard token cap in-harness (rejected — Anthropic's server-side 5h/weekly caps are
the real backstop; pacing only optimizes within them). Self-modifies CLAUDE.md +
the harness — permitted because the human directed it (§0.2). Metric to watch
(meta-review): window utilization (tokens used / estimated budget per window)
trends up without a rise in churn/proxy-gaming flags. Residual: pacing depends on
ccusage being installed + a built usage history for the cap proxy; degrades to
neutral STEADY otherwise. Live behavior is unit-tested (pure controller) but not
yet integration-tested against real ccusage (B-020).

## ADR-006 | 2026-06-10 | Billing scheduler lock fails CLOSED + renews its lease
Context (B-001): billing crons fired on every replica → double-charges. Decision:
a Valkey SET-NX-EX lock (like OutboxRelay) BUT (a) fail CLOSED on Valkey-down —
skip the cycle rather than run unguarded, because the work is due-based and
self-heals next cycle whereas an unguarded run double-charges; (b) RENEW the
lease at ttl/3 while work runs (a long ≤500-sub charge loop could otherwise
outlive a fixed TTL and let a second replica start — adversarial review found
this; new invoice per cycle ⇒ new idempotency key ⇒ no downstream dedup); (c)
atomic owner-checked Lua for renew+release so neither can touch a lease another
instance now holds. Alternatives: fail-open (rejected — money); fixed TTL +
batch cap (rejected — fragile vs PSP latency). Residual: renewal *timing* is
integration-tested only; fail-closed safety assumes due-based re-selection
(B-017 regression test).

## ADR-005 | 2026-06-09 | Reconciliation/dispute ports backed by the ledger module
Context: PaymentQueryPort/LedgerQueryPort (recon) and LedgerPort (dispute) had no
implementations → context fails. reconciliation/dispute may depend on `ledger`
(OPEN) but NOT on `payment`. Decision: implement against the ledger's
JournalEntryRepository / CreateJournalEntryUseCase. Consequence: reconciliation
is effectively settlement↔ledger two-way (no reachable payment read-model);
documented in the adapter + a future payment read-model (B-003-adjacent) would
upgrade it to true three-way. Alternative: leave unimplemented (rejected — app
won't start).

## ADR-009 | 2026-06-10 | B-003 ships as a SCOPED first-cut gate, not a complete sanctions control
Context: B-003 wired the never-called fraud + cross-border modules into the payment
path. The T3 dual review (adversarial + security) confirmed the fraud half is sound
and tested, but found the gate is incomplete as a sanctions control: it covers only
the interactive REST `create` path (not confirm/capture or the billing/b2b/workflow
port callers — B-024); sanctions geography comes from client-controlled metadata
that can be omitted/forged (B-025); the OFAC adapter's parser is broken so it runs on
a 4-country static fallback and fails open (B-026); REVIEW capture-hold is not
enforced at capture (B-027). Decision: SHIP the first cut — it is a real improvement
(a fraud BLOCK now stops the PSP call on the dominant create-with-card flow; an
end-to-end IT proves a sanctioned destination is 403'd) and makes nothing worse — but
(a) correct the gate's own Javadoc/commit to state the limits honestly, (b) do NOT
advertise OFAC coverage, and (c) track every review finding as B-024..B-028 with
severity. Alternatives rejected: silently ship as a "sanctions gate" (dishonest /
false assurance); re-architect to the port boundary + server-side geography + OFAC
parser fix in one change (correct end state, but a multi-item epic — sequenced as
B-024..B-026 instead, each its own T3 review). The fraud-only value is real today;
the sanctions value is advisory until B-025/B-026 land.

## ADR-010 | 2026-06-10 | B-002 RLS activation = Option B (physical role isolation), staged
Context: making RLS enforce needs the app to connect as a non-owner role; the 16
cross-tenant @Scheduled jobs (incl. OutboxRelay) then see zero rows. A judge panel
weighed (A) an in-policy `OR current_tenant_id()='system'` escape hatch vs (B) a
separate `BYPASSRLS` system role/pool for jobs. Decision: **Option B** — three roles
(`nexuspay` owner/Flyway-only, `nexuspay_app` RLS-bound for all request traffic,
`nexuspay_system` BYPASSRLS for cross-tenant jobs); NO in-policy bypass. GUC set by a
`RlsAwareJpaTransactionManager.doBegin` running `set_config(...,true)` (auto-reverts
on commit → no pool leak; fail-closed — unset tenant = zero rows, never resolved to
'system'). Why B over A: a payments ledger must not have a fleet-wide escape hatch
that any GUC-manipulation/injection bug flips into a full cross-tenant bypass;
verified fact — `MaterializedViewRefreshService` needs OWNERSHIP to REFRESH, so a
privileged pool is mandatory anyway, which sinks A's "simplicity" premise. Staged:
Stage-0 ships dormant (owner unchanged, enforce=false → byte-identical); the
owner→nexuspay_app cutover + FORCE RLS is human-gated (needs the nexuspay_system
secret + staging canary). LANDED this session (dormant, CI-green): the proving
RlsIsolationIntegrationTest, migration set 1 (system role, cross-schema grants, MV
ownership, and the USING→WITH CHECK write-leak fix). REMAINING (C5-C7, coupled,
co-land with cutover): the tx-manager + system datasource/EMF + 16-job re-routing +
FORCE migration. Full plan in research/rfc-b002.

## ADR-011 | 2026-06-10 | B-024 gate coverage = @Primary PaymentGatewayPort decorator
Context: the B-003 fraud+sanctions gate ran on ONLY the gateway REST create path;
confirm/capture and the sibling PSP callers (billing renewals/dunning, workflow, SDK
checkout) bypassed it. A judge panel chose among (1) port-boundary decorator, (2) a
payment-orchestration use case, (3) keep-in-gateway + duplicate to siblings. Decision:
**(2/decorator hybrid) — a `@Primary GatedPaymentGateway implements PaymentGatewayPort`
in payment-orchestration** wrapping the sole adapter, so every caller inherits the
screen with zero caller edits + a single new `payment→fraud` edge (tenant from
metadata, no `iam` edge). Flow-aware: sanctions hard-block in ALL modes (OFAC applies
to standing mandates), but a fraud BLOCK DOWNGRADES to capture-held REVIEW on
server-initiated rails (recurring billing must not be silently declined by a velocity
rule). A `payment_capture_hold` table makes REVIEW enforceable at capture + links the
payment to its assessment (closes B-027). The interactive REST path stamps the trusted
tenant and strips client `source`/`workflow` markers so a caller can't claim the softer
rail. Rejected (3) — duplicated screening logic is the maintainability failure B-024
exists to fix. LANDED + CI-green this session (C1+C2), then a T3 adversarial+security
review (SHIP-WITH-FIXES, 0 refuted) drove fixes (C2b):
- REFINEMENT (from review M1): capture-hold is INTERACTIVE-only. A server-initiated
  pre-authorized mandate (billing/workflow) that's fraud-flagged now CAPTURES + is
  recorded/logged for analyst review, NOT held — holding it surfaced as
  requires_capture and tripped billing's dunning. Sanctions still hard-block all rails.
  (Supersedes the panel's "server REVIEW → hold" for recurring rails; emitting a
  proper review event/analyst queue is B-030.)
- confirm now ALWAYS screens (sanctions on every confirm) and REJECTS a flagged
  auto-capture confirm rather than capturing (B1/B2); getPayment errors remap to
  PaymentException (M3). Latent mode/tenant-from-metadata trust → B-029. B-025
  (client-forgeable geography) + B-026 (OFAC parser) remain separate follow-ups.

## ADR-012 | 2026-06-13 | B-002 C5-C7 activation machinery (dormant) — three deliberate deviations from ADR-010's sketch
Context: implement the runtime machinery ADR-010 deferred (tx-manager GUC, system
role pool, cross-tenant job routing, FORCE), landed DORMANT behind
`nexuspay.multi-tenancy.rls.enforce=false` and proven by ITs in CI; the prod flip
stays human-gated. An ultracode design-verification workflow + a 26-method
classification workflow (per-method table-tracing + adversarial verification of every
SYSTEM verdict) drove the decisions. Three deviations from ADR-010's wording, each to
remove a risk or a wiring trap:

1. **Single routing datasource, not a 2nd EMF/txManager.** ADR-010 said "second
   systemDataSource/EMF/systemTxManager". Rejected: JPA repositories bind to ONE EMF,
   so a second EMF would force every cross-tenant repo onto a parallel persistence
   unit (or fragment the context). Instead: ONE EMF over a `RoleRoutingDataSource`
   (extends AbstractRoutingDataSource) keyed by a ThreadLocal `DbRoleContext`
   (default APP = fail-closed) fronting two Hikari pools (nexuspay_app / nexuspay_system).
   `RlsRoutingTransactionManager extends JpaTransactionManager` sets the tenant GUC via
   `set_config(...,true)` in `doBegin` for APP txns; SYSTEM txns skip it. The routing
   DS reads DbRoleContext on every getConnection.

2. **`@SystemTransactional` lives in `common`, annotates the JOB ENTRY method, and
   pins the role via a thread-local — not the tx-interceptor.** The classification
   found the 15 cross-tenant jobs live in 6 modules that CANNOT depend on `app` (the
   composition root). So the marker moved to `io.nexuspay.common.rls` (7 modules already
   depend on :common); the acting `SystemRoleAspect` stays in `app`, advises by
   annotation type across the whole context, and wraps the call in
   `DbRoleContext.runAs(SYSTEM, …)` — a CALL-SCOPED thread-local. Consequence: annotate
   the `@Scheduled`/entry method and the pin covers the entire SYNCHRONOUS subtree
   (nested `@Transactional` service calls, self-invocation, even a non-transactional
   read). It does NOT cross thread boundaries (async callbacks/executors run on APP).
   The 6 TENANT_SCOPED Kafka consumers are deliberately NOT annotated — they must bind
   `TenantContext` from the event and stay RLS-armed; annotating them would bypass RLS
   (a cross-tenant hole). 5 NO_DB jobs need nothing.

3. **FORCE via a single repeatable `R__rls_force_owner.sql`, not versioned
   V4021+V4022.** A versioned migration applied while dormant (rlsforce=false) can never
   re-activate (Flyway runs a version once). The repeatable is idempotent in BOTH
   directions, gated by the `${rlsforce}` placeholder (default false = ensures NO table
   is FORCE'd, so the app-as-owner is never locked out — the catastrophic case), and
   re-triggers at the human cutover by flipping the placeholder alone (with
   placeholderReplacement=true, Flyway computes the repeatable's checksum over the
   placeholder-REPLACED text, so flipping `${rlsforce}` changes the checksum and re-runs it
   at the next migrate — no file edit needed; corrected per review finding #1). It also
   subsumes the revert (set false → NO FORCE). FORCE is
   defense-in-depth: runtime isolation already comes from the non-owner nexuspay_app role.
   Also deleted the broken `TenantAwareDataSourceConfig` (set_config at getConnection in
   autocommit = RLS-inert).

Proof (CI-green): RlsDormancyIT (enforce=false → routing DS/aspect/config absent AND no
table forced) + RlsEnforceIT (boots on nexuspay_app under the rls-enforce profile;
tenant A sees only A, B only B, unbound = zero rows fail-closed, @SystemTransactional
sees all; every RLS table FORCE'd). REMAINING before the flip: B-002-activation-tenant
(bind TenantContext in the 6 consumers + per-item binding in the billing batch sweeps to
keep WITH CHECK on writes) — see HANDOFF checklist. Full per-method map in the C6
workflow result; rfc-b002.

ADDENDUM (2026-06-13, post-ship adversarial review — SHIP-WITH-MINORS, 0 blockers/0 must-fix,
8 confirmed minors, each independently verified). Fixes applied (commit follows):
- #1 (runbook inverted): corrected above — the rlsforce placeholder value IS in the repeatable's
  checksum, so a placeholder-only flip re-runs R__; no file bump needed. (Reviewer also noted the
  effective Flyway is BOM-managed 9.22.3, not the dangling 10.15.0 in libs.versions.toml.)
- #2/#5 (missed entry point): `TrialExpirationScheduler.convertExpiredTrials` — structural twin of
  RenewalScheduler — was left unannotated; now @SystemTransactional too, so the SYSTEM job count is
  **16**, not 15. Its per-item write binding joins RenewalScheduler in B-002-activation-tenant.
- #3 (analytics not fail-closed): V3017-3020 policies used bare current_setting (ERROR when GUC
  unset, not zero rows). New migration `analytics/V3022__analytics_rls_failclosed.sql` normalizes
  all 7 to the current_tenant_id() helper (missing_ok); enforce IT now asserts it structurally.
- #4 (FORCE/enforce interlock): no clean DB-side interlock exists (a migration can't see the app's
  runtime role); the practical interlock is profile co-location — rlsforce=true lives ONLY in the
  rls-enforce profile, which also sets enforce=true, so they cannot diverge unless an operator
  force-overrides rlsforce alone (documented footgun). Documented in application-rls-enforce.yml.
- #6 (cross-module routing only incidentally proven): RlsProbe (a context bean) + the live CI log of
  OutboxRelay.relayEvents routing through SystemRoleAspect under enforce are adequate; a dedicated
  cross-module-job assertion is folded into B-002-activation-tenant's per-consumer ITs.
- #7/#8 (NITs): documented the DLQ async-callback-off-the-pin invariant in code; documented the
  superuser-bypasses-FORCE caveat (structural-only proof) in the enforce IT javadoc.

## ADR-013 | 2026-06-14 | B-002-activation-tenant: TenantWorkRunner + the bind-vs-run distinction
Context: with the C5-C7 machinery dormant, the remaining step before RLS can be flipped is making the
single-tenant consumers and the per-item billing writes run on the APP role WITH a tenant bound (so
RLS USING + WITH CHECK guard them). A grounded ultracode design + an adversarial review settled the
shape. Decision:
- **One shared primitive, `TenantWorkRunner` (interface in `common`, impls in `app`).** The enforcing
  impl (`AppTenantTransactionTemplate`, @ConditionalOnProperty enforce=true) sets DbRole.APP +
  TenantContext BEFORE the transaction begins (RlsRoutingTransactionManager.doBegin reads them at
  tx-begin); the dormant impl (`InlineTenantWorkRunner`, enforce=false/matchIfMissing) ignores the
  tenant so call sites are structurally identical when off. Mirrors @SystemTransactional's module split.
- **Two methods, deliberately distinct (the review's key correction, L-036):** `runInTenant`/
  `callInTenant` OPEN a REQUIRES_NEW tenant-bound tx — for handlers that aren't themselves
  transactional and for per-item sweep writes. `bindTenant` binds APP+tenant WITHOUT an enclosing tx —
  for work whose inner @Transactional manages its own boundary/isolation. The ledger consumer uses
  `bindTenant` because wrapping its @Transactional(SERIALIZABLE) journal write in a default-isolation
  helper tx silently downgraded it to READ_COMMITTED (and broke dormancy).
- **Consumers bind BEFORE the @Transactional boundary** (drop @Transactional, route through the helper);
  tenant from `metadata.tenant_id` (fallback "default"). **Sweeps** discover under the SYSTEM pin then
  write each item via the helper on APP+tenant (per-item commit; one tenant's failure no longer rolls
  back others). The gateway tenant-scoped finder is **gated on the enforce flag** (L-037) — an app-level
  WHERE is not gated by the GUC, so an ungated swap would break webhook delivery at enforce=false.
- **Step 0 is out of scope, human-gated:** the `nexuspay.payments` topic carries no tenant yet, so the
  4 payments-topic consumers bind "default" under enforcement until B-002-cutover Step 0 stamps the real
  tenant at HyperSwitch ingest. This task builds + PROVES the mechanism (helper IT + analytics-consumer
  IT under the rls-enforce profile); the producer fix and the heavy per-site ITs are deferred to cutover.
Proof CI-green via PR #2 (build+test). Review: SHIP-WITH-FIXES → both fixes applied. Full plan in
research/rfc-b002-activation-tenant.md; lessons L-036/037/038 (the last is the ci.yml trigger-gap guardrail).

## ADR-014 | 2026-06-14 | B-025/B-026: server-authoritative sanctions geography + fail-closed OFAC
Context: the sanctions/cross-border screen read geography from client request metadata (forgeable) and
the OFAC list parser was broken + failed OPEN. Decision (ultracode design + security review):
- GEOGRAPHY IS SERVER-AUTHORITATIVE: destination from the trusted merchant_country (new V4011 column),
  source from a trusted edge signal; client metadata is advisory only. Unknown country on a
  cross-border-capable flow → REVIEW/EDD, never silent ALLOW. The compliance REVIEW is
  NON-DOWNGRADEABLE on server rails (GateDecision.mandatoryReview, honored in create+confirm regardless
  of ScreeningMode) — distinct from fraud-REVIEW so the M1 server-rail dunning policy is preserved.
- OFAC SCREEN FAILS CLOSED: the parser reads the REAL CSL feed (ISO-2 from addresses/nationalities,
  scoped by comprehensive-embargo programs, unioned with a curated KP/IR/SY/CU baseline so a parse miss
  never empties the set); the compliance check BLOCKS when screening is unavailable (empty/stale/failed)
  instead of ALLOWING; a `sanctions` readiness HealthIndicator pulls a degraded screen from rotation
  (boot-on-baseline stays healthy). Feed URL is injectable (no live-network in tests).
The original parser was BUILT AGAINST A NON-EXISTENT FEED COLUMN and passed its own fabricated-fixture
tests — the live feed (29 cols, ISO-2 in addresses) was verified during review (L-040). Residual
robustness items tracked as B-026-hardening; RU/BY EO-program treatment flagged in-code for legal review.

## ADR-015 | 2026-06-14 | B-029: trusted gate MODE+TENANT (CallContext) + idempotent fraud (B-027b)
Context: the gate read screening mode + tenant from client request metadata; a future client-metadata-
forwarding caller could claim the softer SERVER rail (dodging hold-capture) or fragment fraud-velocity
across fabricated tenants. And a retried Idempotency-Key re-ran fraud assess(), double-counting velocity.
Decision (ultracode design + security review, security dimension PASSED):
- A server-set CallContext(tenantId, mode) is threaded through PaymentGatewayPort as ADDITIVE overloads
  (the raw delegate + non-screening callers keep compiling via defaults); GatedPaymentGateway sources
  mode+tenant ONLY from CallContext (REST=interactive(principal.tenantId); billing=serverRecurring;
  workflow=serverOther) and scrubs client source/workflow/tenant_id markers (advisory-only). Confirm-time
  authority comes from a server-owned payment_screening_origin table (V4022), never the intent metadata
  blob. The transitional 1-arg createPayment forces a STRICT default (INTERACTIVE + null tenant) so no
  un-migrated caller can grant a soft rail or a metadata tenant. This strengthens B-025 (geography keys
  off the now-trusted tenant).
- Fraud assess() is idempotent: read-through dedup on (tenant, idempotency-key) for the common retry +
  a unique index (V4023) as the truly-concurrent-race backstop. The backstop uses saveAndFlush so the
  constraint violation throws SYNCHRONOUSLY inside assess()'s catch (plain save() merges a pre-assigned-@Id
  entity and DEFERS the INSERT past the catch — L-041); V4023 collapses pre-existing duplicates BEFORE
  creating the index (safe under baseline-on-migrate); the catch is narrowed to the specific
  uq_fraud_assessments_tenant_idem violation so unrelated integrity errors propagate. Velocity is
  separately protected by the SET-NX first-seen marker.
Residual: B-029-hardening (request-fingerprint match on a dedup hit) deferred — needs a schema column.

## ADR-016 | 2026-06-14 | Sync CHARTER to L2 (Q-001 ratification) — provenance + human-gated carve-outs
Context: Q-001 was RESOLVED by the human owner on 2026-06-10 — they chose **L2 +
push + allow merge** (`.perpetua/QUESTIONS.md:14-18`): the agent may merge to main
when ALL CI gates pass, EXCEPT tier-3 changes (auth/crypto/ledger/vault/money/
migrations) which always go via PR review (§3 / §17.3). Q-001 explicitly instructed:
"CHARTER.md still reads `level: L1` (human-owned — agent may not edit); please update
it to L2 to match." STATE.md has tracked AUTONOMY: L2 since 2026-06-10. In the current
session (2026-06-14) the human RE-AUTHORIZED push-to-main and explicitly listed
"CHARTER L1→L2" as a task. PROVENANCE (recorded honestly): CHARTER.md is HUMAN-OWNED;
the agent applied this edit ONLY under that explicit human instruction — the HUMAN
ratified (Q-001 + this-session re-authorization). The agent is NOT self-elevating.
The L2 wording is grounded in PERPETUA.md §3 (Autonomy Levels) and §17.3 (risk tiers),
not invented. The evidence dossier justifies the trust in context: a clean track
record on main — 6 tier-3 features shipped with adversarial + dedicated security review
(ADR-009/011/012/013/014/015), a tier-3 change correctly routed through PR #2, ratchets
raised honestly (test_count_floor → 680; coverage_floor_pct → 33 — corrected DOWN once,
23→16, as a Q-006 denominator fix flagged for human ratification, not a silent lowering,
then ratcheted up to 33 as modules gained tests), and
self-disclosed mistakes (L-038). CI independently blocks any merge that drops coverage/
test-count or leaks a secret (`perpetua-gates.yml`), so L2 does NOT make the agent the
sole guardian of its own discipline (§18.3).

Decision: sync the human-owned CHARTER.md to the already-ratified L2 autonomy.
Concretely: `level: L1` → `level: L2`; STATUS DRAFT/"operates at L1" banner →
RATIFIED/L2-operating-rule banner with an explicit provenance line; the obsolete
"Never push or open PRs until ratified" clause → the L2 operating rule (push freely
on `perpetua/**`; merge to main only when all CI gates pass; tier-3 ALWAYS via PR even
at L2); the stale North-star "(baseline 201)" → a pointer at `ratchets.json` →
`test_count_floor` (currently 680) as the source of truth so it cannot drift again.
L2 is DEFINED as: merge non-tier-3 to main when every CI gate passes; tier-3
(ratchets.json risk_map.t3_globs: auth/authz, crypto, parsing/deserialization,
SQL/shell/path, network boundary, concurrency, money/data-integrity, migrations)
ALWAYS via PR review (§17.3 "always via PR even at L2"). Human-gated carve-outs that
this ADR explicitly does NOT weaken: (1) tier-3-always-via-PR; (2) the production RLS
cutover B-002-cutover — the irreversible/high-blast-radius `rls.enforce=true` flip and
its MANDATORY Step 0 (stamp the real tenant on `nexuspay.payments`) — `rls.enforce`
defaults false (dormant), the flip stays with the human; (3) L3 / external actions —
`whitelisted_external_actions` stays `[]` (deploys, releases, enabling branch
protection, any side effect outside the repo); (4) branch protection on main (require
the CI workflow, forbid force-push) — the §18.3 structural backstop — remains OPEN,
Q-002 UNANSWERED. The verbatim-in-spirit controls "never weaken a test/assertion/add a
coverage-or-scan exclusion to go green", "never commit a real secret", "no new runtime
dep without a BLOCKING question + supply-chain check", and "do not touch
docs/gaps/known-gaps.md history" are PRESERVED. Also syncs the auto-loaded operating
core CLAUDE.md (its HARD RULES line hardcoded L1, which would override the ratified
charter at runtime — the env-override class of bug).

Consequences: operationally, the agent now pushes `perpetua/**` and merges
non-tier-3 changes to main on its own once CI is green, while tier-3 work continues
to flow through PR review (PR #2 was the live proof). ADR-001's "operate L1 until
ratified" is SUPERSEDED by this ADR (ADR-001 is append-only history — NOT edited).
Residual human gates remain the real backstops: tier-3-via-PR, the B-002-cutover flip
(+ its Step 0), `whitelisted_external_actions: []`, and — most importantly until it is
enabled — branch protection on main (Q-002), without which L2 push authority rests on
the agent's discipline + the CI ratchets rather than on the structural §18.3 enforcement
that would make it robust against a degenerated future session. The human still owns
CHARTER.md; future edits to it require explicit human instruction (recorded as here).

## ADR-019 | 2026-06-15 | SEC-BATCH-1: tenant-authority from the principal, routed through `common` (T3, via PR)
STATUS: Accepted (T3 — via PR, human merges). Closes audit SEC-02/05/06/19/20 (cross-tenant IDOR/spoof).
CONTEXT: every module except gateway-api derived tenant authority from a client `X-Tenant-Id` header; by-id
reads were global PK lookups; no authz aspect; RLS dormant ⇒ cross-tenant read/write of cardholder data,
payouts, and approvals (CRITICAL). The obvious fix (copy gateway-api's `@AuthenticationPrincipal
NexusPayPrincipal`) is UNCOMPILABLE in marketplace/vault/b2b/fraud: NexusPayPrincipal + TenantContext live
in `iam`, which those modules do not depend on (common-only; fraud pins allowedDependencies={common}).
DECISION: route the mechanism through `common` (the @Modulithic sharedModule), mirroring the existing
TenantWorkRunner/@SystemTransactional precedent:
 - common.tenant.TenantPrincipal (interface {tenantId()}); iam NexusPayPrincipal implements it (iam→common
   is the legal direction; zero behaviour change).
 - common.tenant.CallerTenant.require() — reads SecurityContextHolder, returns the principal's tenantId or
   throws AuthorizationException(403). The controller-side tenant source (replaces every X-Tenant-Id header).
 - common.tenant.TenantOwnership.assertOwned/require — returns the entity iff present AND owner==caller, else
   common.exception.ResourceNotFoundException → 404. Collapses absent + wrong-tenant into ONE not-found path
   (NO existence oracle). GlobalExceptionHandler maps it to 404 (also fixes a latent bug: services threw
   IllegalArgumentException for not-found, which had NO handler → was returning 500).
 - common needs spring-boot-starter-security on its own compile classpath (SecurityContextHolder).
 - Every by-id read/write switched to findByIdAndTenantId (JPA derivation, NO migration — columns exist);
   writes assert referenced-resource ownership (payout→account, split rules, vendor approve).
SCOPE: marketplace (Payout/SplitPayment/ConnectedAccount + services), vault (card/network-token/migration —
cardholder data), b2b (VendorPayment/VirtualCard), fraud (FraudRule), gateway WebhookEndpoint.delete. 58 files.
PRESERVED: the legit NON-HTTP cross-tenant sweep PayoutScheduler.processPendingPayouts (@SystemTransactional /
BYPASSRLS) is untouched — it has no SecurityContext; adding CallerTenant.require() there would NPE/403.
CONSEQUENCES: tenant isolation is now enforced at the APPLICATION layer regardless of RLS state (so it holds
pre-cutover). T3 review (3 lenses) SHIP/SHIP_WITH_NITS, 0 blockers. RESIDUALS: SEC-23 (b2b B2bInvoice/
PurchaseOrder + fraud FraudAssessment controllers have the SAME defect, out of this batch's verified scope —
apply the same helper); extend the real-SQL TenantIsolationIntegrationTest to the money + cryptogram paths.
See L-048. The RLS cutover (B-002-cutover) remains the human-gated defense-in-depth backstop.
## ADR-017 | 2026-06-14 | B-029-hardening: request-fingerprint guard on the fraud dedup-hit (T3, via PR)
STATUS: Accepted (lands via PR — T3 always via PR even at L2, §3/§17.3; human merges).
CONTEXT: FraudAssessmentService.assess() deduped retries on (tenantId, dedupKey) and on a HIT returned the
prior decision WITHOUT checking the retried request matched the original. A reused idempotency key on a
DIFFERENT charge would be served the stale fraud decision — an attacker could get a known-ALLOW decision
applied to a high-risk transaction. Deferred from B-027b because the request fields weren't persisted.
DECISION: persist a KEYED request fingerprint and verify it on every dedup hit.
 - Crypto: HMAC-SHA256 (NOT a plain hash — the (amount,currency,customer,cardToken) tuple has catastrophically
   low entropy and a plain digest in a leaked column is rainbow-tableable). Key = SHA-256(masterKey ||
   "fraud-request-fingerprint") — domain-separated from the vault PAN-fingerprint key (L-009), same master key
   (nexuspay.vault.encryption.master-key, guarded by StartupSecretsValidator/B-004). 64-hex, VARCHAR(64).
 - Canonicalization = a SECURITY BOUNDARY: version byte + per-field [type-tag][null|present marker][8-byte BE
   length][utf8 bytes]. Self-delimiting ⇒ injective ⇒ no in-band-delimiter forgery (the classic pipe-join
   collision is structurally impossible). tenantId is folded in FIRST (TAG_VERSION 0x02) so the fingerprint is
   self-binding and can never be honored cross-tenant even if a future refactor compares outside the scoped lookup.
 - Dedup-hit branch: stored==current ⇒ short-circuit (retry); stored!=current ⇒ idempotency-key REUSE
   (client bug or attack) ⇒ RE-ASSESS (never return prior) + WARN, and return the fresh decision WITHOUT
   saveAndFlush/publish (the prior row legitimately belongs to the original charge; the unique key makes a 2nd
   row impossible, so the violation path is STRUCTURALLY unreachable, not merely caught); stored==null (legacy
   pre-migration row) ⇒ return prior (intentional, bounded back-compat; new writes always set a non-null fp).
 - Fail-CLOSED everywhere: fingerprint computed BEFORE the dedup lookup, so a compute/key failure throws
   FingerprintUnavailableException and rolls back assess() — it can never be masked by a hit returning a stale
   ALLOW. Constant-time compare (MessageDigest.isEqual). Never stores/logs a raw PAN (cardHash folded one-way).
 - Migration: fraud/V4024 (next free GLOBAL version; ADD COLUMN IF NOT EXISTS, nullable; safe on a non-empty DB).
CONSEQUENCES: a reused key on a different charge is always re-assessed, never served the stale decision. T3
adversarial review: crypto SHIP, migration SHIP, dedup-bypass SHIP; test-adequacy initially BLOCKED — the
anti-collision tests were VACUOUS (a pipe-join mutant kept them green); fixed with real delimiter-shift
collision pairs + an extended fuzz, self-verified by mutation (pass-on-real / fail-on-mutant) (L-044).
RESIDUAL (tracked, NOT in this PR): velocity-counter evasion on the reuse path — the rule pipeline gates the
velocity INCR behind a SET-NX first-seen marker keyed on (tenant, idempotency-key); the original request
already set it, so N distinct charges under one reused key only count the first toward velocity. Separate T3
follow-up (B-029-velocity): key velocity accounting on charge identity, not the idempotency key.
## ADR-018 | 2026-06-14 | B-022: stuck-APPROVED refund reconciler (T3, via PR)
STATUS: Accepted (lands via PR — T3 money, always via PR even at L2; human merges). MERGE-ORDER NOTE:
this PR's migration is iam/V4025; the fraud-fingerprint PR (#3) owns fraud/V4024. Out-of-order is
DISABLED — on a persistent DB merge fraud V4024 BEFORE this V4025 (CI uses a fresh DB so both are green
independently; the gap at V4024 is intentional). See L-046.
CONTEXT: refund approve() commits APPROVED in its own tx; executeApprovedRefund then runs OUTSIDE it.
A gateway failure leaves the refund stuck APPROVED-forever (a retry of approve() throws "not pending"),
needing manual recovery (B-009 review finding). B-009's deterministic key (refund-approval-<id>) makes a
re-drive safe.
DECISION: a @Scheduled reconciler (gateway-api) that discovers APPROVED-but-unexecuted refunds and
RE-DRIVES executeApprovedRefund (never approve()) under the SAME idempotency key.
 - No-double-pay (the money backstop): every execute path sends the byte-identical refund-approval-<id>
   as the HyperSwitch Idempotency-Key; the PSP collapses N submits to ONE refund. The executed_at marker
   is only a "stop re-driving" optimization, set ONLY after RefundResponse.isSuccessful() and conditional
   on executed_at IS NULL — correctness does not depend on it winning a race.
 - Concurrency: fail-CLOSED Valkey lock (ADR-006 pattern; inlined as GatewaySchedulerLock since gateway-api
   must not depend on :billing) — replicas never both run a cycle; NOT the OutboxRelay fail-OPEN lock.
 - Tenant-safety: @SystemTransactional discovery (BYPASSRLS, sees all tenants) + per-item
   TenantWorkRunner.callInTenant write so RLS WITH CHECK scopes the marker to the row's own tenant
   (L-034/L-035). The gateway call + marker write stay SYNCHRONOUS on the bound thread (no async callback).
 - Failure handling: a gateway FAILURE leaves executed_at NULL (re-drivable next cycle) + bounded
   reconcile_attempts + exponential backoff; an exhausted refund is loudly surfaced (operator-signal sweep,
   ERROR log) — never silently stranded, never flipped to a terminal state. A PSP `pending` response is
   BENIGN (re-checkable, does NOT increment attempts) so a normally-settling async refund never false-pages.
 - Migration iam/V4025: executed_at + reconcile_attempts + next_reconcile_at + last_reconcile_error +
   partial index, all additive/nullable (no new status; chk_approval_status untouched), safe on a non-empty DB.
CONSEQUENCES: a gateway failure mid-execute self-heals within ~1 min, no manual recovery, no double-pay.
T3 review (3 lenses): double-pay/money-safety, concurrency/tenant, test-adequacy — all SHIP_WITH_NITS, 0
blockers; the pending-false-page + lockless-signal nits were fixed; V4024 collision renumbered to V4025.
RESIDUALS (tracked, not this PR): B-022-async (a getRefund-poll/webhook settle path + max-pending-age signal
so a long-pending refund eventually pages); a Docker-gated @DataJpaTest proving the conditional-UPDATE SQL
invariants (mark-once / discovery filters) — folds into B-016. The original ApprovalController path now also
stamps executed_at on success so the common case never enters the reconciler.

## ADR-020 | 2026-06-15 | SEC-BATCH-2: dispute-webhook auth + replay + idempotency (T3)
STATUS: Accepted (T3 — money/webhook auth; via PR). Closes audit SEC-01 (CRITICAL).
CONTEXT: POST /internal/webhooks/disputes was permitAll, unsigned, non-idempotent, and trusted a client
X-Tenant-Id — so anyone reaching it could forge/replay events that drive real chargeback-ledger reserves
(DR chargeback_reserve / CR merchant_receivables), draining a victim merchant.
DECISION (mirror HyperSwitchWebhookController, but fail-CLOSED):
 - HMAC-SHA512 over the RAW body, constant-time MessageDigest.isEqual, FAIL-CLOSED 401 on missing secret /
   missing / invalid signature (drop HyperSwitch's dev fail-open branch — money endpoint).
 - Tenant is SERVER-AUTHORITATIVE from the HMAC-VERIFIED payload (drop the X-Tenant-Id header; the signed
   body is trustworthy because only the PSP holds the secret) — never a client header (SEC-BATCH-1/L-048).
 - openDispute IDEMPOTENT on (tenantId, externalDisputeId): lookup-then-no-op so createChargebackReserve
   fires exactly once; V4026 adds UNIQUE(tenant_id, external_dispute_id) as the race backstop (pre-dedup
   asserted, NULL-safe). A dispute.opened with a blank external_dispute_id is REJECTED 400 (Postgres treats
   NULLs as distinct, so a blank id would bypass the unique dedup).
 - The new nexuspay.dispute.webhook-secret default is registered in StartupSecretsValidator.KNOWN_DEFAULTS
   (+ prod env-var guidance + drift-guard test) so prod refuses to boot on the public default (B-004/L-017).
 - Flipped DisputeWebhookAuthReplayRedteamTest from @Tag(redteam) report-only INTO the default gate — now a
   permanent regression guard (unsigned→401, replay→single reserve, forged X-Tenant-Id ignored vs the signed
   body tenant).
CONSEQUENCES: the chargeback ledger can no longer be moved by a forged/replayed/cross-tenant webhook. T3
review: auth-correctness + test-adequacy SHIP_WITH_NITS; money-safety BLOCK→fixed (the KNOWN_DEFAULTS gap +
the blank-id bypass + the test that didn't prove header-ignored). See L-051.
RESIDUAL (tracked, NOT this PR — SEC-24): LedgerChargebackAdapter posts under DEFAULT_TENANT, so even a
legitimate dispute's DR/CR lands on the default tenant's accounts, not the dispute's tenant — a money
mis-attribution correctness bug separate from the auth/replay fix. Needs the dispute tenant threaded into
the ledger posting (cross-module; its own T3 change).

## ADR-021 | 2026-06-15 | SEC-BATCH-3: encrypt PAN-at-rest on the SDK tokenize path (T3, PCI)
STATUS: Accepted (T3 — PCI/crypto; via PR). Closes audit SEC-04 + the SEC-03 residual.
CONTEXT: the SDK tokenize path stored the full PAN base64-encoded (reversible, unencrypted) in
payment_tokens.token_data with a null encryption_key_id — a PCI-DSS at-rest violation (a DB dump/SQLi/
backup/insider base64-decodes back to the live PAN). The vault module already encrypts (AES-256-GCM) but
gateway-api could not reach its EncryptionPort.
DECISION: encrypt token_data in place via AES-256-GCM + set encryption_key_id (option a — minimal PAN
footprint; option b "store a vault ref" was rejected because it would send the cleartext PAN gateway→vault
and add a gateway→vault edge). Wiring (L-048, zero new module edges): LIFT EncryptionPort into
io.nexuspay.common.crypto (common is Type.OPEN); vault AesGcm/Hsm adapters implement the common interface
(vault→common already legal); gateway-api TokenizationService injects the common EncryptionPort; the single
@Component adapter bean resolves at the :app composition root (which depends on both vault + gateway-api) —
no gateway→vault dependency. Stored = [12-byte IV][ciphertext+GCM tag], non-reversible without the
master key (B-004-guarded, fail-closed). Safe display fields (last4/brand/exp) unchanged.
MIGRATION V4027 (gateway schema, DATA not DDL — token_data BYTEA + encryption_key_id already exist): PURGES
existing base64 token_data rows (encryption_key_id IS NULL) — they are cleartext PAN at rest and CANNOT be
re-encrypted in-migration (no key in SQL), so the only safe action is to remove the recoverable PAN; the
token rows are re-creatable on the next tokenize. Safe on a non-empty DB (L-041).
SEC-03 RESIDUAL: the card iframe pins apiBase/sessionToken ONCE at init and ignores later message attempts to
change them (defense-in-depth beyond the DX-1 origin pin); CheckoutSecurityHeadersFilter frame-ancestors
tightened off '*'.
CONSEQUENCES: no recoverable PAN persists anywhere on this path. PanPersistenceRedteamTest flipped from
@Tag(redteam) report-only INTO the default gate (SEC-04 permanent guard). T3 review (PCI / regression+modulith
/ test-adequacy): all SHIP, 0 blockers. See L-052. MERGE-ORDER: V4027 > V4026 (after SEC-BATCH-2).

## ADR-022 | 2026-06-15 | SEC-BATCH-4: money-dup — ledger double-post + payout double-pay (T3)
STATUS: Accepted (T3 money; via PR). Closes audit SEC-10 + SEC-11 (HIGH).
SEC-10 (ledger double-post on Kafka redelivery): the capture/refund idempotency was check-then-act
(existsByPaymentReferenceAndDescription) with no DB backstop → a duplicate delivery / DLT-replay racing the
check double-posted the journal. FIX: V4028 adds UNIQUE(payment_reference, description) on journal_entries
(pre-dedup existing rows first, L-041); CreateJournalEntryUseCase now saveAndFlush-es (the @Id is pre-assigned
so plain save() defers the INSERT past the catch — L-041) and catches the duplicate-key violation NARROWED to
uq_journal_entries_payment_ref_desc as a NO-OP return (not a throw → no retry/DLT). The SERIALIZABLE tx means
the loser's balance increments roll back with the duplicate row (no double-credit). existsBy stays the fast path.
SEC-11 (payout double-pay on multi-replica): PayoutScheduler had no distributed lock + whole-batch tx → both
replicas disbursed the same PENDING payouts. FIX: inlined MarketplaceSchedulerLock (fail-CLOSED, ADR-006; the
B-022 GatewaySchedulerLock precedent — marketplace can't reach billing's/gateway's lock and lifting to common
is forbidden, so inline + add the redis starter) wrapping processPendingPayouts; AND the REAL guarantee — an
atomic per-payout claim `UPDATE payouts SET status=PROCESSING WHERE id=? AND status=PENDING` (rows==1; only the
winner disburses), so even a lock failure can't double-pay (L-018/B-009 precedent). No migration (existing status).
CONSEQUENCES: the journal posts once under redelivery; a payout is disbursed by exactly one replica.
LedgerDoublePostRedeliveryRedteamTest + PayoutDoublePayRedteamTest flipped from @Tag(redteam) report-only INTO
the gate (SEC-10/SEC-11 permanent guards). T3 review (money-once / migration+concurrency / test-adequacy): all
SHIP_WITH_NITS, 0 blockers. MERGE-ORDER: V4028 > V4027. RESIDUAL (SEC-25): the payout disburse-before-commit
window (a crash after claim PROCESSING but before disbursement leaves a stuck PROCESSING payout) — needs a
payout reconciler like B-022's for refunds (the atomic claim prevents double-pay, not the stuck state).

## ADR-023 | 2026-06-15 | SEC-BATCH-4b: outbound-webhook SSRF + dead-letter stuck-RETRYING (T3)
STATUS: Accepted (T3; via PR). Closes audit SEC-14 + SEC-16 (HIGH). NEW RUNTIME DEP (flagged): httpclient5
(Apache, Spring-BOM-managed 5.2.3) added to gateway-api for SSRF-safe IP-pinned delivery — supply-chain
checked by the perpetua-gates OSV scan; mainstream Apache lib. (Charter new-dep rule noted; human-flagged.)
SEC-14 (outbound webhook SSRF): WebhookDeliveryService POSTed to merchant URLs with no validation. FIX:
common.net.WebhookUrlValidator (https-only; reject loopback/RFC1918/link-local incl. 169.254.169.254/ULA
fc00::/7/CGNAT 100.64/10/IPv4-mapped/multicast/0.0.0.0/NXDOMAIN; multi-record any-private reject) enforced at
REGISTRATION (@SafeWebhookUrl → 400) AND at DELIVERY. Three real holes closed in review: (1) redirects were
followed → 3xx to an internal host = deterministic SSRF → redirects DISABLED + 3xx treated as a delivery
failure; (2) DNS-rebinding TOCTOU → the validator's resolved InetAddress[] is now PINNED per-delivery via an
Apache HttpClient5 custom DnsResolver (connect goes ONLY to validated IPs, fail-closed for unpinned; TLS
SNI/Host keep the hostname so certs validate) — the check and the connect use the SAME resolution; (3) the new
gate broke the loopback WebhookDeliveryServiceTest → a package-private validator-seam constructor lets the test
permit loopback without weakening production. 49 WebhookUrlValidator unit tests (in-gate) + a positive
registration case. OutboundWebhookSsrfRedteamTest flipped into the gate.
SEC-16 (dead-letter stuck RETRYING): DeadLetterReprocessor flipped RETRYING then mutated the terminal state in
an async Kafka callback outliving the tx/lock/role-pin → rows stuck RETRYING forever (findRetryable selects
PENDING only). FIX: BLOCK on the send ack (.get(SEND_ACK_TIMEOUT), mirror OutboxRelay) + mutate
RESOLVED/PENDING/DISCARDED SYNCHRONOUSLY inside the @SystemTransactional tx; on failure → PENDING+backoff
(re-selectable). BATCH_SIZE bounded to 6 so worst-case cycle (6×10s) stays ≤ ½ the 120s lock TTL (no overrun).
T3 review (SSRF-bypass / dead-letter-correctness / test-adequacy): SSRF BLOCK→fixed (redirects + real IP-pin +
test); dead-letter + tests SHIP_WITH_NITS. ADR + L-053.

## ADR-024 | 2026-06-16 | SEC-BATCH-1b: payment-lifecycle + ledger-query + webhook fan-out tenant scoping (T3)
SEC-07/B-007 (payment-lifecycle IDOR): get/capture/cancel/confirm + the sub-threshold refund forwarded the gateway
payment id straight to the PSP with NO tenant-ownership check (a tenant-A operator could refund tenant-B with
amount<50000, dodging maker-checker). FIX: `ScreeningOriginService.assertOwnedBy(paymentId, callerTenant)` —
fail-closed 404 (no existence oracle) when the trusted server-owned origin row is absent OR its tenant != caller;
called in PaymentController (get/capture/cancel/confirm) + RefundOrchestrationService BEFORE the threshold branch.
SEC-08/B-008 (ledger query leak): LedgerController.listJournalEntries returned EVERY tenant's journal lines; now
threads principal.tenantId() through GetJournalEntriesUseCase → port → JPA (findByPaymentReferenceAndTenantId +
`AND j.tenantId=:tenantId` on findByDateRange), mirroring listAccounts. Cross-tenant findByPaymentReference(String)
retained for internal callers only.
SEC-09/B-009 (webhook cross-tenant fan-out): producer (HyperSwitchWebhookController) stamps the outbox tenant from
the trusted screening-origin store (keyed by gateway payment id); relay (OutboxRelay) carries it as a `tenant_id`
Kafka header; consumer (WebhookDeliveryService) filters endpoints by event tenant UNCONDITIONALLY (not gated on
rls.enforce). Hardened in review: extractTenant trusts ONLY the relay-stamped header, NEVER payload metadata (lowest-
trust input); DeadLetterReprocessor re-attaches the original headers (incl. tenant_id) on republish.
Availability backfill V4029 (payment_screening_origin): assertOwnedBy + fan-out now treat the origin store as a HARD
authority, but B-029 shipped no backfill — pre-V4022 payments (authorized-but-uncaptured intents, refundables) had
no row → their legitimate owner was 404'd + webhooks dropped. V4029 backfills tenant from TRUSTED server-owned
records (event_outbox.tenant_id PRIMARY, journal_entries.tenant_id SECONDARY), skipping 'default'/null (no invented
data; fail-closed preserved for the un-attributable residue); idempotent (ON CONFLICT DO NOTHING), strictest rail.
Adversarial review: 0 BLOCKERS, 3 SHOULD_FIX (all applied). CI caught two latent defects inherited from the SEC-4b
base that the no-CI review could not: a Spring two-constructor context collapse (L-054) and @SafeWebhookUrl ITs using
NXDOMAIN hosts (L-055) — both fixed on SEC-4b and inherited here via the rebase onto main. ADR-024, L-054, L-055.

## ADR-025 | 2026-06-16 | INT-1: canonical versioned outbound webhook contract (T3)
The merchant-facing webhook was unconsumable by a standard client: a nested PascalCase envelope
{event_id,event_type,aggregate_id,timestamp(ISO),payload} vs the industry {id,type,created,data} shape, carrying NO
merchant correlation metadata. INT-1 makes the PUBLIC contract canonical + versioned WITHOUT touching the internal
Kafka payload (internal consumers unchanged): WebhookDeliveryService transforms each outbound delivery into
{id, type, created(epoch s), api_version, data:{object, metadata}} via WebhookEnvelopeSerializer and RE-SIGNS
HMAC-SHA256 over the EXACT transformed body. Types are dotted-lowercase (common.event.WebhookEventTaxonomy:
PaymentCaptured→payment.succeeded, RefundCompleted→payment.refunded, PaymentFailed→payment.failed, …); internal
subscribesToEvent still matches the internal type. The public `id` is stable (anchored on the PSP
original_event_id) for idempotent redelivery. MERCHANT METADATA ROUND-TRIP: a NEW tenant-scoped store
payment_webhook_metadata (V4030) is written at create (GatedPaymentGateway.doCreate, alongside
screeningOrigins.record) capturing the merchant `metadata` map ONLY (never payment_method_data/PAN; size/key-capped)
and read at delivery (tenant-scoped) into data.metadata — so userId/packId-style correlation rides the
SERVER-DERIVED path, never the PSP echo or client. Registration now REJECTS unknown event-name strings
(@CanonicalWebhookEvents on CreateWebhookEndpointRequest). Review: 0 BLOCKERS, 1 SHOULD_FIX applied (app-level
tenant assertion on the metadata read so isolation holds independent of rls.enforce, mirroring B-007). ADR-025.

## ADR-026 | 2026-06-16 | INT-2: payments/session contract polish (T3)
Four merchant-DX contract fixes. (1) CAPTURE ALIAS: CreatePaymentRequest accepts an optional Boolean `capture`;
when set and capture_method is absent it maps true→automatic/false→manual (capture_method authoritative when
both) — additive, no security surface. (2) STANDARDIZED ERROR ENVELOPE (cross-cutting): every API error now emits
`{error:{type,code,message,request_id}}` via the evolved common ApiError (param→request_id; stable taxonomy
validation/not_found/unauthorized/forbidden/conflict/payment/rate_limit/internal) + GlobalExceptionHandler, with
request_id sourced from CorrelationIdFilter's MDC (UUID fallback); ALL 500s hardened to a generic message (no
ex.getMessage()/stack/SQL leakage); HTTP status codes unchanged. The adversarial blast-radius lens caught a missed
path — SessionTokenAuthenticationFilter's 401 still emitted the retired `authentication_error` envelope — fixed +
regression-tested (ApiKey + SessionToken 401-envelope tests); an FxRate DCC 422 plain-string body was also
normalized. (3) REFUND requires_approval: the maker-checker 202 now carries `requires_approval:true` +
`approval_threshold`; the created path carries `requires_approval:false`. (4) SESSION METADATA PARITY: payments
created via the session/SDK path persist the merchant metadata map into the INT-1 payment_webhook_metadata store
(tenant-scoped, no PAN) so the SDK path round-trips correlation to webhooks like /v1/payments. No migration. Review:
3 BLOCKERS (all the same missed 401 envelope) + 3 SHOULD_FIX, all applied. ADR-026.

## ADR-027 | 2026-06-16 | INT-3: real sandbox — key-mode routing + MockPaymentGatewayPort (T3)
"sk_test_" was a cosmetic label (GAP-10): is_live was never read in the charge path, so a test key could move
real money. INT-3 makes test-mode a HARD, platform-side guarantee in EVERY profile. Mode is SERVER-DERIVED from
the authenticated API key's is_live → threaded into NexusPayPrincipal and into a request-scoped ThreadLocal
PaymentMode (common.mode, set by the auth filters, cleared in finally — mirrors TenantContext). GatedPaymentGateway
(@Primary chokepoint) now routes EVERY port op (create/confirm/capture/void/get/refund) to a new deterministic
MockPaymentGatewayPort (in-memory, pay_test_*/re_test_* ids, ZERO HyperSwitch/HTTP) for test callers, and to the
real HyperSwitch adapter for live — proven by verifyNoInteractions(hyperSwitchAdapter) on a test key. Async/system
(Kafka consumer) contexts default to LIVE; request contexts fail-closed to TEST. The mock SYNTHESIZES the canonical
internal outbox events (MockWebhookSynthesizer: PaymentCaptured/RefundCompleted, livemode=false, source=mock_sandbox,
trusted tenant) so the INT-1 webhook→credit loop fires end-to-end in test mode with metadata round-trip. mode/livemode
is stamped on payment responses + the canonical webhook envelope. Adversarial review caught TWO real bypasses
(both fixed): (1) the SDK/session checkout path had no mode → real charge for a test merchant → session JWT now
carries a signed `livemode` claim (fail-closed to test when absent) threaded through PaymentSession*; (2) the
above-threshold approved-refund path (executeApprovedRefund) reached the real PSP → now mode-routed. 2 BLOCKERS +
4 SHOULD_FIX, all applied. No migration. ADR-027.

## ADR-028 | 2026-06-16 | INT-4: outbound webhook delivery reliability (T3)
Outbound webhook delivery was fire-once-and-log (no retry/DLQ) — a merchant outage = permanently lost events.
INT-4 makes it persisted at-least-once. New webhook_deliveries table (V4031, gateway leaf; global max was V4030)
records one row per (endpoint,event) — unique (endpoint_id,event_id) + saveAndFlush dup-key no-op (L-041) = no
double-record; status PENDING→DELIVERED/FAILED→DEAD; RLS-policied + tenant-scoped. The SEC-4b SSRF-pinned +
INT-1 canonical-transform + per-attempt HMAC re-sign send was REFACTORED into one shared method used by both the
Kafka consumer and a new leader-locked @Scheduled WebhookDeliveryRetrier (atomic Valkey owner-checked release,
B-018) that re-attempts FAILED rows with exponential backoff+jitter, →DEAD (DLQ) at max attempts. Signature uses
the endpoint's CURRENT secret each attempt (so rotation takes effect next try). Tenant-scoped list + admin replay
API (404 no-oracle, secret never exposed) + a secret-rotation endpoint (new whsec_ once). Adversarial review found
+ fixed a real lost-delivery hole (PENDING rows orphaned by a pre-outcome crash were never swept → now re-driven
after a staleness threshold, with a crash-recovery test) and a lock-expiry-mid-batch double-send (now an atomic
per-row claimForRetry conditional UPDATE — exactly one leader claims a row). 1 BLOCKER + 3 SHOULD_FIX, all applied.
Migration V4031. ADR-028.

## ADR-029 | 2026-06-16 | INT-5: @nexuspay/node backend SDK (DX)
Every server integrator was hand-rolling fetch/auth/idempotency/error-mapping/webhook-parse against an
undocumented shape (exactly how Snap called the wrong endpoint). New workspace package
checkout-sdk/packages/node (@nexuspay/node, MIT, zero runtime deps, Node 18+ built-in crypto + global fetch,
ESM+CJS via tsup): a typed NexusPay client (createPaymentSession/createPayment[incl INT-2 capture alias]/getPayment/
capture/cancel/confirm/createRefund) with Bearer auth + optional Idempotency-Key + AbortController timeout, mapping
the INT-2 {error:{type,code,message,request_id}} envelope to a typed NexusPayError and surfacing the 202
requires_approval refund as a discriminated result; plus verifyWebhook (never throws) + constructEvent (throws
SignatureVerificationError) that BYTE-MATCH the platform's INT-1 signing (HMAC-SHA256 over the raw body, lowercase
hex, bare or sha256=-prefixed, crypto.timingSafeEqual) and return the typed canonical WebhookEvent
{id,type,created,api_version,data:{object,metadata},livemode}. Review hardened the replay window to anchor on the
HMAC-SIGNED `created` (the X-NexusPay-Timestamp header is OUTSIDE the HMAC and spoofable) — new createdToleranceSeconds
option + test proving a rewritten header can't defeat it. 0 BLOCKERS, 2 SHOULD_FIX applied (replay-window + README).
Locally npm build+test green (33 tests). No migration, no Java change. ADR-029.

## ADR-030 | 2026-06-16 | INT-6: finish /v1/checkout/confirm result contract (T3)
INT-3 had already wired confirm to create+capture through the mode-routed gateway with server-stored session
metadata + tenant + idempotency key (canonical event fires via auto-capture), but confirm DISCARDED the
PaymentResponse and returned a session-status object — so the SDK couldn't tell success from a held/failed
payment. INT-6 returns a proper ConfirmResponse {status: succeeded|processing|requires_action|failed, payment_id,
mode, next_action} (new DTO + ResponseMapper.toConfirmResponse) DERIVED from the real PaymentApiResponse — a
capture-held-for-review surfaces as requires_action/processing, NEVER succeeded; gate PaymentExceptions propagate
to the INT-2 error envelope (no 500/leak). Re-confirming a COMPLETED session is idempotent (read-only getPayment
short-circuit, no second charge); expired session stays 410. The @nexuspay/js ConfirmResult type was aligned.
End-to-end IT proves a test-mode confirm delivers the canonical payment.succeeded webhook. 0 BLOCKERS, 0 SHOULD_FIX
(all four lenses SHIP). No migration. ADR-030.

## ADR-031 | 2026-06-16 | INT-7: SDK publish-readiness + release pipeline (DX)
The SDK packages existed but were unpublishable (dist gitignored, no files/exports/publishConfig). INT-7 makes
@nexuspay/js, @nexuspay/react, @nexuspay/node publish-ready (files allowlist [dist+README+LICENSE only],
exports/main/module/types→dist, publishConfig.access public, repository/homepage/bugs, MIT LICENSE + README per
package, engines, prepublishOnly→build); nexuspay-checkout stays private:true (Vite demo app). Added
.github/workflows/release.yml — tag sdk-v* / workflow_dispatch, SHA-pinned actions (reusing the repo's existing
B-012 pins), minimal perms (contents:read, id-token:write), NODE_AUTH_TOKEN←secrets.NPM_TOKEN, npm publish
--access public --provenance, skips cleanly without the token; + checkout-sdk/PUBLISHING.md. Verified: whole
workspace builds + npm publish --dry-run for all three lists exactly dist+README+LICENSE+package.json (no
src/tests). Versions unchanged (0.1.0). The actual npm publish is the OWNER's credentialed action (add NPM_TOKEN
secret + push an sdk-vX.Y.Z tag). 0 BLOCKERS, 1 SHOULD_FIX (README snippet). No migration. ADR-031.

## ADR-032 | 2026-06-16 | INT-8: local-dev sandbox (lite compose + seed script + runbook)
Running NexusPay locally needed ~17 containers (GAP-12). INT-8 adds a lite path: docker/docker-compose.lite.yml
= only nexuspay-pg + kafka + valkey + keycloak (+ the one volume), each service block byte-identical to the full
compose except host ports templated to ${...PORT:-default}. The app runs via ./gradlew :app:bootRun with
SPRING_CLOUD_VAULT_ENABLED=false (the critic confirmed Vault auto-config else fails at boot — env, not code) and
SPRING_PROFILES local; HyperSwitch/Temporal/Vault/schema-registry/observability are omitted because INT-3's
in-process MockPaymentGatewayPort handles sk_test_ keys (test mode needs no real PSP) and nothing else is required
at boot. scripts/dev/seed-local.sh automates the GAP-01 onboarding ritual: Keycloak admin token (seeded realm
user) -> POST /v1/api-keys (operator, live=false) -> prints the once-shown sk_test_ key + POST /v1/webhook-endpoints
-> prints the whsec_ + endpoint id; ZERO literal secrets in the file (all runtime-generated; gitleaks-clean),
LOCAL-DEV-ONLY header + Windows note. docs/LOCAL_DEV.md runbook (compose up -> bootRun :8090 -> seed -> curl a
sk_test_ payment -> canonical webhook) with the SEC-4b localhost-webhook caveat (loopback rejected -> tunnel or
@nexuspay/node self-signed verify) + a port-override example. Review caught a compose port double-bind + a
host->Kafka listener mismatch (use localhost:29092 PLAINTEXT_HOST) that would've broken the webhook loop — both
doc-fixed. 0 BLOCKERS, 2 SHOULD_FIX. No Java change, no migration, no committed secrets. Ops/docs validated by
review (not CI-exercised). ADR-032.

## ADR-033 | 2026-06-16 | INT-9: integration documentation (DX)
The single biggest adoption gap (GAP-11) was no committed, self-contained contract — exactly how Snap called the
wrong endpoint expecting a field that never existed. INT-9 ships: docs/api/openapi.yaml (curated OpenAPI 3.1, 16
paths + 19 schemas + 3 bearer security schemes, cross-checked against the real controllers/DTOs — sessions,
payments incl capture alias, refunds incl 201/202 requires_approval, webhook-endpoints incl rotate/list/replay,
api-keys, the canonical WebhookEvent + dotted event enum + the {error:{type,code,message,request_id}} envelope;
notes that the live GET /v1/api-docs is authoritative + how to snapshot/codegen); docs/WEBHOOKS.md (taxonomy,
annotated envelope with livemode + data.metadata, the 3 headers, HMAC-SHA256 verify via @nexuspay/node AND
dependency-free crypto, idempotency, correlation, retry/DLQ/replay/rotation, SEC-4b https requirement);
README rewrite (keeps the PolyForm-NC + sponsors License section verbatim); docs/INTEGRATION.md (end-to-end
cURL + Node quickstart + merchant-vs-platform responsibility matrix + the INT-3 test-mode guarantee); and
docs/integrations/snap-loyalty.md — the concrete Snap (Next.js) path-forward mapping every gap (GAP-01..16) to how
it is now closed (data.metadata round-trips, dotted events now match Snap's expectation, @nexuspay/node, test-mode
mock, the localhost-webhook tunnel caveat), referencing Snap's files READ-ONLY (no Snap code modified, per standing
instruction). All example secrets are placeholders (gitleaks-safe). 0 BLOCKERS, 1 SHOULD_FIX (delivery-id prefix
whd_). No code, no migration. ADR-033.

## ADR-034 | 2026-06-16 | INT-10 / SEC-22: API-key prefix-collision hardening (T3)
ApiKeyService.authenticate looked the key up by its 12-char prefix via findByKeyPrefixAndRevokedAtIsNull returning
a single Optional — so two un-revoked keys sharing those 12 chars (sk_test_/sk_live_ = 8-char scheme + 4 random)
made Spring Data throw IncorrectResultSizeDataAccessException → 401 for BOTH (a self-inflicted DoS on the
legitimate key). Fix (candidate-iterate, no migration, backward-compatible): the finder returns List<ApiKeyEntity>;
authenticate iterates the (typically size-1) candidates and passwordEncoder.matches(rawKey, hash) each, returning
the matching key's principal; no match → the existing uniform invalid_api_key (IDENTICAL outcome whether 0/N
candidates/revoked/wrong-scheme — no existence oracle). Revoked keys still excluded (AndRevokedAtIsNull). The only
production caller is authenticate; the filter mocks authenticate, unaffected. New ApiKeyServiceCollisionTest (6
cases) proves two prefix-colliding keys each authenticate to their own principal (fails on the old single-Optional
code), a wrong colliding key → uniform 401, a revoked colliding sibling is excluded, and principal tenant/role/live
are correct. Fix agent rightly rejected an H2-@DataJpaTest suggestion (no H2 in this repo; all DB tests are
Testcontainers/Postgres) in favor of an infra-free service-layer test. Orchestrator shortened the test's fake
collision keys (sk_test_coll_a/b/z, underscore body) to stay gitleaks-clean while preserving the 12-char prefix
collision. 0 BLOCKERS, 1 SHOULD_FIX. No migration. SEC-22 closed. ADR-034.

## ADR-035 | 2026-06-16 | SEC-23: cross-tenant IDOR on remaining b2b + fraud controllers (T3)
B2bInvoiceController, PurchaseOrderController, FraudAssessmentController trusted a client @RequestHeader
("X-Tenant-Id") (fraud even defaulted to "default") — cross-tenant IDOR on b2b money + fraud surfaces, the same
class SEC-BATCH-1 closed elsewhere. Fix: tenant now comes from the authenticated principal via
common.tenant.CallerTenant.require(); every read AND mutation resolves via a tenant-scoped findByIdAndTenantId +
TenantOwnership -> ResourceNotFoundException (404, no existence oracle). Adversarial review caught a DEEPER residual
the controllers hid: B2bInvoiceService.createInvoiceFromPO loaded the referenced purchase order via the GLOBAL
(un-scoped) finder, then read its financials + called po.markInvoiced() — a cross-tenant read AND write on a money
surface; fixed to the 2-arg tenant-scoped finder + 404 before any read/mutation (markInvoicePaid's linked-PO load
tightened too, defense-in-depth). app TenantIsolationIntegrationTest extended with cross-tenant cases per endpoint
incl. the create-from-foreign-PO case (asserts 404 + the PO stays APPROVED, not INVOICED) — all fail on the
vulnerable code. 2 BLOCKERS (same defect, 2 lenses), applied. No migration (tenant_id cols exist). ADR-035.

## ADR-036 | 2026-06-16 | SEC-24: dispute chargeback ledger posts under the dispute's tenant (T3)
LedgerChargebackAdapter.post() hardcoded EnsureAccountsExistUseCase.DEFAULT_TENANT ("default") as the journal
tenant AND called the no-tenant ensureAccountsForCurrency overload — so EVERY chargeback entry (open DR
chargeback_reserve / CR merchant_receivables; win reversal; loss/expire expense) landed on tenant_id='default'
regardless of which merchant's dispute fired it → a chargeback for merchant X silently reduced the DEFAULT
tenant's receivables (money mis-attribution). Fix: tenantId is now the first parameter of all three LedgerPort
methods; DisputeLifecycleService passes the dispute's SERVER-AUTHORITATIVE tenant (SEC-BATCH-2) — openDispute
forwards its tenantId, win/lose/expire forward the persisted dispute.getTenantId() so the reversal/expense lands
under the same tenant; the adapter routes through the existing tenant-aware ensureAccountsForCurrency(ccy, tenant)
+ stamps the journal command's tenantId. Double-entry stays balanced + the SEC-10 idempotency holds (now correctly
tenant-scoped). No migration needed (existing tenant-aware overload + tenant_id columns). New
LedgerChargebackAdapterTest pins tenantId on all 3 flows (+ asserts never DEFAULT_TENANT, balanced postings) —
fails on the vulnerable code. 0 BLOCKERS, 2 SHOULD_FIX (adapter test), applied. ADR-036.

## ADR-037 | 2026-06-16 | SEC-25: payout disburse-before-commit reconciler (T3)
PayoutService commits the SEC-11 atomic claim (PENDING->PROCESSING) and only then disburses + writes the terminal
markPaid/markFailed; a crash in that window strands the row PROCESSING forever (the scheduler's finder selects only
PENDING, so it's never re-driven — money un-disbursed, no double-pay). New leader-locked @Scheduled PayoutReconciler
(+ PayoutReconcileService) mirrors B-022's RefundReconciler and reuses the marketplace fail-closed SchedulerLock
(atomic Valkey owner-checked release, B-018/SEC-11): it finds payouts PROCESSING longer than a threshold (new
processing_since column, V4032) and re-drives each. NO double-pay: the disburse now ALWAYS carries a deterministic
idempotency key (payout-<id>, Payout.idempotencyKey) on BOTH the original path (PayoutService) and every reconciler
re-drive, so the PSP dedups (B-009); defense-in-depth re-checks status FOR UPDATE before re-driving. Terminal
transitions use the existing PAID/FAILED (conditional SQL UPDATEs); transient errors keep it PROCESSING + record
last_reconcile_error for a bounded next pass. V4032 adds processing_since + reconcile tracking (+ index). Tests:
stuck-PROCESSING recovered to terminal, idempotent re-drive (same key, no second disburse), leader-locked single-run,
threshold doesn't grab fresh payouts; SERIALIZABLE soak @Tag("simulation"). 0 BLOCKERS, 5 SHOULD_FIX (failure_reason
truncation vs column width, PSP-call-in-tx contract, race hardening), applied. Migration V4032. ADR-037.

## ADR-038 | 2026-06-16 | SEC-BATCH-5a: payment idempotency (SEC-12 + SEC-15) (T3)
SEC-12 (double-charge on retry): capture/void/refund passed the OPTIONAL Idempotency-Key header raw — a null key
meant no PSP dedup, so a network retry could double-capture/refund/void. PaymentController.resolveKey now returns
the caller key verbatim when present (authoritative) else derives a DETERMINISTIC key: capture-{id}-{amount|full},
void-{id}, refund-{id}-{amount} — so a retry of the SAME logical op sends the SAME key (PSP dedups) while distinct
ops/amounts don't collide (intentional same-amount partial refunds still need an explicit caller key — documented).
SEC-15 (lost redelivery): HyperSwitchWebhookController claimed the Valkey dedup key (setIfAbsent) BEFORE the
@Transactional DB work committed; Valkey isn't transactional, so a rollback left the eventId suppressed for the TTL
and the retryable webhook was never redelivered. Fix: keep the claim-first (concurrent-dup safety) but register a
TransactionSynchronization afterCompletion that DELETEs the dedup key when status != COMMITTED — a rolled-back/failed
webhook is now redeliverable; a successfully-committed one stays deduped. Inbound HMAC-SHA512 + tenant stamping +
outbox untouched. 9 SEC-12 + 4 SEC-15 tests (fail on the vulnerable code). No migration. 0 BLOCKERS, 0 SHOULD_FIX.
ADR-038.

## ADR-039 | 2026-06-16 | SEC-BATCH-5b: durable recon failure (SEC-17) + idempotent analytics rollups (SEC-18) (T3)
SEC-17 (lost failure record): a failed reconciliation run's terminal FAILED state was saved inside the same
@Transactional that then rolled back → no durable trace of failed runs. INITIAL naive fix (a REQUIRES_NEW
failure-recorder) was caught DO_NOT_SHIP by review: a REQUIRES_NEW tx that re-saves the SAME run PK the suspended
OUTER tx already inserted+locked SELF-DEADLOCKS (lock-wait) → record still lost + the gate test would hang.
Restructured: ReconciliationRunLifecycle commits the run (PENDING then RUNNING) in its OWN REQUIRES_NEW tx BEFORE
the work; ReconciliationExecutor runs the matching in a separate tx that never touches the run-status row (holds no
lock the recorder blocks on); ReconciliationFailureRecorder does a non-blocking UPDATE (merge the committed RUNNING
row → FAILED + a durable SYSTEM_ERROR ReconciliationException) on the orchestrator's catch. Success path unchanged.
SEC-18 (rollup double-count): the payment/fraud/routing analytics consumers applied additive upserts with no
idempotency → Kafka redelivery/DLT replay inflated revenue/auth-rate/decline metrics. New processed_analytics_events
(V4033, UNIQUE (event_id, rollup_kind)) + saveAndFlush dup-no-op: each additive upsert dedups on the stable event id
PER rollup_kind (so one event updating several rollups still counts each once) and SKIPs on replay; first delivery
unchanged. Real-Postgres ITs: failed-run durable record survives rollback; same event twice = counters unchanged,
distinct event still increments (a vacuous routing test was caught + fixed). 6 BLOCKERS (the REQUIRES_NEW
self-deadlock, one root cause across lenses) + 5 SHOULD_FIX, all applied. Migration V4033. ADR-039.

CI ITERATION (first PR-#27 CI run RED — both invisible to the no-Gradle static review, surfaced by real-Postgres CI):
(1) OOM. The SEC-17 IT's lone `@MockBean ThreeWayMatchingService` forked a SECOND full Testcontainers context into
Spring's cache (it was the only @MockBean among 24 app ITs); the extra ~300MB context OOM'd the `:app:test` JVM and
cascaded as a flood of unrelated gate FAILEDs (SEC-11/HyperSwitch/SEC-18) + 08001 + CannotCreateTransaction during
teardown. Fixed by replacing the @MockBean with FaultInjectableThreeWayMatchingService — a @Primary armable double in
the SHARED TestSecurityConfig (ThreadLocal-armed, delegates to super by default, inert for every other IT), keeping
the context-cache key identical → one context. The INT-3 MockPaymentGatewayPort precedent. L-057.
(2) Pre-existing L-056 grammar bug. The SEC-18 ITs are the FIRST to drive analytics' real native auth-rate upsert
against Postgres (the prior unit tests MOCK the repo), exposing a latent `(...)::DECIMAL` cast in
AnalyticsRepositoryAdapters.upsertHourly (committed 2026-03-27, untouched by this batch) — the exact L-056 `::`-vs-
`:param` collision that SEC-25's "repo-wide scan (clean)" MISSED because it only matched the `::timestamptz` form. So
the auth-rate rollup threw SQLGrammarException on every PaymentFailed/routing event in prod (revenue passed: no cast).
Swept ALL 6 analytics SQL casts → ANSI CAST(... AS ...): upsertHourly + RollupJobService (hourly→daily→monthly
aggregation, `::DECIMAL`/`::INTEGER`/`::DATE`). The SEC-18 dedup logic itself was correct; only the cast blocked it.
NOTE: the marker adapter uses INSERT ... ON CONFLICT (event_id, rollup_kind) DO NOTHING (single-statement claim, no
23505 on our own dup) rather than saveAndFlush — so a multi-rollup event (PaymentFailed → AUTH_RATE then DECLINE)
never poisons the shared tx on a concurrent dup of the first marker.

## ADR-040 | 2026-06-16 | SEC-BATCH-5c: fee BigDecimal HALF_EVEN + split idempotency + /internal IP rate-limit (T3)
SEC-19 (float money math): PlatformFee.calculateFee and SplitPayment.resolveAmounts computed fees/legs with
(long)(amount * percent.doubleValue() / 100.0) — float loses fractional minor units and biases collected fees.
Fixed via a new pure helper common/.../domain/MoneyRounding.percentageOfMinorUnits(amountMinor, percent, mode) =
BigDecimal multiply/divide-by-100 at scale 0 (minor units already encode the currency exponent → correct for
JPY/BHD) with RoundingMode.HALF_EVEN (banker's; avoids HALF_UP's systematic upward bias when collecting many
fees). The FX path keeps its existing HALF_UP (B-014) — out of scope, untouched.
SEC-20 (split idempotency): split_payments had no UNIQUE(tenant_id,payment_id) and createSplitPayment had no
lookup → a retry double-created the split tree. V4034 pre-dedups (FK-safe: child split_rules/platform_fees of
loser parents first, then losers, survivor=latest created_at) then adds UNIQUE(tenant_id,payment_id) (fraud V4023
precedent). SplitPaymentService now read-through-dedups (return existing) and, on the concurrent race, the write
runs in a SplitPaymentWriter @Transactional(REQUIRES_NEW) whose unique-violation rollback does not poison the
outer tx — the outer catch re-fetches the winner and returns it idempotently (never a 500).
SEC-21 (/internal rate-limit): the two HMAC-only inbound webhook endpoints (/internal/webhooks/{hyperswitch,
disputes}) had no rate limit (RateLimitFilter explicitly skips /internal/). New InternalWebhookRateLimitFilter
@Order(9) reuses the Valkey token-bucket Lua, keyed per CLIENT IP, fail-OPEN on Redis down (dropping real PSP
money-events is worse than brief flood exposure; HMAC still gates authenticity). X-Forwarded-For spoofing handled:
trust-forwarded-for DEFAULT false (getRemoteAddr); when true, the RIGHTMOST XFF entry (the hop the single trusted
proxy appended — spoof-resistant; leftmost entries are client-controlled).
REVIEW (4 adversarial lenses): 1 BLOCKER + 2 SHOULD_FIX + 2 NIT. BLOCKER (money-math): switching each PERCENTAGE
leg from truncation (always DOWN) to HALF_EVEN (can round UP) made equal-thirds splits (33.33/33.33/33.34) sum to
MORE than total, tripping the pre-existing over-allocation guard → IllegalStateException on VALID splits the old
code resolved. Fixed: detect genuine over-allocation against the EXACT un-rounded BigDecimal sum (rounding cannot
inflate it), and reconcile the rounding delta via largest-remainder redistributeDelta() (never drives a leg
negative; legs sum EXACTLY to total). L-058. SHOULD_FIX: narrowed the idempotency catch to ONLY the
uq_split_payments_tenant_payment violation (DuplicateKeyException or constraint name in the cause chain) so an
unrelated FK/NOT-NULL DataIntegrityViolation is NOT masked as the benign race (mirror FraudAssessmentService);
documented the rate-limit sizing. ORCHESTRATOR follow-through on the two remaining items: raised the per-IP
default 60→600/min (against a flood 60 vs 600 are equally effective, but 60/min=1/s needlessly 429s a legitimate
PSP burst — availability of money-events wins); fixed the inaccurate "BEFORE Spring Security" javadoc (Security
FilterChainProxy runs at -100, earlier — but /internal is permitAll so this is the first EFFECTIVE gate); added
app/.../SplitPaymentIdempotencyIT (real-Postgres: retried create returns same split + one row; distinct payments
get distinct splits) for the NIT that the concurrent-race unit test was a no-op without a real tx manager.
Migration V4034. ADR-040. CI is the oracle (no local Gradle).

## ADR-041 | 2026-06-16 | SEC-26: analytics + billing tenant from principal, not X-Tenant-Id header (T3)
Cross-tenant IDOR surfaced while writing the B-002 RLS cutover runbook: analytics/AnalyticsController (4
endpoints, @RequestHeader X-Tenant-Id defaultValue="default") and billing/{Invoice,Product,Subscription}
Controller derived the authorization tenant from the CLIENT header — an authenticated tenant-A caller
could read/act on tenant-B by setting the header (the defaultValue="default" made it worse: no header →
silently reads tenant "default"). SEC-1b/SEC-23 had closed payment/ledger/webhook/b2b/fraud but missed
these two modules. Also a hard precondition for the RLS cutover: RLS isolates to whatever tenant the app
BINDS, so a header-bound tenant defeats it entirely. Fixed by mirroring SEC-23: every endpoint now derives
the tenant from CallerTenant.require() (server-authoritative principal, 403 if absent); billing by-id
ops use tenant-scoped findByIdAndTenantId + TenantOwnership.require (404, no existence oracle). All targets
were already authenticated (analytics @PreAuthorize, billing global authenticated()), so the swap can't
403 a legitimate call. WebMvc-slice + service tests authenticate a principal and prove a supplied
X-Tenant-Id is ignored + a foreign id 404s.
REVIEW (3 adversarial lenses, 1 SHOULD_FIX + 1 NIT): the IDOR-completeness lens caught a SECONDARY
cross-tenant input the headline fix missed — a client-supplied priceId/newPriceId flowing into the UNSCOPED
productRepository.findPriceById(...) in createSubscription + changePlan. The subscription itself was
correctly scoped, but a tenant-A caller could bind/repoint THEIR OWN sub onto a tenant-B Price, importing
and disclosing another tenant's economic terms (unitAmount/currency/interval) via the resulting amounts.
Fixed: added ProductRepository.findPriceByIdAndTenantId + routed all three client-input price lookups
through it (404 on absent/foreign), + 3 tests. Correctly left RenewalScheduler/TrialManagementService
unchanged (their priceId is the sub's own stored value reached via a server-side scan, not client input).
L-059. The NIT (billing tests use an advice-backed real-404 assertion vs the assertThatThrownBy idiom
elsewhere) was kept — the advice approach is the stronger assertion. ADR-041. CI is the oracle.

## ADR-042 | 2026-06-16 | SEC-27: finish cross-tenant tenant-scoping across all remaining controllers (T3)
The capstone read-only adversarial security audit (6 attack lenses + independent refutation) found that
the SEC-1b/23/26 tenant-scoping sweep had STOPPED SHORT of four modules — a verified residue of HIGH
cross-tenant IDORs the earlier batches never reached: workflow (a WHOLE-MODULE IDOR — every by-id read
AND mutation ignored tenant; the tenantId param was dead in findOrThrow), dispute (by-id read/timeline +
submitEvidence/uploadEvidence state-changes on a raw disputeId), reconciliation (run/records read +
exception resolve/assign mutations + unbounded getRunRecords), and three payment-orchestration controllers
(RoutingConfig with a 'default' header fallback, FxRate, MerchantCurrencyPrefs). 7 controllers, 31
@RequestHeader("X-Tenant-Id") usages. RLS is dormant (app connects as table owner) so these were LIVE
exploitable IDORs, not theoretical. Closed by the canonical SEC-23/26 pattern, applied per-module by 4
parallel implement agents: tenant from CallerTenant.require() (never the header; no 'default' fallback);
new tenant-scoped repository finders findByIdAndTenantId on every entity (the workflow VERSION entity has
no tenant_id column, so its finder JOINs to the parent workflow_definitions and filters d.tenant_id); every
by-id READ and MUTATION routed through the scoped finder + TenantOwnership.require (404, no existence
oracle); recon getRunRecords pagination clamped; cross-tenant + header-ignored + foreign-id-404 tests per
module. After this, a repo-wide grep for @RequestHeader("X-Tenant-Id") in any controller returns ZERO —
no controller on the platform trusts a client-supplied tenant.
REVIEW (3 adversarial lenses): 1 BLOCKER — the IDOR-completeness lens caught that the payment-orchestration
agent scoped the DCC READ (getDccOffer) but left the two by-id MUTATIONS acceptDccOffer/declineDccOffer
UNSCOPED, so a tenant-A operator could flip tenant-B's DCC currency election (a money-affecting cross-tenant
state change) by guessing the offer UUID. Fixed: tenant-scoped service overloads that
TenantOwnership.assertOwned the loaded offer before mutating + CallerTenant.require() in the controller. This
is the SAME class as SEC-26's price-by-id residual — the implement pass scopes the obvious GET and the
sibling state-changing op is the easy miss. L-060. ADR-042. CI is the oracle (no local Gradle).

## ADR-043 | 2026-06-16 | SEC-28: webhook fail-closed + B-004 blank-secret guard + batch DoS cap + split negative-fee guard (T3)
Final batch from the capstone audit's MEDIUM/LOW residue. (1) HyperSwitchWebhookController FAIL-OPENED on a
null/blank secret (skipped HMAC verification → accepted unsigned webhooks); now UNCONDITIONALLY fail-closed
(blank → 401 before parse/verify) mirroring the SEC-2 DisputeWebhookHandler. Dev still verifies (the resolved
dev default webhook_secret_for_local is non-blank + prod-boot-guarded). (2) StartupSecretsValidator (B-004)
only failed prod boot when a KNOWN_DEFAULTS secret EQUALLED the in-source dev default; a null/BLANK secret in
prod slipped through (an empty HMAC/JWT/vault key is as dangerous). Extended to also FAIL boot under a prod
profile when any guarded secret resolves null/blank (dev/local/test still WARN) — closes the gap behind L-051.
(3) VendorPaymentController /batch accepted an unbounded list (one DB write/element in one tx → DoS); capped
via the configurable B2bProperties.batchMaxSize (default 100, env NEXUSPAY_B2B_BATCH_MAX_SIZE) — rejected
before any write. (4) SplitPaymentWriter could compute a NEGATIVE distributable (and negative payout legs)
when platformFee >= total; now rejected with IllegalArgumentException before allocating.
REVIEW (2 lenses) caught a BLOCKER: the fail-closed change broke the existing SEC-15 WebhookDedupRollbackRelease
test suite (4 tests posted UNSIGNED webhooks with a blank secret → now 401 before reaching the dedup logic) —
fix updated those tests to a non-blank secret + signed payloads (the SEC-15 control assertions retained). Also
SHOULD_FIX: the @Size method-param violation surfaced as HTTP 500 (HandlerMethodValidationException unhandled
by the gateway GlobalExceptionHandler) not the promised 400 → added an @ExceptionHandler(ResponseStatusException)
mapping to the TYPE_VALIDATION 400 envelope + tests; and made the cap read the configurable property rather
than a literal. WORKFLOW NOTE: the implement agent's final structured summary was blocked by a content filter
(false positive — it discussed webhook-secret handling), which truncated it BEFORE fix (4) (the split guard),
so the working tree had only 3 of 4 fixes; I detected the gap by diffing the tree against the spec and
completed the split guard + its regression test by hand. L-061. ADR-043. CI is the oracle.

## ADR-044 | 2026-06-17 | DX-2: @nexus-pay SDK 0.1.1 hardening (Snap DX critique) (T2/SDK)
The Snap team's evidence-grounded DX critique v2 (after adopting @nexus-pay/node@0.1.0 on their money
path) found real SDK gaps; DX-2 fixes them as a BACKWARD-COMPATIBLE 0.1.1 patch (all additive / opt-in /
deprecate-not-remove): (1.2) getHeader now accepts a WHATWG Headers (typeof bag.get === function → .get),
fixing Next.js App-Router constructEvent which previously read req.headers as empty → missing_signature on
every delivery; plain-object/string[] paths unchanged. (4.1) added 'api_error' to NexusPayErrorType
(node; js already had it) so the fallback type is in the union, not just the | string escape — restoring
the exhaustiveness GAP-10 promised. (4.3) createRefund now asserts a boolean requires_approval before the
discriminated return; envelope drift / a proxied 2xx throws NexusPayError(api_error/unexpected_refund_
response) instead of silently taking the requires_approval===false branch (ledger-corruption path closed).
(4.4) createPayment rejects client-side on a capture vs captureMethod CONFLICT (no silent server
precedence); agreeing/single values forward unchanged. (5.3) the unsigned, attacker-defeatable
toleranceSeconds is @deprecated + synonym unsafeHeaderToleranceSeconds, with the SIGNED createdToleranceSeconds
documented first as canonical; both honored (back-compat). (4.2) opt-in maxRetries (default 0 =
single-fetch, byte-identical to 0.1.0) with exp backoff honoring Retry-After on 429, retrying only
429/5xx/network, pinning ONE Idempotency-Key across the attempt sequence (auto crypto.randomUUID for a POST
if the caller gave none) so retries are safe by construction. (5.1) Payment JSDoc'd re no client_secret →
PaymentSession. +21 SDK tests (deterministic: mocked fetch + injected sleep/uuid/clock). npm build+test
GREEN (195 across js/node/react). REVIEW (2 lenses): 0 BLOCKER + 2 SHOULD_FIX — (a) the 0.1.1 caller-AbortSignal
cancellation now rejects with the raw AbortError, not NexusPayError(timeout) as 0.1.0 mis-mapped: kept the
more-correct behavior, corrected the "byte-identical" claim, and DOCUMENTED the behavior change in the node
README + CHANGELOG (a genuine tail-behavior delta on the {signal} path; default path unaffected); (b)
package-lock not bumped → regenerated. Versions 0.1.0→0.1.1 (js/node/react; private checkout demo stays).
Republish via the sdk-v0.1.1 tag → release.yml (the 0.1.1 tarball also ships DX-1's corrected node README).
L-062. ADR-044.

## ADR-045 | 2026-06-17 | DX-3: livemode on /v1/payments + prefix<->is_live binding invariant (refines INT-3) + no-secret doc (T3)
From the Snap DX critique. (5.2) ConfirmResponse already carried both mode (string) + livemode (boolean); the
/v1/payments PaymentApiResponse had only mode, so a webhook-side `event.livemode` boolean check had no REST
equivalent. Added a NULLABLE Boolean livemode (true IFF mode=="live"), set in ResponseMapper.toPaymentResponse(p,
mode); the legacy no-mode overload passes null so NON_NULL drops both mode+livemode (today's serialization
preserved). OpenAPI updated. (5.1) PaymentApiResponse Javadoc/@Schema + OpenAPI now state the create response
carries only the payment id, NO client_secret (browser client_secret comes from POST /v1/payment-sessions).
(3.2) REFINES INT-3: INT-3 (ADR-028) made mode SERVER-DERIVED from the is_live COLUMN (not the raw key STRING) and
treated the sk_test_/sk_live_ prefix as cosmetic. That left a residual footgun: a row whose displayed prefix
disagreed with is_live (DB corruption / a future bad code path) would authenticate and route by is_live — so a
sk_test_-DISPLAYED key could, in principle, move REAL money, violating the integrator's "sk_test_ == test" mental
model (and Snap's CHARTER). DX-3 makes the prefix a BINDING invariant: ApiKeyService.authenticate now, AFTER the
constant-time bcrypt match, verifies prefixAgreesWithLive(keyPrefix, isLive) (sk_live_⟺true, sk_test_⟺false,
null/other→reject) and on mismatch `continue`s to the single terminal invalidApiKey() throw — fail-closed, no
oracle, no timing signal (post-match). createApiKey gets a belt-and-suspenders assert (prefix+live already derive
from one arg). INT-3's CORE guarantee is PRESERVED for all consistent (i.e. all real, createApiKey-minted) keys —
mode is still the is_live column — but an inconsistent pair now fails closed instead of trusting is_live, so the
prefix is finally a trustworthy safety signal. NOT a contradiction of INT-3, a strengthening of it.
Test reconciliation (NOT a weakening): the two pre-existing security tests that intentionally FORGED an
inconsistent row (ApiKeyServicePrincipalModeTest.modeIsServerDerived_* forged sk_test_+is_live=true to prove
column-authority; SEC-22 ApiKeyServiceCollisionTest RAW_B = sk_test_ prefix + is_live=true) asserted a state the
new invariant FORBIDS. Reconciled by making the fixtures internally CONSISTENT (mode-derivation now asserts
principal.live()==entity.isLive() with a consistent sk_live_ row; collision now pairs two same-mode sk_test_/false
keys sharing the 12-char sk_test_coll prefix) — SEC-22 collision-iteration coverage + column-authority coverage
both intact, PLUS a new ApiKeyServicePrefixConsistencyTest proving the mismatch is rejected with no oracle. Net
coverage up. Adversarial review caught the conflict as 3 BLOCKERs (a documented-invariant contradiction, not stale
data) and escalated rather than silently rewriting a security test — the right call; I verified the refinement is
strictly safer before accepting. L-063. ADR-045 (refines ADR-028/INT-3). CI is the oracle.

## ADR-046 | 2026-06-17 | DX-5a: durable test/live mode for server-initiated charges (CHARTER money-safety) (T3)
Snap critique 3.1. PaymentMode is a ThreadLocal set ONLY on the authenticated request thread;
GatedPaymentGateway.routeToMock() returns real-PSP when it is UNSET on a system thread, and createPayment
has no isTestModeId fail-safe (no id yet). The @Scheduled @SystemTransactional billing jobs (RenewalScheduler,
DunningService) already call createPayment via PaymentOrchestrationAdapter.collectPayment on a system thread —
so a renewal/dunning charge for a TEST subscription would hit the REAL PSP (CHARTER violation). ACTIVELY
WIRED, not theoretical — only unexploited because no subscription renews in prod yet. Fix threads the DURABLE
mode explicitly (not a fragile scheduler ThreadLocal set/clear): V4035 adds subscriptions.is_live (DEFAULT
true — existing rows = LIVE, the safe default); Subscription.create stamps it from the creating caller's
server-derived PaymentMode (SubscriptionLifecycleService: test key => false, indeterminate => false fail-safe);
CallContext gains a NULLABLE Boolean live (existing factories/ctors default null → byte-identical routing;
new serverRecurring(tenant, live) overload); GatedPaymentGateway.routeToMock(ctxLive) consults it FIRST for
createPayment (TRUE→real, FALSE→mock, null→the unchanged ThreadLocal/request-thread heuristic + isTestModeId);
billing threads sub.isLive() down renewal + dunning. The UNSET-system-thread=real invariant is preserved for
a null ctx.live (system paths now DECLARE mode).
REVIEW (3 lenses): 1 BLOCKER + 1 SHOULD_FIX + 1 NIT. BLOCKER — my manual-pay InvoiceController.resolveLiveMode
let a TEST-key request paying a LIVE subscription's invoice reach the real PSP; fixed to compose request-mode
AND subscription-mode with TEST-WINS (either test => mock). SHOULD_FIX — a THIRD system-thread createPayment
hole: workflow PaymentActivitiesImpl (Temporal activity) also creates payments off-thread without declaring
mode; DEFERRED to DX-5a-ii (tracked) because Temporal is disabled by default (latent) and the fix needs a new
field on a Temporal-serialized DTO outside DX-5a's scope — the reviewer classified it as a pre-existing residual,
not a blocker. The two WIRED paths (renewal, dunning) + manual-pay are closed now. Migration V4035. L-064.
ADR-046.

## ADR-047 | 2026-06-17 | DX-5b: data.object.amount is authoritative for the REFUNDED amount (docs-only carve-out) (T3)
Snap critique §6. The integration docs teach one blanket rule — "do not trust the event amount; resolve the
entitlement from your own catalog keyed by your metadata" (WEBHOOKS.md §6, INTEGRATION.md §5). That rule is
correct for GRANTS (on payment.succeeded the create-time amount is client-supplied and the SKU must come from
the merchant's own pack table — GAP-13). But applied literally to REFUNDS it causes an over-claw: Snap claws
back the FULL pack's credits on a PARTIAL refund, because it ignores the event amount and debits the whole
pack from its table. Investigation (grep, not a code change): the platform already carries the right figure —
MockWebhookSynthesizer.onRefundTerminal builds the payment.refunded data.object with object.put("amount",
refund.amount()) (the partial refunded amount), and the real path (HyperSwitch refund_succeeded ->
REFUND_COMPLETED -> WebhookEnvelopeSerializer) passes content.object (incl. amount) straight into data.object,
stripping only card subtrees. So data.object.amount on a refund event IS the server-derived amount actually
refunded, on BOTH the mock and real paths. The "client-controlled, don't trust it" reasoning behind the
blanket rule does NOT hold for refunds — the platform, not the client, computed that number. Fix is therefore
DOCUMENTATION-ONLY (no platform/SDK code change): a refund carve-out in WEBHOOKS.md §6 + INTEGRATION.md §5
stating that for payment.refunded / payment.refund.created, data.object.amount is the authoritative refunded
amount (use it for partial claw-back), and a new GAP-17 row in snap-loyalty.md tracking Snap's residual fix
(scale the claw-back by event.data.object.amount, the one refund-path exception to GAP-13). Metadata still
answers WHICH account; data.object.amount answers HOW MUCH. L-065. ADR-047 (carves out the refund case from
the ADR documenting the "resolve from your catalog" guidance).

## ADR-048 | 2026-06-17 | DX-5c: API-key lifecycle (expiry + last-used + rotate-with-overlap + revoke IDOR fix) (T3)
Snap critique 3.3 (lifecycle half; per-key SCOPES deferred to DX-5c-ii to avoid reshaping the widely-used
NexusPayPrincipal record in the same batch). Migration V4036 adds three NULLABLE additive columns to
api_keys (back-compat: every existing row keeps working): expires_at (NULL=never), last_used_at, replaced_by.
ApiKeyService changes: (1) authenticate() now FAIL-CLOSED on expiry — a matched candidate with expires_at
at-or-after now `continue`s to the SAME single terminal invalidApiKey() throw as a non-match (no oracle;
identical to the SEC-22 prefix-consistency pattern); (2) a best-effort, THROTTLED (5-min), FAIL-OPEN
last_used_at stamp via a SEPARATE @Component ApiKeyUsageTracker (separate bean so the @Transactional proxy
applies; any touch failure is swallowed — observability must never deny a valid key); (3) rotateApiKey
mints a successor (same role/tenant/live, inherits the OLD key's ORIGINAL expiry) and shortens the old key
to expires_at = EARLIER(original, now+overlap) so rotation can NEVER EXTEND a key's lifetime (a security
property — a sooner-expiring key stays sooner); sets replaced_by; guards revoked/expired/already-replaced;
(4) revokeApiKey + rotateApiKey are now TENANT-SCOPED via findByIdAndTenantId — fixing a real IDOR (the old
revokeApiKey did a bare findById, so any admin could revoke ANY tenant's key by id); an other-tenant id is
indistinguishable from a missing one (no existence oracle). New admin-only POST /v1/api-keys/{id}/rotate
(default overlap 86400s, hard cap 604800s, clamped). Back-compat 4-arg createApiKey overload preserved;
9-arg ApiKey/ApiKeyEntity ctors preserved (existing fixtures compile unchanged).
REVIEW (4 lenses: auth-bypass, tenant-IDOR, back-compat, correctness) found the IDOR fix + no-oracle sound
and ONE SHOULD_FIX (raised by TWO lenses): the new service-layer state guards threw raw
IllegalStateException (rotate revoked/expired/already-replaced) and IllegalArgumentException (create past
expiry) which GlobalExceptionHandler had no handler for -> fell through to the catch-all 500, contradicting
the OpenAPI 400/409 contract. The workflow's fix agent patched it with a BLANKET
@ExceptionHandler(IllegalStateException)->409 + @ExceptionHandler(IllegalArgumentException)->400. On
diff-review I REJECTED that as too broad: a blanket advice on those two java.lang types silently re-maps the
codebase's ~100 internal Illegal* throws (53 IllegalArgument + 47 IllegalState) — including
ApiKeyService.generateKey's own "prefix/is_live mismatch" corruption guard, which is a SERVER fault that MUST
stay 500 and would have been mislabelled "409 key_not_rotatable". Replaced it with the reviewer's PREFERRED
contained option: two new typed domain exceptions in :common — ConflictException (->409) and
InvalidRequestException (->400, named to avoid the jakarta.validation.ValidationException clash) — each
mirroring ResourceNotFoundException, with type-SPECIFIC @ExceptionHandlers. Brand-new types => ZERO blast
radius; the 100 internal Illegal* throws stay 500 (locked by a new envelope test asserting raw
IllegalStateException -> 500 generic/no-leak). Messages are curated/id-free (key id logged at the throw
site, never on the wire) so the 4xx echo is safe. CI is the oracle (Gradle can't run locally). L-066. ADR-048.

## ADR-049 | 2026-06-17 | DX-5d + DX-5e: webhook-taxonomy drift guard + collapse the version identifiers (T3)
Snap critique 5.4 (event taxonomy) + 5.5 (version identifiers). Both turned out SMALLER than the critique
framed — INT-1 had already regularized the taxonomy — so this is a single hygiene batch, not a contract
rework.
DX-5d (5.4): the platform WebhookEventTaxonomy.CANONICAL (8 dotted names) and the hand-maintained TS SDK
WEBHOOK_EVENT_TYPES union (checkout-sdk/packages/node/src/types.ts) were ALREADY identical, and the
registration validator (CanonicalWebhookEvents) + WEBHOOKS.md already cover all 8 — so NO taxonomy change,
NO back-compat aliases. The only gap was DRIFT risk (the two lists live in different toolchains with nothing
binding them). Added a cross-toolchain parity TEST in :common (WebhookEventTaxonomyParityTest) that resolves
the repo root by walking up to settings.gradle.kts, reads the SDK types.ts, regex-extracts the union, and
asserts it equals CANONICAL — failing CI the moment either side drifts. (The implement-time claim of a stale
"6 types" comment was a MIS-READ: the comment says "the 6 [HyperSwitch-emitted] types plus the two
domain-emitted" = 8, which is correct — verified against source, no change made.)
DX-5e (5.5): three version-like identifiers existed; two of them — the webhook envelope api_version
("2026-06-16", WebhookEnvelopeSerializer, tested) and the X-API-Version request-header default
("2026-03-01", ApiVersionInterceptor, NO test) — DISAGREED for no reason (only one contract version exists).
Introduced ONE source of truth common/api/ApiVersion.CURRENT = "2026-06-16"; both surfaces now reference it
(the envelope value is unchanged so its tests still pass; the interceptor default changes 2026-03-01 ->
2026-06-16 with no test pinning the old value). The third identifier (SDK npm semver 0.1.x) is orthogonal
(client-library version) and stays distinct. DECISION on the critique's "honor OR delete X-API-Version": KEPT
the interceptor but made it HONEST — it remains informational single-version plumbing (parses/echoes, no
per-version transform), now documented as such (INTEGRATION.md new section 7 + class Javadoc). DELETING the
inert interceptor (it implies per-request version negotiation that does not exist, and the keep choice
preserves the documented ADR-008 surface) is the alternative; left as a reversible, low-stakes owner call
rather than a unilateral subtraction. CI is the oracle. ADR-049.

## ADR-050 | 2026-06-17 | DX-5c-ii: per-API-key SCOPES — fail-closed authorization narrowing (T3)
Snap critique 3.3 (scopes half; lifecycle was DX-5c). An API key MAY now carry a set of SCOPES that NARROW
what it can do ON TOP OF its role; a key with NO scopes (NULL/empty) is UNRESTRICTED (role-based) — the
back-compat default, so every existing key + every JWT/session principal is unchanged. Pieces: (1) ONE
vocabulary common/api/ApiScope (13 resource:action strings; parseCsv/toCanonicalCsv fail-closed on an
unknown token -> InvalidRequestException 400 code invalid_scope; null/empty csv == unrestricted). (2)
Migration V4037 adds api_keys.scopes TEXT NULL. (3) NexusPayPrincipal gains a 7th component Set<String>
scopes + hasScope and implements ScopedPrincipal (a NEW :common interface) — all 4/5/6-arg convenience
ctors delegate with scopes=null so the JWT/session paths + ~15 test sites stay byte-identical/unrestricted
(L-062). (4) A FAIL-CLOSED enforcement bean common/tenant/ScopeSecurity @Component scopeAuth read from
@PreAuthorize SpEL — placed in :common (not gateway-api) and keyed on the ScopedPrincipal contract (mirrors
CallerTenant) so marketplace/vault/dispute (which depend on :common only, not :iam) can enforce without
importing NexusPayPrincipal. No/anon principal -> false (deny); ScopedPrincipal -> hasScope; authenticated
non-ScopedPrincipal -> true (a restricted API key is ALWAYS a ScopedPrincipal, so it can never reach this
branch). (5) Per-endpoint @PreAuthorize on 8 money-critical/sensitive controllers AND-composes the EXISTING
role with @scopeAuth.has(...) (scopes narrow, never replace the role). authenticate parses the key csv
defensively (filters to known vocab, never throws on the auth path); createApiKey gained a scopes overload
(fail-closed) + 4/5-arg back-compat overloads; rotate INHERITS the rotated key scopes verbatim (never widens).
REVIEW (4 lenses) found 6 actionable. BLOCKER (x2 same cause): ApiKeyEntity.scopes mapped to varchar(255)
but V4037 is TEXT -> ddl-auto=validate would fail full-context boot in CI (L-025); FIXED with
columnDefinition TEXT (the convention all 13 other TEXT String columns use). SHOULD_FIX (auth-bypass): the
maker-checker ApprovalController.approve SETTLES a refund (moves money) under role-only auth — an admin key
scoped away from refunds could still settle one; FIXED by gating it on refunds:write (verified refund is the
only approval action type today). SHOULD_FIX: refunds:write was inert (refund-create used payments:write) ->
FIXED by switching refund create + settle to refunds:write, making the scope real and the read/write pair
meaningful. SHOULD_FIX: vault GETs required vault:write -> added vault:read. SHOULD_FIX: only PayoutController
had a runtime 403 test -> added gateway-api + vault @WebMvcTest scope-enforcement suites (+ a new
GatewayTestApplication anchor). All 13 vocabulary scopes now have at least one enforcement site (locked by
ScopeVocabularyGuardTest). dispute/build.gradle.kts gained spring-boot-starter-security (needed for
@PreAuthorize compile; mirrors marketplace/vault). CI is the oracle. L-067. ADR-050.

## ADR-051 | 2026-06-17 | DX-6: published Docker app image (Snap critique 2.4 tooling) (T3)
Snap critique 2.4 asked for a "just run it" path. Chose the published Docker image (the bigger DX win and
self-contained) over the nexuspay-listen CLI (more surface; NexusPay has no websocket relay, so a real
listen would be a poll-over-the-INT-4 delivery-list tool — left as a future option). DX-4 already made the
JDK+Gradle bootRun path low-friction; the image removes the JDK/Gradle requirement entirely. Deliverables:
(1) a multi-stage Dockerfile (eclipse-temurin:21-jdk builds :app:bootJar -x test, copying the EXECUTABLE
bootJar excluding the -plain.jar; eclipse-temurin:21-jre runtime as a NON-ROOT system user on 8090,
JAVA_OPTS-tunable). (2) .dockerignore (drops build/, .git, .gradle, node_modules, the checkout-sdk npm
workspace, docs/.perpetua — none are inputs to the Java build). (3) docs/LOCAL_DEV.md 3b "Option B: run the
app as a container" — docker run joined to the lite-stack compose network using IN-network hostnames
(kafka:9092 / nexuspay-pg:5432 / keycloak:8080), not the host-mapped ports. (4)
.github/workflows/docker-image.yml: pull_request (paths-filtered to Dockerfile/app/gradle) does a BUILD-ONLY
validation so the Dockerfile cannot rot; an app-v* tag or workflow_dispatch builds + PUSHES to
ghcr.io/<owner>/nexuspay-app (:latest, :sha, :semver) via the built-in GITHUB_TOKEN (packages: write) — NO
owner secret, mirroring the SDK release flow. Uses the runner's preinstalled Docker CLI + only the SHA-pinned
actions/checkout (avoids pinning unverified docker/* action SHAs; B-012). GHCR image name lowercased
(github.repository has uppercase N). Test-mode safety unchanged in a container (same code, sk_test_ -> mock).
CI build-validates the Dockerfile on this PR. ADR-051.

## ADR-052 | 2026-06-17 | DX-5a-ii: durable test/live mode on the Temporal payment activity (closes the last L-064 path) (T3)
Snap critique 3.1 residual, deferred from DX-5a (ADR-046). PaymentActivitiesImpl.createPayment runs on a
Temporal WORKER thread where the request-scoped PaymentMode ThreadLocal is UNSET; it called
paymentGateway.createPayment(req, CallContext.serverOther(tenantId)) with live=null, so a TEST-mode charge
routed through Temporal would fall to the GatedPaymentGateway heuristic and could reach the REAL PSP — the
4th instance of the L-064 off-request-path class (after renewal/dunning/manual-pay). Fix is small + LATENT
(Temporal is disabled by default; the payment workflow is a Sprint-2.2 scaffold with NO production trigger —
the only PaymentWorkflowRequest construction site is its unit test): (1) added a nullable Boolean live to
PaymentWorkflowRequest (the Temporal-serialized DTO), Javadoc'd that any FUTURE trigger MUST stamp it from
the server-derived PaymentMode (never client input), TRUE=live/FALSE=test/null=heuristic; (2)
PaymentActivitiesImpl now passes request.live() into the ALREADY-EXISTING CallContext.serverOther(tenantId,
live) overload (DX-5a built + reviewed serverOther(.,live) and routeToMock(ctxLive) consuming it — this only
feeds the value through); (3) tests assert the activity threads live=false (mock), live=true (real), and
null (unchanged heuristic) into the CallContext. No production behaviour change today (null on the only
caller until a trigger is wired); the path is now correct-by-construction so enabling Temporal later cannot
silently move a test charge through the real PSP. Done directly (not a workflow) — the money-routing logic
was already adversarially reviewed in DX-5a; this is a latent value-threading change with full true/false/null
test coverage. CI is the oracle. L-064 (closes its last instance). ADR-052.

## ADR-053 | 2026-06-17 | GitHub Pages landing site (docs/index.html) (T3)
Added a self-contained, dependency-free marketing/onboarding landing page at docs/index.html (+ empty
docs/.nojekyll so Pages serves it verbatim, no Jekyll build). Single file: inline CSS/JS + a hand-authored
inline SVG architecture diagram (request -> API -> gated gateway -> mock(test)/HyperSwitch(live) -> ledger
-> signed webhook -> verify). Sections: hero, features (9), architecture, get-started (tabbed, copyable
quickstart drawn VERBATIM from docs/INTEGRATION.md so the code is real), security pillars, SDK cards (link
npm), contribute (CI gates), contact (GitHub issues / profile / email for commercial-license inquiries),
footer. Deep docs link to GitHub blob views (rendered MD); API reference links the existing
docs/api/openapi.html (served by Pages). Only the SDK npm package names/versions, license (PolyForm-NC; SDKs
MIT), repo, and ghcr image are asserted — all verified against the repo. Verified rendering via the preview
tool: correct dark theme + gradient-clip text, all sections/cards/tabs/arch-SVG present, tab-switch + nav
anchors functional, zero console errors (the screenshot capturer timed out on the hero blur filters — an
environment limitation, not a page defect; confirmed via computed-style + DOM inspection instead). OWNER
ACTION to go live: repo Settings -> Pages -> Deploy from a branch -> main -> /docs. ADR-053.

## ADR-054 | 2026-06-27 | TEST-1: forced test-mode payment outcomes in the mock (critique v3 A1/A2) (T3)
Integrator critique v3 (testability) root-cause A: the in-process test-mode mock ALWAYS succeeds, so an
integrator cannot exercise decline / failure / failed-refund handling without a real declined card (their
charter forbids). Added a DETERMINISTIC, TEST-MODE-ONLY forced-outcome convention. MockPaymentGatewayPort
honors a reserved request-metadata key __test_outcome (case-insensitive) on createPayment: declined ->
card_declined, insufficient_funds -> insufficient_funds, expired_card -> expired_card, each returning
STATUS_FAILED + a populated errorCode/errorMessage; absent / "succeed" / UNKNOWN -> the byte-identical prior
success path (an unknown value must NOT fail a happy-path test). Mapping is a single-source-of-truth private
enum. Refund failure uses a documented magic-amount sentinel (amount % 100 == 66 -> refund STATUS_FAILED,
since RefundRequest carries no metadata). MockWebhookSynthesizer gained onTerminalFailure (PaymentFailed ->
payment.failed) + onRefundFailed (RefundFailed -> payment.refund.failed), reusing the existing best-effort
write(...) outbox path so the OutboxRelay->WebhookDeliveryService pipeline delivers them with ZERO new
delivery code. GatedPaymentGateway: in the doCreate MOCK branch only, an `else if (response.isFailed())`
synthesizes payment.failed; in the refund MOCK branch only, a failed refund synthesizes payment.refund.failed
else the existing payment.refunded. MONEY-SAFETY (the dedicated review lens cleared it, NIT-only): the
mechanism is reachable ONLY through the mock, which a live sk_live_ key can NEVER reach (routeToMock=false ->
HyperSwitch; arch test enforces the mock imports no HTTP client); the live branch is untouched, so a forced
FAILURE can never skip/cancel a real charge. __test_outcome is a server-reserved control key added to
WebhookMetadataService.FORBIDDEN (the same sanitize() set that strips client __livemode/PAN) so it can never
leak into the delivered data.metadata. Catalog documented in LOCAL_DEV.md + INTEGRATION.md. REVIEW (4 lenses)
1 actionable SHOULD_FIX: the end-to-end IT no-leak assertion was a TAUTOLOGY (it asserted __test_outcome
absent from a delivered envelope whose metadata fixture never contained it) -> fixed by routing the merchant
map (incl. __test_outcome) through the REAL WebhookMetadataService.record()/find() path so the strip is
genuinely exercised. No migration. CI is the oracle. L-069. ADR-054.
