package io.nexuspay.common.tenant;

/**
 * DX-5c-ii: module-portable contract for a principal that MAY carry API scopes.
 *
 * <p>Lives in {@code common} (alongside {@link TenantPrincipal}) so that {@code :common}-only modules
 * (marketplace, vault, dispute, …) can enforce per-key scopes via the {@code @scopeAuth} bean WITHOUT
 * importing the concrete {@code io.nexuspay.iam.domain.NexusPayPrincipal} (which is off their compile
 * classpath — those modules depend on {@code :common} only, not {@code :iam}).</p>
 *
 * <p>The iam {@code NexusPayPrincipal} {@code implements ScopedPrincipal}, so the same object Spring
 * Security places in the {@code SecurityContext} in production satisfies this contract with zero
 * behaviour change. {@link ScopeSecurity} reads this interface from the context to make the fail-closed
 * scope decision.</p>
 *
 * @since DX-5c-ii
 */
public interface ScopedPrincipal {

    /**
     * FAIL-CLOSED scope check. Returns {@code true} when this principal is UNRESTRICTED (carries no
     * scopes — the back-compat / role-based case) OR explicitly carries {@code scope}; returns
     * {@code false} for a RESTRICTED principal that lacks the scope. Scopes NARROW a role, never widen it.
     *
     * @param scope the required scope (a member of the {@code io.nexuspay.common.api.ApiScope} vocabulary)
     * @return {@code true} if the operation is permitted by this principal's scope set
     */
    boolean hasScope(String scope);
}
