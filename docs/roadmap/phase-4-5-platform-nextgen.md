# Phase 4: Platform Expansion & Phase 5: Next-Gen

**Phase 4: Weeks 65-88 | v0.4.0 | Theme: Full payment platform**
**Phase 5: Weeks 89-120 | v1.0.0 | Theme: Market leadership**

Last updated: 2026-03-15

---

## Phase 4 Entry Criteria

- Phase 3 (v0.3.0) tagged and released
- Fraud rules engine operational with FRM provider integration
- Cross-border/FX routing live with rate locking
- Smart routing engine active (cost + success-rate strategies)
- Schema Registry + Avro events in production
- Client-side SDK (NexusPay.js) published to npm
- Analytics platform serving auth rate and PSP health data
- Performance: 1000 TPS at p95 < 600ms

## Phase 4 Exit Criteria

- Universal card vault operational with network tokenization (Visa/MC)
- Marketplace split payments processing live
- B2B payment flows (virtual cards, AP automation) functional
- Visual workflow builder backend API complete
- Mobile SDKs (iOS/Android) published
- Compliance toolkit (tax, sanctions, KYC) integrated
- Performance: 2500 TPS at p95 < 500ms
- Tag v0.4.0

---

# Phase 4: Platform Expansion (Weeks 65-88)

## Sprint 4.1 — Universal Card Vault & Network Tokenization (Weeks 65-68)

### Objectives
- Create isolated `vault` module for PAN storage
- Implement AES-256-GCM encryption at rest
- Network token provisioning (Visa VTS, Mastercard MDES)
- Vault-to-vault migration tooling

### Module Structure

```
vault/src/main/java/io/nexuspay/vault/
├── domain/
│   ├── VaultedCard.java              # Encrypted PAN with metadata
│   ├── NetworkToken.java             # Provisioned network token
│   ├── TokenState.java               # PROVISIONED, ACTIVE, SUSPENDED, DELETED
│   ├── VaultToken.java               # NexusPay token (tok_xxx) referencing vaulted card
│   ├── CryptogramRequest.java        # For e-commerce transactions
│   └── VaultMigration.java           # Tracks vault-to-vault imports
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── VaultCardUseCase.java
│   │   │   ├── ProvisionNetworkTokenUseCase.java
│   │   │   ├── GenerateCryptogramUseCase.java
│   │   │   └── MigrateVaultUseCase.java
│   │   └── out/
│   │       ├── VaultRepository.java
│   │       ├── EncryptionPort.java           # HSM or software encryption
│   │       ├── VisaTokenServicePort.java     # VTS API
│   │       ├── MastercardMdesPort.java       # MDES API
│   │       └── AmexTokenServicePort.java     # Amex token API
│   └── service/
│       ├── CardVaultService.java
│       ├── NetworkTokenService.java
│       └── VaultMigrationService.java
├── adapter/
│   ├── in/
│   │   └── rest/VaultController.java
│   └── out/
│       ├── persistence/
│       │   ├── JpaVaultRepository.java
│       │   └── VaultedCardEntity.java
│       ├── encryption/
│       │   ├── AesGcmEncryptionAdapter.java    # Software encryption (dev/test)
│       │   └── HsmEncryptionAdapter.java       # CloudHSM/Thales Luna (production)
│       └── network/
│           ├── VisaVtsAdapter.java
│           ├── MastercardMdesAdapter.java
│           └── AmexTokenAdapter.java
└── config/
    ├── VaultConfig.java
    ├── VaultFlywayConfig.java
    └── VaultSecurityConfig.java          # Separate security context for PCI isolation
```

### Flyway Migrations

**`V4001__create_vault_schema.sql`:**
```sql
CREATE TABLE vaulted_cards (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    encrypted_pan BYTEA NOT NULL,          -- AES-256-GCM encrypted
    pan_last4 VARCHAR(4) NOT NULL,
    pan_bin VARCHAR(8) NOT NULL,           -- First 6-8 digits for routing
    brand VARCHAR(16) NOT NULL,            -- VISA, MASTERCARD, AMEX, DISCOVER
    exp_month SMALLINT NOT NULL,
    exp_year SMALLINT NOT NULL,
    cardholder_name VARCHAR(256),
    encryption_key_id VARCHAR(64) NOT NULL, -- References key in HSM/Vault
    fingerprint VARCHAR(128) NOT NULL,      -- SHA-256 of PAN for dedup
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, fingerprint)          -- One vault entry per card per tenant
);

CREATE TABLE network_tokens (
    id VARCHAR(64) PRIMARY KEY,
    vaulted_card_id VARCHAR(64) NOT NULL REFERENCES vaulted_cards(id),
    tenant_id VARCHAR(64) NOT NULL,
    network VARCHAR(16) NOT NULL,           -- VISA_VTS, MC_MDES, AMEX
    token_reference VARCHAR(256) NOT NULL,  -- Network-assigned token reference
    token_last4 VARCHAR(4),
    status VARCHAR(16) NOT NULL DEFAULT 'PROVISIONED',
    token_expiry VARCHAR(4),               -- MMYY
    provisioned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP,
    suspended_at TIMESTAMP
);

CREATE TABLE vault_tokens (
    id VARCHAR(64) PRIMARY KEY,             -- tok_xxx
    vaulted_card_id VARCHAR(64) NOT NULL REFERENCES vaulted_cards(id),
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE vault_migrations (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    source_provider VARCHAR(32) NOT NULL,   -- SPREEDLY, STRIPE, BRAINTREE
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    total_cards INTEGER DEFAULT 0,
    migrated_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- RLS
ALTER TABLE vaulted_cards ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_vault ON vaulted_cards USING (tenant_id = current_tenant_id());
ALTER TABLE network_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_network_tokens ON network_tokens USING (tenant_id = current_tenant_id());
ALTER TABLE vault_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_vault_tokens ON vault_tokens USING (tenant_id = current_tenant_id());
ALTER TABLE vault_migrations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_vault_migrations ON vault_migrations USING (tenant_id = current_tenant_id());
```

