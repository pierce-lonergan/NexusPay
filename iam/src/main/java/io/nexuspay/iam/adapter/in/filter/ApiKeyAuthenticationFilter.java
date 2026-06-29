package io.nexuspay.iam.adapter.in.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.iam.application.ApiKeyService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

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

    private static final String MDC_REQUEST_ID = "request_id";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        // INT-3: track whether THIS filter stamped the request-scoped PaymentMode holder so we clear
        // ONLY what we set, in a finally that wraps the rest of the chain — the holder must never leak
        // onto the next request served by this pooled thread.
        boolean modeSet = false;
        try {
            if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                String token = authHeader.substring(BEARER_PREFIX.length());

                if (token.startsWith(SK_PREFIX)) {
                    try {
                        NexusPayPrincipal principal = apiKeyService.authenticate(token);
                        if (principal != null) {
                            // Roles are stored/checked lowercase (DB chk_api_key_role + every @PreAuthorize
                            // uses hasRole('admin'|'operator'|...) → Spring expects the case-sensitive
                            // authority "ROLE_admin"). Normalize to lowercase so a real key actually
                            // authorizes (toUpperCase produced "ROLE_ADMIN", which hasRole('admin') never
                            // matched → 403 on every role-gated endpoint).
                            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().toLowerCase()));
                            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                            // INT-3: stamp the SERVER-DERIVED mode from the authenticated key's is_live. A
                            // sk_test_ key sets live=false -> payment ops on this request route to the mock.
                            PaymentMode.set(principal.live());
                            modeSet = true;
                            log.debug("API key authenticated: userId={}, role={}", principal.userId(), principal.role());
                        }
                    } catch (Exception e) {
                        log.warn("API key authentication failed: {}", e.getMessage());
                        // INT-2: emit the stable error envelope via common's ApiError/ApiErrorResponse so the
                        // 401 body stays in lock-step with the rest of the contract (type=unauthorized,
                        // code=invalid_api_key, request_id from the correlation MDC with a UUID fallback).
                        String rid = MDC.get(MDC_REQUEST_ID);
                        if (rid == null || rid.isBlank()) {
                            rid = UUID.randomUUID().toString();
                        }
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        objectMapper.writeValue(response.getOutputStream(),
                                ApiErrorResponse.of(ApiError.of(ApiError.TYPE_UNAUTHORIZED,
                                        "invalid_api_key", "Invalid API key", rid)));
                        return;
                    }
                }
                // If not sk_ prefix, fall through to JWT filter
            }

            filterChain.doFilter(request, response);
        } finally {
            if (modeSet) {
                PaymentMode.clear();
            }
        }
    }
}
