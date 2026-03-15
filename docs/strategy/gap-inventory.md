# NexusPay Comprehensive Gap Inventory

Last updated: 2026-03-15

This document catalogs every identified capability gap between NexusPay's current state and enterprise payment platform readiness. Gaps are organized by domain, prioritized, and mapped to implementation phases.

---

## Summary

| Domain | Total Gaps | Phase 2 | Phase 3 | Phase 4 | Phase 5 | Deferred |
|--------|-----------|---------|---------|---------|---------|----------|
| Core Platform (existing gaps) | 12 | 5 | 2 | 2 | 3 | 0 |
| Subscription Billing | 15 | 5 | 3 | 4 | 3 | 0 |
| Fraud Prevention | 10 | 0 | 6 | 2 | 2 | 0 |
| Tax Compliance | 8 | 1 | 2 | 2 | 3 | 0 |
| Cross-Border / FX | 8 | 0 | 5 | 2 | 1 | 0 |
| Payment Method Lifecycle | 10 | 2 | 4 | 2 | 2 | 0 |
| Developer Ecosystem | 12 | 3 | 2 | 5 | 2 | 0 |
| Reconciliation / Disputes | 8 | 6 | 1 | 1 | 0 | 0 |
| B2B Payments | 10 | 0 | 0 | 6 | 4 | 0 |
| POS / Omnichannel | 8 | 0 | 0 | 0 | 5 | 3 |
| Real-Time Rails | 8 | 0 | 0 | 2 | 4 | 2 |
| AI / ML Capabilities | 8 | 0 | 3 | 2 | 3 | 0 |
| Embedded Finance | 6 | 0 | 0 | 1 | 4 | 1 |
| Stablecoin / Crypto | 5 | 0 | 0 | 0 | 3 | 2 |
| Compliance / Certification | 8 | 2 | 1 | 2 | 3 | 0 |
| Mobile SDK | 6 | 0 | 1 | 3 | 2 | 0 |
| Marketplace / Platform | 8 | 1 | 1 | 4 | 2 | 0 |
| **Total** | **160** | **25** | **31** | **38** | **46** | **8** |

---

## Domain 1: Core Platform Gaps (12)

These are Phase 1 gaps already tracked in `docs/gaps/known-gaps.md`.

| ID | Gap | Severity | Phase | Status |
|----|-----|----------|-------|--------|
| GAP-001 | No multi-tenancy RLS enforcement | Critical | 2 | Open |
| GAP-002 | No TLS/mTLS between services | Critical | 2 | Open |
| GAP-003 | No secrets management | Critical | 2 | Open |
| GAP-004 | No database backup/PITR | Critical | 2 | Open |
| GAP-008 | No DLT reprocessing UI | High | 2 | Partial |
| GAP-011 | 1-second outbox polling latency | Medium | 2 | Accepted |
| GAP-012 | No Schema Registry / event versioning | Medium | 3 | Open |
| GAP-015 | No webhook retry/reprocessing | Medium | 2 | Open |
| GAP-018 | No structured error codes catalog | Low | 3 | Open |
| GAP-020 | No metrics export (Prometheus/Grafana) | Medium | 3 | Open |
| GAP-021 | HyperSwitch drainer not health-checked | Low | 2 | Open |
| GAP-026 | No API key expiration/rotation | Medium | 2 | Open |

---

## Domain 2: Subscription Billing (15)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| SUB-001 | No subscription lifecycle state machine | P0 | 2 | Very High |
| SUB-002 | No dunning / smart retry engine | P0 | 2 | High |
| SUB-003 | No proration engine for mid-cycle changes | P0 | 2 | High |
| SUB-004 | No product catalog / pricing model engine | P0 | 2 | Medium |
| SUB-005 | No usage-based / metered billing | P1 | 3 | High |
| SUB-006 | No trial management | P1 | 2 | Medium |
| SUB-007 | No coupon / discount engine | P1 | 3 | Medium |
| SUB-008 | No subscription pause/resume | P1 | 3 | Medium |
| SUB-009 | No subscriber analytics (MRR/ARR/churn) | P2 | 4 | Medium |
| SUB-010 | No subscription invoicing (PDF generation) | P1 | 4 | Medium |
| SUB-011 | No entitlement management | P2 | 4 | Medium |
| SUB-012 | No customer self-service portal | P2 | 4 | Medium |
| SUB-013 | No revenue recognition (ASC 606/IFRS 15) | P2 | 5 | Very High |
| SUB-014 | No multi-subscription account hierarchy | P3 | 5 | High |
| SUB-015 | No test clocks (time-travel for testing) | P2 | 5 | Medium |

