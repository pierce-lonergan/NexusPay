# ADR-003: Transactional Outbox Pattern for Event Delivery

**Status**: Accepted
**Date**: 2026-03-01
**Deciders**: Architecture team

## Context

NexusPay must publish domain events (PaymentCaptured, RefundCompleted, etc.) to Kafka for downstream consumers (ledger, audit, future services). The dual-write problem means we cannot reliably update a database AND publish to Kafka in the same logical operation — if either fails independently, the system becomes inconsistent.

## Decision

Use the **Transactional Outbox Pattern**: write events to an `event_outbox` table in the same database transaction as the business state change. A separate relay process polls the outbox and publishes events to Kafka.

## Rationale

**Why outbox over direct Kafka publish:**
- Atomicity: business state and event are committed in one DB transaction
- No distributed transaction needed (no XA, no 2PC)
- Natural retry: if Kafka is down, events accumulate in the outbox and publish when Kafka recovers
- Audit trail: outbox table serves as a log of all events emitted

**Why polling relay over Debezium CDC (in Phase 1):**
- Simplicity: no additional infrastructure (Kafka Connect, Debezium connector)
- Faster to implement and debug
- 1-second polling delay is acceptable for Phase 1 volumes
- Debezium planned for Phase 2 when sub-second latency matters

**Why no ApplicationEvent fallback:**
- Dual-mode dispatch (outbox + ApplicationEvent) creates confusion about which path is authoritative
- If Kafka is down, events should NOT fire locally — the whole point of the outbox is reliable eventual delivery
- Consumers that need real-time notification should consume from Kafka, not in-process events

## Consequences

**Positive:**
- Guaranteed at-least-once delivery (events never lost if DB commits)
- Simple implementation (~50 lines of relay code)
- Works with any Kafka availability level
- Events are ordered per aggregate (partition key = aggregate_id)

**Negative:**
- 1-second polling delay (Phase 1 only)
- Outbox table grows unbounded (GAP-005: needs cleanup job)
- Multiple app instances poll simultaneously (GAP-007: needs leader election or Debezium)
- At-least-once means consumers must be idempotent

## Phase Evolution

| Phase | Mechanism | Latency | Infra |
|-------|-----------|---------|-------|
| 1 | Polling relay (`@Scheduled`) | ~1s | None |
| 2 | Debezium CDC | ~100ms | Kafka Connect |
| 3 | Debezium + Schema Registry | ~100ms | Kafka Connect + SR |
