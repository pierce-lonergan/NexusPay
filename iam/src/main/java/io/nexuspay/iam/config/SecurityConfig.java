package io.nexuspay.iam.config;

import io.nexuspay.iam.adapter.in.filter.ApiKeyAuthenticationFilter;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Security configuration with dual authentication:
 * 1. API key (sk_test_/sk_live_) — handled by ApiKeyAuthenticationFilter
 * 2. JWT (Keycloak OIDC) — handled by Spring's oauth2ResourceServer
 *
 * Both produce a NexusPayPrincipal in the SecurityContext.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    public SecurityConfig(ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) {
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthenticationFilter, BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
                        .requestMatchers("/internal/webhooks/**").permitAll()
                        .requestMatchers("/v1/api-docs/**").permitAll()
                        .requestMatchers("/v1/swagger-ui/**").permitAll()
                        // Checkout endpoints use session token auth (handled by SessionTokenAuthenticationFilter)
                        .requestMatchers("/v1/checkout/**").permitAll()
                        // All other endpoints require authentication
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        // sk_/pk_ API-key tokens are authenticated by ApiKeyAuthenticationFilter (which runs
                        // first). Without this, the JWT resource-server's default resolver ALSO grabs the
                        // "Bearer sk_..." token, fails to decode it as a JWT, CLEARS the already-set api-key
                        // authentication, and 401s — so every api-key request died after authenticating.
                        // Skip api-key tokens here so the api-key auth stands; only real JWTs reach the decoder.
                        .bearerTokenResolver(apiKeyAwareBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter()))
                );

        return http.build();
    }

    /**
     * A {@link BearerTokenResolver} that returns {@code null} for API-key tokens (sk_/pk_ prefix) so the
     * OAuth2 resource server (JWT) leaves them alone — they are handled by {@link ApiKeyAuthenticationFilter}.
     * Any other Bearer token is resolved normally and validated as a Keycloak JWT.
     */
    private BearerTokenResolver apiKeyAwareBearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String token = delegate.resolve(request);
            if (token != null && (token.startsWith("sk_") || token.startsWith("pk_"))) {
                return null; // API-key token — not a JWT; ApiKeyAuthenticationFilter owns it.
            }
            return token;
        };
    }

    /**
     * Converts Keycloak JWT to NexusPayPrincipal.
     * Extracts roles from realm_access.roles claim.
     */
    private Converter<Jwt, AbstractAuthenticationToken> keycloakJwtConverter() {
        return jwt -> {
            String userId = jwt.getSubject();
            String tenantId = jwt.getClaimAsString("tenant_id") != null
                    ? jwt.getClaimAsString("tenant_id") : "default";

            // Extract roles from realm_access.roles
            List<String> roles = extractRealmRoles(jwt);
            String primaryRole = roles.stream()
                    .filter(r -> List.of("admin", "operator", "viewer").contains(r))
                    .findFirst()
                    .orElse("viewer");

            var principal = new NexusPayPrincipal(userId, tenantId, primaryRole,
                    NexusPayPrincipal.AuthMethod.JWT);

            // Lowercase to match the case-sensitive hasRole('admin'|'operator'|...) used by every
            // @PreAuthorize (Spring checks the literal authority "ROLE_admin"). toUpperCase produced
            // "ROLE_ADMIN", which never matched → 403 for every real Keycloak admin/operator principal.
            Collection<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toLowerCase()))
                    .collect(Collectors.toList());

            return new UsernamePasswordAuthenticationToken(principal, jwt, authorities);
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return List.of();
        Object roles = realmAccess.get("roles");
        if (roles instanceof List<?> roleList) {
            return roleList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
