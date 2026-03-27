# Changelog

All notable changes to NexusPay are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased] — Phase 4 (v0.4.0)

### Added

**Sprint 4.3 — B2B Payments**
- New `b2b` Gradle module with hexagonal architecture (16th module)
- Purchase order lifecycle management: DRAFT → SUBMITTED → APPROVED → INVOICED → PAID/CANCELLED
- Payment terms support: DUE_ON_RECEIPT, NET_30, NET_60, NET_90 with automatic due date calculation
- B2B invoice creation from approved POs with status cascade on payment
- Virtual card issuance with spend controls, MCC restrictions, freeze/cancel via stub issuing adapter
- Vendor payment disbursement with ACH, wire, virtual card, and check methods
- Vendor payment batching with auto-generated batch IDs
- Level 2/3 commercial card data enrichment for reduced interchange rates
- 4 domain models: PurchaseOrder, B2bInvoice, VirtualCard, VendorPayment
- 7 enums: PurchaseOrderStatus, PaymentTerms, VirtualCardType, VirtualCardStatus, VendorPaymentMethod, VendorPaymentStatus, InvoiceStatus
- JSONB storage for PO line items, text[] for virtual card MCC codes
- 4 inbound ports, 4 outbound ports, 5 application services + Level23DataEnricher
- 4 REST controllers with 14 endpoints, 8 DTOs
- Flyway migration V4003 with 4 tables, RLS policies, and `nexuspay_app` grants
- Transactional outbox for B2B events (13 event types, 4 aggregate types)
- Kafka topics: `nexuspay.b2b.events`, `nexuspay.b2b.DLT`
- OutboxRelay routing updated for PurchaseOrder, B2bInvoice, VirtualCard, VendorPayment aggregates
- Stub adapters for card issuing (Marqeta/Lithic/Stripe Issuing) and vendor payment execution
- 32 unit tests across 5 service tests + 1 controller test
- New gaps identified: GAP-066, GAP-067, GAP-068, GAP-069, GAP-070

**Sprint 4.2 — Marketplace & Platform Payments**
- New `marketplace` Gradle module with hexagonal architecture (15th module)
- Connected account onboarding with KYC verification integration (stub adapter)
- Account lifecycle management: onboard → verify → activate → suspend → close
- Split payment engine with rule resolution (PERCENTAGE, FIXED, REMAINDER)
- Platform fee calculation (percent + fixed) deducted before distribution
- Payout service with minimum threshold enforcement per connected account
- Payout scheduler with configurable interval (disabled by default)
- Payout execution via stub adapter (bank transfer and card push)
- 5 domain models: ConnectedAccount, SplitPayment, SplitRule, Payout, PlatformFee
- 7 enums: AccountState, KycStatus, SplitType, PayoutSchedule, PayoutStatus, PayoutMethod, SplitPaymentStatus
- 4 inbound ports, 4 outbound ports, 4 application services
- 3 REST controllers with 12 endpoints, 9 DTOs
- Flyway migration V4002 with 5 tables, RLS policies, and `nexuspay_app` grants
- Transactional outbox for marketplace events (9 event types, 3 aggregate types)
- Kafka topics: `nexuspay.marketplace.events`, `nexuspay.marketplace.DLT`
- OutboxRelay routing updated for ConnectedAccount, SplitPayment, Payout aggregates
- 25 unit tests across 4 service tests + 1 controller test
- New gaps identified: GAP-061, GAP-062, GAP-063, GAP-064, GAP-065

