# NexusPay Known Gaps Analysis

Last updated: 2026-03-15 (Sprint 1.7 complete — gap remediation applied)

This document tracks known gaps, technical debt, and deferred decisions in the NexusPay system. Each gap is categorized by severity, the sprint it was identified, and the planned resolution timeline.

---

## Critical Gaps (Must Address Before Production)

### ~~GAP-001: No Multi-Tenancy Enforcement~~ (IN PROGRESS Sprint 2.1)
- **Identified**: Sprint 1.1
- **Status**: In progress — Sprint 2.1
- **Description**: `TenantContext` ThreadLocal holder, `TenantContextFilter` (extracts tenant from `NexusPayPrincipal`), and `TenantAwareDataSourceConfig` (injects `SET LOCAL app.current_tenant_id` per connection) implemented. Flyway migration `V2001__enable_row_level_security.sql` enables RLS on all 9 tenant-scoped tables. `postings` table extended with `tenant_id` column (backfilled from `journal_entries`). Dedicated `nexuspay_app` role created (subject to RLS; superuser bypasses for migrations).
- **Remaining**: Integration tests for cross-tenant isolation, production role configuration.

### GAP-002: No TLS / mTLS Between Services
- **Identified**: Sprint 1.1
- **Status**: Deferred to Phase 2
- **Description**: All inter-service communication (NexusPay ↔ HyperSwitch, NexusPay ↔ Kafka, NexusPay ↔ PostgreSQL) is unencrypted in the Docker Compose dev environment. The Helm chart does not configure TLS.
- **Risk**: Unacceptable for production. API keys and payment data traverse the network in plaintext.
- **Resolution**: Phase 2 Helm chart — TLS termination at ingress, mTLS for internal services, PostgreSQL `sslmode=verify-full`.

### ~~GAP-003: No Secrets Management~~ (IN PROGRESS Sprint 2.1)
- **Identified**: Sprint 1.1
- **Status**: In progress — Sprint 2.1
- **Description**: HashiCorp Vault 1.17 added to Docker Compose. Spring Cloud Vault dependency added. Vault dev server with seed script (`docker/config/vault/seed-secrets.sh`) provisions database, HyperSwitch, Keycloak, Kafka, and encryption secrets. `VaultHealthIndicator` monitors connectivity. Vault integration disabled by default for local dev (enable with `vault` Spring profile).
- **Remaining**: Enable by default in production profile, dynamic database credentials via Vault database secrets engine.

### GAP-004: No Database Backup / Point-in-Time Recovery
- **Identified**: Sprint 1.1
- **Status**: Deferred to Phase 2
- **Description**: PostgreSQL runs as a Docker container with ephemeral storage. No WAL archiving, no backup strategy, no PITR capability.
- **Risk**: Data loss on container restart in production.
- **Resolution**: Phase 2 — managed PostgreSQL (RDS/CloudSQL) or WAL-G based backup with S3.

---

## High Gaps (Should Address Before Beta)

### ~~GAP-005: Outbox Table Unbounded Growth~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `DataRetentionJob` added with `@Scheduled(cron = "0 0 3 * * *")` — deletes published outbox events older than 7 days (configurable via `nexuspay.retention.outbox-days`).

### ~~GAP-006: Webhook Raw Payload Unbounded Growth~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `DataRetentionJob` added with `@Scheduled(cron = "0 30 3 * * *")` — deletes processed/failed webhooks older than 90 days (configurable via `nexuspay.retention.webhook-days`).

### ~~GAP-007: No Outbox Relay Leader Election~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `OutboxRelay` now acquires a Valkey distributed lock (`outbox:relay:leader` with 5s TTL) before polling. Only one instance relays events at a time. Fails open if Valkey is unavailable (single instance safe).

### GAP-008: No Dead Letter Queue Reprocessing UI
- **Identified**: Sprint 1.2 (DLT publishing resolved in 1.4)
- **Status**: Partially resolved — DLT publishing works, reprocessing not yet available
- **Description**: `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` now publishes failed messages to `.DLT` topics after 3 retries. However, there is no consumer, dashboard, or retry-from-DLT API to reprocess failed messages.
- **Risk**: Failed messages are retained in DLT for 30 days but require manual Kafka tooling to inspect.
- **Resolution**: Phase 2 — add DLT monitoring dashboard and retry-from-DLT capability.

### ~~GAP-009: No Rate Limiting Implementation~~ (RESOLVED Sprint 1.6)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `RateLimitFilter` implemented with Valkey token bucket. 100 requests/minute per API key (configurable). Returns `429 Too Many Requests` with `Retry-After` and `X-RateLimit-Remaining` headers. Fails open if Valkey is unavailable.

### ~~GAP-010: No Idempotency Implementation~~ (RESOLVED Sprint 1.6)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `IdempotencyFilter` implemented with Valkey distributed lock + response caching. Uses `SET NX EX 60` for processing lock, caches responses for 24h. Concurrent duplicate requests poll with backoff until first request completes. Idempotency-Key also propagated to HyperSwitch.

