# Phase 2: Production Hardening

**Weeks 17-44 | Target: v0.2.0 | Theme: Enterprise-ready infrastructure**

Last updated: 2026-03-15

---

## Phase 2 Entry Criteria

- Phase 1 (v0.1.0) tagged and released
- All Sprint 1.7 acceptance criteria met
- CI green (unit + integration + Modulith verification)
- Docker Compose fully operational (8 containers healthy)
- Helm chart deploys to minikube
- Gatling baseline documented (>200 TPS)
- Known gaps documented and triaged

## Phase 2 Exit Criteria

- Multi-tenancy enforced via PostgreSQL RLS on all tables
- Debezium CDC operational, polling outbox decommissioned
- Temporal running at least one production workflow
- Reconciliation engine processing settlement files
- Dispute lifecycle tracked with evidence collection
- Subscription billing: create, renew, cancel, dunning
- Prometheus + Grafana dashboards live
- Performance: 500 TPS at p95 < 800ms
- All Phase 2 integration tests green (Testcontainers)
- Tag v0.2.0

## New Technology Additions

| Technology | Sprint | Purpose |
|-----------|--------|---------|
| HashiCorp Vault | 2.1 | Secrets management, dynamic DB credentials |
| cert-manager | 2.1 | TLS certificate automation (Kubernetes) |
| Debezium | 2.2 | CDC-based outbox relay replacing polling |
| Kafka Connect | 2.2 | Debezium connector runtime |
| Temporal Server | 2.2 | Durable workflow execution |
| Temporal UI | 2.2 | Workflow observability |
| Prometheus | 2.7 | Metrics collection and storage |
| Grafana | 2.7 | Dashboards and alerting |
| Alertmanager | 2.7 | Alert routing |

## Sprint Dependency Graph

```
Sprint 2.1 (Multi-Tenancy & Security)
    │
    ├── Sprint 2.2 (Event Infrastructure) ──── depends on 2.1 (RLS must be active)
    │       │
    │       ├── Sprint 2.3 (Reconciliation) ── depends on 2.2 (Temporal for workflows)
    │       │
    │       └── Sprint 2.4 (Disputes) ──────── depends on 2.2 (Temporal for lifecycle)
    │
    └── Sprint 2.5a (Billing Core) ─────────── depends on 2.1 (multi-tenancy)
            │                                   depends on 2.2 (Temporal for workflows)
            │
            └── Sprint 2.5b (Billing Advanced) ─ depends on 2.5a
                    │
Sprint 2.7 (Observability) ─────────────────── independent, overlaps with 2.5b
```

## Performance Target

**500 TPS at p95 < 800ms** (2.5x Phase 1 baseline)

Key optimizations:
- Debezium CDC eliminates 1-second polling latency
- Connection pool tuning (HikariCP: 20 → 40 connections)
- Kafka producer batching (linger.ms=5, batch.size=32KB)
- Read replicas for reconciliation queries (optional)

---

## Sprint 2.1 — Multi-Tenancy & Security (Weeks 17-20)

### Objectives
- Enforce tenant isolation at the database level via PostgreSQL RLS
- Centralize secrets management with HashiCorp Vault
- Enable TLS/mTLS between all services
- Establish database backup and PITR capability

### 2.1.1 PostgreSQL Row-Level Security

**Flyway migration `V2001__enable_rls.sql`:**

```sql
-- Helper function to get current tenant from session variable
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS VARCHAR AS $$
BEGIN
    RETURN current_setting('app.current_tenant_id', true);
END;
$$ LANGUAGE plpgsql STABLE;

-- Apply RLS to every tenant-scoped table
-- Pattern repeated for each table:

ALTER TABLE ledger_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_ledger_accounts ON ledger_accounts
    USING (tenant_id = current_tenant_id());
CREATE POLICY tenant_insert_ledger_accounts ON ledger_accounts
    FOR INSERT WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_journal_entries ON journal_entries
    USING (tenant_id = current_tenant_id());
CREATE POLICY tenant_insert_journal_entries ON journal_entries
    FOR INSERT WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE postings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_postings ON postings
    USING (tenant_id = current_tenant_id());

ALTER TABLE inbound_webhooks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_inbound_webhooks ON inbound_webhooks
    USING (tenant_id = current_tenant_id());

ALTER TABLE event_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_event_outbox ON event_outbox
    USING (tenant_id = current_tenant_id());

ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_api_keys ON api_keys
    USING (tenant_id = current_tenant_id());

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_audit_log ON audit_log
    USING (tenant_id = current_tenant_id());

ALTER TABLE webhook_endpoints ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_webhook_endpoints ON webhook_endpoints
    USING (tenant_id = current_tenant_id());

ALTER TABLE pending_approvals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_pending_approvals ON pending_approvals
    USING (tenant_id = current_tenant_id());

-- Superuser bypass for migrations and system jobs
ALTER TABLE ledger_accounts FORCE ROW LEVEL SECURITY;
-- (repeat FORCE for all tables)

-- Application role must not be superuser
CREATE ROLE nexuspay_app LOGIN PASSWORD 'nexuspay_local';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nexuspay_app;
```

**Spring TransactionCallback for tenant context:**

```java
// iam/adapter/in/filter/TenantContextFilter.java
@Component
@Order(2) // After correlation ID filter, before auth
public class TenantContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) {
        // Extract tenant_id from authenticated principal (JWT claim or API key lookup)
        String tenantId = extractTenantId(request);
        TenantContext.set(tenantId);
        MDC.put("tenant_id", tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenant_id");
        }
    }
}

// iam/config/TenantAwareTransactionManager.java
@Configuration
public class TenantAwareDataSourceConfig {
    @Bean
    public TransactionManager transactionManager(DataSource dataSource) {
        return new TenantAwareTransactionManager(dataSource);
    }
}

// Sets SET LOCAL app.current_tenant_id = ? at the start of every transaction
public class TenantAwareTransactionManager extends DataSourceTransactionManager {
    @Override
    protected void prepareTransactionConnection(Connection con, TransactionDefinition def) {
        String tenantId = TenantContext.get();
        if (tenantId != null) {
            try (var stmt = con.prepareStatement("SET LOCAL app.current_tenant_id = ?")) {
                stmt.setString(1, tenantId);
                stmt.execute();
            }
        }
    }
}
```

