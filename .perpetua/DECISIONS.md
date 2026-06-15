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