**Sprint 4.1 — Universal Card Vault & Network Tokenization**
- New `vault` Gradle module with hexagonal architecture (14th module)
- AES-256-GCM encryption at rest for PANs with 12-byte random IV, 128-bit auth tag
- EncryptionPort abstraction: software adapter (dev/test) vs HSM placeholder (production)
- SHA-256 fingerprint-based duplicate card detection per tenant
- VaultedCard domain model (`vc_` prefix) with encrypted PAN, BIN, brand, expiry
- VaultToken (`tok_xxx`) merchant-facing token for card references — PAN never exposed via API
- NetworkToken (`nt_` prefix) for Visa VTS, Mastercard MDES, and Amex provisioning
- Stub network token adapters (real enrollment requires 3-6 month certification)
- Cryptogram generation (TAVV/CAVV) for tokenized e-commerce transactions
- Vault-to-vault migration framework: PENDING → IN_PROGRESS → COMPLETED/FAILED lifecycle
- CardVaultService: Luhn validation, BIN-based brand detection (Visa/MC/Amex/Discover), fingerprint dedup, cascade delete
- Flyway migration V4001: vaulted_cards (BYTEA), network_tokens, vault_tokens, vault_migrations with RLS policies
- Transactional outbox events: CardVaulted, CardDeleted, NetworkTokenProvisioned, MigrationStarted
- Kafka topic: `nexuspay.vault.events` with dedicated consumer group
- REST API: POST/GET/DELETE /v1/vault/cards, POST network-tokens, POST cryptogram, POST/GET migrations
- VaultSecurityConfig for PCI DSS isolation preparation (extractable to standalone service)
- VaultProperties: encryption (provider, masterKey, keyId), network-tokens (visa/mc/amex enabled)
- New gaps identified: GAP-056 (HSM not implemented), GAP-057 (network token adapters are stubs), GAP-058 (migration ingestion not implemented), GAP-059 (key rotation job not implemented), GAP-060 (vault not independently deployable)

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

**Sprint 3.3 Gap Patches**
- GAP-046: Grafana routing metrics dashboard — 12-panel dashboard with template variables (`$psp`, `$strategy`): routing decisions/sec by strategy, auth rate by PSP, decision latency percentiles, cascade depth distribution, cascade trigger rate gauge, PSP latency p95, circuit breaker state timeline, routing failures by reason, A/B test traffic split, cost per transaction by PSP, PSP selection distribution, decline code heatmap. Auto-provisioned via Grafana file-based provisioning.
- GAP-047: A/B test statistical significance — two-proportion z-test implementation in `RoutingAbTestService`. Computes z-score, p-value (via Taylor series normal CDF approximation), and confidence intervals. `AbTestSummary` extended with `groupAAuthRate`, `groupBAuthRate`, `zScore`, `pValue`, `confidenceInterval`, `isStatisticallySignificant`, `winner`. In-memory ConcurrentHashMap-based outcome counters updated via `recordOutcome()`.
- GAP-048: Full circuit breaker state machine — `CircuitBreakerManager` service with CLOSED → OPEN → HALF_OPEN → CLOSED transitions. Configurable `failureRateThreshold` (0.50), `failureCountThreshold` (10), `cooldownSeconds` (60), `probeRequests` (3). Scheduled cooldown checker transitions OPEN breakers to HALF_OPEN. Probe-based recovery: all probes must succeed to close. REST endpoints: `GET/POST /v1/routing/circuit-breakers/{pspConnector}`. `PspHealthTracker` updated to delegate to `CircuitBreakerManager`. `RoutingProperties.CircuitBreakerProperties` added.
- GAP-049: Card-brand-specific fee pricing — `PspFeeModel` extended with `cardBrand`, `cardType`, `isDomestic` fields. Specificity scoring (0–3) for best-match selection. `PspFeeRepository.findBestMatch()` default method filters by card attributes and selects highest specificity. `PspFeeModelEntity` updated with new columns. Migration `V3012__add_card_brand_to_psp_fee_models.sql` adds columns, unique constraint, index, and seed data (AMEX surcharge, domestic debit discount, international credit premium). REST fee endpoints extended with card-brand fields.

