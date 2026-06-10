# MAP — NexusPay codebase

Spring Modulith monolith. 16 Gradle modules; package = `io.nexuspay.<module>`.
Modulith module name derives from the **package**, not the Gradle project name
(e.g. `:payment-orchestration` → module `payment`). App entry point lives at the
`io.nexuspay` root so component scan + Modulith cover every module.

## Modules (responsibility)
- **common** (OPEN) — Money, PrefixedId, SessionToken, exceptions, event
  envelope + Avro/dual-write serialization, Topics. Shared kernel.
- **gateway-api** — REST controllers, rate-limit + idempotency filters,
  maker-checker refund orchestration, global exception handling. Depends:
  common, payment, ledger, iam.
- **payment-orchestration** (OPEN, pkg `payment`) — HyperSwitch client, webhook
  receiver, transactional outbox relay, routing engine, FX/cross-border, DCC.
- **ledger** (OPEN) — double-entry journal, balances, reconciliation job, FX
  conversion entries. Chart of accounts auto-created per currency.
- **iam** (OPEN) — API keys, RBAC, maker-checker approvals, audit, session
  tokens, TenantContext + tenant-aware datasource (RLS).
- **reconciliation** — settlement ingest, three-way matching (settlement↔
  payment↔ledger), exception management. Depends: common, ledger.
- **dispute** — chargeback lifecycle, evidence, auto-representment, chargeback
  ledger entries. Depends: common, ledger.
- **billing** — products/prices, subscriptions, invoices, dunning, proration.
  Depends: common, ledger, payment.
- **fraud** — rules engine, velocity, device fingerprint, FRM (Sift/Signifyd),
  risk aggregation, assessment pipeline.
- **analytics** — payment-event rollups (auth-rate/revenue/decline), PSP health,
  anomaly detection.
- **vault** — PCI card storage (AES-256-GCM), tokenization, network tokens.
- **marketplace** — connected accounts, split payments, payouts, KYC (stub).
- **b2b** — POs, vendor payments, virtual cards, invoices, Level 2/3 data.
- **workflow** — visual workflow builder (Temporal), webhook triggers. Depends:
  common, payment.
- **observability** — Micrometer metrics, health indicators, outbox-lag monitor.
- **app** — Spring Boot entry, Kafka/dual-write config, DLQ, event log.

## HOT PATHS (perf-tracked — none benchmarked yet; establish in ROADMAP Later)
- Payment create → HyperSwitch authorize (gateway → payment).
- Outbox relay poll → Kafka publish (payment, every 1s).
- Ledger journal-entry create under SERIALIZABLE (ledger, per capture/refund).

## DANGER ZONES (T3 — mirror ratchets.risk_map)
- **ledger/** — double-entry integrity, SERIALIZABLE retry, money math.
- **vault/** — PCI: PAN encryption, fingerprint, BIN exposure, detokenization.
- **iam/** — auth, RBAC, maker-checker, RLS/tenant isolation.
- **payment webhook + HMAC** — signature verification, replay/dedup.
- **payment/application/fx/** — currency-exponent math, rate locks, DCC, sanctions.
- ***Idempotency*** filters/keys — cross-tenant scoping, retry de-dup.
- **marketplace SplitPayment / Payout** — fund allocation, payout gating.
- **billing Dunning / *Scheduler*** — multi-instance double-billing.
- **db/migration/** — Flyway ordering/collisions across modules.
- **common/avro/** — serialization format detection, decimal precision.

## KNOWN STRUCTURAL DEBT (see BACKLOG / AUDITS)
- RLS not enforced at runtime (B-002). Fraud/sanctions not wired into payment
  path (B-003). Flyway V1/V2 collisions across modules (B-011). Schedulers lack
  distributed locks (B-001). Several protective services are advisory side-cars,
  not gates.
