package io.nexuspay.iam.adapter.in.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.iam.application.ApiKeyService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-3 (L-044): the request-scoped {@link PaymentMode} holder MUST be SET during the chain for an
 * authenticated key/principal and CLEARED in the filter's {@code finally} after the chain returns — the
 * clear is the ONLY thing preventing a TEST key's mode from leaking onto the next request served by a
 * pooled thread.
 *
 * <p>Each assertion fails if the corresponding {@code PaymentMode.set/clear} is removed from the filter:
 * <ul>
 *   <li>{@link ApiKeyAuthenticationFilter}: stamps {@code is_live} during the chain, clears after (and
 *       on the auth-failure 401 early-return);</li>
 *   <li>{@link TenantContextFilter}: belt-and-suspenders stamp for a JWT/OIDC principal (defaults LIVE),
 *       cleared unconditionally in finally.</li>
 * </ul>
 */
class FilterPaymentModeLifecycleTest {

    private ApiKeyService apiKeyService;
    private ApiKeyAuthenticationFilter apiKeyFilter;
    private TenantContextFilter tenantFilter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        apiKeyFilter = new ApiKeyAuthenticationFilter(apiKeyService, new ObjectMapper());
        tenantFilter = new TenantContextFilter();
        // Start from a known-clean holder so a probe during the chain is unambiguous.
        PaymentMode.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        PaymentMode.clear();
        SecurityContextHolder.clearContext();
    }

    private static NexusPayPrincipal testKeyPrincipal() {
        // is_live=false -> a TEST principal (sk_test_).
        return new NexusPayPrincipal("key_1", "t1", "operator",
                NexusPayPrincipal.AuthMethod.API_KEY, null, false);
    }

    @Test
    void apiKeyFilter_setsTestModeDuringChain_andClearsAfter() throws Exception {
        when(apiKeyService.authenticate(anyString())).thenReturn(testKeyPrincipal());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer sk_test_validkey");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Boolean> testDuringChain = new AtomicReference<>();
        FilterChain probe = (req, res) -> testDuringChain.set(PaymentMode.isTestExplicit());

        apiKeyFilter.doFilterInternal(request, response, probe);

        // DURING the chain the sk_test_ mode is in effect (fails if PaymentMode.set is removed).
        assertThat(testDuringChain.get()).as("test mode set during chain").isTrue();
        // AFTER the chain the holder is cleared (fails if the finally clear is removed -> leak).
        assertThat(PaymentMode.isUnset()).as("holder cleared after chain").isTrue();
    }

    @Test
    void apiKeyFilter_clearsMode_onAuthFailureEarlyReturn() throws Exception {
        when(apiKeyService.authenticate(anyString())).thenThrow(AuthorizationException.invalidApiKey());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer sk_test_bogus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        apiKeyFilter.doFilterInternal(request, response, mock(FilterChain.class));

        // 401 written, no mode leaked (the catch returns inside the try; the finally still runs).
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(PaymentMode.isUnset()).as("no mode leaked on the 401 path").isTrue();
    }

    @Test
    void apiKeyFilter_noAuthHeader_leavesHolderUnset_andClears() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Boolean> unsetDuringChain = new AtomicReference<>();
        FilterChain probe = (req, res) -> unsetDuringChain.set(PaymentMode.isUnset());

        apiKeyFilter.doFilterInternal(request, response, probe);

        // No key -> this filter never sets a mode; the holder stays unset and is not corrupted.
        assertThat(unsetDuringChain.get()).isTrue();
        assertThat(PaymentMode.isUnset()).isTrue();
    }

    @Test
    void tenantContextFilter_jwtPrincipal_setsLiveDuringChain_andClearsAfter() throws Exception {
        // A JWT/OIDC console principal defaults to LIVE; the ApiKeyAuthenticationFilter did not run, so
        // PaymentMode is UNSET when TenantContextFilter's belt-and-suspenders stamp fires.
        var jwt = new NexusPayPrincipal("u", "t1", "admin", NexusPayPrincipal.AuthMethod.JWT);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                jwt, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Boolean> liveDuringChain = new AtomicReference<>();
        FilterChain probe = (req, res) -> liveDuringChain.set(PaymentMode.isLiveExplicit());

        tenantFilter.doFilterInternal(request, response, probe);

        assertThat(liveDuringChain.get()).as("JWT/OIDC actor is LIVE during chain").isTrue();
        assertThat(PaymentMode.isUnset()).as("TenantContextFilter clears the holder in finally").isTrue();
    }

    @Test
    void tenantContextFilter_doesNotClobberAlreadySetTestMode_andStillClears() throws Exception {
        // Simulate the API-key filter having already stamped TEST mode before TenantContextFilter runs.
        PaymentMode.set(false);
        var principal = testKeyPrincipal();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Boolean> testDuringChain = new AtomicReference<>();
        FilterChain probe = (req, res) -> testDuringChain.set(PaymentMode.isTestExplicit());

        tenantFilter.doFilterInternal(request, response, probe);

        // The already-set TEST mode must NOT be clobbered to LIVE by the belt-and-suspenders branch.
        assertThat(testDuringChain.get()).as("pre-set TEST mode preserved through chain").isTrue();
        // TenantContextFilter still clears unconditionally in finally.
        assertThat(PaymentMode.isUnset()).as("holder cleared after chain").isTrue();
    }
}