### Key Design Decisions

> **External Dependency**: Visa VTS and Mastercard MDES enrollment is a 3-6 month business process. Applications must be submitted to each card network, requiring a registered business entity, PCI DSS compliance documentation, and technical integration review. This enrollment should be initiated at the start of Phase 4 (or earlier) to avoid blocking Sprint 4.1 delivery. Development can proceed against sandbox/stub APIs while enrollment is in progress.

- **PCI isolation**: Vault module can be deployed as a separate service for PCI DSS scope segmentation. In Phase 1-3, it runs in-process with the monolith. In production, it's extracted behind a dedicated network boundary.
- **Encryption key rotation**: New key ID per rotation. Old encrypted PANs re-encrypted in background job. Zero-downtime rotation.
- **Network token benefits**: 2-5% auth rate improvement (issuers trust network tokens more), reduced interchange on some networks, no card-on-file expiration issues.

### API Endpoints

- `POST /v1/vault/cards` — vault a card, returns `tok_xxx`
- `GET /v1/vault/cards/{token}` — card metadata (last4, brand, expiry — never full PAN)
- `DELETE /v1/vault/cards/{token}` — delete vaulted card
- `POST /v1/vault/cards/{token}/network-tokens` — provision network token
- `POST /v1/vault/migrations` — start vault-to-vault migration (from Spreedly/Stripe)

### Acceptance Criteria
- [ ] Card vaulted with AES-256-GCM, PAN never stored in plaintext
- [ ] Token (tok_xxx) returned, usable for payments without re-entering card
- [ ] Network token provisioned via VTS/MDES API (or stub in test)
- [ ] Cryptogram generated for tokenized e-commerce transaction
- [ ] Duplicate card detection via fingerprint
- [ ] Key rotation completes without downtime

---

## Sprint 4.2 — Marketplace & Platform Payments (Weeks 69-72)

### Objectives
- New `marketplace` module for multi-party payments
- Connected account onboarding and management
- Split payment execution with ledger entries per participant
- Payout scheduling and execution

> **Sub-Sprint Note**: KYC/KYB onboarding for connected accounts is a substantial workstream that warrants its own sub-sprint or dedicated focus period within Sprint 4.2. Connected account onboarding involves KYC provider integration (Onfido, Persona, Jumio), document collection workflows, identity verification state machines, beneficial ownership verification for businesses, and ongoing monitoring. If Sprint 4.2 scope proves too large, KYC onboarding should be extracted into a dedicated Sprint 4.2b.

### Module Structure

```
marketplace/src/main/java/io/nexuspay/marketplace/
├── domain/
│   ├── ConnectedAccount.java         # Sub-merchant account
│   ├── AccountState.java             # ONBOARDING, VERIFIED, ACTIVE, SUSPENDED, CLOSED
│   ├── SplitPayment.java             # Payment split definition
│   ├── SplitRule.java                # Percentage, fixed, remainder
│   ├── Payout.java                   # Scheduled payout to connected account
│   ├── PayoutSchedule.java           # DAILY, WEEKLY, MONTHLY, MANUAL
│   └── PlatformFee.java              # Fee charged by platform
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── OnboardAccountUseCase.java
│   │   │   ├── CreateSplitPaymentUseCase.java
│   │   │   ├── SchedulePayoutUseCase.java
│   │   │   └── ConfigureFeeUseCase.java
│   │   └── out/
│   │       ├── ConnectedAccountRepository.java
│   │       ├── SplitPaymentRepository.java
│   │       ├── PayoutRepository.java
│   │       ├── KycProviderPort.java          # KYC/KYB verification
│   │       └── PayoutExecutionPort.java      # Bank transfer / card push
│   └── service/
│       ├── AccountOnboardingService.java
│       ├── SplitPaymentService.java
│       ├── PayoutService.java
│       └── PlatformFeeService.java
├── adapter/
│   ├── in/
│   │   ├── rest/
│   │   │   ├── ConnectedAccountController.java
│   │   │   ├── SplitPaymentController.java
│   │   │   └── PayoutController.java
│   │   └── scheduler/PayoutScheduler.java
│   └── out/
│       └── persistence/
└── config/
```

### Flyway Migrations