---

## Domain 3: Fraud Prevention (10)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| FRD-001 | No configurable rules engine with velocity checks | P0 | 3 | Medium |
| FRD-002 | No device fingerprinting / intelligence | P0 | 3 | Medium |
| FRD-003 | No adaptive 3DS2 / SCA exemption optimization | P0 | 3 | High |
| FRD-004 | No manual review queue / case management | P1 | 3 | Medium |
| FRD-005 | No chargeback/dispute auto-representment | P1 | 3 | High |
| FRD-006 | No network-level risk signals (TC40/SAFE) | P1 | 3 | High |
| FRD-007 | No consortium / shared fraud intelligence | P2 | 4 | Very High |
| FRD-008 | No ML-based fraud scoring | P2 | 4 | Very High |
| FRD-009 | No Verifi CDRN/RDR pre-chargeback alerts | P1 | 5 | High |
| FRD-010 | No Ethoca alert integration | P1 | 5 | High |

---

## Domain 4: Tax Compliance (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| TAX-001 | No tax provider abstraction layer | P0 | 2 | Medium |
| TAX-002 | No real-time tax calculation engine | P1 | 3 | Plugin |
| TAX-003 | No tax obligation / nexus monitoring | P1 | 3 | Plugin |
| TAX-004 | No 1099-K reporting | P1 | 4 | Medium |
| TAX-005 | No DAC7 reporting (EU) | P1 | 4 | Medium |
| TAX-006 | No tax-inclusive/exclusive pricing display | P2 | 5 | Low |
| TAX-007 | No tax exemption certificate management | P2 | 5 | Medium |
| TAX-008 | No data localization compliance (India RBI, etc.) | P0 | 5 | Very High |

---

## Domain 5: Cross-Border / FX (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| FX-001 | No adaptive multi-currency pricing engine | P0 | 3 | High |
| FX-002 | No local acquiring via BIN-country matching | P0 | 3 | Medium |
| FX-003 | No FX management layer (rate feeds, markup, locking) | P1 | 3 | Medium |
| FX-004 | No sanctions / denied-party screening | P0 | 3 | Plugin |
| FX-005 | No Level II/III interchange data enrichment | P1 | 3 | Medium |
| FX-006 | No multi-currency virtual accounts | P2 | 4 | High |
| FX-007 | No cross-border payout optimization | P2 | 4 | High |
| FX-008 | No FX hedging tools | P3 | 5 | Plugin |

---

## Domain 6: Payment Method Lifecycle (10)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| PML-001 | No universal portable card vault | P0 | 4 | Very High |
| PML-002 | No network tokenization (Visa VTS / MC MDES) | P0 | 3 | Very High |
| PML-003 | No Card Account Updater orchestration | P1 | 2 | Medium |
| PML-004 | No credential-on-file indicator management (MIT/CIT) | P0 | 2 | Medium |
| PML-005 | No SEPA Direct Debit mandate lifecycle | P1 | 3 | High |
| PML-006 | No smart payment method recommendation engine | P2 | 3 | High |
| PML-007 | No declined payment cascade/fallback | P0 | 3 | Medium |
| PML-008 | No payment method verification ($0 auth, micro-deposits) | P1 | 4 | Medium |
| PML-009 | No digital wallet token lifecycle (DPAN management) | P2 | 4 | High |
| PML-010 | No card expiry proactive management | P2 | 5 | Low |

---

