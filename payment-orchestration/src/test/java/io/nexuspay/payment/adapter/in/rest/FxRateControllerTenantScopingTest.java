package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.fx.CurrencyRoutingService;
import io.nexuspay.payment.application.fx.DynamicCurrencyConversionService;
import io.nexuspay.payment.application.fx.FxRateLockService;
import io.nexuspay.payment.application.fx.FxRateService;
import io.nexuspay.payment.domain.fx.DccOffer;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRatePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
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
 * SEC-27 tenant-scoping tests for {@link FxRateController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client {@code X-Tenant-Id}
 * header (the old header carried a {@code defaultValue="default"} — itself a silent cross-tenant read),
 * and that by-id reads (rate-lock by id, DCC offer by id) are tenant-scoped: a foreign id 404s. The
 * old controller looked locks up with an unscoped {@code getValidLock(lockId)} and DCC offers with an
 * unscoped {@code getOffer(offerId)} — leaking any tenant's lock/offer.</p>
 */
class FxRateControllerTenantScopingTest {

    private FxRateService rateService;
    private FxRateLockService lockService;
    private CurrencyRoutingService routingService;
    private CrossBorderComplianceService complianceService;
    private DynamicCurrencyConversionService dccService;
    private FxRateController controller;

    @BeforeEach
    void setUp() {
        rateService = mock(FxRateService.class);
        lockService = mock(FxRateLockService.class);
        routingService = mock(CurrencyRoutingService.class);
        complianceService = mock(CrossBorderComplianceService.class);
        dccService = mock(DynamicCurrencyConversionService.class);
        controller = new FxRateController(rateService, lockService, routingService,
                complianceService, dccService);
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

    private static DccOffer dccOffer(UUID id, String tenantId) {
        Instant now = Instant.now();
        return new DccOffer(id, tenantId, "pay_1", "EUR", 10_000L, "USD", 11_000L,
                new BigDecimal("1.10"), 300, new BigDecimal("300"), "ECB",
                now, now.plusSeconds(300), DccOffer.DccStatus.OFFERED);
    }

    private static FxRate fxRate() {
        return new FxRate(new FxRatePair("EUR", "USD"), new BigDecimal("1.10"),
                new BigDecimal("0.909"), "ECB", Instant.now());
    }

    // ---------- getRate: tenant from principal, header ignored ----------

    @Test
    void getRate_usesPrincipalTenant_notHeader() {
        authenticateAs("tenant-a", "viewer");
        when(rateService.getRate("tenant-a", "EUR", "USD")).thenReturn(fxRate());

        controller.getRate("eur", "usd");

        verify(rateService).getRate("tenant-a", "EUR", "USD");
        // Old code would have passed the header value (defaulting to "default").
        verify(rateService, never()).getRate(eq("default"), any(), any());
    }

    // ---------- getLock: tenant-scoped by-id read ----------

    @Test
    void getLock_scopesToPrincipalTenant() {
        UUID lockId = UUID.randomUUID();
        authenticateAs("tenant-a", "viewer");
        when(lockService.getValidLock(lockId, "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Rate lock not found"));

        assertThatThrownBy(() -> controller.getLock(lockId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(lockService).getValidLock(lockId, "tenant-a");
        // The old unscoped overload must not be used.
        verify(lockService, never()).getValidLock(any(UUID.class));
    }

    // ---------- getDccOffer: tenant-scoped by-id read ----------

    @Test
    void getDccOffer_foreignTenantOffer_404s_noOracle() {
        UUID offerId = UUID.randomUUID();
        authenticateAs("tenant-a", "viewer");
        // Offer exists but belongs to another tenant — must collapse to 404.
        when(dccService.getOffer(offerId)).thenReturn(Optional.of(dccOffer(offerId, "tenant-b")));

        assertThatThrownBy(() -> controller.getDccOffer(offerId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(dccService).getOffer(offerId);
    }

    @Test
    void getDccOffer_absentOffer_404s() {
        UUID offerId = UUID.randomUUID();
        authenticateAs("tenant-a", "viewer");
        when(dccService.getOffer(offerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDccOffer(offerId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getDccOffer_ownOffer_returnsDisclosure() {
        UUID offerId = UUID.randomUUID();
        authenticateAs("tenant-a", "viewer");
        DccOffer mine = dccOffer(offerId, "tenant-a");
        when(dccService.getOffer(offerId)).thenReturn(Optional.of(mine));
        when(dccService.buildDisclosure(mine)).thenReturn(java.util.Map.of("dcc_offer_id", offerId.toString()));

        var response = controller.getDccOffer(offerId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(dccService).buildDisclosure(mine);
    }

    // ---------- no authenticated principal: 403, never silent 'default' ----------

    @Test
    void getRate_noPrincipal_throwsForbidden_neverDefaults() {
        assertThatThrownBy(() -> controller.getRate("eur", "usd"))
                .isInstanceOf(AuthorizationException.class);

        verify(rateService, never()).getRate(eq("default"), any(), any());
    }

    @Test
    void getLock_noPrincipal_throwsForbidden_neverDefaults() {
        assertThatThrownBy(() -> controller.getLock(UUID.randomUUID()))
                .isInstanceOf(AuthorizationException.class);

        verify(lockService, never()).getValidLock(any(UUID.class), eq("default"));
        verify(lockService, never()).getValidLock(any(UUID.class));
    }
}
