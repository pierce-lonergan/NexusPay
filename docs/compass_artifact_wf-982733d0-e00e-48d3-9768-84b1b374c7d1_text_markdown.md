# Building an enterprise payment backend library in Java/Spring Boot

**No production-ready, multi-processor payment abstraction library exists in the Java/Spring Boot ecosystem today — creating one would fill a significant gap.** The architecture should combine HyperSwitch's connector pattern, Stripe's API design philosophy, Axon Framework's event sourcing, and Temporal's saga orchestration into a Spring Boot starter that developers can drop into any application with a single Maven dependency. This report synthesizes research across open-source payment orchestration platforms, event-driven architectures, PCI compliance strategies, and API design patterns to deliver a complete blueprint for such a library.

The payment technology landscape has shifted dramatically in 2024–2025: HyperSwitch (Juspay) has emerged as the dominant open-source payment orchestrator with **40,000+ GitHub stars** and 200+ processor connectors, Axon Framework 5.0 launched with async-native APIs, and real-time payment networks now operate in **~80 countries**. These developments validate the demand for a unified payment abstraction layer while providing rich architectural patterns to borrow.

---

## 1. The open-source payment orchestration landscape

The ecosystem divides into two categories: **dedicated payment orchestrators** (HyperSwitch, Kill Bill) and **commerce platforms with payment abstraction** (Saleor, Medusa). Only HyperSwitch functions as a true payment routing engine comparable to what this library aims to achieve.

**HyperSwitch** (Rust, Apache 2.0, ~40,100 stars) is the clear architectural reference. Built on a hexagonal ports-and-adapters architecture, it implements each payment processor as a Rust module conforming to a `ConnectorIntegration` trait, with separate transformer modules handling request/response mapping. Its routing engine supports priority-based, volume-split, rule-based, and ML-driven strategies. HyperSwitch achieved **PCI Software Security Standard (S3) certification** in Q4 2024, and its open-source Card Vault (Tartarus) provides a standalone, GDPR-compliant tokenization service with multi-layered AES encryption. The project processes 200M+ daily transactions at Juspay's enterprise clients including Amazon and Google Pay.

**Kill Bill** (Java, Apache 2.0, ~4,600 stars) brings **15+ years of production maturity** as a billing-and-payments platform. Its OSGI-based plugin system with `PaymentPluginApi` interface — defining `authorizePayment()`, `capturePayment()`, `refundPayment()` methods returning standardized `PaymentTransactionInfoPlugin` objects — is the closest existing Java model for a payment processor SPI. Kill Bill's persistent event bus, multi-tenancy support, and plugin property passthrough for gateway-specific data are directly transferable patterns. However, it is a full billing platform rather than a lightweight library.

**Medusa** (TypeScript, MIT, ~31,000 stars) contributes a clean `AbstractPaymentProvider` pattern with compensatable workflow steps, while **Saleor** (Python, BSD-3, ~22,200 stars) demonstrates a webhook-based payment app model that decouples processor logic into external services. **BoltApp/Sleet** (Go, archived April 2024) deserves mention as the most architecturally relevant multi-gateway abstraction — its unified `Authorize()`, `Capture()`, `Void()`, `Refund()` interface reduced new gateway integration time from months to one week.

| Project | Language | Stars | Purpose | Connectors | PCI |
|---------|----------|-------|---------|------------|-----|
| HyperSwitch | Rust | ~40,100 | Payment orchestration | 200+ | S3 certified |
| Kill Bill | Java | ~4,600 | Billing + payments | ~5 native | Deployer responsibility |
| Medusa | TypeScript | ~31,000 | Headless commerce | 1 official + community | PSP-delegated |
| Saleor | Python | ~22,200 | Headless commerce | 2 official apps | PSP-delegated |

---

## 2. Recommended technology stack

The stack targets **Spring Boot 3.2+** with Java 21, leveraging virtual threads for non-blocking payment processor calls. Every choice prioritizes production readiness, community support, and alignment with the Spring ecosystem.

**Core framework**: Spring Boot 3.2+ with `@AutoConfiguration`, `@ConfigurationProperties`, and `@ConditionalOnClass` for provider auto-discovery. Virtual threads (`spring.threads.virtual.enabled=true`) eliminate thread-pool bottlenecks during payment processor HTTP calls. The library publishes as a Spring Boot starter with per-processor modules that activate only when the corresponding SDK appears on the classpath.

