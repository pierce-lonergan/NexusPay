# NexusPay Known Gaps Analysis

Last updated: 2026-03-15 (Sprint 3.3 — Smart Routing Engine)

This document tracks known gaps, technical debt, and deferred decisions in the NexusPay system. Each gap is categorized by severity, the sprint it was identified, and the planned resolution timeline.

---

## Critical Gaps (Must Address Before Production)

### ~~GAP-001: No Multi-Tenancy Enforcement~~ (RESOLVED Sprint 2.1)
- **Identified**: Sprint 1.1
- **Status**: Resolved — Sprint 2.1
- **Description**: `TenantContext` ThreadLocal holder, `TenantContextFilter` (extracts tenant from `NexusPayPrincipal`), and `TenantAwareDataSourceConfig` (injects `SET LOCAL app.current_tenant_id` per connection) implemented. Flyway migration `V2001__enable_row_level_security.sql` enables RLS on all tenant-scoped tables (including fraud tables added in Sprint 3.1). `postings` table extended with `tenant_id` column (backfilled from `journal_entries`). Dedicated `nexuspay_app` role created (subject to RLS; superuser bypasses for migrations).

### GAP-002: No TLS / mTLS Between Services
- **Identified**: Sprint 1.1
- **Status**: Deferred to Phase 2
- **Description**: All inter-service communication (NexusPay ↔ HyperSwitch, NexusPay ↔ Kafka, NexusPay ↔ PostgreSQL) is unencrypted in the Docker Compose dev environment. The Helm chart does not configure TLS.
- **Risk**: Unacceptable for production. API keys and payment data traverse the network in plaintext.
- **Resolution**: Phase 2 Helm chart — TLS termination at ingress, mTLS for internal services, PostgreSQL `sslmode=verify-full`.

### ~~GAP-003: No Secrets Management~~ (RESOLVED Sprint 2.1)
- **Identified**: Sprint 1.1
- **Status**: Resolved — Sprint 2.1
- **Description**: HashiCorp Vault 1.17 added to Docker Compose. Spring Cloud Vault dependency added. Vault dev server with seed script (`docker/config/vault/seed-secrets.sh`) provisions database, HyperSwitch, Keycloak, Kafka, and encryption secrets. `VaultHealthIndicator` monitors connectivity. Vault integration disabled by default for local dev (enable with `vault` Spring profile). Dynamic database credentials via Vault database secrets engine deferred to production hardening.

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

### ~~GAP-011: Polling Outbox 1-Second Latency~~ (RESOLVED Sprint 2.2)
- **Identified**: Sprint 1.2
- **Status**: Resolved — Sprint 2.2
- **Description**: Debezium CDC (2.7) added to Docker Compose via Kafka Connect. Outbox Event Router transform routes events from `event_outbox` table to Kafka topics based on `routing_key` column. Migration `V2002__outbox_debezium_columns.sql` adds `routing_key` and `event_version` columns. Feature flag (`nexuspay.outbox.polling.enabled`) allows parallel operation of polling relay and CDC during migration. PostgreSQL WAL level set to `logical` with replication slots configured.

### ~~GAP-012: No Schema Registry / Event Versioning~~ (RESOLVED Sprint 3.4)
- **Identified**: Sprint 1.2
- **Status**: ~~Partially addressed Sprint 2.2~~ → **Resolved Sprint 3.4**
- **Description**: ~~`EventUpcaster` interface and `EventUpcasterChain` added in `common` module for runtime event schema evolution (v1 → v2 → ... → current). Outbox `event_version` column tracks version per event. Full Schema Registry (Confluent, Avro/Protobuf) still deferred.~~ Full Confluent Schema Registry (7.6.1) with 21 Avro schema definitions, compile-time code generation via avro-gradle-plugin, DualFormatDeserializer for backward-compatible consumption, feature-flagged DualWritePublisher with Schema Registry circuit breaker, and EventUpcaster extended with GenericRecord support.
- **Risk**: ~~No compile-time schema validation yet.~~ Resolved — Avro schemas provide compile-time validation via generated Java classes.
- **Resolution**: Sprint 3.4 — JSON-to-Avro migration with dual-write strategy. Schema compatibility: NONE during registration, FULL_TRANSITIVE after validation.

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

