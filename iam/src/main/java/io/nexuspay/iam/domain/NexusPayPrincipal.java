package io.nexuspay.iam.domain;

import io.nexuspay.common.tenant.TenantPrincipal;

/**
 * Uniform principal produced by JWT (Keycloak), API key, or session token authentication.
 * Available via SecurityContext in all downstream code.
 *
 * <p>Session-scoped principals (from checkout SDK) include a {@code sessionId} that
 * restricts them to operations on a single payment session.
 *
 * <p>Implements {@link TenantPrincipal} (a {@code common} contract) so that {@code :common}-only
 * modules can read the caller's tenant via {@code CallerTenant.require()} without importing this
 * iam type. The {@code tenantId()} record accessor already satisfies the interface — zero
 * behaviour change.
 */
public record NexusPayPrincipal(
        String userId,
        String tenantId,
        String role,
        AuthMethod authMethod,
        String sessionId
) implements TenantPrincipal {
    /**
     * Constructor without sessionId (backward-compatible for JWT and API key auth).
     */
    public NexusPayPrincipal(String userId, String tenantId, String role, AuthMethod authMethod) {
        this(userId, tenantId, role, authMethod, null);
    }

    public enum AuthMethod {
        JWT,
        API_KEY,
        SESSION_TOKEN
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    public boolean isOperator() {
        return "operator".equals(role);
    }

    public boolean isViewer() {
        return "viewer".equals(role);
    }

    /**
     * Returns {@code true} if this principal was authenticated via a session token
     * and is scoped to a specific payment session.
     *
     * @since 0.3.5 (Sprint 3.5)
     */
    public boolean isSessionScoped() {
        return authMethod == AuthMethod.SESSION_TOKEN && sessionId != null;
    }
}
