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
     * Helper to create a test authentication token for a specific role.
     *
     * <p>Hardcodes tenant {@code "default"} — kept for backward compatibility with
     * the existing integration tests. For cross-tenant attack scenarios (the
     * red-team suite) use {@link #authFor(String, String)} so the principal's
     * tenant can differ from the {@code X-Tenant-Id} header under attack.</p>
     */
    public static UsernamePasswordAuthenticationToken authForRole(String role) {
        return authFor("default", role);
    }

    /**
     * Helper to create a test authentication token for a specific TENANT and role.
     *
     * <p>Test-only (this is a {@code @TestConfiguration}, never on the main source
     * set or scanned by SAST). Used by the red-team cross-tenant IDOR suite to
     * authenticate as one tenant while sending an {@code X-Tenant-Id} header naming
     * a different (victim) tenant — the core IDOR vector. The principal's
     * {@code tenantId} is the authenticated identity; a secure system must derive
     * the effective tenant from THIS, not from a client-supplied header.</p>
     *
     * @param tenant the authenticated tenant the principal belongs to
     * @param role   the role granted (admin/operator/viewer)
     */
    public static UsernamePasswordAuthenticationToken authFor(String tenant, String role) {
        var principal = new NexusPayPrincipal(
                "test-" + role + "-user",
                tenant,
                role,
                NexusPayPrincipal.AuthMethod.JWT
        );
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
