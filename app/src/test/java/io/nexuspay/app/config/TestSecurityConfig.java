package io.nexuspay.app.config;

import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test security configuration that provides a mock JWT decoder.
 * Allows integration tests to bypass Keycloak.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        // Return a mock decoder that always succeeds with a test admin user
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "test-admin-user")
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    /**
     * Helper to create a test authentication token for a specific role on the "default" tenant.
     */
    public static UsernamePasswordAuthenticationToken authForRole(String role) {
        return authForRole(role, "default");
    }

    /**
     * Helper to create a test authentication token for a specific role and tenant. Because
     * {@link NexusPayPrincipal} implements {@code common.TenantPrincipal}, {@code CallerTenant.require()}
     * resolves this principal's tenant in tests exactly as in production — letting integration tests
     * exercise cross-tenant isolation (caller tenant A, resource tenant B).
     *
     * @since SEC-BATCH-1
     */
    public static UsernamePasswordAuthenticationToken authForRole(String role, String tenantId) {
        var principal = new NexusPayPrincipal(
                "test-" + role + "-user",
                tenantId,
                role,
                NexusPayPrincipal.AuthMethod.JWT
        );
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
