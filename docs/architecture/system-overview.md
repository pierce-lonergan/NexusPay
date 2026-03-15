# NexusPay System Architecture Overview

## 1. System Purpose

NexusPay is an enterprise payment platform built **on top of** HyperSwitch (open-source payment orchestrator by Juspay). NexusPay provides the enterprise operations layer that transforms HyperSwitch from a developer tool into a CFO-ready payment platform: SSO, approval workflows, double-entry ledger, reconciliation, observability, and compliance automation.

## 2. Architecture Style

**Spring Modulith + Hexagonal (Ports & Adapters)** inside a Gradle multi-module monorepo.

```
┌──────────────────────────────────────────────────────────────────────┐
│                   NexusPay Application (Java 21 / Spring Boot 3.2)   │
│                                                                      │
│  ┌──────────┐ ┌──────────────┐ ┌────────┐ ┌─────┐ ┌─────────────┐  │
│  │ Gateway  │ │  Payment     │ │ Ledger │ │ IAM │ │ Reconcil-   │  │
│  │   API    │ │ Orchestration│ │        │ │     │ │   iation    │  │
│  └────┬─────┘ └──────┬───────┘ └───┬────┘ └──┬──┘ └─────────────┘  │
│       │              │             │         │                       │
│  ┌────┴──────────────┴─────────────┴─────────┴───────────────────┐  │
│  │              Kafka Event Bus (KRaft) + Outbox Pattern          │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌───────────┐  ┌────────────┐  ┌──────────┐  ┌─────────────────┐  │
│  │Observabil-│  │  Dispute   │  │ Workflow │  │     Common      │  │
│  │    ity    │  │            │  │          │  │ (shared kernel) │  │
│  └───────────┘  └────────────┘  └──────────┘  └─────────────────┘  │
└────────────────────────┬─────────────────────────────────────────────┘
                         │ REST API (with circuit breaker)
┌────────────────────────┴─────────────────────────────────────────────┐
│                HyperSwitch (Rust, PCI DSS v4.0)                      │
│          50+ PSP Connectors │ Card Vault │ Smart Routing             │
│                                                                      │
│   ┌────────────────┐  ┌──────────────────┐  ┌───────────────────┐   │
│   │ hyperswitch-   │  │  hyperswitch-    │  │  hyperswitch-     │   │
│   │    router      │  │    consumer      │  │      pg           │   │
│   │  (API, 8080)   │  │   (drainer)      │  │  (postgres:16)    │   │
│   └────────────────┘  └──────────────────┘  └───────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

## 3. Module Inventory

| Module | Package Root | Purpose | Phase |
|--------|-------------|---------|-------|
| `common` | `io.nexuspay.common` | Shared kernel: exceptions, value objects (`Money`, `PrefixedId`), API error structure | 1.1 |
| `gateway-api` | `io.nexuspay.gateway` | REST controllers, correlation IDs, global error handler, rate limiting, idempotency | 1.1+ |
| `payment-orchestration` | `io.nexuspay.payment` | HyperSwitch client, webhook receiver, outbox relay, payment domain events | 1.2 |
| `ledger` | `io.nexuspay.ledger` | Double-entry bookkeeping, journal entries, balance management | 1.3 |
| `iam` | `io.nexuspay.iam` | Keycloak integration, API key auth, RBAC, maker-checker, audit logging | 1.5 |
| `reconciliation` | `io.nexuspay.reconciliation` | Automated reconciliation (stub in Phase 1) | 2.3 |
| `observability` | `io.nexuspay.observability` | Metrics, alerting, dashboards (stub in Phase 1) | 2.7 |
| `dispute` | `io.nexuspay.dispute` | Chargeback/dispute management (stub in Phase 1) | 2.4 |
| `workflow` | `io.nexuspay.workflow` | Temporal-based durable workflow orchestration (PaymentWithRetryWorkflow) | 2.2 |
| `app` | `io.nexuspay.app` | Spring Boot main class, unified configuration, Modulith verification | 1.1 |

## 4. Hexagonal Package Convention

Every active module follows this structure:

```
io.nexuspay.{module}/
├── domain/          # Entities, value objects, domain events, exceptions
│                    # ZERO framework dependencies — pure Java
├── application/     # Use cases (inbound ports), outbound port interfaces
│   └── port/        # Port interfaces (PaymentGatewayPort, LedgerPort, etc.)
├── adapter/
│   ├── in/          # REST controllers, Kafka listeners, webhook receivers
│   │                # Inbound adapters — drive the application
│   └── out/         # Database repositories, external API clients
│                    # Outbound adapters — driven by the application
└── config/          # Module-specific @Configuration, @ConfigurationProperties
```

**Dependency rule**: `domain` ← `application` ← `adapter` ← `config`. Inner layers never reference outer layers. Ports live in `application/`, adapters in `adapter/`.

## 5. Technology Stack

| Layer | Technology | Version | Notes |
|-------|-----------|---------|-------|
| Runtime | Java (Temurin) | 21 | Virtual threads enabled |
| Framework | Spring Boot | 3.2.5 | Spring Modulith 1.2.6 |
| Build | Gradle (Kotlin DSL) | 8.7 | Version catalog in `libs.versions.toml` |
| Database | PostgreSQL | 16 | Single DB, module-prefixed Flyway migrations |
| Migrations | Flyway | 10.15.0 | Paths: `db/migration/{module}/` |
| Event Bus | Apache Kafka | KRaft mode | No ZooKeeper, exactly-once semantics |
| Cache | Valkey | 8 | Redis-compatible, used for dedup + idempotency |
| Identity | Keycloak | 26 | OIDC, 3 roles, realm auto-import |
| Resilience | Resilience4j | 2.2.0 | Circuit breaker on HyperSwitch calls |
| Payment Engine | HyperSwitch | Latest | Router + Consumer (drainer) |
| Secrets | HashiCorp Vault | 1.17 | Dev mode with seed script; Spring Cloud Vault (Sprint 2.1) |
| CDC | Debezium | 2.7 | Outbox Event Router via Kafka Connect (Sprint 2.2) |
| Workflows | Temporal | 1.25 | Durable payment orchestration with retry + signal (Sprint 2.2) |
| Logging | Logback + Logstash encoder | — | JSON structured in production, human-readable in dev |
| API Docs | Springdoc OpenAPI | — | Auto-generated at `/v1/api-docs` |

## 6. Infrastructure Topology (Local Development)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                       Docker Compose Network (12 containers)              │
│                                                                          │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────┐          │
│  │ nexuspay-pg  │  │    kafka      │  │      valkey          │          │
│  │ :5432        │  │ :9092/:29092  │  │      :6379           │          │
│  │ (WAL=logical)│  │ (KRaft mode)  │  │  (dedup/idempotency) │          │
│  └──────────────┘  └───────────────┘  └──────────────────────┘          │
│                                                                          │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────┐          │
│  │ hyperswitch- │  │ hyperswitch-  │  │  hyperswitch-pg      │          │
│  │   router     │  │    redis      │  │  :5433               │          │
│  │   :8080      │  │   :6380       │  │ (HyperSwitch DB)     │          │
│  └──────────────┘  └───────────────┘  └──────────────────────┘          │
│                                                                          │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────┐          │
│  │   keycloak   │  │    vault      │  │   kafka-connect      │          │
│  │   :8180      │  │   :8200       │  │   :8083 (Debezium)   │          │
│  │ (realm import│  │ (dev mode)    │  │  (Outbox CDC relay)  │          │
│  └──────────────┘  └───────────────┘  └──────────────────────┘          │
│                                                                          │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────┐          │
│  │  temporal     │  │ temporal-pg   │  │   temporal-ui        │          │
│  │  :7233       │  │  :5434        │  │   :8280              │          │
│  │ (auto-setup) │  │ (Temporal DB) │  │  (workflow dashboard) │          │
│  └──────────────┘  └───────────────┘  └──────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────┘
```

