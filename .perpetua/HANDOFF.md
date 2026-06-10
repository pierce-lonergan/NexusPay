# Handoff — 2026-06-10 — session 0 (bootstrap + 7 items)
NOW: clean stopping point. Next §4 item: B-014 cont. (coverage on gateway-api 2%/billing 4%/common 7%) or B-007 (routing A/B: wire or delete) or B-018 (OutboxRelay atomic release).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (latest turn): B-014a FX currency-exponent convert fix across 3 sites + first fraud/payment-orch tests (0efcbc2). Earlier turns: build-fix, PERPETUA bootstrap, B-010, B-001, B-019, B-004, B-008, B-009, B-005, B-013.
STATE: build green; 234 unit tests pass / 13 Docker-skip / 0 fail; coverage 17% TRUE aggregate (floor 16, CI-enforced; was mis-read as 24% on an incomplete denominator — see Q-006).
WATCH OUT:
- L1 LOCAL ONLY — do NOT push/PR until Q-001 answered. main untouched; all work on perpetua/bootstrap (~13 commits ahead).
- Q-006 BLOCKING: coverage floor corrected 23→16 (the 24% was an incomplete-denominator artifact) — awaiting ratification.
- BUILD NEEDS JDK 21 + temp dir: JAVA_HOME=<Adoptium jdk-21>, TMP=C:\Temp, then .\gradlew.bat <task>.
- No Docker here → 13 integration tests skip; RLS (B-002) + Flyway (B-011) still UNVERIFIABLE locally (Q-004).
- B-022 (stuck-APPROVED refund recovery) is an open pre-existing money residual from B-009.
BUDGET: heavy session (bootstrap + 6 shipped items). limit status: ok.
QUEUE: B-006 (scans) · B-014 (coverage on thin modules) · B-002 (RLS, needs DB/RFC) · B-003 (wire fraud/sanctions, RFC) · B-007 (routing A/B: wire or delete)