**Event sourcing and CQRS**: **Axon Framework 4.11+** (Apache 2.0, 70M+ downloads) provides `@Aggregate`, `@CommandHandler`, `@EventHandler`, and built-in saga support via `@SagaEventHandler`. Its Spring Boot auto-configuration via `axon-spring-boot-starter` makes it seamless. Axon Framework 5.0, released in early 2025, adds async-native APIs and improved throughput — worth evaluating for greenfield adoption.

**Event streaming**: **Apache Kafka** with Spring Kafka for transaction event streaming. Kafka's exactly-once semantics (transactional producers + `read_committed` consumers) provide the guarantees financial systems require. Use the **Transactional Outbox pattern** with Debezium CDC to atomically publish events alongside database writes.

**Saga orchestration**: **Temporal.io** (Server 1.28+, Java SDK 1.30+) for complex multi-step payment workflows. Its workflow-as-code paradigm wraps normal Java methods in durable execution, with automatic retry, timeout management, and state persistence. For simpler flows, Axon's built-in saga orchestration suffices.

**Monetary calculations**: **JSR 354 / Moneta** (`org.javamoney:moneta:1.4.4`) for type-safe currency handling. The `Money` implementation backed by `BigDecimal` handles all currencies correctly, including zero-decimal currencies like JPY. Provide adapters to/from `long` (minor units for Stripe/Adyen) and `BigDecimal` (for Braintree/PayPal).

**Webhook delivery**: **Svix** (Rust, MIT, SOC 2 Type II compliant) for reliable outbound webhook delivery with HMAC signing, automatic retries, and dead letter management. Used by Brex, Benchling, and Drata.

**Tokenization vault**: **HyperSwitch Card Vault (Tartarus)** as the recommended self-hosted option, with HashiCorp Vault Transit engine and PSP-native tokenization as alternatives behind an abstraction layer.

**Resilience**: **Resilience4j** for circuit breakers per payment connector, rate limiting, and retry policies. **Redis** for idempotency key storage and caching.

---

## 3. End-to-end architecture design

The architecture follows a **hexagonal (ports-and-adapters) pattern** with clear separation between the payment domain core and external integrations. The library exposes a unified API while internally routing to the appropriate processor through a strategy-based connector layer.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Application                        │
│   [Stripe Elements / Adyen Drop-in / Apple Pay JS / Google Pay] │
│   Card data tokenized client-side → never touches merchant server│
└──────────────────────────┬──────────────────────────────────────┘
                           │ Token / PaymentMethod ID
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              payment-spring-boot-starter (The Library)            │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │ Payment API  │  │ Idempotency  │  │ Routing Engine         │ │
│  │ (REST + SDK) │→ │ Service      │→ │ (Rule/Volume/Priority) │ │
│  └──────────────┘  └──────────────┘  └────────────┬───────────┘ │
│                                                    │              │
│  ┌────────────────────────────────────────────────▼────────────┐│
│  │              Payment Orchestration Service                   ││
│  │  ┌──────────────┐  ┌─────────────┐  ┌───────────────────┐  ││
│  │  │ Saga Engine  │  │ State       │  │ Event Publisher    │  ││
│  │  │ (Temporal)   │  │ Machine     │  │ (Kafka/Outbox)     │  ││
│  │  └──────────────┘  └─────────────┘  └───────────────────┘  ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │          Connector Layer (Strategy Pattern)                  ││
│  │  ┌────────┐ ┌──────┐ ┌───────┐ ┌──────────┐ ┌──────────┐ ││
│  │  │ Stripe │ │Adyen │ │Square │ │Braintree │ │  Mock    │ ││
│  │  │Connector││Conn. │ │Conn.  │ │Connector │ │Processor │ ││
│  │  └────────┘ └──────┘ └───────┘ └──────────┘ └──────────┘ ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │ Vault        │  │ Webhook      │  │ Audit / Event Store    │ │
│  │ Abstraction  │  │ Service      │  │ (Axon + Kafka)         │ │
│  └──────────────┘  └──────────────┘  └────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

**Module structure** follows Spring Boot starter conventions with per-processor modules:

