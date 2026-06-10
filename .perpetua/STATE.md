# STATE — NexusPay

PHASE: bootstrap → running first improvement iterations
BRANCH: perpetua/bootstrap (off main @ 6281809; not pushed)
AUTONOMY: L1 (local only) — CHARTER awaiting ratification (Q-001)

BUILD: green (Gradle, JDK 21). TESTS: 201 pass / 13 skip (Docker integration).
APP: boots through full bean wiring (fails only on missing Postgres — expected).

ACTIVE ITEM: bootstrap complete; selecting first §4 item.
BLOCKERS: Q-001 (ratify/push), Q-004 (no Docker → can't verify RLS/Flyway items).

ENV QUIRKS (load-bearing):
- Build REQUIRES JDK 21: `JAVA_HOME=<Adoptium jdk-21>`. The machine default was 17.
- Gradle needs a working temp dir (a bad TMP triggered a loopback/NIO-pipe error);
  building with `TMP=C:\Temp` worked.
- `gradlew.bat` was missing; it was added this session.

RECENT COMMITS:
- 4a1c6ea fix: make NexusPay build, pass tests, boot; resolve audited bugs (90 files)

NEXT: improvement iterations on B-010 / B-001 (see BACKLOG, HANDOFF).
