package io.nexuspay.app.config;

import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.reconciliation.application.port.out.LedgerQueryPort;
import io.nexuspay.reconciliation.application.port.out.PaymentQueryPort;
import io.nexuspay.reconciliation.application.service.ThreeWayMatchingService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test security configuration that provides a mock JWT decoder.
 * Allows integration tests to bypass Keycloak.
 */
@TestConfiguration
public class TestSecurityConfig {

    /**
     * SEC-17: armable fault-injection seam over {@link ThreeWayMatchingService}, {@code @Primary} so the
     * orchestrator autowires it. Lives HERE (the shared test config) rather than as a per-test
     * {@code @MockBean} on purpose — a {@code @MockBean} would fork a second full Testcontainers context
     * into Spring's cache and OOM the {@code :app:test} JVM. Default behavior is identical to the real
     * service; a test arms a per-thread fault via
     * {@link FaultInjectableThreeWayMatchingService#armFault} and clears it in a {@code finally}. Inert
     * for every other IT. See {@link FaultInjectableThreeWayMatchingService} for the full rationale.
     */
    @Bean
    @Primary
    public ThreeWayMatchingService faultInjectableMatchingService(PaymentQueryPort paymentQueryPort,
                                                                  LedgerQueryPort ledgerQueryPort) {
        return new FaultInjectableThreeWayMatchingService(paymentQueryPort, ledgerQueryPort);
    }

    /**
     * WAVE1-money-ledger: armable fault-injection seam over {@link CreateJournalEntryUseCase},
     * {@code @Primary} so every ledger-posting adapter (marketplace/b2b/dispute) autowires it.
     * Same shared-context rationale as {@link #faultInjectableMatchingService} (no @MockBean context
     * fork). Inert unless a test arms a per-thread fault via
     * {@link FaultInjectableCreateJournalEntryUseCase#armFault} (cleared in a finally).
     */
    @Bean
    @Primary
    public CreateJournalEntryUseCase faultInjectableCreateJournalEntryUseCase(
            JournalEntryRepository journalEntryRepository,
            LedgerAccountRepository ledgerAccountRepository) {
        return new FaultInjectableCreateJournalEntryUseCase(journalEntryRepository, ledgerAccountRepository);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Return a mock decoder that always succeeds with a test admin user
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "test-admin-user")
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    /**
     * Test auth token for a role on the "default" tenant. Backward-compatible with existing ITs.
     */
    public static UsernamePasswordAuthenticationToken authForRole(String role) {
        return authFor("default", role);
    }

    /**
     * Test auth token for a role + tenant (SEC-BATCH-1 API). Delegates to {@link #authFor}.
     * NexusPayPrincipal implements common.TenantPrincipal, so CallerTenant.require() resolves this
     * principal's tenant in tests exactly as in production — letting ITs exercise cross-tenant isolation.
     */
    public static UsernamePasswordAuthenticationToken authForRole(String role, String tenantId) {
        return authFor(tenantId, role);
    }

    /**
     * Test auth token for a specific TENANT and role (red-team / sim API) — the canonical builder.
     *
     * <p>Test-only ({@code @TestConfiguration}, never on the main source set). Used by the red-team
     * cross-tenant IDOR suite to authenticate as one tenant while sending an {@code X-Tenant-Id} header
     * naming a different (victim) tenant. The principal's {@code tenantId} is the authenticated identity;
     * a secure system must derive the effective tenant from THIS, not from a client-supplied header.</p>
     *
     * @param tenant the authenticated tenant the principal belongs to
     * @param role   the role granted (admin/operator/viewer)
     */
    public static UsernamePasswordAuthenticationToken authFor(String tenant, String role) {
        var principal = new NexusPayPrincipal(
                "test-" + role + "-user",
                tenant,
                role,
                NexusPayPrincipal.AuthMethod.JWT
        );
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
