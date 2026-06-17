package io.nexuspay.common.api;

/**
 * The single canonical NexusPay API CONTRACT version (DX-5e).
 *
 * <p>NexusPay uses date-based contract versioning (Stripe-shaped). There is exactly ONE supported
 * contract version today; this constant is its single source of truth so the value can never disagree
 * across the surfaces that expose it:</p>
 * <ul>
 *   <li>the {@code api_version} field stamped into every canonical webhook envelope
 *       ({@code WebhookEnvelopeSerializer}); and</li>
 *   <li>the {@code X-API-Version} request header default echoed back by {@code ApiVersionInterceptor}.</li>
 * </ul>
 *
 * <p>Before DX-5e these two were hard-coded to DIFFERENT dates (the envelope said {@code 2026-06-16}
 * while the request default said {@code 2026-03-01}) — a confusing, meaningless disagreement, since only
 * one contract version actually exists. They now both reference {@link #CURRENT}.</p>
 *
 * <p>This date is the API CONTRACT version. It is NOT the SDK/library version: the {@code @nexus-pay/*}
 * npm packages carry their own independent semver ({@code 0.1.x}), which tracks client-library releases,
 * not the wire contract. The two are orthogonal and intentionally distinct.</p>
 */
public final class ApiVersion {

    /** The one supported API contract version (date-based). */
    public static final String CURRENT = "2026-06-16";

    private ApiVersion() {
    }
}
