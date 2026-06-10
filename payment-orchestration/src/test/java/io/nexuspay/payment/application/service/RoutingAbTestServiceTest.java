package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.application.service.RoutingAbTestService.SignificanceResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * B-007: the A/B framework is currently unreachable (the routing engine never
 * calls selectConfig/recordOutcome — see Q-007), but its two-proportion z-test
 * math is the load-bearing part if it is ever wired. These tests lock that math
 * so a future wiring (or a port to a real stats lib) can't silently regress it.
 */
class RoutingAbTestServiceTest {

    private RoutingAbTestService service(double confidence) {
        return new RoutingAbTestService(
                mock(RoutingConfigRepository.class),
                mock(RoutingDecisionRepository.class),
                1000, confidence, true);
    }

    @Test
    void normalCdf_knownValues() {
        assertThat(RoutingAbTestService.normalCdf(0.0)).isEqualTo(0.5, within(1e-6));
        assertThat(RoutingAbTestService.normalCdf(1.96)).isEqualTo(0.975, within(1e-3));
        assertThat(RoutingAbTestService.normalCdf(-1.96)).isEqualTo(0.025, within(1e-3));
        // symmetry: cdf(z) + cdf(-z) == 1
        assertThat(RoutingAbTestService.normalCdf(1.2) + RoutingAbTestService.normalCdf(-1.2))
                .isEqualTo(1.0, within(1e-6));
    }

    @Test
    void significance_largeDifferenceIsSignificant() {
        // 95% vs 85% over 1000 each — a ~7σ difference.
        SignificanceResult r = service(0.95).computeSignificance(950, 1000, 850, 1000);
        assertThat(r.isSignificant()).isTrue();
        assertThat(r.pValue()).isLessThan(0.05);
        assertThat(r.zScore()).isGreaterThan(2.0);
    }

    @Test
    void significance_equalRatesNotSignificant() {
        SignificanceResult r = service(0.95).computeSignificance(900, 1000, 900, 1000);
        assertThat(r.isSignificant()).isFalse();
        assertThat(r.zScore()).isEqualTo(0.0, within(1e-9));
        assertThat(r.pValue()).isEqualTo(1.0, within(1e-6));
    }

    @Test
    void significance_zeroVarianceIsNotSignificant() {
        // Both groups 100% success → pooled q = 0 → se = 0 → guarded, not significant.
        SignificanceResult r = service(0.95).computeSignificance(1000, 1000, 1000, 1000);
        assertThat(r.isSignificant()).isFalse();
    }

    @Test
    void significance_emptyGroupsInsufficient() {
        SignificanceResult r = service(0.95).computeSignificance(0, 0, 5, 10);
        assertThat(r.isSignificant()).isFalse();
    }
}
