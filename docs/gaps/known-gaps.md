# NexusPay Known Gaps Analysis

Last updated: 2026-06-27 (TEST-6 — Testability Program close-out)

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

### GAP-050: Checkout SDK — No card-frame.html CDN Hosting
- **Identified**: Sprint 3.5
- **Status**: Open
- **Description**: The PCI-compliant `card-frame.html` iframe is referenced via a relative path. In production, it must be served from a NexusPay CDN with proper CSP headers. Currently only works in local development via Vite dev server.
- **Risk**: SDK cannot be used by merchants until the iframe HTML is hosted and the iframe src URL is configurable.
- **Resolution**: Phase 4 — deploy card-frame.html to CDN (CloudFront/Fastly), configure `NexusPay({ iframeSrc })` option.

### GAP-051: Checkout SDK — Apple Pay / Google Pay Not Sandbox-Tested
- **Identified**: Sprint 3.5
- **Status**: Open
- **Description**: Apple Pay and Google Pay handlers are implemented with correct API structures but require real merchant IDs and sandbox credentials to validate end-to-end flows. Currently tested with mocked `ApplePaySession` and `PaymentsClient`.
- **Risk**: Payment token mapping or merchant validation may fail in real Apple/Google sandbox environments.
- **Resolution**: Obtain Apple Pay sandbox merchant ID and Google Pay test merchant ID for integration testing.

### GAP-052: Checkout SDK — BNPL Provider SDK Versions Unpinned
- **Identified**: Sprint 3.5
- **Status**: Open
- **Description**: Klarna, Afterpay, and Affirm SDKs are loaded dynamically from provider CDNs without version pinning. SDK breaking changes could silently break the BNPL flow.
- **Risk**: Provider SDK updates could cause runtime failures without warning.
- **Resolution**: Pin SDK versions in script URLs and add integration smoke tests.

### GAP-053: Analytics Module — No Integration Tests for Kafka Consumers
- **Identified**: Sprint 3.6
- **Status**: Open
- **Description**: Analytics Kafka consumers (PaymentEventAnalyticsConsumer, RoutingEventAnalyticsConsumer, FraudEventAnalyticsConsumer) are not covered by integration tests with embedded Kafka / Testcontainers. Only unit tests with mocked repositories verify the consumer logic.
- **Risk**: Deserialization issues, offset management, or consumer group rebalancing problems would not be caught before deployment.
- **Resolution**: Phase 4 — add Testcontainers-based integration tests that publish events to embedded Kafka and verify rollup table population end-to-end.

### GAP-054: Analytics Module — Consumers Use JSON, Not Avro
- **Identified**: Sprint 3.6
- **Status**: Accepted for Phase 3
- **Description**: Analytics Kafka consumers deserialize events as JSON (`ConsumerRecord<String, String>`), matching the existing consumer pattern. Sprint 3.4 added Avro support with `DualFormatDeserializer`, but analytics consumers were not migrated.
- **Risk**: Analytics consumers do not benefit from Avro schema validation. If producers switch to Avro-only, analytics consumers will break.
- **Resolution**: Phase 4 — migrate analytics consumers to `DualFormatDeserializer` for seamless JSON/Avro consumption.

### GAP-055: Analytics Module — No Grafana Analytics Dashboard
- **Identified**: Sprint 3.6
- **Status**: Open
- **Description**: The analytics module exposes REST API endpoints but has no Grafana dashboard for visualizing auth rates, PSP health trends, revenue, or decline patterns. Other modules (routing, observability, billing) have provisioned Grafana dashboards.
- **Risk**: Operations teams must use raw API calls to view analytics data. No visual alerting for PSP health degradation.
- **Resolution**: Phase 4 — add `docker/config/grafana/dashboards/analytics.json` with panels for auth rate trends, PSP health scores, revenue breakdowns, and decline heatmaps.

### GAP-056: Vault Module — HSM Encryption Not Implemented
- **Identified**: Sprint 4.1
- **Status**: Open
- **Description**: `HsmEncryptionAdapter` is a placeholder that throws `UnsupportedOperationException`. Production deployment requires CloudHSM (AWS) or Thales Luna HSM integration for PCI DSS compliance. Software AES-256-GCM adapter is functional for dev/test.
- **Risk**: PCI DSS Level 1 compliance requires HSM-managed encryption keys. Software encryption is insufficient for production PAN storage.
- **Resolution**: Phase 4 production hardening — implement CloudHSM PKCS#11 or Thales Luna integration.

