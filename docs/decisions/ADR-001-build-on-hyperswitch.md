# ADR-001: Build on HyperSwitch as Payment Engine

**Status**: Accepted
**Date**: 2026-03-01
**Deciders**: Architecture team

## Context

NexusPay needs a payment processing engine that can route transactions to 50+ PSPs, handle card vaulting (PCI DSS v4.0), and provide smart routing. We must decide whether to build payment infrastructure from scratch, use a hosted service (Stripe/Adyen direct), or build on top of an existing open-source payment orchestrator.

## Decision

Build NexusPay as an enterprise operations layer **on top of HyperSwitch** (by Juspay), an open-source payment orchestrator written in Rust.

## Rationale

**Why HyperSwitch over building from scratch:**
- PCI DSS v4.0 compliance is a multi-year, multi-million dollar effort. HyperSwitch already holds it.
- 50+ PSP connectors pre-built (Stripe, Adyen, Braintree, etc.) vs. building each integration.
- Card vaulting (locker) is included — avoids storing sensitive card data in NexusPay.
- Smart routing engine reduces development scope by 6-12 months.

**Why HyperSwitch over hosted services:**
- Full control: self-hosted, customizable, no vendor lock-in.
- No per-transaction SaaS fees (significant at enterprise scale).
- Can swap PSPs without code changes (routing rules only).

**Why build NexusPay at all (vs. using HyperSwitch directly):**
- HyperSwitch is a developer tool, not an enterprise platform. It lacks:
  - SSO / enterprise IAM with role-based access
  - Maker-checker approval workflows
  - Double-entry ledger for accounting
  - Automated reconciliation
  - Compliance audit trails
  - Multi-tenant isolation
- NexusPay fills these gaps, targeting CFOs and finance teams, not just developers.

## Consequences

**Positive:**
- Massive reduction in payment infrastructure scope
- PCI DSS compliance inherited (NexusPay never handles raw card data)
- Focus engineering effort on enterprise differentiators

**Negative:**
- Tight coupling to HyperSwitch API (mitigated by `PaymentGatewayPort` abstraction)
- Must track HyperSwitch releases and breaking changes
- HyperSwitch consumer (drainer) is a critical dependency — must be monitored
- Docker Compose complexity increases (router + consumer + their PostgreSQL + their Redis)

**Risks:**
- HyperSwitch project abandonment (mitigated: large community, Juspay is commercially incentivized)
- API incompatibility on upgrades (mitigated: hand-written thin client with `@JsonIgnoreProperties(ignoreUnknown = true)`)
