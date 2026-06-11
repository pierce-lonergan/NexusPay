# Handoff — 2026-06-10 — session 1 (B-011 + full integration-test bring-up)
NOW: **CI is GREEN** on branch perpetua/bootstrap / PR #1. For the first time ever the
app boots end-to-end in CI against real Postgres+Kafka+Redis (Testcontainers) and all
13 integration tests pass (263 tests total). B-011 CLOSED. CI is the standing verifier (L2).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (this session): B-011 — the one Flyway collision was the cork; clearing it surfaced
~10 latent layers, all fixed with CI-artifact ground truth: Flyway leaf-locations +
fail-on-missing (+ found the test yaml's 4-leaf list silently dropped the `app` leaf);
event_publication table; @JdbcTypeCode(JSON) ×23 jsonb; List/array→jsonb; Double type;
2 child-table RLS policies; nested Spring Data repos (@EnableJpaRepositories); Kafka
ConsumerFactory @Qualifier; set_config() not `SET …=?`; AccessDenied→403; PSP/Keycloak
health indicators disableable; Vault off in tests. L-023–L-030. (commits up to e6c2392)
STATE: CI green; 263 tests pass (250 unit + 13 integration, the latter now EXECUTING not
skipped); coverage floor 16 (CI-enforced); test floor 234→250.
WATCH OUT:
- **B-002 RLS is still NOT effective** (only made valid+booting). The tenant `set_config`
  runs at getConnection (pre-transaction, autocommit) so it's discarded, AND tests/app
  connect as the table OWNER which bypasses RLS (V2001 doesn't FORCE it). Real fix =
  set tenant INSIDE the tx (Hibernate StatementInspector / tx hook) + a non-owner app
  role + an isolation IT. RFC: research/rfc-b002. Tracked, unchanged.
- These migrations had NEVER run before, so they're effectively still "unreleased" — I
  edited them in place (safe: fresh Testcontainers DB each run, no persisted history).
- BUILD NEEDS JDK 21 + temp dir: JAVA_HOME=<Adoptium jdk-21>, TMP=C:\Temp, .\gradlew.bat.
- No Docker locally → integration tests skip here; CI (Docker) is the only place they run.
  Diagnose CI failures by downloading the `test-results` artifact and reading the JUnit
  HTML (captures Flyway log + full stack/SQL) — the Gradle console hides exception messages.
- B-022 (stuck-APPROVED refund recovery) remains an open pre-existing money residual.
- Q-006 (coverage floor 23→16 ratification), Q-002 (branch protection) still open.
BUDGET: very heavy session (~15 CI iterations, all real bug fixes). limit status: ok.
QUEUE (no Flyway/boot blockers left — pick by value):
- B-002 (RLS effectiveness — now CI-verifiable with an isolation IT; T3) ·
- B-003 (wire fraud/sanctions pre-auth gate — no DB, T3 dual-review, RFC ready) ·
- B-014 (coverage on thin modules — gateway/billing/iam were ~2-6%) ·
- B-006 remainder (semgrep SAST + triage first OSV findings) ·
- B-016/B-020 (more integration tests now that the harness works) ·
- update CHARTER L1→L2.
