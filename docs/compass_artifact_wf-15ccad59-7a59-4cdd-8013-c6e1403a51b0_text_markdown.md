# Building an enterprise payment platform on HyperSwitch

**Build ON TOP of HyperSwitch as infrastructure, wrapping it with a Java/Spring Boot enterprise layer that fills its 10 critical gaps.** HyperSwitch provides a production-grade payment routing core backed by a $1.2B unicorn (Juspay), but it lacks the enterprise operations layer — SSO, approval workflows, advanced RBAC, reconciliation automation, and payment-specific observability — that justifies commercial pricing from competitors like Primer.io and Spreedly. The opportunity is to become the "enterprise operations layer" that transforms HyperSwitch from a developer tool into a CFO-ready platform. This analysis draws from 947 open GitHub issues, community discussions, architecture documentation, commercial competitor analysis, and engineering blogs from Netflix, Uber, Airbnb, and Shopify.

---

## HyperSwitch is solid infrastructure with a porous enterprise ceiling

HyperSwitch's core strengths are real. Written in Rust with **<30ms application latency** and a claimed **5,000 TPS** ceiling under horizontal scaling, it outperforms most commercial orchestrators on raw throughput. Its three-tier storage design — Redis for hot-path caching, a drainer for async persistence, PostgreSQL for durability — keeps the database off the critical payment path. The **PCI DSS v4.0 Level 1** certification, **50+ PSP connectors**, and Multi-Armed Bandit routing algorithm provide a genuinely competitive orchestration core.

The project is actively maintained: **40,700+ GitHub stars**, **7,221 merged PRs**, version **v1.120.0** as of November 2025, and quarterly public roadmaps through Q1 2026. Juspay raised **$248M** across five rounds, reaching unicorn status in January 2026 with a $1.2B valuation. Revenue grew from ~$13M (FY22) to **~$64M (FY25)**, with EBITDA margins improving from -84% to -21%. The Apache 2.0 license provides fork insurance.

But the enterprise ceiling is low. There is **no SSO/SAML integration**, only **7 fixed RBAC roles** (no custom roles), **no approval workflows** for refunds or chargebacks, **no SLA monitoring**, and **no SOX compliance automation**. The analytics dashboard is basic — GitHub Discussion #3971 documents 10 specific UX complaints including no sorting, wrong timezones, maximum 10 records per page, and email-based data export. These are not minor gaps; they are procurement blockers for any Fortune 500 company.

---

## The 10 gaps that represent maximum enterprise value

Each gap below represents a feature that commercial competitors charge $50K–$500K+ annually for, that HyperSwitch lacks, and that an enterprise layer could provide.

### Gap 1: Enterprise identity and access management

HyperSwitch offers 7 predefined roles (Organization Admin, Merchant Admin, Profile Admin, Developer, IAM, Operations, Customer Support) with no customization. There is no SSO/SAML/OIDC integration. Every enterprise with Okta, Azure AD, or Google Workspace identity providers will reject this during security review. Primer.io and Spreedly both offer enterprise SSO on their premium tiers. **Building a comprehensive IAM layer** — custom roles with granular permissions, SAML 2.0/OIDC federation, session management, and audit logging — immediately unlocks enterprise procurement.

### Gap 2: Approval workflows and financial controls

No configurable approval chains exist for refunds, chargebacks, configuration changes, or connector modifications. Enterprises need rules like "refunds >$500 require manager approval" and "production routing changes require two approvals." This is a SOX compliance requirement for public companies. Every payment at Netflix, Uber, and Airbnb passes through financial control layers. **A workflow engine for payment operations** — configurable approval chains, four-eyes principle enforcement, time-based auto-escalation — is a high-value differentiator.

### Gap 3: Payment-specific observability platform

HyperSwitch provides OpenTelemetry-native metrics, Prometheus, and Grafana integration, but **no payment-domain-aware observability**. Generic APM tools cannot alert on authorization rate drops, PSP-specific degradation, or decline code anomalies without extensive custom configuration. Airbnb built a four-layer monitoring system (client → backend → PSP → webhook) with composite alerts and anomaly detection. Shopify uses country-specific circuit breaker identifiers. **A payment observability layer** that tracks authorization rates by PSP/country/card-network/amount-bucket, provides decline code analysis, monitors PSP health with automatic circuit-breaking, and offers Stripe-style "canonical log lines" per transaction would be transformative.

### Gap 4: Automated reconciliation engine

