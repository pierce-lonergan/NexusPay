package io.nexuspay.gateway.adapter.in.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP request/response logging filter (GAP-019).
 *
 * Logs method, path, status code, and duration for every API call.
 * Runs at lowest filter order to capture the full request lifecycle.
 * Skips actuator and internal paths to reduce noise.
 */
@Component
@Order(5)
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String queryString = request.getQueryString();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();

            if (log.isInfoEnabled()) {
                String fullPath = queryString != null ? path + "?" + queryString : path;
                String requestId = MDC.get("request_id");
                log.info("HTTP {} {} {} {}ms request_id={}",
                        method, fullPath, status, durationMs,
                        requestId != null ? requestId : "-");
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/internal")
                || path.startsWith("/v1/swagger-ui")
                || path.startsWith("/v1/api-docs");
    }
}
