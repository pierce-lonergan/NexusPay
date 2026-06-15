package io.nexuspay.gateway.adapter.in.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-03 (clickjacking / hostile-embedder): the {@link CheckoutSecurityHeadersFilter}
 * protects the PAN-input {@code /v1/checkout*} endpoints. It must NEVER emit the
 * wildcard {@code frame-ancestors *} (any origin could clickjack the card field) nor
 * the no-op {@code X-Frame-Options: ALLOWALL}. Default scope is {@code 'self'}; an
 * explicit allowlist scopes to named merchant origins (still never a wildcard).
 */
class CheckoutSecurityHeadersFilterTest {

    private static MockHttpServletResponse runFilter(CheckoutSecurityHeadersFilter filter,
                                                     String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // doFilter() honors shouldNotFilter() internally (OncePerRequestFilter).
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void defaultsToFrameAncestorsSelf_andNeverWildcardOrAllowall() throws Exception {
        CheckoutSecurityHeadersFilter filter = new CheckoutSecurityHeadersFilter(List.of());

        MockHttpServletResponse response = runFilter(filter, "/v1/checkout/tokenize");

        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isEqualTo("frame-ancestors 'self'");
        // The exact SEC-03 failure modes must be gone.
        assertThat(csp).doesNotContain("*");
        assertThat(response.getHeader("X-Frame-Options"))
                .as("X-Frame-Options: ALLOWALL is a no-op and must not be emitted")
                .isNull();

        // Unrelated defensive headers stay.
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    void explicitAllowlistScopesToNamedOrigins_stillNotWildcard() throws Exception {
        CheckoutSecurityHeadersFilter filter = new CheckoutSecurityHeadersFilter(
                List.of("https://merchant-a.example", "https://merchant-b.example"));

        MockHttpServletResponse response = runFilter(filter, "/v1/checkout/session");

        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isEqualTo(
                "frame-ancestors 'self' https://merchant-a.example https://merchant-b.example");
        assertThat(csp).doesNotContain("*");
        assertThat(response.getHeader("X-Frame-Options")).isNull();
    }

    @Test
    void doesNotTouchNonCheckoutPaths() throws Exception {
        CheckoutSecurityHeadersFilter filter = new CheckoutSecurityHeadersFilter(List.of());

        MockHttpServletResponse response = runFilter(filter, "/v1/payments");

        // shouldNotFilter short-circuits non-/v1/checkout requests — no CSP added here.
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
    }
}