**Key classes:**
- `iam/domain/TenantContext.java` — ThreadLocal holder for current tenant ID
- `iam/adapter/in/filter/TenantContextFilter.java` — Servlet filter extracting tenant
- `iam/config/TenantAwareTransactionManager.java` — Injects `SET LOCAL` per transaction

### 2.1.2 HashiCorp Vault Integration

**Docker Compose addition:**
```yaml
vault:
  image: hashicorp/vault:1.17
  ports:
    - "8200:8200"
  environment:
    VAULT_DEV_ROOT_TOKEN_ID: nexuspay-dev-token
    VAULT_DEV_LISTEN_ADDRESS: "0.0.0.0:8200"
  cap_add:
    - IPC_LOCK
```

**application.yml additions:**
```yaml
spring.cloud.vault:
  uri: ${VAULT_URL:http://localhost:8200}
  token: ${VAULT_TOKEN:nexuspay-dev-token}
  kv:
    enabled: true
    backend: secret
    default-context: nexuspay
  database:
    enabled: true
    role: nexuspay-app
    backend: database
```

**Secrets stored in Vault:**
- `secret/nexuspay/database` — DB URL, username, password
- `secret/nexuspay/hyperswitch` — API key, webhook secret
- `secret/nexuspay/keycloak` — client ID, client secret
- `secret/nexuspay/kafka` — SASL credentials (production)
- Dynamic DB credentials via Vault's database secrets engine

### 2.1.3 TLS/mTLS

- Self-signed CA for local development (`docker/certs/`)
- `mkcert` script generates certs for all services
- Spring Boot TLS config:
  ```yaml
  server.ssl:
    enabled: true
    key-store: classpath:certs/nexuspay.p12
    key-store-type: PKCS12
  ```
- mTLS between NexusPay → HyperSwitch via RestClient TLS config
- Kubernetes: cert-manager with Let's Encrypt (production)

### 2.1.4 Database Backup Strategy

- `pg_basebackup` cron job (nightly)
- WAL archiving to S3/MinIO
- PITR configuration: `archive_mode=on`, `archive_command`
- Backup verification job: weekly restore to test DB, run checksums
- Docker Compose addition: MinIO container for local S3-compatible storage

### Test Strategy

| Layer | Tests |
|-------|-------|
| Unit | TenantContext ThreadLocal isolation, RLS policy SQL validation |
| Integration | Multi-tenant data isolation (Testcontainers): write as tenant A, verify invisible to tenant B |
| E2E | Full request flow with tenant extraction from JWT → RLS enforcement → correct data returned |

### Acceptance Criteria
- [ ] Tenant A cannot read/write tenant B's data at the database level
- [ ] `SET LOCAL` is executed for every transaction (verified via pg_stat_activity)
- [ ] Vault stores all secrets; no plaintext secrets in application.yml or Docker Compose
- [ ] TLS enabled on all HTTP endpoints
- [ ] mTLS between NexusPay and HyperSwitch
- [ ] Database backup completes and can be restored
- [ ] PITR recovers to arbitrary point in time

---

## Sprint 2.2 — Event Infrastructure Upgrade (Weeks 21-24)

### Objectives
- Replace polling outbox relay with Debezium CDC
- Introduce Temporal for durable workflow execution
- Establish event versioning strategy

### 2.2.1 Debezium CDC

**Docker Compose additions:**
```yaml
kafka-connect:
  image: debezium/connect:2.7
  ports:
    - "8083:8083"
  environment:
    BOOTSTRAP_SERVERS: kafka:9092
    GROUP_ID: nexuspay-connect
    CONFIG_STORAGE_TOPIC: nexuspay.connect.configs
    OFFSET_STORAGE_TOPIC: nexuspay.connect.offsets
    STATUS_STORAGE_TOPIC: nexuspay.connect.status
  depends_on:
    - kafka
    - nexuspay-pg
```

**Debezium connector configuration (registered via REST on startup):**
```json
{
  "name": "nexuspay-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "nexuspay-pg",
    "database.port": "5432",
    "database.user": "nexuspay",
    "database.password": "nexuspay_local",
    "database.dbname": "nexuspay",
    "topic.prefix": "nexuspay",
    "table.include.list": "public.event_outbox",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.fields.additional.placement": "aggregate_type:header,created_at:header",
    "transforms.outbox.route.topic.replacement": "nexuspay.${routedByValue}",
    "transforms.outbox.route.by.field": "aggregate_type"
  }
}
```

**Migration strategy:**
1. Deploy Debezium alongside existing polling relay
2. Both publish to same Kafka topics (consumers dedup by event_id)
3. Monitor parity for 1 week: compare event counts, latency
4. Disable polling relay (`@ConditionalOnProperty("nexuspay.outbox.polling.enabled")`)
5. Remove polling relay code after 2 weeks of clean CDC operation

**Flyway migration `V2002__outbox_debezium_columns.sql`:**
```sql
-- Add columns Debezium outbox router expects
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS routing_key VARCHAR(64);
-- Backfill routing_key from aggregate_type for existing rows
UPDATE event_outbox SET routing_key = LOWER(aggregate_type) WHERE routing_key IS NULL;
```

### 2.2.2 Temporal Workflow Engine

**Docker Compose additions:**
```yaml
temporal:
  image: temporalio/auto-setup:1.25
  ports:
    - "7233:7233"
  environment:
    DB: postgresql
    DB_PORT: 5432
    POSTGRES_USER: temporal
    POSTGRES_PWD: temporal_local
    POSTGRES_SEEDS: temporal-pg
    DYNAMIC_CONFIG_FILE_PATH: /etc/temporal/dynamicconfig.yaml
  depends_on:
    - temporal-pg

temporal-pg:
  image: postgres:16-alpine
  environment:
    POSTGRES_USER: temporal
    POSTGRES_PASSWORD: temporal_local
  ports:
    - "5434:5432"

temporal-ui:
  image: temporalio/ui:2.31
  ports:
    - "8280:8080"
  environment:
    TEMPORAL_ADDRESS: temporal:7233
```