---

## Medium Gaps (Address in Phase 2)

### GAP-011: Polling Outbox 1-Second Latency
- **Identified**: Sprint 1.2
- **Status**: Accepted for Phase 1
- **Description**: The outbox relay polls every 1 second, adding up to 1s latency between a webhook being received and the event being published to Kafka. This is acceptable for Phase 1 volumes.
- **Resolution**: Phase 2 — replace with Debezium CDC for sub-second event propagation.

### GAP-012: No Schema Registry / Event Versioning
- **Identified**: Sprint 1.2
- **Status**: Deferred to Phase 3
- **Description**: Events are JSON without schema validation. No Confluent Schema Registry, no Avro/Protobuf schemas, no compatibility checks. Event `version` field exists in the envelope but is always `1`.
- **Risk**: Schema evolution (adding/removing fields) could break consumers without detection.
- **Resolution**: Phase 3 — Avro schemas with Schema Registry, backward/forward compatibility enforcement.

### ~~GAP-013: No Keycloak Health Indicator~~ (RESOLVED Sprint 1.5)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `KeycloakHealthIndicator` implemented. Calls Keycloak realm endpoint, reports UP/DOWN to `/actuator/health`.

### GAP-026: No API Key Expiration or Rotation
- **Identified**: Sprint 1.5
- **Status**: Open
- **Description**: API keys have no expiration date. The only way to invalidate a key is manual revocation. No built-in rotation mechanism (must create new key, update clients, then revoke old key).
- **Risk**: Long-lived keys increase the blast radius of a compromised key.
- **Resolution**: Phase 2 — add optional TTL on API keys, rotation API that creates new key and schedules old key revocation.

### GAP-027: No Audit Log Retention Policy
- **Identified**: Sprint 1.5
- **Status**: Open
- **Description**: The `audit_log` table grows unboundedly. No archival, partitioning, or retention policy.
- **Risk**: Storage growth, query performance degradation over time.
- **Resolution**: Phase 2 — partition by timestamp, archive to cold storage after 90 days.

### GAP-028: Self-Approval Prevention is Application-Level Only
- **Identified**: Sprint 1.5
- **Status**: Accepted for Phase 1
- **Description**: The maker-checker flow prevents the requester from approving their own request at the application level (`ApprovalService.approve()` checks `requestedBy != reviewerId`). No database-level constraint.
- **Risk**: A bug in the application layer could allow self-approval.
- **Resolution**: Low priority — application-level check is sufficient for Phase 1. Consider DB trigger in Phase 2.

### ~~GAP-014: No Graceful Shutdown for Outbox Relay~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `OutboxRelay` now has `@PreDestroy` shutdown hook that sets `shuttingDown` flag and waits up to 5 seconds for the in-flight relay cycle to complete. Also releases the Valkey leader lock on shutdown.

### GAP-015: No Webhook Retry / Reprocessing
- **Identified**: Sprint 1.2
- **Status**: Open
- **Description**: If a webhook fails after HMAC verification (e.g., DB write fails), there's no mechanism to request HyperSwitch resend it. The raw payload is persisted, but there's no reprocessing API.
- **Risk**: Missed events if processing fails post-persist.
- **Resolution**: Phase 2 — add `POST /internal/webhooks/reprocess/{id}` endpoint that reads from `inbound_webhooks` and re-inserts into outbox.

### ~~GAP-016: No API Versioning Implementation~~ (RESOLVED Sprint 1.6)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `ApiVersionInterceptor` implemented. Parses `X-API-Version` header, defaults to `2026-03-01`, stores in request attribute. Single version but plumbing ready for future versions.

### ~~GAP-017: Single Default Flyway Location~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: Each module now registers its own `FlywayConfigurationCustomizer` bean (`PaymentFlywayConfig`, `LedgerFlywayConfig`, `IamFlywayConfig`, `GatewayFlywayConfig`) that adds its migration path. `application.yml` uses a base `classpath:db/migration` location; modules self-register. New modules just need a config class.

---

## Low Gaps (Nice to Have)

### ~~GAP-022: Ledger Consumer Lacks DLT Handling~~ (RESOLVED Sprint 1.4)
- **Identified**: Sprint 1.3
- **Status**: Resolved
- **Description**: `KafkaConsumerConfig` now applies `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` and `FixedBackOff(1000, 3)` to all consumer container factories. Failed messages are published to `.DLT` topics after 3 retries.

### GAP-023: No Multi-Currency Reconciliation Reporting
- **Identified**: Sprint 1.3
- **Status**: Open
- **Description**: The balance reconciliation job checks all accounts but doesn't report per-currency summaries. As more currencies are added, operators need visibility into aggregate positions per currency.
- **Resolution**: Phase 2 — add per-currency balance summary to reconciliation report.

