package io.nexuspay.payment;

import io.nexuspay.common.tenant.ScopeSecurity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only Spring Boot configuration anchor for payment-orchestration slice tests (TEST-3a).
 *
 * <p>This module is a library — the real {@code @SpringBootApplication} lives in the {@code app}
 * module — so a {@code @WebMvcTest} slice (e.g. {@code CustomerControllerScopeEnforcementTest}) needs a
 * local {@code @SpringBootConfiguration} to bootstrap against. Copied in spirit from
 * {@code MarketplaceTestApplication}: the security filter chain mirrors production semantics (stateless
 * API, CSRF disabled, 401 on missing auth, {@code @PreAuthorize} enforced).</p>
 */
@SpringBootApplication
@EnableMethodSecurity
public class PaymentOrchestrationTestApplication {

    /**
     * DX-5c-ii: register the {@code @scopeAuth} bean so the {@code @PreAuthorize("... and
     * @scopeAuth.has(...)")} SpEL on {@code CustomerController} resolves in this slice context (the bean
     * lives in {@code common}, component-scanned in the real app, but the slice's local
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
