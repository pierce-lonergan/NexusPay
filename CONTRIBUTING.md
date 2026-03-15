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
