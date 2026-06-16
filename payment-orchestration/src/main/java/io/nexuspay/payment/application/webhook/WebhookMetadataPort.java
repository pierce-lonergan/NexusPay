package io.nexuspay.payment.application.webhook;

import java.util.Map;

/**
 * INT-1 read port for outbound-webhook merchant metadata. Defined in payment-orchestration (where the
 * implementing bean lives) so {@code gateway-api} — which already depends on payment-orchestration —
 * can inject the interface and read the server-owned correlation map at delivery time WITHOUT a new
 * cross-module wiring beyond this contract.
 *
 * <p>The write side ({@code WebhookMetadataService.record}) is NOT on this port: gateway-api only ever
 * reads. The single concrete implementation is {@code WebhookMetadataService}.</p>
 */
public interface WebhookMetadataPort {

    /**
     * Returns the stored merchant correlation metadata for a gateway payment id, or an empty map when
     * no row exists. Never returns {@code null}.
     *
     * <p>Tenant isolation is enforced at the APPLICATION layer, independent of the {@code rls.enforce}
     * flag: the caller passes the server-resolved delivery {@code tenant}, and the row is returned ONLY
     * when its stored {@code tenant_id} equals that tenant. A row owned by a different tenant (or a row
     * with a {@code null} tenant, which cannot prove ownership) yields {@code {}} — the safe direction,
     * mirroring {@code ScreeningOriginService.assertOwnedBy} (B-007). The RLS GUC binding remains a
     * defense-in-depth DB-row guard when {@code rls.enforce=true}, but is no longer the SOLE tenant
     * control on this read. Callers SHOULD still invoke it inside the resolved tenant's RLS scope.</p>
     */
    Map<String, Object> find(String gatewayPaymentId, String tenant);
}
