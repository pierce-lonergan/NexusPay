# STATE — NexusPay

PHASE: session 0 (bootstrap + 6 shipped items) → clean stopping point
BRANCH: perpetua/bootstrap (off main @ 6281809; NOT pushed; ~10 commits ahead)
AUTONOMY: L1 (local only) — CHARTER awaiting ratification (Q-001)

BUILD: green (Gradle, JDK 21). TESTS: 234 pass / 13 skip (Docker integration), 0 fail.
COVERAGE: 17% TRUE aggregate line (JaCoCo, complete denominator); floor 16%, CI-enforced.
  (Earlier 24% was an artifact — fraud/payment-orch had no reports; corrected, Q-006.)
APP: boots through full bean wiring (fails only on missing Postgres — expected, no infra).

SHIPPED (PERPETUA loop): B-010, B-001, B-019, B-004, B-008, B-009, B-013, B-005, B-014a.
ACTIVE ITEM: none in-flight. Next: B-014 cont. (thin-module coverage) / B-007 / B-018.
BLOCKERS: Q-001 (ratify/push), Q-004 (no Docker → can't verify RLS B-002 / Flyway B-011).
OPEN money residual: B-022 (stuck-APPROVED refund recovery, pre-existing).

ENV QUIRKS (load-bearing):
- Build REQUIRES JDK 21: `JAVA_HOME=<Adoptium jdk-21>`. Machine default was 17.
- Gradle needs a working temp dir (bad TMP → loopback/NIO-pipe error); use `TMP=C:\Temp`.
- `gradlew.bat` was missing; added this session.

RECENT COMMITS (newest first):
- dd2a6e6 fix(billing): scheduler distributed locks, double-billing (B-001)
- 6e8cf67 fix(reconciliation): settlement raw_data jsonb mapping (B-010)
- 09f0e20 chore(perpetua): bootstrap autonomous improvement system
- 4a1c6ea fix: make NexusPay build, pass tests, boot; resolve audited bugs (90 files)

SHIPPED THIS SESSION: B-010 (jsonb ingest), B-001 (scheduler locks). 2 ADRs added
(006), threat model B-001→PARTIALLY CLOSED, 14 new tests.

HARNESS: perpetua-loop.sh present + syntax-checked. NOT run (would recursively spawn
claude). To run perpetually on this Windows box: WSL2 systemd or Task Scheduler →
`wsl.exe -e /mnt/.../perpetua-loop.sh --once` every 30 min (Appendix C.4).