## Domain 7: Developer Ecosystem (12)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| DEV-001 | No client-side checkout SDK (NexusPay.js) | P0 | 4 | High |
| DEV-002 | No e-commerce platform plugins (Shopify, WooCommerce) | P0 | 4 | High |
| DEV-003 | No enterprise-grade webhook infrastructure (retry, monitoring) | P0 | 2 | Medium |
| DEV-004 | No Stripe-quality API versioning (date-based, pinned) | P1 | 2 | Medium |
| DEV-005 | No interactive documentation (Redocly-style) | P1 | 2 | Medium |
| DEV-006 | No multi-language SDK generation | P1 | 4 | Medium |
| DEV-007 | No app marketplace / extension framework | P2 | 4 | Very High |
| DEV-008 | No payment migration toolkit (token import) | P1 | 4 | High |
| DEV-009 | No CLI tool | P2 | 4 | Medium |
| DEV-010 | No ERP connectors (SAP, NetSuite) | P1 | 3 | High |
| DEV-011 | No accounting integrations (QuickBooks, Xero) | P1 | 3 | Medium |
| DEV-012 | No developer certification program | P3 | 5 | Low |

---

## Domain 8: Reconciliation / Disputes (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| REC-001 | No settlement file ingestion (multi-PSP) | P0 | 2 | High |
| REC-002 | No three-way matching engine | P0 | 2 | Very High |
| REC-003 | No exception management / dashboard | P1 | 2 | Medium |
| REC-004 | No dispute lifecycle state machine | P0 | 2 | High |
| REC-005 | No dispute evidence assembly | P1 | 2 | Medium |
| REC-006 | No Verifi/Ethoca pre-chargeback alert integration | P1 | 2 | High |
| REC-007 | No AI reconciliation matching | P2 | 3 | High |
| REC-008 | No dispute win-rate analytics | P2 | 4 | Medium |

---

## Domain 9: B2B Payments (10)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| B2B-001 | No invoice generation / lifecycle engine | P0 | 4 | High |
| B2B-002 | No net payment terms (Net 30/60/90) | P0 | 4 | Medium |
| B2B-003 | No batch/mass payment processing | P1 | 4 | High |
| B2B-004 | No B2B payment rails (ACH/wire) | P0 | 4 | High |
| B2B-005 | No AP/AR approval workflows | P1 | 4 | Medium |
| B2B-006 | No purchase order 3-way matching | P2 | 4 | High |
| B2B-007 | No virtual card issuance for AP | P2 | 5 | High |
| B2B-008 | No vendor/supplier onboarding (W-9, TIN) | P1 | 5 | Medium |
| B2B-009 | No EDI integration (810/820/850) | P2 | 5 | Very High |
| B2B-010 | No early payment discounting / dynamic discounting | P2 | 5 | Medium |

---

## Domain 10: POS / Omnichannel (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| POS-001 | No Tap-to-Pay / SoftPOS SDK | P1 | 5 | High |
| POS-002 | No terminal hardware abstraction layer | P1 | 5 | Very High |
| POS-003 | No store-and-forward offline mode | P2 | 5 | High |
| POS-004 | No unified omnichannel reporting | P1 | 5 | Medium |
| POS-005 | No tip handling / authorization adjustment | P2 | 5 | Medium |
| POS-006 | No EMV chip/contactless processing (L1/L2/L3 cert) | P3 | Deferred | Extreme |
| POS-007 | No terminal fleet management | P3 | Deferred | High |
| POS-008 | No POS software ecosystem integration | P3 | Deferred | High |

---

## Domain 11: Real-Time Payment Rails (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| RTR-001 | No FedNow/RTP integration | P1 | 5 | Very High |
| RTR-002 | No SEPA Instant Credit Transfer | P1 | 5 | Very High |
| RTR-003 | No Pay by Bank checkout | P1 | 4 | High |
| RTR-004 | No Variable Recurring Payments (UK VRP) | P2 | 4 | High |
| RTR-005 | No ISO 20022 native messaging | P1 | 5 | Very High |
| RTR-006 | No Open Banking PIS framework | P1 | 5 | High |
| RTR-007 | No Request to Pay orchestration | P2 | Deferred | High |
| RTR-008 | No payment consent lifecycle management | P2 | Deferred | High |

---