**First workflow — PaymentWithRetryWorkflow:**

```java
// workflow/application/PaymentWithRetryWorkflow.java
@WorkflowInterface
public interface PaymentWithRetryWorkflow {
    @WorkflowMethod
    PaymentResult processPayment(PaymentRequest request);

    @SignalMethod
    void cancelPayment();

    @QueryMethod
    PaymentStatus getStatus();
}

// workflow/application/PaymentWithRetryWorkflowImpl.java
public class PaymentWithRetryWorkflowImpl implements PaymentWithRetryWorkflow {
    private final PaymentActivities activities = Workflow.newActivityStub(
        PaymentActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build());

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        // 1. Create payment in HyperSwitch
        var payment = activities.createPayment(request);
        // 2. Wait for webhook confirmation (signal or timeout)
        Workflow.await(Duration.ofMinutes(5), () -> confirmed || cancelled);
        // 3. On confirmation → write outbox event
        if (confirmed) {
            activities.publishPaymentEvent(payment);
        }
        return result;
    }
}
```

**Activity definitions:**
```java
// workflow/application/PaymentActivities.java
@ActivityInterface
public interface PaymentActivities {
    PaymentResponse createPayment(PaymentRequest request);
    void publishPaymentEvent(PaymentResponse payment);
    RefundResponse createRefund(RefundRequest request);
}
```

**Configuration:**
```yaml
nexuspay.temporal:
  target: ${TEMPORAL_ADDRESS:localhost:7233}
  namespace: nexuspay
  task-queue: nexuspay-main
  worker-threads: 4
```

### 2.2.3 Event Versioning Strategy

- All events carry `version` field in envelope (integer, starts at 1)
- **Backward compatible by default**: new fields are optional, removed fields keep defaults
- **Upcaster registry**: transforms v1 event → v2 event transparently for consumers
- Breaking changes: new event type (e.g., `PaymentCapturedV2`) + deprecation period for old type

```java
// common/event/EventUpcaster.java
public interface EventUpcaster<T> {
    int fromVersion();
    int toVersion();
    T upcast(JsonNode oldPayload);
}

// common/event/EventUpcasterChain.java
public class EventUpcasterChain {
    // Chains upcasters: v1 → v2 → v3 transparently
    public JsonNode upcast(String eventType, int fromVersion, JsonNode payload) { ... }
}
```

### Test Strategy

| Layer | Tests |
|-------|-------|
| Unit | Temporal workflow unit tests (TestWorkflowEnvironment), upcaster chain |
| Integration | Debezium CDC end-to-end (Testcontainers: PG + Kafka Connect + Kafka), Temporal integration |
| E2E | Write to outbox → CDC captures → Kafka topic receives → consumer processes |

### Acceptance Criteria
- [ ] Debezium captures outbox inserts within 100ms (vs 1000ms polling)
- [ ] Zero duplicate events during CDC migration (dedup by event_id)
- [ ] Polling relay disabled via config property, CDC is sole publisher
- [ ] Temporal workflow executes payment-with-retry successfully
- [ ] Temporal UI accessible at port 8280
- [ ] Event version field present on all published events

---

## Sprint 2.3 — Reconciliation Engine (Weeks 25-28)

### Objectives
- Activate reconciliation stub module with full hexagonal structure
- Build settlement file ingestion from multiple sources
- Implement 3-way matching engine
- Create exception management workflow

### 2.3.1 Module Structure

```
reconciliation/src/main/java/io/nexuspay/reconciliation/
├── domain/
│   ├── SettlementRecord.java         # Parsed settlement line item
│   ├── MatchResult.java              # MATCHED, PARTIAL, UNMATCHED, EXCEPTION
│   ├── ReconciliationRun.java        # Aggregate: one reconciliation batch
│   ├── ReconciliationException.java  # Unresolved discrepancy
│   └── MatchStrategy.java            # Enum: EXACT, FUZZY, DATE_RANGE
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── IngestSettlementUseCase.java
│   │   │   ├── RunReconciliationUseCase.java
│   │   │   └── ResolveExceptionUseCase.java
│   │   └── out/
│   │       ├── SettlementFilePort.java       # SFTP/S3 adapter interface
│   │       ├── SettlementParserPort.java     # Provider-specific parser
│   │       ├── ReconciliationRepository.java
│   │       └── PaymentQueryPort.java         # Query payments for matching
│   └── service/
│       ├── SettlementIngestionService.java
│       ├── ThreeWayMatchingService.java
│       └── ExceptionManagementService.java
├── adapter/
│   ├── in/
│   │   ├── rest/ReconciliationController.java
│   │   └── scheduler/ReconciliationScheduler.java
│   └── out/
│       ├── persistence/
│       │   ├── JpaReconciliationRunRepository.java
│       │   ├── JpaSettlementRecordRepository.java
│       │   └── JpaReconciliationExceptionRepository.java
│       ├── file/
│       │   ├── SftpSettlementFileAdapter.java
│       │   └── S3SettlementFileAdapter.java
│       └── parser/
│           ├── StripeCsvParser.java
│           ├── AdyenSettlementParser.java
│           └── HyperSwitchJsonParser.java
└── config/
    ├── ReconciliationConfig.java
    └── ReconciliationFlywayConfig.java
```

### 2.3.2 Flyway Migrations

