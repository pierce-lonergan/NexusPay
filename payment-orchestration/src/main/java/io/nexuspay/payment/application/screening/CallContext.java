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
 * <p><b>DX-5a — durable test/live mode.</b> {@code live} carries the DURABLE test/live mode for a
 * server-initiated charge whose execution thread has no request-scoped {@code PaymentMode}
 * (renewals/dunning run on a {@code @Scheduled @SystemTransactional} SYSTEM thread). It is a
 * tri-state: {@code Boolean.TRUE} = a LIVE subscription (route to the real PSP); {@code
 * Boolean.FALSE} = a TEST subscription (route to the mock — a recurring TEST charge MUST NEVER hit
 * the real PSP, the CHARTER guarantee); {@code null} = INDETERMINATE (the existing call sites that
 * cannot resolve a durable mode), in which case the gateway falls back to the request/system-thread
 * {@code PaymentMode} heuristic — i.e. {@code null} preserves the pre-DX-5a behavior exactly. The
 * existing factories all default {@code live=null}, so every legacy construction site and the port
 * {@code default} methods stay byte-identical; callers that CAN resolve the durable mode use the
 * {@code live}-accepting overloads.</p>
 *
 * @param tenantId the TRUSTED tenant for this call (from the authenticated principal or a
 *                 server-side argument), or {@code null} when the ingress has none
 * @param mode     the screening rail this trusted ingress declares
 * @param live     DX-5a durable test/live mode for a system-thread charge: {@code TRUE}=live,
 *                 {@code FALSE}=test, {@code null}=indeterminate (defer to {@code PaymentMode})
 * @since B-029 (live field added DX-5a)
 */
public record CallContext(String tenantId, ScreeningMode mode, Boolean live) {

    public CallContext {
        if (mode == null) {
            mode = ScreeningMode.INTERACTIVE;
        }
    }

    /**
     * Back-compat 2-arg constructor: a {@code null} (indeterminate) durable mode, so every existing
     * {@code new CallContext(tenantId, mode)} site stays source-compatible and routes via the
     * {@code PaymentMode} heuristic exactly as before DX-5a.
     */
    public CallContext(String tenantId, ScreeningMode mode) {
        this(tenantId, mode, null);
    }

    /** Customer/operator-initiated ingress (REST create/confirm, SDK checkout). Durable mode {@code null}. */
    public static CallContext interactive(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.INTERACTIVE, null);
    }

    /** Billing/subscription/dunning charge — the soft recurring rail. Durable mode {@code null}. */
    public static CallContext serverRecurring(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.SERVER_RECURRING, null);
    }

    /**
     * DX-5a: billing/subscription/dunning charge that DECLARES its durable test/live mode (from the
     * subscription's {@code is_live}), so a system-thread recurring charge for a TEST subscription
     * routes to the mock even though the request-scoped {@code PaymentMode} is unset.
     */
    public static CallContext serverRecurring(String tenantId, Boolean live) {
        return new CallContext(tenantId, ScreeningMode.SERVER_RECURRING, live);
    }

    /** Other server-initiated charge (e.g. a Temporal workflow activity) — the soft server rail. Durable mode {@code null}. */
    public static CallContext serverOther(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.SERVER_OTHER, null);
    }

    /**
     * DX-5a: other server-initiated charge that DECLARES its durable test/live mode, for any future
     * system-thread caller that can resolve it (e.g. a Temporal activity carrying the durable mode).
     */
    public static CallContext serverOther(String tenantId, Boolean live) {
        return new CallContext(tenantId, ScreeningMode.SERVER_OTHER, live);
    }

    /**
     * Strict default for any caller that did not declare a trusted identity: the strictest rail
     * (INTERACTIVE — fraud BLOCK rejects, REVIEW holds capture) with no tenant. Used by the port
     * {@code default} methods so a caller that has not been migrated to pass a {@code CallContext}
     * gets the safest behavior rather than a soft server rail. Durable mode {@code null} (defer to
     * the {@code PaymentMode} heuristic — which fail-closes a request thread to the mock).
     */
    public static CallContext strictDefault(String tenantId) {
        return new CallContext(tenantId, ScreeningMode.INTERACTIVE, null);
    }
}
