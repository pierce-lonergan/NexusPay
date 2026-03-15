# NexusPay Architecture Evolution Plan

Last updated: 2026-03-15

This document maps how NexusPay's architecture must evolve from its current Spring Modulith monolith into a platform capable of serving the full payment lifecycle. Each section identifies the current state, the target state, the migration path, and the key decisions.

---

## 1. Module Evolution Map

### Current State (Phase 1 — v0.1.0)

```
nexuspay/
├── common/                    [ACTIVE]  Shared domain: Money, PrefixedId, exceptions, events
├── gateway-api/               [ACTIVE]  REST controllers, rate limiting, idempotency, webhook delivery
├── payment-orchestration/     [ACTIVE]  HyperSwitch client, webhook receiver, outbox relay
├── ledger/                    [ACTIVE]  Double-entry ledger, balance reconciliation
├── iam/                       [ACTIVE]  API keys, JWT auth, RBAC, maker-checker, audit
├── reconciliation/            [STUB]    package-info.java only
├── observability/             [STUB]    package-info.java only
├── dispute/                   [STUB]    package-info.java only
├── workflow/                  [STUB]    package-info.java only
└── app/                       [ACTIVE]  Spring Boot main, Kafka config, unified config
```

**Active modules**: 5 (common, gateway-api, payment-orchestration, ledger, iam)
**Stub modules**: 4 (reconciliation, observability, dispute, workflow)
**Total Java files**: ~130
**Total Flyway migrations**: 7

### Target State (Phase 5 — v1.0.0)

```
nexuspay/
├── common/                    Shared domain, ISO 20022 types, multi-currency primitives
├── gateway-api/               REST API, GraphQL API, rate limiting, idempotency, versioning
├── payment-orchestration/     Multi-PSP routing, network tokenization, APM lifecycle
├── ledger/                    Double-entry ledger, multi-entity, sub-accounts, FX entries
├── iam/                       Multi-tenant IAM, RLS enforcement, SCIM provisioning
├── reconciliation/            Settlement ingestion, 3-way matching, exception management
├── observability/             Prometheus metrics, Grafana dashboards, PSP health scoring
├── dispute/                   Dispute lifecycle, Verifi/Ethoca, auto-representment
├── workflow/                  Temporal integration, visual workflow builder backend
├── billing/                   [NEW] Subscription engine, invoicing, dunning, metered billing
├── fraud/                     [NEW] Rules engine, device fingerprinting, risk scoring
├── vault/                     [NEW] Universal card vault, network tokenization
├── marketplace/               [NEW] Split payments, connected accounts, payouts
├── checkout-sdk/              [NEW] NexusPay.js, React components, PCI iframe
├── realtime-rails/            [NEW] FedNow, SEPA Instant, Open Banking PIS
├── embedded-finance/          [NEW] Treasury, issuing, lending orchestration
├── compliance/                [NEW] Tax provider abstraction, sanctions screening, KYC/KYB
├── pos/                       [NEW] SoftPOS SDK, terminal abstraction
├── app/                       Spring Boot main, unified config, Temporal workers
└── platform-admin/            [NEW] Multi-merchant portal, app marketplace
```

**New modules to build**: 10
**Estimated total Java files at v1.0.0**: ~1,500-2,000

---

## 2. Data Model Evolution

### Phase 1 → Phase 2: Multi-Tenancy

**Current**: `tenant_id` columns exist but unenforced.

**Target**: PostgreSQL Row-Level Security on every table.

```sql
-- Pattern applied to every table
ALTER TABLE ledger_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_accounts
    USING (tenant_id = current_setting('app.current_tenant_id'));

-- Set per-request via Spring TransactionCallback
SET LOCAL app.current_tenant_id = 'tenant_abc123';
```

**Migration strategy**:
1. Add RLS policies via Flyway (non-breaking — existing queries still work for superuser)
2. Add `SET LOCAL` in a Spring `TransactionSynchronization.beforeCommit()` callback
3. Test with multi-tenant Testcontainers setup
4. Flip enforcement on per-table with feature flags

### Phase 1 → Phase 3: Ledger Multi-Entity

**Current**: Single chart of accounts per currency.

**Target**: Per-entity, per-currency accounts for marketplace support.

```sql
ALTER TABLE ledger_accounts ADD COLUMN entity_id VARCHAR(64);
ALTER TABLE ledger_accounts ADD COLUMN parent_account_id VARCHAR(64);
-- Entity hierarchy: Platform → Merchant → Sub-merchant
CREATE INDEX idx_ledger_accounts_entity ON ledger_accounts(entity_id);
```

### Phase 1 → Phase 4: Universal Vault

