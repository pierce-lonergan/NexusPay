# Kafka Event Streaming Architecture

## 1. Topic Topology

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Kafka Cluster (KRaft)                         │
│                                                                     │
│  ┌───────────────────────────────┐  ┌───────────────────────────┐  │
│  │  nexuspay.payments            │  │  nexuspay.ledger          │  │
│  │  ─────────────────            │  │  ──────────────           │  │
│  │  Partitions: 6                │  │  Partitions: 6            │  │
│  │  Replication: 1 (dev) / 3    │  │  Replication: 1 (dev) / 3 │  │
│  │  Retention: 7 days           │  │  Retention: 7 days        │  │
│  │  Cleanup: delete             │  │  Cleanup: delete          │  │
│  │                               │  │                           │  │
│  │  Event types:                 │  │  Event types:             │  │
│  │  • PaymentCreated            │  │  • LedgerEntryCreated     │  │
│  │  • PaymentAuthorized         │  │                           │  │
│  │  • PaymentCaptured           │  │  Partition key:            │  │
│  │  • PaymentFailed             │  │    journal_entry_id        │  │
│  │  • PaymentVoided             │  │                           │  │
│  │  • RefundCreated             │  └───────────────────────────┘  │
│  │  • RefundCompleted           │                                  │
│  │  • RefundFailed              │  ┌───────────────────────────┐  │
│  │                               │  │  nexuspay.ledger.DLT     │  │
│  │  Partition key:               │  │  ─────────────────       │  │
│  │    payment_id or refund_id    │  │  Partitions: 1           │  │
│  └───────────────────────────────┘  │  Retention: 30 days      │  │
│                                      └───────────────────────────┘  │
│  ┌───────────────────────────────┐                                  │
│  │  nexuspay.payments.DLT       │                                  │
│  │  ─────────────────────       │                                  │
│  │  Partitions: 1               │                                  │
│  │  Retention: 30 days          │                                  │
│  └───────────────────────────────┘                                  │
└─────────────────────────────────────────────────────────────────────┘
```

## 2. Producer → Topic → Consumer Flow

```
┌──────────────┐      ┌──────────────────┐      ┌──────────────────┐
│ Outbox Relay │      │ nexuspay.payments │      │  Ledger Consumer │
│ (payment     │─────►│                  │─────►│  (nexuspay-      │
│  module)     │      │  P0 P1 P2 P3 P4 │      │   ledger-        │
│              │      │  P5              │      │   consumer)      │
│ Partition    │      │                  │      │                  │
│ key =        │      │  Kafka headers:  │      │  Handles:        │
│ aggregate_id │      │  • event_type    │      │  PaymentCaptured │
│              │      │  • aggregate_type│      │  RefundCompleted │
└──────────────┘      │  • aggregate_id  │      └──────────────────┘
                      └──────────────────┘
                              │
                              │ On failure (3 retries)
                              ▼
                      ┌──────────────────┐
                      │ nexuspay.payments │
                      │     .DLT         │
                      │                  │
                      │ Poisoned messages│
                      │ retained 30 days │
                      └──────────────────┘
```

## 3. Consumer Group Topology

```
Consumer Group: nexuspay-ledger-consumer
├── Topic: nexuspay.payments
├── Offset reset: earliest
├── Isolation: read_committed
├── Purpose: Creates journal entries from payment events
└── DLT: nexuspay.payments.DLT (3 retries, 1s backoff)

Consumer Group: nexuspay-gateway-consumer (Sprint 1.6)
├── Topic: nexuspay.payments
├── Purpose: Delivers webhook events to merchant endpoints
└── DLT: nexuspay.payments.DLT
```

## 4. Event Envelope (JSON)

Every event published to Kafka follows this structure:

```json
{
  "event_id": "evt_a1b2c3d4e5f6",
  "event_type": "PaymentCaptured",
  "aggregate_type": "Payment",
  "aggregate_id": "pi_xyz789abc",
  "timestamp": "2026-03-15T12:00:00Z",
  "version": 1,
  "metadata": {
    "trace_id": "abc-123-def",
    "tenant_id": "default",
    "request_id": "req_12345"
  },
  "payload": {
    "gateway_payment_id": "pay_hs_12345",
    "amount": 10000,
    "currency": "USD",
    "status": "succeeded"
  }
}
```

**Kafka Record Headers** (set by outbox relay for consumer-side filtering):
- `event_type` → e.g., `PaymentCaptured`
- `aggregate_type` → e.g., `Payment`
- `aggregate_id` → e.g., `pi_xyz789abc`

## 5. Dead Letter Topic (DLT) Error Handling

```
  Consumer receives message
         │
         ▼
  ┌──────────────┐
  │ Process event │
  └──────┬───────┘
         │
    ┌────┴────┐
    │         │
  Success   Exception
    │         │
    ▼         ▼
  Commit   Retry 1 (after 1s)
  offset       │
          ┌────┴────┐
          │         │
        Success   Exception
          │         │
          ▼         ▼
        Commit   Retry 2 (after 1s)
        offset       │
                ┌────┴────┐
                │         │
              Success   Exception
                │         │
                ▼         ▼
              Commit   Retry 3 (after 1s)
              offset       │
                      ┌────┴────┐
                      │         │
                    Success   Exception (final)
                      │         │
                      ▼         ▼
                    Commit   Publish to .DLT topic
                    offset   Commit offset
                             Log ERROR with details
```

Configuration: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(1000, 3)`

## 6. Ordering Guarantees

```
Payment pi_abc123 lifecycle:
                                                    Kafka Partition
  Webhook: payment_succeeded ─┐                    (key = pi_abc123)
                               │                         │
  Outbox: PaymentCaptured     ─┤── Same partition ──────►│ Offset 0: PaymentCaptured
                               │   (same key)            │
  Webhook: refund_succeeded   ─┤                         │ Offset 1: RefundCompleted
                               │                         │
  Outbox: RefundCompleted     ─┘                         │
                                                         │
  Consumer reads in order: ◄─────────────────────────────┘
  1. PaymentCaptured (creates ledger entry)
  2. RefundCompleted (creates refund ledger entry)

  ✓ Per-payment ordering guaranteed by partition key
  ✗ Cross-payment ordering NOT guaranteed (different partitions)
```

## 7. Shared Constants (Code Organization)

```
common/src/main/java/io/nexuspay/common/event/
├── EventEnvelope.java   ← Standard event record structure
├── EventTypes.java      ← All event type constants
│                          (PAYMENT_CAPTURED, REFUND_COMPLETED, etc.)
└── Topics.java          ← All topic names + consumer group IDs
                           (PAYMENTS, LEDGER, PAYMENTS_DLT, etc.)

app/src/main/java/io/nexuspay/app/config/
├── KafkaTopicConfig.java    ← NewTopic beans (auto-create on startup)
├── KafkaProducerConfig.java ← Producer factory (idempotent, acks=all)
└── KafkaConsumerConfig.java ← Consumer factory + DLT error handler
```

## 8. Phase Evolution

| Phase | Feature | Status |
|-------|---------|--------|
| 1 (current) | JSON events, polling outbox, DLT, 6 partitions | Implemented |
| 2 | Debezium CDC (replaces polling), consumer lag alerting | Planned |
| 3 | Avro schemas + Schema Registry, compatibility checks | Planned |
| 3 | Kafka Streams for real-time aggregation | Planned |
