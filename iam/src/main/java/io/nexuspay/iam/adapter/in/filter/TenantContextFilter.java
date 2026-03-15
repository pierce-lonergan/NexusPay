package io.nexuspay.iam.adapter.in.filter;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts the tenant ID from the authenticated principal and sets it in
 * {@link TenantContext} and SLF4J MDC for downstream use.
 *
 * <p>Runs after authentication filters (API key @Order(1), JWT) but before
 * any business logic. The tenant ID flows into:</p>
 * <ul>
 *     <li>{@link TenantContext} — read by {@code TenantAwareConnectionProvider}
 *         to inject {@code SET LOCAL app.current_tenant_id} per transaction</li>
 *     <li>MDC — for structured logging correlation</li>
 * </ul>
 *
 * <p>For unauthenticated endpoints (actuator, webhooks, swagger), the filter
 * is a no-op — no tenant context is set, and the database connection will use
 * the superuser role which bypasses RLS.</p>
 *
 * @since 0.2.0 (Sprint 2.1)
 */
@Component
@Order(2)  // After ApiKeyAuthenticationFilter (@Order(1)), after JWT filter
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);
    private static final String MDC_TENANT_KEY = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        try {
            String tenantId = extractTenantId();
            if (tenantId != null) {
                TenantContext.set(tenantId);
                MDC.put(MDC_TENANT_KEY, tenantId);
                log.trace("Tenant context set: {}", tenantId);
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT_KEY);
        }
    }

    /**
     * Extracts tenant ID from the SecurityContext principal.
     * Both API key and JWT authentication produce a {@link NexusPayPrincipal}
     * with a tenantId field.
     *
     * @return tenant ID or null if unauthenticated
     */
    private String extractTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }

        Object principal = auth.getPrincipal();
        if (principal instanceof NexusPayPrincipal nexusPrincipal) {
            return nexusPrincipal.tenantId();
        }

        // For other authentication types (e.g., anonymous), no tenant context
        return null;
    }

    /**
     * Skip tenant context for paths that are always unauthenticated.
     * This prevents unnecessary SecurityContext lookups.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/internal/")
                || path.startsWith("/v1/api-docs")
                || path.startsWith("/v1/swagger-ui");
    }
}