### ~~GAP-023: No Multi-Currency Reconciliation Reporting~~ (PARTIALLY ADDRESSED Sprint 2.3)
- **Identified**: Sprint 1.3
- **Status**: Partially addressed — Sprint 2.3
- **Description**: Reconciliation engine implemented with full 3-way matching (settlement ↔ payment ↔ ledger). `ThreeWayMatchingService` validates amounts, currencies, and ledger entries. Settlement records track currency per record. Per-currency aggregate summary reporting deferred.
- **Remaining**: Per-currency aggregate balance summary view in reconciliation dashboard.

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

### GAP-032: Dispute Module — Network Integration Stubs Only
- **Identified**: Sprint 2.4
- **Status**: Partially addressed
- **Description**: Dispute lifecycle, evidence collection, chargeback ledger integration, and auto-representment rule engine implemented. `DisputeNetworkPort` has stub implementations for Verifi RDR and Ethoca. Evidence storage uses local filesystem; S3 adapter deferred.
- **Remaining**: Real Verifi/Ethoca API integration (Phase 3), S3 evidence storage, Temporal-based deadline tracking workflow, dispute dashboard UI.
- **Resolution**: Phase 3 — network adapter implementations, S3 storage, Temporal deadline workflow.

### ~~GAP-034: Billing Payment Collection Uses Stub Adapter~~ (RESOLVED Sprint 2.5b)
- **Identified**: Sprint 2.5a
- **Status**: Resolved
- **Description**: `PaymentOrchestrationAdapter` now delegates to `PaymentGatewayPort` from the payment-orchestration module, which calls HyperSwitch via `RestClient` with circuit breaker protection. Payments created with `captureMethod=automatic` for subscription billing. Idempotency keys include invoice ID for retry safety.

### GAP-035: No Tax Calculation in Billing
- **Identified**: Sprint 2.5a
- **Status**: Open
- **Description**: `Invoice.recalculate()` sets tax to 0. No tax engine integration (Avalara, TaxJar, etc.). Invoices only contain subtotal; tax line items deferred.
- **Risk**: Invoices are not legally compliant for jurisdictions requiring tax collection.
- **Resolution**: Phase 3 — integrate tax calculation service.

### ~~GAP-036: Billing Kafka Events Not Yet Published~~ (RESOLVED Sprint 2.5b)
- **Identified**: Sprint 2.5a
- **Status**: Resolved
- **Description**: All billing lifecycle transitions now publish events via `BillingOutboxPort` → `event_outbox` table → OutboxRelay → `nexuspay.billing` Kafka topic. Events: SubscriptionCreated, SubscriptionActivated, SubscriptionCanceled, SubscriptionPaused, SubscriptionResumed, SubscriptionRenewed, SubscriptionTrialConverted, InvoiceCreated, InvoicePaid, DunningInitiated, DunningRetryFailed, DunningRecovered, DunningExhausted. `BillingPaymentEventListener` consumes `nexuspay.payments` events for async payment result handling.

### GAP-037: Smart Retry Customer Metadata Not Populated
- **Identified**: Sprint 2.5b
- **Status**: Open
- **Description**: `SmartRetryOptimizer` reads `customer_timezone` and `card_type` from subscription metadata, but no API endpoint or webhook handler currently populates these fields. Merchants must manually pass them in subscription creation metadata.
- **Risk**: Smart retry falls back to default timezone and timing without metadata, reducing optimization effectiveness.
- **Resolution**: Phase 3 — auto-populate card_type from payment method details via HyperSwitch. Customer timezone from billing address or client-side detection.

### GAP-038: Billing Module Circular Dependency Risk
- **Identified**: Sprint 2.5b
- **Status**: Accepted for Phase 2
- **Description**: The billing module now depends on `payment-orchestration` (for `PaymentGatewayPort`). If payment-orchestration ever needs billing data (e.g., invoice context), it would create a circular dependency. Currently safe because the dependency is one-directional.
- **Risk**: Low — architecture guard in Spring Modulith verification test prevents accidental circular deps.
- **Resolution**: If needed, introduce a shared `billing-api` interface module or use event-based communication.

