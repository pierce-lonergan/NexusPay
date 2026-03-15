# NexusPay Strategic Roadmap

Last updated: 2026-03-15

## Executive Summary

This document defines the NexusPay product roadmap across five major phases spanning approximately 120 weeks (30 months). The roadmap transforms NexusPay from an MVP payment orchestrator into a comprehensive enterprise payment platform capable of competing with Spreedly, Primer.io, Stripe, and Adyen across seven market categories.

Each phase is structured as six 4-week sprints, with clear entry/exit criteria, staffing requirements, and measurable success metrics. The roadmap prioritizes production readiness before feature expansion, ensuring that each phase builds on a stable foundation.

**Key milestones**:

| Phase | Version | Timeline | Theme | TPS Target |
|-------|---------|----------|-------|------------|
| 1 | v0.1.0 | Weeks 1-16 | MVP Payment Orchestration | 200 |
| 2 | v0.2.0 | Weeks 17-44 | Production Hardening | 500 |
| 3 | v0.3.0 | Weeks 45-68 | Intelligence & Global | 1,000 |
| 4 | v0.4.0 | Weeks 69-92 | Platform Expansion | 2,500 |
| 5 | v1.0.0 | Weeks 93-124 | Next-Gen & Market Leadership | 5,000 |

---

## Phase 1: MVP Payment Orchestration (COMPLETE)

**Version**: v0.1.0
**Timeline**: Weeks 1-16 (4 sprints)
**Status**: COMPLETE

### Objectives and Business Value

Phase 1 established the foundational architecture and proved that NexusPay can route payments through HyperSwitch with double-entry accounting, event-driven messaging, and enterprise-grade IAM. The MVP validates the core thesis: an open-source, self-hosted payment orchestrator that eliminates $24K-$200K+/year in platform fees.

### Delivered Capabilities

| Capability | Implementation | Module |
|-----------|---------------|--------|
| Gradle multi-module scaffold | Spring Modulith with enforced boundaries | `nexuspay-app` |
| HyperSwitch integration | Circuit breaker (Resilience4j), retry, fallback | `nexuspay-orchestration` |
| Double-entry ledger | SERIALIZABLE isolation, idempotent postings | `nexuspay-ledger` |
| Kafka event streaming | Polling outbox pattern, at-least-once delivery | `nexuspay-events` |
| IAM | Keycloak 26, API key management, maker-checker workflows | `nexuspay-iam` |
| Gateway API | Rate limiting (Valkey), idempotency keys, webhook delivery with retry | `nexuspay-gateway` |
| Test suite | Testcontainers for PostgreSQL, Kafka, Valkey, Keycloak | All modules |
| Deployment | Helm chart, Docker Compose, Gradle convention plugins | `nexuspay-deploy` |

### Technology Stack Established

| Layer | Technology | Version |
|-------|-----------|---------|
| Runtime | Java | 21 (LTS) |
| Framework | Spring Boot | 3.2.5 |
| Architecture | Spring Modulith | 1.1.x |
| Database | PostgreSQL | 16 |
| Messaging | Apache Kafka (KRaft) | 3.7.x |
| Cache | Valkey | 8.x |
| Identity | Keycloak | 26 |
| Resilience | Resilience4j | 2.2.x |

### Performance Baseline

| Metric | Target | Achieved |
|--------|--------|----------|
| Payment creation throughput | 200 TPS | 200 TPS |
| p99 latency (payment create) | < 500ms | ~420ms |
| Ledger posting throughput | 400 TPS (2 entries/payment) | 400 TPS |
| Webhook delivery p99 | < 2s | ~1.6s |

### Key Technical Decisions

1. **Spring Modulith over microservices**: Reduces operational complexity while preserving module boundaries. Microservice extraction deferred to Phase 3+ if scaling demands it.
2. **Polling outbox over CDC**: Simpler to implement and debug. Debezium CDC planned for Phase 2 when throughput requirements increase.
3. **Valkey over Redis**: Open-source, API-compatible, avoids Redis licensing concerns.
4. **KRaft over ZooKeeper**: Kafka's native consensus eliminates ZooKeeper dependency and simplifies deployment.

### Exit Criteria (MET)

- [x] Payment creation, capture, refund, void flows functional end-to-end
- [x] Ledger balances reconcile with zero discrepancies on 10,000-transaction load test
- [x] Webhook delivery succeeds within 3 retries for 99.5% of events
- [x] All Testcontainers integration tests pass in CI
- [x] Helm chart deploys to a single-node k8s cluster successfully
- [x] API documentation (OpenAPI 3.1) published for all gateway endpoints

---

## Phase 2: Production Hardening

**Version**: v0.2.0
**Timeline**: Weeks 17-40 (6 sprints, 4 weeks each)
**Theme**: Make NexusPay production-deployable for real merchant traffic

### Objectives and Business Value

Phase 2 transforms the MVP into a production-grade system. Multi-tenancy enables SaaS deployment. Vault integration secures secrets. CDC replaces the polling outbox to handle higher throughput. The reconciliation engine and dispute management close critical gaps against Modern Treasury and Chargebacks911. Subscription billing opens the recurring revenue use case. Observability ensures operational readiness.

**Business value**: After Phase 2, NexusPay can process live merchant traffic under SLA, handle settlement reconciliation, and manage the chargeback lifecycle -- the minimum bar for enterprise adoption.

### Entry Criteria

- Phase 1 v0.1.0 tagged and deployed to staging
- Staging environment provisioned with 3-node PostgreSQL, 3-broker Kafka cluster
- HashiCorp Vault instance available (dev or managed)
- At least one PSP sandbox account configured in HyperSwitch
- Team staffed to Phase 2 levels (see Staffing below)

### Sprint 2.1: Multi-Tenancy, Secrets, and Transport Security (Weeks 17-20)

**Objective**: Isolate tenant data at the database level, secure all secrets, and enforce TLS everywhere.

| Deliverable | Detail |
|------------|--------|
| PostgreSQL Row-Level Security (RLS) | Tenant ID column on all tables; RLS policies enforced via `SET app.current_tenant` per connection |
| Tenant provisioning API | CRUD for tenants with quota configuration, API key scoping |
| HashiCorp Vault integration | Spring Cloud Vault for DB credentials, API keys, PSP secrets; auto-rotation |
| TLS termination | mTLS between services, TLS 1.3 at gateway ingress |
| Certificate management | cert-manager with Let's Encrypt (public) and Vault PKI (internal) |

**New modules**: `nexuspay-tenant`

**Risks**: RLS adds query overhead (~5-8%); mitigate with composite indexes on `(tenant_id, id)` and connection pooling tuning (PgBouncer transaction mode).

**Dependencies**: None (foundational sprint).

### Sprint 2.2: CDC and Workflow Engine (Weeks 21-24)

**Objective**: Replace the polling outbox with Debezium CDC for reliable, low-latency event propagation. Introduce Temporal for long-running payment workflows.

| Deliverable | Detail |
|------------|--------|
| Debezium CDC connector | PostgreSQL logical replication to Kafka; outbox table routing |
| Outbox migration | Feature flag to switch from polling to CDC; polling retained as fallback |
| Temporal workflow engine | Payment lifecycle (authorize-capture-settle) as durable workflows |
| Retry/timeout policies | Configurable per-PSP retry strategies in Temporal |
| Dead letter handling | Failed events routed to DLQ topic with alerting |

**New modules**: `nexuspay-workflow`

**New technologies introduced**: Debezium 2.x, Temporal 1.24+

**Risks**: Debezium replication slot management requires monitoring to prevent WAL bloat. Temporal adds operational overhead (server cluster + UI).

**Dependencies**: Sprint 2.1 (tenant-aware outbox table).

### Sprint 2.3: Reconciliation Engine (Weeks 25-28)

**Objective**: Ingest settlement files from PSPs and perform automated 3-way matching between internal ledger, PSP settlement, and bank statement data.

