package io.nexuspay.iam.adapter.in.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import io.nexuspay.common.domain.SessionToken;
import io.nexuspay.iam.application.service.SessionTokenIssuer;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.iam.domain.TenantContext;
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
 * Authenticates checkout SDK requests using session-scoped JWTs.
 *
 * <p>Runs at {@code @Order(0)} — before {@link ApiKeyAuthenticationFilter} (Order 1).
 * Only activates on {@code /v1/checkout/**} paths. For all other paths, the filter
 * is a no-op and falls through to the API key or JWT filters.
 *
 * <p>Extracts the Bearer token, validates it as a session JWT via
 * {@link SessionTokenIssuer}, and creates a {@link NexusPayPrincipal} with
 * {@code AuthMethod.SESSION_TOKEN} and the session's role set to "session".
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Component
@Order(0)
public class SessionTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CHECKOUT_PATH_PREFIX = "/v1/checkout";

    private static final String MDC_REQUEST_ID = "request_id";

    private final SessionTokenIssuer sessionTokenIssuer;
    private final ObjectMapper objectMapper;

    public SessionTokenAuthenticationFilter(SessionTokenIssuer sessionTokenIssuer, ObjectMapper objectMapper) {
        this.sessionTokenIssuer = sessionTokenIssuer;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHECKOUT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        // Skip API keys — they start with sk_
        if (token.startsWith("sk_")) {
            filterChain.doFilter(request, response);
            return;
        }

        SessionToken sessionToken = sessionTokenIssuer.validateSessionToken(token);
        if (sessionToken == null) {
            sendUnauthorized(response, "Invalid or expired session token");
            return;
        }

        // Create session-scoped principal
        var principal = new NexusPayPrincipal(
                sessionToken.sessionId(),   // userId = sessionId for session-scoped auth
                sessionToken.tenantId(),
                "session",
                NexusPayPrincipal.AuthMethod.SESSION_TOKEN,
                sessionToken.sessionId()
        );

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_SESSION"));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Set tenant context for RLS
        TenantContext.set(sessionToken.tenantId());

        log.debug("Session token authenticated: session={}, tenant={}",
                sessionToken.sessionId(), sessionToken.tenantId());

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        // INT-2: emit the stable error envelope via common's ApiError/ApiErrorResponse so the session
        // 401 body stays in lock-step with the rest of the contract (type=unauthorized,
        // code=invalid_session_token, request_id from the correlation MDC with a UUID fallback).
        // Mirrors ApiKeyAuthenticationFilter. Serializing via ObjectMapper also JSON-escapes the
        // message rather than concatenating it into a raw string.
        String rid = MDC.get(MDC_REQUEST_ID);
        if (rid == null || rid.isBlank()) {
            rid = UUID.randomUUID().toString();
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(ApiError.of(ApiError.TYPE_UNAUTHORIZED,
                        "invalid_session_token", message, rid)));
    }
}
