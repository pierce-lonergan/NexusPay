package io.nexuspay.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Stripe-inspired API error structure.
 * All error responses follow this format for consistency.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String code,
        String message,
        String param
) {

    public static final String TYPE_API_ERROR = "api_error";
    public static final String TYPE_CARD_ERROR = "card_error";
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    public static final String TYPE_AUTHENTICATION = "authentication_error";
    public static final String TYPE_RATE_LIMIT = "rate_limit_error";

    public static ApiError apiError(String code, String message) {
        return new ApiError(TYPE_API_ERROR, code, message, null);
    }

    public static ApiError invalidRequest(String code, String message, String param) {
        return new ApiError(TYPE_INVALID_REQUEST, code, message, param);
    }

    public static ApiError authenticationError(String message) {
        return new ApiError(TYPE_AUTHENTICATION, "authentication_failed", message, null);
    }

    public static ApiError rateLimitError() {
        return new ApiError(TYPE_RATE_LIMIT, "rate_limit_exceeded",
                "Too many requests. Please retry after the period specified in the Retry-After header.", null);
    }
}