```
payment-spring-boot/
├── payment-core/                    # Interfaces, domain models, annotations
├── payment-stripe/                  # Stripe connector + auto-config
├── payment-adyen/                   # Adyen connector + auto-config
├── payment-square/                  # Square connector + auto-config
├── payment-braintree/               # Braintree connector + auto-config
├── payment-paypal/                  # PayPal connector + auto-config
├── payment-kafka/                   # Kafka event streaming module
├── payment-vault/                   # Tokenization vault abstraction
├── payment-spring-boot-starter/     # Starter JAR with auto-config registration
└── payment-test/                    # Mock processor, test utilities, WireMock stubs
```

Each processor module activates via `@ConditionalOnClass` — if `com.stripe:stripe-java` is on the classpath and `payment.stripe.enabled=true`, the Stripe connector auto-registers. This achieves the plug-and-play goal: developers add a Maven dependency and set configuration properties.

---

## 4. Data model for transactions, ledger, and payment methods

The data model draws from Stripe's object model for the transaction layer and Square's Books system for the ledger layer. All amounts are stored as **`long` values in minor currency units** (cents for USD, yen for JPY) to eliminate floating-point errors. All entity IDs use **type-prefixed identifiers** (`txn_`, `pm_`, `cus_`, `ref_`, `evt_`) for instant recognition in logs and debugging.

### Transaction model

The core transaction lifecycle follows the **PaymentIntent pattern** pioneered by Stripe, which unifies all payment methods under a single flow:

```sql
CREATE TABLE payment_intents (
    id              VARCHAR(64) PRIMARY KEY,      -- pi_xxx
    idempotency_key VARCHAR(128) UNIQUE,
    customer_id     VARCHAR(64),                  -- cus_xxx
    amount          BIGINT NOT NULL,              -- minor units
    currency        VARCHAR(3) NOT NULL,          -- ISO 4217
    status          VARCHAR(32) NOT NULL,         -- REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION
                                                  -- → PROCESSING → SUCCEEDED → CANCELED
    payment_method_id VARCHAR(64),                -- pm_xxx
    processor       VARCHAR(32),                  -- stripe, adyen, square
    processor_ref   VARCHAR(128),                 -- processor's transaction ID
    capture_method  VARCHAR(16) DEFAULT 'automatic', -- automatic | manual
    metadata        JSONB,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE TABLE charges (
    id              VARCHAR(64) PRIMARY KEY,      -- ch_xxx
    payment_intent_id VARCHAR(64) REFERENCES payment_intents(id),
    amount          BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    status          VARCHAR(32) NOT NULL,         -- PENDING, SUCCEEDED, FAILED
    failure_code    VARCHAR(64),
    failure_message TEXT,
    processor_response JSONB,
    created_at      TIMESTAMP NOT NULL
);

CREATE TABLE refunds (
    id              VARCHAR(64) PRIMARY KEY,      -- ref_xxx
    charge_id       VARCHAR(64) REFERENCES charges(id),
    amount          BIGINT NOT NULL,
    reason          VARCHAR(64),
    status          VARCHAR(32) NOT NULL,
    processor_ref   VARCHAR(128),
    created_at      TIMESTAMP NOT NULL
);

CREATE TABLE payment_methods (
    id              VARCHAR(64) PRIMARY KEY,      -- pm_xxx
    customer_id     VARCHAR(64),
    type            VARCHAR(32) NOT NULL,         -- card, bank_account, apple_pay, google_pay
    card_brand      VARCHAR(16),
    card_last4      VARCHAR(4),
    card_exp_month  SMALLINT,
    card_exp_year   SMALLINT,
    token_ref       VARCHAR(256),                 -- vault/processor token reference
    billing_address JSONB,
    created_at      TIMESTAMP NOT NULL
);
```

### Double-entry ledger model

The ledger follows the three-table pattern proven at Square (Books) and Stripe (Ledger), where every money movement creates balanced debit/credit entries. This provides an **immutable, auditable record** of all financial activity:

```sql
CREATE TABLE ledger_accounts (
    id       VARCHAR(64) PRIMARY KEY,
    name     VARCHAR(128) NOT NULL,
    type     VARCHAR(16) NOT NULL,           -- ASSET, LIABILITY, REVENUE, EXPENSE
    currency VARCHAR(3) NOT NULL
);

CREATE TABLE journal_entries (
    id                 VARCHAR(64) PRIMARY KEY,
    payment_intent_id  VARCHAR(64),          -- links to transaction
    description        TEXT,
    posted_at          TIMESTAMP NOT NULL,
    metadata           JSONB
);

CREATE TABLE book_entries (
    id                VARCHAR(64) PRIMARY KEY,
    journal_entry_id  VARCHAR(64) REFERENCES journal_entries(id),
    ledger_account_id VARCHAR(64) REFERENCES ledger_accounts(id),
    amount            BIGINT NOT NULL,       -- positive = debit, negative = credit
    currency          VARCHAR(3) NOT NULL
    -- INVARIANT: SUM(amount) across all book_entries per journal_entry = 0
);
```

