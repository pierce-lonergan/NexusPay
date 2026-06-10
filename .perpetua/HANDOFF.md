# Handoff — 2026-06-10 — session 0 (bootstrap + 12 items)
NOW: PR #1 open (github.com/pierce-lonergan/NexusPay/pull/1); CI is the verifier (L2, push authorized). CI status: 250 UNIT tests PASS; 13 integration tests fail on B-011 (Flyway duplicate-version, now CI-REPRODUCED at CompositeMigrationResolver:87). TOP next item: **B-011** — now CI-verifiable; apply rfc-b011 (unique versions + de-dup base-vs-explicit locations), push, watch CI. Then B-002 RLS, B-003 gate.
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (latest turn): SubscriptionTest coverage incl. calendar date math (7bed9a0); Q-007 deleted dead A/B framework −509 LOC (db9c6d1); B-006 OSV CI + secret baseline (457ec4b); RFCs for B-002/B-011/B-003 (research/). Earlier: bootstrap, B-010, B-001, B-019, B-004, B-008, B-009, B-005, B-013, B-014a, B-018, B-014b.
STATE: build green; 250 unit tests pass / 13 Docker-skip / 0 fail; coverage ~17% (floor 16, CI-enforced).
WATCH OUT:
- L1 LOCAL ONLY — do NOT push/PR until Q-001 answered. main untouched; all work on perpetua/bootstrap (20 commits ahead).
- Q-001 (ratify/push) is now the key UNBLOCKER: pushing lets CI (Postgres/Kafka + gitleaks/OSV) run, which is the verification gate for B-002/B-011/B-016 and the first OSV triage. Q-006 (coverage floor 23→16). Q-007 RESOLVED (deleted).
- B-022 (stuck-APPROVED refund recovery) remains an open pre-existing money residual.
- BUILD NEEDS JDK 21 + temp dir: JAVA_HOME=<Adoptium jdk-21>, TMP=C:\Temp, then .\gradlew.bat <task>.
- No Docker here → 13 integration tests skip; RLS (B-002) + Flyway (B-011) still UNVERIFIABLE locally (Q-004).
- B-022 (stuck-APPROVED refund recovery) is an open pre-existing money residual from B-009.
BUDGET: heavy session (bootstrap + 6 shipped items). limit status: ok.
QUEUE: B-006 (scans) · B-014 (coverage on thin modules) · B-002 (RLS, needs DB/RFC) · B-003 (wire fraud/sanctions, RFC) · B-007 (routing A/B: wire or delete)