### GAP-057: Vault Module — Network Token Adapters Are Stubs
- **Identified**: Sprint 4.1
- **Status**: Open
- **Description**: Visa VTS, Mastercard MDES, and Amex token service adapters return simulated responses. Real network token enrollment requires 3-6 month business certification per card network.
- **Risk**: Network tokenization benefits (2-5% auth rate improvement, reduced interchange) not realized until real adapters are active.
- **Resolution**: Phase 4/5 — implement real adapters as certifications complete. Visa VTS enrollment should be initiated first.

### GAP-058: Vault Module — Migration Ingestion Not Implemented
- **Identified**: Sprint 4.1
- **Status**: Open
- **Description**: `VaultMigrationService` creates migration records but does not process card imports from source providers (Spreedly, Stripe, Braintree). Needs Kafka consumer or batch job for actual card ingestion.
- **Risk**: Merchants cannot migrate existing card-on-file data into NexusPay vault.
- **Resolution**: Sprint 4.2 or later — add migration ingestion consumer with provider-specific API clients.

### GAP-059: Vault Module — Key Rotation Background Job Not Implemented
- **Identified**: Sprint 4.1
- **Status**: Open
- **Description**: `EncryptionPort` supports multiple key IDs and `VaultRepository.findCardsByEncryptionKeyId()` enables querying by key, but no scheduled job exists to re-encrypt cards from old keys to the current key.
- **Risk**: Key rotation requires manual intervention. Compromised keys cannot be rotated automatically.
- **Resolution**: Sprint 4.2 — add `KeyRotationJobService` with `@Scheduled` background re-encryption.

### GAP-060: Vault Module — Not Independently Deployable
- **Identified**: Sprint 4.1
- **Status**: Accepted for Phase 4
- **Description**: `VaultSecurityConfig` prepares for PCI isolation, but the vault module runs inside the monolith. Separate Spring Boot entry point, independent database schema, and dedicated network boundary needed for true PCI scope segmentation.
- **Risk**: PCI DSS audit scope includes the entire monolith rather than just the vault service.
- **Resolution**: Phase 5 — extract vault module to standalone service with mTLS and dedicated database.

### GAP-061: Marketplace Module — KYC Provider Stub Only
- **Identified**: Sprint 4.2
- **Status**: Deferred to Phase 5
- **Description**: `KycProviderStubAdapter` returns simulated KYC responses. Real provider integration (Onfido, Persona, Jumio) requires API contracts, webhook handling for async verification updates, document upload workflows, and beneficial ownership verification for businesses.
- **Risk**: Cannot onboard real sub-merchants without identity verification.
- **Resolution**: Sprint 4.2b or Phase 5 — integrate real KYC provider with webhook listener.

### GAP-062: Marketplace Module — Payout Execution Stub Only
- **Identified**: Sprint 4.2
- **Status**: Deferred to Phase 5
- **Description**: `PayoutExecutionStubAdapter` simulates successful payouts. Real bank transfer and card push execution requires integration with banking rails (ACH, SEPA, Faster Payments) or card network push-to-card APIs.
- **Risk**: Cannot disburse real funds to connected accounts.
- **Resolution**: Phase 5 — integrate with banking/card push providers.

### GAP-063: Marketplace Module — No Ledger Integration for Split Payments
- **Identified**: Sprint 4.2
- **Status**: Deferred to Phase 4
- **Description**: Split payments calculate distributions but do not create actual ledger entries (DR customer liability, CR merchant receivable, CR platform revenue). The ledger module integration is needed for true double-entry accounting of splits.
- **Risk**: Split payment amounts are tracked but not reflected in the general ledger.
- **Resolution**: Sprint 4.2b — wire SplitPaymentService to ledger module for journal entry creation.

### GAP-064: Marketplace Module — No 1099-K Reporting
- **Identified**: Sprint 4.2
- **Status**: Deferred to Phase 5
- **Description**: Acceptance criteria require 1099-K reporting data collection for US connected accounts. Currently no tax reporting fields (SSN/EIN, aggregate gross amounts, transaction counts per year) are captured.
- **Risk**: Platform non-compliant with IRS reporting requirements for marketplace facilitators.
- **Resolution**: Phase 5 — add tax identity fields, annual reporting aggregation, and 1099-K generation.

### GAP-065: Marketplace Module — No Integration Tests
- **Identified**: Sprint 4.2
- **Status**: Deferred to Phase 4
- **Description**: Only unit tests and `@WebMvcTest` controller tests exist. No end-to-end integration tests with PostgreSQL, Kafka, and Flyway. Testcontainers-based integration tests needed to verify RLS policies, outbox relay, and full request lifecycle.
- **Risk**: RLS policy correctness, Flyway migration execution, and cross-module wiring are untested.
- **Resolution**: Sprint 4.3 — add Testcontainers-based integration tests.