A successful $100 payment creates a journal entry with two book entries: a **debit of 10000** (cents) to the merchant receivables account and a **credit of -10000** to the customer liability account. Corrections never update existing entries — they create new reversal journal entries, maintaining full auditability.

---

## 5. Security architecture for PCI compliance

The library's security architecture targets **SAQ A eligibility** — the simplest PCI DSS 4.0 compliance level — by ensuring raw cardholder data never touches the merchant's server. This is the single most impactful design decision for adoption.

**Client-side tokenization is mandatory.** The library requires payment methods to arrive as processor tokens (Stripe PaymentMethod IDs, Adyen encrypted data, etc.) rather than raw card numbers. The client application uses Stripe Elements, Adyen Drop-in, or equivalent iframe-based components that collect card data in an isolated browser context. Only tokens flow to the merchant's Spring Boot backend. This architecture reduces PCI scope from **277+ requirements (SAQ D) to ~24 requirements (SAQ A)**.

PCI DSS 4.0, which became fully mandatory on March 31, 2025, introduced critical new requirements: **Requirement 6.4.3** mandates continuous monitoring of payment page scripts, and **Requirement 11.6.1** requires real-time integrity checking. These apply differently based on integration method — iframe-based approaches (SAQ A) face lighter obligations than JavaScript direct-post approaches (SAQ A-EP, ~140 requirements).

**The vault abstraction layer** supports three backends behind a `TokenVaultService` interface:

- **HyperSwitch Card Vault (Tartarus)**: The recommended self-hosted option. Written in Rust, it provides multi-layered AES encryption with KMS-managed key encryption keys, JWE/JWS signed communication, deduplication via internal hashing, and a `StrongSecret` pattern that zeros sensitive data from memory after processing.
- **HashiCorp Vault Transit engine**: Encryption-as-a-service using AES-256-GCM96 with automatic key rotation. Spring Cloud Vault (`spring-vault-core`) provides native integration. The open-source edition supports Transit (encryption); the enterprise Transform engine adds format-preserving tokenization.
- **PSP-native tokenization**: Simplest path — store processor tokens directly. Trade-off: tokens are proprietary and not portable across processors.

**Network tokenization** (Visa/Mastercard EMVCo tokens) should be layered on top. Network tokens deliver **2–6% higher authorization rates**, **30–40% lower fraud**, and Visa interchange savings of 0.10% on tokenized CNP transactions. Adyen manages network tokens automatically; Stripe handles them behind the scenes with PaymentMethods. The library should expose a configuration flag to enable network tokenization where supported.

**Encryption strategy** follows the envelope encryption pattern: data encryption keys (DEKs) encrypt sensitive fields using AES-256-GCM, while key encryption keys (KEKs) stored in KMS/HSM protect the DEKs. Spring Cloud Vault's `VaultTransitOperations` provides this as a service call. All inter-service communication uses **mTLS with TLS 1.3**, configurable via Spring Boot's server.ssl properties or automated through a service mesh.

---

## 6. Event-driven architecture with Kafka integration

The event-driven layer serves three purposes: **real-time transaction streaming** for downstream consumers, **immutable audit trails** for compliance, and **async processing** for settlement, reconciliation, and notifications. The architecture uses Kafka as the event backbone with the Transactional Outbox pattern for reliable publishing.

### Kafka topic design and exactly-once semantics

Topics follow a domain-based structure with the payment or account ID as the partition key, guaranteeing ordered processing of all events within a single payment lifecycle:

- `payment.events` — All payment state transitions (authorized, captured, settled, refunded)
- `account.events` — Account balance changes, ledger entries
- `webhook.outbound` — Events queued for external webhook delivery
- `payment.events.DLT` — Dead letter topic for failed event processing

**Exactly-once semantics** require three coordinated settings: idempotent producers (`enable.idempotence=true`), transactional producers (`transactional.id` configured), and read-committed consumers (`isolation.level=read_committed`). Spring Kafka's `spring.kafka.producer.transaction-id-prefix=payment-tx-` enables this declaratively.

