# RFC B-011 — Flyway cross-module version collisions (CONFIRMED root cause + fix)

CONFIDENCE: high on root cause; fix needs a Postgres run (Testcontainers/CI) to verify.

## Confirmed architecture (read, not guessed)
- ONE Flyway instance: Spring Boot auto-config, base `spring.flyway.locations:
  classpath:db/migration` (app/application.yml), one `flyway_schema_history`.
- Each module has a `FlywayConfigurationCustomizer` (e.g. LedgerFlywayConfig) that
  APPENDS `classpath:db/migration/<module>` to that single configuration.
- Migration files live in `db/migration/<module>/V*.sql`; top-level ones
  (V2001–V2005, V3013–V3014) live directly in `db/migration/`.

## Two defects
1. **Version collisions (definite):** bare versions repeat across modules —
   `V1` in gateway/iam/ledger/payment (×4), `V2` in iam/ledger (×2). One shared
   history + one version sequence ⇒ Flyway aborts: "more than one migration with
   version 1". Later modules already dodge this with unique high ranges
   (fraud V30xx, b2b V40xx, …) — the early modules predate the convention.
2. **Location double-discovery (suspected, verify in CI):** Flyway scans a
   location AND its subdirectories. Base `classpath:db/migration` therefore also
   finds `db/migration/<module>/*.sql`, which the explicit per-module locations
   find AGAIN ⇒ each module migration resolved twice ⇒ duplicate-version abort,
   independent of (1). (Consistent with "app never booted against a DB".)

## Recommended fix (single coherent change)
- **De-duplicate discovery:** DELETE the per-module `*FlywayConfigurationCustomizer`
  classes (subtraction) and rely solely on the base `classpath:db/migration`
  recursive scan, which already sees every module's subdir exactly once.
  (Alternative if base recursion proves not to recurse in this Flyway version:
  keep the customizers and set base location to a non-recursive marker dir.)
- **Globally-unique versions:** renumber the 7 early files into an unused band,
  preserving order < V2001 (so base tables exist before the V2001 RLS migration):
  ledger V1→V1001, V2→V1002 · iam V1→V1101, V2→V1102, V3→V1103 ·
  gateway V1→V1201 · payment V1→V1301. (Versions reference tables, not numbers;
  ordering within/across modules is preserved.)

## Verification (the gate that makes this safe to land)
Add `app/src/test/.../FlywayMigrationIT` (Testcontainers Postgres): start PG, run
`Flyway.migrate()` with the production location config, assert success and that
`flyway_schema_history` has the expected count with no failures. Self-skips
without Docker; runs in CI. This catches BOTH defects.

## Why not blind-applied now
No Postgres here to run Flyway; the discovery-dedup half (delete customizers vs.
adjust base) can't be safely chosen by inspection alone, and a wrong choice makes
module migrations vanish (empty schema) or still collide. Renumbering alone is
necessary-but-maybe-insufficient. Execute the whole fix in one Docker/CI-verified
pass. Caveat: assumes NO environment has these migrations applied (true — the app
can't boot today); if a dev DB has them, `flyway repair` / reset first.