**`V2003__create_reconciliation_schema.sql`:**
```sql
CREATE TABLE reconciliation_runs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    file_name VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING, RUNNING, COMPLETED, FAILED
    total_records INTEGER DEFAULT 0,
    matched_count INTEGER DEFAULT 0,
    unmatched_count INTEGER DEFAULT 0,
    exception_count INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE settlement_records (
    id VARCHAR(64) PRIMARY KEY,
    reconciliation_run_id VARCHAR(64) NOT NULL REFERENCES reconciliation_runs(id),
    tenant_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    payment_reference VARCHAR(64),
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    fee_amount BIGINT DEFAULT 0,
    net_amount BIGINT NOT NULL,
    settled_at TIMESTAMP NOT NULL,
    match_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING, MATCHED, UNMATCHED, EXCEPTION
    matched_payment_id VARCHAR(64),
    matched_journal_entry_id VARCHAR(64),
    raw_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE reconciliation_exceptions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    reconciliation_run_id VARCHAR(64) NOT NULL REFERENCES reconciliation_runs(id),
    settlement_record_id VARCHAR(64) REFERENCES settlement_records(id),
    exception_type VARCHAR(32) NOT NULL,  -- AMOUNT_MISMATCH, MISSING_PAYMENT, MISSING_SETTLEMENT, FEE_DISCREPANCY
    expected_amount BIGINT,
    actual_amount BIGINT,
    description TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN, INVESTIGATING, RESOLVED, WRITTEN_OFF
    assigned_to VARCHAR(128),
    resolved_at TIMESTAMP,
    resolution_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_settlement_records_run ON settlement_records(reconciliation_run_id);
CREATE INDEX idx_settlement_records_match ON settlement_records(match_status);
CREATE INDEX idx_recon_exceptions_status ON reconciliation_exceptions(status);

-- RLS
ALTER TABLE reconciliation_runs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_recon_runs ON reconciliation_runs USING (tenant_id = current_tenant_id());
ALTER TABLE settlement_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_settlement ON settlement_records USING (tenant_id = current_tenant_id());
ALTER TABLE reconciliation_exceptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_recon_exceptions ON reconciliation_exceptions USING (tenant_id = current_tenant_id());
```

### 2.3.3 Three-Way Matching Engine

Matches across three data sources:
1. **Payment records** (NexusPay `payments` view or HyperSwitch query)
2. **Ledger entries** (NexusPay `journal_entries` + `postings`)
3. **Settlement records** (ingested from PSP settlement files)

**Matching strategies:**
- **Exact**: amount, currency, and reference ID must match exactly
- **Fuzzy**: amount within configurable tolerance (e.g., +/- $0.01 for rounding), date within range
- **Date range**: settlement date within N days of payment date (PSPs settle on different schedules)

```java
// reconciliation/application/service/ThreeWayMatchingService.java
public class ThreeWayMatchingService {
    public List<MatchResult> reconcile(ReconciliationRun run, List<SettlementRecord> settlements) {
        for (SettlementRecord settlement : settlements) {
            // Step 1: Find matching payment by external reference
            Optional<PaymentRecord> payment = paymentQueryPort.findByExternalRef(
                settlement.getExternalId(), settlement.getProvider());

            // Step 2: Find matching ledger entry
            Optional<JournalEntry> ledgerEntry = payment.flatMap(p ->
                ledgerQueryPort.findByPaymentReference(p.getId()));

            // Step 3: Validate amounts across all three
            if (payment.isPresent() && ledgerEntry.isPresent()) {
                if (amountsMatch(settlement, payment.get(), ledgerEntry.get())) {
                    return MatchResult.matched(settlement, payment.get(), ledgerEntry.get());
                } else {
                    return MatchResult.exception(AMOUNT_MISMATCH, settlement, payment.get());
                }
            }
            // ... handle partial matches, missing records
        }
    }
}
```

### 2.3.4 API Endpoints

- `POST /v1/reconciliation/runs` — trigger reconciliation (manual or file upload)
- `GET /v1/reconciliation/runs` — list runs (paginated)
- `GET /v1/reconciliation/runs/{id}` — run details with summary stats
- `GET /v1/reconciliation/runs/{id}/records` — settlement records for a run
- `GET /v1/reconciliation/exceptions` — list open exceptions
- `POST /v1/reconciliation/exceptions/{id}/resolve` — resolve an exception
- `POST /v1/reconciliation/exceptions/{id}/assign` — assign to user

### Test Strategy

| Layer | Tests |
|-------|-------|
| Unit | Parser tests (CSV/JSON fixtures), matching logic, tolerance calculations |
| Integration | Testcontainers: ingest file → match against seeded payments → verify results |
| E2E | Temporal workflow: scheduled reconciliation → file fetch → match → exceptions created |

### Acceptance Criteria
- [ ] CSV and JSON settlement files parsed correctly (Stripe, Adyen, HyperSwitch formats)
- [ ] 3-way matching produces correct MATCHED/UNMATCHED/EXCEPTION results
- [ ] Amount tolerance and date range matching work as configured
- [ ] Exceptions tracked with assignment and resolution workflow
- [ ] Reconciliation runs are tenant-isolated (RLS)
- [ ] API endpoints return paginated results with filters

---

## Sprint 2.4 — Dispute Management (Weeks 29-32)

### Objectives
- Activate dispute stub module with lifecycle state machine
- Build evidence collection framework
- Define network integration interfaces (Verifi, Ethoca)
- Create chargeback ledger entries

### 2.4.1 Module Structure

```
dispute/src/main/java/io/nexuspay/dispute/
├── domain/
│   ├── Dispute.java                  # Aggregate root with state machine
│   ├── DisputeState.java             # Enum: OPENED, EVIDENCE_NEEDED, SUBMITTED, WON, LOST, EXPIRED
│   ├── DisputeEvidence.java          # Evidence item (type, file ref, metadata)
│   ├── DisputeEvidenceType.java      # Enum: SHIPPING_RECEIPT, CUSTOMER_COMMS, IP_LOG, etc.
│   ├── DisputeReason.java            # Reason code mapping per card network
│   └── DisputeEvent.java             # Domain events
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── CreateDisputeUseCase.java
│   │   │   ├── SubmitEvidenceUseCase.java
│   │   │   └── ResolveDisputeUseCase.java
│   │   └── out/
│   │       ├── DisputeRepository.java
│   │       ├── DisputeNetworkPort.java   # Verifi/Ethoca interface
│   │       └── EvidenceStoragePort.java  # File storage for evidence
│   └── service/
│       ├── DisputeLifecycleService.java
│       └── AutoRepresentmentService.java  # Rule-based auto-submission
├── adapter/
│   ├── in/
│   │   ├── rest/DisputeController.java
│   │   └── webhook/DisputeWebhookHandler.java  # Receives dispute notifications from PSPs
│   └── out/
│       ├── persistence/
│       │   ├── JpaDisputeRepository.java
│       │   └── DisputeEntity.java
│       ├── network/
│       │   ├── VerifiRdrAdapter.java     # Stub — interface defined, impl Phase 3
│       │   └── EthocaAdapter.java        # Stub — interface defined, impl Phase 3
│       └── storage/
│           └── S3EvidenceStorageAdapter.java
└── config/
    ├── DisputeConfig.java
    └── DisputeFlywayConfig.java
```

