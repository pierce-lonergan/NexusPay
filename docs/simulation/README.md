# Simulation, Stress & Red-Team Environment

This is the aggressive stress-test + active-attack environment for NexusPay: load
sims, concurrency/idempotency/money-invariant soak tests, adversarial-input fuzz,
and a red-team attack suite (cross-tenant IDOR, webhook replay/forgery, ledger
double-post, payout double-pay, idempotency reuse, SSRF, PAN-at-rest).

## The hard constraint it honors

`main` still has the holes the SEC-* PRs fix; those PRs are **not merged**. So any
test that asserts SECURE behavior main lacks (e.g. cross-tenant request → 404)
would FAIL on `main`. The whole design keeps the default
`./gradlew build test` gate (`ci.yml`, `perpetua-gates.yml` `build-test-ratchet`)
**green on current main** by placing work in three layers:

| Layer | Where | In default gate? | Asserts |
|------|-------|------------------|---------|
| **A. Load sims** | `gatling/src/gatling/java/...` | No (gatling module is not in `settings.gradle.kts`) | latency/throughput SLAs, no error-storm |
| **B. In-gate soak/fuzz** | per-module `src/test`, **UNTAGGED** | **Yes** | behaviors main is ALREADY correct on (exact money, dedup, parser hardening) |
| **C. Red-team attacks** | `app/src/test/.../redteam/`, `@Tag("redteam")` | **No (excluded)**, report-only | SECURE behavior main does NOT have yet → fails today |

The single gate exclusion lives in the root `build.gradle.kts`:

```kotlin
tasks.withType<Test> {
    useJUnitPlatform { excludeTags("redteam", "simulation") }
    ...
}
```

- `./gradlew build` still **compiles** the red-team classes (they are in
  `app/src/test`, compiled by the test compile task even when excluded from
  execution) → CI verifies buildability.
- `./gradlew test` **skips** `@Tag("redteam")` / `@Tag("simulation")` → main stays
  green; the secure-behavior assertions main fails never run in the gate.
- The UNTAGGED in-gate soak/fuzz run normally → they raise the ratchet
  `test_count_floor` / `coverage_floor_pct` headroom (never lower them).

`@Tag("simulation")` is a reserved co-excluded alias for heavier on-demand in-JVM
stress (e.g. very-high-iteration soaks) that we want runnable but not in the fast
gate. Nothing carries it today; the in-gate soaks are deliberately untagged.

## How to run

### B — in-gate soak/fuzz (run automatically by the gate)
```bash
./gradlew test            # runs everything UNTAGGED, including the soak/fuzz below
```
No special invocation: these are normal tests. The integration soaks self-skip
when Docker is absent (Testcontainers).