**`V4002__create_marketplace_schema.sql`:**
```sql
CREATE TABLE connected_accounts (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    business_name VARCHAR(256) NOT NULL,
    email VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ONBOARDING',
    kyc_status VARCHAR(16) DEFAULT 'PENDING',
    country VARCHAR(2) NOT NULL,
    default_currency VARCHAR(3) NOT NULL,
    payout_schedule VARCHAR(16) NOT NULL DEFAULT 'DAILY',
    payout_minimum BIGINT DEFAULT 0,
    platform_fee_percent NUMERIC(5,2) DEFAULT 0,
    platform_fee_fixed BIGINT DEFAULT 0,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE split_payments (
    id VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE split_rules (
    id VARCHAR(64) PRIMARY KEY,
    split_payment_id VARCHAR(64) NOT NULL REFERENCES split_payments(id),
    connected_account_id VARCHAR(64) NOT NULL REFERENCES connected_accounts(id),
    split_type VARCHAR(16) NOT NULL,     -- PERCENTAGE, FIXED, REMAINDER
    amount BIGINT,                        -- For FIXED
    percentage NUMERIC(5,2),              -- For PERCENTAGE
    calculated_amount BIGINT,             -- Resolved after payment capture
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE payouts (
    id VARCHAR(64) PRIMARY KEY,
    connected_account_id VARCHAR(64) NOT NULL REFERENCES connected_accounts(id),
    tenant_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING, PROCESSING, PAID, FAILED
    method VARCHAR(16) NOT NULL,           -- BANK_TRANSFER, CARD_PUSH
    scheduled_at TIMESTAMP,
    paid_at TIMESTAMP,
    failure_reason VARCHAR(256),
    external_reference VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes and RLS (pattern same as other tables)
ALTER TABLE connected_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_accounts ON connected_accounts USING (tenant_id = current_tenant_id());
ALTER TABLE split_payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_splits ON split_payments USING (tenant_id = current_tenant_id());
ALTER TABLE split_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_split_rules ON split_rules USING (tenant_id = (SELECT tenant_id FROM split_payments WHERE id = split_payment_id));
ALTER TABLE payouts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_payouts ON payouts USING (tenant_id = current_tenant_id());
```

### Split Payment Ledger Entries

Payment of $100 with 80% to merchant, 15% platform fee, 5% to partner:

```
DR la_customer_liab_usd         -10000
CR la_merchant_recv_{merchant}   +8000   (connected account balance)
CR la_platform_revenue           +1500   (platform fee)
CR la_partner_recv_{partner}     +500    (partner share)
```

### API Endpoints

- `POST /v1/connected-accounts` — onboard sub-merchant
- `GET /v1/connected-accounts/{id}` — account details + KYC status
- `PUT /v1/connected-accounts/{id}` — update account
- `POST /v1/payments` (with `splits` param) — create split payment
- `GET /v1/payouts` — list payouts for account
- `POST /v1/payouts` — create manual payout

### Acceptance Criteria
- [ ] Connected account onboarding with KYC status tracking
- [ ] Split payment distributes funds to multiple accounts in ledger
- [ ] Platform fee deducted and recorded as revenue
- [ ] Payout scheduling (daily/weekly/monthly) executes correctly
- [ ] Payout holds and minimum thresholds enforced
- [ ] 1099-K reporting data collected (US accounts)

---

## Sprint 4.3 — B2B Payments (Weeks 73-76)

### Objectives
- Invoice-based payment flows (net-30/60/90)
- Virtual card issuance via provider integration
- AP automation with approval workflows
- Level 2/3 commercial card data enrichment

### Key Components

**Domain model:**
- `PurchaseOrder` — tracks PO lifecycle
- `VirtualCard` — single/multi-use with spend controls
- `VendorPayment` — batched payments to vendors
- `B2bInvoice` — buyer↔seller invoice with terms

**Flyway migration `V4003__create_b2b_schema.sql`:**
```sql
CREATE TABLE purchase_orders (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    buyer_id VARCHAR(64) NOT NULL,
    seller_id VARCHAR(64) NOT NULL,
    po_number VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    terms VARCHAR(16),              -- NET_30, NET_60, NET_90
    line_items JSONB NOT NULL,
    due_date TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE virtual_cards (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    issuing_provider VARCHAR(32) NOT NULL,   -- MARQETA, LITHIC, STRIPE_ISSUING
    external_card_id VARCHAR(128),
    card_last4 VARCHAR(4),
    card_type VARCHAR(16) NOT NULL,          -- SINGLE_USE, MULTI_USE
    amount_limit BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    merchant_category_codes TEXT[],          -- Allowed MCC codes
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    spent_amount BIGINT NOT NULL DEFAULT 0,
    purchase_order_id VARCHAR(64) REFERENCES purchase_orders(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE vendor_payments (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    vendor_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    method VARCHAR(16) NOT NULL,    -- ACH, WIRE, VIRTUAL_CARD, CHECK
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    batch_id VARCHAR(64),
    remittance_info TEXT,
    scheduled_at TIMESTAMP,
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- RLS on all tables (same pattern)
```

