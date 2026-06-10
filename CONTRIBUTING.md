# Contributing to NexusPay

## Prerequisites

- JDK 21+
- Docker & Docker Compose
- Gradle (wrapper included)

## Project Structure

```
nexuspay/
├── common/                 Shared domain objects, exceptions, events
├── gateway-api/            REST API, rate limiting, idempotency
├── payment-orchestration/  HyperSwitch integration, outbox relay
├── ledger/                 Double-entry ledger
├── iam/                    Authentication, authorization, audit
├── app/                    Spring Boot main class, config
├── docker/                 Docker Compose + config files
├── nexuspay-helm/          Helm chart
├── gatling/                Load tests
└── docs/                   Architecture docs, ADRs, diagrams
```

## Development Workflow

1. Start infrastructure: `docker compose -f docker/docker-compose.yml up -d`
2. Build: `./gradlew build`
3. Run: `./gradlew bootRun`
4. Test: `./gradlew test`

## Build requirements & troubleshooting

The build needs a real **JDK 21** toolchain and a working temp directory. The
gotchas below have each cost someone an afternoon:

- **`JAVA_HOME` must point at JDK 21**, not just "21+ installed". If it points
  at JDK 17 the build fails with `invalid source release: 21`. Check with
  `java -version` AND `echo $JAVA_HOME` — they can disagree.
  - macOS/Linux: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` (or your distro's path).
  - Windows (PowerShell): `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot'`.
- **Windows uses `gradlew.bat`** (`.\gradlew.bat build`), not `./gradlew`.
- **A broken/locked temp dir breaks Gradle** with a confusing
  `java.io.IOException: Unable to establish loopback connection` (Gradle's
  worker uses an NIO loopback pipe). If you hit it, point the JVM temp dir at a
  writable path, e.g. Windows `set TMP=C:\Temp & set TEMP=C:\Temp` before building.
- **Integration tests need Docker** (Testcontainers: Postgres, Kafka, Valkey).
  They self-skip when no Docker daemon is available, so `./gradlew test` stays
  green on a machine without Docker — but you then haven't exercised the
  RLS/migration/boot paths. Run them with Docker up for full coverage.
- **Run one module's tests** with `./gradlew :billing:test` (faster inner loop).
- **Coverage:** `./gradlew test jacocoTestReport` writes a per-module report to
  `<module>/build/reports/jacoco/test/html/index.html` (XML alongside). CI sums
  the module XMLs and fails if aggregate line coverage drops below the ratchet
  floor in `.perpetua/ratchets.json` (`coverage_floor_pct`).

## Module Conventions

Each module follows hexagonal architecture:

```
module/src/main/java/io/nexuspay/{module}/
├── domain/       Pure Java domain objects (no framework deps)
├── application/  Use cases, port interfaces
├── adapter/
│   ├── in/       REST controllers, event listeners, filters
│   └── out/      Database repos, external API clients
└── config/       Spring @Configuration classes
```

**Rules:**
- Domain objects must have zero Spring/JPA annotations
- Cross-module dependencies must go through the `common` module
- Every module needs a `package-info.java` declaring `@ApplicationModule`
- Spring Modulith verification test enforces boundaries

## Adding a New Module

1. Create the module directory with hexagonal package structure
2. Add `package-info.java` with `@ApplicationModule(allowedDependencies = {"common"})`
3. Add to `settings.gradle.kts`
4. Add Flyway migration path to `application.yml`
5. Run `./gradlew test` to verify modulith boundaries

## Naming Conventions

- IDs: type-prefixed UUIDs (`pi_`, `ch_`, `ref_`, `la_`, `je_`, etc.)
- API keys: `sk_test_` / `sk_live_` prefix
- Kafka topics: `nexuspay.{domain}` (e.g., `nexuspay.payments`)
- Events: PascalCase (`PaymentCaptured`, `RefundCompleted`)
- DB tables: snake_case, module-prefixed migrations

## Code Standards

- Java 21 features encouraged (records, pattern matching, sealed classes)
- No Lombok — use records for immutable data
- Amounts always in minor units (cents), stored as `BIGINT`
- All financial operations use `Money` value object from `common`
- Ledger sign convention: positive = debit, negative = credit

## Submitting Changes

1. Create a feature branch from `main`
2. Make changes following module conventions
3. Ensure `./gradlew build test` passes
4. Submit a PR with description of changes