In-gate soak/fuzz inventory:
- `common` → `MoneyFuzzTest` — round-trip / overflow / cross-currency fuzz; money never silently drops, overflow throws.
- `reconciliation` → `StripeCsvParserFuzzTest` — hostile settlement CSV; no crash, every row is a record OR a `ParseFailure` (no silent money-drop).
- `payment-orchestration` → `SanctionsListParserFuzzTest` — hostile OFAC CSV; fail-closed, curated embargo baseline never emptied, no over-block.
- `app` → `LedgerBalanceInvariantSoakTest` — LOW-contention concurrent balanced captures keep `SUM(debits)==SUM(credits)` and exact balances (FIX 6: reduced threads/iterations + bounded test-level retry of the SERIALIZABLE 40001 that production's version-CAS-only loop does NOT retry, so the exact-balance oracle is deterministic and the gate is reliable). A heavy high-contention soak, if added, must be `@Tag("simulation")` (out of gate).
- `gateway-api` → `RefundDeterministicKeyIdempotencySoakTest` — concurrent same-approval refunds dedup to exactly one effect.
- `app` → `HyperSwitchWebhookGuardTest` — unsigned webhook → 401; duplicate `event_id` writes no second outbox row.

### C — red-team attack suite (report-only)
```bash
./gradlew :app:redteamTest    # runs ONLY @Tag("redteam")/@Tag("simulation"); needs Docker
```
`redteamTest` is the inverse of the gate (registered in `app/build.gradle.kts`),
reusing the same compiled `test` source set (no separate source set, no duplicated
`IntegrationTestBase` / `TestSecurityConfig`). It is expected to FAIL on current
main — that is the point; it is report-only until the SEC fixes land. Without
Docker the Testcontainers attacks self-skip (report all-skipped, not all-fail).

> ⚠ **Gotcha (FIX 1): the root `subprojects` exclude leaks into `redteamTest`.**
> `tasks.withType<Test>` in the root `build.gradle.kts` matches `redteamTest` too
> and mutates the SAME `JUnitPlatformOptions`, so its `excludeTags("redteam",
> "simulation")` is applied first. Because include/exclude accumulate independently
> and JUnit's EXCLUDE wins when a tag is in both sets, a naive
> `useJUnitPlatform { includeTags(...) }` selects **ZERO** tests. The task block
> must therefore RESET the inherited excludes before including:
> ```kotlin
> useJUnitPlatform {
>     excludeTags = emptySet()                 // drop the inherited gate excludes
>     includeTags = setOf("redteam", "simulation")
> }
> ```
> Verify with `./gradlew :app:redteamTest --info | grep -i "include\|exclude"`:
> includes redteam/simulation, excludes nothing.

Red-team inventory (`app/src/test/java/io/nexuspay/app/redteam/`):

IN-GATE (flipped — fix landed; now a permanent regression guard in the default `test` task):
- `PanPersistenceRedteamTest` (B-004 / "SEC-04") — full PAN must not be recoverable from **`payment_tokens.token_data`** after the gateway SDK **tokenize** path. SEC-BATCH-3 landed the fix (`TokenizationService` AES-256-GCM-encrypts `token_data` via the `:common` `EncryptionPort` and sets `encryption_key_id`; V4027 purges legacy null-key base64-PAN rows), so the three doesNotContain(PAN/base64(PAN)/decoded) checks pass and a `encryption_key_id IS NOT NULL` hardening assertion was added. De-tagged from `@Tag("redteam")` into the gate.

ACTIVE (genuinely FAIL on current main; PASS once the named fix lands):
- `CrossTenantIdorRedteamTest` (B-002/B-005/B-006) — X-Tenant-Id IDOR on vault/payout/virtual-card. **Seeds a real victim-owned resource and asserts attacker→404 WHILE a same-tenant control read→200**, so a not-found→404 fix alone cannot green it; only a real ownership check does.
- `DisputeWebhookAuthReplayRedteamTest` (B-001) — unsigned dispute webhook + replay (no double chargeback reserve).
- `LedgerDoublePostRedeliveryRedteamTest` (B-010) — same capture delivered twice → journal count must stay 1.
- `PayoutDoublePayRedteamTest` — concurrent identical payouts → exactly one payout.
- `OutboundWebhookSsrfRedteamTest` — internal/link-local webhook target must be refused.

@Disabled (cannot be made fail-on-main in THIS harness without a stub PSP — see reason strings; do NOT replace with a vacuous assertion):
- `IdempotencyReuseRedteamTest` (B-012) — two keyless identical payments must not double-charge. Needs a stub PSP minting DISTINCT payment ids per call to demonstrate the double-charge; the app harness has no WireMock PSP, so both calls fail identically downstream (422) → indistinguishable on vulnerable vs fixed. `@Disabled(B-012/SEC-BATCH-5)`.
- `PaymentLifecycleIdorRedteamTest` (B-007) — cross-tenant get/capture/cancel/sub-threshold-refund. The payment lifecycle is PSP-backed (no local payments table to seed a victim payment into); needs a stub PSP to mint+return a victim-owned payment so the cross-tenant read is an observable 200 leak on main. `@Disabled(B-007/SEC-BATCH-3)`.

REMOVED:
- `HyperSwitchWebhookOrderingRedteamTest` (B-015) — DELETED. It sent an UNSIGNED webhook (→ 401 before reaching dedup, since the test profile sets a webhook-secret) and asserted a meaningless `status != 208`. The narrated "downstream processing rolls back, redelivery dropped" scenario does NOT occur in `HyperSwitchWebhookController`: that handler only writes `inbound_webhooks` + `event_outbox` and returns 200 — it does no synchronous downstream booking to roll back (booking is async via OutboxRelay→Kafka→ledger). The real B-015 pre-commit-dedup-vs-rollback ordering bug is a fault-injection scenario not exercisable black-box over MockMvc, and replay/auth are already covered by `HyperSwitchWebhookGuardTest` (unsigned→401, duplicate→no second outbox row) and `DisputeWebhookAuthReplayRedteamTest`. B-015 therefore has no red-team test for now (tracked in AUDITS.md); when the fix lands, add a fault-injecting integration test rather than a black-box webhook POST.

### A — load simulations (against a RUNNING app)
The gatling module is standalone (`gatling/build.gradle.kts`, `io.gatling.gradle`).
Start the app (default `http://localhost:8090`) then:
```bash
# compile only (what CI does — verifies buildability, no load):
./gradlew gatlingClasses -p gatling

# run a simulation against a live app:
./gradlew gatlingRun -p gatling -Dgatling.simulationClass=io.nexuspay.gatling.PaymentBurstSimulation
# optional: -DbaseUrl=http://host:port -DapiKey=sk_... -DspikeUsers=200
```
> Note the `-p gatling`: the module is NOT in `settings.gradle.kts`, so a root
> `./gradlew gatlingClasses` will not find the task.

Load sim inventory (`gatling/src/gatling/java/io/nexuspay/gatling/`):
- `PaymentLoadSimulation` (pre-existing baseline) — ramp + sustained payment + dashboard flow.
- `PaymentBurstSimulation` — `atOnceUsers(200)` spike onto POST /v1/payments; bounded tail, no 5xx storm.
- `RefundIdempotencyStormSimulation` — same Idempotency-Key replayed under load (dedup holds, no error-storm).
- `DashboardReadStormSimulation` — high-rate read storm (ledger/journal/approvals) + connection pool.
- `MixedConcurrencySimulation` — interleaved payments + refunds + reads to surface lock/pool contention.

Load sims assert latency/throughput SLAs only — **money-exactness is NOT asserted
over black-box HTTP**; that is the job of the in-gate JVM soaks (layer B).

## CI

A new **report-only** job `redteam-sim` was added to
`.github/workflows/perpetua-gates.yml` (mirrors the §15.3 OSV report-only
precedent: `continue-on-error: true` + `|| echo "::warning::..."`). It:
1. runs `./gradlew redteamTest` (report-only — failures surface as warnings, never block),
2. compiles the gatling sims with `./gradlew gatlingClasses -p gatling` (buildability check; no load run in CI),
3. uploads `**/build/reports/tests/`.

It does **not** touch `.perpetua/ratchets.json`, so it cannot block via the
perpetua ratchet job either. `ubuntu-latest` has Docker, so the Testcontainers
attacks actually exercise on that runner.

## Flip-to-gating plan

As each SEC-* PR lands the secure behavior on `main`, that attack's assertion
starts PASSING → flip it from report-only into the gate. **Two coordinated moves
per flip, in the SAME SEC-* PR (fix merged first, un-tag second):**

1. **Drop `@Tag("redteam")`** on the now-green class (or move it to the UNTAGGED
   in-gate set) so the default `test` task runs it.
2. **Raise `test_count_floor`** in `.perpetua/ratchets.json` in the same PR (the
   floor only ratchets up — un-tagging moves a test from excluded→executed) and
   add a `CHANGELOG.md` entry.
3. Once **all** classes are flipped, replace the `|| echo "::warning"` swallow in
   the `redteam-sim` job with the SAST exit-code-branch pattern
   (`set +e; ./gradlew redteamTest; rc=$?; set -e; if [ "$rc" -ne 0 ]; then exit 1; fi`)
   and set `continue-on-error: false`.

Per-PR mapping:

| SEC fix | Un-tag / enable | Status |
|--------|--------|--------|
| B-002/B-005/B-006 (X-Tenant-Id derived from principal; by-id loads assert ownership) | `CrossTenantIdorRedteamTest` | ACTIVE (fail-on-main) |
| B-007 (payment-by-id tenant scoping) | `PaymentLifecycleIdorRedteamTest` | `@Disabled` — needs stub PSP first (SEC-BATCH-3) |
| B-001 (HMAC + idempotency on dispute webhook) | `DisputeWebhookAuthReplayRedteamTest` | ACTIVE (fail-on-main) |
| B-015 (move HyperSwitch dedup post-commit) | _(no red-team test — see REMOVED above)_ | needs a fault-injection IT, not black-box |
| B-010 (DB UNIQUE behind the ledger idempotency check) | `LedgerDoublePostRedeliveryRedteamTest` | ACTIVE (fail-on-main) |
| Payout lock/idempotency | `PayoutDoublePayRedteamTest` | ACTIVE (fail-on-main) |
| B-012 (mandatory/server-derived idempotency key) | `IdempotencyReuseRedteamTest` | `@Disabled` — needs stub PSP first (SEC-BATCH-5) |
| SSRF egress filter | `OutboundWebhookSsrfRedteamTest` | ACTIVE (fail-on-main) |
| B-004 / "SEC-04" (encrypt SDK tokenize `token_data` via the `EncryptionPort`; set `encryption_key_id`; purge legacy null-key rows via V4027) | `PanPersistenceRedteamTest` (targets `payment_tokens.token_data`, NOT `vaulted_cards`) | **FLIPPED INTO GATE** (SEC-BATCH-3): `@Tag("redteam")` removed + `encryption_key_id IS NOT NULL` hardening added |

> For the two `@Disabled` rows: the fix-merged-first / un-tag-second rule is unchanged,
> but FIRST the app harness must gain a stub PSP (WireMock) so these can be made genuine
> fail-on-main tests. Enabling them BEFORE that would only restore a vacuous green. The
> precise TODO is in each class's `@Disabled` reason.

## Open risks / gotchas

1. **Docker on the report-only runner.** `IntegrationTestBase` self-skips without
   Docker (`assumeTrue(DOCKER_AVAILABLE)`). `ubuntu-latest` has Docker, so the
   singleton PG/Kafka/Valkey start and the attacks actually run. On a Docker-less
   runner the suite would report all-skipped (false comfort) — verify Docker is
   present before trusting a green report.
2. **RLS app-datasource OWNER gap.** Even after the X-Tenant-Id fix, the app's main
   datasource authenticates as the table OWNER → RLS is bypassed (see
   `RlsIsolationIntegrationTest`). So `CrossTenantIdorRedteamTest` asserts at the
   HTTP/app layer (ownership check), NOT via RLS; the DB-level RLS proof stays in
   the separate in-gate `RlsIsolationIntegrationTest`. Don't conflate the two or a
   flip may falsely go green.
3. **Floor accounting on flip.** Un-tagging RAISES the executed count (floor rises,
   safe). But un-tagging WITHOUT the SEC fix merged would red the gate — enforce
   "fix merged first, un-tag second".
4. **Gatling needs `-p gatling`.** The module is absent from `settings.gradle.kts`.
5. **B-010 harness invokes the consumer directly** (`onPaymentEvent` twice), not
   via the broker — deterministic, no Kafka timing flake.
6. **No property engine in the gate.** In-gate fuzz is deterministic
   `@ParameterizedTest` tables (jqwik is not wired per `ratchets.json`
   `tooling.property`); do NOT add a property-engine dependency to the gate here.
7. **`authFor(tenant, role)` is test-only** (`TestSecurityConfig` is
   `@TestConfiguration`); it is never referenced from any main source set, so it
   cannot leak into prod or the SAST scan (which excludes `**/test/**`).
8. **`changelog gate`:** this repo has no `docs/SYSTEM_MAP/changelog.md` pre-commit
   gate (that lives in a different project). The top-level `CHANGELOG.md`
   (Keep-a-Changelog) carries this batch's entry instead.