**Level 2/3 data enrichment:**
```java
// Enriches payment requests with commercial card data for lower interchange
public class Level23DataEnricher {
    public void enrich(PaymentRequest request, PurchaseOrder po) {
        request.setLevel2Data(Level2Data.builder()
            .customerReferenceNumber(po.getPoNumber())
            .taxAmount(po.getTaxAmount())
            .build());
        request.setLevel3Data(Level3Data.builder()
            .lineItems(po.getLineItems().stream()
                .map(li -> LineItem.builder()
                    .description(li.getDescription())
                    .quantity(li.getQuantity())
                    .unitCost(li.getUnitCost())
                    .commodityCode(li.getCommodityCode())
                    .build())
                .toList())
            .build());
    }
}
```

### API Endpoints

- `POST /v1/purchase-orders` — create PO
- `POST /v1/virtual-cards` — issue virtual card
- `POST /v1/vendor-payments` — create vendor payment
- `POST /v1/vendor-payments/batch` — batch vendor payments

### Acceptance Criteria
- [ ] PO → invoice → payment → 3-way match flow works end-to-end
- [ ] Virtual card issued with spend controls (amount, MCC, expiry)
- [ ] Virtual card authorization webhook updates spent_amount
- [ ] Vendor payment batching executes with approval workflow
- [ ] Level 2/3 data passed to PSP, lower interchange confirmed

---

## Sprint 4.4 — Visual Workflow Builder Backend (Weeks 77-80)

### Objectives
- Workflow definition storage and versioning
- Temporal-backed execution of visually defined workflows
- Built-in action library (route, fraud check, webhook, branch, wait)

### Architecture

Visual workflow definitions are stored as JSON DAGs, compiled to Temporal workflow definitions at activation time.

**Workflow definition model:**
```json
{
  "id": "wf_abc123",
  "name": "High-value payment flow",
  "version": 3,
  "trigger": "payment.created",
  "nodes": [
    {
      "id": "n1",
      "type": "condition",
      "config": { "field": "amount", "operator": "gt", "value": 50000 }
    },
    {
      "id": "n2",
      "type": "action",
      "action": "fraud_check",
      "config": { "provider": "sift", "threshold": 70 }
    },
    {
      "id": "n3",
      "type": "action",
      "action": "route_payment",
      "config": { "strategy": "cost_optimized" }
    },
    {
      "id": "n4",
      "type": "action",
      "action": "send_webhook",
      "config": { "event": "payment.requires_review" }
    }
  ],
  "edges": [
    { "from": "n1", "to": "n2", "condition": "true" },
    { "from": "n1", "to": "n3", "condition": "false" },
    { "from": "n2", "to": "n3", "condition": "risk_score < 70" },
    { "from": "n2", "to": "n4", "condition": "risk_score >= 70" }
  ]
}
```

**Built-in actions:**
- `route_payment` — invoke smart routing engine
- `fraud_check` — invoke fraud assessment
- `apply_discount` — modify payment amount
- `send_webhook` — deliver webhook to merchant
- `wait` — pause execution (timer or signal)
- `branch` — conditional branching
- `approval_required` — create pending approval
- `log` — write to audit log

### Flyway Migration

**`V4004__create_workflow_schema.sql`:**
```sql
CREATE TABLE workflow_definitions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    trigger_event VARCHAR(64) NOT NULL,
    definition JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT, ACTIVE, ARCHIVED
    activated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, name, version)
);

CREATE TABLE workflow_executions (
    id VARCHAR(64) PRIMARY KEY,
    workflow_definition_id VARCHAR(64) NOT NULL REFERENCES workflow_definitions(id),
    tenant_id VARCHAR(64) NOT NULL,
    trigger_event_id VARCHAR(64),
    temporal_workflow_id VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',  -- RUNNING, COMPLETED, FAILED, TIMED_OUT
    input JSONB,
    output JSONB,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);
```

### API Endpoints

- `POST /v1/workflows` — create workflow definition
- `GET /v1/workflows` — list workflows
- `GET /v1/workflows/{id}` — retrieve (includes all versions)
- `PUT /v1/workflows/{id}` — update (creates new version)
- `POST /v1/workflows/{id}/activate` — make active (only one active version per name)
- `POST /v1/workflows/{id}/archive` — archive
- `GET /v1/workflows/{id}/executions` — list executions

### Acceptance Criteria
- [ ] Workflow JSON definition validated on create/update
- [ ] Activation compiles to Temporal workflow and registers
- [ ] Triggered by matching event type in Kafka
- [ ] Conditional branching executes correct path
- [ ] Execution history queryable with input/output
- [ ] Version N+1 doesn't affect in-flight version N executions

---

## Sprint 4.5 — Mobile SDKs & POS (Weeks 81-84)

### Objectives
- iOS SDK (Swift, SPM) and Android SDK (Kotlin, Maven Central)
- PCI-compliant card handling in mobile
- SoftPOS / NFC tap-to-pay (Android)
- Terminal abstraction for cloud-connected terminals

### Deliverables

