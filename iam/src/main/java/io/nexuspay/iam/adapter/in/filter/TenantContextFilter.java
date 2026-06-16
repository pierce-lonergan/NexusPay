package io.nexuspay.iam.adapter.in.filter;

import io.nexuspay.common.mode.PaymentMode;
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
            // INT-3: belt-and-suspenders mode stamp for the JWT/OIDC (and any non-API-key) ingress that
            // the @Order(1) ApiKeyAuthenticationFilter did not stamp. Only set when STILL UNSET so the
            // API-key filter's already-stamped mode is never clobbered. A NexusPayPrincipal from JWT/OIDC
            // carries live=true by default, so a console actor is explicitly LIVE (not unset-fail-closed).
            if (PaymentMode.isUnset()) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                Object principal = auth != null ? auth.getPrincipal() : null;
                if (principal instanceof NexusPayPrincipal np) {
                    PaymentMode.set(np.live());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT_KEY);
            // INT-3: clear the request-scoped mode holder so it never leaks onto a pooled thread. Safe to
            // call unconditionally — clear() is a ThreadLocal.remove() (the API-key filter also clears
            // only what it set; a double clear is harmless).
            PaymentMode.clear();
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
