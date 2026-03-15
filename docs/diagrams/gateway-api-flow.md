# Gateway API Flow Diagrams

## 1. Request Processing Pipeline

```
┌──────────┐     ┌──────────────────────────────────────────────────────────────────┐
│  Client  │     │                        NexusPay Gateway                          │
│          │     │                                                                   │
│ POST /v1/│     │  ┌────────────────┐  ┌──────────────┐  ┌───────────────────┐    │
│ payments │     │  │ Correlation    │  │ ApiKey /     │  │ Rate Limit        │    │
│          │     │  │ ID Filter      │  │ JWT Filter   │  │ Filter            │    │
│          │     │  │ (Order MIN)    │  │ (Order 1/2)  │  │ (Order 10)        │    │
└────┬─────┘     │  └───────┬────────┘  └──────┬───────┘  └────────┬──────────┘    │
     │           │          │                   │                    │               │
     │           │          ▼                   ▼                    ▼               │
     │           │  ┌────────────────────────────────────────────────────────────┐  │
     │           │  │                  Filter Chain Execution                     │  │
     │           │  │                                                            │  │
     │           │  │  1. CorrelationIdFilter: Generate/propagate X-Request-Id   │  │
     │           │  │  2. ApiKeyAuthFilter:    sk_ prefix → bcrypt verify        │  │
     │           │  │     OR BearerTokenFilter: JWT → Keycloak validate          │  │
     │           │  │  3. RateLimitFilter:     Token bucket check (Valkey)       │  │
     │           │  │  4. IdempotencyFilter:   Dedup via Valkey lock (POST only) │  │
     │           │  └────────────────────────────────────────────────────────────┘  │
     │           │                         │                                        │
     │           │                         ▼                                        │
     │           │  ┌────────────────┐  ┌──────────────────┐                       │
     │           │  │ ApiVersion     │  │  @PreAuthorize   │                       │
     │           │  │ Interceptor    │  │  RBAC Check      │                       │
     │           │  │ (X-API-Version)│  │                  │                       │
     │           │  └───────┬────────┘  └──────┬───────────┘                       │
     │           │          │                   │                                    │
     │           │          ▼                   ▼                                    │
     │           │  ┌────────────────────────────────────────────────────────────┐  │
     │           │  │                   REST Controller                          │  │
     │           │  │  PaymentController / RefundController / LedgerController   │  │
     │           │  │  ApprovalController / WebhookEndpointController            │  │
     │           │  └────────────────────────────────────────────────────────────┘  │
     │           └──────────────────────────────────────────────────────────────────┘
```

## 2. Rate Limiting (Token Bucket)

```
┌────────────────────────────────────────────────────────────────────┐
│                    Rate Limiting Flow                               │
│                                                                    │
│  Client Request                                                    │
│       │                                                            │
│       ▼                                                            │
│  ┌──────────────────────┐                                         │
│  │ Resolve rate limit   │                                         │
│  │ key from principal   │                                         │
│  │ (API key ID or JWT)  │                                         │
│  └──────────┬───────────┘                                         │
│             │                                                      │
│             ▼                                                      │
│  ┌──────────────────────┐     ┌──────────────────────────────┐   │
│  │ Lua Script (atomic)  │────►│ Valkey                       │   │
│  │                      │     │ Key: ratelimit:{principal}   │   │
│  │ Check bucket tokens  │     │ Value: {tokens: N, ts: T}    │   │
│  │ Refill based on time │     │ TTL: 60 seconds              │   │
│  │ Decrement if > 0     │     └──────────────────────────────┘   │
│  └──────────┬───────────┘                                         │
│             │                                                      │
│     ┌───────┴───────┐                                             │
│     │               │                                              │
│  tokens > 0     tokens = 0                                        │
│     │               │                                              │
│     ▼               ▼                                              │
│  ┌──────┐     ┌──────────────────────┐                            │
│  │ PASS │     │ 429 Too Many Requests│                            │
│  │      │     │                      │                            │
│  │ Hdrs:│     │ Retry-After: N       │                            │
│  │ X-RL-│     │ X-RateLimit-Limit:   │                            │
│  │ Limit│     │   100                │                            │
│  │ X-RL-│     │ X-RateLimit-Remaining│                            │
│  │ Rem. │     │   0                  │                            │
│  └──────┘     └──────────────────────┘                            │
│                                                                    │
│  Default: 100 requests / 60 seconds per API key                   │
│  Fail open: if Valkey down → request allowed                      │
└────────────────────────────────────────────────────────────────────┘
```