**iOS SDK (`nexuspay-ios`):**
- Swift Package Manager distribution
- `NexusPaySDK.configure(publishableKey: "pk_test_...")`
- `PaymentSheet` — drop-in payment UI
- `CardField` — embeddable card input (PCI scope: SAQ-A)
- Apple Pay integration
- Biometric authentication support
- Card scanning via Vision framework

**Android SDK (`nexuspay-android`):**
- Maven Central / GitHub Packages distribution
- Kotlin-first API, Java-compatible
- `PaymentSheet` — drop-in payment UI
- `CardInputWidget` — embeddable card input
- Google Pay integration
- NFC tap-to-pay for SoftPOS
- Card scanning via ML Kit

**Terminal abstraction:**
```java
// pos/application/port/out/TerminalPort.java
public interface TerminalPort {
    TerminalPaymentResult processPayment(TerminalPaymentRequest request);
    void cancelTransaction(String transactionId);
    TerminalStatus getStatus(String terminalId);
}

// Adapters for different terminal types
// pos/adapter/out/terminal/SoftPosAdapter.java      — NFC on Android
// pos/adapter/out/terminal/AdyenTerminalAdapter.java — Adyen cloud terminal
// pos/adapter/out/terminal/StripeTerminalAdapter.java — Stripe Terminal
```

### API Additions

- `POST /v1/terminal-payments` — initiate terminal payment
- `GET /v1/terminal-payments/{id}` — terminal payment status
- `POST /v1/terminals/{id}/cancel` — cancel in-flight terminal transaction
- `GET /v1/terminals` — list registered terminals

### Acceptance Criteria
- [ ] iOS SDK installable via SPM, card input renders
- [ ] Android SDK installable via Gradle, card input renders
- [ ] Card data tokenized in SDK, never reaches merchant app
- [ ] Apple Pay / Google Pay buttons functional
- [ ] SoftPOS NFC payment completes on Android test device
- [ ] Terminal payment creates matching ledger entry

---

## Sprint 4.6 — Compliance Toolkit (Weeks 85-88)

### Objectives
- Tax calculation provider abstraction
- Sanctions screening on payments and payouts
- KYC/KYB orchestration for connected accounts

### Module Structure

```
compliance/src/main/java/io/nexuspay/compliance/
├── domain/
│   ├── TaxCalculation.java
│   ├── SanctionsScreening.java
│   ├── ScreeningResult.java          # CLEAR, MATCH, POTENTIAL_MATCH
│   ├── KycVerification.java
│   └── VerificationLevel.java        # LOW, MEDIUM, HIGH
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── CalculateTaxUseCase.java
│   │   │   ├── ScreenTransactionUseCase.java
│   │   │   └── VerifyIdentityUseCase.java
│   │   └── out/
│   │       ├── TaxCalculationPort.java       # TaxJar, Avalara, Vertex
│   │       ├── SanctionsListPort.java        # OFAC, EU sanctions
│   │       └── KycProviderPort.java          # Onfido, Jumio, Persona
│   └── service/
│       ├── TaxService.java
│       ├── SanctionsScreeningService.java
│       └── KycOrchestrationService.java
├── adapter/
│   ├── out/
│   │   ├── tax/
│   │   │   ├── TaxJarAdapter.java
│   │   │   └── AvalaraAdapter.java
│   │   ├── sanctions/
│   │   │   ├── OfacSdnAdapter.java           # US Treasury OFAC SDN list
│   │   │   └── EuSanctionsAdapter.java
│   │   └── kyc/
│   │       ├── OnfidoAdapter.java
│   │       └── PersonaAdapter.java
│   └── in/
│       └── rest/ComplianceController.java
└── config/
```

### Flyway Migration

**`V4005__create_compliance_schema.sql`:**
```sql
CREATE TABLE sanctions_screenings (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    entity_type VARCHAR(16) NOT NULL,      -- PAYMENT, PAYOUT, ACCOUNT
    entity_id VARCHAR(64) NOT NULL,
    screened_name VARCHAR(256) NOT NULL,
    screened_country VARCHAR(2),
    result VARCHAR(16) NOT NULL,            -- CLEAR, MATCH, POTENTIAL_MATCH
    match_details JSONB,
    reviewed_by VARCHAR(128),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE kyc_verifications (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    connected_account_id VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    verification_level VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING, IN_REVIEW, APPROVED, REJECTED
    provider_reference VARCHAR(128),
    result JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE tax_calculations (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64),
    provider VARCHAR(32) NOT NULL,
    subtotal BIGINT NOT NULL,
    tax_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    jurisdiction JSONB,                     -- {country, state, city, rate}
    line_items JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- RLS on all tables
```

### Acceptance Criteria
- [ ] Tax calculation returns correct amount for US multi-jurisdiction
- [ ] EU VAT reverse charge handled for B2B cross-border
- [ ] OFAC SDN screening blocks payments to sanctioned entities
- [ ] Potential match creates review workflow
- [ ] KYC verification initiated for connected account onboarding
- [ ] Risk-based verification levels applied correctly

---

# Phase 5: Next-Gen & Market Leadership (Weeks 89-120)