**Sprint 3.5 — Client-Side SDK (Checkout)**
- TypeScript monorepo (`checkout-sdk/`) with npm workspaces: `@nexuspay/js`, `@nexuspay/react`, `nexuspay-checkout`
- Database migrations: `payment_sessions` and `payment_tokens` tables with RLS, lazy expiration, tokenization rate limiting
- `PaymentSession` and `PaymentToken` domain models with `ps_` / `ptok_` ID prefixes
- Session token authentication: restricted-scope JWT (HMAC-SHA256) via `SessionTokenIssuer` in IAM module
- `SessionTokenAuthenticationFilter` at `@Order(0)` for `/v1/checkout/**` paths
- REST controllers: `PaymentSessionController` (merchant API key auth) and `CheckoutController` (session token auth)
- CORS `Access-Control-Max-Age: 86400` and `Content-Security-Policy: frame-ancestors *` for iframe embedding
- Design system specification (`DESIGN.md`) with theme tokens, component states, animations, responsive behavior
- `@nexuspay/js` — zero-dependency browser SDK: `NexusPay` class, typed event emitter, HTTP client (10s timeout, network-only retry)
- Theme engine: CSS custom properties (`--nxp-*`), 3 built-in presets (default, night, flat), Appearance API for merchant customization
- Card validator: Luhn check, BIN detection for 8+ networks (Visa, MC, Amex, Discover, JCB, UnionPay, Maestro, Diners Club)
- PCI-compliant card input via sandboxed iframe (`card-frame.html`): PAN never crosses postMessage boundary
- CardElement with micro-interactions: brand icon crossfade (150ms), number formatting with cursor preservation, expiry auto-advance
- PaymentElement: composite with payment method tabs — horizontal on desktop, radio-button list on mobile (<640px)
- AddressElement: billing/shipping form with country-aware field formatting (US/CA/AU state support)
- 3DS challenge handling: redirect mode (full page) + iframe mode (modal overlay, 10min timeout)
- 3DS device fingerprint collection via hidden iframe
- Apple Pay handler: `ApplePaySession` feature detection, merchant validation, official brand guidelines button
- Google Pay handler: dynamic script loading, `isReadyToPay()` check, official brand guidelines button
- Bank redirect handler: iDEAL (11 Dutch banks), Bancontact, Giropay, P24
- BNPL handler: Klarna, Afterpay, Affirm with on-demand dynamic script loading
- `@nexuspay/react` — React component library: `NexusPayProvider`, `PaymentElement`, `CardElement`, `AddressElement`
- React hooks: `useNexusPay()`, `useConfirmPayment()` (auto-handles 3DS challenges)
- Hosted checkout page (`nexuspay-checkout`): Vite SPA, responsive two-column/single-column layout
- Checkout UI: merchant branding header, order summary, animated success checkmark (stroke-dasharray 600ms), failure retry
- Loading skeleton with shimmer animation (1.5s infinite linear) during session load
- Dark mode support via `prefers-color-scheme` media query across all components
- New gaps identified: GAP-050 (no card-frame.html CDN hosting), GAP-051 (no Apple Pay/Google Pay sandbox testing), GAP-052 (BNPL provider SDK versions unpinned)

