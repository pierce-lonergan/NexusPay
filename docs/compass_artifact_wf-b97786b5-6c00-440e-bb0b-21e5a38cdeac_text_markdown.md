# Enterprise Payment Platform: Definitive Architecture Blueprint

**Building an enterprise operations layer on HyperSwitch demands a modular, event-driven architecture that treats payment correctness as a non-negotiable constraint.** This document defines every component, integration point, data flow, and technology choice for a Java 21 / Spring Boot 3.2+ platform that fills HyperSwitch's enterprise gaps — IAM, approval workflows, double-entry ledger, reconciliation, observability, dispute management, ML routing, and compliance automation. The architecture follows patterns proven at Stripe, Uber, Airbnb, and Square while remaining deployable as a single Helm chart for mid-to-large enterprises. Every decision includes alternatives considered and explicit justification.

The platform positions HyperSwitch as the payment execution engine — handling PSP routing, card vaulting, and connector integrations — while our Java layer provides the enterprise control plane. This separation keeps PCI DSS scope narrow (HyperSwitch handles raw card data; our layer works exclusively with tokenized references) while delivering the operational capabilities enterprises require.

---

## System architecture and service decomposition

### Why Spring Modulith over full microservices

The architecture uses **Spring Modulith with hexagonal architecture** rather than day-one microservices. This mirrors the actual evolution of every major payment company: Stripe started as a Ruby monolith, Uber's Gulfstream began monolithic before sharding, Airbnb migrated from Rails monolith to SOA only after product-market fit.

**Alternatives considered:**

- **Full microservices from day one**: Rejected. Introduces distributed transaction complexity, requires service mesh, increases operational burden, and slows iteration for a new project. Uber spent years migrating to their stream-processing architecture — premature for an open-source project seeking adoption.
- **Pure monolith without module boundaries**: Rejected. Without enforced boundaries, the codebase degrades into a big ball of mud. Payment systems demand clear bounded contexts.
- **Spring Modulith**: Selected. Enforces bounded contexts via package-level verification (`ApplicationModules.verify()`), supports event-driven inter-module communication, provides a natural extraction path to microservices when scaling demands it, and keeps deployment as a single JAR. ACID transactions across modules eliminate distributed saga overhead for internal operations.

Each module uses **hexagonal architecture (ports and adapters)** internally, ensuring PSP integrations, database choices, and external service dependencies remain swappable without domain logic changes.

### Bounded contexts and responsibility mapping

The platform decomposes into **eight bounded contexts**, each a Spring Modulith module:

| Module | Responsibility | Key Domain Objects |
|--------|---------------|-------------------|
| **gateway-api** | API gateway, authentication, rate limiting, request routing | ApiKey, RateLimitPolicy, RequestContext |
| **payment-orchestration** | Payment lifecycle coordination, HyperSwitch integration, saga orchestration | PaymentOrder, PaymentAttempt, SagaExecution |
| **ledger** | Double-entry bookkeeping, balance management, financial reporting | Account, JournalEntry, Posting, Balance |
| **iam** | Identity, multi-tenant RBAC/ABAC, approval workflows, audit | User, Role, Permission, ApprovalRequest, Tenant |
| **reconciliation** | Settlement file ingestion, three-way matching, exception management | SettlementFile, MatchResult, Exception, ReconciliationRun |
| **observability** | Payment metrics, canonical log lines, anomaly detection, PSP health scoring | MetricSnapshot, AnomalyAlert, PSPHealthScore, CanonicalLogEntry |
| **dispute** | Chargeback lifecycle, evidence management, representment tracking, deadline enforcement | Dispute, Evidence, DisputeDeadline, RepresentmentPackage |
| **workflow** | No-code workflow builder, routing rule engine, conditional logic execution | WorkflowDefinition, WorkflowExecution, RoutingRule, Condition |

**Inter-module communication** uses Spring's `ApplicationEventPublisher` for synchronous, transactional events within the modulith and Kafka for events that must survive process restarts or reach external consumers. The `@ApplicationModuleListener` annotation combines `@Async`, `@Transactional`, and `@TransactionalEventListener` for reliable event processing.

### Package structure following hexagonal architecture

```
com.paymentplatform/
├── gateway/
│   ├── api/              # Public module API (interfaces)
│   ├── adapter/in/web/   # REST controllers, webhook receivers
│   ├── adapter/out/      # HyperSwitch client, rate limiter
│   ├── application/      # Use case implementations
│   ├── domain/           # Entities, value objects, ports
│   └── config/           # Spring configuration
├── ledger/
│   ├── api/
│   ├── adapter/in/       # Event listeners, REST endpoints
│   ├── adapter/out/      # JPA repositories, Kafka producers
│   ├── application/
│   ├── domain/
│   │   ├── model/        # Account, JournalEntry, Posting
│   │   ├── port/in/      # PostTransactionUseCase, QueryBalanceUseCase
│   │   └── port/out/     # AccountRepository, EventPublisher
│   └── config/
├── iam/
│   └── ... (same pattern)
├── reconciliation/
├── observability/
├── dispute/
├── workflow/
└── PaymentPlatformApplication.java
```

The domain layer has **zero external dependencies** — no Spring, no JPA, no Kafka. Business rules live entirely in POJOs. The application layer orchestrates use cases. Adapters connect to infrastructure. This structure means adding a new PSP connector requires only implementing a new adapter — zero domain changes.

---

## HyperSwitch integration layer

### API surface and communication pattern

HyperSwitch exposes a comprehensive REST API organized around **Payments, Refunds, Disputes, Customers, Mandates, Payouts, Routing, and Account Management**. The account hierarchy follows **Organization → Merchant → Business Profile** (1:many:many), which maps directly to our multi-tenancy model.

**Communication pattern selected: Direct HTTP via Spring WebClient**

Alternatives considered:
- **Sidecar deployment**: HyperSwitch is stateful (PostgreSQL + Redis connections), making per-pod sidecar deployment wasteful and operationally complex. Rejected.
- **Service mesh routing (Istio)**: Adds **1-5ms latency per hop** for Istio sidecar proxies. Appropriate for service-to-service security but not the communication mechanism itself. Used for mTLS, not routing logic. Supplementary.
- **gRPC**: HyperSwitch does not expose a gRPC interface. Would require a custom adapter layer. Rejected.
- **Direct HTTP with WebClient**: Selected. HyperSwitch processes requests in **~25ms**; same-cluster Kubernetes DNS resolution adds **<1ms**. Total internal overhead is negligible compared to the **500-2000ms** external PSP round-trip that dominates payment latency.