**Current**: HyperSwitch handles tokenization. NexusPay never sees card data.

**Target**: NexusPay-owned vault with PSP-portable tokens.

**Architectural decision**: The vault MUST be a separate, isolated service (not a Spring Modulith module) to contain PCI DSS scope. Options:

| Option | PCI Scope | Complexity | Recommendation |
|--------|-----------|------------|----------------|
| Extend HyperSwitch Tartarus | Contained in HyperSwitch | Medium | **Preferred for Phase 4** |
| Standalone vault microservice | New CDE, requires QSA audit | Very High | Phase 5+ |
| Integrate with Basis Theory/VGS | Delegated to third party | Low | Interim option |

**Recommended**: Extend Tartarus first (HyperSwitch already PCI-certified), add NexusPay token namespace and PSP token mapping table. If Tartarus limitations block multi-PSP scenarios, build standalone vault in Phase 5.

---

## 3. Event Architecture Evolution

### Phase 1 (Current)

```
DB Write → event_outbox table → Polling Relay (1s) → Kafka → Consumers
```

- JSON events, no schema validation
- Single outbox table, leader-elected poller
- 3 topics: nexuspay.payments, nexuspay.ledger, DLTs

### Phase 2 Target

```
DB Write → event_outbox table → Debezium CDC → Kafka → Consumers
                                                    ↓
                                              Schema Registry (JSON Schema)
```

- Debezium PostgreSQL connector replaces polling relay
- Sub-100ms latency (vs. 1s polling)
- JSON Schema validation via Confluent Schema Registry
- Event versioning with compatibility checks

### Phase 3+ Target

```
DB Write → Debezium CDC → Kafka → Schema Registry (Avro)
                                       ↓
                              ┌─────────┴─────────┐
                              │  Event Store       │
                              │  (optional CQRS)   │
                              └────────────────────┘
```

- Avro schemas for compact serialization
- Event store for replay capability
- CQRS read models for analytics queries
- Cross-service event mesh

### Migration Path

| Phase | Mechanism | Schema | Latency | Topics |
|-------|-----------|--------|---------|--------|
| 1 (current) | Polling outbox | None | ~1s | 3 |
| 2 | Debezium CDC | JSON Schema | <100ms | 5+ |
| 3 | Debezium CDC | Avro | <100ms | 10+ |
| 4+ | Debezium + Event Store | Avro | <50ms | 15+ |

---

## 4. API Surface Evolution

### Phase 1 (Current): 12 endpoints

| Method | Path | Module |
|--------|------|--------|
| POST | `/v1/payments` | gateway-api |
| POST | `/v1/payments/{id}/confirm` | gateway-api |
| POST | `/v1/payments/{id}/capture` | gateway-api |
| POST | `/v1/payments/{id}/cancel` | gateway-api |
| POST | `/v1/payments/{id}/refunds` | gateway-api |
| GET | `/v1/payments/{id}` | gateway-api |
| GET | `/v1/refunds/{id}` | gateway-api |
| GET | `/v1/ledger/accounts` | gateway-api |
| GET | `/v1/ledger/journal-entries` | gateway-api |
| GET/POST | `/v1/approvals` | gateway-api |
| POST | `/v1/webhook-endpoints` | gateway-api |
| POST | `/v1/api-keys` | gateway-api |

### Phase 5 Target: ~150+ endpoints

New API domains:
- `/v1/subscriptions/*` — subscription lifecycle CRUD
- `/v1/invoices/*` — invoice generation, payment, void
- `/v1/products/*` — product catalog management
- `/v1/disputes/*` — dispute lifecycle, evidence upload
- `/v1/reconciliation/*` — settlement upload, match review
- `/v1/fraud/*` — risk assessment, rules management, review queue
- `/v1/connected-accounts/*` — marketplace sub-merchant management
- `/v1/transfers/*` — split payment transfers
- `/v1/payouts/*` — scheduled disbursements
- `/v1/payment-links/*` — hosted payment page links
- `/v1/checkout-sessions/*` — client-side checkout session management
- `/v1/tax/*` — tax calculation, reporting
- `/v1/treasury/*` — embedded financial accounts
- `/v1/issuing/*` — card issuance management
- `/v1/workflows/*` — workflow builder CRUD

### API Versioning Strategy

Phase 1 uses header-based versioning (`X-API-Version: 2026-03-01`). This scales as follows:

- **Additive changes** (new fields, new endpoints): no version bump needed
- **Breaking changes** (removed fields, changed behavior): new API version date
- **Per-merchant version pinning**: merchant's API key stores their pinned version
- **Sunset policy**: old versions supported for 12 months after deprecation notice