PwC estimates manual reconciliation consumes **up to 30% of finance team time**. HyperSwitch's reconciliation framework is on the roadmap but immature. The challenge is multi-source: gateways, banks, ERPs, and billing platforms all use different formats, timestamps, and reference IDs. Partial settlements, split payouts, and FX differences create complex matching scenarios. Airbnb, Netflix, and Uber all built custom reconciliation systems. **An automated, multi-source reconciliation engine** — ingesting PSP settlement files, matching against internal records, flagging exceptions, and posting to GL systems — fills a gap that no open-source project addresses well.

### Gap 5: No-code payment workflow builder

Primer.io's visual drag-and-drop workflow builder is its strongest differentiator, enabling non-technical teams to build end-to-end payment automation (routing → fraud check → 3DS → retry → notification) without code. HyperSwitch requires developer involvement for every flow change. Gr4vy offers a similar no-code dashboard. **A visual workflow builder** — with conditional routing, fraud integration, 3DS orchestration, and retry logic as configurable nodes — would eliminate Primer's primary advantage while running on open-source infrastructure.

### Gap 6: Advanced smart routing with ML

HyperSwitch's Multi-Armed Bandit algorithm is a legitimate but basic ML approach. It lacks fraud-score-based routing, latency-based routing, A/B testing frameworks for routing strategies, and true ML models (gradient boosting, neural networks) that learn from historical patterns. ProcessOut already offers ML-powered smart routing; Primer is building an "AI teammate" for payment optimization. **An advanced routing engine** — with pluggable ML models, cost-optimization that factors interchange/scheme-fees/acquirer-margins, and built-in experimentation — would justify significant pricing premium.

### Gap 7: Double-entry payment ledger

Every company that reaches payment scale builds a double-entry ledger. Uber's Gulfstream uses immutable entries with zero-sum validation. Stripe's core architecture centers on a double-entry bookkeeping system. **No production-ready open-source payment ledger exists.** Building one that integrates with HyperSwitch — tracking every money movement as debits and credits, providing an immutable audit trail, supporting multi-currency accounting, and feeding financial reporting — fills the most significant architectural gap in the open-source payment ecosystem.

### Gap 8: Dispute and chargeback management

HyperSwitch offers basic centralized dispute monitoring but **no automated workflows for dispute response**. There is no evidence assembly automation, no deadline tracking, no win-rate analytics, and no pre-built response templates. Commercial chargeback tools (Chargeflow, Chargeback Gurus) charge significant fees per dispute. **An integrated dispute management layer** — with automated evidence collection, response template generation, deadline alerts, and win-rate optimization — adds direct, measurable ROI for merchants.

### Gap 9: Multi-region deployment orchestration

HyperSwitch's architecture is "active-active multi-region" in principle but **DIY in practice**. No documentation exists for cross-region failover, database replication topology, or configuration synchronization. The drainer architecture (Redis → PostgreSQL) adds complexity for multi-region consistency. Enterprise payment platforms require guaranteed availability across regions with clear RPO/RTO objectives. **A multi-region deployment framework** — with documented topologies for AWS/GCP/Azure, cross-region PostgreSQL replication, Redis cluster management, and automated failover — converts a "possible" architecture into a "turnkey" one.

### Gap 10: Compliance automation and audit tooling

HyperSwitch is PCI DSS v4.0 Level 1 certified but offers **no SOX compliance support, no SOC 2 Type II certification, and no automated evidence collection**. Enterprises in regulated industries need continuous compliance monitoring, automated audit trail generation, change management controls with separation of duties, and evidence packages for auditors. **A compliance automation layer** — generating PCI DSS 4.0 evidence, SOX audit trails, automated access reviews, and continuous control monitoring — addresses the single largest procurement blocker for financial institutions.

---

## How HyperSwitch compares to commercial competitors

The comparison reveals a clear pattern: commercial orchestrators excel at operations and business-user tooling, while HyperSwitch excels at developer control and infrastructure flexibility.

| Capability | HyperSwitch | Spreedly | Primer.io | Gr4vy | ProcessOut |
|---|---|---|---|---|---|
| **Pricing** | Free / custom cloud | $1,500/mo + usage | Custom enterprise | SaaS + rev share | ~1¢/transaction |
| **Self-hosted option** | ✅ Full control | ❌ SaaS only | ❌ SaaS only | ❌ SaaS only | ❌ SaaS only |
| **PSP connectors** | 50+ | 140+ | Hundreds | 400+ methods | Major PSPs |
| **PCI vault** | ✅ Open-source | ✅ Universal Vault (best) | ✅ Level 1 | ✅ Cloud vault | ✅ Tokenization |
| **No-code workflows** | ❌ Missing | ❌ Limited | ✅ Best-in-class | ✅ Dashboard | ❌ |
| **SSO/SAML** | ❌ Missing | ✅ Enterprise tier | ✅ Included | ✅ Included | Not documented |
| **SOC 2 Type II** | ❌ | ✅ | ✅ | ✅ | ✅ |
| **SLA monitoring** | ❌ Missing | Enterprise SLAs | ✅ Monitors + alerts | Limited | Limited |
| **ML routing** | ⚠️ Basic MAB | Basic | ✅ AI teammate | Adaptive | ✅ ML-powered |
| **White-label** | ✅ Best-in-class | ❌ | ❌ | ❌ | ❌ |
| **Network tokenization** | ⚠️ Growing | ✅ Advanced (100M+ enrolled) | ✅ | ✅ | Limited |
| **Analytics depth** | ⚠️ Basic | Customized reporting | ✅ Real-time observability | Centralized | ✅ Telescope audit |