### GAP-033: Dispute Deadline Tracking Not Automated
- **Identified**: Sprint 2.4
- **Status**: Open
- **Description**: The `evidence_due_date` field is stored on disputes but no automated scheduler or Temporal workflow monitors deadlines and triggers EXPIRED transitions. Expiration currently requires explicit API call or webhook.
- **Risk**: Disputes may miss evidence deadlines silently.
- **Resolution**: Phase 3 — Temporal workflow with timer-based deadline monitoring and reminder notifications.

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

### ~~GAP-020: No Metrics Export~~ (RESOLVED Sprint 2.7)
- **Identified**: Sprint 1.1
- **Status**: Resolved
- **Description**: Full production observability stack deployed. Prometheus scrapes NexusPay metrics every 10s. Grafana provisioned with 4 dashboards (Payment Operations, Ledger Health, Infrastructure, Subscriptions & Billing). AlertManager configured with 6 alert rules (HighPaymentFailureRate, CircuitBreakerOpen, OutboxLagHigh, DatabaseConnectionPoolSaturation, KafkaConsumerLagHigh, HighP99Latency). 10+ custom Micrometer metrics across PaymentMetrics, LedgerMetrics, BillingMetrics, InfrastructureMetrics. SLO/SLI recording rules for availability (99.9%), latency p99 (<2s), error rate (<0.1%), payment success (>95%).

### GAP-021: HyperSwitch Consumer (Drainer) Not Health-Checked
- **Identified**: Sprint 1.2
- **Status**: Open
- **Description**: The HyperSwitch health indicator checks the router's `/health` endpoint, but the consumer/drainer process (critical for webhook delivery and state persistence) is not monitored.
- **Risk**: If the drainer goes down, HyperSwitch webhooks stop firing and Redis→PostgreSQL persistence breaks. NexusPay wouldn't detect this.
- **Resolution**: Research if HyperSwitch drainer exposes a health endpoint. If not, monitor indirectly via webhook arrival rate.

### GAP-039: Fraud Module — No ML-Based Scoring
- **Identified**: Sprint 3.1
- **Status**: Deferred to Phase 4
- **Description**: The fraud rules engine uses deterministic rule-based scoring. No ML model for anomaly detection, behavioral analysis, or adaptive risk scoring.
- **Resolution**: Phase 4 — integrate ML model serving (e.g., TensorFlow Serving or SageMaker) for real-time fraud scoring alongside native rules.

### GAP-040: Fraud Module — FRM Providers Not Fully Tested End-to-End
- **Identified**: Sprint 3.1
- **Status**: Open
- **Description**: Sift and Signifyd adapters are implemented with correct API structures but require real API keys and sandbox testing to validate request/response mapping. Currently tested with WireMock stubs only.
- **Resolution**: Obtain sandbox API keys from Sift and Signifyd for integration testing.

### GAP-041: Fraud Module — No IP Geolocation Service
- **Identified**: Sprint 3.1
- **Status**: Open
- **Description**: GEO_RESTRICTION rules rely on `ip_country` being provided in the PaymentContext. No automatic IP-to-country lookup service (e.g., MaxMind GeoIP) is integrated.
- **Resolution**: Integrate MaxMind GeoLite2 or similar IP geolocation database for automatic country resolution.

### ~~GAP-042: FX Module — No Real-Time Rate Streaming~~ (RESOLVED Sprint 3.2 patch)
- **Identified**: Sprint 3.2
- **Status**: Resolved — Sprint 3.2 patch
- **Description**: `FxRateStreamingService` added with `@Scheduled` rate streaming to `nexuspay.fx.rates` Kafka topic every 5 minutes (configurable via `nexuspay.fx.streaming.cron`). Downstream consumers now receive proactive rate updates via Kafka instead of relying on pull-based cache reads. On-demand `publishRateUpdate()` also available for ad-hoc rate pushes.

