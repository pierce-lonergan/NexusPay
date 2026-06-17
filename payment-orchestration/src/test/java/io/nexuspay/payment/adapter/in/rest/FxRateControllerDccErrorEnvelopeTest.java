package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.fx.CurrencyRoutingService;
import io.nexuspay.payment.application.fx.DynamicCurrencyConversionService;
import io.nexuspay.payment.application.fx.FxRateLockService;
import io.nexuspay.payment.application.fx.FxRateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-2: when DCC is unavailable, POST /v1/fx/dcc/offers no longer returns the non-conforming
 * {@code {"error":"<string>"}} body. The controller throws a {@link PaymentException}
 * (code {@code dcc_unavailable}) which the {@code GlobalExceptionHandler} maps to a 422 + the stable
 * {@code { error: { type, code, message, request_id } }} envelope. The HTTP status (422) is unchanged.
 */
class FxRateControllerDccErrorEnvelopeTest {

    private DynamicCurrencyConversionService dccService;
    private FxRateController controller;

    @BeforeEach
    void setUp() {
        FxRateService rateService = mock(FxRateService.class);
        FxRateLockService lockService = mock(FxRateLockService.class);
        CurrencyRoutingService routingService = mock(CurrencyRoutingService.class);
        CrossBorderComplianceService complianceService = mock(CrossBorderComplianceService.class);
        dccService = mock(DynamicCurrencyConversionService.class);
        controller = new FxRateController(rateService, lockService, routingService,
                complianceService, dccService);

        // SEC-27: createDccOffer now derives the tenant from the authenticated principal
        // (CallerTenant.require()) instead of an X-Tenant-Id header, so authenticate the thread.
        TenantPrincipal principal = () -> "tenant-a";
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_operator"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dccUnavailable_throwsPaymentException_routedToStandardEnvelope() {
        when(dccService.createOffer(anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString()))
                .thenReturn(Optional.empty());

        var request = new FxRateController.DccOfferRequest(
                "pay_123", "stripe", "EUR", 10_000L, "USD");

        // The controller now signals the failure as a domain exception (-> GlobalExceptionHandler ->
        // 422 + envelope) rather than hand-rolling a plain-string body.
        assertThatThrownBy(() -> controller.createDccOffer(request))
                .isInstanceOf(PaymentException.class)
                .satisfies(ex -> {
                    PaymentException pe = (PaymentException) ex;
                    org.assertj.core.api.Assertions.assertThat(pe.getErrorCode()).isEqualTo("dcc_unavailable");
                    org.assertj.core.api.Assertions.assertThat(pe.getMessage())
                            .isEqualTo("DCC not available for this payment configuration");
                });
    }
}
