# STATE — NexusPay

PHASE: session 0 (bootstrap + 2 iterations) COMPLETE → clean shutdown
BRANCH: perpetua/bootstrap (off main @ 6281809; NOT pushed)
AUTONOMY: L1 (local only) — CHARTER awaiting ratification (Q-001)

BUILD: green (Gradle, JDK 21). TESTS: 202 pass / 13 skip (Docker integration), 0 fail.
APP: boots through full bean wiring (fails only on missing Postgres — expected, no infra).

ACTIVE ITEM: none in-flight. Next: B-004 (secrets fail-fast) or B-006 (baseline scans).
BLOCKERS: Q-001 (ratify/push), Q-004 (no Docker → can't verify RLS B-002 / Flyway B-011).

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
