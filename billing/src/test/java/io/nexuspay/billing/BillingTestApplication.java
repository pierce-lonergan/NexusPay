package io.nexuspay.billing;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only Spring Boot configuration anchor for the billing module.
 *
 * <p>This module is a library — the real {@code @SpringBootApplication} lives in the {@code app}
 * module — so controller-slice tests ({@code @WebMvcTest}) need a local {@code @SpringBootConfiguration}
 * to bootstrap against. The security filter chain mirrors the production semantics from the iam
 * module's {@code SecurityConfig}: stateless API, CSRF disabled, 401 on missing auth.</p>
 *
 * <p>SEC-26: the {@link BillingTestExceptionAdvice} (a top-level {@code @RestControllerAdvice} in this
 * package, auto-detected by {@code @WebMvcTest}) maps {@code ResourceNotFoundException} → 404 so the
 * cross-tenant by-id IDOR cases (which now throw not-found via {@code TenantOwnership.require}) assert
 * the same 404 the production {@code GlobalExceptionHandler} produces, without dragging in the iam
 * module (off billing's classpath).</p>
 */
@SpringBootApplication
public class BillingTestApplication {

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