> **Why 6-week sprints in Phase 5**: Phase 5 shifts from 4-week to 6-week sprints for three reasons:
> 1. **Increased complexity**: Real-time payment rails, ML model training, and embedded finance each involve integration with multiple external systems and longer feedback cycles.
> 2. **Integration testing overhead**: By Phase 5 the platform has 20+ modules. Cross-module integration testing, end-to-end regression, and performance validation require significantly more time per sprint.
> 3. **Regulatory requirements**: Features like FedNow integration, stablecoin settlement, and SOC 2 compliance involve regulatory review cycles, external auditor coordination, and documentation requirements that cannot be compressed into 4-week windows.

## Phase 5 Entry Criteria

- Phase 4 (v0.4.0) tagged
- Universal vault operational with at least Visa VTS
- Marketplace split payments processing
- Compliance toolkit integrated
- Mobile SDKs published
- Performance: 2500 TPS at p95 < 500ms

## Phase 5 Exit Criteria (v1.0.0 Release)

- Real-time rails (FedNow or SEPA Instant) processing
- ML-powered smart routing outperforming rule-based
- Embedded finance capabilities available
- Multi-merchant platform operational
- SOC 2 Type II evidence collection automated
- Chaos engineering resilience validated
- Performance: 5000 TPS at p95 < 400ms
- Full documentation suite
- Tag v1.0.0

---

## Sprint 5.1 — Real-Time Payment Rails (Weeks 89-92)

### Objectives
- New `realtime-rails` module
- FedNow instant credit transfer
- SEPA Instant (EU) integration
- Open Banking PIS (UK/EU)
- Unified API regardless of rail

### Key Interfaces

```java
// realtime-rails/application/port/out/RealTimePaymentPort.java
public interface RealTimePaymentPort {
    RtpResult initiatePayment(RtpRequest request);
    RtpStatus getStatus(String paymentId);
    boolean supportsRail(String currency, String country);
}

// Adapters:
// FedNowAdapter — ISO 20022 pacs.008 messages
// SepaInstantAdapter — SCT Inst via banking API
// OpenBankingPisAdapter — UK/EU Open Banking
```

**Unified API:**
- `POST /v1/realtime-payments` — single endpoint, rail auto-selected by currency + country
- `GET /v1/realtime-payments/{id}` — status (real-time confirmation)
- Fallback: if real-time rail unavailable, offer card payment as fallback

**ISO 20022 support:**
- `common` module extended with ISO 20022 message types
- XML generation/parsing for pacs.008 (credit transfer), pacs.002 (status), camt.053 (statement)

### Flyway Migration

**`V5001__create_realtime_schema.sql`:**
```sql
CREATE TABLE realtime_payments (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    rail VARCHAR(16) NOT NULL,        -- FEDNOW, SEPA_INSTANT, OPEN_BANKING
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    debtor_account VARCHAR(64),
    creditor_account VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'INITIATED',
    iso_message_id VARCHAR(128),
    confirmation_id VARCHAR(128),
    settled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### Acceptance Criteria
- [ ] FedNow payment initiated and confirmed within 10 seconds (sandbox)
- [ ] SEPA Instant payment initiated (or stub if no sandbox access)
- [ ] Open Banking PIS redirect flow completes
- [ ] Rail auto-selected based on currency and country
- [ ] Ledger entries created for real-time payments
- [ ] Fallback to card if rail unavailable

---

## Sprint 5.2 — AI/ML Capabilities (Weeks 93-96)

### Objectives
- ML model serving infrastructure
- ML-powered smart routing (predict auth probability per PSP)
- Anomaly detection for payments and PSP health
- Subscription churn prediction

### Architecture

```
ML Pipeline:
  Historical data (PostgreSQL) → Feature engineering (batch)
  Real-time events (Kafka) → Feature engineering (streaming)
  Features → Model training (offline, MLflow)
  Trained model → ONNX export → Embedded serving (in-process)
```

**Key components:**
- Feature store: Valkey for real-time features, PostgreSQL for batch features
- Model serving: ONNX Runtime embedded in JVM (no separate serving infra)
- Model registry: MLflow tracking server (Docker Compose addition)
- A/B testing: traffic split between ML routing and rule-based routing

**ML-powered routing:**
```java
// payment-orchestration/application/service/MlRoutingStrategy.java
public class MlRoutingStrategy implements RoutingStrategy {
    private final OnnxModelRunner modelRunner;
    private final FeatureStore featureStore;

    @Override
    public RoutingDecision route(PaymentContext ctx) {
        Features features = featureStore.getFeatures(ctx);
        // Features: card_brand, card_country, amount_bucket, hour_of_day,
        //           psp_auth_rate_7d, psp_latency_p95, merchant_category
        Map<String, Float> predictions = modelRunner.predict(features);
        // predictions: {stripe: 0.94, adyen: 0.91, checkout: 0.87}
        return selectHighestProbability(predictions);
    }
}
```

**Cold-start handling:** Rule-based routing until 10,000 transactions per PSP collected, then gradual ML traffic ramp (10% → 25% → 50% → 100%).

### Docker Compose Additions

```yaml
mlflow:
  image: ghcr.io/mlflow/mlflow:2.16
  ports:
    - "5050:5000"
  environment:
    MLFLOW_BACKEND_STORE_URI: postgresql://mlflow:mlflow@nexuspay-pg:5432/mlflow
    MLFLOW_DEFAULT_ARTIFACT_ROOT: /mlflow/artifacts