**Spreedly** justifies its $18K+/year minimum with a **Universal Vault** storing 1B+ payment methods and 15+ years of maturity. Its vault-centric value proposition — "no PSP can hold your card data hostage" — is its moat. **Primer.io** ($94M+ raised) wins on the **workflow builder** and adaptive 3DS, enabling business teams to iterate without engineering. **Gr4vy** ($27.2M raised, $115M valuation) differentiates through **dedicated cloud instances per merchant** — not shared infrastructure — with edge computing for data sovereignty. **ProcessOut** (acquired by Checkout.com) leads with **Telescope**, a free audit tool that benchmarks payment performance against 200+ merchants without integration.

HyperSwitch's advantages are undeniable: **zero licensing cost**, full source code ownership, white-label excellence, and the ability to self-host for complete data sovereignty. But it competes as developer infrastructure, not as an enterprise operations platform.

---

## What payment engineering teams at scale actually build

Research into Netflix, Uber, Airbnb, Shopify, and Stripe reveals a consistent internal platform architecture that goes far beyond routing:

**Uber** built Gulfstream, a double-entry bookkeeping payment order system processing **dozens of millions of transactions daily**. Their unified checkout reduced ~70 endpoints to one orchestration layer, delivering a **4.5% higher session recovery rate** and hundreds of millions of dollars in incremental gross bookings annually. Key innovation: configuration-driven business logic that reduced new-market launch time from months to days.

**Airbnb** rebuilt payment orchestration from a monolithic Rails app into three standalone systems (billing, orchestration, financial reporting) using a **Multi-Step Transaction framework** with YAML-based configuration. They built a PSP Emulator for testing without live dependencies and achieved **150x latency improvement** in payment data reads through denormalization into Elasticsearch. Their four-layer observability model (client → backend → PSP → webhook) with composite anomaly detection is the gold standard.

**Netflix** uses MySQL for billing ACID transactions, Cassandra for subscription state, and CockroachDB for multi-region active-active — **multiple databases optimized for different consistency needs**. A dedicated Payment Analytics team uses ML to optimize dynamic routing across 300M+ monthly subscriber renewals.

**Shopify** developed country-specific circuit breakers (not global), aggressive HTTP timeout policies, and load tests months before Black Friday. Their 10 resilience practices emphasize that payment system reliability requires domain-specific patterns beyond generic microservice practices.

The pattern across all these companies: they spend **seven figures** and 12-18 months building the operations layer (reconciliation, observability, financial reporting, internal tooling) that sits above the routing core. This is precisely the layer HyperSwitch lacks.

---

## The Java/Spring Boot integration strategy

HyperSwitch is a standalone HTTP service, not an embeddable library. JNI/FFI integration is impractical — its Tokio async runtime, database connections, and HTTP server cannot be meaningfully embedded in a JVM. The recommended approach is a **two-layer strategy**:

**Layer 1 — Auto-generated REST client.** Use `openapi-generator-maven-plugin` with HyperSwitch's OpenAPI specification (maintained via the `utoipa` Rust crate, ensuring spec-implementation synchronization) to generate type-safe Java DTOs and API clients. The generator supports **RestClient** (Spring 6.1+), **WebClient** (reactive), and **Feign** as HTTP backends. This mirrors how HyperSwitch's own Node.js SDK works.

**Layer 2 — Spring Boot starter SDK.** Build `hyperswitch-spring-boot-starter` that auto-configures the generated client, adds **Resilience4j** circuit breakers, **Spring Retry** policies, **Micrometer** metrics integration, and a fluent domain-specific API. Include a Spring Boot Actuator health indicator that monitors HyperSwitch availability.

**Deployment pattern:** Run HyperSwitch as a Kubernetes sidecar alongside the Spring Boot application for localhost-latency communication (~0.1ms overhead vs. ~1-5ms cross-service). For simpler setups, Docker Compose with both services on the same network. This approach requires **zero modifications to HyperSwitch**, leverages mature tooling, and maintains loose coupling for easy upgrades or replacement.

---

## Community health and dependency risk assessment

