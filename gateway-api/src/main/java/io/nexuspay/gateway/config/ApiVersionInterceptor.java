package io.nexuspay.gateway.config;

import io.nexuspay.common.api.ApiVersion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Parses the {@code X-API-Version} request header, exposes it as a request attribute, and echoes it on
 * the response. There is currently exactly ONE supported contract version, so this is INFORMATIONAL
 * plumbing (the value is recorded/echoed but no per-version request/response transformation exists yet).
 *
 * <p>DX-5e: the default now references the single canonical {@link ApiVersion#CURRENT} — previously it
 * hard-coded {@code 2026-03-01}, which disagreed with the {@code 2026-06-16} stamped into every webhook
 * envelope ({@code WebhookEnvelopeSerializer}). Both surfaces now derive from the one source of truth, so
 * the request-header version and the webhook {@code api_version} always match.</p>
 */
@Component
public class ApiVersionInterceptor implements HandlerInterceptor {

    public static final String API_VERSION_HEADER = "X-API-Version";
    public static final String API_VERSION_ATTRIBUTE = "nexuspay.api.version";
    public static final String DEFAULT_API_VERSION = ApiVersion.CURRENT;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) {
        String version = request.getHeader(API_VERSION_HEADER);
        if (version == null || version.isBlank()) {
            version = DEFAULT_API_VERSION;
        }
        request.setAttribute(API_VERSION_ATTRIBUTE, version);
        response.setHeader(API_VERSION_HEADER, version);
        return true;
    }
}