| Deliverable | Detail |
|------------|--------|
| Settlement file ingestion | SFTP/S3 file watchers; parsers for Stripe, Adyen, Worldpay CSV/XML formats |
| 3-way matching engine | Transaction-level matching: ledger entry <-> PSP settlement <-> bank line item |
| Exception management | Unmatched/mismatched transactions flagged for manual review with audit trail |
| Reconciliation dashboard API | Summary endpoints: match rate, outstanding exceptions, aging |
| Scheduled reconciliation | Temporal-based daily/weekly reconciliation schedules per tenant |

**New modules**: `nexuspay-reconciliation`

**Risks**: Settlement file formats vary wildly across PSPs; plan for at least 3 parser implementations plus a configurable CSV mapper for long-tail PSPs.

**Dependencies**: Sprint 2.2 (Temporal for scheduling), Sprint 2.1 (tenant-scoped reconciliation).

### Sprint 2.4: Dispute and Chargeback Management (Weeks 29-32)

**Objective**: Model the full dispute lifecycle and integrate with chargeback prevention networks.

| Deliverable | Detail |
|------------|--------|
| Dispute state machine | States: inquiry, pre-arbitration, chargeback, representment, arbitration, resolved |
| Verifi CDRN integration | Visa CDRN alerts for proactive refunds before chargebacks |
| Ethoca alerts integration | Mastercard Ethoca collaboration alerts |
| Auto-representment stubs | Rule-based auto-response framework (templates, evidence assembly) |
| Dispute analytics API | Win rates, reason code distribution, average resolution time |
| Ledger integration | Dispute holds, reversals, and fee postings in double-entry ledger |

**New modules**: `nexuspay-disputes`

**Risks**: Verifi and Ethoca integrations require merchant enrollment and certification, which can take 4-8 weeks. Begin enrollment in Sprint 2.2.

**Dependencies**: Sprint 2.1 (tenant isolation), Sprint 2.3 (reconciliation for dispute evidence).

### Sprint 2.5: Subscription Billing (Weeks 33-36)

**Objective**: Provide a complete subscription lifecycle engine for recurring payments.

| Deliverable | Detail |
|------------|--------|
| Subscription state machine | States: trial, active, past_due, paused, canceled, expired |
| Dunning management | Configurable retry schedules (exponential backoff, smart retry timing) |
| Proration engine | Upgrade/downgrade proration (time-based, immediate, end-of-period) |
| Product catalog | Plans, prices (flat, tiered, volume, per-unit), add-ons, coupons |
| Invoice generation | PDF invoices with tax line items, credit notes |
| Subscription events | Lifecycle events published to Kafka for downstream systems |

**New modules**: `nexuspay-billing`

**Risks**: Proration logic is notoriously complex; invest heavily in property-based testing (jqwik) to cover edge cases around mid-cycle changes, timezone boundaries, and leap years.

**Dependencies**: Sprint 2.2 (Temporal for dunning workflows), Sprint 2.1 (tenant-scoped plans and subscriptions).

### Sprint 2.6: Production Observability (Weeks 37-40)

**Objective**: Instrument the platform for production operations with metrics, dashboards, alerting, and SLO tracking.

| Deliverable | Detail |
|------------|--------|
| Prometheus metrics | Micrometer instrumentation: payment latency histograms, ledger throughput, Kafka consumer lag, cache hit rates |
| Grafana dashboards | Pre-built dashboards: platform overview, per-tenant health, PSP health, Kafka pipeline, JVM |
| Alerting rules | PagerDuty/OpsGenie integration; alerts on SLO breaches, error rate spikes, replication lag |
| SLO tracking | Error budget tracking for availability (99.95%) and latency (p99 < 500ms) targets |
| Distributed tracing | OpenTelemetry with Tempo/Jaeger; trace ID propagation across Kafka consumers |
| Structured logging | JSON logging with correlation IDs, tenant context, redacted PII fields |

**New technologies introduced**: Prometheus, Grafana, OpenTelemetry, Tempo

**Risks**: Cardinality explosion from high-tenant-count metric labels. Mitigate with label whitelisting and recording rules.

**Dependencies**: All prior Phase 2 sprints (metrics cover all new modules).

### Phase 2 Exit Criteria

- [ ] Multi-tenant deployment with 3+ tenants processing payments concurrently, zero data leakage
- [ ] Debezium CDC delivering events with < 500ms lag (p99) under 500 TPS
- [ ] Reconciliation engine matches > 95% of settlement file lines automatically
- [ ] Dispute lifecycle tested end-to-end with mock Verifi/Ethoca alerts
- [ ] Subscription billing handles 1,000 concurrent subscriptions through renewal cycle without errors
- [ ] Grafana dashboards operational with SLO tracking for 2-week burn-down period
- [ ] Load test: 500 TPS sustained for 30 minutes with < 0.1% error rate
- [ ] Security audit: no critical/high findings from OWASP ZAP scan of gateway API
- [ ] All Vault-managed secrets rotated successfully without downtime

### Phase 2 Performance Targets

| Metric | Target |
|--------|--------|
| Payment throughput | 500 TPS |
| p99 latency (payment create) | < 450ms |
| CDC event lag (p99) | < 500ms |
| Reconciliation match rate | > 95% automated |
| Availability SLO | 99.95% |

### Phase 2 Staffing

| Role | Count | Notes |
|------|-------|-------|
| Senior Backend Engineer | 3 | Spring Boot, PostgreSQL, Kafka expertise |
| Platform/DevOps Engineer | 1 | Vault, Helm, observability stack |
| QA Engineer | 1 | Load testing, integration testing |
| Product Manager | 0.5 | Requirements for reconciliation/disputes |
| **Total** | **5.5** | |

---

## Phase 3: Intelligence & Global

**Version**: v0.3.0
**Timeline**: Weeks 45-68 (6 sprints, 4 weeks each)
**Theme**: Make NexusPay intelligent, global, and developer-friendly

### Objectives and Business Value

Phase 3 introduces the differentiating capabilities that move NexusPay beyond basic orchestration. The fraud rules engine protects merchants. Cross-border/FX support opens international markets. Smart routing optimizes payment costs and success rates. The client-side SDK makes integration frictionless. Analytics provides the data layer that drives merchant retention.

**Business value**: After Phase 3, NexusPay competes with Primer.io on fraud/routing intelligence, with Spreedly on developer experience, and with ProcessOut on analytics. International merchants can process cross-border payments with automatic FX handling.

### Entry Criteria

- Phase 2 v0.2.0 deployed to production with at least one live tenant
- 2+ PSP connectors active in production (e.g., Stripe + Adyen)
- Observability stack operational with 4+ weeks of production baseline data
- Schema Registry infrastructure provisioned (Confluent or Apicurio)
- CDN account for SDK distribution (CloudFront or similar)

### Sprint 3.1: Fraud Rules Engine (Weeks 41-44)

**Objective**: Provide configurable fraud screening with device fingerprinting and external FRM provider integration.

| Deliverable | Detail |
|------------|--------|
| Rules engine | Configurable rules (velocity checks, amount thresholds, geo-blocking, BIN restrictions) |
| Device fingerprinting | Client-side fingerprint collection (browser, device, IP); server-side scoring |
| FRM provider integration | Abstraction layer for Signifyd, Riskified, Sift; pre-auth and post-auth hooks |
| Risk scoring API | Composite risk score (0-100) from rules + device + FRM provider |
| Block/challenge/accept workflow | Configurable actions per risk band; 3DS step-up on challenge |
| Fraud dashboard API | Fraud rates, rule hit rates, false positive tracking |

**New modules**: `nexuspay-fraud`

**Risks**: False positives directly impact merchant revenue. Provide a shadow mode where rules score but do not block, allowing merchants to tune thresholds before enforcement.

**Dependencies**: None within Phase 3 (can begin immediately).

### Sprint 3.2: Cross-Border and FX (Weeks 45-48)

