# RFC B-003 — Enforce fraud + cross-border compliance in the payment path

CONFIDENCE: high (confirmed zero callers in the audit). T3 security/architecture.

## Problem
`AssessFraudRiskUseCase.assess()` and `CrossBorderComplianceService.validateOrThrow()`
are fully built but have NO callers in the money path: `PaymentController.createPayment`
→ `PaymentGatewayPort` (HyperSwitch) never consults them. A BLOCK decision or a
sanctioned-country payment is therefore processed normally. The protective modules
are advisory side-cars, not gates.

## Recommended design (smallest correct gate)
Insert a synchronous pre-authorization gate in the payment create/confirm flow,
BEFORE `PaymentGatewayPort.createPayment`:
1. Build a `PaymentContext` (amount, currency, source/destination country, card
   metadata, ip_country if available) from the request + principal.
2. `CrossBorderComplianceService.validateOrThrow(...)` → reject (`cross_border_blocked`,
   HTTP 403) on sanctioned source/dest; attach reporting/EDD flags otherwise.
3. `AssessFraudRiskUseCase.assess(...)`:
   - BLOCK → reject the payment (e.g. 402/422, `fraud_blocked`), no PSP call.
   - REVIEW → create the payment but HOLD capture (do not auto-capture); surface a
     pending fraud assessment (the fraud REVIEW pipeline already exists).
   - ALLOW → proceed.

## Module-boundary impact (must resolve before coding)
`gateway` would need to call `fraud` and `payment`'s FX/compliance services. Today
the modulith allows gateway → {common, payment, ledger, iam}, NOT fraud. Two options:
- **(A) Direct dependency:** add `fraud` to gateway's allowed deps + a
  `FraudGatePort`/`CompliancePort` in gateway's application layer implemented by
  adapters delegating to the fraud/payment services. Simple, synchronous, testable.
- **(B) Orchestrate in payment-orchestration:** move the gate into the payment
  create use case (payment already may depend on fraud/compliance), keeping gateway
  thin. Cleaner layering; bigger move.
Recommend (A) for the first cut (smallest diff, synchronous, unit-testable with
mocked ports), revisit (B) at a later architecture pass.

## Verification
Integration test: a payment with a sanctioned destination country is rejected
(403, no PSP call); a fraud-BLOCK context is rejected; a REVIEW context creates a
payment with capture held; a clean payment proceeds. Mockable at unit level
(ports), plus an app-level IT once wired.

## Dependencies / sequencing
Independent of RLS (B-002) and Flyway (B-011). Should land with the fraud REVIEW
state-machine guards (already partly present). Effort: M (multi-file, boundary
change) — own branch, dual review (T3). This is the highest-value SECURITY item
after RLS.
