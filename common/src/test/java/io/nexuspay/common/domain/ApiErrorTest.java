package io.nexuspay.common.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INT-2: tests for {@link ApiError} / {@link ApiErrorResponse} — the cross-module wire contract for
 * error responses. Verifies the evolved shape: {@code { error: { type, code, message, request_id } }},
 * the stable taxonomy constant values, that {@code request_id} serializes (and as {@code "request_id"},
 * not {@code "requestId"}), that the dropped {@code param} field never appears, and that
 * {@code @JsonInclude(NON_NULL)} drops only null optionals.
 *
 * <p>These assertions FAIL if the INT-2 envelope change is reverted (old factories/constants/param).</p>
 */
class ApiErrorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ofFactorySetsAllFields() {
        ApiError err = ApiError.of(ApiError.TYPE_INTERNAL, "internal_error", "Something failed", "rid-1");
        assertEquals(ApiError.TYPE_INTERNAL, err.type());
        assertEquals("internal_error", err.code());
        assertEquals("Something failed", err.message());
        assertEquals("rid-1", err.requestId());
    }

    @Test
    void taxonomyConstantsHaveStableWireValues() {
        // Consumers branch on these exact strings — pin them.
        assertEquals("validation_error", ApiError.TYPE_VALIDATION);
        assertEquals("not_found", ApiError.TYPE_NOT_FOUND);
        assertEquals("unauthorized", ApiError.TYPE_UNAUTHORIZED);
        assertEquals("forbidden", ApiError.TYPE_FORBIDDEN);
        assertEquals("conflict", ApiError.TYPE_CONFLICT);
        assertEquals("payment_error", ApiError.TYPE_PAYMENT);
        assertEquals("rate_limit_error", ApiError.TYPE_RATE_LIMIT);
        assertEquals("internal_error", ApiError.TYPE_INTERNAL);
        assertEquals("session_error", ApiError.TYPE_SESSION);
    }

    @Test
    void serializationIncludesRequestIdUnderSnakeCaseKey() throws Exception {
        ApiError err = ApiError.of(ApiError.TYPE_VALIDATION, "invalid_parameter", "amount: must be > 0", "rid-42");
        String json = mapper.writeValueAsString(err);

        // request_id is on the wire under its snake_case @JsonProperty name (not "requestId").
        assertTrue(json.contains("\"request_id\":\"rid-42\""), json);
        assertFalse(json.contains("\"requestId\""), json);
        assertTrue(json.contains("\"type\":\"validation_error\""), json);
        assertTrue(json.contains("\"code\":\"invalid_parameter\""), json);
    }

    @Test
    void serializationNeverIncludesParam() throws Exception {
        // INT-2 dropped `param` from the wire entirely (the field hint is folded into `message`).
        ApiError err = ApiError.of(ApiError.TYPE_VALIDATION, "invalid_parameter", "amount: required", "rid-7");
        String json = mapper.writeValueAsString(err);

        assertFalse(json.contains("\"param\""), "the param field must be gone from the wire: " + json);
    }

    @Test
    void serializationDropsNullOptionalsButKeepsRequestId() throws Exception {
        // @JsonInclude(NON_NULL): a null code/message is dropped; a present request_id is kept.
        ApiError err = ApiError.of(ApiError.TYPE_NOT_FOUND, null, null, "rid-9");
        String json = mapper.writeValueAsString(err);

        assertFalse(json.contains("\"code\""), json);
        assertFalse(json.contains("\"message\""), json);
        assertTrue(json.contains("\"request_id\":\"rid-9\""), json);
        assertTrue(json.contains("\"type\":\"not_found\""), json);
    }

    @Test
    void apiErrorResponseWrapsUnderErrorKey() throws Exception {
        ApiErrorResponse response = ApiErrorResponse.of(
                ApiError.of(ApiError.TYPE_UNAUTHORIZED, "invalid_api_key", "Invalid API key", "rid-x"));

        assertEquals(ApiError.TYPE_UNAUTHORIZED, response.error().type());

        String json = mapper.writeValueAsString(response);
        // Shape: {"error": {...}}
        assertTrue(json.startsWith("{\"error\":"), json);
        assertTrue(json.contains("\"type\":\"unauthorized\""), json);
        assertTrue(json.contains("\"code\":\"invalid_api_key\""), json);
        assertTrue(json.contains("\"request_id\":\"rid-x\""), json);
    }
}