Since HyperSwitch provides no official Java SDK, the platform generates a **type-safe client** from HyperSwitch's OpenAPI specification using `openapi-generator` with custom templates, wrapped in a `HyperSwitchGatewayPort` interface that the domain layer depends on:

```java
public interface HyperSwitchGatewayPort {
    PaymentResponse createPayment(CreatePaymentRequest request);
    PaymentResponse confirmPayment(String paymentId, ConfirmRequest request);
    PaymentResponse capturePayment(String paymentId, CaptureRequest request);
    RefundResponse createRefund(CreateRefundRequest request);
    DisputeResponse getDispute(String disputeId);
}
```

### Webhook consumption

HyperSwitch emits **18 webhook event types** signed with **HMAC-SHA512** (header: `x-webhook-signature-512`). The retry schedule spans 24 hours: 1min → 5min(×2) → 10min(×5) → 1hr(×5) → 6hr(×3). Our webhook receiver:

1. Verifies HMAC-SHA512 signature immediately
2. Persists the raw event to the `inbound_webhooks` table within a database transaction
3. Returns HTTP 200 within **100ms** (before business logic processing)
4. Publishes to Kafka via the transactional outbox pattern for async processing
5. Deduplicates using HyperSwitch's `event_id` field

### Data synchronization strategy

**Primary mechanism: Debezium CDC** reading HyperSwitch's PostgreSQL WAL. This captures every `INSERT`, `UPDATE`, and `DELETE` on HyperSwitch's `payment_intent`, `payment_attempt`, `refund`, and `dispute` tables with millisecond latency and zero impact on HyperSwitch's performance.

**Supplementary: HyperSwitch webhooks** for application-level business events carrying richer semantic meaning.

**Fallback: API polling** runs as a scheduled reconciliation job every 15 minutes, querying HyperSwitch's list endpoints with `updated_after` filters to detect and repair any CDC sync gaps.

Alternatives considered: Pure webhook-based sync (rejected — webhooks only emit for state changes the application explicitly publishes, potentially missing edge cases or direct database modifications). Pure API polling (rejected — adds seconds of latency and unnecessary load on HyperSwitch).

---

## Data architecture and database strategy

### Primary database: PostgreSQL 16

**PostgreSQL** serves as the primary database for all modules. **SERIALIZABLE Snapshot Isolation (SSI)** prevents all transaction anomalies — critical for financial ledger correctness. Advisory locks enable application-level idempotency enforcement. `SELECT FOR UPDATE SKIP LOCKED` powers payment queue processing without contention.

**Alternatives considered:**
- **CockroachDB**: SERIALIZABLE by default with distributed consensus, but **2-5x write latency** due to Raft overhead. Best when multi-region is a day-one requirement. Planned as migration target for Phase 4.
- **YugabyteDB**: Best PostgreSQL compatibility among distributed databases (~95%), used by Wells Fargo and Fiserv for payment ledgers. Selected as the **recommended multi-region migration path** due to superior PG compatibility over CockroachDB.
- **PostgreSQL selected for launch**: Lowest operational complexity, best ecosystem maturity, lowest write latency (<5ms), and the team needs only one database skill set. Multi-region handled via Patroni + streaming replication initially.

### Double-entry ledger schema

The ledger follows patterns from **Square's Books** (immutable, double-entry on Cloud Spanner), **Modern Treasury** (optimistic locking, hierarchical accounts), and **Stripe's Ledger** (projection/validation layer with data quality scores). All amounts stored as `BIGINT` in minor currency units (cents) — **never floating point**.

