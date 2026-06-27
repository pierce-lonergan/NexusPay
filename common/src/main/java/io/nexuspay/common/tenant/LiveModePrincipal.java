package io.nexuspay.common.tenant;

/**
 * TEST-2: module-portable view of the authenticated principal's KEY MODE.
 *
 * <p>Lives in {@code common} — like {@link TenantPrincipal} and {@link ScopedPrincipal} — so that
 * {@code :common}-only modules (dispute, marketplace, vault, …) can read whether the caller is a LIVE
 * or a TEST key WITHOUT importing the concrete {@code io.nexuspay.iam.domain.NexusPayPrincipal} (which
 * is off their compile classpath: those modules depend on {@code :common} only, not {@code :iam}).</p>
 *
 * <p>The iam {@code NexusPayPrincipal} {@code implements LiveModePrincipal} — its existing
 * {@code live()} record accessor already satisfies this contract with zero behaviour change (INT-3:
 * server-derived from the API key's {@code is_live}; {@code true} for JWT/OIDC and the default, and
 * {@code false} ONLY for an authenticated {@code sk_test_} key).</p>
 *
 * <p>Used by the TEST-mode-only control surface ({@code POST /v1/test/*}) to HARD-GATE on
 * {@code live() == false}: a live key must never reach a test-control endpoint (no production oracle).
 * Read it from the security context via {@link CallerMode}.</p>
 *
 * @since TEST-2
 */
public interface LiveModePrincipal {

    /**
     * @return {@code true} if this principal was authenticated by a LIVE key (or a non-API-key
     *         JWT/OIDC console actor); {@code false} ONLY for an authenticated {@code sk_test_} key.
     */
    boolean live();
}