## Domain 12: AI / ML Capabilities (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| AI-001 | No ML-powered smart routing model | P0 | 3 | Very High |
| AI-002 | No authorization rate optimization (Boost equivalent) | P0 | 3 | Very High |
| AI-003 | No AI dispute response automation | P1 | 3 | High |
| AI-004 | No agentic commerce protocol support (ACP/UCP/TAP) | P1 | 5 | High |
| AI-005 | No predictive churn modeling | P2 | 4 | Medium |
| AI-006 | No NLP payment analytics | P2 | 5 | Medium |
| AI-007 | No unsupervised fraud pattern discovery | P2 | 5 | Very High |
| AI-008 | No payments foundation model architecture | P3 | 4 | Extreme |

---

## Domain 13: Embedded Finance (6)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| EMB-001 | No treasury-as-a-service (embedded accounts) | P2 | 5 | Plugin |
| EMB-002 | No card issuing orchestration | P2 | 5 | Plugin |
| EMB-003 | No merchant capital / embedded lending | P2 | 5 | Plugin |
| EMB-004 | No programmable/conditional payments (escrow) | P1 | 4 | High |
| EMB-005 | No KYC/KYB orchestration layer | P1 | 5 | Plugin |
| EMB-006 | No hosted payment pages / payment links | P0 | Deferred | Low |

---

## Domain 14: Stablecoin / Crypto (5)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| STC-001 | No stablecoin payment method connector | P2 | 5 | High |
| STC-002 | No multi-chain payment routing | P2 | 5 | High |
| STC-003 | No B2B stablecoin payouts | P2 | 5 | High |
| STC-004 | No crypto regulatory compliance module | P2 | Deferred | Plugin |
| STC-005 | No wallet address sanctions screening | P2 | Deferred | Plugin |

---

## Domain 15: Compliance / Certification (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| CMP-001 | No PCI DSS 4.0 deployment templates | P0 | 5 | High |
| CMP-002 | No SOC 2 evidence collection automation | P0 | 5 | High |
| CMP-003 | No penetration test of reference deployment | P0 | 5 | External |
| CMP-004 | No SBOM generation / vulnerability disclosure | P1 | 2 | Medium |
| CMP-005 | No data localization deployment topology | P1 | 5 | Very High |
| CMP-006 | No CIS benchmark container images | P1 | 2 | Medium |
| CMP-007 | No PCI DSS SAQ guide for self-hosters | P1 | 4 | Medium |
| CMP-008 | No automated PCI compliance monitoring | P2 | 4 | High |

---

## Domain 16: Mobile SDK (6)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| MOB-001 | No standalone customer payment method management | P1 | 4 | Medium |
| MOB-002 | No deep Apple Pay integration (certificate lifecycle) | P0 | 3 | High |
| MOB-003 | No deep Google Pay integration (Jetpack Compose) | P0 | 4 | Medium |
| MOB-004 | No App Store external payment compliance layer | P1 | 4 | Medium |
| MOB-005 | No mobile-optimized checkout UX | P0 | 4 | High |
| MOB-006 | No WCAG 2.2 accessibility compliance for UI | P1 | 5 | Medium |

---

## Domain 17: Marketplace / Platform (8)

| ID | Gap | Priority | Phase | Complexity |
|----|-----|----------|-------|------------|
| MKT-001 | No sub-merchant onboarding | P0 | 4 | High |
| MKT-002 | No configurable split payment rules | P0 | 4 | High |
| MKT-003 | No payout scheduling / disbursements | P0 | 4 | High |
| MKT-004 | No sub-merchant ledger accounts | P0 | 4 | Medium |
| MKT-005 | No 1099-K / DAC7 tax reporting | P1 | 4 | Medium |
| MKT-006 | No connected account credential vaulting | P1 | 2 | High |
| MKT-007 | No per-merchant analytics isolation | P1 | 3 | Medium |
| MKT-008 | No merchant self-service portal | P2 | 5 | High |

---

## Implementation Sequencing

### Phase 2 Priority Queue (25 gaps)