HyperSwitch's community shows strong signals with notable caveats. The **40,700 stars** are impressive, but Reddit has virtually zero discussion (multiple searches returned no results), and Hacker News engagement is minimal (36 points on the launch post, 2 comments). The star-to-engagement ratio suggests significant GitHub-star inflation, possibly from Hacktoberfest participation or the large Indian developer community rather than production users.

Development velocity is high — **464 open PRs** and 7,221 merged PRs indicate active internal development by Juspay's ~150 core engineers. But the **947 open issues** represent significant backlog pressure. The Q1 2026 roadmap focuses on connector expansion (Brazilian banks, Worldpay Access), vault improvements (multi-vault support, network tokenization), and SDK accessibility — not the enterprise operations features identified as gaps.

**Juspay's financial trajectory supports long-term commitment**: revenue nearly 5x'd from FY22 to FY25, and the January 2026 $50M follow-on round at $1.2B valuation signals strong investor confidence. The open-source model is strategic — driving adoption that converts to enterprise cloud subscriptions — not philanthropic. The risk of abandonment is low, but the risk of the project prioritizing Juspay's cloud revenue over community enterprise features is moderate.

**Key DX pain points to account for**: Rust compilation requires **24GB RAM on WSL** and takes 15+ minutes; the multi-repository architecture (backend, SDK, control center, helm charts, CDK — each in different languages including Rust, ReScript, and OCaml) creates confusion; the upgrade path requires two-step migration from older versions; and the control center dashboard is operationally weak (no sorting, wrong timezones, 10-record page limit).

---

## Strategic recommendation: build ON TOP, not around or alongside

Three approaches were evaluated:

**ON TOP (recommended):** Use HyperSwitch as the payment routing and PSP integration core. Build the enterprise operations layer — IAM, workflows, observability, reconciliation, ledger, compliance — in Java/Spring Boot, communicating with HyperSwitch via REST API. This leverages HyperSwitch's strongest capabilities (routing, connectors, vault) while building differentiated value where it's weakest.

**AROUND (wrapping):** Build a complete Java wrapper that intercepts all HyperSwitch operations, adding enterprise features at the proxy layer. This creates tight coupling, doubles latency for every operation, and requires tracking every HyperSwitch API change. Too fragile.

**ALONGSIDE (independent):** Borrow HyperSwitch's patterns but build independently. This wastes 2-3 years rebuilding the PSP connector layer (50+ integrations), routing engine, and card vault — undifferentiated work that HyperSwitch already does well. Not justified unless HyperSwitch's architecture proves fundamentally incompatible.

The ON TOP approach maximizes leverage: HyperSwitch handles the hard, undifferentiated payment plumbing (PCI-compliant card handling, PSP API transformations, 3DS orchestration, basic routing), while the enterprise layer captures the high-margin operational value. This mirrors how companies build on PostgreSQL (don't rewrite the database) or Kubernetes (don't rewrite the orchestrator) — use proven infrastructure, differentiate above it.

---

## Concrete product strategy

**Phase 1 (Months 1-4): Foundation.** Build the Java/Spring Boot SDK (auto-generated client + starter), enterprise IAM layer (custom RBAC, SAML 2.0/OIDC, audit logging), and basic approval workflows. Deploy HyperSwitch via Helm with a documented production topology. This alone unlocks enterprise procurement.

**Phase 2 (Months 4-8): Operations.** Build payment-specific observability (authorization rate monitoring by PSP/country/card-network, decline analysis dashboard, PSP health tracking with circuit-breaking, anomaly alerting) and the reconciliation engine (multi-source ingestion, automated matching, exception management, GL posting). These two capabilities justify $100K+ annual pricing.

**Phase 3 (Months 8-14): Intelligence.** Build the double-entry payment ledger, advanced ML routing layer (pluggable models, cost optimization, A/B experimentation), no-code workflow builder, and dispute management automation. The ledger becomes the system of record; HyperSwitch becomes the execution engine.

**Phase 4 (Months 14-18): Scale.** Multi-region deployment framework with documented topologies, compliance automation (PCI 4.0 evidence collection, SOX controls, continuous monitoring), and the PSP testing/emulation framework that Airbnb built internally.

The payment orchestration market generates **$1.46B annually** and grows at **~19.5% CAGR**, projected to reach **$6.5B by 2032**. The enterprise operations layer — sitting between HyperSwitch's open-source routing and the CFO's financial systems — is where the margin lives. Primer.io raised $94M and Gr4vy reached $115M valuation building exactly this layer on proprietary infrastructure. Building it on open-source infrastructure is structurally more defensible and offers merchants what no commercial orchestrator can: full source code ownership of the payment core, with enterprise-grade operations on top.