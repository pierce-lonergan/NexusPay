package io.nexuspay.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stripe-inspired API error structure (INT-2 contract).
 * All error responses follow this format for consistency:
 * <pre>{ "error": { "type": "&lt;category&gt;", "code": "&lt;machine_code&gt;", "message": "&lt;safe text&gt;",
 *            "request_id": "&lt;correlation id&gt;" } }</pre>
 *
 * <p>INT-2: the wire {@code param} field is gone (the field hint is folded into {@code message} by the
 * GlobalExceptionHandler). {@code request_id} echoes the {@code X-Request-Id} correlation id and is
 * always present (the caller generates a UUID fallback when MDC is empty), so {@code @JsonInclude(NON_NULL)}
 * never drops it. The {@code type} string values are the stable taxonomy consumers branch on.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String code,
        String message,
        @JsonProperty("request_id") String requestId
) {

    // --- Stable error taxonomy (wire values — consumers branch on these strings) ---
    public static final String TYPE_VALIDATION = "validation_error";
    public static final String TYPE_NOT_FOUND = "not_found";
    public static final String TYPE_UNAUTHORIZED = "unauthorized";
    public static final String TYPE_FORBIDDEN = "forbidden";
    public static final String TYPE_CONFLICT = "conflict";
    public static final String TYPE_PAYMENT = "payment_error";
    public static final String TYPE_RATE_LIMIT = "rate_limit_error";
    public static final String TYPE_INTERNAL = "internal_error";
    /** Session lifecycle category (e.g. an expired checkout session -> HTTP 410). Branchable like the rest. */
    public static final String TYPE_SESSION = "session_error";

    public static ApiError of(String type, String code, String message, String requestId) {
        return new ApiError(type, code, message, requestId);
    }
}