---

## 5. Infrastructure Evolution

### Phase 1 Docker Compose (Current)

```
nexuspay-pg (PostgreSQL 16)
kafka (KRaft, single broker)
valkey (Valkey 8)
keycloak (Keycloak 26)
hyperswitch-router
hyperswitch-consumer
hyperswitch-pg
hyperswitch-redis
```
**Total containers**: 8

### Phase 2 Docker Compose Target

```
+ kafka-connect (Debezium)
+ schema-registry
+ temporal
+ temporal-ui
+ temporal-postgresql
```
**Total containers**: 13

### Phase 3+ Docker Compose Target

```
+ prometheus
+ grafana
+ alertmanager
+ redis-insight (dev tooling)
```
**Total containers**: 17

### Production Kubernetes Target (Phase 2+)

```
Namespace: nexuspay
├── Deployments
│   ├── nexuspay-api (3 replicas, HPA 3-10)
│   ├── nexuspay-worker (2 replicas — Temporal workers)
│   ├── nexuspay-scheduler (1 replica — cron jobs, leader-elected)
│   └── kafka-connect (2 replicas)
├── StatefulSets
│   ├── postgresql (Bitnami, 1 primary + 1 replica)
│   ├── kafka (Bitnami, 3 brokers)
│   └── valkey (Bitnami, sentinel mode)
├── External Services
│   ├── keycloak (managed or self-hosted)
│   ├── hyperswitch-router (3 replicas)
│   ├── hyperswitch-consumer (2 replicas)
│   └── temporal (managed or self-hosted)
├── Ingress
│   └── nginx-ingress with cert-manager (Let's Encrypt)
├── Monitoring
│   ├── prometheus (ServiceMonitor CRDs)
│   ├── grafana (pre-provisioned dashboards)
│   └── alertmanager (PagerDuty/Slack)
└── Secrets
    └── HashiCorp Vault (CSI provider or agent injector)
```

---

## 6. Security Architecture Evolution

### Phase 1 (Current)

```
Client → [HTTP] → NexusPay → [HTTP] → HyperSwitch
                            → [TCP]  → PostgreSQL
                            → [TCP]  → Kafka
                            → [TCP]  → Valkey
```
- No TLS between components
- Secrets in environment variables
- Single Keycloak realm, no RLS

### Phase 2+ (Target)

```
Client → [HTTPS/TLS 1.3] → Ingress → [mTLS] → NexusPay
                                              → [TLS]  → PostgreSQL (sslmode=verify-full)
                                              → [TLS]  → Kafka (SASL_SSL)
                                              → [TLS]  → Valkey (TLS)
```
- TLS everywhere (zero plaintext)
- Vault-managed dynamic credentials
- RLS enforced per-tenant
- API key encryption at rest (AES-256-GCM)

### Phase 4+ (Universal Vault)

```
Client → [HTTPS] → NexusPay → [mTLS/PCI CDE] → Vault Service
                                                   ↕ [HSM]
```
- Vault service in isolated PCI Cardholder Data Environment
- Hardware Security Module for key management
- Tokenization at network edge before NexusPay processes

---

## 7. Deployment Topology Evolution

### Single-Region (Phase 1-3)

```
┌─────────────────────────────────────┐
│            Single Region             │
│  ┌──────────┐  ┌──────────────────┐ │
│  │ NexusPay │  │  PostgreSQL      │ │
│  │ (3 pods) │  │  (primary+replica)│ │
│  └──────────┘  └──────────────────┘ │
│  ┌──────────┐  ┌──────────────────┐ │
│  │  Kafka   │  │     Valkey       │ │
│  │(3 broker)│  │   (sentinel)     │ │
│  └──────────┘  └──────────────────┘ │
└─────────────────────────────────────┘
```

### Multi-Region (Phase 5 — Data Localization)

```
┌─────────────────┐     ┌─────────────────┐
│   US Region      │     │   EU Region      │
│  NexusPay (US)   │────→│  NexusPay (EU)   │
│  PostgreSQL (US) │     │  PostgreSQL (EU)  │
│  Kafka (US)      │     │  Kafka (EU)       │
└─────────────────┘     └─────────────────┘
                              │
                    ┌─────────────────┐
                    │  India Region    │
                    │  NexusPay (IN)   │
                    │  PostgreSQL (IN) │
                    │  (RBI compliant) │
                    └─────────────────┘
```

**Data routing**: Payment data routed to region based on:
- Merchant's registered country
- Card issuer country (for India RBI: all Indian card data stays in India)
- Customer's billing address country

**Cross-region**: Only anonymized analytics and aggregate routing intelligence shared.

---