```

### Acceptance Criteria
- [ ] ONNX model loads and serves predictions in-process (< 5ms p99)
- [ ] ML routing outperforms rule-based on auth rate (A/B test)
- [ ] Cold-start gracefully falls back to rules
- [ ] Anomaly detection alerts on PSP degradation
- [ ] Churn prediction scores available per subscription
- [ ] Model retraining pipeline documented

---

## Sprint 5.3 — Embedded Finance (Weeks 97-100)

### Objectives
- Treasury / BaaS integration (virtual accounts, money movement)
- Card issuing orchestration
- Lending orchestration (merchant cash advance)

### Key Interfaces

```java
// embedded-finance/application/port/out/TreasuryPort.java
public interface TreasuryPort {
    VirtualAccount createAccount(CreateAccountRequest request);
    Transfer initiateTransfer(TransferRequest request);
    Balance getBalance(String accountId);
}

// embedded-finance/application/port/out/IssuingPort.java
public interface IssuingPort {
    IssuedCard createCard(CreateCardRequest request);
    void updateSpendingControls(String cardId, SpendingControls controls);
    void freezeCard(String cardId);
}
```

**Provider adapters:** Column, Treasury Prime, Unit (treasury); Marqeta, Lithic (issuing)

### Acceptance Criteria
- [ ] Virtual account created via treasury provider
- [ ] Money movement between virtual accounts
- [ ] Physical/virtual card issued with spending controls
- [ ] Real-time authorization webhook for issued cards
- [ ] Merchant cash advance offer generated based on payment history

---

## Sprint 5.4 — Stablecoin & Crypto Rails (Weeks 101-104)

### Objectives
- Stablecoin (USDC/USDT) as settlement currency
- On-ramp/off-ramp via partner integration (Circle, Paxos)
- Crypto payment acceptance via PSP connectors
- Blockchain transaction monitoring

### Key Design

- **Not a crypto exchange** — NexusPay orchestrates crypto payment acceptance and settlement via regulated partners
- Stablecoin settlement reduces FX costs for cross-border payments
- Instant conversion to fiat available (no volatility exposure)
- Ledger extended with cryptocurrency account types

### Flyway Migration

```sql
CREATE TABLE crypto_transactions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64),
    chain VARCHAR(16) NOT NULL,          -- ETHEREUM, SOLANA, POLYGON
    token VARCHAR(16) NOT NULL,          -- USDC, USDT, ETH, BTC
    amount NUMERIC(28,8) NOT NULL,       -- High precision for crypto
    tx_hash VARCHAR(128),
    confirmations INTEGER DEFAULT 0,
    required_confirmations INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### Acceptance Criteria
- [ ] USDC payment accepted via Circle integration (sandbox)
- [ ] On-ramp: fiat → USDC for cross-border settlement
- [ ] Off-ramp: USDC → fiat for merchant payout
- [ ] Blockchain confirmations tracked
- [ ] Ledger entries for crypto transactions balanced

---

## Sprint 5.5 — Multi-Merchant Platform (Weeks 105-112)

### Objectives
- New `platform-admin` module
- Self-service merchant onboarding
- Per-merchant configuration isolation
- App marketplace / extension architecture
- White-label customization

### Key Components

**Merchant self-service:**
- Registration flow → KYC verification → PSP configuration → Go live
- Per-merchant: routing rules, fraud rules, webhook endpoints, branding
- Usage-based billing (NexusPay charges platform operators per transaction)

**Extension architecture:**
```java
// platform-admin/application/port/ExtensionPoint.java
public interface ExtensionPoint {
    String name();
    ExtensionResult execute(ExtensionContext context);
}

// Built-in extension points:
// - payment.pre_authorize  — before payment auth
// - payment.post_capture   — after payment capture
// - refund.pre_execute      — before refund execution
// - subscription.pre_renew  — before subscription renewal
```

**White-label:**
- Custom branding per merchant (logo, colors, fonts)
- Custom domain support for hosted checkout
- White-label API documentation (Redocly per merchant)

### Acceptance Criteria
- [ ] Merchant self-service onboarding completes end-to-end
- [ ] Per-merchant PSP configuration active
- [ ] Extension point executes custom logic at payment lifecycle hooks
- [ ] White-label checkout page renders with merchant branding
- [ ] Usage-based billing calculates charges per merchant

---

## Sprint 5.6 — Enterprise Hardening & v1.0.0 (Weeks 113-120)

### Objectives
- ISO 20022 full message compliance
- SOC 2 Type II automation
- PCI DSS v4.0 compliance tooling
- Chaos engineering
- Performance hardening to 5000 TPS

### ISO 20022 Compliance

- Full message catalog: pain.001 (payment initiation), pacs.008 (credit transfer), pacs.002 (status), camt.053 (bank-to-customer statement), acmt.023 (account management)
- XML generation and validation against ISO 20022 schemas
- JSON ↔ XML bidirectional conversion

