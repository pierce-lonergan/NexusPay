package io.nexuspay.iam.adapter.in.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.iam.application.service.SessionTokenIssuer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-2: a missing/invalid session token produces the stable 401 error envelope
 * {@code { error: { type:"unauthorized", code:"invalid_session_token", message, request_id } }}.
 *
 * <p>Mirrors {@link ApiKeyAuthFilter401EnvelopeTest}. FAILS if the filter's 401 body regresses to the
 * retired {@code authentication_error} taxonomy type or drops {@code request_id}.</p>
 */
class SessionTokenAuthFilter401EnvelopeTest {

    private SessionTokenIssuer sessionTokenIssuer;
    private ObjectMapper objectMapper;
    private SessionTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        sessionTokenIssuer = mock(SessionTokenIssuer.class);
        objectMapper = new ObjectMapper();
        filter = new SessionTokenAuthenticationFilter(sessionTokenIssuer, objectMapper);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void missingAuthHeader_returns401_withStableEnvelopeAndRequestId() throws Exception {
        MDC.put("request_id", "rid-sess-401");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/sessions");
        // No Authorization header.
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("unauthorized");
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_session_token");
        assertThat(body.path("error").path("message").asText()).isNotBlank();
        assertThat(body.path("error").path("request_id").asText()).isEqualTo("rid-sess-401");

        // The chain must NOT proceed once the 401 is written.
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void invalidSessionToken_returns401_withStableEnvelope() throws Exception {
        when(sessionTokenIssuer.validateSessionToken(anyString())).thenReturn(null);
        MDC.put("request_id", "rid-sess-bad");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/sessions");
        request.addHeader("Authorization", "Bearer not_a_valid_session_jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("unauthorized");
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_session_token");
        assertThat(body.path("error").path("request_id").asText()).isEqualTo("rid-sess-bad");

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void requestId_fallsBackToUuid_whenMdcEmpty() throws Exception {
        // No MDC request_id set.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, mock(FilterChain.class));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("request_id").asText()).isNotBlank();
    }
}
