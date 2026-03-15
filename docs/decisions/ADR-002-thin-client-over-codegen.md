# ADR-002: Hand-Written Thin Client Over OpenAPI Codegen

**Status**: Accepted
**Date**: 2026-03-01
**Deciders**: Architecture team

## Context

NexusPay must communicate with HyperSwitch's REST API (~15 endpoints in Phase 1). HyperSwitch publishes an OpenAPI specification. We need to decide between:

1. **OpenAPI codegen** (`openapi-generator` with Spring WebClient or RestTemplate)
2. **Hand-written thin client** using Spring's `RestClient`

## Decision

Hand-write a thin REST client for the ~15 HyperSwitch endpoints we use, using Spring 6.1's `RestClient`.

## Rationale

**Against codegen:**
- HyperSwitch's OpenAPI spec has known `oneOf` polymorphism issues that break `openapi-generator`
- Generated code creates deep dependency trees and massive DTOs for fields we don't use
- Upgrade friction: regenerating after HyperSwitch releases risks breaking existing code
- Debugging generated code is significantly harder than hand-written code

**For thin client:**
- Only ~15 endpoints needed in Phase 1 (create, confirm, capture, cancel, get payment; create, get refund; health)
- DTOs are hand-mapped with `@JsonIgnoreProperties(ignoreUnknown = true)` — resilient to HyperSwitch adding new fields
- Full control over error handling, retry logic, and circuit breaker integration
- Easy to read, test, and debug
- RestClient (Spring 6.1+) is the modern synchronous HTTP client, simpler than WebClient for blocking calls

## Consequences

**Positive:**
- Clean, minimal DTOs with only the fields NexusPay uses
- No codegen toolchain in the build pipeline
- `@JsonIgnoreProperties` provides forward compatibility with API changes
- Easier onboarding for new developers

**Negative:**
- Manual effort to add new endpoints (low cost: ~30 min per endpoint)
- Must manually track HyperSwitch API changes (mitigated: versioned API, changelog monitoring)
- No automatic SDK updates when HyperSwitch releases

## Implementation Notes

- DTOs live in `payment-orchestration/adapter/out/hyperswitch/dto/` (adapter layer, NOT domain)
- `HyperSwitchResponseMapper` translates DTOs → domain objects at the boundary
- All external field names use `@JsonProperty("snake_case")` matching HyperSwitch conventions
- Domain objects use Java naming conventions (camelCase)
