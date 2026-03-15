# ADR-004: Spring Modulith with Hexagonal Architecture

**Status**: Accepted
**Date**: 2026-03-01
**Deciders**: Architecture team

## Context

NexusPay has 8 bounded contexts (payment orchestration, ledger, IAM, etc.) that need clear boundaries but share a single deployment unit. We must decide between:

1. **Microservices** — separate deployable services per bounded context
2. **Modular monolith** — single deployment with enforced module boundaries
3. **Traditional monolith** — single deployment without formal boundaries

## Decision

Use **Spring Modulith** (modular monolith) with **hexagonal architecture** (ports and adapters) inside each module. Gradle multi-module project enforces compile-time dependency boundaries.

## Rationale

**Why modular monolith over microservices:**
- Phase 1 team is small — microservice operational overhead (service mesh, distributed tracing, per-service CI/CD) is premature
- Single database simplifies transactions (SERIALIZABLE for ledger, outbox pattern for events)
- Easier debugging and local development
- Can extract to microservices later if needed (module boundaries are the seam)

**Why Spring Modulith specifically:**
- `ApplicationModules.of(...).verify()` enforces dependency rules at test time
- Module events provide in-process pub/sub as a stepping stone to Kafka
- Documentation generation for module relationships
- First-class Spring Boot integration

**Why hexagonal inside each module:**
- Domain logic has zero framework dependencies (pure Java)
- Easy to test: mock ports, no Spring context needed for unit tests
- `PaymentGatewayPort` abstracts HyperSwitch — could swap to another PSP without touching domain
- Clear adapter boundaries make it obvious where framework code lives

## Consequences

**Positive:**
- Compile-time enforcement of module boundaries (not just convention)
- Single deployment simplifies ops in Phase 1
- Hexagonal ports make external dependencies swappable
- Natural microservice extraction path if scale demands it

**Negative:**
- All modules share one JVM — a memory leak in one module affects all
- Database schema is shared (mitigated: module-prefixed Flyway migrations)
- Deployment is all-or-nothing (can't deploy ledger without payment module)

## Module Boundary Rules

Enforced by `ModulithVerificationTest`:

```
Module               Allowed Dependencies
─────────────────────────────────────────
common               (none — shared kernel)
gateway-api          common, payment, ledger, iam
payment-orchestration common
ledger               common
iam                  common
reconciliation       common, ledger
observability        common
dispute              common, payment
workflow             common, payment
```

Cross-module communication: direct method calls for synchronous operations, Kafka events for async.
