# IAM & Authentication Flow Diagrams

## 1. Dual Authentication Flow (API Key + JWT)

```
┌──────────┐     ┌───────────────────────────────────────────────────┐
│  Client  │     │                  NexusPay                          │
│          │     │                                                    │
│ Authorization: │  ┌─────────────────────┐   ┌──────────────────┐   │
│ Bearer ...     │  │ ApiKeyAuthFilter    │   │ JWT Filter       │   │
│          │     │  │ (@Order 1)          │   │ (Spring OAuth2)  │   │
└────┬─────┘     │  └─────────┬───────────┘   └────────┬─────────┘   │
     │           │            │                         │             │
     │           │            ▼                         │             │
     │           │  ┌─────────────────────┐             │             │
     │           │  │ Token starts with   │             │             │
     │           │  │ "sk_test_" or       │             │             │
     │           │  │ "sk_live_"?         │             │             │
     │           │  └────┬──────────┬─────┘             │             │
     │           │       │          │                    │             │
     │           │      YES         NO                   │             │
     │           │       │          │                    │             │
     │           │       ▼          └───────────────────►│             │
     │           │  ┌──────────┐                         ▼             │
     │           │  │ Lookup   │                ┌──────────────────┐  │
     │           │  │ by prefix│                │ Validate JWT     │  │
     │           │  │ Verify   │                │ against Keycloak │  │
     │           │  │ bcrypt   │                │ issuer-uri       │  │
     │           │  └────┬─────┘                └────────┬─────────┘  │
     │           │       │                               │             │
     │           │       ▼                               ▼             │
     │           │  ┌─────────────────────────────────────────────┐   │
     │           │  │           NexusPayPrincipal                  │   │
     │           │  │  { userId, tenantId, role, authMethod }     │   │
     │           │  │                                              │   │
     │           │  │  API Key: userId=key_id, authMethod=API_KEY │   │
     │           │  │  JWT:     userId=sub,    authMethod=JWT     │   │
     │           │  └─────────────────────────────────────────────┘   │
     │           │                         │                          │
     │           │                         ▼                          │
     │           │  ┌─────────────────────────────────────────────┐   │
     │           │  │           SecurityContext                     │   │
     │           │  │  Authorities: ROLE_ADMIN / ROLE_OPERATOR /  │   │
     │           │  │              ROLE_VIEWER                     │   │
     │           │  └─────────────────────────────────────────────┘   │
     │           └────────────────────────────────────────────────────┘
```

## 2. API Key Lifecycle

```
┌──────────────────────────────────────────────────────────────┐
│                    API Key Creation                            │
│                                                               │
│  1. Generate random:  sk_test_ + Base64URL(24 random bytes)  │
│  2. Hash full key:    BCrypt(sk_test_abc123...)               │
│  3. Store prefix:     "sk_test_abc1" (first 12 chars)        │
│  4. Persist:          api_keys table (hash + prefix + role)  │
│  5. Return to user:   full key shown ONCE                    │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    API Key Authentication                      │
│                                                               │
│  1. Extract Bearer token from Authorization header           │
│  2. Check prefix: starts with "sk_"?                         │
│  3. Lookup: SELECT WHERE key_prefix = first12chars           │
│             AND revoked_at IS NULL                            │
│  4. Verify: BCrypt.matches(rawKey, storedHash)               │
│  5. Create NexusPayPrincipal with role from api_keys record  │
└──────────────────────────────────────────────────────────────┘
```

## 3. Maker-Checker Approval Flow

