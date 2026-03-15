# ADR-007: Dual Authentication — JWT + API Keys

**Status**: Accepted
**Date**: 2026-03-15
**Deciders**: Architecture team

## Context

NexusPay needs to support two authentication models:
1. **Interactive users** (dashboard, admin console) — SSO via Keycloak
2. **Programmatic access** (merchant integrations, CI/CD) — API keys

## Decision

Support both JWT (Keycloak OIDC) and Stripe-inspired API keys (`sk_test_`/`sk_live_`). Both produce a uniform `NexusPayPrincipal` record in the SecurityContext.

### Authentication flow:
1. `ApiKeyAuthenticationFilter` (`@Order(1)`) runs first
2. If token starts with `sk_` → authenticate via API key (bcrypt verify)
3. If not → fall through to Spring's `BearerTokenAuthenticationFilter` (JWT/Keycloak)
4. Both paths set `NexusPayPrincipal { userId, tenantId, role, authMethod }` in SecurityContext

### API key design:
- Format: `sk_test_{Base64URL(24 bytes)}` or `sk_live_{Base64URL(24 bytes)}`
- Storage: bcrypt hash + first 12 chars as prefix (for lookup)
- Full key shown once at creation, never retrievable again
- Each key has a role (admin/operator/viewer) and tenant_id

## Rationale

**Why Stripe-style API keys:**
- Industry standard for payment APIs — developers expect this model
- `sk_test_`/`sk_live_` prefix makes environment immediately visible
- Prefix-based lookup avoids full-table scan on every request
- bcrypt hash protects against database breach

**Why not OAuth2 client credentials only:**
- API keys are simpler for basic integrations
- No token refresh dance — key is the credential
- Keycloak client credentials still available for advanced use cases

**Why uniform NexusPayPrincipal:**
- Downstream code doesn't need to know which auth method was used
- Audit logging, RBAC, and tenant isolation work identically for both

## Consequences

**Positive:**
- Familiar API key model for payment industry
- Seamless dual auth without code changes in controllers
- bcrypt protects keys at rest

**Negative:**
- API keys don't expire automatically (must be manually revoked)
- No key rotation mechanism in Phase 1 (create new + revoke old)
- Key prefix lookup assumes unique first-12-chars (collision extremely unlikely with 24 random bytes)
