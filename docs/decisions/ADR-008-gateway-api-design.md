# ADR-008: Gateway API Design — Rate Limiting, Idempotency, Versioning

**Status**: Accepted
**Date**: 2026-03-15
**Deciders**: Architecture team

## Context

The gateway-api module is the public-facing surface for NexusPay. It needs rate limiting, idempotency enforcement, and API versioning plumbing for a production-ready payment API.

## Decisions

### 1. Rate Limiting: Valkey Token Bucket (Lua Script)

**Approach**: Atomic Lua script in Valkey implementing a token bucket per authenticated principal.

**Why Lua script, not Spring RateLimiter or Spring Cloud Gateway**:
- Atomic: no race condition between check-and-decrement
- Per-key: each API key gets its own bucket (not global or per-IP)
- Fail-open: if Valkey is down, requests are allowed (availability > throttling)
- Headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After` per Stripe convention

**Default**: 100 requests/60 seconds. Configurable via `nexuspay.rate-limit.*`.

### 2. Idempotency: Distributed Lock + Response Cache

**Approach**: Valkey-based two-phase idempotency:
1. `SET NX EX 60`: acquire processing lock (60s TTL prevents deadlock on crash)
2. Process request → cache response (`SET EX 86400`: 24h cache)
3. Duplicate requests poll with 200ms backoff until response available

**Why not database-based**:
- Valkey is already in the stack for rate limiting
- Lock + cache in same store avoids distributed coordination
- 60s TTL auto-recovers from crashes (no manual cleanup)
- 24h response cache matches Stripe's behavior

**Key propagation**: `Idempotency-Key` header is also forwarded to HyperSwitch, providing PSP-level deduplication as a safety net.

### 3. API Versioning: Header-Based, Single Version

**Approach**: `X-API-Version` header parsed by interceptor, stored as request attribute.

**Why header, not URL path (`/v2/payments`)**:
- URL versioning creates parallel controller hierarchies
- Header versioning allows same endpoint to serve multiple versions via conditional logic
- Stripe uses date-based versioning (`Stripe-Version: 2023-10-16`)

Phase 1 has a single version (`2026-03-01`). The interceptor plumbing is in place for future versions without refactoring.

### 4. Cross-Module Orchestration in Gateway

**Approach**: `RefundOrchestrationService` in gateway-api orchestrates the refund threshold check.

**Why in gateway-api, not in payment-orchestration or iam**:
- The threshold check crosses module boundaries (iam config + payment execution)
- Gateway-api already depends on both modules
- Keeps downstream modules (payment-orchestration, iam) focused on their bounded contexts
- Controllers remain thin — orchestration logic in a service

## Consequences

**Positive:**
- Stripe-familiar API surface (headers, error shapes, idempotency behavior)
- Rate limiting and idempotency use the same Valkey instance (no new infrastructure)
- Version plumbing wired — adding v2 requires no architectural changes

**Negative:**
- Rate limiter uses single config for all keys (no per-key tiers in Phase 1)
- Idempotency polling uses Thread.sleep (pins virtual thread carrier)
- Webhook endpoint delivery not yet implemented (endpoints stored but events not forwarded)
