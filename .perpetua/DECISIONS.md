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
