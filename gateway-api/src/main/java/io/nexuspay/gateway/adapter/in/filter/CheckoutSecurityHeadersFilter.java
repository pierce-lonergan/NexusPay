package io.nexuspay.gateway.adapter.in.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds security headers for checkout/SDK endpoints.
 *
 * <p>Key headers:
 * <ul>
 *   <li>{@code Content-Security-Policy: frame-ancestors *} — allows SDK iframe embedding from any merchant domain</li>
 *   <li>{@code X-Frame-Options: ALLOWALL} — legacy browser support for iframe embedding</li>
 *   <li>{@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}</li>
 * </ul>
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Component
@Order(5)
public class CheckoutSecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CHECKOUT_PATH_PREFIX = "/v1/checkout";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHECKOUT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", "frame-ancestors *");
        response.setHeader("X-Frame-Options", "ALLOWALL");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        filterChain.doFilter(request, response);
    }
}
