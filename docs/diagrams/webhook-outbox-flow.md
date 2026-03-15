# Webhook & Transactional Outbox Flow

## 1. Webhook Reception Flow

```
┌─────────────┐     ┌─────────────────────┐     ┌──────────┐     ┌──────────────┐
│ HyperSwitch │     │  Webhook Controller │     │  Valkey   │     │ PostgreSQL   │
│   Router    │     │  (NexusPay)         │     │          │     │ (NexusPay)   │
└──────┬──────┘     └──────────┬──────────┘     └────┬─────┘     └──────┬───────┘
       │                       │                     │                   │
       │ POST /internal/       │                     │                   │
       │  webhooks/hyperswitch │                     │                   │
       │ X-Webhook-Signature   │                     │                   │
       │ raw JSON body         │                     │                   │
       ├──────────────────────►│                     │                   │
       │                       │                     │                   │
       │                       │ STEP 1: Verify      │                   │
       │                       │ HMAC-SHA512          │                   │
       │                       │ (webhook-secret)     │                   │
       │                       │                     │                   │
       │                       │ ✗ Invalid signature │                   │
       │   401 Unauthorized    │◄─ ─ ─ ─ ─ ─ ─ ─ ─  │                   │
       │◄──────────────────────┤                     │                   │
       │                       │                     │                   │
       │                       │ ✓ Valid signature   │                   │
       │                       │                     │                   │
       │                       │ STEP 2: Persist raw │                   │
       │                       │────────────────────────────────────────►│
       │                       │                     │  INSERT INTO      │
       │                       │                     │  inbound_webhooks │
       │                       │                     │  (raw_payload)    │
       │                       │                     │                   │
       │                       │ STEP 3: Dedup       │                   │
       │                       │────────────────────►│                   │
       │                       │ SET NX webhook:     │                   │
       │                       │  dedup:{event_id}   │                   │
       │                       │  EX 86400 (24h)     │                   │
       │                       │                     │                   │
       │                       │ NX fails = duplicate│                   │
       │   200 OK (ack dedup)  │◄─ ─ ─ ─ ─ ─ ─ ─ ─  │                   │
       │◄──────────────────────┤                     │                   │
       │                       │                     │                   │
       │                       │ NX succeeds = new   │                   │
       │                       │                     │                   │
       │                       │ STEP 4: Write outbox│                   │
       │                       │────────────────────────────────────────►│
       │                       │                     │  INSERT INTO      │
       │                       │                     │  event_outbox     │
       │                       │                     │  (same TX as      │
       │                       │                     │   webhook update) │
       │                       │                     │                   │
       │                       │ STEP 5: Acknowledge │                   │
       │   200 OK              │                     │                   │
       │◄──────────────────────┤                     │                   │
```

## 2. Transactional Outbox Pattern

### Why Outbox?

The dual-write problem: if we update the database AND publish to Kafka in the same operation, either could fail independently, leading to inconsistency. The outbox pattern solves this:

```
WITHOUT OUTBOX (dangerous):                 WITH OUTBOX (safe):

  BEGIN TX                                    BEGIN TX
  ├─ UPDATE payment status ✓                  ├─ UPDATE payment status ✓
  COMMIT TX ✓                                 ├─ INSERT INTO event_outbox ✓
  ├─ PUBLISH to Kafka ✗ ← LOST!              COMMIT TX ✓  (atomic)

                                              [Async relay polls outbox]
                                              ├─ SELECT unpublished events
                                              ├─ PUBLISH to Kafka ✓
                                              ├─ UPDATE published_at ✓
```

### Outbox Relay (Polling)

```
┌───────────────────────────────────────────────────────────────┐
│                     Outbox Relay                               │
│                 @Scheduled(fixedDelay = 1000)                  │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  1. SELECT * FROM event_outbox                          │  │
│  │     WHERE published_at IS NULL                          │  │
│  │     ORDER BY created_at ASC                             │  │
│  │     LIMIT 100                                           │  │
│  └────────────────────┬────────────────────────────────────┘  │
│                       │                                       │
│                       ▼                                       │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  2. For each event:                                     │  │
│  │     ┌──────────────────────────────────────────────┐    │  │
│  │     │ Resolve Kafka topic from aggregate_type:     │    │  │
│  │     │   Payment → nexuspay.payments                │    │  │
│  │     │   Refund  → nexuspay.payments                │    │  │
│  │     │   Ledger  → nexuspay.ledger                  │    │  │
│  │     └──────────────────────────────────────────────┘    │  │
│  │                                                         │  │
│  │     Partition key = aggregate_id                        │  │
│  │     (ensures ordering per payment)                     │  │
│  └────────────────────┬────────────────────────────────────┘  │
│                       │                                       │
│                       ▼                                       │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  3. kafkaTemplate.send(topic, key, payload)             │  │
│  │                                                         │  │
│  │     ✓ Success → UPDATE event_outbox                     │  │
│  │                  SET published_at = NOW()                │  │
│  │                  WHERE id = event.id                     │  │
│  │                                                         │  │
│  │     ✗ Failure → BREAK loop (preserve ordering)          │  │
│  │                  Events stay unpublished                 │  │
│  │                  Retry on next poll cycle                │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

**Key design decisions:**
- On Kafka failure, the relay **breaks** (stops processing remaining events) to maintain ordering
- No `ApplicationEvent` fallback — if Kafka is down, events accumulate in outbox and retry
- This is intentional: the outbox IS the reliability mechanism

### Phase 1 → Phase 2+ Evolution

```
Phase 1 (Current):                     Phase 2+ (Planned):
┌──────────┐    ┌───────┐             ┌──────────┐    ┌──────────┐    ┌───────┐
│ Database │───►│Polling │───►Kafka    │ Database │───►│ Debezium │───►│ Kafka │
│ (outbox) │    │ Relay  │             │ (outbox) │    │  CDC     │    │       │
└──────────┘    └───────┘             └──────────┘    └──────────┘    └───────┘

  Pros: Simple, no extra infra           Pros: Sub-second latency,
  Cons: 1s polling delay,                       no polling overhead
        DB load from polling             Cons: Additional infrastructure
