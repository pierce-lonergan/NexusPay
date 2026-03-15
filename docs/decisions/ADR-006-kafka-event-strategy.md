# ADR-006: Kafka Event Strategy — JSON, Domain Topics, DLT

**Status**: Accepted
**Date**: 2026-03-15
**Deciders**: Architecture team

## Context

NexusPay must publish domain events for inter-module communication. Decisions needed:
1. Serialization format (JSON vs Avro vs Protobuf)
2. Topic granularity (per-event-type vs per-domain vs single topic)
3. Error handling strategy (retry policy, dead letter topics)
4. Ordering guarantees

## Decisions

### 1. JSON serialization (Phase 1)

**Decision**: Plain JSON with a standard `EventEnvelope` record. No schema registry.

**Rationale**: Avro + Schema Registry adds infrastructure complexity (Confluent Schema Registry service, schema evolution management) that isn't justified at Phase 1 volumes. JSON is human-readable, debuggable, and sufficient. Migrate to Avro in Phase 3 when schema evolution becomes a concern.

**Risk**: No compile-time or runtime schema validation. Mitigated by the `EventEnvelope` record in `common` module providing a consistent structure.

### 2. Domain-level topics (not per-event-type)

**Decision**: Two domain topics (`nexuspay.payments`, `nexuspay.ledger`) with event type filtering via the `event_type` field and Kafka headers.

**Rationale**:
- **Per-event topics** (e.g., `nexuspay.payments.captured`, `nexuspay.payments.failed`) creates topic sprawl and complicates consumer configuration
- **Single topic** loses per-domain ordering and mixes unrelated events
- **Domain topics** balance simplicity and separation: payment lifecycle in one topic, ledger events in another
- Consumers filter by `event_type` header without payload deserialization

### 3. Dead letter topics with fixed backoff

**Decision**: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(1000ms, 3 retries)`.

**Rationale**: After 3 retries at 1-second intervals, failed messages move to `{topic}.DLT`. This prevents poison messages from blocking the consumer partition while preserving the failed message for investigation.

**DLT retention**: 30 days (vs 7 days for domain topics), giving operators time to investigate and reprocess.

### 4. Partition key = aggregate_id

**Decision**: Use `aggregate_id` (payment_id or refund_id) as the Kafka partition key.

**Rationale**: Guarantees ordered processing of all events for a single payment/refund lifecycle within a partition. Cross-payment ordering is not required.

## Consequences

**Positive:**
- Simple, debuggable event format
- Per-payment ordering guaranteed
- Poison messages don't block consumers
- Kafka headers enable efficient consumer-side filtering

**Negative:**
- No schema validation (until Phase 3 Avro migration)
- DLT messages require manual tooling to inspect/reprocess
- Domain topics mix event types (consumers must filter)

## Phase Evolution

| Phase | Serialization | Schema Registry | CDC |
|-------|--------------|----------------|-----|
| 1 | JSON | No | Polling outbox |
| 2 | JSON | No | Debezium CDC |
| 3 | Avro | Confluent SR | Debezium CDC |
