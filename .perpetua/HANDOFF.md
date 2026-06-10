# Handoff — 2026-06-10 — session 0 (bootstrap + 2 iterations)
NOW: session complete & checkpointed. Next session: SELECT B-004 (secrets fail-fast, T2) or B-006 (baseline scans).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SHUTDOWN
DONE THIS SESSION: 4a1c6ea make-it-build (90 files); 09f0e20 PERPETUA bootstrap; B-010 settlement jsonb (6e8cf67); B-001 scheduler locks (dd2a6e6). 14 new tests; all green.
WATCH OUT:
- L1 LOCAL ONLY — do NOT push/PR until Q-001 answered. main untouched; all work on perpetua/bootstrap.
- BUILD NEEDS JDK 21 + working temp dir: JAVA_HOME=<Adoptium jdk-21>, TMP=C:\Temp, then .\gradlew.bat <task>.
- No Docker here → 13 integration tests skip; RLS (B-002) + Flyway (B-011) UNVERIFIABLE locally (Q-004).
- coverage/mutation UNMEASURED — ratchet floors are placeholders, not achievements (B-005).
- Don't re-run perpetua-loop.sh from inside a session (it spawns claude -p). Schedule it via WSL2/Task Scheduler.
BUDGET: bootstrap + 2 iters (≈ session cap). limit status: ok.
QUEUE: B-004 (secrets fail-fast) · B-006 (baseline gitleaks/OSV/semgrep) · B-005 (wire JaCoCo) · B-008 (recon PARTIAL)
