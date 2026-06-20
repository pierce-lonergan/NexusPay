package io.nexuspay.gateway;

import io.nexuspay.common.tenant.ScopeSecurity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only Spring Boot configuration anchor for the {@code gateway-api} module.
 *
 * <p>This module is a library — the real {@code @SpringBootApplication} lives in the {@code app}
 * module — so slice tests like {@code @WebMvcTest} need a local {@code @SpringBootConfiguration} to
 * bootstrap against. Mirrors {@code MarketplaceTestApplication}/{@code VaultTestApplication}: the
 * security filter chain reproduces the production semantics from the iam module's
 * {@code SecurityConfig} (stateless API, CSRF disabled, 401 on missing auth, {@code @PreAuthorize}
 * enforced).</p>
 */
@SpringBootApplication
@EnableMethodSecurity
public class GatewayTestApplication {

    /**
     * DX-5c-ii: register the {@code @scopeAuth} bean so the {@code @PreAuthorize("... and
     * @scopeAuth.has(...)")} SpEL on the gateway-api controllers resolves in this slice context (the
     * bean lives in {@code common} and is component-scanned in the real app, but the slice's local
     * {@code @SpringBootApplication} does not scan {@code io.nexuspay.common}).
     */
    @Bean
    ScopeSecurity scopeAuth() {
        return new ScopeSecurity();
    }

    @Bean
    SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, e) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)));
        return http.build();
    }
}
