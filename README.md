# NexusPay

Enterprise payment platform built on [HyperSwitch](https://github.com/juspay/hyperswitch) — open-source payment orchestration with double-entry ledger, fraud prevention, subscription billing, dispute management, and production observability.

NexusPay provides the enterprise operations layer that transforms HyperSwitch from a developer tool into a CFO-ready payment platform: SSO, approval workflows, double-entry ledger, reconciliation, fraud prevention, subscription billing, observability, and compliance automation.

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │           Gateway API                │
                    │  Rate Limit · Idempotency · RBAC     │
                    └────────┬────────────┬───────────────┘
                             │            │
              ┌──────────────┘            └──────────────┐
              │                                           │
    ┌─────────┴──────────┐                    ┌──────────┴─────────┐
    │  Payment Orch.     │                    │    IAM Module      │
    │  HyperSwitch Client│                    │  JWT + API Keys    │
    │  Circuit Breaker   │                    │  Maker-Checker     │
    └────────┬───────────┘                    │  Audit Logging     │
             │                                └────────────────────┘
    ┌────────┴───────────┐     ┌──────────────────────┐
    │  Transactional     │     │  Double-Entry Ledger  │
    │  Outbox → Kafka    │────►│  SERIALIZABLE Txns    │
    │  Debezium CDC      │     │  Reconciliation Jobs  │
    └────────────────────┘     └──────────────────────┘

    ┌────────────────────┐     ┌──────────────────────┐
    │  Fraud Prevention  │     │  Subscription Billing │
    │  Rules Engine      │     │  Multi-Pricing Models │
    │  FRM (Sift/Signifyd│     │  Smart Retry Dunning  │
    │  Device Fingerprint│     │  Invoice & Proration  │
    └────────────────────┘     └──────────────────────┘

    ┌────────────────────┐     ┌──────────────────────┐
    │  Dispute Mgmt      │     │  Observability        │
    │  State Machine      │     │  Prometheus + Grafana │
    │  Evidence + Ledger  │     │  SLO/SLI + Alerts    │
    └────────────────────┘     └──────────────────────┘
```

**Tech stack**: Java 21, Spring Boot 3.2, Spring Modulith, PostgreSQL 16, Kafka (KRaft), Valkey 8, Keycloak 26, Resilience4j, Temporal, Prometheus, Grafana, AlertManager, HashiCorp Vault, Debezium

## Quick Start

```bash
# Prerequisites: Docker, Docker Compose, JDK 21

# 1. Start infrastructure (15 containers)
docker compose -f docker/docker-compose.yml up -d

# 2. Build
./gradlew build

# 3. Run
./gradlew bootRun

# 4. Open
# API:           http://localhost:8090
# Swagger UI:    http://localhost:8090/v1/swagger-ui
# Keycloak:      http://localhost:8180 (admin/admin)
# Grafana:       http://localhost:3000 (admin/admin)
# Prometheus:    http://localhost:9090
# Temporal UI:   http://localhost:8280
# Vault:         http://localhost:8200
```

Or use the quickstart script: `bash scripts/quickstart.sh`

## API Endpoints

### Payments & Refunds

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| POST | `/v1/payments` | Create payment intent | admin, operator |
| POST | `/v1/payments/{id}/confirm` | Confirm payment | admin, operator |
| POST | `/v1/payments/{id}/capture` | Capture payment | admin, operator |
| POST | `/v1/payments/{id}/cancel` | Void authorization | admin, operator |
| POST | `/v1/payments/{id}/refunds` | Create refund (202 if > threshold) | admin, operator |
| GET | `/v1/payments/{id}` | Retrieve payment | all |
| GET | `/v1/refunds/{id}` | Retrieve refund | all |

### Ledger & Approvals

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| GET | `/v1/ledger/accounts` | List ledger accounts | all |
| GET | `/v1/ledger/journal-entries` | List journal entries | all |
| GET | `/v1/approvals` | List pending approvals | admin, operator |
| POST | `/v1/approvals/{id}/approve` | Approve request | admin |
| POST | `/v1/approvals/{id}/reject` | Reject request | admin |

### Fraud Prevention

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| POST | `/v1/fraud/rules` | Create fraud rule | admin |
| GET | `/v1/fraud/rules` | List fraud rules | admin, operator |
| PUT | `/v1/fraud/rules/{id}` | Update fraud rule | admin |
| DELETE | `/v1/fraud/rules/{id}` | Disable fraud rule | admin |
| GET | `/v1/fraud/assessments/pending` | List pending reviews | admin, operator |
| GET | `/v1/fraud/assessments/{id}` | Get assessment details | all |
| POST | `/v1/fraud/assessments/{id}/approve` | Approve assessment | admin |
| POST | `/v1/fraud/assessments/{id}/reject` | Reject assessment | admin |

### Webhooks & API Keys

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| POST | `/v1/webhook-endpoints` | Register webhook URL | admin |
| GET | `/v1/webhook-endpoints` | List webhook endpoints | admin |
| DELETE | `/v1/webhook-endpoints/{id}` | Remove webhook endpoint | admin |
| POST | `/v1/api-keys` | Create API key | admin |

## Authentication

Two authentication methods, both producing a uniform `NexusPayPrincipal`:

1. **JWT (Keycloak OIDC)** — for interactive users (dashboard, admin console)
2. **API Keys** (`sk_test_` / `sk_live_`) — for programmatic access (merchant integrations)

```bash
# API key authentication
curl -H "Authorization: Bearer sk_test_..." http://localhost:8090/v1/payments

# JWT authentication
TOKEN=$(curl -s -X POST http://localhost:8180/realms/nexuspay/protocol/openid-connect/token \
  -d "grant_type=password&client_id=nexuspay-api&username=admin@nexuspay.test&password=test123" \
  | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/v1/payments
```

## Modules

| Module | Purpose | Phase |
|--------|---------|-------|
| `common` | Shared domain (Money, PrefixedId, exceptions, events) | 1.1 |
| `gateway-api` | REST controllers, rate limiting, idempotency | 1.1+ |
| `payment-orchestration` | HyperSwitch client, webhook receiver, outbox relay, Debezium CDC | 1.2 |
| `ledger` | Double-entry ledger, balance reconciliation | 1.3 |
| `iam` | API keys, RBAC, maker-checker, audit logging | 1.5 |
| `reconciliation` | Automated 3-way reconciliation (PSP, ledger, bank) | 2.3 |
| `dispute` | Dispute lifecycle, evidence collection, chargeback ledger | 2.4 |
| `billing` | Subscription billing, multi-pricing, smart dunning, invoicing | 2.5 |
| `workflow` | Temporal-based durable payment orchestration | 2.2 |
| `observability` | Micrometer metrics, Prometheus, Grafana dashboards, SLOs | 2.7 |
| `fraud` | Rules engine, FRM integration (Sift/Signifyd), device fingerprinting | 3.1 |
| `app` | Spring Boot main class, unified configuration | 1.1 |

## Kafka Topics

| Topic | Purpose | Partitions |
|-------|---------|------------|
| `nexuspay.payments` | Payment lifecycle events | 6 |
| `nexuspay.ledger` | Ledger journal entry events | 6 |
| `nexuspay.billing` | Subscription/invoice lifecycle events | 12 |
| `nexuspay.fraud.assessments` | Fraud assessment results | 12 |
| `nexuspay.fraud.events` | Fraud rule trigger events | 12 |
| `nexuspay.fraud.rules.changelog` | Fraud rule CRUD changes | 6 |

## Testing

```bash
# Unit tests
./gradlew test

# Integration tests (requires Docker for Testcontainers)
./gradlew test -Pintegration

# Load test (requires running app)
cd gatling && ../gradlew gatlingRun
```

## Deployment

```bash
# Helm (Kubernetes)
helm install nexuspay ./nexuspay-helm -f nexuspay-helm/environments/dev/values.yaml
```

## Documentation

- [Architecture Overview](docs/architecture/system-overview.md) — full system architecture, module inventory, data flows
- [Payment Flow Diagrams](docs/diagrams/payment-flow.md)
- [Ledger Flow Diagrams](docs/diagrams/ledger-flow.md)
- [IAM & Auth Flow](docs/diagrams/iam-auth-flow.md)
- [Kafka Event Streaming](docs/diagrams/kafka-event-streaming.md)
- [Gateway API Flow](docs/diagrams/gateway-api-flow.md)
- [Known Gaps](docs/gaps/known-gaps.md) — 41 gaps tracked, 22 resolved
- [Architecture Decision Records](docs/decisions/)
- [Strategic Roadmap](docs/strategy/strategic-roadmap.md)
- [Phase 2 Plan](docs/roadmap/phase-2-production-hardening.md)
- [Phase 3 Plan](docs/roadmap/phase-3-intelligence-global.md)

## License

Apache License 2.0 — see [LICENSE](LICENSE)
