# Handoff — 2026-06-10 — session 0 (bootstrap + 10 items)
NOW: clean stopping point. Next §4 item: B-014 cont. (coverage on gateway-api 2%/billing 4%/common 7%) or B-006 (baseline scans — needs tools/CI) or process B-002/B-003 RFCs.
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (latest turn): B-018 OutboxRelay atomic lock release (4fdf2b2); B-014b ledger L-001/L-003 regression tests (d9c64ba); B-007 A/B stats locked + wire/delete escalated to Q-007 (b5e6a39). Earlier: build-fix, bootstrap, B-010, B-001, B-019, B-004, B-008, B-009, B-005, B-013, B-014a.
STATE: build green; 245 unit tests pass / 13 Docker-skip / 0 fail; coverage ~17% (floor 16, CI-enforced).
WATCH OUT:
- L1 LOCAL ONLY — do NOT push/PR until Q-001 answered. main untouched; all work on perpetua/bootstrap (16 commits ahead).
- Q-006 BLOCKING: coverage floor corrected 23→16 — awaiting ratification. Q-007: A/B routing wire-vs-delete (product decision).
- B-022 (stuck-APPROVED refund recovery) remains an open pre-existing money residual.
- BUILD NEEDS JDK 21 + temp dir: JAVA_HOME=<Adoptium jdk-21>, TMP=C:\Temp, then .\gradlew.bat <task>.
- No Docker here → 13 integration tests skip; RLS (B-002) + Flyway (B-011) still UNVERIFIABLE locally (Q-004).
- B-022 (stuck-APPROVED refund recovery) is an open pre-existing money residual from B-009.
BUDGET: heavy session (bootstrap + 6 shipped items). limit status: ok.
QUEUE: B-006 (scans) · B-014 (coverage on thin modules) · B-002 (RLS, needs DB/RFC) · B-003 (wire fraud/sanctions, RFC) · B-007 (routing A/B: wire or delete)