**Objective**: Enable multi-currency payment processing with automatic FX conversion and currency-aware ledger entries.

| Deliverable | Detail |
|------------|--------|
| Multi-currency routing | Route payments to PSPs based on currency support and cost |
| FX rate engine | Rate feeds from ECB, Open Exchange Rates; configurable markup |
| Currency conversion ledger | Conversion entries with rate, markup, and P&L tracking |
| Presentment currency | Customer pays in local currency; merchant settles in base currency |
| FX rate caching | Valkey-cached rates with configurable staleness tolerance (default 15 min) |
| Supported currencies | Launch with 25 major currencies (USD, EUR, GBP, JPY, etc.) |

**New modules**: `nexuspay-fx`

**Risks**: FX rate volatility can cause losses if rates are stale. Implement rate validity windows and auto-reject if the quoted rate has expired.

**Dependencies**: Sprint 2.1 (tenant-scoped currency configuration).

### Sprint 3.3: Smart Routing Engine (Weeks 49-52)

**Objective**: Route payments to the optimal PSP based on cost, success rate, and latency.

| Deliverable | Detail |
|------------|--------|
| Cost-based routing | Minimize processing fees based on card type, region, amount |
| Success-rate routing | Route to PSPs with highest historical approval rates per BIN range |
| Latency-based routing | Prefer faster PSPs when cost/success are equivalent |
| Routing rules DSL | Declarative routing configuration (YAML/JSON) with conditional logic |
| A/B testing framework | Split traffic between routing strategies for controlled experiments |
| Automatic failover | Cascade to secondary PSP on decline/timeout with reason-code analysis |

**New modules**: `nexuspay-routing`

**Risks**: Insufficient historical data can lead to poor routing decisions early on. Bootstrap with industry benchmark data and switch to live data after 10,000 transactions per BIN range.

**Dependencies**: Sprint 2.6 (metrics data for success-rate calculation), Sprint 3.2 (currency-aware routing).

### Sprint 3.4: Schema Registry and Event Evolution (Weeks 53-56)

**Objective**: Introduce schema governance for all Kafka events, enabling safe event evolution and an event store for replay.

| Deliverable | Detail |
|------------|--------|
| Schema Registry deployment | Apicurio Registry (open-source) or Confluent Schema Registry |
| Avro schema migration | All existing JSON events migrated to Avro with backward-compatible schemas |
| Event versioning | Schema evolution rules enforced at CI time (backward compatibility check) |
| Event store | Append-only event log in PostgreSQL for replay and audit |
| Consumer migration tooling | Dual-read period: consumers accept both JSON (v1) and Avro (v2) |

**New technologies introduced**: Apicurio Schema Registry, Apache Avro

**Risks**: Avro migration requires coordinated producer/consumer updates. Use a 4-week dual-read window with feature flags.

**Dependencies**: Sprint 2.2 (Debezium CDC events must be included in migration).

### Sprint 3.5: Client-Side SDK (Weeks 57-60)

**Objective**: Provide a JavaScript SDK and React components for PCI-compliant client-side payment collection.

| Deliverable | Detail |
|------------|--------|
| NexusPay.js | Vanilla JS SDK: tokenization, payment element, 3DS handling |
| React components | `<PaymentElement>`, `<CardElement>`, `<AddressElement>` with hooks |
| PCI-compliant iframe | Card fields rendered in isolated iframe (SAQ A eligible) |
| SDK hosting | Versioned bundles on CDN; SRI hash integrity |
| Customization API | Theming (colors, fonts, borders), localization (12 languages at launch) |
| Developer documentation | Integration guides, code samples, sandbox playground |

**New modules**: `nexuspay-sdk` (separate repository)

**New technologies introduced**: TypeScript, Rollup, Playwright (SDK E2E testing)

**Risks**: PCI SAQ A eligibility requires the iframe to be served from a PCI-compliant domain. Ensure CDN and origin server are within PCI scope.

**Dependencies**: Sprint 2.1 (tenant API keys for SDK authentication).

### Sprint 3.6: Payment Analytics Platform (Weeks 61-64)

**Objective**: Provide actionable payment analytics including authorization rates, decline analysis, and PSP health scoring.

| Deliverable | Detail |
|------------|--------|
| Authorization rate tracking | Success/decline rates by PSP, BIN range, currency, country, card type |
| Decline analysis | Reason code categorization (issuer declines, fraud, technical, insufficient funds) |
| PSP health scoring | Real-time PSP reliability scores based on success rate, latency, error rate |
| Analytics API | RESTful query endpoints with date range, grouping, filtering |
| Data pipeline | Kafka Streams aggregation into analytics-optimized PostgreSQL tables (or ClickHouse) |
| Scheduled reports | Tenant-configurable email reports (daily, weekly, monthly) |

**New modules**: `nexuspay-analytics`

**New technologies introduced**: Kafka Streams (or ClickHouse for analytics store)

**Risks**: Analytics queries on high-volume data can impact transactional database performance. Use a dedicated read replica or analytics-specific data store.

**Dependencies**: Sprint 2.6 (metrics pipeline), Sprint 3.3 (routing data for PSP health).

### Phase 3 Exit Criteria

- [ ] Fraud rules engine blocking > 90% of known test fraud patterns in shadow mode validation
- [ ] Cross-border payments processed in 25+ currencies with FX conversion within 0.5% of market rate
- [ ] Smart routing demonstrating measurable improvement (> 2% higher auth rate or > 5% lower cost) vs. static routing on A/B test
- [ ] All Kafka events migrated to Avro with zero consumer errors during dual-read period
- [ ] NexusPay.js SDK rendering payment form in < 1.5s (p95) across Chrome, Firefox, Safari
- [ ] Analytics API returning authorization rate queries in < 2s for 30-day windows
- [ ] Load test: 1,000 TPS sustained for 30 minutes with smart routing active

### Phase 3 Performance Targets

| Metric | Target |
|--------|--------|
| Payment throughput | 1,000 TPS |
| p99 latency (payment create with fraud check) | < 600ms |
| Smart routing decision time | < 50ms |
| FX rate freshness | < 15 min staleness |
| SDK load time (p95) | < 1.5s |
| Analytics query (30-day) | < 2s |

### Phase 3 Staffing

| Role | Count | Notes |
|------|-------|-------|
| Senior Backend Engineer | 4 | +1 for fraud/routing complexity |
| Frontend/SDK Engineer | 2 | NexusPay.js, React components |
| Data Engineer | 1 | Analytics pipeline, Kafka Streams |
| Platform/DevOps Engineer | 1 | Schema Registry, CDN, infrastructure |
| QA Engineer | 1 | Cross-browser SDK testing, load testing |
| Product Manager | 1 | Full-time for SDK/analytics requirements |
| **Total** | **10** | |

---

## Phase 4: Platform Expansion

**Version**: v0.4.0
**Timeline**: Weeks 69-92 (6 sprints, 4 weeks each)
**Theme**: Expand NexusPay from payment processing into a complete payment platform

### Objectives and Business Value

Phase 4 transforms NexusPay into a platform that competes with Stripe and Adyen on breadth. Card vaulting eliminates PSP lock-in. Marketplace payments address the platform economy. B2B payments open enterprise AP/AR use cases. The visual workflow builder matches Primer.io's no-code proposition. Mobile SDKs and POS integration extend reach to physical commerce. The compliance toolkit removes regulatory friction.

**Business value**: After Phase 4, NexusPay addresses 80%+ of the payment platform market. A merchant can vault cards, process marketplace splits, handle B2B invoicing, accept mobile and in-store payments, and manage compliance -- all within NexusPay.

### Entry Criteria

- Phase 3 v0.3.0 deployed to production with 5+ live tenants
- PCI DSS Level 1 assessment initiated (required for card vault)
- Mobile development team onboarded (iOS + Android)
- At least 3 PSP connectors in production with smart routing active
- Legal review of marketplace payment money transmission requirements complete

### Sprint 4.1: Universal Card Vault and Network Tokenization (Weeks 65-68)