```sql
CREATE TABLE ledger_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_id       UUID NOT NULL REFERENCES ledgers(id),
    parent_id       UUID REFERENCES ledger_accounts(id),
    code            VARCHAR(50) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    account_type    VARCHAR(20) NOT NULL,  -- asset, liability, equity, revenue, expense
    normal_balance  VARCHAR(10) NOT NULL,  -- debit, credit
    currency_code   CHAR(3) NOT NULL,
    posted_balance  BIGINT NOT NULL DEFAULT 0,
    pending_balance BIGINT NOT NULL DEFAULT 0,
    available_balance BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,  -- optimistic concurrency
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(ledger_id, code)
);

CREATE TABLE journal_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_id       UUID NOT NULL REFERENCES ledgers(id),
    idempotency_key VARCHAR(255) UNIQUE,
    description     TEXT,
    effective_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at       TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, posted, voided
    reversal_of     UUID REFERENCES journal_entries(id),
    external_ref    VARCHAR(255),  -- link to payment_id
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id),
    account_id       UUID NOT NULL REFERENCES ledger_accounts(id),
    amount           BIGINT NOT NULL,     -- always positive
    direction        VARCHAR(10) NOT NULL, -- debit, credit
    currency_code    CHAR(3) NOT NULL,
    account_version  BIGINT NOT NULL,
    metadata         JSONB DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Balance strategy: Hybrid running balance** (Square's and Modern Treasury's approach). The `posted_balance` on `ledger_accounts` updates atomically within the same transaction as posting inserts, with a `version` counter for optimistic concurrency. A periodic reconciliation job verifies running balances match computed `SUM(postings)` — catching any drift.

**Multi-currency handling**: Separate account per currency per merchant. Currency conversion is a distinct journal entry debiting the source-currency account and crediting the destination-currency account, with the exchange rate stored in metadata for auditability.

**Immutability**: Following Square's Books, postings are append-only. Corrections create new reversing journal entries rather than updating existing records.

### Event sourcing storage

**PostgreSQL-backed event store** for the core ledger event log. An `events` table with `(aggregate_id, sequence_num)` uniqueness constraint provides optimistic concurrency. An `event_outbox` table enables the transactional outbox pattern — events written atomically with business data, then captured by Debezium for Kafka publishing.

Alternatives considered: EventStoreDB (purpose-built but adds operational complexity and SSPL licensing concerns for open-source project), Axon Server (JVM-centric, commercial enterprise edition), Kafka as event store (cannot efficiently load per-aggregate event streams — rejected per industry consensus).

### Analytics database: TimescaleDB

**TimescaleDB** extends PostgreSQL with hypertables for time-series payment analytics. Continuous aggregates auto-maintain hourly/daily/monthly rollups of authorization rates, decline codes, and transaction volumes — eliminating custom batch aggregation jobs. Cloudflare reported **5-35x lower query latency** after migrating from ClickHouse to TimescaleDB.

Alternative considered: ClickHouse (superior for massive OLAP aggregations across billions of denormalized rows, but requires a separate system, different SQL dialect, and ETL pipeline). Reserved as a future addition for very high-scale analytics.

### Caching and idempotency: Valkey

**Valkey 8.x** (the Linux Foundation's Redis fork) provides **37% higher write throughput** and **30% faster p99 latencies** versus Redis, with identical API compatibility. All existing Redis clients work unchanged. AWS, Google, Oracle, and DigitalOcean offer managed Valkey services, priced **20-33% below Redis**.

Uses in the platform: idempotency key storage (`SET idempotency:{key} {payment_id} NX EX 86400`), sliding-window rate limiting, merchant configuration caching with pub/sub invalidation, and distributed locking via Valkey 9.0's `DELIFEQ` command.

---

## Event-driven architecture

### Kafka as the integration event backbone

Apache Kafka serves as the system's nervous system — not the primary event store (that's PostgreSQL), but the **integration event bus** connecting all modules and external consumers. Stripe describes Kafka as their "financial source of truth" with **99.9999% availability**. PayPal processes over a **trillion events per day** through Kafka.

### Exactly-once delivery via transactional outbox

The **transactional outbox pattern with Debezium** eliminates the dual-write problem (writing to both database and Kafka atomically):

1. Application writes business data AND an event row to the `event_outbox` table in the **same database transaction**
2. Debezium's PostgreSQL connector reads the WAL and captures outbox INSERTs
3. Debezium's `outbox.EventRouter` SMT routes events to appropriate Kafka topics
4. Consumers process events with **idempotent handlers** (deduplication via `event_id`)

This provides **at-least-once delivery to Kafka** (guaranteed if the database transaction commits) with **effectively-once processing** via consumer-side deduplication. Direct Kafka publishing without the outbox was rejected because it creates inconsistency on partial failures.

### Topic design and naming

**Convention**: `{domain}.{aggregate}.{event}` with past-tense event names.

| Topic | Partition Key | Cleanup | Retention |
|-------|--------------|---------|-----------|
| `payments.Payment.Authorized` | payment_id | delete | 90 days |
| `payments.Payment.Captured` | payment_id | delete | 90 days |
| `payments.Payment.Settled` | payment_id | delete | 90 days |
| `payments.Refund.Initiated` | payment_id | delete | 90 days |
| `payments.Refund.Completed` | payment_id | delete | 90 days |
| `disputes.Dispute.Opened` | dispute_id | delete | 1 year |
| `ledger.JournalEntry.Posted` | account_id | delete | 1 year |
| `reconciliation.Match.Completed` | recon_run_id | delete | 90 days |
| `observability.Anomaly.Detected` | merchant_id | delete | 30 days |
| `iam.Approval.Requested` | approval_id | delete | 90 days |
| `platform.Config.Updated` | merchant_id | compact | infinite |

**Partition key rationale**: Payment ID ensures all lifecycle events for a single payment land on the same partition, guaranteeing ordering (Created → Authorized → Captured → Settled). Partition count targets **2x expected consumer parallelism** (e.g., 24 partitions across 3 brokers for 12 consumer instances).

Long-term archival uses Kafka Connect S3 Sink Connector to write events to object storage for **7-year regulatory retention** (PCI DSS, SOX, MiFID II), avoiding unbounded Kafka storage costs.

### Framework: Spring Kafka over Spring Cloud Stream

**Spring Kafka** (`spring-kafka`) provides the low-level control payment processing demands: precise transactional boundaries, manual offset commits, custom error handlers, and direct access to Kafka's transactional producer API.

Spring Cloud Stream was rejected because its binder abstraction obscures Kafka-specific behaviors critical to financial correctness. The abstraction's main benefit — broker swappability — is irrelevant once committed to Kafka's exactly-once semantics. Stripe, PayPal, and Uber all use native Kafka clients or internal frameworks, not Spring Cloud Stream.

### Dead letter queue strategy

Following Uber's pattern, failed events progress through **tiered retry topics** before reaching the DLQ:

```
payment-events           → main processing
payment-events-retry-1   → 1-minute delay
payment-events-retry-2   → 5-minute delay
payment-events-retry-3   → 30-minute delay
payment-events-dlq       → dead letter (30-day retention)
```

Non-retryable exceptions (validation errors, schema incompatibilities, permanent PSP rejections) bypass retry topics and go directly to DLQ. DLQ entries trigger PagerDuty/Slack alerts and populate a manual review dashboard for operations teams.

### Schema evolution: Avro with Schema Registry

**Avro** with Confluent Schema Registry in **FULL_TRANSITIVE compatibility mode** (checked against all previous versions, not just the last). Every event includes a metadata envelope:

```json
{
  "metadata": {
    "event_id": "uuid",
    "event_type": "payments.Payment.Captured",
    "version": "2.1",
    "timestamp": "2026-03-02T15:30:00Z",
    "source": "payment-orchestration",
    "correlation_id": "trace-id",
    "tenant_id": "org_456"
  },
  "payload": { ... }
}
```

Alternative considered: Protobuf (stronger typing, multi-language code generation, field tag-based renaming). Selected Avro because the platform is JVM-first, and Avro is the native Confluent ecosystem format with first-class Schema Registry integration. Teams with multi-language services should consider Protobuf instead.

---

## Saga orchestration with Temporal

### Why Temporal over alternatives

**Temporal.io** orchestrates all payment workflows requiring multi-step coordination with compensation logic.

| Engine | License | Saga Support | Java SDK | Verdict |
|--------|---------|-------------|----------|---------|
| **Temporal** | MIT | Native `Saga` class | Excellent, official payment samples | **Selected** |
| Camunda 8 | Camunda License v1 (post-8.5) | BPMN compensation events | Good | Rejected — requires paid production license, incompatible with open-source project |
| Cadence | Apache 2.0 | Native | Good | Viable but smaller community; Temporal is the clear successor |
| Axon Framework | Partial open-source | `@Saga` annotation | Excellent Spring integration | Rejected — tightly coupled to CQRS/ES paradigm, commercial enterprise edition |

Temporal guarantees **durable execution**: every workflow step persists to its event history. Worker or cluster failures trigger automatic replay from the last checkpoint. The Temporal Java SDK provides `Saga` class with `saga.addCompensation()` for registering rollback actions.

### Core payment saga: authorize → capture → settle

```
State Machine: PENDING → AUTHORIZED → CAPTURED → SETTLED → COMPLETED

Step 1 - Authorize:
  Action: Call HyperSwitch POST /payments/{id}/confirm
  Compensation: Call POST /payments/{id}/cancel (void authorization — free)
  Timeout: 30 seconds for PSP response; auth hold expires in 7-30 days

