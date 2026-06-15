package io.nexuspay.common.tenant;

/**
 * Minimal, module-portable view of an authenticated principal that carries a tenant.
 *
 * <p>Lives in {@code common} — like {@link io.nexuspay.common.rls.TenantWorkRunner} and
 * {@link io.nexuspay.common.rls.SystemTransactional} — so that {@code :common}-only modules
 * (marketplace, vault, b2b, fraud) can read the caller's tenant from the security context
 * WITHOUT importing the concrete {@code io.nexuspay.iam.domain.NexusPayPrincipal} (which is
 * off their compile classpath: those modules depend on {@code :common} only, not {@code :iam}).</p>
 *
 * <p>The iam {@code NexusPayPrincipal} {@code implements TenantPrincipal} (the dependency
 * direction iam→common is already legal), so the same object that Spring Security places in the
 * {@code SecurityContext} in production satisfies this contract with zero behaviour change.</p>
 *
 * @since SEC-BATCH-1
 */
public interface TenantPrincipal {

    /** The tenant the authenticated caller belongs to. Never null for an authenticated principal. */
    String tenantId();
}