### GAP-066: B2B Module — Card Issuing Provider Stub Only
- **Identified**: Sprint 4.3
- **Status**: Deferred to Phase 5
- **Description**: `CardIssuingStubAdapter` returns simulated card issuance responses. Real provider integration (Marqeta, Lithic, Stripe Issuing) requires API contracts, webhook handling for transaction notifications, spend control enforcement at the provider level, and card lifecycle management.
- **Risk**: Cannot issue real virtual cards for B2B payments.
- **Resolution**: Phase 5 — integrate with card issuing provider (Marqeta recommended for B2B due to just-in-time funding).

### GAP-067: B2B Module — Vendor Payment Execution Stub Only
- **Identified**: Sprint 4.3
- **Status**: Deferred to Phase 5
- **Description**: `VendorPaymentExecutionStubAdapter` simulates successful vendor disbursements. Real execution requires integration with ACH/Nacha files, SWIFT wire transfers, check printing services, and vendor bank account verification.
- **Risk**: Cannot disburse real funds to vendors.
- **Resolution**: Phase 5 — integrate with payment rail providers (Modern Treasury, Column, or direct bank APIs).

### GAP-068: B2B Module — No Approval Workflow for POs and Vendor Payments
- **Identified**: Sprint 4.3
- **Status**: Deferred to Phase 4
- **Description**: PO approval and vendor payment approval are single-step operations. Production B2B workflows typically require multi-level approval chains (e.g., manager → finance → CFO for amounts above threshold), delegation rules, and approval deadline enforcement.
- **Risk**: Insufficient controls for large B2B transactions.
- **Resolution**: Sprint 4.4 or Phase 5 — integrate with workflow module for configurable approval chains.

### GAP-069: B2B Module — No Ledger Integration
- **Identified**: Sprint 4.3
- **Status**: Deferred to Phase 4
- **Description**: B2B payment flows (PO approval, invoice payment, vendor disbursement) do not create ledger journal entries. Double-entry accounting entries (DR accounts payable, CR vendor payable, etc.) are needed for complete financial tracking.
- **Risk**: B2B transactions not reflected in the general ledger.
- **Resolution**: Sprint 4.4 — wire B2bInvoiceService and VendorPaymentService to ledger module.

### GAP-070: B2B Module — No Integration Tests
- **Identified**: Sprint 4.3
- **Status**: Deferred to Phase 4
- **Description**: Only unit tests and `@WebMvcTest` controller tests exist. No end-to-end integration tests with PostgreSQL, Kafka, and Flyway. Testcontainers-based integration tests needed to verify RLS policies, JSONB serialization, outbox relay, and full request lifecycle.
- **Risk**: RLS policy correctness, Flyway migration execution, JSONB ↔ domain mapping, and cross-module wiring are untested.
- **Resolution**: Sprint 4.4 — add Testcontainers-based integration tests.

### GAP-071: Workflow Module — Graph-to-Temporal Compilation Not Implemented
- **Identified**: Sprint 4.4
- **Status**: Open
- **Description**: `WorkflowExecutionService.triggerWorkflow()` creates an execution record with a placeholder Temporal workflow ID but does not compile the visual DAG graph into actual Temporal workflow activities. The graph-to-Temporal compilation step (traversing nodes/edges, mapping node types to activities, handling conditions and splits) is not implemented.
- **Risk**: Workflow executions are recorded but no actual workflow logic is executed. The visual builder is a design tool only until compilation is implemented.
- **Resolution**: Phase 5 — implement `GraphCompiler` that translates the DAG into Temporal workflow/activity definitions and dispatches to Temporal worker.

### GAP-072: Workflow Module — No JSONLogic Expression Evaluator
- **Identified**: Sprint 4.4
- **Status**: Open
- **Description**: Condition nodes store JSONLogic expressions in the `conditionExpression` field, and `WorkflowBuilderProperties` configures the expression engine as JSONLogic. However, no JSONLogic evaluator library is integrated. Expression evaluation during execution is not implemented.
- **Risk**: Conditional branching in workflows cannot be evaluated at runtime.
- **Resolution**: Phase 5 — integrate a JSONLogic Java library (e.g., `io.github.jamsesso:json-logic-java`) and wire it into the graph compiler's condition node handler.