**Port allocations**:
- `8090` — NexusPay application (host, not containerized in dev)
- `8080` — HyperSwitch Router API
- `8180` — Keycloak Admin Console (avoids HyperSwitch port conflict)
- `8200` — HashiCorp Vault (dev mode, Sprint 2.1)
- `8083` — Kafka Connect / Debezium (Sprint 2.2)
- `8280` — Temporal UI (Sprint 2.2)
- `7233` — Temporal gRPC (Sprint 2.2)
- `5432` — NexusPay PostgreSQL (WAL=logical for CDC)
- `5433` — HyperSwitch PostgreSQL
- `5434` — Temporal PostgreSQL
- `9092` — Kafka (internal), `29092` (host access)
- `6379` — Valkey
- `6380` — HyperSwitch Redis

## 7. Cross-Cutting Concerns

### Structured Logging
- JSON format via Logstash encoder in production profile
- MDC fields on every log line: `request_id`, `trace_id`, `payment_id`, `tenant_id`, `module`
- Correlation ID filter (`@Order(HIGHEST_PRECEDENCE)`) generates `X-Request-Id` if absent

### Global Error Handling
- `@ControllerAdvice` maps all domain exceptions to Stripe-inspired error structure
- Response format: `{ "error": { "type": "...", "code": "...", "message": "...", "param": "..." } }`

