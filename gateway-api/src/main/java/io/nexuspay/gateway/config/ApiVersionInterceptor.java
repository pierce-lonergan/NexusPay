package io.nexuspay.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Parses the X-API-Version header and stores it as a request attribute.
 * Phase 1: single version (2026-03-01). Plumbing wired for future versions.
 */
@Component
public class ApiVersionInterceptor implements HandlerInterceptor {

    public static final String API_VERSION_HEADER = "X-API-Version";
    public static final String API_VERSION_ATTRIBUTE = "nexuspay.api.version";
    public static final String DEFAULT_API_VERSION = "2026-03-01";

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
