package io.nexuspay.gateway.adapter.in.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Adds security headers for checkout/SDK endpoints.
 *
 * <p><strong>SEC-03 residual (clickjacking / hostile-embedder hardening).</strong> The
 * card-input endpoints under {@code /v1/checkout*} accept the raw PAN, so who may FRAME
 * them is a security boundary. This filter previously set {@code Content-Security-Policy:
 * frame-ancestors *} (any origin could embed the PAN field and overlay/clickjack it) plus
 * the non-standard {@code X-Frame-Options: ALLOWALL} (a no-op — modern browsers honor only
 * {@code DENY}/{@code SAMEORIGIN} for that header — that merely advertised a false intent).
 * Both are removed.</p>
 *
 * <p>The {@code frame-ancestors} directive is now SCOPED:</p>
 * <ul>
 *   <li>If {@code nexuspay.checkout.frame-ancestors} is configured with an explicit
 *       merchant-origin allowlist, those origins are emitted verbatim — the per-deployment
 *       allowlist of domains permitted to embed the checkout iframe.</li>
 *   <li>Otherwise it defaults to {@code 'self'} — the card iframe is only embeddable from a
 *       NexusPay-hosted checkout page. This is the safe conservative default: it never
 *       widens to a wildcard, so an unconfigured deployment fails closed rather than open.</li>
 * </ul>
 *
 * <p>A future per-tenant/per-session merchant-origin allowlist (driven from the session
 * config) can replace the static property without changing this filter's contract — the
 * point is that the directive is never {@code *}.</p>
 *
 * <p>Other headers: {@code X-Content-Type-Options: nosniff},
 * {@code Referrer-Policy: strict-origin-when-cross-origin}.</p>
 *
 * @since 0.3.5 (Sprint 3.5); frame-ancestors scoped + ALLOWALL removed in SEC-BATCH-3 (SEC-03)
 */
@Component
@Order(5)
public class CheckoutSecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CHECKOUT_PATH_PREFIX = "/v1/checkout";

    /**
     * Merchant origins allowed to frame the checkout iframe. Empty (the default) means
     * {@code frame-ancestors 'self'} — only NexusPay-hosted pages may embed it. Never a
     * wildcard.
     */
    private final String frameAncestors;

    public CheckoutSecurityHeadersFilter(
            @Value("${nexuspay.checkout.frame-ancestors:}") List<String> frameAncestorOrigins) {
        this.frameAncestors = buildFrameAncestors(frameAncestorOrigins);
    }

    private static String buildFrameAncestors(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            // Safe conservative default: only same-origin (NexusPay-hosted checkout) may frame.
            return "'self'";
        }
        // Explicit merchant-origin allowlist. 'self' is always retained so the
        // NexusPay-hosted checkout page itself keeps working.
        StringBuilder sb = new StringBuilder("'self'");
        for (String origin : origins) {
            if (origin != null && !origin.isBlank()) {
                sb.append(' ').append(origin.trim());
            }
        }
        return sb.toString();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHECKOUT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        // SEC-03: scoped frame-ancestors (never "*"); X-Frame-Options: ALLOWALL removed
        // (it was a no-op that advertised a false intent).
        response.setHeader("Content-Security-Policy", "frame-ancestors " + frameAncestors);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        filterChain.doFilter(request, response);
    }
}
