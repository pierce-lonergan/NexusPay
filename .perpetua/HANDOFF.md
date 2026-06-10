# Handoff — 2026-06-10 — session 0 (bootstrap + 6 items)
NOW: clean stopping point. Next §4 item: B-006 (baseline security scans: gitleaks/OSV/semgrep) or B-014 (raise coverage on gateway-api/billing/common — now measured).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE (this turn): B-004 secrets fail-fast (54334b5); B-008 recon PARTIAL (a8f565d); B-009 refund execute-once+idempotency (438423e); B-005 JaCoCo + B-013 build docs (10df9fa). Earlier: build-fix, PERPETUA bootstrap, B-010, B-001, B-019.
STATE: build green; 223 unit tests pass / 13 Docker-skip / 0 fail; coverage 24% (floor 23, CI-enforced).
WATCH OUT:
- L1 LOCAL ONLY — do NOT push/PR until Q-001 answered. main untouched; all work on perpetua/bootstrap (10 commits ahead).
- BUILD NEEDS JDK 21 + temp dir: JAVA_HOME=<Adoptium jdk-21>, TMP=C:\Temp, then .\gradlew.bat <task>.
- No Docker here → 13 integration tests skip; RLS (B-002) + Flyway (B-011) still UNVERIFIABLE locally (Q-004).
- B-022 (stuck-APPROVED refund recovery) is an open pre-existing money residual from B-009.
BUDGET: heavy session (bootstrap + 6 shipped items). limit status: ok.
QUEUE: B-006 (scans) · B-014 (coverage on thin modules) · B-002 (RLS, needs DB/RFC) · B-003 (wire fraud/sanctions, RFC) · B-007 (routing A/B: wire or delete)
