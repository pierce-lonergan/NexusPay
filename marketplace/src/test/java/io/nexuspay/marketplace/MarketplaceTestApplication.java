package io.nexuspay.marketplace;

import io.nexuspay.common.tenant.ScopeSecurity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only Spring Boot configuration anchor.
 *
 * <p>This module is a library — the real {@code @SpringBootApplication} lives
 * in the {@code app} module — so slice tests like {@code @WebMvcTest} need a
 * local {@code @SpringBootConfiguration} to bootstrap against. The security
 * filter chain mirrors the production semantics from the iam module's
 * {@code SecurityConfig}: stateless API, CSRF disabled, 401 on missing auth,
 * {@code @PreAuthorize} enforced.</p>
 */
@SpringBootApplication
@EnableMethodSecurity
public class MarketplaceTestApplication {

    /**
     * DX-5c-ii: register the {@code @scopeAuth} bean so the {@code @PreAuthorize("... and
     * @scopeAuth.has(...)")} SpEL on {@code PayoutController}/{@code SplitPaymentController} resolves in
     * this slice context (the bean lives in {@code common}, component-scanned in the real app, but the
     * slice's local {@code @SpringBootApplication} does not scan {@code io.nexuspay.common}). A bare
     * {@code TenantPrincipal} (this slice's auth) is unrestricted, so {@code has()} returns true and the
     * existing assertions stand.
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
