# Handoff — 2026-06-10 — session 0 (bootstrap + iters)
NOW: B-010 shipped; next §4 item B-001 (billing scheduler distributed locks, T3).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
DONE THIS SESSION: committed audit-fix body (4a1c6ea); bootstrapped .perpetua/+CLAUDE.md+CI+harness (09f0e20); shipped B-010 (settlement jsonb, +4 tests, review SHIP).
WATCH OUT:
- L1 LOCAL ONLY — do NOT push or open PRs until Q-001 answered.
- BUILD NEEDS JDK 21 + working temp dir: set JAVA_HOME to Adoptium jdk-21 and TMP=C:\Temp, then gradlew.bat.
- No Docker here → integration tests skip; RLS (B-002) + Flyway (B-011) can't be verified locally (Q-004).
- Coverage/mutation UNMEASURED — ratchet floors are placeholders (B-005), don't treat as achievements.
BUDGET: bootstrap-heavy session | limit status ok
QUEUE: B-010 (jsonb ingest, T2, cheapest) · B-001 (scheduler locks, T3, money) · B-004 (secrets fail-fast)
