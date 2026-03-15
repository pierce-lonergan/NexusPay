package io.nexuspay.iam.adapter.in.filter;

import io.nexuspay.iam.application.ApiKeyService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API key authentication filter.
 * Runs BEFORE the JWT filter (@Order(1)).
 *
 * Checks if the Authorization: Bearer token starts with sk_ prefix.
 * If so, authenticates via ApiKeyService.
 * If not, falls through to the JWT (Keycloak) filter.
 *
 * Both paths produce a NexusPayPrincipal in the SecurityContext.
 */
@Component
@Order(1)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SK_PREFIX = "sk_";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());

            if (token.startsWith(SK_PREFIX)) {
                try {
                    NexusPayPrincipal principal = apiKeyService.authenticate(token);
                    if (principal != null) {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().toUpperCase()));
                        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        log.debug("API key authenticated: userId={}, role={}", principal.userId(), principal.role());
                    }
                } catch (Exception e) {
                    log.warn("API key authentication failed: {}", e.getMessage());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("""
                            {"error":{"type":"authentication_error","code":"invalid_api_key","message":"Invalid API key"}}""");
                    return;
                }
            }
            // If not sk_ prefix, fall through to JWT filter
        }

        filterChain.doFilter(request, response);
    }
}