### GAP-073: Workflow Module — Webhook Trigger Ingestion Not Implemented
- **Identified**: Sprint 4.4
- **Status**: Open
- **Description**: `WebhookTriggerService` creates and manages webhook triggers with URL paths and HMAC secrets, but no inbound webhook endpoint exists to receive HTTP POST requests, verify HMAC signatures, and dispatch to `WorkflowExecutionService.triggerWorkflow()`.
- **Risk**: External systems cannot trigger workflows via webhooks despite triggers being configured.
- **Resolution**: Phase 5 — add `POST /v1/webhooks/workflows/{urlPathSuffix}` endpoint with HMAC verification and payload forwarding.

### GAP-074: Workflow Module — No Graph Validation (Cycle Detection, Connectivity)
- **Identified**: Sprint 4.4
- **Status**: Open
- **Description**: `WorkflowDefinition.publish()` validates that at least one node exists, but does not validate graph structure: no cycle detection, no reachability check from trigger node, no validation that END nodes are terminal, and no orphan node detection.
- **Risk**: Invalid workflow graphs (cycles, disconnected nodes) can be published and may cause infinite loops or unreachable code paths during execution.
- **Resolution**: Phase 5 — add `GraphValidator` with topological sort cycle detection, connectivity analysis, and terminal node validation before publish.

### GAP-075: Workflow Module — No Integration Tests
- **Identified**: Sprint 4.4
- **Status**: Deferred to Phase 5
- **Description**: Only unit tests and `@WebMvcTest` controller tests exist. No end-to-end integration tests with PostgreSQL, Kafka, and Flyway. Testcontainers-based integration tests needed to verify RLS policies, JSONB serialization of graph data, outbox relay, version snapshot roundtrips, and full request lifecycle.
- **Risk**: RLS policy correctness, Flyway migration execution, JSONB ↔ domain graph mapping, and cross-module wiring are untested.
- **Resolution**: Phase 5 — add Testcontainers-based integration tests.

---

## Testability Program (P2 nicety) — Deferred

The TEST-1..6 testability program added forced outcomes, a webhook test-event trigger,
delivery-body/signature visibility, a connectivity ping, dispute/customer/payment-method/
mandate test fixtures, the 3DS/async/review-hold non-failure outcomes (TEST-6 A3/A4/A5),
and `request_id` in the error envelope (TEST-6 F3). The items below were considered and
**deliberately deferred**. Every one is a **P2 testability NICETY — NOT a security or
money-safety gap**: nothing here weakens the gate, the capture-hold control, a tenant/PCI
boundary, or any production correctness. They were deferred because each needs a
semi-orthogonal platform seam that is out of scope for a testability batch.

### GAP-076 (F1): No payments / refunds LIST (read-model) — ✅ DELIVERED 2026-06-28 (ADR-064)
- **Identified**: TEST-4 (re-confirmed TEST-6)
- **Status**: ✅ **DELIVERED** — `GET /v1/payments` + `GET /v1/refunds` now backed by a durable, tenant+livemode-scoped
  projection (V4041 `payments`/`refunds` tables) written BEST-EFFORT at the gateway + async-webhook hooks (a
  projection write can never fail/block/rollback a charge; never a source of truth). Forward-fill (lists rows created
  after 2026-06-28; `GET /{id}` still serves older; a live async settlement may lag by the webhook window). SDK
  `listPayments`/`listRefunds`. See ADR-064. *(Original deferral rationale retained below for history.)*
- **Original status (now superseded)**: Deferred — P2 testability nicety (NOT a security/money gap)
- **What it is**: There is no `GET /v1/payments` / `GET /v1/refunds` list endpoint. An integrator
  cannot enumerate the payments/refunds they created in a test session.
- **Why deferred**: No payment/refund **read-model** exists. Live state lives inside HyperSwitch;
  test state lives only in the in-memory singleton `MockPaymentGatewayPort` maps (`payments` /
  `refunds` `ConcurrentHashMap`s), which are neither durable nor queryable across instances. A
  by-id `getPayment`/`getRefund` works, but a *list* has no backing store.
- **What it would take**: a durable **projection table** populated from the payment/refund
  lifecycle (created/succeeded/failed/refunded events) with tenant scoping + pagination — a
  semi-orthogonal platform feature, not a test seam. Deferred pending a product decision on a
  first-class list/read API.

### GAP-077 (F4): No per-tenant test-data reset
- **Identified**: TEST-6
- **Status**: Deferred — P2 testability nicety (NOT a security/money gap)
- **What it is**: An integrator cannot reset (clear) the test payments/refunds they created so a
  fresh test run starts clean.