**Objective**: Build a PCI-compliant card vault with network token provisioning to eliminate PSP lock-in and improve authorization rates.

| Deliverable | Detail |
|------------|--------|
| Card vault | AES-256-GCM encrypted card storage; HSM integration (AWS CloudHSM or SoftHSM) |
| Vault API | Store, retrieve (masked), delete; PAN-to-token and token-to-PAN operations |
| Visa Token Service (VTS) | Token provisioning for Visa cards |
| Mastercard MDES | Token provisioning for Mastercard cards |
| Amex Token Service | Token provisioning for Amex cards |
| Token lifecycle | Provisioning, suspension, resumption, deletion; status sync with networks |
| PSP-agnostic forwarding | Forward vaulted cards to any PSP without re-collecting from customer |

**New modules**: `nexuspay-vault`

**Risks**: PCI DSS Level 1 scope expansion is the single largest risk. Isolate the vault in a dedicated service with its own database, network segment, and deployment pipeline. Engage a QSA early.

**Dependencies**: Sprint 3.5 (SDK for client-side tokenization flow).

### Sprint 4.2: Marketplace and Platform Payments (Weeks 69-72)

**Objective**: Enable split payments, connected merchant accounts, and payouts for marketplace and platform business models.

| Deliverable | Detail |
|------------|--------|
| Connected accounts | Sub-merchant onboarding, KYC status tracking, account hierarchy |
| Split payments | Percentage-based, fixed-amount, and multi-party splits |
| Platform fee collection | Configurable platform take rate with ledger entries |
| Payout engine | Scheduled payouts to connected accounts via bank transfer/ACH |
| Payout reconciliation | Automated matching of payout batches with bank confirmations |
| Marketplace dashboard API | Per-merchant balances, pending payouts, transaction history |

**New modules**: `nexuspay-marketplace`

**Risks**: Money transmission licensing requirements vary by jurisdiction. NexusPay should operate as a technology platform (not a money transmitter) by ensuring funds flow through licensed PSPs. Legal counsel required.

**Dependencies**: Sprint 2.1 (multi-tenancy), Sprint 2.5 (billing for platform fees).

### Sprint 4.3: B2B Payments (Weeks 73-76)

**Objective**: Support B2B payment workflows including invoice financing, virtual cards, purchase orders, and accounts payable automation.

| Deliverable | Detail |
|------------|--------|
| Invoice management | Invoice creation, lifecycle tracking, partial payments, credit memos |
| Virtual card issuance | Single-use and multi-use virtual cards via issuing partner API |
| Purchase order matching | PO-to-invoice-to-payment 3-way matching |
| AP automation | Batch payment processing, approval workflows, vendor management |
| Payment terms | Net 30/60/90 tracking, early payment discounts, late fee calculation |
| B2B ledger entries | Invoice-level accounting with GL code mapping |

**New modules**: `nexuspay-b2b`

**Risks**: Virtual card issuance requires a BIN sponsor or issuing partner (e.g., Marqeta, Lithic). Partnership agreement must be in place before development begins.

**Dependencies**: Sprint 4.2 (connected accounts for vendors), Sprint 2.3 (reconciliation for PO matching).

### Sprint 4.4: Visual Workflow Builder (Weeks 77-80)

**Objective**: Provide a drag-and-drop workflow builder for configuring payment flows without code.

| Deliverable | Detail |
|------------|--------|
| Workflow canvas | React-based drag-and-drop flow editor (React Flow) |
| Node types | Payment, condition, split, delay, webhook, notification, custom script |
| Conditional routing | If/else branching on amount, currency, card type, risk score, custom fields |
| Webhook triggers | Inbound webhooks as workflow entry points |
| Workflow versioning | Version history, rollback, diff view |
| Workflow execution | Compiled to Temporal workflows for durable execution |

**New modules**: `nexuspay-workflows-ui` (frontend), extensions to `nexuspay-workflow`

**New technologies introduced**: React Flow, Monaco Editor (for custom script nodes)

**Risks**: Visual builders that generate executable workflows are a security surface. All user-defined logic must be sandboxed (no arbitrary code execution; only a safe expression language like SpEL subset or JSONLogic).

**Dependencies**: Sprint 2.2 (Temporal as execution engine), Sprint 3.1 (fraud rules as workflow nodes).

### Sprint 4.5: Mobile SDKs and POS Integration (Weeks 81-84)

**Objective**: Extend NexusPay to native mobile applications and point-of-sale terminals.

| Deliverable | Detail |
|------------|--------|
| iOS SDK | Swift SDK: card tokenization, Apple Pay, 3DS, payment sheet |
| Android SDK | Kotlin SDK: card tokenization, Google Pay, 3DS, payment sheet |
| SoftPOS integration | Tap-to-pay on Android via NFC (SoftPOS provider abstraction) |
| Terminal management API | Terminal registration, configuration, firmware status tracking |
| Unified transaction model | Online and in-store transactions in single ledger with channel indicator |
| SDK distribution | iOS: Swift Package Manager + CocoaPods; Android: Maven Central |

**New modules**: `nexuspay-sdk-ios` (separate repo), `nexuspay-sdk-android` (separate repo)

**New technologies introduced**: Swift, Kotlin, NFC/SoftPOS SDKs

**Risks**: Apple Pay and Google Pay certification processes can take 6-8 weeks. Submit applications in Sprint 4.3.

**Dependencies**: Sprint 4.1 (card vault for tokenization), Sprint 3.5 (shared tokenization protocol).

### Sprint 4.6: Compliance Toolkit (Weeks 85-88)

**Objective**: Provide abstraction layers for tax calculation, sanctions screening, and KYC/KYB orchestration.

| Deliverable | Detail |
|------------|--------|
| Tax provider abstraction | Integration layer for Avalara, TaxJar, Vertex; tax calculation on payment |
| Tax reporting | Tax summary reports by jurisdiction, period, tax type |
| Sanctions screening | OFAC, EU, UN sanctions list checks on payer/payee; configurable actions |
| KYC orchestration | Multi-provider KYC flow (Jumio, Onfido, Persona); status tracking |
| KYB orchestration | Business verification workflow (ownership, registration, beneficial owners) |
| Compliance audit trail | Immutable log of all compliance decisions with evidence retention |

**New modules**: `nexuspay-compliance`

**Risks**: Sanctions list updates must be applied within 24 hours of publication. Implement automated list refresh with alerting on stale data.

**Dependencies**: Sprint 4.2 (KYC/KYB for connected account onboarding).

### Phase 4 Exit Criteria

- [ ] Card vault passing PCI DSS Level 1 assessment with QSA sign-off
- [ ] Network tokens provisioned for Visa, Mastercard, and Amex with > 90% success rate
- [ ] Marketplace split payment processed end-to-end with correct ledger entries for all parties
- [ ] B2B invoice-to-payment flow completed with PO matching
- [ ] Visual workflow builder creating and executing a 5-node payment flow without errors
- [ ] iOS and Android SDKs rendering payment sheet on respective platforms
- [ ] Sanctions screening blocking test OFAC-listed entity within 200ms
- [ ] Load test: 2,500 TPS sustained for 30 minutes across all payment channels

### Phase 4 Performance Targets

| Metric | Target |
|--------|--------|
| Payment throughput | 2,500 TPS |
| p99 latency (payment with vault lookup) | < 550ms |
| Network token provisioning | < 3s |
| Marketplace payout processing | 10,000 payouts/hour |
| Mobile SDK payment sheet render | < 2s (p95) |
| Sanctions screening | < 200ms per check |

### Phase 4 Staffing

