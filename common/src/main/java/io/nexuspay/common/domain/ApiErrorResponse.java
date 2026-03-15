package io.nexuspay.common.domain;

/**
 * Wrapper for API error responses: { "error": { ... } }
 */
public record ApiErrorResponse(ApiError error) {

    public static ApiErrorResponse of(ApiError error) {
        return new ApiErrorResponse(error);
    }
}
