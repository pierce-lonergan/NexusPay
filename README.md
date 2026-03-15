# NexusPay

Enterprise payment platform built on [HyperSwitch](https://github.com/juspay/hyperswitch) — open-source payment orchestration with double-entry ledger, IAM, and event streaming.

NexusPay provides the enterprise operations layer that transforms HyperSwitch from a developer tool into a CFO-ready payment platform: SSO, approval workflows, double-entry ledger, reconciliation, observability, and compliance automation.

## Architecture

```
                    ┌─────────────────────────────────┐
                    │          Gateway API             │
                    │  Rate Limit · Idempotency · RBAC │
                    └────────┬───────────┬────────────┘
                             │           │
              ┌──────────────┘           └──────────────┐
              │                                          │
    ┌─────────┴──────────┐                    ┌─────────┴──────────┐
    │  Payment Orch.     │                    │    IAM Module      │
    │  HyperSwitch Client│                    │  JWT + API Keys    │
    │  Circuit Breaker   │                    │  Maker-Checker     │
    └────────┬───────────┘                    │  Audit Logging     │
             │                                └────────────────────┘
    ┌────────┴───────────┐     ┌──────────────────────┐
    │  Transactional     │     │  Double-Entry Ledger  │
    │  Outbox → Kafka    │────►│  SERIALIZABLE Txns    │
    └────────────────────┘     │  Reconciliation Jobs  │
                               └──────────────────────┘
```

**Tech stack**: Java 21, Spring Boot 3.2, Spring Modulith, PostgreSQL 16, Kafka (KRaft), Valkey 8, Keycloak 26, Resilience4j

## Quick Start

```bash
# Prerequisites: Docker, Docker Compose, JDK 21

# 1. Start infrastructure
docker compose -f docker/docker-compose.yml up -d

# 2. Build
./gradlew build

# 3. Run
./gradlew bootRun

# 4. Open
# API:        http://localhost:8090
# Swagger UI: http://localhost:8090/v1/swagger-ui
# Keycloak:   http://localhost:8180 (admin/admin)
```

Or use the quickstart script: `bash scripts/quickstart.sh`

## API Endpoints

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| POST | `/v1/payments` | Create payment intent | admin, operator |
| POST | `/v1/payments/{id}/confirm` | Confirm payment | admin, operator |
| POST | `/v1/payments/{id}/capture` | Capture payment | admin, operator |
| POST | `/v1/payments/{id}/cancel` | Void authorization | admin, operator |
| POST | `/v1/payments/{id}/refunds` | Create refund (202 if > threshold) | admin, operator |
| GET | `/v1/payments/{id}` | Retrieve payment | all |
| GET | `/v1/refunds/{id}` | Retrieve refund | all |
| GET | `/v1/ledger/accounts` | List ledger accounts | all |
| GET | `/v1/ledger/journal-entries` | List journal entries | all |
| GET | `/v1/approvals` | List pending approvals | admin, operator |
| POST | `/v1/approvals/{id}/approve` | Approve request | admin |
| POST | `/v1/approvals/{id}/reject` | Reject request | admin |
| POST | `/v1/webhook-endpoints` | Register webhook URL | admin |
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

| Module | Purpose |
|--------|---------|
| `common` | Shared domain (Money, PrefixedId, exceptions, events) |
| `gateway-api` | REST controllers, rate limiting, idempotency |
| `payment-orchestration` | HyperSwitch client, webhook receiver, outbox relay |
| `ledger` | Double-entry ledger, balance reconciliation |
| `iam` | API keys, RBAC, maker-checker, audit logging |
| `app` | Spring Boot main class, unified configuration |

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

- [Architecture Overview](docs/architecture/system-overview.md)
- [Payment Flow Diagrams](docs/diagrams/payment-flow.md)
- [Ledger Flow Diagrams](docs/diagrams/ledger-flow.md)
- [IAM & Auth Flow](docs/diagrams/iam-auth-flow.md)
- [Kafka Event Streaming](docs/diagrams/kafka-event-streaming.md)
- [Gateway API Flow](docs/diagrams/gateway-api-flow.md)
- [Known Gaps](docs/gaps/known-gaps.md)
- [Architecture Decision Records](docs/decisions/)

## License

Apache License 2.0 — see [LICENSE](LICENSE)
