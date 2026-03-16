# Changelog

All notable changes to NexusPay are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased] — Phase 3 (v0.3.0)

### Added

**Sprint 3.1 — Fraud Prevention**
- New `fraud` Gradle module with hexagonal architecture (12th module)
- Rules engine with 5 rule types: VELOCITY, AMOUNT_THRESHOLD, GEO_RESTRICTION, BIN_CHECK, DEVICE_FINGERPRINT
- Three-phase evaluation pipeline: pre-auth rules → scoring → decision (ALLOW/REVIEW/BLOCK)
- External FRM provider integration: Sift (primary) + Signifyd (fallback) with fallback chain
- Weighted risk scoring aggregator (configurable native vs FRM weights, default 60/40)
- Device fingerprint matching with reputation scoring (0-100), flagging, and sighting tracking
- A/B testing support for rules with configurable traffic splitting
- Valkey-cached rule sets per tenant with TTL-based invalidation
- Transactional outbox events: FraudCheckPassed/Failed/Review, RuleTriggered, FraudRule CRUD, FraudReview Approved/Rejected
- 4 Flyway migrations with RLS policies on all fraud tables
- 3 Kafka topics (assessments, events, rules changelog) + DLT
- REST API: rule CRUD (`/v1/fraud/rules`), assessment review queue, approve/reject
- Resilience4j circuit breakers for Sift and Signifyd FRM providers
- Full persistence layer with JPA entities and domain model mapping

**Sprint 3.2 — Cross-Border & FX**
- FX rate management with ECB (free, primary) and Open Exchange Rates (configurable) providers
- Valkey-backed FX rate cache with stale-serving on provider outage (cache-aside pattern, 1h fresh / 24h stale TTL)
- FX rate locking for payment lifecycle (lock at intent, consume at settlement, auto-refresh on expiry)
- Currency conversion service with merchant-configurable markup (basis points) and settlement currency
- Multi-leg journal entries for cross-currency settlements: presentment DR/CR, FX conversion, gain/loss balancing
- FX gain/loss tracking per currency pair per tenant (realized + unrealized positions)
- Currency-aware PSP routing: presentment, settlement, and DCC capability queries
- Cross-border compliance: configurable sanctions list (KP, IR, SY, CU), high-risk country flagging, regulatory reporting threshold
- Merchant currency preferences API: settlement currency, auto-convert, FX markup, rate provider, lock duration
- REST API: `/v1/fx/rates/{from}/{to}`, `/v1/fx/locks`, `/v1/fx/routing`, `/v1/fx/compliance/validate`, `/v1/merchant/currency-preferences`
- 4 Flyway migrations with RLS policies: fx_rate_locks, currency_capabilities, merchant_currency_prefs, fx_gain_loss_accounts
- 3 Kafka topics: nexuspay.fx.rates, nexuspay.fx.conversions, nexuspay.fx.locks + DLT
- Outbox relay extended with FX aggregate type → topic mappings
- PSP currency capabilities seeded for Stripe, Adyen, and dummy_connector

**Sprint 3.2 Gap Patches**
- GAP-042: `FxRateStreamingService` — scheduled FX rate streaming to Kafka (5min interval, configurable)
- GAP-043: ECB adapter rewritten with primary XML parsing (eurofxref-daily.xml, DOM parser, XXE protection) and SDMX CSV fallback
- GAP-044: DCC (Dynamic Currency Conversion) flow — `DccOffer` domain model, `DynamicCurrencyConversionService` with rate disclosure, markup, consent tracking; REST endpoints for create/accept/decline offers
- GAP-045: Automated sanctions list updates — `SanctionsListAdapter` with scheduled OFAC CSL refresh (daily, configurable), static fallback, configurable high-risk countries

**Sprint 3.3 — Smart Routing Engine**
- Pluggable routing strategy framework with `RoutingStrategy` interface and 6 implementations:
  - `CostBasedStrategy` — selects cheapest PSP using `PspFeeModel` with 4 fee types (per-tx, percentage, blended, interchange++)
  - `SuccessRateStrategy` — selects highest auth rate PSP with segmented rates (brand/type/country) and overall fallback
  - `LatencyBasedStrategy` — selects lowest p95 latency PSP with inverted normalization scoring
  - `RoundRobinStrategy` — atomic counter cycling through eligible candidates
  - `WeightedStrategy` — weighted random selection with cascade ordered by weight descending
  - `FailoverStrategy` — deterministic primary/fallback ordering