```
┌──────────┐         ┌─────────────┐         ┌──────────┐
│ Operator │         │  Gateway    │         │   IAM    │
│ (maker)  │         │   API       │         │  Module  │
└────┬─────┘         └──────┬──────┘         └────┬─────┘
     │                      │                      │
     │ POST /v1/payments/   │                      │
     │  {id}/refunds        │                      │
     │ amount: 75000        │                      │
     │ (above 50000         │                      │
     │  threshold)          │                      │
     ├─────────────────────►│                      │
     │                      │                      │
     │                      │ Check threshold:     │
     │                      │ 75000 >= 50000       │
     │                      │                      │
     │                      │ createApproval()     │
     │                      ├─────────────────────►│
     │                      │                      │
     │                      │                      │ INSERT pending_approvals
     │                      │                      │ status = PENDING
     │                      │                      │ + audit log entry
     │                      │                      │
     │  202 Accepted        │                      │
     │  {                   │                      │
     │    "id": "apr_xyz",  │                      │
     │    "status":         │                      │
     │      "pending_       │                      │
     │       approval",     │                      │
     │    "action": "refund"│                      │
     │  }                   │                      │
     │◄─────────────────────┤                      │
     │                      │                      │

┌──────────┐         ┌─────────────┐         ┌──────────┐         ┌──────────────┐
│  Admin   │         │  Gateway    │         │   IAM    │         │   Payment    │
│ (checker)│         │   API       │         │  Module  │         │  Orchestr.   │
└────┬─────┘         └──────┬──────┘         └────┬─────┘         └──────┬───────┘
     │                      │                      │                      │
     │ POST /v1/approvals/  │                      │                      │
     │  {id}/approve        │                      │                      │
     ├─────────────────────►│                      │                      │
     │                      │                      │                      │
     │                      │ approve()            │                      │
     │                      ├─────────────────────►│                      │
     │                      │                      │                      │
     │                      │                      │ Verify:              │
     │                      │                      │ - Status = PENDING   │
     │                      │                      │ - Reviewer ≠ Maker   │
     │                      │                      │                      │
     │                      │                      │ UPDATE status =      │
     │                      │                      │   APPROVED           │
     │                      │                      │ + audit log          │
     │                      │                      │                      │
     │                      │ Execute refund       │                      │
     │                      ├─────────────────────────────────────────────►
     │                      │                      │                      │
     │                      │                      │                      │ POST /refunds
     │                      │                      │                      │ → HyperSwitch
     │                      │                      │                      │
     │  200 OK              │                      │                      │
     │  RefundResponse      │                      │                      │
     │◄─────────────────────┤                      │                      │
```

## 4. RBAC Permission Matrix

```
┌────────────────────────────┬───────┬──────────┬────────┐
│ Operation                  │ Admin │ Operator │ Viewer │
├────────────────────────────┼───────┼──────────┼────────┤
│ Create payment             │  ✓    │    ✓     │   ✗    │
│ Capture payment            │  ✓    │    ✓     │   ✗    │
│ Void payment               │  ✓    │    ✓     │   ✗    │
│ Create refund (< threshold)│  ✓    │    ✓     │   ✗    │
│ Create refund (≥ threshold)│ 202*  │   202*   │   ✗    │
│ Approve/reject refund      │  ✓    │    ✗     │   ✗    │
│ View payments              │  ✓    │    ✓     │   ✓    │
│ View ledger                │  ✓    │    ✓     │   ✓    │
│ View approvals             │  ✓    │    ✓     │   ✗    │
│ Create API key             │  ✓    │    ✗     │   ✗    │
│ Revoke API key             │  ✓    │    ✗     │   ✗    │
│ View audit log             │  ✓    │    ✗     │   ✗    │
├────────────────────────────┴───────┴──────────┴────────┤
│ * 202 Accepted = creates pending approval request      │
│   Threshold: 50000 minor units (configurable)          │
└────────────────────────────────────────────────────────┘
```

## 5. Audit Logging Strategy

```
┌─────────────────────────────────────────────────────────────┐
│                    Audit Logging                             │
│                                                             │
│  Financial Operations (EXPLICIT):                           │
│  ─────────────────────────────────                          │
│  auditService.logAction(actor, "payment_created", ...)     │
│  auditService.logAction(actor, "refund_created", ...)      │
│  auditService.logAction(actor, "approval_approved", ...)   │
│                                                             │
│  Non-Financial Operations (AOP):                            │
│  ─────────────────────────────────                          │
│  @Audited(action = "api_key_created", resourceType = "..") │
│  @Audited(action = "config_read", resourceType = "Config") │
│                                                             │
│  Storage: audit_log table (synchronous DB write)            │
│  Phase 2: + async Kafka publish to nexuspay.audit topic     │
└─────────────────────────────────────────────────────────────┘
```

## 6. Keycloak Realm Configuration

```
Realm: nexuspay
│
├── Roles
│   ├── admin     ← Full access, can approve/reject
│   ├── operator  ← Can create payments, initiate refunds
│   └── viewer    ← Read-only access
│
├── Client: nexuspay-api
│   ├── Protocol: openid-connect
│   ├── Access Type: confidential
│   ├── Grant Types: authorization_code, client_credentials
│   └── Protocol Mapper: realm roles → realm_access.roles (JWT claim)
│
└── Test Users
    ├── admin@nexuspay.test    / test123  → admin role
    ├── operator@nexuspay.test / test123  → operator role
    └── viewer@nexuspay.test   / test123  → viewer role
```
