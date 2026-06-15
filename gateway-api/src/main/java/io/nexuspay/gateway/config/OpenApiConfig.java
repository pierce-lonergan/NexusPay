package io.nexuspay.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI metadata for the NexusPay API (DX-1).
 *
 * <p>springdoc 2.5.0 is already wired (Swagger UI at {@code /v1/swagger-ui}, JSON
 * at {@code /v1/api-docs}); this bean supplies the document-level metadata that
 * springdoc otherwise leaves empty: title/version/description, the three auth
 * schemes the gateway accepts, and the server URL(s).
 *
 * <p>The three security schemes mirror the runtime filters exactly — all three
 * are read from the {@code Authorization: Bearer <token>} header, so all three
 * are modeled as HTTP bearer schemes distinguished by token shape:
 * <ul>
 *   <li>{@code apiKey} — secret API key {@code sk_test_/sk_live_…}, validated by
 *       {@code ApiKeyAuthenticationFilter} (token starts with {@code sk_}).</li>
 *   <li>{@code sessionToken} — short-lived session JWT for {@code /v1/checkout/**},
 *       validated by {@code SessionTokenAuthenticationFilter}.</li>
 *   <li>{@code bearerAuth} — Keycloak OIDC access token (JWT), validated by
 *       Spring Security's OAuth2 resource server.</li>
 * </ul>
 * Declaring schemes here does not enforce auth (Spring Security does that); it
 * only documents them for the UI/spec. No global SecurityRequirement is set so
 * the public endpoints (health, api-docs) remain shown as open.
 */
@Configuration
public class OpenApiConfig {

    @Value("${nexuspay.openapi.server-url:http://localhost:8090}")
    private String serverUrl;

    @Bean
    public OpenAPI nexusPayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NexusPay API")
                        .version("v1")
                        .description(
                                "NexusPay payment gateway API. Supports three authentication schemes: "
                                        + "merchant API keys (sk_…), short-lived checkout session tokens, "
                                        + "and Keycloak OIDC bearer tokens for the dashboard/back office."))
                .servers(List.of(
                        new Server().url(serverUrl).description("Configured server")))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description(
                                        "Merchant API key. Send as `Authorization: Bearer sk_test_…` "
                                                + "(or sk_live_… in production). Validated by ApiKeyAuthenticationFilter."))
                        .addSecuritySchemes("sessionToken", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "Short-lived checkout session token (JWT) for /v1/checkout/** endpoints. "
                                                + "Send as `Authorization: Bearer <session-jwt>`."))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "Keycloak OIDC access token (JWT) for dashboard/back-office endpoints. "
                                                + "Send as `Authorization: Bearer <oidc-jwt>`.")));
    }
}
