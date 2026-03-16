package io.nexuspay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS configuration for checkout SDK endpoints ({@code /v1/checkout/**}).
 *
 * <p>Allows any origin (SDK is embedded in merchant sites), with
 * {@code Access-Control-Max-Age: 86400} (24h) for preflight caching.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Configuration
public class CheckoutCorsConfig {

    @Value("${nexuspay.checkout.cors-allowed-origins:*}")
    private String allowedOrigins;

    @Value("${nexuspay.checkout.cors-max-age:86400}")
    private long corsMaxAge;

    @Bean
    public CorsFilter checkoutCorsFilter() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(corsMaxAge);
        config.setAllowCredentials(false);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/checkout/**", config);

        return new CorsFilter(source);
    }
}
