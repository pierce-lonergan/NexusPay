package io.nexuspay.payment.application.screening;

/**
 * Server-set call-site identity carried into the {@link io.nexuspay.payment.application.port.PaymentGatewayPort}
 * so the {@link GatedPaymentGateway} derives the screening {@link ScreeningMode} and the tenant
 * from a TRUSTED ingress identity — NOT from client-shaped request metadata (B-029).
 *
 * <p><b>Why this exists.</b> Before B-029 the gate classified the screening mode via
 * {@code ScreeningMode.fromMetadata(request.metadata())} and read the tenant via
 * {@code metadata.get("tenant_id")}. Both are free-form, client-shaped values. A caller that
 * forwarded client metadata could therefore (a) claim the softer {@code SERVER_RECURRING}/
 * {@code SERVER_OTHER} rail to dodge the INTERACTIVE capture-hold, or (b) fragment fraud
 * velocity across fabricated tenant ids. Each real ingress (REST, billing, workflow) knows its
 * own trusted identity; it stamps that here via the typed factory, and the gate treats any
 * client-supplied {@code source}/{@code workflow}/{@code tenant_id} marker as advisory and
 * ignores it.</p>
 *
 * <p>A {@code null}/blank tenant is permitted (the geography resolver fails closed to a
 * mandatory review when it cannot resolve a tenant); it is never substituted with a
 * client-supplied value.</p>
 *
 * @param tenantId the TRUSTED tenant for this call (from the authenticated principal or a
 *                 server-side argument), or {@code null} when the ingress has none
 * @param mode     the screening rail this trusted ingress declares
 * @since B-029
 */
public record CallContext(String tenantId, ScreeningMode mode) {

    public CallContext {
        if (mode == null) {
            mode = ScreeningMode.INTERACTIVE;
        }
    }

    /** Customer/operator-initiated ingress (REST create/confirm, SDK checkout). */
    public static CallContext interactive(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.INTERACTIVE);
    }

    /** Billing/subscription/dunning charge — the soft recurring rail. */
    public static CallContext serverRecurring(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.SERVER_RECURRING);
    }

    /** Other server-initiated charge (e.g. a Temporal workflow activity) — the soft server rail. */
    public static CallContext serverOther(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.SERVER_OTHER);
    }

    /**
     * Strict default for any caller that did not declare a trusted identity: the strictest rail
     * (INTERACTIVE — fraud BLOCK rejects, REVIEW holds capture) with no tenant. Used by the port
     * {@code default} methods so a caller that has not been migrated to pass a {@code CallContext}
     * gets the safest behavior rather than a soft server rail.
     */
    public static CallContext strictDefault(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.INTERACTIVE);
    }
}