| Role | Count | Notes |
|------|-------|-------|
| Senior Backend Engineer | 5 | +1 for vault/compliance security focus |
| Frontend Engineer | 2 | Workflow builder UI |
| Mobile Engineer (iOS) | 1 | iOS SDK |
| Mobile Engineer (Android) | 1 | Android SDK |
| Security Engineer | 1 | PCI DSS, vault, HSM |
| Data Engineer | 1 | Analytics expansion |
| Platform/DevOps Engineer | 2 | PCI-segmented infrastructure |
| QA Engineer | 2 | Mobile testing, PCI validation |
| Product Manager | 1 | Full-time |
| **Total** | **16** | |

---

## Phase 5: Next-Gen & Market Leadership

**Version**: v1.0.0
**Timeline**: Weeks 93-124 (6 sprints, ~5 weeks each to allow for v1.0 hardening)
**Theme**: Achieve market leadership with next-generation payment capabilities

### Objectives and Business Value

Phase 5 positions NexusPay at the frontier of payment technology. Real-time payment rails (FedNow, SEPA Instant) future-proof the platform. AI/ML capabilities provide data-driven optimization that surpasses manual configuration. Embedded finance opens entirely new revenue streams. Crypto rails serve the emerging digital asset market. The multi-merchant admin platform enables NexusPay to be deployed as a white-label payment platform. Enterprise hardening (ISO 20022, SOC 2, PCI DSS tooling, chaos engineering) ensures the platform meets the compliance and reliability bar for the largest merchants.

**Business value**: v1.0.0 represents a feature-complete enterprise payment platform that competes across all seven market categories. NexusPay achieves parity with Stripe on capability breadth while maintaining its core differentiators: open-source, self-hosted, zero platform fees.

### Entry Criteria

- Phase 4 v0.4.0 deployed to production with 20+ live tenants
- PCI DSS Level 1 certification obtained
- At least $10M monthly payment volume processed through platform
- AI/ML infrastructure provisioned (GPU compute for model training, or managed ML platform)
- Legal review of stablecoin/crypto rail regulatory requirements complete
- SOC 2 Type I readiness assessment completed

### Sprint 5.1: Real-Time Payment Rails (Weeks 89-94)

**Objective**: Integrate with instant payment networks to enable real-time money movement.

| Deliverable | Detail |
|------------|--------|
| FedNow integration | Send/receive via FedNow through banking partner API |
| SEPA Instant (SCT Inst) | Euro instant credit transfers via SEPA-connected PSP |
| Open Banking PIS | Payment Initiation Service via PSD2-compliant providers (TrueLayer, Plaid) |
| Real-time rails abstraction | Unified API across FedNow, SEPA Instant, and Open Banking |
| Instant settlement | Ledger entries posted in real-time upon payment confirmation |
| Status webhooks | Real-time payment status callbacks (initiated, accepted, settled, failed) |

**New modules**: `nexuspay-realtime`

**New technologies introduced**: ISO 20022 XML messaging, Open Banking APIs

**Risks**: FedNow requires a sponsoring bank relationship. Begin partner discussions 6+ months before Sprint 5.1 start.

**Dependencies**: Sprint 2.1 (multi-tenancy), Sprint 4.1 (vault for account credential storage).

### Sprint 5.2: AI/ML Capabilities (Weeks 95-100)

**Objective**: Apply machine learning to optimize routing, detect anomalies, and predict churn.

| Deliverable | Detail |
|------------|--------|
| ML routing optimization | Gradient-boosted model predicting PSP success probability per transaction |
| Anomaly detection | Unsupervised anomaly detection on transaction patterns (isolation forest) |
| Churn prediction | Subscription churn risk scoring based on payment failure patterns |
| Feature store | Centralized feature computation and serving for ML models |
| Model serving | ONNX Runtime or TensorFlow Serving for low-latency inference |
| A/B testing integration | ML routing vs. rule-based routing comparison framework |

**New modules**: `nexuspay-ml`

**New technologies introduced**: ONNX Runtime (or TF Serving), Python (model training pipeline), Feature Store (Feast or custom)

**Risks**: ML model accuracy depends on sufficient training data (minimum 100K labeled transactions recommended). Start collecting labeled data (approval/decline with features) in Phase 3.

**Dependencies**: Sprint 3.3 (rule-based routing as baseline), Sprint 3.6 (analytics data for training).

### Sprint 5.3: Embedded Finance (Weeks 101-106)

**Objective**: Enable Banking-as-a-Service capabilities including treasury management, card issuing, and lending orchestration.

| Deliverable | Detail |
|------------|--------|
| Treasury management | Multi-currency account balances, yield optimization, cash flow forecasting |
| Card issuing | Physical and virtual card issuance via issuing partner (Marqeta, Lithic, Galileo) |
| Issuing controls | Spending limits, merchant category restrictions, geographic restrictions |
| Lending orchestration | Loan origination workflow, disbursement, repayment tracking |
| BaaS API | Unified API for embedded finance products |
| Regulatory reporting | SAR filing stubs, CTR generation, CFPB complaint tracking |

**New modules**: `nexuspay-embedded-finance`

**Risks**: BaaS activities are heavily regulated. NexusPay must operate through licensed banking partners and avoid becoming a regulated entity itself. Detailed legal analysis required per jurisdiction.

**Dependencies**: Sprint 4.3 (virtual card infrastructure), Sprint 4.6 (compliance toolkit for KYC/KYB).

### Sprint 5.4: Stablecoin and Crypto Payment Rails (Weeks 107-112)

**Objective**: Accept and settle payments in stablecoins, with fiat on-ramp and off-ramp capabilities.

| Deliverable | Detail |
|------------|--------|
| USDC settlement | Accept USDC payments on Ethereum and Solana; settle to merchant USDC wallet |
| USDT settlement | Accept USDT payments on Ethereum and Tron |
| Fiat on-ramp | Convert fiat to stablecoin via MoonPay, Transak, or Ramp Network |
| Fiat off-ramp | Convert stablecoin to fiat via banking partner |
| Crypto ledger entries | Stablecoin transactions recorded in double-entry ledger with blockchain tx hash |
| Wallet management | Custodial wallet abstraction (Fireblocks, BitGo) for merchant settlement |

**New modules**: `nexuspay-crypto`

**New technologies introduced**: Web3 libraries (ethers.js/web3j), blockchain RPC providers

**Risks**: Regulatory landscape for crypto payments is evolving rapidly. Implement a jurisdiction-based feature flag system to enable/disable crypto rails per region. MiCA (EU), state-by-state (US) compliance required.

**Dependencies**: Sprint 4.6 (compliance for crypto-specific sanctions screening), Sprint 3.2 (FX engine for crypto-fiat conversion).

### Sprint 5.5: Multi-Merchant Platform Admin (Weeks 113-118)

**Objective**: Build the administration layer that enables NexusPay to be deployed as a white-label, multi-merchant payment platform.

| Deliverable | Detail |
|------------|--------|
| Platform admin console | Admin UI for managing merchants, PSP connections, global configuration |
| App marketplace | Plugin/extension marketplace for third-party integrations |
| White-label theming | Per-merchant branding for dashboards, emails, SDK, and hosted pages |
| Self-service onboarding | Merchant self-registration, KYB, PSP credential setup wizard |
| Tiered pricing engine | Configurable pricing tiers (transaction fees, monthly fees, volume discounts) |
| Platform analytics | Cross-merchant analytics, revenue reporting, growth metrics |

**New modules**: `nexuspay-admin`, `nexuspay-marketplace-admin`

**Risks**: Multi-merchant admin introduces a new attack surface. Implement strict RBAC with per-merchant data isolation and audit logging for all admin actions.

**Dependencies**: Sprint 2.1 (multi-tenancy foundation), Sprint 4.6 (compliance for onboarding).

### Sprint 5.6: Enterprise Hardening (Weeks 119-124)

**Objective**: Achieve enterprise-grade compliance, messaging standards, and reliability for the v1.0.0 release.