### 2.4.2 Flyway Migrations

**`V2004__create_dispute_schema.sql`:**
```sql
CREATE TABLE disputes (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    external_dispute_id VARCHAR(128),
    reason_code VARCHAR(32) NOT NULL,
    reason_description TEXT,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPENED',
    network VARCHAR(16),              -- VISA, MASTERCARD, AMEX
    evidence_due_date TIMESTAMP,
    evidence_submitted_at TIMESTAMP,
    resolved_at TIMESTAMP,
    outcome VARCHAR(16),              -- WON, LOST
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE dispute_evidence (
    id VARCHAR(64) PRIMARY KEY,
    dispute_id VARCHAR(64) NOT NULL REFERENCES disputes(id),
    tenant_id VARCHAR(64) NOT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    file_key VARCHAR(256),            -- S3/MinIO key
    file_name VARCHAR(256),
    file_size BIGINT,
    description TEXT,
    uploaded_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE dispute_events (
    id VARCHAR(64) PRIMARY KEY,
    dispute_id VARCHAR(64) NOT NULL REFERENCES disputes(id),
    tenant_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    old_status VARCHAR(32),
    new_status VARCHAR(32),
    actor VARCHAR(128),
    details JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_disputes_payment ON disputes(payment_id);
CREATE INDEX idx_disputes_status ON disputes(status);
CREATE INDEX idx_disputes_due_date ON disputes(evidence_due_date) WHERE status = 'EVIDENCE_NEEDED';

-- RLS
ALTER TABLE disputes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_disputes ON disputes USING (tenant_id = current_tenant_id());
ALTER TABLE dispute_evidence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_evidence ON dispute_evidence USING (tenant_id = current_tenant_id());
ALTER TABLE dispute_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_dispute_events ON dispute_events USING (tenant_id = current_tenant_id());
```

### 2.4.3 Dispute State Machine

```
OPENED → EVIDENCE_NEEDED → EVIDENCE_SUBMITTED → WON
                                               → LOST
                                               → EXPIRED (deadline passed)
```

- SLA timers configurable per card network (Visa: 20 days, MC: 45 days, Amex: 20 days)
- Temporal workflow manages deadline tracking and reminder notifications
- Auto-transition to EXPIRED if evidence_due_date passes without submission

### 2.4.4 Chargeback Ledger Entries

When a dispute is opened:
```
DR la_chargeback_reserve_{currency}   +{amount}
CR la_merchant_recv_{currency}        -{amount}
```

When dispute is won (reversed):
```
DR la_merchant_recv_{currency}        +{amount}
CR la_chargeback_reserve_{currency}   -{amount}
```

When dispute is lost (finalized):
```
DR la_chargeback_expense_{currency}   +{amount}
CR la_chargeback_reserve_{currency}   -{amount}
```

### 2.4.5 API Endpoints

- `GET /v1/disputes` — list disputes (paginated, filterable by status/payment)
- `GET /v1/disputes/{id}` — dispute details
- `POST /v1/disputes/{id}/evidence` — upload evidence file
- `POST /v1/disputes/{id}/submit` — submit evidence to network
- `GET /v1/disputes/{id}/events` — timeline of dispute events

### Acceptance Criteria
- [ ] Dispute created from PSP webhook transitions through correct states
- [ ] Evidence upload stores files and tracks metadata
- [ ] Deadline tracking creates EXPIRED transition when overdue
- [ ] Chargeback ledger entries are balanced (zero-sum)
- [ ] Auto-representment rules evaluate and auto-submit when confident
- [ ] All data tenant-isolated via RLS

---

## Sprint 2.5a — Subscription Billing Core (Weeks 33-36)

### Objectives
- Create new `billing` module with subscription lifecycle state machine
- Product catalog with flexible pricing models
- Invoice generation and payment collection
- Basic renewal and cancellation flows

### 2.5.1 Module Structure

```
billing/src/main/java/io/nexuspay/billing/
├── domain/
│   ├── Subscription.java            # Aggregate root with state machine
│   ├── SubscriptionState.java       # TRIALING, ACTIVE, PAST_DUE, PAUSED, CANCELED, EXPIRED
│   ├── Product.java                 # What is being sold
│   ├── Price.java                   # How much (flat, tiered, volume, per-unit)
│   ├── PricingModel.java            # Enum of pricing strategies
│   ├── Invoice.java                 # Generated billing document
│   ├── InvoiceLineItem.java         # Individual charge/credit on invoice
│   ├── DunningAttempt.java          # Record of retry attempt
│   ├── Trial.java                   # Trial period configuration
│   └── BillingEvent.java            # Domain events
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── CreateSubscriptionUseCase.java
│   │   │   ├── CancelSubscriptionUseCase.java
│   │   │   ├── ChangeSubscriptionUseCase.java   # Upgrade/downgrade
│   │   │   ├── CreateProductUseCase.java
│   │   │   └── CreatePriceUseCase.java
│   │   └── out/
│   │       ├── SubscriptionRepository.java
│   │       ├── ProductRepository.java
│   │       ├── InvoiceRepository.java
│   │       └── PaymentPort.java              # Delegates to payment-orchestration
│   └── service/
│       ├── SubscriptionLifecycleService.java
│       ├── InvoiceGenerationService.java
│       ├── DunningService.java
│       ├── ProrationService.java
│       └── TrialManagementService.java
├── adapter/
│   ├── in/
│   │   ├── rest/
│   │   │   ├── SubscriptionController.java
│   │   │   ├── ProductController.java
│   │   │   └── InvoiceController.java
│   │   └── scheduler/
│   │       ├── RenewalScheduler.java          # Daily: find subscriptions due for renewal
│   │       └── TrialExpirationScheduler.java  # Daily: convert expired trials
│   └── out/
│       ├── persistence/
│       │   ├── JpaSubscriptionRepository.java
│       │   ├── JpaProductRepository.java
│       │   ├── JpaInvoiceRepository.java
│       │   └── entities/
│       │       ├── SubscriptionEntity.java
│       │       ├── ProductEntity.java
│       │       ├── PriceEntity.java
│       │       ├── InvoiceEntity.java
│       │       └── InvoiceLineItemEntity.java
│       └── payment/
│           └── PaymentOrchestrationAdapter.java  # Calls payment-orchestration module
└── config/
    ├── BillingConfig.java
    └── BillingFlywayConfig.java
```