Step 2 - Capture:
  Action: Call HyperSwitch POST /payments/{id}/capture
  Compensation: Call POST /refunds (full refund — incurs fees)
  Timeout: Must capture before auth expiry (7-10 days)

Step 3 - Settle:
  Action: Record in ledger, await PSP settlement confirmation
  Compensation: Record refund intent, execute when settlement completes
  Timeout: T+1 to T+7 business days depending on PSP

Step 4 - Notify:
  Action: Send webhook, update merchant dashboard
  Compensation: None (retryable transaction, not compensable)
```

**Key compensation rule**: Before settlement → void (free). After settlement → refund (incurs interchange fees). All compensations are idempotent — voiding an already-voided authorization is a no-op.

### Idempotency in sagas

Temporal generates idempotency keys via `Workflow.getInfo().getRunId() + "-" + Activity.getExecutionContext().getInfo().getActivityId()`. These keys propagate to HyperSwitch and downstream PSP APIs that accept `Idempotency-Key` headers, preventing duplicate charges on activity retries.

### Additional saga patterns

**Refund workflow**: Validate eligibility → Call PSP refund API with idempotency key → Update ledger (credit customer, debit revenue) → Notify customer → Update order status. Partial refunds track remaining balance for potential additional refunds.

**Dispute/chargeback workflow**: Chargeback received → Start deadline timer (Visa/Mastercard: 20-30 days) → Evidence gathering → Submit representment → Await ruling (45-75 days) → Resolution. Temporal timers enforce card network deadlines — missing a deadline means automatic loss.

**Subscription billing cycle**: Trial → Active → Renewal Due → Payment Processing → (success) Renewed / (failure) Dunning. Smart retry: Day 1, Day 3, Day 7, Day 15. Hard declines (expired card) notify customer immediately without retry. Soft declines (insufficient funds) retry with optimized timing.

### No-code workflow builder

A visual workflow designer built with **React Flow** (MIT license, used by Stripe and Typeform) renders custom node types for payment-specific blocks: Authorize, Capture, Route, 3DS Check, Fraud Check, Conditional Branch, Delay, Notify. The designer serializes workflows to JSON, which the backend translates into Temporal workflow definitions.

This approach follows Primer.io's model — enabling non-technical teams (payment leads, operations managers) to manage payment flows without engineering involvement. Alternative considered: Camunda's BPMN Modeler (native visual design but licensing prevents open-source use).

---

## Enterprise IAM and security architecture

### Identity provider: Keycloak with realm-per-tenant

**Keycloak** serves as the authorization server, providing SAML 2.0 + OIDC support, multi-tenant realms, user federation, MFA, and admin console out-of-box. Each merchant organization gets its own Keycloak realm with isolated users, roles, clients, and IdP configurations.

Alternatives considered: Spring Authorization Server (no admin UI, requires significant development), external IdPs like Auth0/Okta (vendor lock-in, cost at scale). Keycloak selected for comprehensive feature set and Apache 2.0 licensing.

Spring Boot services validate JWTs from Keycloak using `JwtIssuerAuthenticationManagerResolver` for multi-tenant issuer resolution. The `Jwt` claims include `tenant_id`, `roles`, and `scope`.

### Authorization: RBAC base + OPA for fine-grained policies

**Coarse-grained authorization** uses RBAC via Keycloak realm roles mapped to Spring Security `GrantedAuthority`. Seven platform roles cover the permission matrix:

- **Platform Admin**: Full system control, tenant management
- **Org Admin**: Organization-level user/role management
- **Finance Manager**: View all payments, initiate and approve refunds, financial reports
- **Operator**: View payments, initiate refunds, manage disputes
- **Compliance Officer**: Audit logs, compliance reports, read-only payment data
- **Developer**: API key management, webhook configuration, test mode
- **Viewer**: Dashboard and payment summary access only

**Fine-grained authorization** uses **Open Policy Agent (OPA)** with policies written in Rego. The OPA Spring Boot SDK provides `OPAAuthorizationManager` that integrates directly with Spring Security 6's `SecurityFilterChain`. Policies evaluate attributes like transaction amount, time of day, tenant-specific rules, and resource ownership:

```rego
package payment.authz
default allow = false

# Finance managers can approve refunds under $10K
allow {
    input.method == "POST"
    input.path = ["api", "v1", "refunds", _, "approve"]
    input.roles[_] == "FINANCE_MANAGER"
    input.body.amount <= 1000000  # cents
}

# Only admins can approve refunds over $10K
allow {
    input.method == "POST"
    input.path = ["api", "v1", "refunds", _, "approve"]
    input.roles[_] == "PLATFORM_ADMIN"
}
```

### Four-eyes principle (maker-checker)

Sensitive operations require dual control — one person initiates, a separate person approves. The system enforces that the maker cannot be their own checker at the application level.

| Operation | Risk | Required Approvals |
|-----------|------|--------------------|
| Refunds > $1,000 | High | 1 checker (Finance Manager) |
| Refunds > $10,000 | Critical | 2 checkers (Finance Manager + Admin) |
| Connector configuration changes | High | 1 checker |
| API key creation/rotation | Medium-High | 1 checker |
| Routing rule changes | High | 1 checker |
| Bulk operations (mass refunds) | Critical | 2 checkers |

State machine: `DRAFT → PENDING_APPROVAL → APPROVED → EXECUTED` (or `REJECTED → DRAFT` for revision, `EXPIRED` after timeout). Every state change logged immutably with who, what, when, IP address, and reason.

### API key management (Stripe-inspired)

| Key Type | Prefix | Usage | Visibility |
|----------|--------|-------|-----------|
| Publishable | `pk_live_`, `pk_test_` | Client-side, low-risk | Public |
| Secret | `sk_live_`, `sk_test_` | Server-side, all operations | Shown once at creation |
| Restricted | `rk_live_`, `rk_test_` | Scoped permissions | Shown once |

Keys are cryptographically random, SHA-256 hashed for storage (only the last 4 characters retained for identification). Support for IP allowlisting (CIDR notation), optional TTL, granular permission scoping, and rotation with configurable overlap period (old key valid 24-72 hours). Test keys can never affect live data.

### Service-to-service security

**Istio service mesh** provides transparent **mTLS** between all pods. Istiod acts as the internal Certificate Authority issuing SPIFFE-based identities (`spiffe://cluster.local/ns/{namespace}/sa/{service-account}`). `PeerAuthentication` enforces STRICT mode in production. `AuthorizationPolicy` implements default-deny with explicit allow rules per service pair.

### Encryption strategy