### Health Checks
- Spring Boot Actuator aggregates PostgreSQL, Kafka, Valkey health
- Custom `HealthIndicator` for HyperSwitch (GET /health)
- Custom `KeycloakHealthIndicator` (calls realm endpoint, Sprint 1.5)
- Custom `VaultHealthIndicator` (calls /v1/sys/health, Sprint 2.1)

### Configuration
- Single `application.yml` with namespaced keys (`nexuspay.hyperswitch.*`, `nexuspay.ledger.*`, `nexuspay.iam.*`)
- Profiles: `default` (local dev), `local` (Docker Compose), `test` (Testcontainers), `production`
- Secrets via environment variables with safe defaults for development

## 8. Data Flow Summary

### Standard Payment Flow
1. **Merchant** sends payment request → **Gateway API** (auth + rate limit + idempotency)
2. **Gateway API** delegates to **Payment Orchestration** use case
3. **Payment Orchestration** calls **HyperSwitch** via `PaymentGatewayPort` (circuit-breaker protected)
4. **HyperSwitch** processes payment through PSP connector, fires webhook
5. **Webhook Controller** receives callback → persists raw payload → dedup → writes to **event_outbox**
6. **Debezium CDC** captures outbox INSERT via WAL → routes to **Kafka** topic `nexuspay.payments`
   - (Fallback: polling **Outbox Relay** if CDC disabled via feature flag)
7. **Ledger** consumes event → creates balanced **journal entry** (double-entry)
8. All operations logged to **audit_log** with correlation IDs

### Durable Workflow Flow (Sprint 2.2+)
1. **Gateway API** starts Temporal **PaymentWithRetryWorkflow** with payment details
2. **Workflow** creates payment in HyperSwitch (activity), waits for confirmation signal
3. **Webhook Controller** signals workflow with payment status via Temporal signal
4. **Workflow** publishes outbox event on success, retries on failure (up to 3 attempts)
5. On timeout (5 min) or exhausted retries: void payment, emit failure event

## 9. Module Dependency Rules (Enforced by Spring Modulith)

```
common          ← (all modules depend on common)
gateway-api     ← depends on: common, payment-orchestration, ledger, iam
payment-orch.   ← depends on: common
ledger          ← depends on: common
iam             ← depends on: common
reconciliation  ← depends on: common, ledger
observability   ← depends on: common
dispute         ← depends on: common, payment-orchestration
workflow        ← depends on: common, payment-orchestration
```

Verified at build time via `ApplicationModules.of(NexusPayApplication.class).verify()`.

## 10. Strategic Documentation

| Document | Path | Purpose |
|----------|------|---------|
| Development Plan | `docs/nexuspay-development-plan.docx` | Original 7-sprint Phase 1 plan |
| Known Gaps | `docs/gaps/known-gaps.md` | Phase 1 gap tracker (19 resolved, 12 open) |
| Strategic Roadmap | `docs/strategy/strategic-roadmap.md` | 5-phase, 120-week roadmap (Phase 1 → v1.0.0) |
| Architecture Evolution | `docs/strategy/architecture-evolution.md` | Module, data model, event, and API evolution plan |
| Competitive Positioning | `docs/strategy/competitive-positioning.md` | Market analysis vs. Spreedly, Primer, Modern Treasury, etc. |
| Gap Inventory | `docs/strategy/gap-inventory.md` | 160 capability gaps across 17 domains mapped to phases |
| Phase 2 Plan | `docs/roadmap/phase-2-production-hardening.md` | Detailed 6-sprint plan: multi-tenancy, CDC, reconciliation, billing |
| Phase 3 Plan | `docs/roadmap/phase-3-intelligence-global.md` | Detailed 6-sprint plan: fraud, FX, smart routing, SDK, analytics |
| Phase 4-5 Plan | `docs/roadmap/phase-4-5-platform-nextgen.md` | Detailed 12-sprint plan: vault, marketplace, B2B, AI/ML, v1.0.0 |

### Architecture Decision Records

| ADR | Decision |
|-----|----------|
| ADR-001 | Build on HyperSwitch (not raw PSP integrations) |
| ADR-002 | Hand-written thin client over OpenAPI codegen |
| ADR-003 | Transactional outbox pattern for event publishing |
| ADR-004 | Spring Modulith + hexagonal architecture |
| ADR-005 | Double-entry ledger with signed amounts |
| ADR-006 | Domain-level Kafka topics (not per-event) |
| ADR-007 | Dual auth: API keys + JWT (Keycloak) |
| ADR-008 | Stripe-inspired gateway API design |