## 8. Performance Targets by Phase

| Metric | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 |
|--------|---------|---------|---------|---------|---------|
| TPS (sustained) | 200 | 500 | 1,000 | 2,000 | 5,000 |
| p95 latency (create payment) | <1000ms | <500ms | <300ms | <200ms | <150ms |
| p95 latency (ledger entry) | <200ms | <100ms | <50ms | <50ms | <30ms |
| Event propagation latency | ~1s | <100ms | <100ms | <50ms | <50ms |
| Availability target | 99% | 99.9% | 99.95% | 99.99% | 99.99% |
| Recovery Time Objective | N/A | 1 hour | 30 min | 15 min | 5 min |
| Recovery Point Objective | N/A | 1 hour | 15 min | 5 min | 1 min |

---

## 9. Technology Additions by Phase

| Phase | New Technologies |
|-------|-----------------|
| 2 | Debezium, Confluent Schema Registry, Temporal, HashiCorp Vault, cert-manager |
| 3 | ONNX Runtime (ML models), Prometheus, Grafana, AlertManager, GeoIP database |
| 4 | React (workflow builder + checkout SDK), Redis Streams (real-time events), OpenAPI codegen |
| 5 | gRPC (real-time rails), WebSocket (POS), Chainalysis SDK (stablecoin compliance) |

---

## 10. Module Dependency Graph (Phase 5)

```
                              common
                            /   |    \
                          /     |      \
              gateway-api   billing   marketplace
              /  |  |  \      |          |
            /    |  |    \    |          |
  payment-orch  ledger  iam  fraud     vault
       |          |      |     |         |
       |          |      |     |         |
   workflow  reconciliation  compliance  pos
       |
   realtime-rails
       |
  embedded-finance
```

**Rules** (enforced by Spring Modulith verification):
- All modules depend on `common` (shared domain)
- `gateway-api` orchestrates cross-module calls
- Domain modules (ledger, iam, fraud) never depend on each other directly
- `workflow` depends on `payment-orchestration` for Temporal workflow definitions
- `vault` is architecturally isolated (separate PCI scope)

---

## 11. Breaking Change Register

Changes that require careful migration:

| Phase | Change | Migration Strategy |
|-------|--------|-------------------|
| 2 | RLS enforcement | Add policies incrementally, test per-table |
| 2 | Debezium replaces polling | Feature flag, run both in parallel, verify event parity |
| 3 | Avro replaces JSON events | Dual-write (JSON + Avro) for 2 weeks, then cut over. **Impact**: All consumers must update deserialization from JSON to Avro. Schema Registry becomes a critical-path dependency -- if it is unavailable, no events can be produced or consumed. Mitigation: deploy Schema Registry in HA mode (2+ replicas) before Avro cutover. Consumer migration order: internal consumers first (reconciliation, observability), then external webhook consumers (which remain JSON -- Avro is internal only). |
| 4 | Universal vault tokens | Gradual token migration per merchant, backward-compatible. **Impact**: HyperSwitch token mapping must be maintained for recurring payments. Existing `pm_` (Stripe) and equivalent PSP tokens linked to active subscriptions must be mapped to `tok_` NexusPay tokens in the vault migration table. Recurring payment retry with old tokens must continue to work during migration window. Migration is per-merchant, not big-bang. |
| 5 | ISO 20022 data model | Internal-only change, API unchanged. **Impact**: Structured address storage required (ISO 20022 mandates structured postal addresses rather than free-text). Existing address fields in `connected_accounts`, `vendor_payments`, and payout tables must be migrated from free-text to structured format (street_name, building_number, postal_code, town_name, country). Flyway migration adds new structured columns; background job parses existing free-text addresses. API accepts both formats during transition period. |

---

## 12. Decision Log

| # | Decision | Rationale | Phase |
|---|----------|-----------|-------|
| 1 | Spring Modulith over microservices | Deployment simplicity, refactor later if needed | 1 |
| 2 | Polling outbox over Debezium | Simpler Phase 1, CDC adds ops complexity | 1 |
| 3 | JSON over Avro events | Developer ergonomics, Schema Registry not needed yet | 1 |
| 4 | Extend Tartarus over standalone vault | Avoid PCI scope expansion, leverage HyperSwitch cert | 4 |
| 5 | ONNX Runtime over separate model server | Simplicity, no gRPC latency, JVM-embedded | 3 |
| 6 | Temporal over custom state machines | Battle-tested, visualization, retry semantics | 2 |
| 7 | React Flow for workflow builder | Open-source, extensible, large community | 4 |
| 8 | SoftPOS before hardware terminals | Lower barrier, no hardware partnerships needed | 5 |
