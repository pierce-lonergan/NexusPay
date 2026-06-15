package io.nexuspay.common.tenant;

import io.nexuspay.common.exception.AuthorizationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the caller's tenant EXCLUSIVELY from the authenticated principal in the Spring Security
 * context — never from a client-supplied header.
 *
 * <p>This is the controller-side tenant source for {@code :common}-only modules. It is semantically
 * identical to the iam {@code TenantContextFilter.extractTenantId()} (which reads
 * {@code NexusPayPrincipal.tenantId()}), but callable from {@code common} because it depends only on
 * {@link TenantPrincipal} (in common) and {@code SecurityContextHolder} (spring-security-core, on
 * every module via spring-boot-starter-security).</p>
 *
 * <p>Intended for HTTP request threads only. On a thread with no {@link TenantPrincipal} in the
 * security context (e.g. a scheduler thread, or a test using {@code @WithMockUser}), it throws
 * {@link AuthorizationException} rather than silently defaulting a tenant. Do NOT call this from
 * cross-tenant system jobs (those use the {@code SystemTransactional}/{@code TenantWorkRunner}
 * mechanism instead).</p>
 *
 * @since SEC-BATCH-1
 */
public final class CallerTenant {

    private CallerTenant() {
        // Utility class
    }

    /**
     * Returns the authenticated caller's tenant id.
     *
     * @throws AuthorizationException if there is no authenticated {@link TenantPrincipal} on the
     *         current thread, or its tenant id is blank.
     */
    public static String require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof TenantPrincipal principal) {
            String tenantId = principal.tenantId();
            if (tenantId != null && !tenantId.isBlank()) {
                return tenantId;
            }
        }
        throw AuthorizationException.forbidden("resolve tenant from authenticated principal");
    }
}
