package io.nexuspay.common.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ApiError} / {@link ApiErrorResponse} — the cross-module wire contract for
 * error responses. Verifies factory type/code assignments and the @JsonInclude(NON_NULL)
 * serialization shape that callers depend on.
 */
class ApiErrorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiErrorFactorySetsTypeAndNullParam() {
        ApiError err = ApiError.apiError("internal_error", "Something failed");
        assertEquals(ApiError.TYPE_API_ERROR, err.type());
        assertEquals("internal_error", err.code());
        assertEquals("Something failed", err.message());
        assertNull(err.param());
    }

    @Test
    void invalidRequestFactoryPreservesParam() {
        ApiError err = ApiError.invalidRequest("parameter_missing", "amount is required", "amount");
        assertEquals(ApiError.TYPE_INVALID_REQUEST, err.type());
        assertEquals("parameter_missing", err.code());
        assertEquals("amount", err.param());
    }

    @Test
    void authenticationErrorFactoryUsesFixedCode() {
        ApiError err = ApiError.authenticationError("API key expired");
        assertEquals(ApiError.TYPE_AUTHENTICATION, err.type());
        assertEquals("authentication_failed", err.code());
        assertEquals("API key expired", err.message());
        assertNull(err.param());
    }

    @Test
    void rateLimitErrorFactoryHasCanonicalCodeAndMessage() {
        ApiError err = ApiError.rateLimitError();
        assertEquals(ApiError.TYPE_RATE_LIMIT, err.type());
        assertEquals("rate_limit_exceeded", err.code());
        assertTrue(err.message().contains("Retry-After"), err.message());
    }

    @Test
    void serializationOmitsNullParam() throws Exception {
        // @JsonInclude(NON_NULL): a null param must NOT appear in the JSON envelope.
        ApiError err = ApiError.apiError("internal_error", "boom");
        String json = mapper.writeValueAsString(err);

        assertFalse(json.contains("\"param\""), "null param should be dropped: " + json);
        assertTrue(json.contains("\"type\":\"api_error\""), json);
        assertTrue(json.contains("\"code\":\"internal_error\""), json);
    }

    @Test
    void serializationIncludesNonNullParam() throws Exception {
        ApiError err = ApiError.invalidRequest("parameter_missing", "amount is required", "amount");
        String json = mapper.writeValueAsString(err);

        assertTrue(json.contains("\"param\":\"amount\""), json);
    }

    @Test
    void apiErrorResponseWrapsUnderErrorKey() throws Exception {
        ApiErrorResponse response = ApiErrorResponse.of(ApiError.authenticationError("bad key"));

        assertEquals(ApiError.TYPE_AUTHENTICATION, response.error().type());

        String json = mapper.writeValueAsString(response);
        // Shape: {"error": {...}}
        assertTrue(json.startsWith("{\"error\":"), json);
        assertTrue(json.contains("\"type\":\"authentication_error\""), json);
        assertTrue(json.contains("\"code\":\"authentication_failed\""), json);
    }
}
