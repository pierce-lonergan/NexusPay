# RESEARCH INDEX (one line per note; expire stale, fold durable facts into DECISIONS)

- [ccusage-blocks-interface](ccusage-blocks-interface.md) — ccusage v20 `blocks --json`
  shape + the `--token-limit max`+`--active` no-op gotcha. HIGH, expires ~2026-09. → B-019.
- [rfc-b011-flyway-collisions](rfc-b011-flyway-collisions.md) — confirmed version
  collisions + suspected location double-scan; fix + FlywayMigrationIT. → B-011.
- [rfc-b002-rls-runtime](rfc-b002-rls-runtime.md) — RLS inert (SET LOCAL pre-tx +
  owner role); set_config(...,true) in-tx + non-owner role + RlsIsolationIT. → B-002.
- [rfc-b003-gate-fraud-sanctions](rfc-b003-gate-fraud-sanctions.md) — fraud/sanctions
  built but ungated; synchronous pre-auth gate + modulith boundary options. → B-003.
