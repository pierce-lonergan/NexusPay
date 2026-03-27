package io.nexuspay.vault.config;

import org.springframework.context.annotation.Configuration;

/**
 * Security configuration for the vault module.
 *
 * <p>Prepares for PCI DSS scope segmentation. In Phase 1-4, the vault module
 * runs in-process with the monolith. In production, it can be extracted behind
 * a dedicated network boundary with its own Spring Security filter chain,
 * TLS termination, and audit logging.</p>
 *
 * <p>Current security is inherited from the IAM module's SecurityConfig.
 * Vault-specific endpoint authorization is enforced via {@code @PreAuthorize}
 * annotations on {@link io.nexuspay.vault.adapter.in.rest.VaultController}.</p>
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Configuration
public class VaultSecurityConfig {
    // PCI isolation: when this module is extracted to a standalone service,
    // this class will define its own SecurityFilterChain with:
    // - Separate JWT validation (vault-specific issuer)
    // - mTLS requirement for all endpoints
    // - Enhanced audit logging for all PAN operations
    // - Rate limiting specific to vault operations
}
