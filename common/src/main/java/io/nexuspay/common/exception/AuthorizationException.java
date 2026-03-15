package io.nexuspay.common.exception;

public class AuthorizationException extends NexusPayException {

    public AuthorizationException(String message, String errorCode) {
        super(message, errorCode);
    }

    public static AuthorizationException forbidden(String action) {
        return new AuthorizationException(
                "Not authorized to perform action: " + action,
                "forbidden"
        );
    }

    public static AuthorizationException invalidApiKey() {
        return new AuthorizationException(
                "Invalid or revoked API key",
                "invalid_api_key"
        );
    }

    public static AuthorizationException approvalRequired(String action) {
        return new AuthorizationException(
                "Action requires approval: " + action,
                "approval_required"
        );
    }
}