### 2.5.2 Flyway Migrations

**`V2005__create_billing_schema.sql`:**
```sql
CREATE TABLE products (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE prices (
    id VARCHAR(64) PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL REFERENCES products(id),
    tenant_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    pricing_model VARCHAR(16) NOT NULL,  -- FLAT, PER_UNIT, TIERED, VOLUME, PACKAGE
    unit_amount BIGINT,                  -- For FLAT/PER_UNIT (minor units)
    tiers JSONB,                         -- For TIERED/VOLUME: [{up_to, unit_amount, flat_amount}]
    billing_interval VARCHAR(16) NOT NULL,  -- MONTH, YEAR, WEEK, DAY
    billing_interval_count INTEGER NOT NULL DEFAULT 1,
    trial_days INTEGER DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE subscriptions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    price_id VARCHAR(64) NOT NULL REFERENCES prices(id),
    status VARCHAR(16) NOT NULL DEFAULT 'TRIALING',
    quantity INTEGER NOT NULL DEFAULT 1,
    current_period_start TIMESTAMP NOT NULL,
    current_period_end TIMESTAMP NOT NULL,
    trial_start TIMESTAMP,
    trial_end TIMESTAMP,
    canceled_at TIMESTAMP,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    payment_method_id VARCHAR(64),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE invoices (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    subscription_id VARCHAR(64) REFERENCES subscriptions(id),
    customer_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT, OPEN, PAID, VOID, UNCOLLECTIBLE
    currency VARCHAR(3) NOT NULL,
    subtotal BIGINT NOT NULL DEFAULT 0,
    tax BIGINT NOT NULL DEFAULT 0,
    total BIGINT NOT NULL DEFAULT 0,
    amount_paid BIGINT NOT NULL DEFAULT 0,
    amount_due BIGINT NOT NULL DEFAULT 0,
    payment_id VARCHAR(64),
    due_date TIMESTAMP,
    paid_at TIMESTAMP,
    period_start TIMESTAMP,
    period_end TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE invoice_line_items (
    id VARCHAR(64) PRIMARY KEY,
    invoice_id VARCHAR(64) NOT NULL REFERENCES invoices(id),
    tenant_id VARCHAR(64) NOT NULL,
    description VARCHAR(512) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    quantity INTEGER DEFAULT 1,
    proration BOOLEAN NOT NULL DEFAULT FALSE,
    period_start TIMESTAMP,
    period_end TIMESTAMP
);

CREATE TABLE dunning_attempts (
    id VARCHAR(64) PRIMARY KEY,
    subscription_id VARCHAR(64) NOT NULL REFERENCES subscriptions(id),
    invoice_id VARCHAR(64) NOT NULL REFERENCES invoices(id),
    tenant_id VARCHAR(64) NOT NULL,
    attempt_number INTEGER NOT NULL,
    payment_id VARCHAR(64),
    status VARCHAR(16) NOT NULL,  -- PENDING, SUCCESS, FAILED
    scheduled_at TIMESTAMP NOT NULL,
    attempted_at TIMESTAMP,
    failure_reason VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscriptions_status ON subscriptions(status);
CREATE INDEX idx_subscriptions_period_end ON subscriptions(current_period_end);
CREATE INDEX idx_invoices_status ON invoices(status);
CREATE INDEX idx_dunning_scheduled ON dunning_attempts(scheduled_at) WHERE status = 'PENDING';

-- RLS on all tables
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_products ON products USING (tenant_id = current_tenant_id());
ALTER TABLE prices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_prices ON prices USING (tenant_id = current_tenant_id());
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_subscriptions ON subscriptions USING (tenant_id = current_tenant_id());
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_invoices ON invoices USING (tenant_id = current_tenant_id());
ALTER TABLE invoice_line_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_line_items ON invoice_line_items USING (tenant_id = current_tenant_id());
ALTER TABLE dunning_attempts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_dunning ON dunning_attempts USING (tenant_id = current_tenant_id());
```

### 2.5.3 Subscription Lifecycle

```
                    ┌──────────────┐
                    │   TRIALING   │
                    └──────┬───────┘
                           │ trial_end reached (or immediate if no trial)
                    ┌──────▼───────┐
              ┌─────│    ACTIVE    │◄─────── payment succeeds (from PAST_DUE)
              │     └──────┬───────┘
              │            │ payment fails
              │     ┌──────▼───────┐
              │     │   PAST_DUE   │──────── dunning exhausted ──► CANCELED
              │     └──────────────┘
              │
              │ user requests cancel
              │     ┌──────────────┐
              └────►│   CANCELED   │
                    └──────────────┘

              PAUSED ◄──► ACTIVE (admin action)
```

### 2.5.4 Dunning Configuration

```yaml
nexuspay.billing.dunning:
  retry-schedule: [1, 3, 5, 7]  # Days after first failure
  grace-period-days: 3           # Days after last retry before cancel
  smart-retry:
    enabled: true
    optimal-hour: 10             # Retry at 10 AM in customer's timezone
    card-type-delay:              # Additional delays by card type
      debit: 1
      prepaid: 0
```

### 2.5.5 Proration Engine

Mid-cycle plan changes calculate prorated amounts:

```java
public class ProrationService {
    public ProrationResult calculate(Subscription current, Price newPrice, Instant changeDate) {
        long daysInPeriod = between(current.getPeriodStart(), current.getPeriodEnd()).toDays();
        long daysRemaining = between(changeDate, current.getPeriodEnd()).toDays();

        // Credit for unused time on old plan
        long unusedCredit = (current.getAmount() * daysRemaining) / daysInPeriod;

        // Charge for remaining time on new plan
        long newCharge = (newPrice.getAmount() * daysRemaining) / daysInPeriod;

        return new ProrationResult(unusedCredit, newCharge, newCharge - unusedCredit);
    }
}
```

### 2.5.6 API Endpoints

Products & Prices:
- `POST /v1/products` — create product
- `GET /v1/products` — list products
- `POST /v1/prices` — create price for product
- `GET /v1/prices` — list prices

Subscriptions:
- `POST /v1/subscriptions` — create subscription
- `GET /v1/subscriptions/{id}` — retrieve
- `POST /v1/subscriptions/{id}/cancel` — cancel (immediate or at period end)
- `POST /v1/subscriptions/{id}/pause` — pause
- `POST /v1/subscriptions/{id}/resume` — resume
- `POST /v1/subscriptions/{id}/change` — upgrade/downgrade

Invoices:
- `GET /v1/invoices` — list invoices
- `GET /v1/invoices/{id}` — retrieve
- `POST /v1/invoices/{id}/pay` — attempt payment on open invoice

### 2.5.7 Kafka Events

Topic: `nexuspay.billing`

Event types:
- `SubscriptionCreated`, `SubscriptionActivated`, `SubscriptionCanceled`, `SubscriptionPaused`, `SubscriptionResumed`, `SubscriptionExpired`
- `InvoiceCreated`, `InvoicePaid`, `InvoicePaymentFailed`, `InvoiceVoided`
- `DunningAttemptFailed`, `DunningExhausted`

### Acceptance Criteria (Sprint 2.5a)
- [ ] Create subscription → activates and begins billing cycle
- [ ] Renewal generates invoice, collects payment via payment-orchestration
- [ ] Cancel subscription (immediate or at period end) works correctly
- [ ] Invoice lifecycle (draft → open → paid/void) functional
- [ ] Product catalog with multiple pricing models (flat, per-unit, tiered, volume)
- [ ] All subscription, invoice, and product data tenant-isolated

---

## Sprint 2.5b — Subscription Billing Advanced (Weeks 37-40)

### Objectives
- Dunning / smart retry engine for failed payments
- Proration engine for mid-cycle plan changes
- Trial period management and automatic conversion
- Subscription pause/resume

### Acceptance Criteria (Sprint 2.5b)
- [ ] Create subscription with trial → auto-activates after trial period
- [ ] Failed payment triggers dunning sequence (configurable retry schedule)
- [ ] Dunning exhaustion cancels subscription
- [ ] Smart retry optimizes retry timing by card type and customer timezone
- [ ] Mid-cycle plan change prorates correctly (credit + charge)
- [ ] Subscription pause and resume preserves billing state

---

## Sprint 2.7 — Production Observability (Weeks 41-44)

> **Note**: Sprint 2.7 overlaps with Sprint 2.5b. Observability work is independent of billing and can proceed in parallel.

### Objectives
- Activate observability stub module
- Prometheus metrics export via Micrometer
- Grafana dashboards provisioned via Docker Compose
- Alerting rules for critical conditions
- SLO/SLI framework

### 2.7.1 Docker Compose Additions

```yaml
prometheus:
  image: prom/prometheus:v2.53
  ports:
    - "9090:9090"
  volumes:
    - ./config/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    - ./config/prometheus/alerts.yml:/etc/prometheus/alerts.yml

grafana:
  image: grafana/grafana:11.1
  ports:
    - "3000:3000"
  environment:
    GF_SECURITY_ADMIN_USER: admin
    GF_SECURITY_ADMIN_PASSWORD: admin
  volumes:
    - ./config/grafana/provisioning:/etc/grafana/provisioning
    - ./config/grafana/dashboards:/var/lib/grafana/dashboards

alertmanager:
  image: prom/alertmanager:v0.27
  ports:
    - "9093:9093"
  volumes:
    - ./config/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
```

### 2.7.2 Micrometer Metrics

**application.yml additions:**
```yaml
management:
  endpoints:
    web.exposure.include: health,info,prometheus,metrics
  metrics:
    tags:
      application: nexuspay
    distribution:
      percentiles-histogram:
        http.server.requests: true
      sli:
        http.server.requests: [0.05, 0.1, 0.25, 0.5, 0.75, 0.95, 0.99]
  prometheus:
    metrics.export.enabled: true
```

**Custom metrics (observability module):**

```java
// observability/adapter/in/metrics/PaymentMetrics.java
@Component
public class PaymentMetrics {
    private final Counter paymentsCreated;
    private final Counter paymentsFailed;
    private final Timer paymentLatency;
    private final Gauge outboxLag;
    private final Gauge circuitBreakerState;

    public PaymentMetrics(MeterRegistry registry) {
        this.paymentsCreated = Counter.builder("nexuspay.payments.created")
            .description("Total payments created")
            .tag("module", "payment-orchestration")
            .register(registry);

        this.paymentsFailed = Counter.builder("nexuspay.payments.failed")
            .description("Total payments failed")
            .register(registry);

        this.paymentLatency = Timer.builder("nexuspay.payments.latency")
            .description("Payment processing latency")
            .publishPercentileHistogram()
            .register(registry);

        this.outboxLag = Gauge.builder("nexuspay.outbox.lag.seconds", this, PaymentMetrics::measureOutboxLag)
            .description("Seconds since oldest unpublished outbox event")
            .register(registry);
    }
}
```