### GAP-024: SERIALIZABLE Isolation Throughput Ceiling
- **Identified**: Sprint 1.3
- **Status**: Accepted for Phase 1
- **Description**: Journal entry creation uses SERIALIZABLE transaction isolation. Under high concurrency, this increases serialization failures and retry overhead. Acceptable for Phase 1 volumes (< 200 TPS).
- **Risk**: At scale, this becomes a bottleneck. May need to move to advisory locks or partitioned accounts.
- **Resolution**: Phase 2 — benchmark and consider advisory locking or sharded ledger accounts if needed.

### ~~GAP-025: Ledger Has No REST API Yet~~ (RESOLVED Sprint 1.6)
- **Identified**: Sprint 1.3
- **Status**: Resolved
- **Description**: `LedgerController` implemented in gateway-api module. `GET /v1/ledger/accounts` lists accounts with balances. `GET /v1/ledger/journal-entries` supports filtering by payment_reference or date range with pagination.

### GAP-029: Rate Limiter Uses Single Window for All API Keys
- **Identified**: Sprint 1.6
- **Status**: Accepted for Phase 1
- **Description**: All API keys share the same rate limit configuration (100 req/min). No per-key tier system or configurable quotas.
- **Risk**: High-volume merchants cannot be given higher limits without changing the global default.
- **Resolution**: Phase 2 — add per-key rate limit tiers stored in `api_keys` table, read by `RateLimitFilter`.

### ~~GAP-030: Webhook Endpoint Delivery Not Implemented~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.6
- **Status**: Resolved
- **Description**: `WebhookDeliveryService` implemented as a Kafka consumer on `nexuspay.payments` topic. Delivers events to all enabled webhook endpoints matching the event type. HMAC-SHA256 signed with `X-NexusPay-Signature` header. Exponential backoff retry deferred to Phase 2.

### ~~GAP-031: Idempotency Filter Thread.sleep in Virtual Thread Context~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.6
- **Status**: Resolved
- **Description**: `IdempotencyFilter.pollForResult()` now uses `LockSupport.parkNanos()` instead of `Thread.sleep()`, avoiding carrier thread pinning when running on virtual threads.

### GAP-018: No Structured Error Codes Catalog
- **Identified**: Sprint 1.1
- **Status**: Open
- **Description**: Error codes exist in `ApiError` (e.g., `payment_error`, `invalid_request_error`) but there's no comprehensive catalog documenting all possible error codes, their meanings, and suggested client actions.
- **Resolution**: Phase 2 — publish error catalog in developer portal.

### ~~GAP-019: No Request/Response Logging (HTTP Level)~~ (RESOLVED Sprint 1.7)
- **Identified**: Sprint 1.2
- **Status**: Resolved
- **Description**: `HttpLoggingFilter` implemented at `@Order(5)`. Logs method, path, status code, duration, and request_id for every API call. Skips actuator, internal, swagger, and api-docs paths.

### GAP-020: No Metrics Export
- **Identified**: Sprint 1.1
- **Status**: Deferred to Phase 2
- **Description**: Actuator exposes `/metrics` and `/prometheus` endpoints, but no Prometheus/Grafana stack is deployed. No custom business metrics (payments/sec, average latency, circuit breaker state).
- **Resolution**: Phase 2 observability module — Prometheus, Grafana dashboards, custom Micrometer metrics.

### GAP-021: HyperSwitch Consumer (Drainer) Not Health-Checked
- **Identified**: Sprint 1.2
- **Status**: Open
- **Description**: The HyperSwitch health indicator checks the router's `/health` endpoint, but the consumer/drainer process (critical for webhook delivery and state persistence) is not monitored.
- **Risk**: If the drainer goes down, HyperSwitch webhooks stop firing and Redis→PostgreSQL persistence breaks. NexusPay wouldn't detect this.
- **Resolution**: Research if HyperSwitch drainer exposes a health endpoint. If not, monitor indirectly via webhook arrival rate.

---

## Gap Resolution Timeline

| Phase | Gaps Addressed |
|-------|---------------|
| Sprint 1.4 | GAP-008 (partial), GAP-022 |
| Sprint 1.5 | GAP-013 |
| Sprint 1.6 | GAP-009, GAP-010, GAP-016, GAP-025 |
| Sprint 1.7 | GAP-005, GAP-006, GAP-007, GAP-014, GAP-017, GAP-019, GAP-030, GAP-031 |
| Sprint 2.1 (in progress) | GAP-001 (RLS), GAP-003 (Vault) |
| Phase 2 (remaining) | GAP-002, GAP-004, GAP-008, GAP-011, GAP-015, GAP-018, GAP-020, GAP-021, GAP-023, GAP-026, GAP-027 |
| Phase 3 | GAP-012 |

## Summary

- **Total gaps tracked**: 31
- **Resolved**: 19 (GAP-005, 006, 007, 009, 010, 013, 014, 016, 017, 019, 022, 025, 030, 031 + partial GAP-008)
- **Open/Deferred**: 12 (GAP-001–004 critical, GAP-008/011/012/015/018/020/021/023/024/026/027/028/029)
- **Accepted for Phase 1**: GAP-024, GAP-028, GAP-029