| Deliverable | Detail |
|------------|--------|
| ISO 20022 messaging | Full ISO 20022 XML message support (pain.001, pain.002, camt.053, camt.054) |
| SOC 2 Type II automation | Continuous compliance evidence collection (Vanta, Drata integration) |
| PCI DSS Level 1 tooling | Automated scope documentation, evidence generation, quarterly scan scheduling |
| Chaos engineering | Steady-state verification, failure injection (Litmus Chaos or Chaos Mesh) |
| Disaster recovery | Automated DR failover testing, RPO < 1 min, RTO < 15 min |
| Performance hardening | JVM tuning, connection pool optimization, query plan analysis for 5,000 TPS |

**New technologies introduced**: ISO 20022 libraries (Prowide ISO 20022), Litmus Chaos, Vanta/Drata SDK

**Risks**: SOC 2 Type II requires 6+ months of continuous evidence collection. Begin the observation period at the start of Phase 5, not Sprint 5.6.

**Dependencies**: All prior phases (hardening covers entire platform).

### Phase 5 Exit Criteria

- [ ] FedNow payment sent and received end-to-end in < 30 seconds
- [ ] ML routing model outperforming rule-based routing by > 3% auth rate improvement on live traffic
- [ ] Card issued and used for purchase in sandbox environment
- [ ] USDC payment accepted, converted to fiat, and settled to merchant bank account
- [ ] Self-service merchant onboarding completed in < 10 minutes
- [ ] SOC 2 Type II report issued by auditor
- [ ] Chaos engineering: system recovers from PostgreSQL primary failure in < 30 seconds
- [ ] Load test: 5,000 TPS sustained for 60 minutes with < 0.05% error rate
- [ ] v1.0.0 release candidate passes full regression suite

### Phase 5 Performance Targets

| Metric | Target |
|--------|--------|
| Payment throughput | 5,000 TPS |
| p99 latency (payment create) | < 500ms |
| Real-time payment settlement | < 30s end-to-end |
| ML routing inference | < 20ms |
| Disaster recovery RTO | < 15 min |
| Disaster recovery RPO | < 1 min |
| Availability SLO | 99.99% |

### Phase 5 Staffing

| Role | Count | Notes |
|------|-------|-------|
| Senior Backend Engineer | 6 | Real-time rails, crypto, embedded finance |
| ML Engineer | 2 | Model training, feature store, serving |
| Frontend Engineer | 3 | Admin console, workflow builder, white-label |
| Mobile Engineer | 1 | SDK maintenance |
| Security/Compliance Engineer | 2 | SOC 2, PCI, chaos engineering |
| Data Engineer | 1 | ML data pipeline |
| Platform/DevOps Engineer | 2 | DR, chaos, performance |
| QA Engineer | 2 | Full regression, load, chaos testing |
| Product Manager | 1.5 | Full-time + 0.5 for compliance |
| Technical Writer | 1 | v1.0 documentation |
| **Total** | **21.5** | |

---

## Technology Additions Timeline

| Week | Phase.Sprint | Technology | Purpose |
|------|-------------|-----------|---------|
| 1 | 1.1 | Java 21, Spring Boot 3.2.5, Spring Modulith | Core runtime |
| 1 | 1.1 | PostgreSQL 16 | Primary database |
| 1 | 1.1 | Kafka KRaft 3.7 | Event streaming |
| 1 | 1.1 | Valkey 8 | Caching, rate limiting |
| 1 | 1.1 | Keycloak 26 | Identity and access management |
| 1 | 1.1 | Resilience4j 2.2 | Circuit breaker, retry |
| 1 | 1.1 | Testcontainers | Integration testing |
| 17 | 2.1 | HashiCorp Vault | Secrets management, PKI |
| 17 | 2.1 | PgBouncer | Connection pooling for RLS |
| 21 | 2.2 | Debezium 2.x | Change data capture |
| 21 | 2.2 | Temporal 1.24+ | Durable workflow engine |
| 37 | 2.6 | Prometheus | Metrics collection |
| 37 | 2.6 | Grafana | Dashboards and alerting |
| 37 | 2.6 | OpenTelemetry | Distributed tracing |
| 37 | 2.6 | Tempo | Trace storage |
| 53 | 3.4 | Apicurio Schema Registry | Schema governance |
| 53 | 3.4 | Apache Avro | Event serialization |
| 57 | 3.5 | TypeScript, Rollup | Client SDK build |
| 57 | 3.5 | Playwright | SDK E2E testing |
| 61 | 3.6 | Kafka Streams | Analytics aggregation |
| 65 | 4.1 | AWS CloudHSM / SoftHSM | Card vault encryption |
| 77 | 4.4 | React Flow | Visual workflow builder |
| 77 | 4.4 | Monaco Editor | Custom script editing |
| 81 | 4.5 | Swift, Kotlin | Mobile SDKs |
| 89 | 5.1 | ISO 20022 (Prowide) | Financial messaging standard |
| 95 | 5.2 | ONNX Runtime | ML model serving |
| 95 | 5.2 | Feast | Feature store |
| 107 | 5.4 | Web3j / ethers.js | Blockchain interaction |
| 119 | 5.6 | Litmus Chaos | Chaos engineering |
| 119 | 5.6 | Vanta / Drata | Compliance automation |

---

## Release Strategy

### Semantic Versioning

NexusPay follows Semantic Versioning 2.0.0 (`MAJOR.MINOR.PATCH`):

| Component | Increment Rule |
|-----------|---------------|
| MAJOR (0.x -> 1.0) | Breaking API changes; reserved for v1.0.0 GA release |
| MINOR (0.1 -> 0.2) | New features, non-breaking; one per phase |
| PATCH (0.1.0 -> 0.1.1) | Bug fixes, security patches; as needed |

### Release Cadence

| Phase | Release | Cadence |
|-------|---------|---------|
| 1 | v0.1.x | Patches as needed |
| 2 | v0.2.x | Sprint-end release candidates; v0.2.0 at phase end |
| 3 | v0.3.x | Sprint-end release candidates; v0.3.0 at phase end |
| 4 | v0.4.x | Sprint-end release candidates; v0.4.0 at phase end |
| 5 | v1.0.0-rc.N | Release candidates per sprint; v1.0.0 GA at phase end |

### Backwards Compatibility Guarantees

| API Surface | Guarantee | Policy |
|------------|-----------|--------|
| REST API (Gateway) | Backwards compatible within MINOR version | Additive changes only (new fields, endpoints); deprecated fields retained for 2 MINOR versions |
| Kafka Events | Schema-evolution compatible (Avro backward) | New fields with defaults only; field removal requires 2-version deprecation cycle |
| SDK (NexusPay.js) | Backwards compatible within MAJOR version | Follows independent semver; pinned to API version |
| Helm Chart | Values file backwards compatible within MINOR | New values have defaults; removed values flagged in upgrade notes |
| Database Schema | Forward-migrated via Flyway | No destructive migrations; column additions only; data migrations in separate scripts |

### Upgrade Path

Each release includes:
1. **Flyway migration scripts** for database schema changes
2. **Helm chart upgrade notes** with breaking changes highlighted
3. **API changelog** with deprecated/added/removed endpoints
4. **Kafka schema compatibility report** from Schema Registry CI check

---

## Risk Register

### Top 10 Strategic Risks

