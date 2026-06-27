package io.nexuspay.common.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * TEST-2: resolves the caller's KEY MODE (live vs test) EXCLUSIVELY from the authenticated principal in
 * the Spring Security context — never from a client-supplied header or request body.
 *
 * <p>The {@code :common}-side companion to {@link CallerTenant}. It depends only on
 * {@link LiveModePrincipal} (in common) and {@code SecurityContextHolder} (spring-security-core, on
 * every module via spring-boot-starter-security), so a {@code :common}-only module (dispute) can read
 * the mode without importing the concrete iam {@code NexusPayPrincipal}.</p>
 *
 * <p>FAIL-CLOSED for the TEST-mode hard gate: when there is no {@link LiveModePrincipal} on the thread
 * (an unexpected principal type, an unauthenticated thread, …) {@link #isTest()} returns {@code false}
 * — i.e. NOT test — so a {@code POST /v1/test/*} endpoint that requires {@code isTest()} stays
 * unreachable. {@link #isLive()} defaults to {@code true} for the same reason.</p>
 *
 * @since TEST-2
 */
public final class CallerMode {

    private CallerMode() {
        // Utility class
    }

    /**
     * @return {@code true} only when the authenticated principal is a TEST key ({@code live() == false}).
     *         Fail-closed: any non-{@link LiveModePrincipal} / unauthenticated thread returns
     *         {@code false} (NOT test), keeping test-control endpoints closed.
     */
    public static boolean isTest() {
        return !isLive();
    }

    /**
     * @return {@code true} when the authenticated principal is a LIVE key (or there is no readable
     *         live-mode principal — the fail-closed default for the test gate).
     */
    public static boolean isLive() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof LiveModePrincipal principal) {
            return principal.live();
        }
        // No readable live-mode principal -> treat as LIVE so a test-only gate (requires isTest()) stays
        // closed. Authentication itself is enforced separately by the security filter chain.
        return true;
    }
}