**Must-do (blocks enterprise adoption)**:
1. GAP-001: Multi-tenancy RLS
2. GAP-002: TLS/mTLS
3. GAP-003: Secrets management (Vault)
4. GAP-004: Database backup/PITR
5. REC-001-006: Reconciliation engine + dispute lifecycle
6. SUB-001-004,006: Subscription billing foundation
7. DEV-003-005: Webhook infra, API versioning, docs
8. CMP-004,006: SBOM, CIS images

### Phase 3 Priority Queue (31 gaps)

**Must-do (blocks global expansion)**:
1. FRD-001-006: Fraud rules engine
2. AI-001-003: ML routing, auth optimization, AI disputes
3. PML-002,005-007: Network tokenization, SEPA DD, APM cascade
4. FX-001-005: Multi-currency, local acquiring, FX, sanctions, L2/L3

### Phase 4 Priority Queue (38 gaps)

**Must-do (blocks platform play)**:
1. PML-001: Universal card vault
2. DEV-001-002,006-009: Checkout SDK, plugins, SDKs, marketplace
3. MKT-001-005: Marketplace module
4. B2B-001-006: Invoicing, net terms, batch payments
5. RTR-003-004: Pay by Bank, VRP

### Phase 5 Priority Queue (46 gaps)

**Differentiating capabilities**:
1. RTR-001-002,005-006: FedNow, SEPA Instant, ISO 20022
2. AI-004: Agentic commerce
3. STC-001-003: Stablecoin rails
4. POS-001-005: SoftPOS, terminals, omnichannel
5. CMP-001-003,005: Compliance toolkit, data localization
6. EMB-001-003,005: Embedded finance

---

## Out of Scope

The following gaps are permanently out of scope for NexusPay. These items require specialized hardware, regulatory processes, or certifications that are outside the boundaries of a software payment platform.

| ID | Gap | Reason for Exclusion |
|----|-----|---------------------|
| POS-006 | EMV chip/contactless processing (L1/L2/L3 certification) | Requires EMV L1 (hardware electrical), L2 (kernel certification per card network), and L3 (end-to-end transaction) certifications. These are hardware-specific processes involving physical test labs, card network approval cycles (6-12 months per network), and specialized terminal firmware. NexusPay is a software platform -- terminal vendors (Verifone, Ingenico, PAX) own these certifications. |
| POS-007 | Terminal fleet management | Requires physical hardware logistics, firmware distribution infrastructure, and vendor-specific integrations that are the domain of terminal manufacturers and their management platforms. |
| POS-008 | POS software ecosystem integration | Deep integration with POS software (Oracle Micros, Toast, Square) requires partnership agreements and vendor-specific APIs that are better served by dedicated POS middleware. |
| RTR-007 | Request to Pay orchestration | Requires direct participation in payment scheme governance and bilateral agreements with banks. Deferred indefinitely. |
| RTR-008 | Payment consent lifecycle management | Depends on Open Banking regulatory frameworks that vary by jurisdiction and require scheme-level enrollment. Deferred indefinitely. |
| STC-004 | Crypto regulatory compliance module | Crypto regulatory requirements (money transmitter licenses, state-by-state compliance in US) are business-process dependencies, not software features. |
| STC-005 | Wallet address sanctions screening | Specialized blockchain analytics (Chainalysis, Elliptic) are better consumed as third-party services rather than built in-house. Integration via plugin adapter is the appropriate approach. |
| EMB-006 | Hosted payment pages / payment links | Low-complexity feature that is better served by merchants' existing frontend frameworks. Not a differentiator for NexusPay's target market. |

> **Note**: Items marked "Deferred" in the domain tables above that are not listed here remain candidates for future phases if the project's scope or team size expands.

---

## Traceability

A traceability matrix mapping gap IDs to sprints and deliverables should be maintained as sprints are executed. The format is:

| Gap ID | Sprint | Deliverable | Status |
|--------|--------|-------------|--------|
| GAP-001 | 2.1 | RLS policies on all tables | Open |
| SUB-001 | 2.5a | Subscription lifecycle state machine | Open |
| ... | ... | ... | ... |

This matrix should be updated at sprint planning and retrospective to ensure every gap is tracked from identification through delivery. It serves as the primary audit trail for gap resolution.