### ~~GAP-043: FX Module — ECB Rate Parsing Brittle~~ (RESOLVED Sprint 3.2 patch)
- **Identified**: Sprint 3.2
- **Status**: Resolved — Sprint 3.2 patch
- **Description**: `EcbFxRateAdapter` rewritten with primary XML parsing using the ECB's well-known `eurofxref-daily.xml` endpoint with DOM parser and XXE protection. SDMX CSV endpoint retained as automatic fallback. Header row skipping and zero-result validation added to CSV parser.

### ~~GAP-044: FX Module — No DCC (Dynamic Currency Conversion) Implementation~~ (RESOLVED Sprint 3.2 patch)
- **Identified**: Sprint 3.2
- **Status**: Resolved — Sprint 3.2 patch
- **Description**: Full DCC flow implemented: `DccOffer` domain model with lifecycle (OFFERED → ACCEPTED/DECLINED/EXPIRED), `DynamicCurrencyConversionService` with rate disclosure, configurable DCC markup (default 300bps), offer validity window (default 5min), and consent tracking. REST endpoints: `POST /v1/fx/dcc/offers`, `POST .../accept`, `POST .../decline`. Regulatory-compliant disclosure includes exchange rate, markup, margin amount, and expiry.

### ~~GAP-045: FX Module — Sanctions List Not Automatically Updated~~ (RESOLVED Sprint 3.2 patch)
- **Identified**: Sprint 3.2
- **Status**: Resolved — Sprint 3.2 patch
- **Description**: `SanctionsListAdapter` rewritten with scheduled OFAC CSL (Consolidated Screening List) refresh via `@Scheduled` cron (default: daily at 2am, configurable via `nexuspay.fx.compliance.sanctions-refresh-cron`). Fetches from `data.trade.gov` CSL CSV endpoint. Merges OFAC country codes with static baseline list. Falls back to static list if OFAC is unreachable. High-risk countries now configurable via `nexuspay.fx.compliance.high-risk-countries`.

### ~~GAP-046: Routing — No Real-Time Strategy Performance Metrics Dashboard~~ (RESOLVED Sprint 3.3 patch)
- **Identified**: Sprint 3.3
- **Status**: Resolved — Sprint 3.3 patch
- **Description**: 12-panel Grafana dashboard (`docker/config/grafana/dashboards/routing-engine.json`) auto-provisioned via file-based provisioning. Panels: routing decisions/sec by strategy, auth rate by PSP, decision latency percentiles (p50/p95/p99), cascade depth distribution, cascade trigger rate gauge, PSP latency p95, circuit breaker state timeline, routing failures by reason, A/B test traffic split, cost per transaction by PSP, PSP selection distribution, decline code heatmap. Template variables for `$psp` and `$strategy` filtering. All metrics prefixed `nexuspay_routing_*`.

### ~~GAP-047: Routing — A/B Test Statistical Significance Not Calculated~~ (RESOLVED Sprint 3.3 patch)
- **Identified**: Sprint 3.3
- **Status**: Resolved — Sprint 3.3 patch
- **Description**: Two-proportion z-test implemented in `RoutingAbTestService`. Computes z-score, p-value (Taylor series normal CDF approximation — no external math library), and confidence intervals at configurable confidence level (default 95%). `AbTestSummary` extended with `groupAAuthRate`, `groupBAuthRate`, `zScore`, `pValue`, `confidenceInterval`, `isStatisticallySignificant`, `winner`. In-memory ConcurrentHashMap-based outcome counters keyed by `abTestId:group`, updated via `recordOutcome()`. REST endpoint response includes all statistical fields.

### ~~GAP-048: Routing — Circuit Breaker State Not Persisted Across Restarts~~ (RESOLVED Sprint 3.3 patch)
- **Identified**: Sprint 3.3
- **Status**: Resolved — Sprint 3.3 patch
- **Description**: Full circuit breaker state machine implemented in `CircuitBreakerManager` service. State transitions: CLOSED → OPEN (failure rate exceeds threshold), OPEN → HALF_OPEN (cooldown elapsed via `@Scheduled` checker), HALF_OPEN → CLOSED (all probe requests succeed) or HALF_OPEN → OPEN (any probe fails). Configurable via `RoutingProperties.CircuitBreakerProperties`: `failureRateThreshold` (0.50), `failureCountThreshold` (10), `cooldownSeconds` (60), `probeRequests` (3), `checkIntervalMs` (5000). `PspHealthTracker` delegates to `CircuitBreakerManager` for all circuit breaker operations. REST endpoints for viewing and force-setting circuit breaker state (`GET/POST /v1/routing/circuit-breakers/{pspConnector}`).