- **In transit**: TLS 1.3 for external traffic, mTLS via Istio internally, TLS-encrypted database connections
- **At rest**: AES-256 with envelope encryption. Data Encryption Keys (DEKs) encrypt data; a Key Encryption Key (KEK) in HashiCorp Vault encrypts the DEKs
- **Field-level**: PII fields (merchant tax IDs, bank account numbers, contact details) encrypted at the application level using JPA `AttributeConverter`
- **PCI DSS scope**: Reduced by never handling raw card data — HyperSwitch manages card vaulting. Our layer works exclusively with tokenized payment IDs. Network segmentation isolates the CDE (HyperSwitch) from the enterprise layer

---

## Payment observability

### Three pillars: metrics, traces, canonical log lines

**Metrics** use Micrometer with OTLP export to Prometheus. Payment-domain counters and histograms track authorization rates, decline codes, PSP latency percentiles, and transaction volumes — all sliced by PSP, card brand, country, payment method, and 3DS status.

**Distributed traces** use OpenTelemetry via the Spring Boot OpenTelemetry Starter with Micrometer Tracing bridge. W3C TraceContext headers (`traceparent`, `tracestate`) propagate across HTTP calls to HyperSwitch, Kafka message headers, and back — spanning Java and Rust boundaries seamlessly. Custom spans annotate business operations: `authorize-payment`, `capture-payment`, `match-settlement`.

**Canonical log lines** follow Stripe's pattern — one dense, structured log line emitted at the end of every payment API request containing all key telemetry. Implemented as a servlet filter that creates a `CanonicalLogLine` context object, passes it via `ThreadLocal`, and emits at request completion:

```
http_method=POST http_path=/api/v1/payments source_ip=10.0.1.5
api_key_id=sk_live_abc merchant_id=m_123 payment_id=pay_456
payment_method=card card_brand=visa psp_name=stripe
psp_response_code=approved amount=5000 currency=USD
http_status=200 duration_ms=847 psp_latency_ms=623
trace_id=abc123 service=payment-orchestration
```

Canonical log lines are archived to Kafka → S3 → data warehouse for ad-hoc analytics and long-term querying. Stripe powers their Developer Dashboard charts from canonical line archives.

### PSP health scoring

A rolling-window health score per PSP combines:
- Authorization success rate (last 15 minutes)
- Latency percentiles (p50, p95, p99)
- Error rate (5xx, timeouts)
- Chargeback rate
- Availability (uptime in last 24h)

Health scores feed into the ML routing engine, which dynamically adjusts PSP selection based on card type, issuer country, historical success rates, and current provider health. Automatic failover triggers when a PSP's health score drops below a configurable threshold.

### Anomaly detection

**Statistical approaches** for immediate deployment: Exponential Weighted Moving Average (EWMA) of authorization rates with alerts when current rate deviates by >3 standard deviations from the rolling mean. CUSUM (Cumulative Sum) detects gradual drift.