| # | Risk | Likelihood | Impact | Phase | Mitigation |
|---|------|-----------|--------|-------|-----------|
| R1 | **PSP vendor lock-in** -- Merchants storing cards directly with PSPs cannot migrate without re-collecting payment methods | High | Critical | 1-4 | Universal card vault (Sprint 4.1) with network tokenization; migration tooling for importing existing PSP tokens |
| R2 | **PCI scope creep** -- Card vault expands PCI DSS scope to the entire platform, increasing compliance cost and audit burden | High | High | 4 | Strict network segmentation; vault deployed as isolated service with dedicated database, CI/CD, and monitoring; CDE boundary documented before Sprint 4.1 begins |
| R3 | **Kafka scaling bottlenecks** -- Single-cluster Kafka cannot handle 5,000 TPS event throughput with acceptable latency | Medium | High | 3-5 | Partition strategy review at each phase boundary; topic compaction for high-volume events; evaluate Kafka tiered storage; contingency plan for cluster splitting |
| R4 | **Monolith coupling** -- Spring Modulith boundaries erode over time, making future microservice extraction difficult | Medium | Medium | 2-4 | ArchUnit tests enforcing module dependency rules in CI; Spring Modulith verification tests; quarterly architecture review |
| R5 | **Key-person risk** -- Critical knowledge concentrated in 1-2 engineers (especially ledger, Kafka, HyperSwitch integration) | High | High | 1-3 | Pair programming on all critical modules; architecture decision records (ADRs) for all major decisions; cross-training rotations each phase |
| R6 | **HyperSwitch dependency** -- Breaking changes in HyperSwitch upstream disrupt NexusPay payment processing | Medium | Critical | 1-5 | Pin HyperSwitch version; maintain adapter layer isolating HyperSwitch API surface; contribute upstream to influence direction; evaluate fallback direct-PSP integration for top 3 PSPs |
| R7 | **Regulatory divergence** -- Payment regulations differ significantly across jurisdictions, complicating cross-border and crypto features | High | High | 3-5 | Jurisdiction-based feature flags; legal review gate before each Phase 3+ sprint; compliance toolkit (Sprint 4.6) as foundational layer |
| R8 | **Database performance ceiling** -- PostgreSQL single-instance performance limits throughput before 5,000 TPS target | Medium | High | 4-5 | Read replicas for analytics queries (Phase 3); connection pooling optimization; evaluate Citus for horizontal sharding if single-node limit reached; ledger partitioning by tenant |
| R9 | **SDK adoption friction** -- Poor developer experience in NexusPay.js or mobile SDKs blocks merchant adoption | Medium | High | 3-4 | Invest in developer documentation, sandbox playground, and code samples; beta program with 5+ merchants before GA; developer satisfaction surveys |
| R10 | **Team scaling challenges** -- Rapid team growth from 5 to 21 engineers introduces coordination overhead and quality regression | High | Medium | 3-5 | Modular team structure aligned to sprint ownership; strong CI/CD gates (test coverage > 80%, mutation testing); architecture guild for cross-team decisions; onboarding playbook per module |

### Risk Review Schedule

| Frequency | Activity |
|-----------|---------|
| Per sprint | Risk review in sprint retrospective; update likelihood/impact |
| Per phase | Full risk register review with new risks added; retired risks archived |
| Quarterly | Executive risk summary with mitigation investment requests |

---

## Solo Developer Critical Path

This section provides a realistic assessment of what a solo developer (with AI assistance) can accomplish and where the project needs additional contributors.

### Phases 1-2: Solo-Achievable (~40 weeks)

Phases 1 and 2 are achievable by a solo developer. The scope is well-defined, the technology stack is familiar (Spring Boot, PostgreSQL, Kafka), and the work is primarily backend infrastructure with clear acceptance criteria. Phase 1 is complete; Phase 2 (Weeks 17-44) adds 7 sprints of production hardening that a single developer can execute sequentially.

### Phase 3: Solo-Feasible with AI (~36 weeks instead of 24)

Phase 3 (Intelligence & Global) is feasible solo but will take approximately 36 weeks rather than the 24-week estimate designed for a small team. The additional time accounts for:
- ML model training and evaluation cycles requiring iteration
- Cross-border/FX testing across multiple currency corridors
- Fraud rules engine requiring extensive test scenario coverage
- No parallelization of independent sprints

With AI-assisted development (code generation, test scaffolding, documentation), a solo developer can maintain quality while absorbing the ~50% timeline extension.

### Phases 4-5: Require Contributors/Team

Phases 4 and 5 are not realistically achievable solo:
- Phase 4 introduces mobile SDKs (iOS + Android), requiring platform-specific expertise
- Phase 5 spans embedded finance, real-time rails, and compliance certification, each a specialization
- External dependencies (Visa VTS enrollment, SOC 2 audit, pen testing) require coordination bandwidth that exceeds a solo developer's capacity
- The breadth of technology (Swift, Kotlin, ONNX, ISO 20022) exceeds what one person can master effectively

### Community Inflection Point: Phase 3 Completion

Phase 3 completion (v0.3.0) represents the "community inflection point" -- the moment NexusPay becomes compelling enough to attract contributors:
- Smart routing, fraud prevention, and multi-currency make the platform genuinely useful
- The project demonstrates sustained execution across 3 phases
- Contributors can work on well-defined, isolated modules (vault, marketplace, mobile SDKs)
- Open-source community building should be an active focus throughout Phase 3

### Minimum Viable Team for v1.0.0

| Role | Phase Needed | Justification |
|------|-------------|---------------|
| Core platform developer (lead) | All phases | Architecture, backend, infrastructure |
| Mobile developer | Phase 4+ | iOS and Android SDKs require platform expertise |
| DevOps / SRE | Phase 4+ | Multi-region deployment, chaos engineering, production operations |
| Security / compliance | Phase 5 | SOC 2 preparation, PCI DSS audit coordination, pen test management |
| Community contributors (2-3) | Phase 4+ | PSP connectors, e-commerce plugins, documentation |

---

## Success Metrics by Phase

### Phase 1 (COMPLETE): Foundation Validation

| Metric | Target | Status |
|--------|--------|--------|
| Core payment flows functional | 4 (create, capture, refund, void) | Achieved |
| Integration test coverage | > 70% | Achieved |
| Deployment time (Helm) | < 15 min | Achieved |
| Ledger accuracy | 100% (zero discrepancy) | Achieved |

### Phase 2: Production Readiness

| Metric | Target |
|--------|--------|
| Live tenants in production | >= 3 |
| Payment throughput (sustained) | 500 TPS |
| Uptime (monthly) | >= 99.95% |
| Reconciliation auto-match rate | >= 95% |
| Mean time to detect (MTTD) incidents | < 5 min |
| Mean time to recover (MTTR) | < 30 min |
| Security vulnerabilities (critical/high) | 0 open |

### Phase 3: Intelligence and Scale

| Metric | Target |
|--------|--------|
| Live tenants in production | >= 15 |
| Monthly payment volume | >= $5M |
| Authorization rate improvement (smart routing) | >= 2% over static |
| Cost reduction (smart routing) | >= 5% lower processing fees |
| SDK integration time (new merchant) | < 2 hours |
| Currencies supported | >= 25 |
| Fraud detection rate (known patterns) | >= 90% |

### Phase 4: Platform Growth

| Metric | Target |
|--------|--------|
| Live tenants in production | >= 50 |
| Monthly payment volume | >= $50M |
| Marketplace merchants onboarded | >= 100 connected accounts |
| Network tokenization rate | >= 70% of stored cards |
| Mobile SDK adoption | >= 10 apps in production |
| PCI DSS Level 1 certification | Obtained |

### Phase 5: Market Leadership

| Metric | Target |
|--------|--------|
| Live tenants in production | >= 200 |
| Monthly payment volume | >= $500M |
| Payment throughput (sustained) | 5,000 TPS |
| ML routing auth rate lift | >= 3% over rule-based |
| Real-time payment volume share | >= 10% of total volume |
| SOC 2 Type II report | Issued |
| Platform uptime (annual) | >= 99.99% |
| Time to onboard new merchant (self-service) | < 10 min |

---

## Competitive Parity Milestones

This table tracks when NexusPay achieves feature parity with key competitors across specific capability dimensions.