- **Why deferred**: `MockPaymentGatewayPort` is a **global `@Component` singleton** with NO
  per-tenant partitioning of its maps. A tenant-scoped reset is impossible (and unsafe — it would
  clear other tenants' test data) until the mock is made **tenant-aware** first. This is the **same
  blocker as F1/GAP-076**.
- **What it would take**: partition the mock's stores by tenant (keyed maps or a per-tenant store)
  — then a tenant-scoped reset endpoint becomes safe. Deferred behind the tenant-aware-mock work.

### GAP-078 (F5): No test clocks (control "now")
- **Identified**: TEST-6
- **Status**: Deferred — P2 testability nicety (NOT a security/money gap)
- **What it is**: No way to control the platform's notion of "now" to exercise time-dependent flows
  (expiry windows, dunning schedules, mandate/age checks) deterministically in test mode.
- **Why deferred**: the platform calls `Instant.now()` **directly** throughout (e.g. mock
  `createPayment`, `CaptureHoldService`, session/key expiry) with **no injectable `Clock` bean**. A
  test clock needs an invasive `Clock` retrofit across many call sites. This is the **largest item
  and the lowest P2 value** of the set.
- **What it would take**: introduce a `Clock` bean, replace `Instant.now()` call sites with
  `Instant.now(clock)`, and add a test-mode control to advance/freeze it. A broad mechanical change
  with real regression surface — deferred.

### GAP-079 (F6): No idempotency inspect / clear
- **Identified**: TEST-6
- **Status**: Deferred — P2 testability nicety (NOT a security/money gap)
- **What it is**: No endpoint to inspect or clear a cached idempotency key during a test run (e.g.
  to re-send a "duplicate" without minting a new key).
- **Why deferred**: the `IdempotencyFilter` caches caller-scoped responses in Valkey (GAP-010,
  `SET NX EX 60` + 24h response cache). An inspect/clear test endpoint is **feasible but niche** and
  not trivially in scope for TEST-6.
- **What it would take**: a small test-mode-only endpoint that reads/deletes the caller-scoped
  Valkey idempotency entries. Low effort but low demand — deferred unless it becomes trivially in
  scope.

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
| Sprint 3.5 (complete) | GAP-050, GAP-051, GAP-052 (checkout SDK — new gaps identified) |
| Sprint 3.6 (complete) | GAP-053, GAP-054, GAP-055 (analytics module — new gaps identified) |
| Sprint 4.1 (complete) | GAP-056, GAP-057, GAP-058, GAP-059, GAP-060 (vault module — new gaps identified) |
| Sprint 4.2 (complete) | GAP-061, GAP-062, GAP-063, GAP-064, GAP-065 (marketplace module — new gaps identified) |
| Sprint 4.3 (complete) | GAP-066, GAP-067, GAP-068, GAP-069, GAP-070 (B2B module — new gaps identified) |
| Sprint 4.4 (complete) | GAP-071, GAP-072, GAP-073, GAP-074, GAP-075 (workflow builder — new gaps identified) |
| Phase 2 (remaining) | GAP-002, GAP-004, GAP-008, GAP-015, GAP-018, GAP-021, GAP-026, GAP-027 |

## Summary

- **Total gaps tracked**: 75
- **Resolved**: 34 (GAP-001, 003, 005, 006, 007, 009, 010, 011, 012, 013, 014, 016, 017, 019, 020, 022, 025, 030, 031, 034, 036, 042, 043, 044, 045, 046, 047, 048, 049 + partial GAP-008)
- **Partially Addressed**: 2 (GAP-023, GAP-032)
- **Open/Deferred**: 42 (GAP-002, GAP-004, GAP-008, GAP-015, GAP-018, GAP-021, GAP-024, GAP-026, GAP-027, GAP-028, GAP-029, GAP-033, GAP-035, GAP-037, GAP-038, GAP-039, GAP-040, GAP-041, GAP-050, GAP-051, GAP-052, GAP-053, GAP-054, GAP-055, GAP-056, GAP-057, GAP-058, GAP-059, GAP-060, GAP-061, GAP-062, GAP-063, GAP-064, GAP-065, GAP-066, GAP-067, GAP-068, GAP-069, GAP-070, GAP-071, GAP-072, GAP-073, GAP-074, GAP-075)
- **Accepted for Phase 1/2/3/4/5**: GAP-024, GAP-028, GAP-029, GAP-038, GAP-054, GAP-060, GAP-063, GAP-065, GAP-068, GAP-069, GAP-070, GAP-075