**ML-based approaches** for Phase 3: Per merchant-provider combination models (following Payrails' January 2026 approach) that learn "normal" traffic patterns and flag statistically significant deviations. Isolation Forests for multivariate anomaly detection. Implemented using Apache Commons Math (statistics) and Smile (ML library) within scheduled Spring components.

### Observability stack

**Grafana + Prometheus + Loki + Tempo** — fully open-source, vendor-neutral, OTLP-native. Dedicated payment dashboards with panels for real-time authorization rates (PromQL: `sum(rate(payment_auth_total{status="approved"}[5m])) by (psp) / sum(rate(payment_auth_total[5m])) by (psp) * 100`), PSP latency heatmaps, decline code breakdowns, settlement match rates, and dispute deadline trackers.

Alternative considered: Datadog (managed SaaS but expensive at scale, proprietary agents, vendor lock-in). The Grafana stack provides equivalent capabilities with full control and zero licensing costs.

---

## Reconciliation engine

### Settlement file ingestion

PSPs deliver settlement data in varying formats:

- **Stripe**: CSV via Reporting API, keyed on `balance_transaction` ID. Types: charge, refund, payout, adjustment, dispute.
- **Adyen**: CSV Settlement Details Reports with columns for Psp Reference, Merchant Reference, Gross/Net Credit/Debit, Markup, Interchange, Commission, Batch Number. Journal types: Settled, Refunded, Chargeback, MerchantPayout.
- **PayPal**: CSV Settlement Reports via SFTP covering 24-hour balance-impacting transactions.
- **Braintree**: CSV/XML via API with Transaction ID matching and disbursement reports.

A **canonical data model** normalizes all PSP formats into a unified `SettlementRecord` with fields: `psp_reference`, `internal_reference`, `gross_amount`, `fee_amount`, `net_amount`, `currency`, `settlement_date`, `record_type`, and `psp_name`.

### Three-way matching

```
Step 1: Internal ↔ PSP Match
  Match by PSP reference (transaction_id, psp_reference)
  Verify amounts (accounting for PSP-deducted fees)
  Flag: missing transactions, amount mismatches, FX differences

Step 2: PSP ↔ Bank Match
  Match payout/batch totals to bank deposit amounts
  Account for: bank fees, FX conversion, timing differences
  Flag: missing deposits, partial settlements

Step 3: Cross-Validate
  Ensure internal authorized ≈ PSP settled ≈ bank received
  Apply tolerance rules for rounding (±$0.01), FX (±2%), timing (T+1 to T+7)
```

**Exception categories**: Missing in PSP, Missing internally, Amount mismatch, Duplicate, FX variance, Timing difference. Each exception auto-routes to the appropriate team (treasury, operations, fraud) with time-based escalation for unresolved items.

### Batch processing with Spring Batch

```
Job: DailyReconciliation
  Step 1: IngestSettlementFiles
    ItemReader: FlatFileItemReader (CSV) or API-based reader per PSP
    ItemProcessor: Normalize to canonical SettlementRecord
    ItemWriter: Write to settlement_staging table

  Step 2: MatchTransactions
    ItemReader: Read internal records + PSP records
    ItemProcessor: Multi-strategy matching engine (exact → fuzzy → tolerance)
    ItemWriter: Write to match_results + exceptions tables

  Step 3: GenerateExceptionReport
    ItemReader: Read unmatched/mismatched records
    ItemProcessor: Categorize and route exceptions
    ItemWriter: Publish to exception queue + generate report
```

Spring Batch provides chunk-oriented processing with skip/retry policies, job restart capability, and partitioning for parallelism. Uber processes **1.2 billion settlements per month** using a similar Kafka-based event-driven architecture with T+1 Spark/Flink offline reconciliation.

---

## API design and developer experience

### Stripe-inspired API patterns

The platform adopts Stripe's proven API design patterns:

**Date-based versioning**: API versions use dates (e.g., `2026-03-01`). When a merchant first calls the API, their account pins to that version. Breaking changes never affect existing integrations. The `X-API-Version` header allows testing newer versions per-request. Internally, version change modules transform requests/responses to match each account's pinned version.

**Cursor-based pagination**: List endpoints return `{ "data": [...], "has_more": true }` with `starting_after` and `ending_before` cursor parameters. No `total_count` by default (expensive to compute).

**Expand parameter**: `?expand[]=customer&expand[]=payment_method` replaces linked object IDs with full objects, reducing N+1 API calls. Supports dot notation for recursive expansion up to 4 levels.

**Idempotency keys**: Required on all POST requests via `Idempotency-Key` header. Results cached for 24 hours. Parameter mismatch with same key returns error. Concurrent requests with same key return 429 with `lock_timeout`.

**Error structure**:
```json
{
  "error": {
    "type": "card_error",
    "code": "card_declined",
    "decline_code": "insufficient_funds",
    "message": "The card has insufficient funds.",
    "doc_url": "https://docs.paymentplatform.io/errors#card-declined",
    "param": "payment_method",
    "request_id": "req_abc123"
  }
}
```

**Human-readable prefixed IDs**: `pay_`, `ref_`, `dis_`, `mer_`, `acc_` prefixes for instant identification.

### OpenAPI 3.1 specification

Payment method polymorphism uses `oneOf` with `discriminator`:

```yaml
PaymentMethodDetails:
  oneOf:
    - $ref: '#/components/schemas/CardPayment'
    - $ref: '#/components/schemas/BankTransferPayment'
    - $ref: '#/components/schemas/WalletPayment'
  discriminator:
    propertyName: type
    mapping:
      card: '#/components/schemas/CardPayment'
      bank_transfer: '#/components/schemas/BankTransferPayment'
      wallet: '#/components/schemas/WalletPayment'
```

Amounts are `type: integer, format: int64` (minor currency units). Currencies use `pattern: ^[A-Z]{3}$`. Webhook payloads documented using OpenAPI 3.1's top-level `webhooks` field.

### Webhook delivery

**HMAC-SHA256 signing** with the signature in `X-Webhook-Signature` header (timestamp + signature). Retry strategy: up to **3 days** with exponential backoff (immediately → 5min → 30min → 2hr → 5hr → 10hr → then every 12hr). After 3 days of failures, endpoint is disabled with email notification.

Consumers must return 2xx within 10 seconds. Best practice (following Adyen's guidance): acknowledge the webhook BEFORE applying business logic to prevent timeouts. Event deduplication via `event_id`. No guaranteed ordering — consumers must handle out-of-order delivery.

### Rate limiting

**Token bucket algorithm** (allows legitimate burst traffic during flash sales while maintaining average rate control):

| Tier | Per Second | Per Minute | Per Hour |
|------|-----------|-----------|---------|
| Standard | 100 | 3,000 | 50,000 |
| Premium | 500 | 15,000 | 250,000 |
| Enterprise | Custom | Custom | Custom |

Every response includes `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers. 429 responses include `Retry-After`. Implementation uses Valkey-backed sliding window with per-merchant isolation.

### Developer portal

**Redocly** generates Stripe-style three-column documentation (navigation, docs, code examples) from the OpenAPI 3.1 spec. SDKs generated via `openapi-generator` with custom templates and manual post-processing for idiomatic code, retry logic, pagination helpers, and webhook verification. Initial SDK targets: Java, Python, Node.js, Go.

---

## Testing strategy

### Testing pyramid for payments

**70% unit tests** (domain logic, validators, balance calculations) → **20% integration tests** (Testcontainers) → **8% contract tests** (Spring Cloud Contract + Pact) → **2% E2E tests** (critical paths only).

### PSP emulation with WireMock

**WireMock** simulates all PSP APIs with stateful scenarios (payment state machine transitions), response templating (test card numbers trigger specific outcomes), and fault injection (timeouts, 5xx errors, connection resets). Pre-built Adyen mock stubs are available in the WireMock library. Spring Boot's `@AutoConfigureWireMock` integrates natively.

### Integration testing with Testcontainers

Spring Boot 3.1+'s `@ServiceConnection` auto-configures Testcontainers for PostgreSQL, Kafka, and Redis. The singleton containers pattern shares container instances across all test classes. HyperSwitch runs in a `GenericContainer` for full integration tests:

```java
@TestConfiguration(proxyBeanMethods = false)
public class PaymentTestContainers {
    @Bean @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
    @Bean @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    }
    @Bean
    GenericContainer<?> redis(DynamicPropertyRegistry registry) {
        var redis = new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        return redis;
    }
}
```

### Contract testing

**Spring Cloud Contract** for internal service-to-service contracts (auto-generates tests and WireMock stubs from contract definitions). **Pact** for external PSP connector verification and HyperSwitch API compatibility (language-agnostic contracts shared via Pact Broker).

### Chaos engineering

**Chaos Monkey for Spring Boot** in CI/CD injects latency, exceptions, and application kills into payment service layers. **Litmus Chaos** on Kubernetes tests pod deletion, network latency, and Kafka broker failures. Critical scenarios: PSP timeout (verify circuit breaker trips and fallback to secondary PSP), database failure (verify connection pool recovery), Kafka outage (verify event buffering and at-least-once delivery after recovery).

PayerMax achieved **99.99% availability** with chaos engineering across 16 core subsystems, reducing failure frequency by ~70% year-over-year.

### Load testing with Gatling

**Gatling** (native JVM, Java DSL) simulates realistic payment traffic: warm-up → steady state → peak burst → cool-down. Traffic mix: 70% card, 15% wallet, 10% bank transfer, 5% BNPL. Performance targets: **p50 <200ms, p95 <500ms, p99 <1s** for authorization; **<0.1% error rate**; **500-2,000 TPS** for mid-size enterprises.

### Sandbox mode

Dual API keys (`sk_test_` / `sk_live_`) create parallel universes. Test card numbers trigger specific outcomes. Separate database schemas for sandbox data. Built-in webhook simulator sends mock events to registered endpoints. Test clocks (following Stripe's model) simulate time progression for subscription billing tests.

---

## Deployment and infrastructure

### Kubernetes topology

**Namespace strategy**:
- `payments-prod`: Payment processing services (Restricted Pod Security Standard)
- `hyperswitch`: HyperSwitch components (Restricted PSS)
- `data`: PostgreSQL, Valkey, Kafka (strict NetworkPolicies)
- `monitoring`: Prometheus, Grafana, Loki, Tempo
- `vault`: HashiCorp Vault, External Secrets Operator
- `gitops`: ArgoCD controllers

Payment pods use **Guaranteed QoS class** (requests == limits). `terminationGracePeriodSeconds: 120` allows in-flight transactions to complete. `preStop` lifecycle hooks drain connections. Pod Disruption Budgets ensure `minAvailable: 2` (always less than HPA's `minReplicas: 3`).

**Default-deny NetworkPolicies** in all payment namespaces, with explicit whitelist rules per service pair. Calico provides microsegmentation enforcement.

### Helm umbrella chart

```
payment-platform/
├── Chart.yaml                 # Umbrella chart
├── values.yaml                # Global defaults
├── charts/
│   ├── payment-platform-app/  # Java Spring Boot application
│   ├── hyperswitch/           # From juspay/hyperswitch-helm
│   ├── temporal/              # Workflow engine
│   ├── postgresql/            # Bitnami chart
│   ├── kafka/                 # Bitnami chart
│   ├── valkey/                # Redis-compatible chart
│   ├── monitoring/            # Prometheus + Grafana stack
│   └── vault/                 # HashiCorp Vault
├── environments/
│   ├── dev/values.yaml
│   ├── staging/values.yaml
│   └── production/values.yaml
```

Components toggle via `condition: <component>.enabled`. Environment-specific overrides use `helm install -f environments/production/values.yaml`.

### PostgreSQL high availability

**Patroni 3.0+** manages PostgreSQL HA with automatic leader election via Kubernetes API as DCS. Synchronous replication ensures zero data loss on failover. **Citus 11.2+** integration (built into Patroni 3.0) enables schema-based sharding when horizontal scale is needed. **pgBouncer** handles connection pooling with transaction-level mode for microservices.

Multi-region: Active-passive primary with streaming replication. Active-active reserved for Phase 4 with YugabyteDB migration, enabling geo-partitioned writes per regulatory zone.

### GitOps with ArgoCD

**ArgoCD** manages all deployments via Git-sourced desired state. Kustomize overlays handle environment-specific configurations. **Argo Rollouts** provides canary deployments for payment services with automated analysis (Prometheus-based: authorization success rate, p95 latency). Sync waves order deployments: ConfigMap (-2) → Database migration Job (-1) → Application Deployment (0).

Database migrations run as ArgoCD Pre-Sync Hooks using Flyway. Alternative considered: Atlas Operator (most GitOps-native with declarative CRDs, selected as future migration target).

### Secret management: HashiCorp Vault

**Vault in HA mode** with Raft storage and auto-unseal via cloud KMS. Integration via **Vault Secrets Operator (VSO)** syncing secrets to native Kubernetes Secrets with automatic rollout-restart on rotation.

**Dynamic database credentials**: Vault's database secrets engine generates short-lived PostgreSQL credentials (1-hour TTL) with automatic renewal. PSP API keys stored in KV v2 with version tracking. Encryption keys managed via Vault Transit engine for encryption-as-a-service.

### CI/CD pipeline

**GitHub Actions** pipeline stages: Build (Gradle) → Security scan (SonarQube SAST + Snyk dependency scan + Trivy container scan, all parallel) → Integration test (Testcontainers) → Deploy to staging (GitOps PR) → Integration test staging → Deploy to production (manual approval gate, ArgoCD canary).

Container images signed with Cosign for supply chain security. SBOM generated in CycloneDX format. Deployment freezes configurable via ArgoCD sync windows during peak payment periods.

---

## Technology selection matrix

| Decision | Selected | Runner-Up | Rejected | Justification |
|----------|----------|-----------|----------|---------------|
| **Architecture** | Spring Modulith | Full microservices | Pure monolith | Module boundaries with single-JAR deployment; proven evolution path |
| **Primary DB** | PostgreSQL 16 | YugabyteDB | CockroachDB | Lowest latency, best ecosystem, SSI for financial correctness |
| **Multi-region DB** | YugabyteDB (Phase 4) | CockroachDB | Aurora Global | Best PG compatibility (~95%), proven in banking |
| **Event Bus** | Apache Kafka | RabbitMQ | Pulsar | Industry standard for payments (Stripe, Uber, PayPal) |
| **Kafka Framework** | Spring Kafka | Spring Cloud Stream | — | Direct transactional control required for payment correctness |
| **Workflow Engine** | Temporal | Camunda 8 | Axon | MIT license, native saga support, durable execution |
| **Cache/Idempotency** | Valkey 8+ | Redis 7 | Memcached | 37% faster writes, BSD-3 license, drop-in compatible |
| **Analytics DB** | TimescaleDB | ClickHouse | Druid | PG-native, continuous aggregates, single-stack simplicity |
| **Identity Provider** | Keycloak | Spring Auth Server | Auth0 | Full-featured, Apache 2.0, realm-per-tenant |
| **Policy Engine** | OPA (Rego) | SAPL | Custom Spring | Policy-as-code, testable, Git-versioned, language-agnostic |
| **Observability** | Grafana + Prometheus + Loki | Datadog | ELK | Open-source, OTLP-native, no vendor lock-in |
| **Schema Format** | Avro + Schema Registry | Protobuf | JSON Schema | JVM-first ecosystem, first-class Confluent support |
| **PSP Mock** | WireMock | Hoverfly | Custom | Native Spring integration, stateful scenarios, pre-built stubs |
| **Contract Testing** | Spring Cloud Contract + Pact | Pact only | — | SCC for internal, Pact for external/polyglot |
| **Load Testing** | Gatling | k6 | JMeter | Native JVM, Java DSL, superior metric reporting |
| **GitOps** | ArgoCD | Flux | Jenkins | Web UI for audit, RBAC, sync waves, Argo Rollouts |
| **Secrets** | HashiCorp Vault | AWS Secrets Manager | K8s Secrets | Dynamic credentials, transit encryption, HSM-backed |
| **Service Mesh** | Istio (Ambient) | Linkerd | No mesh | mTLS, AuthorizationPolicy, 8% latency overhead (ambient) |
| **CDC** | Debezium | Custom polling | — | WAL-based, millisecond latency, no source code changes |

---

## Complete event catalog

| Event Type | Schema Key Fields | Producer | Consumer(s) |
|-----------|-------------------|----------|-------------|
| `payments.Payment.Created` | payment_id, merchant_id, amount, currency, method | payment-orchestration | ledger, observability |
| `payments.Payment.Authorized` | payment_id, psp_name, auth_code, amount | payment-orchestration | ledger, observability, workflow |
| `payments.Payment.Captured` | payment_id, captured_amount, capture_id | payment-orchestration | ledger, observability, reconciliation |
| `payments.Payment.Failed` | payment_id, decline_code, decline_category, psp_name | payment-orchestration | observability, workflow (retry logic) |
| `payments.Payment.Cancelled` | payment_id, reason, cancelled_by | payment-orchestration | ledger, observability |
| `payments.Refund.Initiated` | refund_id, payment_id, amount, reason | payment-orchestration | ledger, dispute |
| `payments.Refund.Completed` | refund_id, payment_id, psp_reference | payment-orchestration | ledger, observability, reconciliation |
| `payments.Refund.Failed` | refund_id, payment_id, error_code | payment-orchestration | observability, workflow (retry) |
| `disputes.Dispute.Opened` | dispute_id, payment_id, reason, deadline | dispute | ledger, observability, iam (alert) |
| `disputes.Dispute.Won` | dispute_id, payment_id | dispute | ledger, observability |
| `disputes.Dispute.Lost` | dispute_id, payment_id, amount | dispute | ledger, observability |
| `disputes.Evidence.Submitted` | dispute_id, evidence_type, submitted_at | dispute | observability |
| `ledger.JournalEntry.Posted` | entry_id, accounts[], amounts[], external_ref | ledger | reconciliation, observability |
| `ledger.Balance.Updated` | account_id, new_balance, version | ledger | observability |
| `reconciliation.Run.Completed` | run_id, matched_count, exception_count, match_rate | reconciliation | observability |
| `reconciliation.Exception.Created` | exception_id, type, amount_diff, psp_ref | reconciliation | iam (alert), observability |
| `iam.Approval.Requested` | approval_id, action_type, maker_id, payload | iam | iam (notify checkers) |
| `iam.Approval.Completed` | approval_id, checker_id, decision | iam | payment-orchestration (execute) |
| `observability.Anomaly.Detected` | metric_name, current_value, expected_value, severity | observability | iam (alert) |
| `observability.PSPHealth.Degraded` | psp_name, health_score, metrics | observability | workflow (routing adjustment) |
| `workflow.Execution.Started` | workflow_id, trigger, merchant_id | workflow | observability |
| `workflow.Execution.Completed` | workflow_id, outcome, duration_ms | workflow | observability |
| `platform.Merchant.Onboarded` | merchant_id, org_id, plan | iam | all modules (tenant setup) |
| `platform.Config.Updated` | config_type, merchant_id, changes | iam | payment-orchestration, workflow |

---

## Phased implementation roadmap

### Phase 1: Foundation (Months 1-3)

**Goal**: Core payment orchestration and ledger running on HyperSwitch

- Spring Modulith project scaffold with hexagonal architecture
- HyperSwitch integration adapter (generated client, webhook receiver)
- PostgreSQL schema: ledger tables, event outbox, IAM tables
- Double-entry ledger module with balance management
- Basic RBAC with Keycloak (3 roles: Admin, Operator, Viewer)
- Kafka setup with transactional outbox + Debezium
- Testcontainers integration test suite
- Docker Compose for local development
- CI/CD pipeline (GitHub Actions + SonarQube + Trivy)

**Dependencies**: None (greenfield)

### Phase 2: Enterprise operations (Months 4-6)

**Goal**: Approval workflows, reconciliation, dispute management

- Four-eyes principle / maker-checker for sensitive operations
- Temporal integration for payment saga orchestration
- Reconciliation engine with Spring Batch (Stripe + Adyen file parsers)
- Three-way matching algorithm
- Dispute lifecycle management with deadline tracking
- OPA integration for fine-grained authorization
- API key management (Stripe-inspired)
- WireMock PSP emulator suite
- Contract testing with Spring Cloud Contract

**Dependencies**: Phase 1 complete

### Phase 3: Observability and intelligence (Months 7-9)

**Goal**: Payment analytics, anomaly detection, ML routing

- OpenTelemetry instrumentation across all modules
- Canonical log line implementation
- TimescaleDB for payment analytics with continuous aggregates
- Grafana dashboards: authorization rates, PSP health, decline analysis
- Statistical anomaly detection (EWMA, CUSUM)
- PSP health scoring engine
- Gatling load test suite
- Chaos Monkey integration
- Sandbox/test mode implementation

**Dependencies**: Phase 2 complete (needs production event flow)

### Phase 4: Scale and polish (Months 10-12)

**Goal**: Multi-region readiness, no-code workflows, developer portal

- No-code workflow builder (React Flow frontend + Temporal backend)
- Helm umbrella chart with all components
- ArgoCD GitOps deployment
- Vault integration for secret management
- Istio service mesh with mTLS
- Multi-region PostgreSQL (Patroni + evaluate YugabyteDB migration)
- MirrorMaker 2 for Kafka cross-region replication
- SDK generation (Java, Python, Node.js, Go)
- Redocly developer portal
- OpenAPI 3.1 specification publication
- Open-source release preparation (Apache 2.0 licensing, CONTRIBUTING.md, documentation)

**Dependencies**: Phase 3 complete; SDK generation depends on stable API

---

## Conclusion: an architecture built for correctness first

This architecture prioritizes **financial correctness over premature optimization**. The double-entry ledger enforces mathematical proof of balanced books. The transactional outbox pattern eliminates dual-write inconsistencies. Temporal's durable execution guarantees saga completion despite failures. SERIALIZABLE isolation prevents phantom reads in concurrent balance updates.

Three architectural decisions deserve emphasis for their long-term implications. First, choosing Spring Modulith with a planned extraction path means the platform ships as a single JAR today but can scale to independent services when traffic demands it — avoiding both monolith rigidity and premature microservice complexity. Second, treating HyperSwitch as a black-box payment execution engine (communicating exclusively via its REST API and webhooks) means the enterprise layer remains decoupled from HyperSwitch's internal architecture — upgrades, forks, or even replacements become possible without rewriting business logic. Third, the event-driven backbone (Kafka + Debezium CDC) creates a complete audit trail that satisfies regulatory requirements while enabling real-time analytics and anomaly detection.

The platform addresses every enterprise gap identified in HyperSwitch — IAM with four-eyes principle, automated reconciliation with three-way matching, a production-grade double-entry ledger, visual workflow orchestration, payment-domain observability with canonical log lines, and dispute lifecycle management with card network deadline enforcement. Shipped as a Helm chart with comprehensive documentation, it gives mid-to-large enterprises a plug-and-play payment infrastructure that would otherwise require 12-18 months and a dedicated platform team to build from scratch.