**Business metrics:**
- `nexuspay.payments.created{psp, currency, status}` — counter
- `nexuspay.payments.latency{psp}` — histogram
- `nexuspay.ledger.entries.created` — counter
- `nexuspay.outbox.lag.seconds` — gauge
- `nexuspay.auth.rate{psp}` — gauge (calculated)
- `nexuspay.refund.rate` — gauge
- `nexuspay.subscriptions.active` — gauge
- `nexuspay.subscriptions.churned` — counter
- `nexuspay.disputes.opened` — counter
- `nexuspay.reconciliation.match.rate` — gauge

### 2.7.3 Alerting Rules

**`config/prometheus/alerts.yml`:**
```yaml
groups:
  - name: nexuspay-critical
    rules:
      - alert: HighPaymentFailureRate
        expr: rate(nexuspay_payments_failed_total[5m]) / rate(nexuspay_payments_created_total[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Payment failure rate > 5% for 5 minutes"

      - alert: OutboxLagHigh
        expr: nexuspay_outbox_lag_seconds > 30
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Outbox lag exceeds 30 seconds"

      - alert: CircuitBreakerOpen
        expr: resilience4j_circuitbreaker_state{name="hyperswitch"} == 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "HyperSwitch circuit breaker is OPEN"

      - alert: DatabaseConnectionPoolSaturation
        expr: hikaricp_connections_active / hikaricp_connections_max > 0.85
        for: 3m
        labels:
          severity: warning

      - alert: KafkaConsumerLag
        expr: kafka_consumer_lag > 1000
        for: 5m
        labels:
          severity: warning

      - alert: HighLatency
        expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "p99 latency exceeds 2 seconds"
```

### 2.7.4 Grafana Dashboards

Provisioned via JSON files in `config/grafana/dashboards/`:

1. **Payment Operations Dashboard**
   - TPS (real-time), auth rate by PSP, failure rate, average latency
   - Payment status distribution (pie chart)
   - Top decline reasons
   - Revenue volume (24h rolling)

2. **Ledger Health Dashboard**
   - Journal entries per hour
   - Balance reconciliation status
   - Posting volume by account type

3. **Infrastructure Dashboard**
   - Database connections (active/idle/max)
   - Kafka consumer lag per topic
   - Outbox relay lag
   - Circuit breaker state timeline
   - JVM memory and GC
   - HTTP request rate and latency percentiles

4. **Subscription Dashboard**
   - Active subscriptions gauge
   - MRR trend
   - Churn rate
   - Dunning success rate
   - Trial conversion rate

### 2.7.5 SLO/SLI Framework

| SLI | Target SLO | Measurement |
|-----|-----------|-------------|
| Availability | 99.9% | `(successful_requests / total_requests) * 100` |
| Latency | p99 < 2000ms | `histogram_quantile(0.99, http_server_requests_seconds)` |
| Error rate | < 0.1% | `(5xx_responses / total_responses) * 100` |
| Payment success | > 95% | `(successful_payments / attempted_payments) * 100` |

Error budget: 0.1% = 43.2 minutes of downtime per 30 days

### 2.7.6 Health Check Enhancements

```java
// observability/adapter/in/health/KeycloakHealthIndicator.java
@Component
public class KeycloakHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // GET {keycloak-url}/realms/nexuspay/.well-known/openid-configuration
        // UP if 200, DOWN otherwise
    }
}

// observability/adapter/in/health/KafkaConsumerHealthIndicator.java
@Component
public class KafkaConsumerHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Check consumer group lag for all NexusPay consumer groups
        // WARNING if lag > 100, DOWN if consumer not registered
    }
}
```

### Acceptance Criteria
- [ ] Prometheus scrapes NexusPay metrics at `/actuator/prometheus`
- [ ] All custom business metrics exported (payments, ledger, outbox, auth rate)
- [ ] Grafana dashboards load with live data (4 dashboards)
- [ ] Alerting rules fire correctly (tested with simulated failures)
- [ ] SLO dashboard shows error budget remaining
- [ ] Keycloak and Kafka consumer health indicators active
- [ ] All health checks aggregate at `/actuator/health`

---

## Appendix: Gradle Module Changes

### New Modules in Phase 2

| Module | `build.gradle.kts` Dependencies |
|--------|-------------------------------|
| `billing` | `common`, `spring-boot-starter-data-jpa`, `spring-kafka` |
| `workflow` (activated) | `common`, `temporal-sdk`, `spring-boot-starter` |
| `reconciliation` (activated) | `common`, `ledger`, `spring-boot-starter-data-jpa`, `jsch` (SFTP) |
| `dispute` (activated) | `common`, `payment-orchestration`, `spring-boot-starter-data-jpa` |
| `observability` (activated) | `common`, `micrometer-registry-prometheus` |

### New Flyway Migrations Summary

| Migration | Sprint | Description |
|-----------|--------|-------------|
| V2001 | 2.1 | Enable RLS on all existing tables |
| V2002 | 2.2 | Debezium outbox routing column |
| V2003 | 2.3 | Reconciliation schema (runs, records, exceptions) |
| V2004 | 2.4 | Dispute schema (disputes, evidence, events) |
| V2005 | 2.5 | Billing schema (products, prices, subscriptions, invoices, dunning) |

### New Kafka Topics

| Topic | Sprint | Partition Key |
|-------|--------|--------------|
| `nexuspay.billing` | 2.5 | subscription_id |
| `nexuspay.disputes` | 2.4 | dispute_id |
| `nexuspay.reconciliation` | 2.3 | reconciliation_run_id |
| `nexuspay.connect.configs` | 2.2 | (Kafka Connect internal) |
| `nexuspay.connect.offsets` | 2.2 | (Kafka Connect internal) |
| `nexuspay.connect.status` | 2.2 | (Kafka Connect internal) |

### Docker Compose Additions Summary

| Container | Sprint | Port |
|-----------|--------|------|
| vault | 2.1 | 8200 |
| kafka-connect (Debezium) | 2.2 | 8083 |
| temporal | 2.2 | 7233 |
| temporal-pg | 2.2 | 5434 |
| temporal-ui | 2.2 | 8280 |
| prometheus | 2.6 | 9090 |
| grafana | 2.6 | 3000 |
| alertmanager | 2.6 | 9093 |

**Total containers after Phase 2: 16** (up from 8 in Phase 1)
