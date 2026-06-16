package io.nexuspay.iam.adapter.in.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.iam.application.ApiKeyService;
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
 * INT-2: a bad {@code sk_} API key produces the stable 401 error envelope
 * {@code { error: { type:"unauthorized", code:"invalid_api_key", message, request_id } }}.
 *
 * <p>FAILS if the filter's 401 body change is reverted (old {@code authentication_error} type or a
 * missing {@code request_id}).</p>
 */
class ApiKeyAuthFilter401EnvelopeTest {

    private ApiKeyService apiKeyService;
    private ObjectMapper objectMapper;
    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        objectMapper = new ObjectMapper();
        filter = new ApiKeyAuthenticationFilter(apiKeyService, objectMapper);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void badApiKey_returns401_withStableEnvelopeAndRequestId() throws Exception {
        when(apiKeyService.authenticate(anyString())).thenThrow(AuthorizationException.invalidApiKey());
        MDC.put("request_id", "rid-401");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer sk_test_bogus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("unauthorized");
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_api_key");
        assertThat(body.path("error").path("message").asText()).isEqualTo("Invalid API key");
        assertThat(body.path("error").path("request_id").asText()).isEqualTo("rid-401");

        // The chain must NOT proceed once the 401 is written.
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void badApiKey_requestId_fallsBackToUuid_whenMdcEmpty() throws Exception {
        when(apiKeyService.authenticate(anyString())).thenThrow(AuthorizationException.invalidApiKey());
        // No MDC request_id set.

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("Authorization", "Bearer sk_test_bogus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, mock(FilterChain.class));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("request_id").asText()).isNotBlank();
    }
}