### SOC 2 Automation

```java
// compliance/application/service/Soc2EvidenceCollector.java
public class Soc2EvidenceCollector {
    // Automated evidence for Trust Services Criteria:
    // CC1 - Control environment: user access logs, role assignments
    // CC2 - Communication: audit trail, change logs
    // CC3 - Risk assessment: vulnerability scan results
    // CC6 - Logical access: API key usage, authentication logs
    // CC7 - System operations: uptime metrics, incident logs
    // CC8 - Change management: git history, deployment logs
}
```

### Chaos Engineering

- Litmus chaos experiments for Kubernetes
- Gameday playbooks:
  - PSP outage (circuit breaker activation → cascade to secondary PSP)
  - Database failover (PG primary failure → replica promotion)
  - Kafka partition loss (consumer rebalance)
  - Cache failure (Valkey down → graceful degradation)
  - Network partition (between services)
- Automated resilience testing in CI/CD (weekly chaos run)

### Performance Hardening

Target: **5000 TPS at p95 < 400ms**

Optimizations:
- Connection pool: HikariCP 60 connections, statement caching
- Kafka: 32+ partitions for payments topic, batch producer (linger.ms=10)
- Read replicas: analytics and reconciliation queries on replicas
- Query audit: EXPLAIN ANALYZE on all hot paths, index optimization
- JVM: G1GC tuning, virtual thread pool sizing
- Valkey: cluster mode for horizontal scaling
- Database: partitioned tables for high-volume (payments, postings, audit_log)

### v1.0.0 Release Criteria

- [ ] All Phase 5 acceptance criteria met
- [ ] 5000 TPS sustained for 30 minutes (Gatling)
- [ ] p95 < 400ms, p99 < 800ms
- [ ] Zero data loss under chaos scenarios
- [ ] SOC 2 evidence collection automated
- [ ] ISO 20022 messages validate against schemas
- [ ] Full API documentation (Redocly developer portal)
- [ ] Migration guides from Spreedly, Stripe, Primer
- [ ] Helm chart v1.0.0 with production-ready defaults
- [ ] CHANGELOG.md complete for all phases
- [ ] Security audit completed (external)
- [ ] Tag v1.0.0, GitHub Release, Docker images published

---

## Appendix: Technology Additions Summary

### Phase 4

| Technology | Sprint | Purpose |
|-----------|--------|---------|
| AWS CloudHSM / Thales Luna (interface) | 4.1 | Hardware security module for vault encryption keys |
| Visa Token Service SDK | 4.1 | Network tokenization |
| Mastercard MDES SDK | 4.1 | Network tokenization |
| Marqeta / Lithic SDK | 4.3 | Virtual card issuance |
| Swift SDK | 4.5 | iOS mobile SDK |
| Kotlin SDK | 4.5 | Android mobile SDK |
| TaxJar / Avalara SDK | 4.6 | Tax calculation |
| Onfido / Persona SDK | 4.6 | KYC verification |

### Phase 5

| Technology | Sprint | Purpose |
|-----------|--------|---------|
| ISO 20022 libraries | 5.1 | Payment message standard |
| ONNX Runtime | 5.2 | ML model serving |
| MLflow | 5.2 | Model registry and experiment tracking |
| Kafka Streams | 5.2 | Real-time feature engineering |
| Circle / Paxos SDK | 5.4 | Stablecoin on/off-ramp |
| Litmus Chaos | 5.6 | Chaos engineering |
| Redocly | 5.6 | API documentation portal |

## Appendix: Competitive Parity Milestones

| Competitor | Basic Capability | Phase | Key Capabilities |
|-----------|-----------------|-------|-----------------|
| Modern Treasury | Phase 2 | 2 | Ledger + reconciliation + payouts |
| Chargeflow | Phase 2 | 2 | Dispute management + auto-representment |
| Chargebee/Recurly | Phase 2 | 2 | Basic subscription billing + dunning |
| ProcessOut | Phase 3 | 3 | Analytics + PSP health scoring |
| Spreedly | Phase 4 | 4 | Universal vault + 220+ gateway abstraction |
| Primer.io | Phase 4 | 4 | Visual workflow builder + SDK |
| Gr4vy | Phase 3 | 3 | Client SDK + multi-PSP routing |
| Stripe (core) | Phase 4 | 4 | Vault + basic subscription billing + marketplace + SDK |
| Adyen (core) | Phase 5 | 5 | POS + real-time rails + issuing |

## Appendix: Module Count Growth

| Phase | Active Modules | Total Java Files (est.) |
|-------|---------------|------------------------|
| Phase 1 (v0.1.0) | 5 + 4 stubs | ~130 |
| Phase 2 (v0.2.0) | 10 (all activated) + billing | ~400 |
| Phase 3 (v0.3.0) | 12 + fraud, analytics | ~700 |
| Phase 4 (v0.4.0) | 18 + vault, marketplace, compliance, pos | ~1,200 |
| Phase 5 (v1.0.0) | 22 + realtime-rails, embedded-finance, platform-admin | ~1,800 |