The **Transactional Outbox pattern** solves the dual-write problem — where writing to the database and publishing to Kafka must be atomic. The payment service writes business state and an outbox record in a single database transaction. Debezium CDC captures outbox inserts and streams them to Kafka, eliminating the risk of publishing an event without persisting the state change (or vice versa).

### Event sourcing with Axon Framework

Payment aggregates are event-sourced, meaning their state is reconstructed from a sequence of domain events rather than stored directly:

```java
@Aggregate
public class PaymentAggregate {
    @AggregateIdentifier
    private String paymentId;
    private PaymentStatus status;
    private long amount;

    @CommandHandler
    public PaymentAggregate(AuthorizePaymentCommand cmd) {
        // Validate business rules
        AggregateLifecycle.apply(new PaymentAuthorizedEvent(
            cmd.getPaymentId(), cmd.getAmount(), cmd.getCurrency()));
    }

    @EventSourcingHandler
    public void on(PaymentAuthorizedEvent event) {
        this.paymentId = event.getPaymentId();
        this.status = PaymentStatus.AUTHORIZED;
        this.amount = event.getAmount();
    }
}
```

The CQRS query side subscribes to domain events and maintains denormalized projections: **PostgreSQL** for transaction history and balance queries, **Elasticsearch** for full-text search, **Redis** for real-time balance caches, and **TimescaleDB or ClickHouse** for time-series analytics. This separation allows each read model to be optimized independently without affecting write performance.

### Saga orchestration for payment workflows

The authorize → capture → settle → reconcile workflow uses **Temporal.io** for durable execution with automatic compensation:

```java
@WorkflowInterface
public interface PaymentWorkflow {
    @WorkflowMethod
    PaymentResult processPayment(PaymentRequest request);
}

public class PaymentWorkflowImpl implements PaymentWorkflow {
    private final PaymentActivities activities =
        Workflow.newActivityStub(PaymentActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        try {
            AuthResult auth = activities.authorizePayment(request);
            CaptureResult capture = activities.capturePayment(auth);
            return PaymentResult.success(capture);
        } catch (Exception e) {
            activities.reverseAuthorization(request);  // Compensating transaction
            return PaymentResult.failed(e.getMessage());
        }
    }
}
```

Temporal persists workflow state to PostgreSQL, so even if the payment service crashes mid-saga, execution resumes exactly where it left off. Each activity step is independently retryable and idempotent. For simpler deployments not requiring Temporal's infrastructure, Axon's built-in `@Saga` with `@SagaEventHandler` annotations provides a lighter-weight alternative.

### Webhook delivery

Outbound webhooks use **Svix** (or a built-in webhook service for lighter deployments) with exponential backoff retry: 5s → 25s → 125s → 625s, capped at hourly retries for 72 hours. Each webhook is HMAC-SHA256 signed with a per-endpoint secret, includes a timestamp to prevent replay attacks, and carries an idempotency key (`{event_type}_{resource_id}_{timestamp}`) for deduplication. Failed deliveries exhaust retries into a dead letter queue with alerting on DLQ growth.

---

## 7. Plugin/provider pattern for payment processor abstraction

The connector layer uses a **Strategy + Factory + Auto-Configuration** pattern that is Spring-native, zero-dependency, and auto-discovers processors at startup.

### Core interface

```java
public interface PaymentProcessor {
    String getProviderName();
    AuthorizationResponse authorize(AuthorizationRequest request) throws PaymentException;
    CaptureResponse capture(CaptureRequest request) throws PaymentException;
    VoidResponse voidTransaction(VoidRequest request) throws PaymentException;
    RefundResponse refund(RefundRequest request) throws PaymentException;
    boolean supportsFeature(PaymentFeature feature);
}
```

Each processor module (e.g., `payment-stripe`) implements this interface and registers as a Spring `@Service("stripe")` bean. The `PaymentProcessorRegistry` auto-collects all implementations via Spring's `Map<String, PaymentProcessor>` autowiring — adding a new processor requires zero changes to existing code.

### Auto-configuration per processor

```java
@AutoConfiguration
@ConditionalOnClass(com.stripe.Stripe.class)
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StripeProperties.class)
public class StripeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public StripePaymentProcessor stripePaymentProcessor(StripeProperties props) {
        return new StripePaymentProcessor(props);
    }
}
```

Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The `@ConditionalOnMissingBean` annotation allows consumers to override any connector with their own implementation.