- `RoutingEngine` central orchestrator: loads tenant config, filters by currency/health/fraud, applies strategy, limits cascade depth, persists audit trail
- Cascade failover with `CascadeService`: soft decline codes (DO_NOT_HONOR, INSUFFICIENT_FUNDS, ISSUER_UNAVAILABLE) trigger next PSP; hard decline codes (STOLEN_CARD, FRAUD) stop cascade immediately
- PSP health tracking via `PspHealthTracker` with Valkey-backed sliding window metrics (auth rate, latency percentiles, circuit breaker state)
- Segmented auth rate tracking via `AuthRateTracker` with Valkey 7-day sliding window, keyed by brand:type:country
- A/B testing for routing strategies via `RoutingAbTestService` — traffic splitting, group assignment, test summary aggregation
- Tenant routing configuration CRUD with `RoutingConfig` (strategy, PSP list, cascade depth, A/B test settings)
- `PspFeeModel` domain model with fee calculation for all 4 fee types and effective date ranges
- Full audit trail: `RoutingDecision` records strategy used, all candidate scores, cascade order, A/B test group, decision latency
- REST API: `/v1/routing/configs` (CRUD), `/v1/routing/decisions` (audit), `/v1/routing/fees` (fee model CRUD), `/v1/routing/health` (PSP health), `/v1/routing/simulate` (dry-run), `/v1/routing/ab-tests` (A/B test management)
- 3 Flyway migrations with RLS policies: `psp_fee_models`, `routing_configs`, `routing_decisions` — seeded with default config and fee models for Stripe/Adyen/dummy_connector
- 4 Kafka topics: `nexuspay.routing.decisions` (12p/30d), `nexuspay.routing.cascades` (12p/30d), `nexuspay.routing.failures` (6p/90d), `nexuspay.routing.DLT` (1p/30d)
- Domain events: `RouteSelected`, `RouteFailed`, `CascadeTriggered`
- OutboxRelay migrated from `Map.of()` (10-entry limit) to `Map.ofEntries()` to accommodate routing aggregate types
- `RoutingProperties` configuration: cascade soft/hard decline codes, health thresholds, latency tracking window, A/B test min sample size and confidence level
- Full persistence layer: 3 JPA entities, 3 Spring Data repositories, 3 hexagonal adapter implementations with JSONB serialization
- New gaps identified: GAP-046 (no routing metrics dashboard), GAP-047 (A/B test statistical significance), GAP-048 (circuit breaker recovery), GAP-049 (card-brand-specific fees)

## [0.2.0] — 2026-03-15 (Phase 2)

### Added

**Sprint 2.1 — Multi-Tenancy & Security**
- PostgreSQL Row-Level Security (RLS) policies on all tables
- HashiCorp Vault integration for secrets management (Spring Cloud Vault)
- Tenant-aware security context and RLS filter
- Vault health indicator

**Sprint 2.2 — Event Infrastructure Upgrade**
- Debezium CDC via Kafka Connect for outbox relay (replaces polling)
- Temporal workflow engine integration (PaymentWithRetryWorkflow)
- Temporal durable orchestration with retry, signal, and timeout
- Temporal UI and PostgreSQL containers in Docker Compose
- Kafka Connect container with Debezium outbox event router

**Sprint 2.3 — Reconciliation Engine**
- Automated 3-way reconciliation matching (PSP, ledger, bank)
- Settlement file ingestion framework
- Exception management for reconciliation mismatches
- Daily reconciliation job with configurable tolerance

**Sprint 2.4 — Dispute Management**
- Dispute lifecycle state machine (OPENED → EVIDENCE_NEEDED → EVIDENCE_SUBMITTED → WON/LOST/EXPIRED)
- Chargeback reserve ledger entries (DR reserve, CR merchant receivables)
- Evidence collection and storage (local/S3)
- Auto-representment service with eligibility evaluation
- Dispute network port stubs (Verifi/Ethoca)
- Configurable auto-submit threshold and evidence deadline

**Sprint 2.5a — Subscription Billing Core**
- Product catalog with multi-pricing models (flat, per-unit, tiered, volume)
- Subscription lifecycle management (create, cancel, pause, resume, plan change)
- Trial period support with automatic conversion
- Invoice generation and proration service
- Daily renewal scheduler and trial expiration scheduler

**Sprint 2.5b — Subscription Billing Advanced**
- Smart retry dunning with card-type aware timing (debit/credit/prepaid)
- Customer timezone scheduling and weekend avoidance
- Real payment integration (PaymentOrchestrationAdapter → HyperSwitch)
- Kafka event publishing for all billing lifecycle transitions (17 event types)
- BillingPaymentEventListener for async payment result handling
- Configurable dunning properties (retry schedule, grace period, optimal hour)

**Sprint 2.7 — Production Observability**
- 10+ custom Micrometer metrics (PaymentMetrics, LedgerMetrics, BillingMetrics, InfrastructureMetrics)
- Prometheus scraping with 6 alert rules (HighPaymentFailureRate, CircuitBreakerOpen, OutboxLagHigh, DatabaseConnectionPoolSaturation, KafkaConsumerLagHigh, HighP99Latency)
- 4 Grafana dashboards (Payment Operations, Ledger Health, Infrastructure, Subscriptions & Billing)
- AlertManager with severity-based routing
- SLO/SLI recording rules (availability 99.9%, latency p99 <2s, error rate <0.1%, payment success >95%)
- OutboxLagMonitor and KafkaConsumerHealthIndicator

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