| Competitor | Capability Dimension | Parity Phase | Sprint | Notes |
|-----------|---------------------|-------------|--------|-------|
| **Spreedly** | Multi-PSP orchestration | Phase 1 | 1.x | Core HyperSwitch integration |
| **Spreedly** | Universal card vault | Phase 4 | 4.1 | Network tokenization included |
| **Spreedly** | 200+ gateway connectors | Phase 5+ | -- | HyperSwitch community-driven; NexusPay covers top 20 |
| **Primer.io** | Payment orchestration | Phase 1 | 1.x | Core flow routing |
| **Primer.io** | No-code workflow builder | Phase 4 | 4.4 | Visual drag-and-drop builder |
| **Primer.io** | Fraud integration | Phase 3 | 3.1 | FRM provider abstraction |
| **ProcessOut** | Payment analytics | Phase 3 | 3.6 | Auth rates, decline analysis, PSP scoring |
| **ProcessOut** | Smart routing | Phase 3 | 3.3 | Cost, success-rate, and latency-based |
| **Modern Treasury** | Double-entry ledger | Phase 1 | 1.x | SERIALIZABLE isolation |
| **Modern Treasury** | Reconciliation | Phase 2 | 2.3 | 3-way matching engine |
| **Modern Treasury** | Payment operations | Phase 2 | 2.4-2.5 | Disputes + billing |
| **Stripe** | Payment processing | Phase 1 | 1.x | Via HyperSwitch PSP connectors |
| **Stripe** | Client SDK | Phase 3 | 3.5 | NexusPay.js + React components |
| **Stripe** | Subscription billing | Phase 2 | 2.5 | State machine, dunning, proration |
| **Stripe** | Marketplace/Connect | Phase 4 | 4.2 | Split payments, connected accounts |
| **Stripe** | Card issuing | Phase 5 | 5.3 | Via issuing partner |
| **Stripe** | Treasury/BaaS | Phase 5 | 5.3 | Embedded finance module |
| **Stripe** | Tax calculation | Phase 4 | 4.6 | Tax provider abstraction |
| **Adyen** | Omnichannel (online + POS) | Phase 4 | 4.5 | Mobile SDK + SoftPOS |
| **Adyen** | Cross-border/FX | Phase 3 | 3.2 | Multi-currency routing + FX engine |
| **Adyen** | Risk management | Phase 3 | 3.1 | Rules engine + FRM integration |
| **Adyen** | Network tokenization | Phase 4 | 4.1 | Visa/MC/Amex token services |
| **Chargebacks911** | Dispute management | Phase 2 | 2.4 | Lifecycle + Verifi/Ethoca |
| **Chargebacks911** | Auto-representment | Phase 2 | 2.4 | Rule-based (stubs; full in Phase 3+) |

### Competitive Positioning Summary by Phase

| Phase | Competitive Position |
|-------|---------------------|
| Phase 1 | Matches Spreedly/Primer on basic orchestration; unique in combining ledger + IAM + events |
| Phase 2 | Closes Modern Treasury gap (reconciliation); matches Chargebacks911 on disputes; production-ready |
| Phase 3 | Matches ProcessOut on analytics; Primer on fraud; Adyen on cross-border; developer experience via SDK |
| Phase 4 | Matches Stripe Connect on marketplaces; Spreedly on vault; Adyen on omnichannel; Primer on workflows |
| Phase 5 | Matches Stripe on breadth (issuing, treasury, tax); adds next-gen capabilities (real-time, AI, crypto) |

---

## Module Inventory by Phase

| Phase | New Modules | Cumulative Total |
|-------|------------|-----------------|
| 1 | `nexuspay-app`, `nexuspay-orchestration`, `nexuspay-ledger`, `nexuspay-events`, `nexuspay-iam`, `nexuspay-gateway`, `nexuspay-deploy` | 7 |
| 2 | `nexuspay-tenant`, `nexuspay-workflow`, `nexuspay-reconciliation`, `nexuspay-disputes`, `nexuspay-billing` | 12 |
| 3 | `nexuspay-fraud`, `nexuspay-fx`, `nexuspay-routing`, `nexuspay-analytics`, `nexuspay-sdk` (separate repo) | 17 |
| 4 | `nexuspay-vault`, `nexuspay-marketplace`, `nexuspay-b2b`, `nexuspay-workflows-ui`, `nexuspay-compliance`, `nexuspay-sdk-ios`, `nexuspay-sdk-android` (separate repos) | 24 |
| 5 | `nexuspay-realtime`, `nexuspay-ml`, `nexuspay-embedded-finance`, `nexuspay-crypto`, `nexuspay-admin`, `nexuspay-marketplace-admin` | 30 |

---

## Cross-Phase Dependencies

```
Phase 1 (Foundation)
  |
  v
Phase 2
  Sprint 2.1 (Multi-tenancy) -----> Required by all subsequent sprints
  Sprint 2.2 (CDC + Temporal) ----> Required by 2.3, 2.5 (workflow scheduling)
  Sprint 2.3 (Reconciliation) ----> Required by 2.4 (dispute evidence)
  Sprint 2.5a/2.5b (Billing) ----> Required by 3.x (subscription data for analytics)
  Sprint 2.7 (Observability) -----> Required by 3.3, 3.6 (metrics data)
  |
  v
Phase 3
  Sprint 3.1 (Fraud) ------------> Required by 4.4 (fraud as workflow node)
  Sprint 3.2 (FX) ---------------> Required by 3.3 (currency-aware routing), 5.4 (crypto FX)
  Sprint 3.3 (Smart Routing) ----> Required by 3.6, 5.2 (routing baseline for ML)
  Sprint 3.5 (SDK) --------------> Required by 4.1, 4.5 (tokenization flow)
  Sprint 3.6 (Analytics) --------> Required by 5.2 (ML training data)
  |
  v
Phase 4
  Sprint 4.1 (Vault) ------------> Required by 4.5 (mobile tokenization), 5.1 (account storage)
  Sprint 4.2 (Marketplace) ------> Required by 4.3 (vendor accounts)
  Sprint 4.3 (B2B) --------------> Required by 5.3 (virtual card infra)
  Sprint 4.6 (Compliance) -------> Required by 5.3, 5.4 (regulated activities)
  |
  v
Phase 5
  Sprint 5.6 (Hardening) --------> Depends on ALL prior phases
```

---

## Appendix A: Sprint-to-Week Mapping

| Sprint | Weeks | Dates (Estimated) |
|--------|-------|-------------------|
| Phase 1 | 1-16 | Complete |
| Sprint 2.1 | 17-20 | Current planning window |
| Sprint 2.2 | 21-24 | |
| Sprint 2.3 | 25-28 | |
| Sprint 2.4 | 29-32 | |
| Sprint 2.5a | 33-36 | |
| Sprint 2.5b | 37-40 | |
| Sprint 2.7 | 41-44 | Overlaps with 2.5b |
| Sprint 3.1 | 45-48 | |
| Sprint 3.2 | 49-52 | |
| Sprint 3.3 | 53-56 | |
| Sprint 3.4 | 57-60 | |
| Sprint 3.5 | 61-64 | |
| Sprint 3.6 | 65-68 | |
| Sprint 4.1 | 69-72 | |
| Sprint 4.2 | 73-76 | |
| Sprint 4.3 | 77-80 | |
| Sprint 4.4 | 81-84 | |
| Sprint 4.5 | 85-88 | |
| Sprint 4.6 | 89-92 | |
| Sprint 5.1 | 93-98 | |
| Sprint 5.2 | 99-104 | |
| Sprint 5.3 | 105-110 | |
| Sprint 5.4 | 111-116 | |
| Sprint 5.5 | 117-122 | |
| Sprint 5.6 | 123-128 | |

---

## Appendix B: Staffing Ramp

| Phase | Engineers | Support Roles | Total | Delta |
|-------|----------|--------------|-------|-------|
| 1 | 3 | 1 (PM 0.5, QA 0.5) | 4 | -- |
| 2 | 4 | 1.5 (PM 0.5, QA 1) | 5.5 | +1.5 |
| 3 | 8 | 2 (PM 1, QA 1) | 10 | +4.5 |
| 4 | 12 | 4 (PM 1, QA 2, SecEng 1) | 16 | +6 |
| 5 | 15 | 6.5 (PM 1.5, QA 2, SecEng 2, TW 1) | 21.5 | +5.5 |

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-03-15 | NexusPay Team | Initial strategic roadmap |