### Transformer pattern for request/response mapping

Each connector includes a **transformer module** (borrowed from HyperSwitch's architecture) that handles bidirectional mapping between unified domain objects and processor-specific API formats. Critical translation points include: amount representation (Stripe uses cents as `long`, Braintree uses decimal `String`), error code mapping (each processor's decline codes mapped to a unified `PaymentDeclineCode` enum), and currency handling (zero-decimal currencies like JPY require special treatment).

### Routing engine

```yaml
payment:
  routing:
    rules:
      - region: US
        processor: stripe
        payment-methods: [card, apple_pay, google_pay]
      - region: EU
        processor: adyen
        payment-methods: [card, ideal, sofort, klarna]
      - region: IN
        processor: adyen
        payment-methods: [upi, card]
    fallback: stripe
    strategy: priority  # priority | volume_split | rule_based
```

The routing engine evaluates rules based on region, currency, payment method, and amount, selecting the appropriate connector. Circuit breakers (Resilience4j) per connector trigger automatic fallback when a processor's failure rate exceeds a configurable threshold.

---

## 8. Deployment, configuration, and GitHub publication patterns

### Publishing to Maven Central

The library publishes to Maven Central via GitHub Actions with a `workflow_dispatch` trigger for manual releases. The CI/CD pipeline includes:

- **build.yml**: Triggered on push/PR — matrix build across Java 17 and 21 — test — coverage — publish SNAPSHOT to GitHub Packages
- **release.yml**: Manual trigger with version input — GPG-sign artifacts via `maven-gpg-plugin` — deploy to Sonatype OSSRH via `nexus-staging-maven-plugin` — create GitHub Release with auto-generated changelog

Consumers integrate with a single dependency:

```xml
<dependency>
    <groupId>io.github.{org}</groupId>
    <artifactId>payment-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Add processor modules as needed -->
<dependency>
    <groupId>io.github.{org}</groupId>
    <artifactId>payment-stripe</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Configuration-driven setup

The library auto-configures via `application.yml` with IDE autocompletion support (generated `spring-configuration-metadata.json`). Every processor credential uses environment variable references, never hardcoded values. Spring profiles separate sandbox and production environments. The built-in **mock processor** (`payment-test` module) simulates all payment flows using test card numbers matching Stripe's patterns (`4242424242424242` = success, `4000000000000002` = decline).

### Repository structure for developer experience

Following Stripe's philosophy that "APIs are products and developers are customers," the repository should include: a badge wall (CI status, Maven Central version, coverage, license), a **5-minute quickstart** in README.md, a `docs/` directory with per-processor integration guides, an `examples/` directory with working Spring Boot sample applications, pre-configured WireMock stubs shipped as test fixtures, and `CONTRIBUTING.md` with step-by-step instructions for adding new processor connectors. Semantic versioning is critical: **MAJOR** for breaking API changes, **MINOR** for new processor support, **PATCH** for bug fixes — breaking changes in a payment library affect money-handling code and deserve exceptional caution.

---

## Conclusion

This architecture achieves three goals simultaneously: **minimal PCI scope** (SAQ A through mandatory client-side tokenization), **processor portability** (swap or add processors via configuration, not code changes), and **financial integrity** (event-sourced double-entry ledger with exactly-once Kafka semantics).

The most critical insight from this research is that the Java ecosystem lacks what HyperSwitch provides for Rust and ActiveMerchant historically provided for Ruby — a mature, multi-processor payment abstraction. Every existing Java option is either single-processor (Stripe starter), a full billing platform (Kill Bill), or unmaintained (J2Pay). The four core operations — **authorize, capture, void, refund** — are universal across all processors, making a unified interface both achievable and valuable.

Three architectural decisions will determine the library's success. First, choosing the **Transactional Outbox pattern with Debezium CDC** over direct Kafka publishing eliminates the dual-write consistency problem that plagues most event-driven payment systems. Second, using **Temporal.io for saga orchestration** rather than hand-rolled state machines provides durable execution guarantees without reinventing them. Third, structuring the library as **separate Maven modules per processor** with `@ConditionalOnClass` auto-configuration means consumers pay (in dependencies and complexity) only for the processors they use, while the library can scale to dozens of connectors without becoming monolithic. Virtual threads in Java 21+ make this architecture performant without reactive complexity — each payment processor call blocks its virtual thread cheaply rather than requiring reactive chain orchestration.