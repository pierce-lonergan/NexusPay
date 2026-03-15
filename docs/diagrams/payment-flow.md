# Payment Flow Diagrams

## 1. Payment Creation & Authorization Flow

```
┌──────────┐     ┌─────────────┐     ┌──────────────────┐     ┌─────────────┐
│ Merchant │     │ Gateway API │     │    Payment        │     │ HyperSwitch │
│  Client  │     │             │     │  Orchestration    │     │   Router    │
└────┬─────┘     └──────┬──────┘     └────────┬─────────┘     └──────┬──────┘
     │                  │                      │                      │
     │ POST /v1/payments│                      │                      │
     │ Idempotency-Key  │                      │                      │
     │ Authorization    │                      │                      │
     ├─────────────────►│                      │                      │
     │                  │                      │                      │
     │                  │ 1. Validate JWT/     │                      │
     │                  │    API key           │                      │
     │                  │ 2. Rate limit check  │                      │
     │                  │    (Valkey bucket)   │                      │
     │                  │ 3. Idempotency check │                      │
     │                  │    (Valkey SET NX)   │                      │
     │                  │                      │                      │
     │                  │ createPayment()      │                      │
     │                  ├─────────────────────►│                      │
     │                  │                      │                      │
     │                  │                      │ POST /payments       │
     │                  │                      │ api-key header       │
     │                  │                      │ Idempotency-Key fwd  │
     │                  │                      ├─────────────────────►│
     │                  │                      │                      │
     │                  │                      │   Circuit Breaker    │
     │                  │                      │   ┌──────────────┐   │
     │                  │                      │   │ Resilience4j │   │
     │                  │                      │   │  hyperswitch │   │
     │                  │                      │   └──────────────┘   │
     │                  │                      │                      │
     │                  │                      │  PaymentResponse     │
     │                  │                      │◄─────────────────────┤
     │                  │                      │                      │
     │                  │  PaymentResponse     │                      │
     │                  │◄─────────────────────┤                      │
     │                  │                      │                      │
     │                  │ 4. Cache response in │                      │
     │                  │    Valkey (24h TTL)  │                      │
     │                  │                      │                      │
     │  201 Created     │                      │                      │
     │◄─────────────────┤                      │                      │
     │                  │                      │                      │
```

## 2. Payment Capture Flow

```
┌──────────┐     ┌─────────────┐     ┌──────────────────┐     ┌─────────────┐
│ Merchant │     │ Gateway API │     │    Payment        │     │ HyperSwitch │
│  Client  │     │             │     │  Orchestration    │     │   Router    │
└────┬─────┘     └──────┬──────┘     └────────┬─────────┘     └──────┬──────┘
     │                  │                      │                      │
     │ POST /v1/payments│                      │                      │
     │  /{id}/capture   │                      │                      │
     ├─────────────────►│                      │                      │
     │                  │                      │                      │
     │                  │ capturePayment()     │                      │
     │                  ├─────────────────────►│                      │
     │                  │                      │                      │
     │                  │                      │ POST /payments/      │
     │                  │                      │   {id}/capture       │
     │                  │                      ├─────────────────────►│
     │                  │                      │                      │
     │                  │                      │  Captured response   │
     │                  │                      │◄─────────────────────┤
     │                  │                      │                      │
     │  200 OK          │                      │                      │
     │◄─────────────────┤                      │                      │
     │                  │                      │                      │
```

## 3. Refund Flow (Below Threshold — Immediate)

```
┌──────────┐     ┌─────────────┐     ┌──────────────────┐     ┌─────────────┐
│ Merchant │     │ Gateway API │     │    Payment        │     │ HyperSwitch │
│  Client  │     │             │     │  Orchestration    │     │   Router    │
└────┬─────┘     └──────┬──────┘     └────────┬─────────┘     └──────┬──────┘
     │                  │                      │                      │
     │ POST /v1/payments│                      │                      │
     │  /{id}/refunds   │                      │                      │
     │ amount: 2500     │                      │                      │
     │ (below threshold)│                      │                      │
     ├─────────────────►│                      │                      │
     │                  │                      │                      │
     │                  │ Check threshold:     │                      │
     │                  │ 2500 < 50000 ✓       │                      │
     │                  │                      │                      │
     │                  │ createRefund()       │                      │
     │                  ├─────────────────────►│                      │
     │                  │                      │                      │
     │                  │                      │ POST /refunds        │
     │                  │                      ├─────────────────────►│
     │                  │                      │                      │
     │                  │                      │  RefundResponse      │
     │                  │                      │◄─────────────────────┤
     │                  │                      │                      │
     │  200 OK          │                      │                      │
     │  RefundResponse  │                      │                      │
     │◄─────────────────┤                      │                      │
```

## 4. Refund Flow (Above Threshold — Maker-Checker)

