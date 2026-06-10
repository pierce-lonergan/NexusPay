package io.nexuspay.workflow;

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
public class WorkflowTestApplication {

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