## 3. Idempotency Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Idempotency Enforcement                               │
│                                                                         │
│  POST /v1/payments                                                      │
│  Idempotency-Key: idem_abc123                                          │
│       │                                                                 │
│       ▼                                                                 │
│  ┌──────────────────────────────────────────────┐                      │
│  │ SET idempotency:idem_abc123 "PROCESSING"     │                      │
│  │     NX EX 60                                  │  ─── Valkey ───     │
│  └─────────────┬──────────────┬─────────────────┘                      │
│                │              │                                          │
│           NX success     NX failed                                      │
│           (first req)    (dup req)                                       │
│                │              │                                          │
│                ▼              ▼                                          │
│  ┌──────────────────┐   ┌──────────────────────────┐                   │
│  │ Process request  │   │ GET idempotency:{key}    │                   │
│  │ normally         │   └───────────┬──────────────┘                   │
│  └────────┬─────────┘               │                                   │
│           │                  ┌──────┴──────┐                            │
│           ▼                  │             │                             │
│  ┌──────────────────┐  "PROCESSING"   JSON response                    │
│  │ Cache response:  │       │             │                             │
│  │ SET key {status, │       ▼             ▼                             │
│  │  contentType,    │  ┌──────────┐  ┌──────────────────┐              │
│  │  body} EX 86400  │  │ Poll     │  │ Return cached    │              │
│  └────────┬─────────┘  │ every    │  │ response (200)   │              │
│           │            │ 200ms    │  └──────────────────┘              │
│           ▼            │ max 15x  │                                     │
│  ┌──────────────────┐  │ (3 sec)  │                                     │
│  │ Return original  │  └──────────┘                                     │
│  │ response         │                                                   │
│  └──────────────────┘  Lock TTL: 60s  │  Cache TTL: 24h               │
│                                                                         │
│  On error: DELETE lock key → retry is safe                              │
│  Idempotency-Key also propagated to HyperSwitch for PSP-level dedup   │
└─────────────────────────────────────────────────────────────────────────┘
```

## 4. Refund with Maker-Checker Threshold

```
┌────────────────────────────────────────────────────────────────────────┐
│                  Refund Creation Flow                                    │
│                                                                        │
│  POST /v1/payments/{id}/refunds                                        │
│  { "amount": 75000, "currency": "USD" }                               │
│       │                                                                │
│       ▼                                                                │
│  ┌────────────────────────────────────┐                               │
│  │ RefundOrchestrationService         │                               │
│  │                                    │                               │
│  │ amount >= threshold (50000)?       │                               │
│  └────────┬───────────────┬───────────┘                               │
│           │               │                                            │
│          YES              NO                                           │
│           │               │                                            │
│           ▼               ▼                                            │
│  ┌─────────────────┐  ┌──────────────────┐                           │
│  │ ApprovalService  │  │ PaymentGateway   │                           │
│  │ .createApproval()│  │ .createRefund()  │                           │
│  │                  │  │                  │                           │
│  │ → pending_       │  │ → HyperSwitch    │                           │
│  │   approvals DB   │  │   POST /refunds  │                           │
│  │ → audit_log      │  │                  │                           │
│  └────────┬─────────┘  └────────┬─────────┘                           │
│           │                      │                                     │
│           ▼                      ▼                                     │
│  ┌─────────────────┐  ┌──────────────────┐                           │
│  │ 202 Accepted    │  │ 201 Created      │                           │
│  │ {               │  │ {                │                           │
│  │   "id":"apr_x", │  │   "id":"ref_y",  │                           │
│  │   "status":     │  │   "status":      │                           │
│  │    "pending_    │  │    "pending",    │                           │
│  │     approval",  │  │   "amount":25000 │                           │
│  │   "action":     │  │ }                │                           │
│  │    "refund"     │  └──────────────────┘                           │
│  │ }               │                                                  │
│  └─────────────────┘                                                  │
│                                                                        │
│  Approval Flow (admin only):                                           │
│  POST /v1/approvals/{id}/approve                                       │
│    → ApprovalService.approve() (self-approval prevented)              │
│    → RefundOrchestrationService.executeApprovedRefund()                │
│    → HyperSwitch POST /refunds                                        │
│    → 200 OK with RefundResponse                                       │
└────────────────────────────────────────────────────────────────────────┘
```

## 5. API Endpoint Summary

```
┌────────────────────────────────────────────────────────────────────────┐
│                        NexusPay REST API v1                            │
│                                                                        │
│  PAYMENTS                                          Auth: admin/operator│
│  ─────────                                                             │
│  POST   /v1/payments                    Create payment intent          │
│  POST   /v1/payments/{id}/confirm       Confirm payment                │
│  POST   /v1/payments/{id}/capture       Capture authorized payment     │
│  POST   /v1/payments/{id}/cancel        Void authorization             │
│  POST   /v1/payments/{id}/refunds       Create refund (or 202)         │
│  GET    /v1/payments/{id}               Retrieve payment    (+ viewer) │
│                                                                        │
│  REFUNDS                                           Auth: all roles     │
│  ────────                                                              │
│  GET    /v1/refunds/{id}                Retrieve refund                │
│                                                                        │
│  LEDGER                                            Auth: all roles     │
│  ───────                                                               │
│  GET    /v1/ledger/accounts             List accounts with balances    │
│  GET    /v1/ledger/journal-entries      List entries (filterable)      │
│                                                                        │
│  APPROVALS                                         Auth: admin/operator│
│  ──────────                                                            │
│  GET    /v1/approvals                   List pending approvals         │
│  POST   /v1/approvals/{id}/approve      Approve (admin only)          │
│  POST   /v1/approvals/{id}/reject       Reject (admin only)           │
│                                                                        │
│  WEBHOOK ENDPOINTS                                 Auth: admin         │
│  ─────────────────                                                     │
│  POST   /v1/webhook-endpoints           Register endpoint              │
│  GET    /v1/webhook-endpoints           List endpoints                 │
│  DELETE /v1/webhook-endpoints/{id}      Delete endpoint                │
│                                                                        │
│  API KEYS                                          Auth: admin         │
│  ─────────                                                             │
│  POST   /v1/api-keys                   Create key (shown once)         │
│  DELETE /v1/api-keys/{id}              Revoke key                      │
│                                                                        │
│  INFRASTRUCTURE                                    Auth: public        │
│  ──────────────                                                        │
│  GET    /actuator/health                Aggregate health               │
│  GET    /v1/api-docs                    OpenAPI 3.1 spec               │
│  GET    /v1/swagger-ui                  Interactive API explorer        │
│  POST   /internal/webhooks/hyperswitch  HyperSwitch webhook receiver   │
│                                                                        │
│  HEADERS                                                               │
│  ────────                                                              │
│  Idempotency-Key    POST requests — dedup via Valkey                  │
│  X-API-Version      API version (default: 2026-03-01)                 │
│  X-Request-Id       Correlation ID (auto-generated if absent)          │
│  X-RateLimit-Limit  Max requests per window                           │
│  X-RateLimit-Remaining  Remaining requests in window                  │
│  Retry-After        Seconds to wait (on 429)                          │
└────────────────────────────────────────────────────────────────────────┘
```

## 6. Webhook Endpoint Lifecycle

```
┌────────────────────────────────────────────────────────────┐
│                 Webhook Endpoint Management                  │
│                                                             │
│  1. Register:                                               │
│     POST /v1/webhook-endpoints                              │
│     {                                                       │
│       "url": "https://merchant.com/webhooks",              │
│       "events": ["payment.captured", "refund.completed"]   │
│     }                                                       │
│                                                             │
│     → Generate whsec_{random32} signing secret              │
│     → Persist to webhook_endpoints table                    │
│     → Return full response WITH secret (shown once)         │
│                                                             │
│  2. List:                                                   │
│     GET /v1/webhook-endpoints                               │
│     → Returns all enabled endpoints (secret omitted)        │
│                                                             │
│  3. Delete (soft):                                          │
│     DELETE /v1/webhook-endpoints/{id}                       │
│     → Sets enabled = false (preserves for audit)            │
│     → Returns 204 No Content                                │
│                                                             │
│  Storage:                                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ webhook_endpoints                                     │  │
│  │ ─────────────────                                     │  │
│  │ id          VARCHAR(64) PK    (we_xxx)               │  │
│  │ url         VARCHAR(512)                              │  │
│  │ description VARCHAR(256)                              │  │
│  │ secret      VARCHAR(256)      (whsec_xxx)            │  │
│  │ events      TEXT[]                                    │  │
│  │ tenant_id   VARCHAR(64)       (indexed)              │  │
│  │ enabled     BOOLEAN                                   │  │
│  │ created_at  TIMESTAMP                                 │  │
│  │ updated_at  TIMESTAMP                                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  NOTE: Event DELIVERY to endpoints is deferred to Phase 2   │
│  (see GAP-030)                                              │
└────────────────────────────────────────────────────────────┘
```
