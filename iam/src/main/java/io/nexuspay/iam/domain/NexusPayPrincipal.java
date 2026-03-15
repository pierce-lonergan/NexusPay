package io.nexuspay.iam.domain;

/**
 * Uniform principal produced by both JWT (Keycloak) and API key authentication.
 * Available via SecurityContext in all downstream code.
 */
public record NexusPayPrincipal(
        String userId,
        String tenantId,
        String role,
        AuthMethod authMethod
) {
    public enum AuthMethod {
        JWT,
        API_KEY
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
}
