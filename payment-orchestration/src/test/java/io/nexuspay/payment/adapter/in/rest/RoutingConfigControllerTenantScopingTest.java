package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.port.routing.PspFeeRepository;
import io.nexuspay.payment.application.port.routing.PspHealthRepository;
import io.nexuspay.payment.application.port.routing.RoutePaymentUseCase;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository.RoutingConfig;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.application.service.PspHealthTracker;
import io.nexuspay.payment.domain.routing.RoutingDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-27 tenant-scoping tests for {@link RoutingConfigController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client {@code X-Tenant-Id}
 * header, and that by-id reads (config / decision / decision-by-payment) are tenant-scoped: a foreign
 * id 404s via {@code TenantOwnership.require} (no cross-tenant existence oracle). These FAIL on the old
 * header-trusting controller, which read {@code @RequestHeader("X-Tenant-Id", defaultValue="default")}
 * and looked rows up with an unscoped {@code findById} — leaking any tenant's config/decision.</p>
 */
class RoutingConfigControllerTenantScopingTest {

    private RoutingConfigRepository configRepository;
    private RoutingDecisionRepository decisionRepository;
    private PspFeeRepository feeRepository;
    private PspHealthRepository healthRepository;
    private PspHealthTracker healthTracker;
    private RoutePaymentUseCase routePaymentUseCase;
    private RoutingConfigController controller;

    @BeforeEach
    void setUp() {
        configRepository = mock(RoutingConfigRepository.class);
        decisionRepository = mock(RoutingDecisionRepository.class);
        feeRepository = mock(PspFeeRepository.class);
        healthRepository = mock(PspHealthRepository.class);
        healthTracker = mock(PspHealthTracker.class);
        routePaymentUseCase = mock(RoutePaymentUseCase.class);
        controller = new RoutingConfigController(configRepository, decisionRepository, feeRepository,
                healthRepository, healthTracker, routePaymentUseCase);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String tenant, String role) {
        TenantPrincipal principal = () -> tenant;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static RoutingConfig config(UUID id, String tenantId) {
        return new RoutingConfig(id, tenantId, "cfg", "SUCCESS_RATE", List.of(),
                true, 3, null, 0.0, true, Instant.now(), Instant.now());
    }

    private static RoutingDecision decision(UUID id, String tenantId, String paymentId) {
        return RoutingDecision.create(tenantId, paymentId, "SUCCESS_RATE", UUID.randomUUID(),
                "stripe", java.util.Map.of(), List.of("stripe"), 5L);
    }

    // ---------- by-id config read: scoped to principal tenant ----------

    @Test
    void getConfig_queriesByPrincipalTenant_notHeader() {
        UUID id = UUID.randomUUID();
        authenticateAs("tenant-a", "viewer");
        when(configRepository.findByIdAndTenantId(id, "tenant-a")).thenReturn(Optional.of(config(id, "tenant-a")));

        var response = controller.getConfig(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(configRepository).findByIdAndTenantId(id, "tenant-a");
        // The old unscoped finder must not be used.
        verify(configRepository, never()).findById(any());
    }

    @Test
    void getConfig_foreignTenantConfig_404s_noOracle() {
        UUID id = UUID.randomUUID();
        authenticateAs("tenant-a", "viewer");
        // Foreign-owned (or absent) row: tenant-scoped finder returns empty.
        when(configRepository.findByIdAndTenantId(id, "tenant-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getConfig(id))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(configRepository).findByIdAndTenantId(id, "tenant-a");
    }

    // ---------- by-id decision read: scoped to principal tenant ----------

    @Test
    void getDecision_foreignTenantDecision_404s_andScopesToPrincipalTenant() {
        UUID id = UUID.randomUUID();
        authenticateAs("tenant-a", "operator");
        when(decisionRepository.findByIdAndTenantId(id, "tenant-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDecision(id))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(decisionRepository).findByIdAndTenantId(id, "tenant-a");
        verify(decisionRepository, never()).findById(any());
    }

    @Test
    void getDecisionByPayment_foreignPayment_404s_andScopesToPrincipalTenant() {
        authenticateAs("tenant-a", "operator");
        when(decisionRepository.findByPaymentIdAndTenantId("pay_victim", "tenant-a"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDecisionByPayment("pay_victim"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(decisionRepository).findByPaymentIdAndTenantId("pay_victim", "tenant-a");
        verify(decisionRepository, never()).findByPaymentId(any());
    }

    @Test
    void getDecisionByPayment_ownPayment_returnsIt_scopedToPrincipal() {
        authenticateAs("tenant-a", "operator");
        when(decisionRepository.findByPaymentIdAndTenantId("pay_1", "tenant-a"))
                .thenReturn(Optional.of(decision(UUID.randomUUID(), "tenant-a", "pay_1")));

        var response = controller.getDecisionByPayment("pay_1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(decisionRepository).findByPaymentIdAndTenantId("pay_1", "tenant-a");
    }

    // ---------- collection reads: tenant from principal ----------

    @Test
    void listConfigs_usesPrincipalTenant() {
        authenticateAs("tenant-a", "viewer");
        when(configRepository.findByTenantId("tenant-a")).thenReturn(List.of());

        controller.listConfigs();

        verify(configRepository).findByTenantId("tenant-a");
        verify(configRepository, never()).findByTenantId("default");
    }

    @Test
    void getActiveConfig_usesPrincipalTenant_noDefaultFallback() {
        authenticateAs("tenant-a", "viewer");
        when(configRepository.findActiveByTenant("tenant-a")).thenReturn(Optional.empty());

        var response = controller.getActiveConfig();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(configRepository).findActiveByTenant("tenant-a");
        // The removed 'default' fallback would have queried/synthesised for "default".
        verify(configRepository, never()).findActiveByTenant("default");
    }

    // ---------- no authenticated principal: 403, never silent 'default' ----------

    @Test
    void getConfig_noPrincipal_throwsForbidden_neverDefaults() {
        // No SecurityContext authentication set.
        assertThatThrownBy(() -> controller.getConfig(UUID.randomUUID()))
                .isInstanceOf(AuthorizationException.class);

        // Must not have fallen back to a 'default' tenant read.
        verify(configRepository, never()).findByIdAndTenantId(any(), eq("default"));
        verify(configRepository, never()).findById(any());
    }

    @Test
    void listConfigs_noPrincipal_throwsForbidden_neverDefaults() {
        assertThatThrownBy(() -> controller.listConfigs())
                .isInstanceOf(AuthorizationException.class);

        verify(configRepository, never()).findByTenantId("default");
    }
}
