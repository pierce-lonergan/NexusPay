# Changelog

All notable changes to NexusPay are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [0.1.0] - 2026-03-15

### Added

**Sprint 1.1 — Project Scaffold**
- Gradle multi-module project with Spring Modulith (8 bounded contexts + common)
- Hexagonal package structure for all modules
- Spring Modulith verification test enforcing module boundaries
- Docker Compose with HyperSwitch, PostgreSQL, Kafka (KRaft), Valkey, Keycloak
- Structured JSON logging via Logback + Logstash encoder
- Global error handler with Stripe-inspired error format
- Correlation ID filter (X-Request-Id) with MDC propagation
- GitHub Actions CI pipeline

**Sprint 1.2 — HyperSwitch Integration**
- HyperSwitch Java client (hand-written thin client for core endpoints)
- PaymentGatewayPort with full operation set (authorize, capture, void, refund)
- Webhook receiver with HMAC-SHA512 verification
- Raw webhook payload persistence (inbound_webhooks table)
- Transactional outbox pattern (event_outbox table)
- Polling outbox relay (1s interval) publishing to Kafka
- Resilience4j circuit breaker for HyperSwitch calls
- HyperSwitch health indicator for actuator

**Sprint 1.3 — Double-Entry Ledger**
- PostgreSQL schema: ledger_accounts, journal_entries, postings
- SERIALIZABLE transaction isolation for journal entries
- Optimistic concurrency (version column) on ledger accounts
- Zero-sum invariant enforcement on all journal entries
- Default chart of accounts seeded for USD
- Multi-currency auto-provisioning (accounts created on demand)
- Hourly balance reconciliation job
- Kafka consumer for payment events → ledger entries

**Sprint 1.4 — Kafka Event Streaming**
- Domain-level Kafka topics (nexuspay.payments, nexuspay.ledger)
- JSON event envelope with standard structure
- Dead letter topics (DLT) with 3 retries + 1s backoff
- Idempotent Kafka producer (acks=all)
- Consumer groups with read_committed isolation

**Sprint 1.5 — IAM & Keycloak**
- Keycloak realm with 3 roles (admin, operator, viewer)
- JWT validation with Spring Security
- Stripe-inspired API keys (sk_test_ / sk_live_) with bcrypt
- Dual auth: API key filter + JWT filter producing NexusPayPrincipal
- 3-role RBAC with @PreAuthorize
- Maker-checker workflow for refunds above threshold (202 Accepted)
- Synchronous audit logging (explicit + AOP)
- Keycloak health indicator

**Sprint 1.6 — Gateway API**
- REST controllers for payments, refunds, ledger, approvals, webhook endpoints, API keys
- Valkey token bucket rate limiting (100 req/min, 429 responses)
- Idempotency enforcement (Valkey distributed lock + 24h response cache)
- API versioning plumbing (X-API-Version header)
- OpenAPI 3.1 spec via Springdoc (Swagger UI)
- Webhook endpoint management (register, list, delete)
- Cursor-based pagination support

**Sprint 1.7 — Testing, Helm & Release**
- Testcontainers integration test suite (singleton container pattern)
- WireMock stubs for HyperSwitch unit tests
- Integration tests: auth, RBAC, ledger, maker-checker, webhook endpoints
- Helm umbrella chart with Bitnami dependencies
- Quickstart script (scripts/quickstart.sh)
- Gatling load test baseline
- README, CONTRIBUTING.md, CHANGELOG.md

### Known Limitations
- Single-tenant (tenant_id present but no RLS enforcement)
- No TLS/mTLS in development environment
- Webhook endpoint delivery not implemented (registration only)
- Rate limiter uses single tier for all API keys
- Polling outbox (1s latency, no leader election)
- See [Known Gaps](docs/gaps/known-gaps.md) for full list