```

## 3. Event Envelope Structure

All events published to Kafka follow this JSON envelope:

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
    "status": "succeeded",
    "connector_name": "stripe"
  }
}
```

## 4. HyperSwitch Webhook Event Type Mapping

```
HyperSwitch Event Type          →  NexusPay Event Type
────────────────────────────────────────────────────────
payment_succeeded               →  PaymentCaptured
payment_failed                  →  PaymentFailed
payment_processing              →  PaymentAuthorized
payment_cancelled               →  PaymentVoided
refund_succeeded                →  RefundCompleted
refund_failed                   →  RefundFailed
```

## 5. End-to-End Event Flow (Payment Captured)

```
 HyperSwitch                NexusPay                     Kafka              Ledger
     │                         │                           │                  │
     │  webhook: payment_      │                           │                  │
     │  succeeded               │                           │                  │
     ├────────────────────────►│                           │                  │
     │                         │                           │                  │
     │                    ┌────┴────────────────┐          │                  │
     │                    │ Single DB TX:       │          │                  │
     │                    │ 1. persist webhook  │          │                  │
     │                    │ 2. write outbox     │          │                  │
     │                    │    (PaymentCaptured)│          │                  │
     │                    └────┬────────────────┘          │                  │
     │                         │                           │                  │
     │  200 OK                 │                           │                  │
     │◄────────────────────────┤                           │                  │
     │                         │                           │                  │
     │                    [1s later: relay polls]           │                  │
     │                         │                           │                  │
     │                         │  PaymentCaptured          │                  │
     │                         │  key=pi_xyz789            │                  │
     │                         ├──────────────────────────►│                  │
     │                         │                           │                  │
     │                         │                           │ PaymentCaptured  │
     │                         │                           ├─────────────────►│
     │                         │                           │                  │
     │                         │                           │             ┌────┴──────────┐
     │                         │                           │             │ SERIALIZABLE  │
     │                         │                           │             │ TX:           │
     │                         │                           │             │               │
     │                         │                           │             │ DR merchant   │
     │                         │                           │             │    recv +10000│
     │                         │                           │             │ CR customer   │
     │                         │                           │             │    liab -10000│
     │                         │                           │             │               │
     │                         │                           │             │ SUM = 0 ✓     │
     │                         │                           │             └────┬──────────┘
     │                         │                           │                  │
     │                         │                           │  LedgerEntry     │
     │                         │                           │  Created         │
     │                         │                           │◄─────────────────┤
     │                         │                           │                  │
```

## 6. Database Schema (Payment Module)

```
┌─────────────────────────────────┐
│         inbound_webhooks         │
├─────────────────────────────────┤
│ id          VARCHAR(64) PK      │
│ event_id    VARCHAR(128) UNIQUE │
│ event_type  VARCHAR(64)         │
│ raw_payload JSONB               │
│ received_at TIMESTAMP           │
│ processed_at TIMESTAMP          │
│ status      VARCHAR(16)         │──── RECEIVED | PROCESSED | FAILED
│ tenant_id   VARCHAR(64)         │
├─────────────────────────────────┤
│ idx: event_type, status,        │
│      received_at                │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│          event_outbox            │
├─────────────────────────────────┤
│ id             BIGSERIAL PK     │
│ aggregate_type VARCHAR(64)      │──── Payment | Refund | Ledger
│ aggregate_id   VARCHAR(64)      │
│ event_type     VARCHAR(64)      │
│ payload        JSONB            │
│ created_at     TIMESTAMP        │
│ published_at   TIMESTAMP        │──── NULL = unpublished
│ tenant_id      VARCHAR(64)      │
├─────────────────────────────────┤
│ idx: unpublished (partial)      │
│ idx: aggregate_type+id          │
└─────────────────────────────────┘
```
