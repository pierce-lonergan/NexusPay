# Phase 3: Intelligence & Global Expansion

**Version**: v0.3.0
**Timeline**: Weeks 41-64 (6 sprints, 4 weeks each)
**Theme**: Fraud prevention, cross-border payments, smart routing, event architecture maturity, client-side SDK, and payment analytics.

Last updated: 2026-03-15

---

## Table of Contents

1. [Phase 3 Entry Criteria](#phase-3-entry-criteria)
2. [Phase 3 Exit Criteria](#phase-3-exit-criteria)
3. [New Technologies Introduced](#new-technologies-introduced)
4. [Performance Targets](#performance-targets)
5. [Sprint 3.1: Fraud Prevention (Weeks 41-44)](#sprint-31-fraud-prevention-weeks-41-44)
6. [Sprint 3.2: Cross-Border & FX (Weeks 45-48)](#sprint-32-cross-border--fx-weeks-45-48)
7. [Sprint 3.3: Smart Routing Engine (Weeks 49-52)](#sprint-33-smart-routing-engine-weeks-49-52)
8. [Sprint 3.4: Event Architecture Upgrade (Weeks 53-56)](#sprint-34-event-architecture-upgrade-weeks-53-56)
9. [Sprint 3.5: Client-Side SDK (Weeks 57-60)](#sprint-35-client-side-sdk-weeks-57-60)
10. [Sprint 3.6: Payment Analytics Platform (Weeks 61-64)](#sprint-36-payment-analytics-platform-weeks-61-64)

---

## Phase 3 Entry Criteria

All of the following Phase 2 deliverables must be complete and verified before Phase 3 work begins:

| # | Criterion | Verification |
|---|-----------|-------------|
| E1 | Multi-tenancy with PostgreSQL RLS enforced on all tables | Integration test proving cross-tenant data isolation; RLS policies active in production schema |
| E2 | Debezium CDC running and reliably streaming outbox events to Kafka | CDC lag < 5 seconds under normal load; outbox relay replaced by Debezium for all event types |
| E3 | Temporal workflow engine integrated for payment orchestration | Payment create/confirm/capture/refund lifecycle managed by Temporal workflows; Temporal UI accessible |
| E4 | Reconciliation module operational with 3-way matching | Settlement file ingestion, ledger matching, and exception management functional; daily reconciliation job completing successfully |
| E5 | Dispute module handling full lifecycle | Dispute creation, evidence submission, and resolution tracking working end-to-end with HyperSwitch |
| E6 | Subscription billing engine live | Plan creation, subscription lifecycle, invoice generation, dunning retries, and metered billing functional |
| E7 | Prometheus + Grafana observability stack deployed | Metrics exported from all modules; Grafana dashboards for payment flow, Kafka lag, and JVM health operational |
| E8 | All Phase 2 Flyway migrations applied cleanly | `flyway info` shows no pending or failed migrations across all module schemas |
| E9 | Gatling load tests passing at 500 TPS with p95 < 400ms | Baseline performance validated before Phase 3 additions |
| E10 | Phase 2 ADRs documented | ADR-009 through ADR-015 (multi-tenancy, CDC, Temporal, reconciliation, disputes, billing, observability) reviewed and merged |

---

## Phase 3 Exit Criteria

| # | Criterion | Verification |
|---|-----------|-------------|
| X1 | Fraud module blocking test transactions matching configured rules | End-to-end test: rule triggers block, review queue populated, FRM fallback functional |
| X2 | Cross-border payment in EUR settling in USD with correct FX ledger entries | Ledger shows multi-leg journal entries with FX gain/loss correctly calculated |
| X3 | Smart routing engine selecting lowest-cost PSP and cascading on decline | Load test showing routing decisions, cascade triggers, and A/B test traffic splitting |
| X4 | All Kafka events published as Avro with Schema Registry validation | No JSON events remain; schema evolution tested with backward-compatible change |
| X5 | NexusPay.js tokenizing card in PCI-compliant iframe | Browser test demonstrating card tokenization without card data reaching merchant server |
| X6 | Analytics API returning auth rate breakdowns by PSP and region | API response validated against known test dataset with correct rollup calculations |
| X7 | 1000 TPS at p95 < 600ms on full payment flow including fraud check and routing | Gatling test suite passing consistently across 3 consecutive runs |
| X8 | All new modules pass Spring Modulith ArchUnit verification | `./gradlew :app:test --tests *ModulithTests*` green |
| X9 | Phase 3 ADRs documented (ADR-016 through ADR-022) | Fraud engine, FX strategy, routing architecture, Avro migration, SDK architecture, analytics pipeline, event sourcing |
| X10 | Zero critical or high severity findings in dependency vulnerability scan | OWASP dependency-check or Snyk scan clean |

---

## New Technologies Introduced

| Technology | Version | Purpose | Sprint |
|------------|---------|---------|--------|
| Confluent Schema Registry | 7.6.x | Avro schema management and compatibility enforcement | 3.4 |
| Apache Avro | 1.11.x | Compact binary event serialization with schema evolution | 3.4 |
| Avro Gradle Plugin | 1.11.x | Java class generation from `.avsc` schema files | 3.4 |
| TypeScript | 5.4.x | NexusPay.js checkout SDK development | 3.5 |
| Vite | 5.x | SDK build tooling (bundling, dev server, library mode) | 3.5 |
| React | 18.x | `@nexus-pay/react` component library | 3.5 |
| Vitest | 1.x | SDK unit testing | 3.5 |
| Playwright | 1.x | SDK browser integration tests | 3.5 |

---

## Performance Targets

| Metric | Phase 2 Baseline | Phase 3 Target | Notes |
|--------|-----------------|----------------|-------|
| Throughput | 500 TPS | 1000 TPS | Full payment flow including fraud check + routing |
| Latency p50 | < 150ms | < 250ms | Fraud + routing adds ~100ms overhead |
| Latency p95 | < 400ms | < 600ms | Includes FRM provider callout (circuit-breakered) |
| Latency p99 | < 800ms | < 1200ms | Cascade scenario: primary PSP timeout + failover |
| Fraud check overhead | N/A | < 80ms p95 | Native rules only; FRM provider adds up to 200ms (async where possible) |
| FX rate lookup | N/A | < 5ms p95 | Valkey cache hit path |
| Routing decision | N/A | < 10ms p95 | In-memory strategy evaluation |
| SDK load time | N/A | < 50KB gzipped | NexusPay.js core bundle |
| Analytics query | N/A | < 500ms p95 | Pre-aggregated rollup tables |

---

## Sprint 3.1: Fraud Prevention (Weeks 41-44)

### Overview

Introduces the `fraud` Gradle module with a hexagonal architecture containing a rules engine, device fingerprinting infrastructure, external FRM provider integration, and risk scoring aggregation. The fraud check is invoked during the payment authorization flow before the PSP call.

### Dependencies on Phase 2

- **Multi-tenancy RLS (Sprint 2.1)**: Fraud rules and assessments are tenant-scoped; RLS policies must be active.
- **Temporal workflows (Sprint 2.2)**: The fraud check step is added as an activity in the payment authorization workflow.
- **Observability (Sprint 2.6)**: Fraud metrics exported to Prometheus; Grafana dashboard for fraud rates.

### New Module Structure

```
fraud/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/io/nexuspay/fraud/
    │   │   ├── package-info.java
    │   │   ├── domain/
    │   │   │   ├── model/
    │   │   │   │   ├── FraudRule.java
    │   │   │   │   ├── RuleCondition.java
    │   │   │   │   ├── RuleAction.java
    │   │   │   │   ├── RiskAssessment.java
    │   │   │   │   ├── RiskDecision.java              // enum: ALLOW, REVIEW, BLOCK
    │   │   │   │   ├── RiskSignal.java
    │   │   │   │   ├── DeviceFingerprint.java
    │   │   │   │   ├── DeviceReputation.java
    │   │   │   │   └── FraudAssessmentResult.java
    │   │   │   └── event/
    │   │   │       ├── FraudCheckPassed.java
    │   │   │       ├── FraudCheckFailed.java
    │   │   │       ├── FraudCheckReview.java
    │   │   │       └── RuleTriggered.java
    │   │   ├── application/
    │   │   │   ├── port/
    │   │   │   │   ├── in/
    │   │   │   │   │   ├── AssessFraudRiskUseCase.java
    │   │   │   │   │   ├── ManageFraudRulesUseCase.java
    │   │   │   │   │   └── ReviewFraudCaseUseCase.java
    │   │   │   │   └── out/
    │   │   │   │       ├── FraudRiskPort.java           // assess(PaymentContext): RiskAssessment
    │   │   │   │       ├── FraudRuleRepository.java
    │   │   │   │       ├── FraudAssessmentRepository.java
    │   │   │   │       ├── DeviceFingerprintRepository.java
    │   │   │   │       └── FraudEventPublisher.java
    │   │   │   ├── service/
    │   │   │   │   ├── FraudAssessmentService.java
    │   │   │   │   ├── RuleEvaluationPipeline.java
    │   │   │   │   ├── RuleEngine.java
    │   │   │   │   ├── RiskScoringAggregator.java
    │   │   │   │   ├── DeviceFingerprintMatcher.java
    │   │   │   │   └── FraudRuleManager.java
    │   │   │   └── dto/
    │   │   │       ├── PaymentContext.java
    │   │   │       ├── FraudRuleCreateRequest.java
    │   │   │       ├── FraudRuleUpdateRequest.java
    │   │   │       └── FraudRuleResponse.java
    │   │   ├── adapter/
    │   │   │   ├── in/
    │   │   │   │   └── rest/
    │   │   │   │       ├── FraudRuleController.java
    │   │   │   │       └── FraudAssessmentController.java
    │   │   │   └── out/
    │   │   │       ├── persistence/
    │   │   │       │   ├── FraudRuleEntity.java
    │   │   │       │   ├── FraudAssessmentEntity.java
    │   │   │       │   ├── DeviceFingerprintEntity.java
    │   │   │       │   ├── JpaFraudRuleRepository.java
    │   │   │       │   ├── JpaFraudAssessmentRepository.java
    │   │   │       │   ├── JpaDeviceFingerprintRepository.java
    │   │   │       │   ├── FraudRuleRepositoryAdapter.java
    │   │   │       │   ├── FraudAssessmentRepositoryAdapter.java
    │   │   │       │   └── DeviceFingerprintRepositoryAdapter.java
    │   │   │       ├── frm/
    │   │   │       │   ├── SiftFraudAdapter.java
    │   │   │       │   ├── SiftApiClient.java
    │   │   │       │   ├── SiftRequestMapper.java
    │   │   │       │   ├── SiftResponseMapper.java
    │   │   │       │   ├── SignifydFraudAdapter.java
    │   │   │       │   ├── SignifydApiClient.java
    │   │   │       │   ├── SignifydRequestMapper.java
    │   │   │       │   ├── SignifydResponseMapper.java
    │   │   │       │   └── FallbackFraudChain.java
    │   │   │       ├── event/
    │   │   │       │   └── KafkaFraudEventPublisher.java
    │   │   │       └── cache/
    │   │   │           └── ValkeyFraudRuleCache.java
    │   │   └── config/
    │   │       ├── FraudFlywayConfig.java
    │   │       ├── FraudProperties.java
    │   │       └── FraudModuleConfig.java
    │   └── resources/
    │       └── db/migration/fraud/
    │           ├── V3001__create_fraud_rules_table.sql
    │           ├── V3002__create_fraud_assessments_table.sql
    │           ├── V3003__create_device_fingerprints_table.sql
    │           └── V3004__create_fraud_events_table.sql
    └── test/
        └── java/io/nexuspay/fraud/
            ├── domain/
            │   └── model/
            │       ├── FraudRuleTest.java
            │       └── RiskAssessmentTest.java
            ├── application/
            │   ├── service/
            │   │   ├── RuleEvaluationPipelineTest.java
            │   │   ├── RiskScoringAggregatorTest.java
            │   │   └── DeviceFingerprintMatcherTest.java
            │   └── FraudAssessmentServiceTest.java
            ├── adapter/
            │   ├── out/
            │   │   ├── frm/
            │   │   │   ├── SiftFraudAdapterTest.java
            │   │   │   ├── SignifydFraudAdapterTest.java
            │   │   │   └── FallbackFraudChainTest.java
            │   │   └── persistence/
            │   │       └── FraudRuleRepositoryAdapterTest.java
            │   └── in/
            │       └── rest/
            │           └── FraudRuleControllerTest.java
            └── integration/
                ├── FraudAssessmentIntegrationTest.java
                └── FraudRuleCacheIntegrationTest.java
```

### Key Java Classes and Interfaces

**`io.nexuspay.fraud.application.port.out.FraudRiskPort`** — Primary outbound port for external FRM providers:

```java
public interface FraudRiskPort {
    RiskAssessment assess(PaymentContext context);
    String providerName();
    int priority();  // lower = higher priority in fallback chain
}
```

**`io.nexuspay.fraud.application.service.RuleEvaluationPipeline`** — Orchestrates the three-phase rule evaluation:

```java
@Service
public class RuleEvaluationPipeline {
    /**
     * Phase 1: Pre-auth rules (velocity, geo, BIN, amount threshold)
     * Phase 2: Scoring (aggregate signals into numeric score 0-100)
     * Phase 3: Decision (apply thresholds → ALLOW / REVIEW / BLOCK)
     */
    public RiskAssessment evaluate(PaymentContext context, List<FraudRule> activeRules);
}
```

**`io.nexuspay.fraud.application.service.RiskScoringAggregator`** — Combines native rule scores with FRM provider scores:

```java
@Service
public class RiskScoringAggregator {
    /**
     * Weighted aggregation: finalScore = (nativeWeight * nativeScore) + (frmWeight * frmScore)
     * Weights are configurable per merchant/tenant via FraudProperties.
     */
    public int aggregate(int nativeRuleScore, int frmProviderScore, TenantFraudConfig config);
}
```

**`io.nexuspay.fraud.adapter.out.frm.FallbackFraudChain`** — Implements fallback chain across FRM providers:

```java
@Component
public class FallbackFraudChain {
    private final List<FraudRiskPort> providers;  // sorted by priority

    /**
     * Try primary FRM → on failure, try secondary → on failure, fall back to native rules only.
     * Circuit breaker wraps each provider call.
     */
    public RiskAssessment assessWithFallback(PaymentContext context);
}
```

**`io.nexuspay.fraud.adapter.out.cache.ValkeyFraudRuleCache`** — Hot-reloadable rule cache:

```java
@Component
public class ValkeyFraudRuleCache {
    /**
     * Loads all active rules for a tenant into Valkey on startup.
     * Listens for FraudRuleUpdated events to invalidate and reload.
     * Rules are versioned; cache key includes version for A/B testing.
     * Key pattern: fraud:rules:{tenantId}:{ruleVersion}
     */
    public List<FraudRule> getActiveRules(String tenantId);
    public void invalidate(String tenantId);
}
```

### Flyway Migration Schemas

**V3001__create_fraud_rules_table.sql**:

```sql
CREATE TABLE fraud.fraud_rules (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    rule_name         VARCHAR(255) NOT NULL,
    rule_type         VARCHAR(50) NOT NULL,   -- VELOCITY, AMOUNT_THRESHOLD, GEO_RESTRICTION, BIN_CHECK, DEVICE_FINGERPRINT
    condition_dsl     JSONB NOT NULL,          -- rule DSL stored as JSON
    action            VARCHAR(20) NOT NULL,    -- BLOCK, REVIEW, SCORE_ADJUST
    score_adjustment  INTEGER DEFAULT 0,
    priority          INTEGER NOT NULL DEFAULT 100,
    version           INTEGER NOT NULL DEFAULT 1,
    ab_test_group     VARCHAR(20),             -- NULL = always active, 'A' or 'B' for A/B testing
    ab_test_traffic   DECIMAL(5,4),            -- percentage of traffic for this group (0.0000-1.0000)
    enabled           BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(255) NOT NULL,
    CONSTRAINT uk_fraud_rules_tenant_name_version UNIQUE (tenant_id, rule_name, version)
);

CREATE INDEX idx_fraud_rules_tenant_enabled ON fraud.fraud_rules (tenant_id, enabled) WHERE enabled = true;
CREATE INDEX idx_fraud_rules_type ON fraud.fraud_rules (rule_type);

ALTER TABLE fraud.fraud_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fraud.fraud_rules
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3002__create_fraud_assessments_table.sql**:

```sql
CREATE TABLE fraud.fraud_assessments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    payment_id        VARCHAR(36) NOT NULL,
    native_score      INTEGER NOT NULL,         -- 0-100 from native rules
    frm_score         INTEGER,                  -- 0-100 from external FRM (nullable if FRM unavailable)
    frm_provider      VARCHAR(50),              -- SIFT, SIGNIFYD, NATIVE_ONLY
    aggregated_score  INTEGER NOT NULL,          -- weighted combination
    decision          VARCHAR(10) NOT NULL,      -- ALLOW, REVIEW, BLOCK
    triggered_rules   JSONB NOT NULL DEFAULT '[]',
    risk_signals      JSONB NOT NULL DEFAULT '{}',
    review_status     VARCHAR(20),               -- NULL, PENDING_REVIEW, APPROVED, REJECTED
    reviewed_by       VARCHAR(255),
    reviewed_at       TIMESTAMPTZ,
    assessed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    latency_ms        INTEGER NOT NULL,
    CONSTRAINT fk_fraud_assessment_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

CREATE INDEX idx_fraud_assessments_payment ON fraud.fraud_assessments (payment_id);
CREATE INDEX idx_fraud_assessments_decision ON fraud.fraud_assessments (tenant_id, decision);
CREATE INDEX idx_fraud_assessments_review ON fraud.fraud_assessments (tenant_id, review_status)
    WHERE review_status IS NOT NULL;

ALTER TABLE fraud.fraud_assessments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fraud.fraud_assessments
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3003__create_device_fingerprints_table.sql**:

```sql
CREATE TABLE fraud.device_fingerprints (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    fingerprint_hash  VARCHAR(64) NOT NULL,      -- SHA-256 of composite fingerprint
    customer_id       VARCHAR(36),
    browser_family    VARCHAR(50),
    os_family         VARCHAR(50),
    device_type       VARCHAR(20),               -- DESKTOP, MOBILE, TABLET
    screen_resolution VARCHAR(20),
    timezone_offset   INTEGER,
    language          VARCHAR(10),
    ip_address        INET,
    ip_country        VARCHAR(2),                -- ISO 3166-1 alpha-2
    ip_city           VARCHAR(100),
    reputation_score  INTEGER NOT NULL DEFAULT 50, -- 0 (malicious) to 100 (trusted)
    first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    times_seen        INTEGER NOT NULL DEFAULT 1,
    flagged           BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uk_device_fp_tenant_hash UNIQUE (tenant_id, fingerprint_hash)
);

CREATE INDEX idx_device_fp_customer ON fraud.device_fingerprints (tenant_id, customer_id);
CREATE INDEX idx_device_fp_ip ON fraud.device_fingerprints (ip_address);
CREATE INDEX idx_device_fp_reputation ON fraud.device_fingerprints (tenant_id, reputation_score)
    WHERE reputation_score < 30;

ALTER TABLE fraud.device_fingerprints ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fraud.device_fingerprints
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3004__create_fraud_events_table.sql**:

```sql
CREATE TABLE fraud.fraud_events (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    event_type        VARCHAR(50) NOT NULL,      -- FRAUD_CHECK_PASSED, FRAUD_CHECK_FAILED, FRAUD_CHECK_REVIEW, RULE_TRIGGERED
    assessment_id     UUID NOT NULL,
    payment_id        VARCHAR(36) NOT NULL,
    rule_id           UUID,
    payload           JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_events_assessment ON fraud.fraud_events (assessment_id);
CREATE INDEX idx_fraud_events_type_time ON fraud.fraud_events (tenant_id, event_type, created_at);

ALTER TABLE fraud.fraud_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fraud.fraud_events
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

### Configuration Additions

```yaml
# application-fraud.yml
nexuspay:
  fraud:
    enabled: true
    native-rules:
      cache-ttl: PT5M                    # Valkey cache TTL for rule sets
      default-block-threshold: 80        # score >= 80 → BLOCK
      default-review-threshold: 50       # score >= 50 → REVIEW
    scoring:
      native-weight: 0.6                 # 60% weight to native rule score
      frm-weight: 0.4                    # 40% weight to FRM provider score
    frm:
      primary-provider: sift             # sift | signifyd
      fallback-provider: signifyd
      timeout: PT2S                      # max wait for FRM provider response
      circuit-breaker:
        failure-rate-threshold: 50
        wait-duration-in-open-state: PT30S
        sliding-window-size: 20
    sift:
      api-url: https://api.sift.com/v205
      api-key: ${SIFT_API_KEY}
      account-id: ${SIFT_ACCOUNT_ID}
    signifyd:
      api-url: https://api.signifyd.com/v2
      api-key: ${SIGNIFYD_API_KEY}
      team-id: ${SIGNIFYD_TEAM_ID}
    device-fingerprint:
      enabled: true
      reputation-decay-days: 90          # reputation decays toward neutral after 90 days of inactivity
```

### Kafka Topic Additions

| Topic | Partitions | Retention | Key | Value |
|-------|-----------|-----------|-----|-------|
| `nexuspay.fraud.assessments` | 12 | 30 days | `tenantId:paymentId` | FraudAssessmentResult |
| `nexuspay.fraud.events` | 12 | 90 days | `tenantId:assessmentId` | FraudCheckPassed / FraudCheckFailed / FraudCheckReview / RuleTriggered |
| `nexuspay.fraud.rules.changelog` | 6 | 7 days | `tenantId:ruleId` | FraudRuleCreated / FraudRuleUpdated / FraudRuleDisabled |

### Test Strategy

| Layer | Scope | Tool | Count (est.) |
|-------|-------|------|-------------|
| Unit | Domain model (FraudRule, RiskAssessment, RuleCondition DSL parsing) | JUnit 5 + AssertJ | ~25 tests |
| Unit | RuleEvaluationPipeline (each rule type in isolation) | JUnit 5 + Mockito | ~30 tests |
| Unit | RiskScoringAggregator (weight calculations, edge cases) | JUnit 5 | ~10 tests |
| Unit | FallbackFraudChain (primary success, primary fail + secondary success, both fail) | JUnit 5 + Mockito | ~10 tests |
| Unit | Sift/Signifyd request/response mappers | JUnit 5 | ~15 tests |
| Integration | FRM adapters with WireMock (Sift API, Signifyd API) | Spring Boot Test + WireMock | ~12 tests |
| Integration | Fraud rule CRUD with PostgreSQL + RLS | Testcontainers (PostgreSQL) | ~10 tests |
| Integration | Valkey rule cache invalidation | Testcontainers (Valkey) | ~6 tests |
| Integration | Kafka event publishing and consumption | Testcontainers (Kafka) | ~8 tests |
| E2E | Full fraud assessment flow in payment authorization | Spring Boot Test + Testcontainers | ~5 tests |
| Architecture | Module boundary verification | Spring Modulith ArchUnit | 1 test |

### Acceptance Criteria

- [ ] `POST /v1/payments` with amount > configured threshold triggers REVIEW decision.
- [ ] Velocity rule blocks 4th payment from same card within 10 minutes when limit is 3.
- [ ] Geo-restriction rule blocks payment from sanctioned country IP.
- [ ] BIN rule flags known high-risk BIN ranges for review.
- [ ] FRM provider timeout triggers fallback to secondary provider; secondary timeout falls back to native-only scoring.
- [ ] Rule created via API is available for evaluation within 5 seconds (cache invalidation).
- [ ] A/B test splits traffic correctly (within 5% of configured percentage over 1000 evaluations).
- [ ] FraudCheckPassed, FraudCheckFailed, FraudCheckReview events published to Kafka.
- [ ] Fraud assessment latency < 80ms p95 for native-only evaluation.
- [ ] Device fingerprint stored and matched on subsequent payment from same device.
- [ ] Review queue API lists pending REVIEW assessments for manual decisioning.
- [ ] All fraud tables have RLS policies enforced.

---

## Sprint 3.2: Cross-Border & FX (Weeks 45-48)

### Overview

Extends the payment platform to handle multi-currency transactions with real-time FX rate management, currency-aware PSP routing, and proper double-entry ledger accounting for cross-currency settlements. Adds regulatory compliance hooks for cross-border restrictions.

### Dependencies on Phase 2

- **Multi-tenancy RLS (Sprint 2.1)**: FX rates are global but merchant preferences and conversion entries are tenant-scoped.
- **Ledger module (Phase 1, extended in Phase 2)**: New FX-specific account types and multi-leg journal entries build on the existing double-entry ledger.
- **Temporal workflows (Sprint 2.2)**: FX rate locking and conversion are activities in the payment workflow.
- **Observability (Sprint 2.6)**: FX rate cache hit ratios, conversion volumes, and FX gain/loss metrics.

### New Module Structure

FX functionality is added to `payment-orchestration` and `ledger` modules rather than creating a standalone module, since FX is cross-cutting across the payment flow:

```
payment-orchestration/src/main/java/io/nexuspay/payment/
├── domain/
│   ├── fx/
│   │   ├── FxRate.java
│   │   ├── FxRatePair.java                    // e.g., EUR/USD
│   │   ├── FxRateLock.java                    // locked rate for payment lifecycle
│   │   ├── CurrencyConversion.java
│   │   └── FxGainLoss.java
│   ├── routing/
│   │   └── CurrencyCapability.java            // which PSPs support which currencies
│   └── compliance/
│       ├── CountryRestriction.java
│       └── CrossBorderRule.java
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── ConvertCurrencyUseCase.java
│   │   │   ├── LockFxRateUseCase.java
│   │   │   └── ManageMerchantCurrencyPrefsUseCase.java
│   │   └── out/
│   │       ├── FxRatePort.java                // rate provider abstraction
│   │       ├── FxRateLockRepository.java
│   │       ├── CurrencyCapabilityRepository.java
│   │       └── CrossBorderCompliancePort.java
│   └── service/
│       ├── FxRateService.java
│       ├── FxRateLockService.java
│       ├── CurrencyRoutingService.java
│       ├── CrossBorderComplianceService.java
│       └── PayoutCurrencyService.java
├── adapter/
│   ├── in/
│   │   └── rest/
│   │       ├── FxRateController.java
│   │       └── MerchantCurrencyPrefsController.java
│   └── out/
│       ├── fx/
│       │   ├── EcbFxRateAdapter.java          // European Central Bank (free)
│       │   ├── OpenExchangeRatesAdapter.java  // Open Exchange Rates API
│       │   ├── CustomFxRateAdapter.java       // merchant-provided rates
│       │   └── ValkeyFxRateCache.java
│       ├── persistence/
│       │   ├── FxRateLockEntity.java
│       │   ├── JpaFxRateLockRepository.java
│       │   ├── FxRateLockRepositoryAdapter.java
│       │   ├── CurrencyCapabilityEntity.java
│       │   ├── JpaCurrencyCapabilityRepository.java
│       │   ├── MerchantCurrencyPrefsEntity.java
│       │   └── JpaMerchantCurrencyPrefsRepository.java
│       └── compliance/
│           └── SanctionsListAdapter.java
└── config/
    └── FxProperties.java

ledger/src/main/java/io/nexuspay/ledger/
├── domain/
│   ├── FxConversionEntry.java                 // multi-leg journal entry model
│   └── FxGainLossAccount.java
├── application/
│   ├── CreateFxConversionEntryUseCase.java
│   └── CalculateFxGainLossUseCase.java
└── adapter/
    └── out/
        └── persistence/
            ├── FxGainLossAccountEntity.java
            └── JpaFxGainLossAccountRepository.java
```

### Key Java Classes and Interfaces

**`io.nexuspay.payment.application.port.out.FxRatePort`** — Rate provider abstraction:

```java
public interface FxRatePort {
    FxRate getRate(CurrencyUnit from, CurrencyUnit to);
    List<FxRate> getAllRates(CurrencyUnit baseCurrency);
    String providerName();
    Instant lastUpdated();
}
```

**`io.nexuspay.payment.application.service.FxRateLockService`** — Rate locking for payment lifecycle:

```java
@Service
public class FxRateLockService {
    /**
     * Locks the FX rate at payment intent creation time.
     * The locked rate is guaranteed for a configurable duration (default 15 minutes).
     * After expiry, the rate is refreshed at confirmation time.
     */
    public FxRateLock lockRate(CurrencyUnit from, CurrencyUnit to, Duration lockDuration);
    public FxRateLock refreshLock(UUID lockId);
    public boolean isLockValid(UUID lockId);
}
```

**`io.nexuspay.ledger.application.CreateFxConversionEntryUseCase`** — Multi-leg FX journal entry:

```java
@Service
public class CreateFxConversionEntryUseCase {
    /**
     * Creates multi-leg journal entries for cross-currency payment:
     * Leg 1: DR merchant_receivable_{presentment_ccy}  CR customer_liability_{presentment_ccy}
     * Leg 2: DR merchant_receivable_{settlement_ccy}   CR merchant_receivable_{presentment_ccy}
     * Leg 3: DR/CR fx_gain_loss_{ccy_pair}             (balancing entry for rate difference)
     */
    public JournalEntry createFxConversion(FxConversionRequest request);
}
```

### Flyway Migration Schemas

**V3005__create_fx_rate_locks_table.sql**:

```sql
CREATE TABLE payment.fx_rate_locks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    payment_id        VARCHAR(36),
    from_currency     VARCHAR(3) NOT NULL,
    to_currency       VARCHAR(3) NOT NULL,
    rate              DECIMAL(18,8) NOT NULL,
    inverse_rate      DECIMAL(18,8) NOT NULL,
    rate_provider     VARCHAR(50) NOT NULL,
    locked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    consumed          BOOLEAN NOT NULL DEFAULT false,
    consumed_at       TIMESTAMPTZ,
    CONSTRAINT uk_fx_lock_payment UNIQUE (payment_id)
);

CREATE INDEX idx_fx_locks_expiry ON payment.fx_rate_locks (expires_at) WHERE NOT consumed;

ALTER TABLE payment.fx_rate_locks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment.fx_rate_locks
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3006__create_currency_capabilities_table.sql**:

```sql
CREATE TABLE payment.currency_capabilities (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    psp_connector     VARCHAR(50) NOT NULL,     -- e.g., STRIPE, ADYEN, CHECKOUT_COM
    currency_code     VARCHAR(3) NOT NULL,       -- ISO 4217
    supports_presentment BOOLEAN NOT NULL DEFAULT true,
    supports_settlement  BOOLEAN NOT NULL DEFAULT false,
    supports_dcc         BOOLEAN NOT NULL DEFAULT false,
    min_amount        DECIMAL(18,2),
    max_amount        DECIMAL(18,2),
    enabled           BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uk_currency_cap UNIQUE (psp_connector, currency_code)
);

CREATE INDEX idx_currency_cap_psp ON payment.currency_capabilities (psp_connector, enabled);
```

**V3007__create_merchant_currency_prefs_table.sql**:

```sql
CREATE TABLE payment.merchant_currency_prefs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    settlement_currency VARCHAR(3) NOT NULL,     -- preferred settlement currency
    auto_convert      BOOLEAN NOT NULL DEFAULT true,
    fx_markup_bps     INTEGER NOT NULL DEFAULT 0, -- merchant FX markup in basis points
    rate_provider     VARCHAR(50) NOT NULL DEFAULT 'ECB',
    rate_lock_duration_minutes INTEGER NOT NULL DEFAULT 15,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_ccy_pref UNIQUE (tenant_id)
);

ALTER TABLE payment.merchant_currency_prefs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment.merchant_currency_prefs
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3008__create_fx_gain_loss_accounts.sql**:

```sql
-- Create FX gain/loss ledger accounts per currency pair
-- These are system-level accounts, not tenant-scoped
CREATE TABLE ledger.fx_gain_loss_accounts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    currency_pair     VARCHAR(7) NOT NULL,       -- e.g., EUR/USD
    account_id        UUID NOT NULL,             -- reference to ledger_accounts
    realized_gain_loss DECIMAL(18,4) NOT NULL DEFAULT 0,
    unrealized_gain_loss DECIMAL(18,4) NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_fx_gl_tenant_pair UNIQUE (tenant_id, currency_pair),
    CONSTRAINT fk_fx_gl_account FOREIGN KEY (account_id) REFERENCES ledger.ledger_accounts(id)
);

ALTER TABLE ledger.fx_gain_loss_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.fx_gain_loss_accounts
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

### Configuration Additions

```yaml
# application-fx.yml
nexuspay:
  fx:
    enabled: true
    default-provider: ecb
    cache:
      ttl: PT1H                           # cache rates for 1 hour
      stale-ttl: PT24H                    # serve stale rates up to 24 hours in emergency
      refresh-interval: PT30M             # proactively refresh every 30 minutes
    rate-lock:
      default-duration: PT15M             # 15-minute rate lock
      max-duration: PT1H                  # maximum lock duration
    ecb:
      api-url: https://data.ecb.europa.eu/service/data/EXR
      update-schedule: "0 16 * * 1-5"     # ECB publishes ~16:00 CET weekdays
    open-exchange-rates:
      api-url: https://openexchangerates.org/api
      app-id: ${OER_APP_ID}
    compliance:
      sanctioned-countries:
        - KP   # North Korea
        - IR   # Iran
        - SY   # Syria
        - CU   # Cuba
      cross-border-amount-reporting-threshold: 10000  # USD equivalent
```

### Kafka Topic Additions

| Topic | Partitions | Retention | Key | Value |
|-------|-----------|-----------|-----|-------|
| `nexuspay.fx.rates` | 3 | 7 days | `currencyPair` | FxRateUpdated |
| `nexuspay.fx.conversions` | 12 | 30 days | `tenantId:paymentId` | CurrencyConversionCompleted |
| `nexuspay.fx.locks` | 6 | 7 days | `tenantId:lockId` | FxRateLocked / FxRateLockExpired |

### Test Strategy

| Layer | Scope | Tool | Count (est.) |
|-------|-------|------|-------------|
| Unit | FxRate, FxRatePair, CurrencyConversion domain models | JUnit 5 + AssertJ | ~15 tests |
| Unit | FxRateLockService (lock, refresh, expiry) | JUnit 5 + Mockito | ~12 tests |
| Unit | CurrencyRoutingService (PSP selection by currency) | JUnit 5 + Mockito | ~10 tests |
| Unit | CreateFxConversionEntryUseCase (multi-leg entries, gain/loss calc) | JUnit 5 + Mockito | ~15 tests |
| Integration | EcbFxRateAdapter + OpenExchangeRatesAdapter with WireMock | Spring Boot Test + WireMock | ~8 tests |
| Integration | ValkeyFxRateCache (caching, staleness, refresh) | Testcontainers (Valkey) | ~8 tests |
| Integration | FX rate lock persistence + expiry | Testcontainers (PostgreSQL) | ~6 tests |
| E2E | EUR payment → USD settlement with ledger verification | Spring Boot Test + Testcontainers | ~4 tests |
| E2E | Sanctioned country rejection | Spring Boot Test | ~3 tests |

### Acceptance Criteria

- [ ] Payment in EUR by customer settles in USD for merchant with correct FX conversion.
- [ ] Ledger shows 3-leg journal entry: presentment currency debit/credit, FX conversion, gain/loss.
- [ ] FX rate locked at intent creation is used at confirmation (within lock duration).
- [ ] Expired rate lock triggers automatic refresh at confirmation time.
- [ ] Valkey cache serves FX rates with < 5ms p95 latency.
- [ ] Stale rate served when provider is unavailable (within stale-ttl window).
- [ ] Currency-aware PSP selection only routes EUR payments to PSPs supporting EUR.
- [ ] Payment from sanctioned country IP is rejected with appropriate error code.
- [ ] Cross-border payment exceeding threshold includes reporting metadata.
- [ ] Merchant currency preference API allows setting/updating settlement currency.
- [ ] DCC flag correctly passed through to PSP when merchant opts in.

---

## Sprint 3.3: Smart Routing Engine (Weeks 49-52)

### Overview

Replaces the single-PSP routing with a pluggable routing engine supporting cost optimization, success rate optimization, latency optimization, failover cascading, and A/B testing of routing strategies. The router integrates with the fraud module (do not route to PSP if fraud check blocked) and FX module (currency-aware routing).

### Dependencies on Phase 2

- **Multi-tenancy RLS (Sprint 2.1)**: Route configurations are tenant-scoped.
- **Temporal workflows (Sprint 2.2)**: Routing decision is an activity in the payment workflow; cascade retries are Temporal activity retries.
- **Observability (Sprint 2.6)**: PSP auth rates, latency percentiles, and routing decision metrics in Grafana.

### New Module Structure

Routing engine is added to `payment-orchestration` since it is core to the payment flow:

```
payment-orchestration/src/main/java/io/nexuspay/payment/
├── domain/
│   └── routing/
│       ├── RoutingStrategy.java                // interface
│       ├── RoutingDecision.java
│       ├── RoutingContext.java
│       ├── PspCandidate.java
│       ├── PspFeeModel.java
│       ├── PspHealthSnapshot.java
│       ├── CascadeResult.java
│       └── event/
│           ├── RouteSelected.java
│           ├── RouteFailed.java
│           └── CascadeTriggered.java
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── RoutePaymentUseCase.java
│   │   │   ├── ManageRoutingConfigUseCase.java
│   │   │   └── RunRoutingAbTestUseCase.java
│   │   └── out/
│   │       ├── PspHealthRepository.java
│   │       ├── PspFeeRepository.java
│   │       ├── RoutingConfigRepository.java
│   │       ├── RoutingDecisionRepository.java
│   │       └── AuthRateRepository.java
│   └── service/
│       ├── RoutingEngine.java
│       ├── CascadeService.java
│       ├── PspHealthTracker.java
│       ├── AuthRateTracker.java
│       ├── RoutingAbTestService.java
│       └── strategy/
│           ├── CostBasedStrategy.java
│           ├── SuccessRateStrategy.java
│           ├── LatencyBasedStrategy.java
│           ├── RoundRobinStrategy.java
│           ├── WeightedStrategy.java
│           └── FailoverStrategy.java
├── adapter/
│   ├── in/
│   │   └── rest/
│   │       ├── RoutingConfigController.java
│   │       └── RoutingAbTestController.java
│   └── out/
│       ├── persistence/
│       │   ├── PspFeeModelEntity.java
│       │   ├── JpaPspFeeModelRepository.java
│       │   ├── RoutingConfigEntity.java
│       │   ├── JpaRoutingConfigRepository.java
│       │   ├── RoutingDecisionEntity.java
│       │   └── JpaRoutingDecisionRepository.java
│       ├── cache/
│       │   ├── ValkeyAuthRateCache.java
│       │   └── ValkeyPspLatencyCache.java
│       └── event/
│           └── KafkaRoutingEventPublisher.java
└── config/
    └── RoutingProperties.java
```

### Key Java Classes and Interfaces

**`io.nexuspay.payment.domain.routing.RoutingStrategy`** — Pluggable strategy interface:

```java
public interface RoutingStrategy {
    String name();
    RoutingDecision selectPsp(RoutingContext context, List<PspCandidate> candidates);
    /**
     * Returns a score for each candidate PSP. Higher score = preferred.
     * Scoring allows strategies to be composed (weighted combination).
     */
    Map<PspCandidate, Double> scoreCandidates(RoutingContext context, List<PspCandidate> candidates);
}
```

**`io.nexuspay.payment.application.service.RoutingEngine`** — Central routing orchestrator:

```java
@Service
public class RoutingEngine {
    /**
     * 1. Load tenant routing config (strategy, cascade depth, PSP list).
     * 2. Filter candidates: currency support, circuit breaker state, fraud module restrictions.
     * 3. Apply configured strategy (or A/B test strategy split).
     * 4. Return ordered list of PSPs for cascade attempts.
     */
    public RoutingDecision route(RoutingContext context);
}
```

**`io.nexuspay.payment.application.service.CascadeService`** — Automatic failover:

```java
@Service
public class CascadeService {
    /**
     * Attempts payment with PSPs in order from routing decision.
     * On soft decline: retry with next PSP.
     * On hard decline: stop cascade, return decline.
     * Max cascade depth configurable per tenant (default 3).
     * Publishes CascadeTriggered event on each failover.
     */
    public CascadeResult cascade(PaymentRequest request, List<PspCandidate> orderedPsps);
}
```

**`io.nexuspay.payment.application.service.strategy.CostBasedStrategy`**:

```java
@Component
public class CostBasedStrategy implements RoutingStrategy {
    /**
     * Calculates effective cost per PSP based on fee model:
     * - Per-transaction fee
     * - Percentage fee (applied to amount)
     * - Interchange++ (estimated interchange + scheme fee + acquirer markup)
     * - Blended rate
     * Routes to lowest effective cost PSP meeting SLA requirements.
     */
}
```

**`io.nexuspay.payment.application.service.strategy.SuccessRateStrategy`**:

```java
@Component
public class SuccessRateStrategy implements RoutingStrategy {
    /**
     * Looks up historical auth rate for each PSP filtered by:
     * - Card type (credit/debit)
     * - Card brand (Visa/MC/Amex)
     * - Issuing region
     * - Amount range bucket
     * Sliding window: last 7 days with hourly granularity from Valkey.
     * Routes to highest auth rate PSP for the given payment characteristics.
     */
}
```

### Flyway Migration Schemas

**V3009__create_psp_fee_models_table.sql**:

```sql
CREATE TABLE payment.psp_fee_models (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    fee_type          VARCHAR(20) NOT NULL,       -- PER_TX, PERCENTAGE, BLENDED, INTERCHANGE_PLUS_PLUS
    per_tx_fee        DECIMAL(10,4),              -- fixed per-transaction fee
    percentage_fee    DECIMAL(8,6),               -- percentage (e.g., 0.029000 = 2.9%)
    interchange_markup_bps INTEGER,               -- basis points above interchange
    scheme_fee_bps    INTEGER,
    currency          VARCHAR(3) NOT NULL,
    effective_from    DATE NOT NULL,
    effective_to      DATE,
    CONSTRAINT uk_psp_fee UNIQUE (tenant_id, psp_connector, currency, effective_from)
);

ALTER TABLE payment.psp_fee_models ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment.psp_fee_models
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3010__create_routing_configs_table.sql**:

```sql
CREATE TABLE payment.routing_configs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    config_name       VARCHAR(100) NOT NULL,
    strategy          VARCHAR(30) NOT NULL,       -- COST_BASED, SUCCESS_RATE, LATENCY, ROUND_ROBIN, WEIGHTED, FAILOVER
    psp_list          JSONB NOT NULL,             -- ordered list of PSPs with weights
    cascade_enabled   BOOLEAN NOT NULL DEFAULT true,
    max_cascade_depth INTEGER NOT NULL DEFAULT 3,
    filters           JSONB NOT NULL DEFAULT '{}', -- additional filters (min amount, currency, etc.)
    ab_test_id        UUID,                        -- NULL if not in A/B test
    ab_test_traffic   DECIMAL(5,4),
    enabled           BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_routing_config UNIQUE (tenant_id, config_name)
);

ALTER TABLE payment.routing_configs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment.routing_configs
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3011__create_routing_decisions_table.sql**:

```sql
CREATE TABLE payment.routing_decisions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    payment_id        VARCHAR(36) NOT NULL,
    strategy_used     VARCHAR(30) NOT NULL,
    config_id         UUID NOT NULL,
    selected_psp      VARCHAR(50) NOT NULL,
    candidate_scores  JSONB NOT NULL,              -- all PSP scores for auditability
    cascade_depth     INTEGER NOT NULL DEFAULT 0,
    cascade_psps      JSONB,                       -- list of PSPs attempted in cascade
    final_psp         VARCHAR(50),                 -- PSP that ultimately processed (after cascade)
    ab_test_id        UUID,
    ab_test_group     VARCHAR(1),                  -- A or B
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decision_latency_ms INTEGER NOT NULL,
    CONSTRAINT fk_routing_decision_config FOREIGN KEY (config_id) REFERENCES payment.routing_configs(id)
);

CREATE INDEX idx_routing_decisions_payment ON payment.routing_decisions (payment_id);
CREATE INDEX idx_routing_decisions_ab ON payment.routing_decisions (ab_test_id, ab_test_group)
    WHERE ab_test_id IS NOT NULL;

ALTER TABLE payment.routing_decisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment.routing_decisions
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

### Configuration Additions

```yaml
# application-routing.yml
nexuspay:
  routing:
    enabled: true
    default-strategy: success-rate
    cascade:
      enabled: true
      max-depth: 3
      soft-decline-codes:                    # decline codes that trigger cascade
        - DO_NOT_HONOR
        - INSUFFICIENT_FUNDS
        - ISSUER_UNAVAILABLE
      hard-decline-codes:                    # decline codes that stop cascade
        - STOLEN_CARD
        - LOST_CARD
        - FRAUD
        - INVALID_CARD
    health:
      sliding-window-hours: 168              # 7-day sliding window
      min-sample-size: 100                   # minimum transactions for statistical confidence
      unhealthy-auth-rate-threshold: 0.70    # below 70% auth rate = unhealthy
    latency:
      tracking-window-minutes: 60            # 1-hour window for latency percentiles
      unhealthy-p95-threshold-ms: 3000       # p95 > 3s = unhealthy
    ab-testing:
      min-sample-size: 1000                  # minimum per group for significance
      confidence-level: 0.95                 # 95% confidence for auto-promotion
      auto-promote: true                     # auto-promote winning strategy
```

### Kafka Topic Additions

| Topic | Partitions | Retention | Key | Value |
|-------|-----------|-----------|-----|-------|
| `nexuspay.routing.decisions` | 12 | 30 days | `tenantId:paymentId` | RouteSelected |
| `nexuspay.routing.cascades` | 12 | 30 days | `tenantId:paymentId` | CascadeTriggered |
| `nexuspay.routing.failures` | 6 | 90 days | `tenantId:paymentId` | RouteFailed |
| `nexuspay.routing.ab-test.results` | 6 | 90 days | `tenantId:abTestId` | AbTestResultUpdated |

### Test Strategy

| Layer | Scope | Tool | Count (est.) |
|-------|-------|------|-------------|
| Unit | CostBasedStrategy (fee calculations, edge cases) | JUnit 5 + AssertJ | ~15 tests |
| Unit | SuccessRateStrategy (auth rate lookups, bucketing) | JUnit 5 + Mockito | ~15 tests |
| Unit | LatencyBasedStrategy (percentile calculations) | JUnit 5 | ~10 tests |
| Unit | RoutingEngine (candidate filtering, strategy dispatch) | JUnit 5 + Mockito | ~12 tests |
| Unit | CascadeService (soft vs hard decline, depth limits) | JUnit 5 + Mockito | ~10 tests |
| Unit | RoutingAbTestService (traffic split, significance calc) | JUnit 5 | ~8 tests |
| Integration | ValkeyAuthRateCache + ValkeyPspLatencyCache | Testcontainers (Valkey) | ~8 tests |
| Integration | Routing config CRUD with PostgreSQL + RLS | Testcontainers (PostgreSQL) | ~6 tests |
| Integration | Full routing decision with Kafka event publishing | Testcontainers (Kafka) | ~6 tests |
| E2E | Payment with cascade: primary decline → secondary success | Spring Boot Test + Testcontainers | ~4 tests |
| E2E | A/B test: traffic splitting verification over 500 payments | Spring Boot Test + Testcontainers | ~2 tests |

### Acceptance Criteria

- [ ] Cost-based strategy routes to cheapest PSP for given payment characteristics.
- [ ] Success-rate strategy routes to highest auth-rate PSP for card type + region.
- [ ] Latency-based strategy avoids PSPs with p95 > threshold.
- [ ] Circuit-broken PSP is excluded from candidate list.
- [ ] Cascade on soft decline: payment retried with next PSP, succeeds on 2nd attempt.
- [ ] Hard decline stops cascade immediately, returns decline to merchant.
- [ ] Max cascade depth respected (no more than N PSP attempts).
- [ ] Routing decision logged with all candidate scores for auditability.
- [ ] A/B test splits traffic within 5% of configured percentage over 1000 payments.
- [ ] A/B test auto-promotes winning strategy when significance threshold reached.
- [ ] RouteSelected, RouteFailed, CascadeTriggered events published to Kafka.
- [ ] Routing decision latency < 10ms p95 (in-memory strategy evaluation).
- [ ] Currency-aware filtering excludes PSPs that do not support presentment currency.

---

## Sprint 3.4: Event Architecture Upgrade (Weeks 53-56)

### Overview

Migrates all Kafka events from JSON to Avro with Confluent Schema Registry, introduces event versioning with upcasters, adds an append-only event store in PostgreSQL for aggregate replay and optional event sourcing, and improves consumer infrastructure with batch processing and dead letter queue management.

### Dependencies on Phase 2

- **Debezium CDC (Sprint 2.1)**: Debezium output must be migrated to Avro; Schema Registry used for CDC schemas too.
- **All Phase 2 event producers**: Every module that publishes events must be migrated to Avro.
- **Observability (Sprint 2.6)**: Schema Registry metrics in Grafana; consumer lag monitoring must survive migration.

### New Module Structure

Event infrastructure is added to the `common` module (shared schemas and serialization) and `app` module (infrastructure configuration):

```
common/src/main/
├── avro/                                       # Avro schema definitions (.avsc files)
│   ├── payment/
│   │   ├── PaymentCreated.avsc
│   │   ├── PaymentConfirmed.avsc
│   │   ├── PaymentCaptured.avsc
│   │   ├── PaymentCancelled.avsc
│   │   ├── PaymentRefunded.avsc
│   │   └── PaymentFailed.avsc
│   ├── ledger/
│   │   ├── JournalEntryCreated.avsc
│   │   └── BalanceUpdated.avsc
│   ├── billing/
│   │   ├── SubscriptionCreated.avsc
│   │   ├── InvoiceGenerated.avsc
│   │   └── DunningAttempted.avsc
│   ├── fraud/
│   │   ├── FraudCheckPassed.avsc
│   │   ├── FraudCheckFailed.avsc
│   │   ├── FraudCheckReview.avsc
│   │   └── RuleTriggered.avsc
│   ├── routing/
│   │   ├── RouteSelected.avsc
│   │   ├── RouteFailed.avsc
│   │   └── CascadeTriggered.avsc
│   └── common/
│       ├── Money.avsc
│       ├── EventMetadata.avsc
│       └── TenantContext.avsc
├── java/io/nexuspay/common/
│   ├── event/
│   │   ├── versioning/
│   │   │   ├── EventVersion.java               // annotation: @EventVersion(version = 2)
│   │   │   ├── EventUpcaster.java              // interface: upcast(GenericRecord v1) → GenericRecord v2
│   │   │   ├── EventUpcasterChain.java
│   │   │   ├── PaymentCreatedV1ToV2Upcaster.java
│   │   │   └── UpcasterRegistry.java
│   │   ├── serde/
│   │   │   ├── AvroEventSerializer.java
│   │   │   ├── AvroEventDeserializer.java
│   │   │   └── DualWriteSerializer.java        // writes both JSON + Avro during migration
│   │   ├── store/
│   │   │   ├── EventStore.java                 // interface
│   │   │   ├── EventStoreEntry.java
│   │   │   ├── AggregateSnapshot.java
│   │   │   └── EventStoreQueryCriteria.java
│   │   └── consumer/
│   │       ├── BatchEventConsumer.java          // interface for batch processing
│   │       ├── DeadLetterPublisher.java
│   │       └── DeadLetterReprocessor.java
│   └── config/
│       └── SchemaRegistryProperties.java

app/src/main/java/io/nexuspay/app/
├── config/
│   ├── SchemaRegistryConfig.java
│   ├── KafkaAvroProducerConfig.java
│   ├── KafkaAvroConsumerConfig.java
│   └── EventStoreConfig.java
└── adapter/
    └── out/
        ├── eventstore/
        │   ├── PostgresEventStore.java
        │   ├── EventStoreEntity.java
        │   ├── SnapshotEntity.java
        │   ├── JpaEventStoreRepository.java
        │   └── JpaSnapshotRepository.java
        └── dlq/
            ├── DeadLetterController.java       // API for reprocessing DLQ
            └── DeadLetterQueueConsumer.java
```

### Key Java Classes and Interfaces

**`io.nexuspay.common.event.versioning.EventUpcaster`**:

```java
public interface EventUpcaster<S, T> {
    int fromVersion();
    int toVersion();
    String eventType();
    T upcast(S oldEvent);
}
```

**`io.nexuspay.common.event.versioning.EventUpcasterChain`**:

```java
@Component
public class EventUpcasterChain {
    /**
     * Given an event at version N, applies upcasters sequentially until reaching
     * the latest version. Example: v1 → v2 → v3.
     * Each upcaster is registered in UpcasterRegistry keyed by (eventType, fromVersion).
     */
    public GenericRecord upcastToLatest(String eventType, int currentVersion, GenericRecord event);
}
```

**`io.nexuspay.common.event.store.EventStore`**:

```java
public interface EventStore {
    void append(String aggregateType, String aggregateId, List<EventStoreEntry> events);
    List<EventStoreEntry> loadEvents(String aggregateType, String aggregateId);
    List<EventStoreEntry> loadEvents(String aggregateType, String aggregateId, int afterVersion);
    Optional<AggregateSnapshot> loadSnapshot(String aggregateType, String aggregateId);
    void saveSnapshot(AggregateSnapshot snapshot);
    /** Query across aggregates (for projections/analytics) */
    List<EventStoreEntry> query(EventStoreQueryCriteria criteria);
}
```

**`io.nexuspay.common.event.serde.DualWriteSerializer`**:

```java
@Component
public class DualWriteSerializer {
    /**
     * Migration strategy: writes both JSON and Avro to separate topics.
     * Phase 1 (weeks 53-54): dual-write active, JSON consumers still running.
     * Phase 2 (weeks 55-56): validate Avro parity, cut over consumers, remove JSON.
     * Controlled by feature flag: nexuspay.events.dual-write-enabled.
     */
    public void publish(String topic, String key, Object event);
}
```

### Flyway Migration Schemas

**V3012__create_event_store_table.sql**:

```sql
CREATE TABLE common.event_store (
    id                  BIGSERIAL PRIMARY KEY,
    aggregate_type      VARCHAR(100) NOT NULL,
    aggregate_id        VARCHAR(100) NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    event_version       INTEGER NOT NULL,
    sequence_number     INTEGER NOT NULL,         -- per-aggregate sequence
    tenant_id           VARCHAR(36) NOT NULL,
    payload             BYTEA NOT NULL,            -- Avro-encoded event
    metadata            JSONB NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_event_store_aggregate_seq UNIQUE (aggregate_type, aggregate_id, sequence_number)
);

CREATE INDEX idx_event_store_aggregate ON common.event_store (aggregate_type, aggregate_id, sequence_number);
CREATE INDEX idx_event_store_type_time ON common.event_store (event_type, created_at);
CREATE INDEX idx_event_store_tenant ON common.event_store (tenant_id, created_at);

-- This table is append-only; no UPDATE or DELETE allowed at application level.
-- Partition by month for manageability:
-- (Partitioning to be applied via separate migration when volume warrants it.)

ALTER TABLE common.event_store ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON common.event_store
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3013__create_event_snapshots_table.sql**:

```sql
CREATE TABLE common.event_snapshots (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type      VARCHAR(100) NOT NULL,
    aggregate_id        VARCHAR(100) NOT NULL,
    snapshot_version    INTEGER NOT NULL,
    last_sequence       INTEGER NOT NULL,          -- last event sequence included
    tenant_id           VARCHAR(36) NOT NULL,
    payload             BYTEA NOT NULL,             -- serialized aggregate state
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_snapshot_aggregate UNIQUE (aggregate_type, aggregate_id, snapshot_version)
);

ALTER TABLE common.event_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON common.event_snapshots
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3014__create_dead_letter_queue_table.sql**:

```sql
CREATE TABLE common.dead_letter_queue (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_topic      VARCHAR(255) NOT NULL,
    original_partition  INTEGER NOT NULL,
    original_offset     BIGINT NOT NULL,
    key                 VARCHAR(255),
    payload             BYTEA NOT NULL,
    error_message       TEXT NOT NULL,
    error_class         VARCHAR(255) NOT NULL,
    stack_trace         TEXT,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    max_retries         INTEGER NOT NULL DEFAULT 3,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, RETRYING, RESOLVED, DISCARDED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_retried_at     TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX idx_dlq_status ON common.dead_letter_queue (status, created_at);
CREATE INDEX idx_dlq_topic ON common.dead_letter_queue (original_topic, status);
```

### Docker Compose Additions

```yaml
# docker-compose.yml additions
services:
  schema-registry:
    image: confluentinc/cp-schema-registry:7.6.1
    hostname: schema-registry
    container_name: nexuspay-schema-registry
    depends_on:
      kafka:
        condition: service_healthy
    ports:
      - "8081:8081"
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: kafka:29092
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081
      SCHEMA_REGISTRY_SCHEMA_COMPATIBILITY_LEVEL: BACKWARD
      SCHEMA_REGISTRY_AVRO_COMPATIBILITY_LEVEL: BACKWARD
    healthcheck:
      test: curl -f http://localhost:8081/subjects || exit 1
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - nexuspay-network

  schema-registry-ui:
    image: landoop/schema-registry-ui:latest
    container_name: nexuspay-schema-registry-ui
    depends_on:
      - schema-registry
    ports:
      - "8082:8000"
    environment:
      SCHEMAREGISTRY_URL: http://schema-registry:8081
    networks:
      - nexuspay-network
```

### Configuration Additions

```yaml
# application-events.yml
nexuspay:
  events:
    schema-registry:
      url: http://localhost:8081
      cache-capacity: 100
      auto-register-schemas: true
    serialization:
      format: avro                           # json | avro | dual-write
      dual-write-enabled: false              # enable during migration only
    event-store:
      enabled: true
      snapshot-threshold: 100                # snapshot every 100 events per aggregate
      retention-days: 365                    # keep events for 1 year
    consumer:
      batch-size: 50                         # process events in batches
      batch-timeout-ms: 1000                 # max wait before processing incomplete batch
      parallel-consumers: 4                  # consumers per topic partition
    dead-letter:
      enabled: true
      max-retries: 3
      retry-backoff-ms: 60000               # 1 minute between retries
      reprocessing-cron: "0 */5 * * * *"    # attempt DLQ reprocessing every 5 minutes
    versioning:
      require-version-header: true
      latest-versions:                       # map of event type → latest version
        PaymentCreated: 2
        PaymentConfirmed: 1
        JournalEntryCreated: 1
        FraudCheckPassed: 1

spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      properties:
        schema.registry.url: ${nexuspay.events.schema-registry.url}
        auto.register.schemas: ${nexuspay.events.schema-registry.auto-register-schemas}
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      properties:
        schema.registry.url: ${nexuspay.events.schema-registry.url}
        specific.avro.reader: true
```

### Kafka Topic Additions

| Topic | Partitions | Retention | Key | Value |
|-------|-----------|-----------|-----|-------|
| `nexuspay.dlq` | 6 | unlimited | original topic + partition + offset | Failed event payload |
| Existing topics unchanged but value format migrated from JSON to Avro | | | | |

### Test Strategy

| Layer | Scope | Tool | Count (est.) |
|-------|-------|------|-------------|
| Unit | AvroEventSerializer/Deserializer round-trip | JUnit 5 | ~10 tests |
| Unit | EventUpcasterChain (v1→v2, v1→v2→v3, no upcaster needed) | JUnit 5 | ~10 tests |
| Unit | DualWriteSerializer (both formats written, feature flag toggle) | JUnit 5 + Mockito | ~6 tests |
| Unit | BatchEventConsumer (batch size, timeout, error handling) | JUnit 5 + Mockito | ~8 tests |
| Integration | Schema Registry schema registration and compatibility check | Testcontainers (Schema Registry + Kafka) | ~8 tests |
| Integration | Event store append + load + snapshot | Testcontainers (PostgreSQL) | ~10 tests |
| Integration | DLQ publish + reprocess | Testcontainers (Kafka + PostgreSQL) | ~6 tests |
| Integration | Full Avro event round-trip: producer → Kafka → consumer | Testcontainers (Schema Registry + Kafka) | ~8 tests |
| Migration | Dual-write parity verification (JSON event = Avro event content) | Spring Boot Test | ~5 tests |

### Acceptance Criteria

- [ ] Schema Registry running and accessible in Docker Compose and Kubernetes.
- [ ] All event types registered as Avro schemas with BACKWARD compatibility.
- [ ] Dual-write mode publishes identical content in both JSON and Avro formats.
- [ ] After cutover, all consumers deserialize Avro events correctly.
- [ ] EventUpcaster chain transparently converts v1 events to v2 during consumption.
- [ ] Event store appends events for payment aggregate and replays to reconstruct state.
- [ ] Snapshot created after 100 events; replay from snapshot + subsequent events produces correct state.
- [ ] Dead letter queue captures failed events with error details.
- [ ] DLQ reprocessing API allows retry of specific failed events.
- [ ] Batch consumer processes 50 events per batch with correct ordering.
- [ ] Zero event loss during JSON-to-Avro migration (validated by count comparison).
- [ ] Schema evolution: adding a nullable field to PaymentCreated passes compatibility check.
- [ ] Schema evolution: removing a required field is rejected by Schema Registry.

---

## Sprint 3.5: Client-Side SDK (Weeks 57-60)

### Overview

Introduces the `checkout-sdk` directory containing NexusPay.js (a TypeScript library for PCI-compliant card tokenization), a React component library, backend APIs for payment sessions and tokenization, alternative payment method support, and a hosted drop-in checkout page. The SDK reduces merchant PCI scope to SAQ-A by handling card data in an iframe that communicates directly with NexusPay servers.

### Dependencies on Phase 2

- **Multi-tenancy RLS (Sprint 2.1)**: Payment sessions and tokens are tenant-scoped.
- **Temporal workflows (Sprint 2.2)**: Payment confirmation triggered by SDK goes through Temporal workflow.
- **Observability (Sprint 2.6)**: SDK usage metrics (load time, tokenization latency, conversion rate).

### New Module Structure

The SDK is a separate workspace at the repository root (not a Gradle module since it is TypeScript):

```
checkout-sdk/
├── package.json
├── tsconfig.json
├── vite.config.ts
├── vitest.config.ts
├── playwright.config.ts
├── packages/
│   ├── nexuspay-js/                          # @nexus-pay/js
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── vite.config.ts
│   │   ├── src/
│   │   │   ├── index.ts
│   │   │   ├── nexuspay.ts                   # NexusPay class (entry point)
│   │   │   ├── types/
│   │   │   │   ├── index.ts
│   │   │   │   ├── payment-session.ts
│   │   │   │   ├── payment-element.ts
│   │   │   │   ├── token.ts
│   │   │   │   ├── appearance.ts
│   │   │   │   └── events.ts
│   │   │   ├── elements/
│   │   │   │   ├── payment-element.ts        # Payment method selection + card input
│   │   │   │   ├── card-element.ts           # PCI iframe for card input
│   │   │   │   ├── address-element.ts        # Address collection
│   │   │   │   └── iframe-manager.ts         # iframe creation, postMessage, sizing
│   │   │   ├── iframe/
│   │   │   │   ├── card-frame.html           # HTML loaded inside PCI iframe
│   │   │   │   ├── card-frame.ts             # iframe-internal logic (card validation, tokenization)
│   │   │   │   └── card-frame.css
│   │   │   ├── three-d-secure/
│   │   │   │   ├── challenge-handler.ts      # 3DS challenge flow in iframe
│   │   │   │   └── fingerprint-handler.ts    # 3DS method (device fingerprint for Sprint 3.1)
│   │   │   ├── apm/
│   │   │   │   ├── apple-pay.ts
│   │   │   │   ├── google-pay.ts
│   │   │   │   ├── bank-redirect.ts          # iDEAL, Bancontact
│   │   │   │   └── bnpl.ts                   # Klarna, Afterpay redirect
│   │   │   ├── api/
│   │   │   │   ├── client.ts                 # HTTP client for NexusPay API
│   │   │   │   ├── session.ts                # payment session management
│   │   │   │   └── tokenize.ts               # tokenization API call
│   │   │   └── utils/
│   │   │       ├── card-validator.ts          # Luhn, BIN detection, expiry
│   │   │       ├── event-emitter.ts
│   │   │       └── logger.ts
│   │   └── tests/
│   │       ├── unit/
│   │       │   ├── card-validator.test.ts
│   │       │   ├── iframe-manager.test.ts
│   │       │   └── event-emitter.test.ts
│   │       └── integration/
│   │           ├── payment-element.test.ts
│   │           └── tokenization.test.ts
│   ├── nexuspay-react/                       # @nexus-pay/react
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── vite.config.ts
│   │   ├── src/
│   │   │   ├── index.ts
│   │   │   ├── NexusPayProvider.tsx          # React context provider
│   │   │   ├── PaymentElement.tsx            # <PaymentElement /> component
│   │   │   ├── AddressElement.tsx            # <AddressElement /> component
│   │   │   ├── hooks/
│   │   │   │   ├── useNexusPay.ts            # hook to access NexusPay instance
│   │   │   │   ├── usePaymentElement.ts
│   │   │   │   └── useConfirmPayment.ts
│   │   │   └── types/
│   │   │       └── index.ts
│   │   └── tests/
│   │       ├── NexusPayProvider.test.tsx
│   │       ├── PaymentElement.test.tsx
│   │       └── hooks.test.tsx
│   └── nexuspay-checkout/                    # Hosted checkout page
│       ├── package.json
│       ├── tsconfig.json
│       ├── vite.config.ts
│       ├── src/
│       │   ├── main.ts
│       │   ├── App.tsx
│       │   ├── pages/
│       │   │   └── CheckoutPage.tsx
│       │   ├── components/
│       │   │   ├── BrandingHeader.tsx
│       │   │   ├── PaymentSummary.tsx
│       │   │   └── SuccessConfirmation.tsx
│       │   └── styles/
│       │       ├── theme.ts
│       │       └── checkout.css
│       └── tests/
│           └── CheckoutPage.test.tsx
└── e2e/                                       # Playwright E2E tests
    ├── tokenization.spec.ts
    ├── three-d-secure.spec.ts
    ├── apple-pay.spec.ts
    ├── hosted-checkout.spec.ts
    └── fixtures/
        └── test-cards.ts
```

Backend API additions in existing modules:

```
gateway-api/src/main/java/io/nexuspay/gateway/
├── adapter/
│   └── in/
│       └── rest/
│           ├── PaymentSessionController.java
│           └── TokenController.java
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── CreatePaymentSessionUseCase.java
│   │   │   └── TokenizePaymentMethodUseCase.java
│   │   └── out/
│   │       ├── PaymentSessionRepository.java
│   │       └── PaymentTokenRepository.java
│   └── service/
│       ├── PaymentSessionService.java
│       └── TokenizationService.java
└── domain/
    ├── PaymentSession.java
    ├── SessionToken.java
    └── PaymentToken.java

iam/src/main/java/io/nexuspay/iam/
└── application/
    └── service/
        └── SessionTokenIssuer.java              // issues restricted-scope JWT for SDK
```

### Key Interfaces

**Backend: `io.nexuspay.gateway.application.port.in.CreatePaymentSessionUseCase`**:

```java
public interface CreatePaymentSessionUseCase {
    /**
     * POST /v1/payment-sessions
     * Creates a client-side session with a restricted-scope token.
     * The token can only confirm the specific payment associated with this session.
     * Session expires after configurable duration (default 24 hours).
     */
    PaymentSession create(PaymentSessionCreateRequest request);
}
```

**Backend: `io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase`**:

```java
public interface TokenizePaymentMethodUseCase {
    /**
     * POST /v1/tokens
     * Called from the PCI iframe. Receives encrypted card data,
     * decrypts server-side, validates, stores token, returns token ID.
     * Card PAN is never logged or stored in plaintext.
     */
    PaymentToken tokenize(TokenizeRequest request, SessionToken sessionToken);
}
```

**TypeScript: NexusPay entry point** (`checkout-sdk/packages/nexuspay-js/src/nexuspay.ts`):

```typescript
export class NexusPay {
  constructor(publishableKey: string, options?: NexusPayOptions);
  elements(options?: ElementsOptions): Elements;
  confirmPayment(options: ConfirmPaymentOptions): Promise<PaymentResult>;
  confirmSetup(options: ConfirmSetupOptions): Promise<SetupResult>;
}

export interface Elements {
  create(type: 'payment' | 'card' | 'address', options?: ElementOptions): Element;
  getElement(type: string): Element | null;
}
```

### Flyway Migration Schemas

**V3015__create_payment_sessions_table.sql**:

```sql
CREATE TABLE gateway.payment_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    payment_id        VARCHAR(36) NOT NULL,
    client_secret     VARCHAR(255) NOT NULL,      -- used by SDK to authenticate
    session_token     TEXT NOT NULL,               -- JWT with restricted scope
    allowed_payment_methods JSONB NOT NULL DEFAULT '["card"]',
    amount            DECIMAL(18,2) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, CONSUMED, EXPIRED
    return_url        TEXT,
    branding           JSONB,                      -- logo, colors, fonts for hosted checkout
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at       TIMESTAMPTZ,
    CONSTRAINT uk_session_client_secret UNIQUE (client_secret)
);

CREATE INDEX idx_payment_sessions_payment ON gateway.payment_sessions (payment_id);
CREATE INDEX idx_payment_sessions_expiry ON gateway.payment_sessions (expires_at) WHERE status = 'ACTIVE';

ALTER TABLE gateway.payment_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON gateway.payment_sessions
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3016__create_payment_tokens_table.sql**:

```sql
CREATE TABLE gateway.payment_tokens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    token_type        VARCHAR(20) NOT NULL,        -- CARD, BANK_ACCOUNT, WALLET
    last_four         VARCHAR(4),
    card_brand        VARCHAR(20),                  -- VISA, MASTERCARD, AMEX, etc.
    card_exp_month    INTEGER,
    card_exp_year     INTEGER,
    card_fingerprint  VARCHAR(64),                  -- hash of card PAN for dedup
    bin               VARCHAR(8),
    customer_id       VARCHAR(36),
    payment_method_data BYTEA,                      -- encrypted payment method details
    encryption_key_id VARCHAR(36) NOT NULL,
    single_use        BOOLEAN NOT NULL DEFAULT true,
    used              BOOLEAN NOT NULL DEFAULT false,
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_token_fingerprint UNIQUE (tenant_id, card_fingerprint) -- for multi-use tokens
);

CREATE INDEX idx_payment_tokens_customer ON gateway.payment_tokens (tenant_id, customer_id);

ALTER TABLE gateway.payment_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON gateway.payment_tokens
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

### Configuration Additions

```yaml
# application-sdk.yml
nexuspay:
  sdk:
    session:
      default-expiry: PT24H                    # session valid for 24 hours
      max-expiry: PT72H
    tokenization:
      encryption-algorithm: AES-256-GCM
      key-rotation-days: 90
      single-use-token-expiry: PT15M           # single-use tokens expire in 15 minutes
      multi-use-token-expiry: P365D            # multi-use tokens valid for 1 year
    hosted-checkout:
      enabled: true
      base-url: https://checkout.nexuspay.io
      default-theme:
        font-family: "Inter, system-ui, sans-serif"
        primary-color: "#0066FF"
        border-radius: "8px"
    cors:
      allowed-origins:
        - "https://*.nexuspay.io"
      allowed-methods:
        - POST
        - GET
    cdn:
      base-url: https://js.nexuspay.io
      version-path: /v1/
```

### Test Strategy

| Layer | Scope | Tool | Count (est.) |
|-------|-------|------|-------------|
| Unit (TS) | Card validator (Luhn, BIN, expiry, formatting) | Vitest | ~20 tests |
| Unit (TS) | Event emitter, iframe manager, API client | Vitest | ~15 tests |
| Unit (TS) | React components (render, props, callbacks) | Vitest + React Testing Library | ~15 tests |
| Unit (Java) | PaymentSessionService (create, expire, consume) | JUnit 5 + Mockito | ~10 tests |
| Unit (Java) | TokenizationService (encrypt, store, validate) | JUnit 5 + Mockito | ~10 tests |
| Unit (Java) | SessionTokenIssuer (JWT creation, restricted scope) | JUnit 5 | ~6 tests |
| Integration (Java) | Payment session + token persistence with RLS | Testcontainers (PostgreSQL) | ~8 tests |
| Integration (Java) | Session token validation in payment confirmation flow | Spring Boot Test | ~5 tests |
| E2E (Browser) | Card tokenization in PCI iframe | Playwright | ~5 tests |
| E2E (Browser) | 3D Secure challenge flow | Playwright | ~3 tests |
| E2E (Browser) | Hosted checkout page flow | Playwright | ~4 tests |
| E2E (Browser) | Apple Pay / Google Pay button rendering | Playwright | ~2 tests |

### Acceptance Criteria

- [ ] `POST /v1/payment-sessions` creates a session with a restricted-scope JWT.
- [ ] SDK initializes with publishable key and creates PaymentElement in DOM.
- [ ] Card data entered in PCI iframe; PAN never reaches merchant server or main page context.
- [ ] `POST /v1/tokens` tokenizes card and returns token ID with last four digits and brand.
- [ ] Token used to confirm payment via existing `POST /v1/payments/{id}/confirm`.
- [ ] 3D Secure challenge renders in iframe and completes successfully.
- [ ] Hosted checkout page at `/checkout/{session_id}` renders with merchant branding.
- [ ] React `<PaymentElement>` component renders and handles payment confirmation.
- [ ] Apple Pay and Google Pay buttons render when available (via PSP).
- [ ] Bank redirect (iDEAL) initiates redirect flow and handles return callback.
- [ ] BNPL (Klarna) redirect flow works end-to-end.
- [ ] `@nexus-pay/js` bundle < 50KB gzipped.
- [ ] Single-use token expires after 15 minutes if unused.
- [ ] Session token cannot be used to perform actions outside the specific payment session.
- [ ] CDN serves versioned SDK at `https://js.nexuspay.io/v1/nexuspay.js`.
- [ ] npm packages `@nexus-pay/js` and `@nexus-pay/react` have TypeScript type definitions.

---

## Sprint 3.6: Payment Analytics Platform (Weeks 61-64)

### Overview

Builds an analytics data pipeline that consumes payment events from Kafka, aggregates them into time-series rollup tables, and exposes API endpoints for authorization rate analysis, PSP health scoring, revenue analytics, and decline analysis. Designed for consumption by Grafana dashboards or a custom analytics UI.

### Dependencies on Phase 2

- **Debezium CDC (Sprint 2.1)**: Analytics pipeline may consume CDC events for real-time updates.
- **Observability (Sprint 2.6)**: Analytics complements Prometheus metrics with business-level insights; Grafana can query analytics API endpoints.
- **Sprint 3.3 (Smart Routing)**: Analytics feeds back into routing decisions (auth rate data used by SuccessRateStrategy).
- **Sprint 3.4 (Event Architecture Upgrade)**: Analytics consumers process Avro events from Schema Registry.

### New Module Structure

Analytics is a new Gradle module since it has distinct data storage and query patterns:

```
analytics/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/io/nexuspay/analytics/
    │   │   ├── package-info.java
    │   │   ├── domain/
    │   │   │   ├── model/
    │   │   │   │   ├── AuthRateMetric.java
    │   │   │   │   ├── PspHealthScore.java
    │   │   │   │   ├── RevenueMetric.java
    │   │   │   │   ├── DeclineAnalysis.java
    │   │   │   │   ├── TimeGranularity.java        // HOURLY, DAILY, MONTHLY
    │   │   │   │   ├── AnalyticsDimension.java      // enum: PSP, CARD_TYPE, REGION, CURRENCY, etc.
    │   │   │   │   └── AnomalyAlert.java
    │   │   │   └── event/
    │   │   │       ├── PspHealthDegraded.java
    │   │   │       └── AnomalyDetected.java
    │   │   ├── application/
    │   │   │   ├── port/
    │   │   │   │   ├── in/
    │   │   │   │   │   ├── QueryAuthRatesUseCase.java
    │   │   │   │   │   ├── QueryPspHealthUseCase.java
    │   │   │   │   │   ├── QueryRevenueUseCase.java
    │   │   │   │   │   └── QueryDeclinesUseCase.java
    │   │   │   │   └── out/
    │   │   │   │       ├── AuthRateRollupRepository.java
    │   │   │   │       ├── PspHealthRepository.java
    │   │   │   │       ├── RevenueRollupRepository.java
    │   │   │   │       ├── DeclineRollupRepository.java
    │   │   │   │       └── AnalyticsEventPublisher.java
    │   │   │   ├── service/
    │   │   │   │   ├── AuthRateAnalyticsService.java
    │   │   │   │   ├── PspHealthScoringService.java
    │   │   │   │   ├── RevenueAnalyticsService.java
    │   │   │   │   ├── DeclineAnalyticsService.java
    │   │   │   │   └── AnomalyDetectionService.java
    │   │   │   └── dto/
    │   │   │       ├── AnalyticsQuery.java            // date range, groupBy, filters
    │   │   │       ├── AuthRateResponse.java
    │   │   │       ├── PspHealthResponse.java
    │   │   │       ├── RevenueResponse.java
    │   │   │       └── DeclineResponse.java
    │   │   ├── adapter/
    │   │   │   ├── in/
    │   │   │   │   ├── rest/
    │   │   │   │   │   └── AnalyticsController.java
    │   │   │   │   └── event/
    │   │   │   │       ├── PaymentEventAnalyticsConsumer.java
    │   │   │   │       ├── RoutingEventAnalyticsConsumer.java
    │   │   │   │       └── FraudEventAnalyticsConsumer.java
    │   │   │   └── out/
    │   │   │       ├── persistence/
    │   │   │       │   ├── AuthRateHourlyEntity.java
    │   │   │       │   ├── AuthRateDailyEntity.java
    │   │   │       │   ├── AuthRateMonthlyEntity.java
    │   │   │       │   ├── PspHealthSnapshotEntity.java
    │   │   │       │   ├── RevenueHourlyEntity.java
    │   │   │       │   ├── RevenueDailyEntity.java
    │   │   │       │   ├── DeclineDailyEntity.java
    │   │   │       │   ├── JpaAuthRateHourlyRepository.java
    │   │   │       │   ├── JpaAuthRateDailyRepository.java
    │   │   │       │   ├── JpaPspHealthSnapshotRepository.java
    │   │   │       │   ├── JpaRevenueHourlyRepository.java
    │   │   │       │   ├── JpaRevenueDailyRepository.java
    │   │   │       │   ├── JpaDeclineDailyRepository.java
    │   │   │       │   └── AnalyticsRepositoryAdapters.java
    │   │   │       ├── cache/
    │   │   │       │   └── ValkeyAnalyticsCache.java
    │   │   │       └── event/
    │   │   │           └── KafkaAnalyticsEventPublisher.java
    │   │   └── config/
    │   │       ├── AnalyticsFlywayConfig.java
    │   │       ├── AnalyticsProperties.java
    │   │       └── AnalyticsModuleConfig.java
    │   └── resources/
    │       └── db/migration/analytics/
    │           ├── V3017__create_auth_rate_rollups.sql
    │           ├── V3018__create_psp_health_snapshots.sql
    │           ├── V3019__create_revenue_rollups.sql
    │           ├── V3020__create_decline_rollups.sql
    │           └── V3021__create_analytics_materialized_views.sql
    └── test/
        └── java/io/nexuspay/analytics/
            ├── application/
            │   ├── service/
            │   │   ├── AuthRateAnalyticsServiceTest.java
            │   │   ├── PspHealthScoringServiceTest.java
            │   │   ├── RevenueAnalyticsServiceTest.java
            │   │   ├── DeclineAnalyticsServiceTest.java
            │   │   └── AnomalyDetectionServiceTest.java
            ├── adapter/
            │   ├── in/
            │   │   ├── rest/
            │   │   │   └── AnalyticsControllerTest.java
            │   │   └── event/
            │   │       └── PaymentEventAnalyticsConsumerTest.java
            │   └── out/
            │       └── persistence/
            │           └── AuthRateRollupRepositoryTest.java
            └── integration/
                ├── AnalyticsPipelineIntegrationTest.java
                └── AnalyticsQueryIntegrationTest.java
```

### Key Java Classes and Interfaces

**`io.nexuspay.analytics.application.service.PspHealthScoringService`**:

```java
@Service
public class PspHealthScoringService {
    /**
     * Computes a composite health score (0-100) per PSP:
     *   healthScore = (authRateWeight * normalizedAuthRate)
     *               + (latencyWeight * normalizedLatencyScore)
     *               + (errorRateWeight * normalizedErrorScore)
     *
     * Anomaly detection: if auth rate drops > 2 standard deviations from
     * the 7-day moving average, publish PspHealthDegraded alert event.
     */
    public PspHealthScore calculateHealthScore(String pspConnector, String tenantId);
    public List<PspHealthScore> getAllHealthScores(String tenantId);
    public boolean detectAnomaly(String pspConnector, String tenantId);
}
```

**`io.nexuspay.analytics.adapter.in.rest.AnalyticsController`**:

```java
@RestController
@RequestMapping("/v1/analytics")
public class AnalyticsController {
    @GetMapping("/auth-rates")     // ?from=...&to=...&groupBy=PSP,CARD_TYPE&psp=stripe
    public AuthRateResponse getAuthRates(AnalyticsQuery query);

    @GetMapping("/psp-health")     // ?psp=stripe,adyen
    public PspHealthResponse getPspHealth(AnalyticsQuery query);

    @GetMapping("/revenue")        // ?from=...&to=...&groupBy=CURRENCY&granularity=DAILY
    public RevenueResponse getRevenue(AnalyticsQuery query);

    @GetMapping("/declines")       // ?from=...&to=...&groupBy=DECLINE_REASON,PSP
    public DeclineResponse getDeclines(AnalyticsQuery query);
}
```

### Flyway Migration Schemas

**V3017__create_auth_rate_rollups.sql**:

```sql
-- Hourly rollup
CREATE TABLE analytics.auth_rate_hourly (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_hour       TIMESTAMPTZ NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    card_brand        VARCHAR(20),
    card_type         VARCHAR(10),                 -- CREDIT, DEBIT, PREPAID
    issuing_region    VARCHAR(2),                   -- ISO 3166-1 alpha-2
    currency          VARCHAR(3),
    payment_method    VARCHAR(30),
    total_attempts    INTEGER NOT NULL DEFAULT 0,
    total_approved    INTEGER NOT NULL DEFAULT 0,
    total_declined    INTEGER NOT NULL DEFAULT 0,
    total_errors      INTEGER NOT NULL DEFAULT 0,
    auth_rate         DECIMAL(8,6) NOT NULL DEFAULT 0,
    avg_latency_ms    INTEGER,
    p50_latency_ms    INTEGER,
    p95_latency_ms    INTEGER,
    p99_latency_ms    INTEGER,
    CONSTRAINT uk_auth_hourly UNIQUE (tenant_id, bucket_hour, psp_connector, card_brand, card_type, issuing_region, currency, payment_method)
);

CREATE INDEX idx_auth_hourly_tenant_time ON analytics.auth_rate_hourly (tenant_id, bucket_hour);
CREATE INDEX idx_auth_hourly_psp ON analytics.auth_rate_hourly (psp_connector, bucket_hour);

-- Daily rollup (materialized from hourly)
CREATE TABLE analytics.auth_rate_daily (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_date       DATE NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    card_brand        VARCHAR(20),
    card_type         VARCHAR(10),
    issuing_region    VARCHAR(2),
    currency          VARCHAR(3),
    payment_method    VARCHAR(30),
    total_attempts    INTEGER NOT NULL DEFAULT 0,
    total_approved    INTEGER NOT NULL DEFAULT 0,
    total_declined    INTEGER NOT NULL DEFAULT 0,
    total_errors      INTEGER NOT NULL DEFAULT 0,
    auth_rate         DECIMAL(8,6) NOT NULL DEFAULT 0,
    avg_latency_ms    INTEGER,
    p95_latency_ms    INTEGER,
    CONSTRAINT uk_auth_daily UNIQUE (tenant_id, bucket_date, psp_connector, card_brand, card_type, issuing_region, currency, payment_method)
);

-- Monthly rollup (materialized from daily)
CREATE TABLE analytics.auth_rate_monthly (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_month      DATE NOT NULL,               -- first day of month
    psp_connector     VARCHAR(50) NOT NULL,
    card_brand        VARCHAR(20),
    card_type         VARCHAR(10),
    issuing_region    VARCHAR(2),
    currency          VARCHAR(3),
    payment_method    VARCHAR(30),
    total_attempts    INTEGER NOT NULL DEFAULT 0,
    total_approved    INTEGER NOT NULL DEFAULT 0,
    total_declined    INTEGER NOT NULL DEFAULT 0,
    total_errors      INTEGER NOT NULL DEFAULT 0,
    auth_rate         DECIMAL(8,6) NOT NULL DEFAULT 0,
    CONSTRAINT uk_auth_monthly UNIQUE (tenant_id, bucket_month, psp_connector, card_brand, card_type, issuing_region, currency, payment_method)
);

ALTER TABLE analytics.auth_rate_hourly ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.auth_rate_hourly
    USING (tenant_id = current_setting('app.current_tenant_id'));

ALTER TABLE analytics.auth_rate_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.auth_rate_daily
    USING (tenant_id = current_setting('app.current_tenant_id'));

ALTER TABLE analytics.auth_rate_monthly ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.auth_rate_monthly
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3018__create_psp_health_snapshots.sql**:

```sql
CREATE TABLE analytics.psp_health_snapshots (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    snapshot_time     TIMESTAMPTZ NOT NULL,
    health_score      INTEGER NOT NULL,             -- 0-100 composite
    auth_rate_score   INTEGER NOT NULL,             -- 0-100 component
    latency_score     INTEGER NOT NULL,             -- 0-100 component
    error_rate_score  INTEGER NOT NULL,             -- 0-100 component
    auth_rate_7d      DECIMAL(8,6),                 -- 7-day rolling auth rate
    avg_latency_ms    INTEGER,
    p95_latency_ms    INTEGER,
    error_rate        DECIMAL(8,6),
    anomaly_detected  BOOLEAN NOT NULL DEFAULT false,
    anomaly_details   JSONB,
    CONSTRAINT uk_psp_health UNIQUE (tenant_id, psp_connector, snapshot_time)
);

CREATE INDEX idx_psp_health_time ON analytics.psp_health_snapshots (tenant_id, psp_connector, snapshot_time DESC);

ALTER TABLE analytics.psp_health_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.psp_health_snapshots
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3019__create_revenue_rollups.sql**:

```sql
CREATE TABLE analytics.revenue_hourly (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_hour       TIMESTAMPTZ NOT NULL,
    psp_connector     VARCHAR(50),
    currency          VARCHAR(3) NOT NULL,
    payment_method    VARCHAR(30),
    total_volume      DECIMAL(18,2) NOT NULL DEFAULT 0,
    total_count       INTEGER NOT NULL DEFAULT 0,
    total_fees        DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_revenue       DECIMAL(18,4) NOT NULL DEFAULT 0,
    refund_volume     DECIMAL(18,2) NOT NULL DEFAULT 0,
    refund_count      INTEGER NOT NULL DEFAULT 0,
    chargeback_volume DECIMAL(18,2) NOT NULL DEFAULT 0,
    chargeback_count  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_revenue_hourly UNIQUE (tenant_id, bucket_hour, psp_connector, currency, payment_method)
);

CREATE TABLE analytics.revenue_daily (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_date       DATE NOT NULL,
    psp_connector     VARCHAR(50),
    currency          VARCHAR(3) NOT NULL,
    payment_method    VARCHAR(30),
    total_volume      DECIMAL(18,2) NOT NULL DEFAULT 0,
    total_count       INTEGER NOT NULL DEFAULT 0,
    total_fees        DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_revenue       DECIMAL(18,4) NOT NULL DEFAULT 0,
    refund_volume     DECIMAL(18,2) NOT NULL DEFAULT 0,
    refund_count      INTEGER NOT NULL DEFAULT 0,
    chargeback_volume DECIMAL(18,2) NOT NULL DEFAULT 0,
    chargeback_count  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_revenue_daily UNIQUE (tenant_id, bucket_date, psp_connector, currency, payment_method)
);

ALTER TABLE analytics.revenue_hourly ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.revenue_hourly
    USING (tenant_id = current_setting('app.current_tenant_id'));

ALTER TABLE analytics.revenue_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.revenue_daily
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3020__create_decline_rollups.sql**:

```sql
CREATE TABLE analytics.decline_daily (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_date       DATE NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    decline_code      VARCHAR(50) NOT NULL,
    decline_category  VARCHAR(20) NOT NULL,         -- SOFT, HARD, ERROR
    card_brand        VARCHAR(20),
    issuing_region    VARCHAR(2),
    issuer_name       VARCHAR(100),
    total_count       INTEGER NOT NULL DEFAULT 0,
    total_volume      DECIMAL(18,2) NOT NULL DEFAULT 0,
    CONSTRAINT uk_decline_daily UNIQUE (tenant_id, bucket_date, psp_connector, decline_code, card_brand, issuing_region, issuer_name)
);

CREATE INDEX idx_decline_daily_tenant ON analytics.decline_daily (tenant_id, bucket_date);
CREATE INDEX idx_decline_daily_code ON analytics.decline_daily (decline_code, bucket_date);

ALTER TABLE analytics.decline_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.decline_daily
    USING (tenant_id = current_setting('app.current_tenant_id'));
```

**V3021__create_analytics_materialized_views.sql**:

```sql
-- Materialized view for daily rollup from hourly data
-- Refreshed by scheduled job every hour
CREATE MATERIALIZED VIEW analytics.mv_auth_rate_daily_refresh AS
SELECT
    tenant_id,
    DATE(bucket_hour) AS bucket_date,
    psp_connector,
    card_brand,
    card_type,
    issuing_region,
    currency,
    payment_method,
    SUM(total_attempts) AS total_attempts,
    SUM(total_approved) AS total_approved,
    SUM(total_declined) AS total_declined,
    SUM(total_errors) AS total_errors,
    CASE WHEN SUM(total_attempts) > 0
         THEN SUM(total_approved)::DECIMAL / SUM(total_attempts)
         ELSE 0 END AS auth_rate,
    AVG(avg_latency_ms)::INTEGER AS avg_latency_ms,
    MAX(p95_latency_ms) AS p95_latency_ms
FROM analytics.auth_rate_hourly
GROUP BY tenant_id, DATE(bucket_hour), psp_connector, card_brand, card_type,
         issuing_region, currency, payment_method;

CREATE UNIQUE INDEX ON analytics.mv_auth_rate_daily_refresh
    (tenant_id, bucket_date, psp_connector, card_brand, card_type, issuing_region, currency, payment_method);

-- PSP health trend view (last 30 days)
CREATE MATERIALIZED VIEW analytics.mv_psp_health_trend AS
SELECT
    tenant_id,
    psp_connector,
    DATE(snapshot_time) AS snapshot_date,
    AVG(health_score)::INTEGER AS avg_health_score,
    MIN(health_score) AS min_health_score,
    MAX(health_score) AS max_health_score,
    BOOL_OR(anomaly_detected) AS had_anomaly
FROM analytics.psp_health_snapshots
WHERE snapshot_time > now() - INTERVAL '30 days'
GROUP BY tenant_id, psp_connector, DATE(snapshot_time);

CREATE UNIQUE INDEX ON analytics.mv_psp_health_trend (tenant_id, psp_connector, snapshot_date);
```

### Configuration Additions

```yaml
# application-analytics.yml
nexuspay:
  analytics:
    enabled: true
    pipeline:
      consumer-group: nexuspay-analytics
      batch-size: 100
      batch-timeout-ms: 2000
    rollup:
      hourly-retention-days: 90          # keep hourly data for 90 days
      daily-retention-days: 730          # keep daily data for 2 years
      monthly-retention-days: 3650       # keep monthly data for 10 years
      daily-rollup-cron: "0 5 * * *"    # roll up hourly → daily at 00:05
      monthly-rollup-cron: "0 10 1 * *" # roll up daily → monthly on 1st at 00:10
      materialized-view-refresh-cron: "0 */1 * * *"  # refresh MVs every hour
    psp-health:
      snapshot-interval: PT5M            # take PSP health snapshot every 5 minutes
      anomaly-std-dev-threshold: 2.0     # 2 standard deviations = anomaly
      scoring-weights:
        auth-rate: 0.50
        latency: 0.30
        error-rate: 0.20
    cache:
      ttl: PT5M                           # cache analytics query results for 5 minutes
    query:
      max-date-range-days: 365           # maximum query range
      default-granularity: DAILY
```

### Kafka Topic Additions

| Topic | Partitions | Retention | Key | Value |
|-------|-----------|-----------|-----|-------|
| `nexuspay.analytics.psp-health` | 6 | 30 days | `tenantId:pspConnector` | PspHealthDegraded / AnomalyDetected |
| Analytics consumers read from existing payment, routing, and fraud topics | | | | |

### Test Strategy

| Layer | Scope | Tool | Count (est.) |
|-------|-------|------|-------------|
| Unit | AuthRateAnalyticsService (rollup calculations, groupBy) | JUnit 5 + AssertJ | ~15 tests |
| Unit | PspHealthScoringService (composite score, anomaly detection) | JUnit 5 + Mockito | ~12 tests |
| Unit | RevenueAnalyticsService (volume, fees, net calculations) | JUnit 5 | ~10 tests |
| Unit | DeclineAnalyticsService (soft vs hard, reason code mapping) | JUnit 5 | ~8 tests |
| Unit | AnomalyDetectionService (std dev calculation, threshold) | JUnit 5 | ~6 tests |
| Integration | Kafka consumer → rollup table insertion | Testcontainers (Kafka + PostgreSQL) | ~10 tests |
| Integration | Rollup job: hourly → daily, daily → monthly | Testcontainers (PostgreSQL) | ~6 tests |
| Integration | Analytics API endpoints with query parameters | Spring Boot Test + Testcontainers | ~12 tests |
| Integration | Materialized view refresh and query | Testcontainers (PostgreSQL) | ~4 tests |
| Integration | Valkey analytics cache | Testcontainers (Valkey) | ~4 tests |
| E2E | 1000 payment events → query auth rates by PSP | Spring Boot Test + Testcontainers | ~3 tests |

### Acceptance Criteria

- [ ] Payment events consumed from Kafka and aggregated into hourly rollup tables.
- [ ] Daily rollup job produces correct aggregates from hourly data.
- [ ] Monthly rollup job produces correct aggregates from daily data.
- [ ] `GET /v1/analytics/auth-rates?groupBy=PSP&from=...&to=...` returns correct auth rate per PSP.
- [ ] `GET /v1/analytics/auth-rates?groupBy=CARD_TYPE,REGION` returns multi-dimensional breakdown.
- [ ] `GET /v1/analytics/psp-health` returns composite health score with component breakdown.
- [ ] PSP health anomaly detected when auth rate drops > 2 std deviations from 7-day average.
- [ ] PspHealthDegraded event published on anomaly detection.
- [ ] `GET /v1/analytics/revenue?granularity=DAILY` returns volume, fees, and net per day.
- [ ] `GET /v1/analytics/declines?groupBy=DECLINE_REASON` returns decline reason distribution.
- [ ] Soft decline vs hard decline correctly categorized.
- [ ] Analytics query latency < 500ms p95 (pre-aggregated tables).
- [ ] Valkey caches query results with 5-minute TTL.
- [ ] Hourly rollup data retained for 90 days; daily for 2 years.
- [ ] All analytics tables have RLS policies enforced.
- [ ] Materialized views auto-refresh every hour.
- [ ] Analytics data feeds back to SuccessRateStrategy in the routing engine (Sprint 3.3).

---

## Cross-Sprint Dependency Graph

```
Sprint 3.1 (Fraud) ──────────────────────┐
                                          │
Sprint 3.2 (FX) ─────────────────────────┤
                                          ├── Sprint 3.4 (Event Upgrade) ──→ Sprint 3.6 (Analytics)
Sprint 3.3 (Routing) ────────────────────┤                                        ↑
    ↑                                     │                                        │
    │   uses fraud decision               │                                        │
    ├── Sprint 3.1 (Fraud)               │                                        │
    │   uses currency capabilities        │                                        │
    └── Sprint 3.2 (FX)                  │                                        │
                                          │                                        │
Sprint 3.5 (SDK) ─── uses tokenization ──┘               auth rate data feeds ────┘
                      + payment session                   routing decisions
```

**Critical path**: Sprint 3.4 (Event Architecture Upgrade) must complete before Sprint 3.6 (Analytics) begins full development, since the analytics pipeline consumes Avro events. Sprint 3.6 can begin schema design and API contract work in parallel with Sprint 3.4 completion.

**Parallel work**: Sprints 3.1, 3.2, and 3.5 can proceed independently once Phase 2 prerequisites are met. Sprint 3.3 depends on 3.1 and 3.2 for full integration but can develop the routing engine framework independently.

---

## Gradle Module Additions Summary

| Module | Type | build.gradle.kts Dependencies |
|--------|------|-------------------------------|
| `fraud` | New Java module | `common`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `resilience4j-spring-boot3` |
| `analytics` | New Java module | `common`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-kafka`, `confluent-kafka-avro-serializer` |
| `checkout-sdk` | New TypeScript workspace (not Gradle) | N/A (npm workspace with Vite) |
| `common` | Extended | `avro` (new), `confluent-kafka-avro-serializer` (new) |
| `payment-orchestration` | Extended | No new external dependencies (FX and routing are internal) |
| `ledger` | Extended | No new external dependencies (FX entries are internal) |
| `gateway-api` | Extended | No new external dependencies (SDK APIs are internal) |
| `iam` | Extended | No new external dependencies (session token issuer is internal) |
| `app` | Extended | `confluent-kafka-avro-serializer`, `schema-registry-client` |

---

## Flyway Migration Summary

| Migration | Module | Sprint | Description |
|-----------|--------|--------|-------------|
| V3001 | fraud | 3.1 | fraud_rules table |
| V3002 | fraud | 3.1 | fraud_assessments table |
| V3003 | fraud | 3.1 | device_fingerprints table |
| V3004 | fraud | 3.1 | fraud_events table |
| V3005 | payment | 3.2 | fx_rate_locks table |
| V3006 | payment | 3.2 | currency_capabilities table |
| V3007 | payment | 3.2 | merchant_currency_prefs table |
| V3008 | ledger | 3.2 | fx_gain_loss_accounts table |
| V3009 | payment | 3.3 | psp_fee_models table |
| V3010 | payment | 3.3 | routing_configs table |
| V3011 | payment | 3.3 | routing_decisions table |
| V3012 | common | 3.4 | event_store table |
| V3013 | common | 3.4 | event_snapshots table |
| V3014 | common | 3.4 | dead_letter_queue table |
| V3015 | gateway | 3.5 | payment_sessions table |
| V3016 | gateway | 3.5 | payment_tokens table |
| V3017 | analytics | 3.6 | auth_rate rollup tables (hourly, daily, monthly) |
| V3018 | analytics | 3.6 | psp_health_snapshots table |
| V3019 | analytics | 3.6 | revenue rollup tables (hourly, daily) |
| V3020 | analytics | 3.6 | decline_daily rollup table |
| V3021 | analytics | 3.6 | materialized views for analytics |

---

## Kafka Topic Summary

| Topic | Sprint | Partitions | Retention |
|-------|--------|-----------|-----------|
| `nexuspay.fraud.assessments` | 3.1 | 12 | 30 days |
| `nexuspay.fraud.events` | 3.1 | 12 | 90 days |
| `nexuspay.fraud.rules.changelog` | 3.1 | 6 | 7 days |
| `nexuspay.fx.rates` | 3.2 | 3 | 7 days |
| `nexuspay.fx.conversions` | 3.2 | 12 | 30 days |
| `nexuspay.fx.locks` | 3.2 | 6 | 7 days |
| `nexuspay.routing.decisions` | 3.3 | 12 | 30 days |
| `nexuspay.routing.cascades` | 3.3 | 12 | 30 days |
| `nexuspay.routing.failures` | 3.3 | 6 | 90 days |
| `nexuspay.routing.ab-test.results` | 3.3 | 6 | 90 days |
| `nexuspay.dlq` | 3.4 | 6 | unlimited |
| `nexuspay.analytics.psp-health` | 3.6 | 6 | 30 days |