**Sprint 3.6 — Payment Analytics Platform**
- New `analytics` Gradle module (13th module) with separate `analytics` PostgreSQL schema
- 5 Flyway migrations (V3017–V3021): auth_rate_hourly/daily/monthly, psp_health_snapshots, revenue_hourly/daily, decline_daily — all with RLS policies
- Materialized views: `mv_auth_rate_daily_refresh` (hourly→daily aggregation), `mv_psp_health_trend` (30-day health trend)
- Domain models: AuthRateMetric, PspHealthScore, RevenueMetric, DeclineAnalysis, AnomalyAlert (Java records)
- Hexagonal port interfaces: QueryAuthRates, QueryPspHealth, QueryRevenue, QueryDeclines use cases
- Auth rate analytics: granularity-based table selection (HOURLY/DAILY/MONTHLY), multi-dimension grouping (PSP, card brand, region, currency, payment method)
- Revenue analytics: volume, count, fees, net revenue, refund rate, chargeback rate with currency-aware aggregation
- Decline analytics: SOFT/HARD/ERROR categorization with 20+ decline code mappings, top-N decline reasons
- PSP health scoring: weighted composite (auth rate 50%, latency 30%, error rate 20%), normalized 0–100 scale
- Anomaly detection: 7-day rolling mean + standard deviation, configurable 2σ threshold, publishes `PspHealthDegraded` domain event
- 3 Kafka consumers: PaymentEventAnalyticsConsumer (payment lifecycle → auth rate + revenue + decline rollups), RoutingEventAnalyticsConsumer (latency enrichment), FraudEventAnalyticsConsumer (fraud block rate logging)
- Idempotent upserts via `INSERT ... ON CONFLICT UPDATE` for all rollup tables
- Scheduled rollup jobs: hourly→daily (daily at 00:05), daily→monthly (1st of month at 00:10), PSP health snapshot every 5 minutes
- Data retention: hourly 90 days, daily 730 days (2 years), monthly 3650 days (10 years) — configurable via `AnalyticsProperties`
- Materialized view refresh: `REFRESH MATERIALIZED VIEW CONCURRENTLY` every hour
- Valkey cache-aside: SHA-256 query hashing, 5-minute TTL, graceful degradation on Valkey failure
- REST API: `GET /v1/analytics/{auth-rates,psp-health,revenue,declines}` with query params (from, to, groupBy, granularity, filters)
- `@PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")` on all analytics endpoints
- Transactional outbox for analytics events (PspHealthDegraded, AnomalyDetected) following FraudOutboxAdapter pattern
- New Kafka topic: `nexuspay.analytics.psp-health` with dedicated consumer group `nexuspay-analytics-consumer`
- Full `AnalyticsProperties` config hierarchy: pipeline, rollup, psp-health (weights), cache (TTL), query (maxDateRangeDays)
- New gaps identified: GAP-053 (no integration tests for analytics consumers), GAP-054 (analytics consumers use JSON not Avro), GAP-055 (no Grafana analytics dashboard)

**Sprint 3.4 — Event Architecture Upgrade**
- JSON-to-Avro event serialization migration with Confluent Schema Registry (7.6.1)
- 21 Avro schema definitions (.avsc) covering all domain events: payment (10), ledger (2), billing (3), fraud (4), routing (3)
- Shared Avro types: EventMetadata and Money records
- Avro Gradle plugin (1.9.1) generates Java classes from schemas at build time
- Feature-flagged dual-write strategy: `NEXUSPAY_AVRO_DUAL_WRITE` controls migration phase
- DualWritePublisher with Schema Registry circuit breaker — Avro serialization failure falls back to JSON
- DualFormatDeserializer: single consumer factory config change makes all consumers dual-format compatible
- GenericRecordToMapConverter: Avro GenericRecord → Map with type unwrapping (unions, nested records, logical types)
- EventSchemaMapping: lazy-cached static registry of event_type → Avro Schema
- JsonToAvroConverter: type-coercing JSON Map → Avro GenericRecord converter
- Schema Registry config with profile-conditional auto-register (true local/test, false production)
- Append-only event log (V3013 migration): captures all published events for audit/replay with DB-level UPDATE/DELETE prevention
- EventLog port interface + PostgresEventLog JPA implementation with idempotent append
- EventLogAppender: post-publish hook for OutboxRelay, failure-isolated (never blocks publish pipeline)
- Dead letter queue management (V3014 migration): captures failed events from all 6 DLT topics
- DeadLetterQueueConsumer: extracts error info from Spring Kafka DLT headers
- DeadLetterReprocessor: scheduled exponential backoff retry (2^n minutes, capped at 60min) with Valkey distributed lock
- Admin REST API at `/v1/admin/dead-letters`: list, detail, retry, discard, bulk retry-all, stats
- BatchEventConsumer interface + BatchKafkaConsumerConfig for high-throughput consumption
- OutboxRelay delegates to DualWritePublisher with PostPublishCallback for event log integration
- EventUpcaster/EventUpcasterChain extended with GenericRecord support for Avro-native upcasting
- Docker Compose: schema-registry + schema-registry-ui services
- Resilience4j circuit breaker instance for Schema Registry
- Migration runbook: 4-phase deployment guide with validation metrics and rollback procedure
- Resolved GAP-012 (Schema Registry)

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