```
┌──────────┐     ┌─────────────┐     ┌─────┐     ┌──────────────────┐     ┌─────────────┐
│ Operator │     │ Gateway API │     │ IAM │     │    Payment        │     │ HyperSwitch │
└────┬─────┘     └──────┬──────┘     └──┬──┘     └────────┬─────────┘     └──────┬──────┘
     │                  │               │                  │                      │
     │ POST /v1/payments│               │                  │                      │
     │  /{id}/refunds   │               │                  │                      │
     │ amount: 75000    │               │                  │                      │
     │ (above threshold)│               │                  │                      │
     ├─────────────────►│               │                  │                      │
     │                  │               │                  │                      │
     │                  │ Check: 75000  │                  │                      │
     │                  │  >= 50000     │                  │                      │
     │                  │               │                  │                      │
     │                  │ createApproval│                  │                      │
     │                  ├──────────────►│                  │                      │
     │                  │               │                  │                      │
     │  202 Accepted    │               │                  │                      │
     │  { id: "apr_x",  │               │                  │                      │
     │    status:        │               │                  │                      │
     │    "pending" }    │               │                  │                      │
     │◄─────────────────┤               │                  │                      │
     │                  │               │                  │                      │
     │                  │               │                  │                      │
┌────┴─────┐            │               │                  │                      │
│  Admin   │            │               │                  │                      │
└────┬─────┘            │               │                  │                      │
     │                  │               │                  │                      │
     │ POST /v1/approvals               │                  │                      │
     │  /{id}/approve   │               │                  │                      │
     ├─────────────────►│               │                  │                      │
     │                  │ approve()     │                  │                      │
     │                  ├──────────────►│                  │                      │
     │                  │               │                  │                      │
     │                  │               │ createRefund()   │                      │
     │                  │               ├─────────────────►│                      │
     │                  │               │                  │ POST /refunds        │
     │                  │               │                  ├─────────────────────►│
     │                  │               │                  │                      │
     │                  │               │                  │  RefundResponse      │
     │                  │               │                  │◄─────────────────────┤
     │  200 OK          │               │                  │                      │
     │◄─────────────────┤               │                  │                      │
```

## 5. Void (Cancel) Flow

```
┌──────────┐     ┌─────────────┐     ┌──────────────────┐     ┌─────────────┐
│ Merchant │     │ Gateway API │     │    Payment        │     │ HyperSwitch │
└────┬─────┘     └──────┬──────┘     └────────┬─────────┘     └──────┬──────┘
     │                  │                      │                      │
     │ POST /v1/payments│                      │                      │
     │  /{id}/cancel    │                      │                      │
     ├─────────────────►│                      │                      │
     │                  │                      │                      │
     │                  │ voidPayment()        │                      │
     │                  ├─────────────────────►│                      │
     │                  │                      │                      │
     │                  │                      │ POST /payments/      │
     │                  │                      │   {id}/cancel        │
     │                  │                      ├─────────────────────►│
     │                  │                      │                      │
     │                  │                      │  Cancelled response  │
     │                  │                      │◄─────────────────────┤
     │                  │                      │                      │
     │  200 OK          │                      │                      │
     │◄─────────────────┤                      │                      │
```

**Note**: Void is a "free compensation action" — it releases the authorization hold without capturing funds. In a saga context, this is the compensating action for a failed downstream step after authorization.

## 6. Circuit Breaker States

```
                    ┌──────────┐
                    │  CLOSED  │ ◄── Normal operation
                    │          │     All calls pass through
                    └────┬─────┘
                         │
                         │ Failure rate >= 50% OR
                         │ Slow call rate >= 80%
                         │ (within sliding window of 10)
                         ▼
                    ┌──────────┐
                    │   OPEN   │ ◄── All calls fail-fast
                    │          │     PaymentException.gatewayError()
                    └────┬─────┘
                         │
                         │ Wait 30 seconds
                         │
                         ▼
                    ┌──────────┐
                    │HALF-OPEN │ ◄── Allow 3 test calls
                    │          │
                    └────┬─────┘
                         │
                    ┌────┴────┐
                    │         │
               Success    Failure
                    │         │
                    ▼         ▼
               ┌────────┐ ┌──────┐
               │ CLOSED │ │ OPEN │
               └────────┘ └──────┘
```

## 7. Idempotency Flow (Concurrent Request Handling)

```
Request A ──►  SET NX idempotency:{key} "PROCESSING" EX 60
               │
               ├── NX succeeds → Process request
               │                  SET idempotency:{key} {response} XX EX 86400
               │                  Return response
               │
Request B ──►  SET NX idempotency:{key} "PROCESSING" EX 60
               │
               ├── NX fails → GET idempotency:{key}
               │              │
               │              ├── Value = "PROCESSING"
               │              │   Poll with backoff (max 30s)
               │              │   Until response appears
               │              │
               │              ├── Value = {response_json}
               │              │   Return cached response (200)
               │              │
               │              └── Key expired (crash scenario)
               │                  Next request retries (60s TTL)
```
