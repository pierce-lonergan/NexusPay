# Avro Migration Runbook — Sprint 3.4

## Overview

4-phase migration from JSON to Avro event serialization using Confluent Schema Registry.
Feature-flagged dual-write strategy ensures zero-downtime migration with instant rollback.

---

## Phase 1: Deploy (No Behavior Change)

**Flag:** `NEXUSPAY_AVRO_DUAL_WRITE=false` (default)

1. Deploy all Sprint 3.4 code
2. Verify Schema Registry is accessible: `curl http://schema-registry:8081/subjects`
3. Verify DualFormatDeserializer initializes (check logs for "DualFormatDeserializer: Avro deserializer initialized")
4. Verify event log captures events: query `SELECT count(*) FROM event_log`
5. Verify DLQ consumer is active: check `GET /v1/admin/dead-letters/stats`

**Validation:** Zero behavior change. All events flow as JSON. Event log + DLQ active.

---

## Phase 2: Enable Dual-Write

**Flag:** `NEXUSPAY_AVRO_DUAL_WRITE=true`

1. Register schemas in Schema Registry (CI/CD pipeline or manual):
   ```bash
   # Set compatibility to NONE for initial registration
   curl -X PUT http://schema-registry:8081/config \
     -H "Content-Type: application/json" \
     -d '{"compatibility": "NONE"}'
   ```
2. Set `NEXUSPAY_AVRO_DUAL_WRITE=true` and restart
3. Verify OutboxRelay logs show "DualWritePublisher" publishing
4. Verify `nexuspay_payload_format` header present on Kafka messages

**Rollback:** Set `NEXUSPAY_AVRO_DUAL_WRITE=false` and restart. Instant revert.

---

## Phase 3: Validate (1-2 Weeks)

Monitor the following metrics:

| Metric | Target | Source |
|--------|--------|--------|
| Schema Registry errors | 0 | Resilience4j circuit breaker metrics |
| Avro serialization failures | 0 | DualWritePublisher WARN logs |
| Format distribution | Track AVRO vs JSON vs DUAL | DualFormatDeserializer logs |
| Consumer lag delta | No increase vs pre-migration | Kafka consumer group lag |
| DLQ ingestion rate delta | No increase | `GET /v1/admin/dead-letters/stats` |
| Event log write errors | 0 | EventLogAppender ERROR logs |

**Schema Registry circuit breaker:** If Schema Registry becomes unreachable, DualWritePublisher
automatically falls back to JSON-only publishing. Monitor `resilience4j.circuitbreaker.schema-registry`
metrics for open/half-open transitions.

---

## Phase 4: Cutover (Future Sprint)

1. Switch Schema Registry compatibility to FULL_TRANSITIVE:
   ```bash
   curl -X PUT http://schema-registry:8081/config \
     -H "Content-Type: application/json" \
     -d '{"compatibility": "FULL_TRANSITIVE"}'
   ```
2. Remove JSON header from Avro messages (code change)
3. Consumers read Avro only via DualFormatDeserializer
4. Clean up dual-write code

---

## Schema Registration (CI/CD)

Production schemas are registered via CI/CD pipeline, not auto-registration.
`nexuspay.schema-registry.auto-register` is `false` in the production profile.

```bash
# Register a schema for a topic
curl -X POST http://schema-registry:8081/subjects/nexuspay.payments-value/versions \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"schema": "<escaped-avro-schema-json>"}'
```

---

## Rollback Procedure

At any phase, set `NEXUSPAY_AVRO_DUAL_WRITE=false` and restart.
- DualWritePublisher publishes JSON-only
- DualFormatDeserializer continues to handle any in-flight Avro messages via fallback
- No data loss: event log captures all events regardless of format