### ~~GAP-049: Routing — Fee Model Lacks Card-Brand-Specific Pricing~~ (RESOLVED Sprint 3.3 patch)
- **Identified**: Sprint 3.3
- **Status**: Resolved — Sprint 3.3 patch
- **Description**: `PspFeeModel` extended with `cardBrand`, `cardType`, and `isDomestic` fields for granular fee pricing. Specificity scoring (0–3) based on how many card attributes are specified. `PspFeeRepository.findBestMatch()` default method filters effective models by PSP, currency, date, and card attributes, then selects the highest-specificity match. `PspFeeModelEntity` updated with `card_brand`, `card_type`, `is_domestic` columns. Migration `V3012__add_card_brand_to_psp_fee_models.sql` adds columns, updates unique constraint, creates index, and seeds card-specific fee data (Stripe AMEX surcharge, Adyen domestic Visa debit discount, Adyen international Visa credit premium). REST fee endpoints and DTOs extended with card-brand fields and specificity in responses.

---

## Gap Resolution Timeline

| Phase | Gaps Addressed |
|-------|---------------|
| Sprint 1.4 | GAP-008 (partial), GAP-022 |
| Sprint 1.5 | GAP-013 |
| Sprint 1.6 | GAP-009, GAP-010, GAP-016, GAP-025 |
| Sprint 1.7 | GAP-005, GAP-006, GAP-007, GAP-014, GAP-017, GAP-019, GAP-030, GAP-031 |
| Sprint 2.1 (complete) | GAP-001 (RLS), GAP-003 (Vault) |
| Sprint 2.2 (complete) | GAP-011 (Debezium CDC), GAP-012 (partial — event upcaster chain, fully resolved Sprint 3.4) |
| Sprint 2.3 (complete) | GAP-023 (partial — reconciliation engine) |
| Sprint 2.4 (complete) | GAP-032 (dispute management — new) |
| Sprint 2.5a (complete) | GAP-034, GAP-035, GAP-036 (billing module — new) |
| Sprint 2.5b (complete) | GAP-034 (real payment), GAP-036 (Kafka events), GAP-037, GAP-038 (new) |
| Sprint 2.7 (complete) | GAP-020 (metrics export — full observability stack) |
| Sprint 3.1 (complete) | GAP-039, GAP-040, GAP-041 (fraud module — new gaps identified) |
| Sprint 3.2 (complete) | GAP-042, GAP-043, GAP-044, GAP-045 (FX module — identified and resolved) |
| Sprint 3.3 (complete) | GAP-046, GAP-047, GAP-048, GAP-049 (routing module — identified and resolved) |
| Sprint 3.4 (complete) | GAP-012 (full Schema Registry with Avro migration) |
| Phase 2 (remaining) | GAP-002, GAP-004, GAP-008, GAP-015, GAP-018, GAP-021, GAP-026, GAP-027 |

## Summary

- **Total gaps tracked**: 49
- **Resolved**: 34 (GAP-001, 003, 005, 006, 007, 009, 010, 011, 012, 013, 014, 016, 017, 019, 020, 022, 025, 030, 031, 034, 036, 042, 043, 044, 045, 046, 047, 048, 049 + partial GAP-008)
- **Partially Addressed**: 2 (GAP-023, GAP-032)
- **Open/Deferred**: 16 (GAP-002, GAP-004, GAP-008, GAP-015, GAP-018, GAP-021, GAP-024, GAP-026, GAP-027, GAP-028, GAP-029, GAP-033, GAP-035, GAP-037, GAP-038, GAP-039, GAP-040, GAP-041)
- **Accepted for Phase 1/2**: GAP-024, GAP-028, GAP-029, GAP-038
