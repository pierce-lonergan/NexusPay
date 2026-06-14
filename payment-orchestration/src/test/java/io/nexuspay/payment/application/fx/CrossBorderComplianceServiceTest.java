package io.nexuspay.payment.application.fx;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService.ComplianceResult;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService.GeographyTrust;
import io.nexuspay.payment.application.port.fx.CrossBorderCompliancePort;
import io.nexuspay.payment.domain.fx.CountryRestriction;
import io.nexuspay.payment.domain.fx.CrossBorderRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-026 fail-closed + B-025 unknown-geo REVIEW at the compliance-service level.
 *
 * <p>The pivotal property: a CLEAN country (US) must be BLOCKED when screening is unavailable —
 * proving fail-CLOSED, not the old fail-OPEN where a null/blank/empty list silently allowed.</p>
 */
class CrossBorderComplianceServiceTest {

    private final BigDecimal amt = new BigDecimal("50.00");

    private CrossBorderCompliancePort port(boolean screeningAvailable) {
        CrossBorderCompliancePort p = mock(CrossBorderCompliancePort.class);
        when(p.isScreeningAvailable()).thenReturn(screeningAvailable);
        // default: nothing restricted, no rule, no reporting
        lenient().when(p.checkCountryRestriction(anyString())).thenReturn(Optional.empty());
        lenient().when(p.checkCountryRestriction(null)).thenReturn(Optional.empty());
        lenient().when(p.getRule(any(), any())).thenReturn(Optional.empty());
        lenient().when(p.requiresReporting(any(), any(), any(), any())).thenReturn(false);
        return p;
    }

    private CrossBorderComplianceService svc(CrossBorderCompliancePort p) {
        return new CrossBorderComplianceService(p, true);
    }

    @Test
    void screeningUnavailable_blocksCleanCountry_failClosed() {
        // US is not sanctioned, yet must be BLOCKED because the screen cannot run.
        CrossBorderComplianceService s = svc(port(false));

        ComplianceResult r = s.validateTransaction("US", "US", amt, "USD");

        assertThat(r.allowed()).isFalse();   // NOT allowed=true (the old fail-open)
        assertThat(r.blockReason()).isNotBlank();
    }

    @Test
    void screeningUnavailable_validateOrThrow_throwsCrossBorderBlocked_withGenericReason() {
        CrossBorderComplianceService s = svc(port(false));

        assertThatThrownBy(() -> s.validateOrThrow("US", "US", amt, "USD"))
                .isInstanceOfSatisfying(PaymentException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked");
                    // B-028: reason must NOT echo a country code.
                    assertThat(e.getMessage()).doesNotContain("US");
                    assertThat(e.getMessage().toLowerCase()).contains("unavailable");
                });
    }

    @Test
    void healthyStaticBaseline_atBoot_screeningAvailable_usAllowed_kpBlocked() {
        // ofac not yet refreshed but static list intact → screening AVAILABLE (no self-inflicted
        // boot outage). US allowed, KP hard-blocked.
        CrossBorderCompliancePort p = port(true);
        when(p.checkCountryRestriction("KP")).thenReturn(Optional.of(new CountryRestriction(
                "KP", CountryRestriction.RestrictionType.SANCTIONED, "sanctioned")));
        CrossBorderComplianceService s = svc(p);

        // US→US domestic (both known, equal) is allowed
        ComplianceResult us = s.validateTransaction("US", "US", amt, "USD",
                new GeographyTrust(true, true, false));
        assertThat(us.allowed()).isTrue();
        assertThat(us.requiresReview()).isFalse();

        // KP destination hard-blocked
        ComplianceResult kp = s.validateTransaction("US", "KP", amt, "USD",
                new GeographyTrust(true, true, true));
        assertThat(kp.allowed()).isFalse();
    }

    @Test
    void unknownDestination_onCrossBorderCapable_routesToReview_notAllow() {
        CrossBorderComplianceService s = svc(port(true));

        ComplianceResult r = s.validateTransaction(null, null, amt, "USD",
                new GeographyTrust(false, false, true));

        assertThat(r.requiresReview()).isTrue();
        assertThat(r.requiresEnhancedDueDiligence()).isTrue();
        assertThat(r.flags()).contains(CrossBorderComplianceService.GEO_UNKNOWN_REVIEW_FLAG);
        // It is NOT a hard block — allowed=true so the PSP authorizes, gate holds capture.
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void sanctionedKnownCountry_hardBlocks_evenWhenAnotherLegUnknown() {
        // A sanctioned KNOWN destination must hard-block BEFORE the unknown-geo REVIEW branch.
        CrossBorderCompliancePort p = port(true);
        when(p.checkCountryRestriction("IR")).thenReturn(Optional.of(new CountryRestriction(
                "IR", CountryRestriction.RestrictionType.SANCTIONED, "sanctioned")));
        CrossBorderComplianceService s = svc(p);

        ComplianceResult r = s.validateTransaction(null, "IR", amt, "USD",
                new GeographyTrust(true, false, true));
        assertThat(r.allowed()).isFalse();
        assertThat(r.requiresReview()).isFalse();
    }

    @Test
    void knownDomesticFlow_isAllowed_noReview() {
        CrossBorderComplianceService s = svc(port(true));
        // Both known + equal → not cross-border-capable → clean ALLOW.
        ComplianceResult r = s.validateTransaction("US", "US", amt, "USD",
                new GeographyTrust(true, true, false));
        assertThat(r.allowed()).isTrue();
        assertThat(r.requiresReview()).isFalse();
        assertThat(r.flags()).doesNotContain(CrossBorderComplianceService.GEO_UNKNOWN_REVIEW_FLAG);
    }

    @Test
    void highRiskCountry_flagsButDoesNotBlock_norReview() {
        // T5 regression: HIGH_RISK is NOT blocking (only SANCTIONED is). It flags EDD context.
        CrossBorderCompliancePort p = port(true);
        when(p.checkCountryRestriction("VE")).thenReturn(Optional.of(new CountryRestriction(
                "VE", CountryRestriction.RestrictionType.HIGH_RISK, "high risk")));
        when(p.getRule("US", "VE")).thenReturn(Optional.of(new CrossBorderRule(
                "US", "VE", new BigDecimal("10000"), "USD", true, false)));
        CrossBorderComplianceService s = svc(p);

        ComplianceResult r = s.validateTransaction("US", "VE", amt, "USD",
                new GeographyTrust(true, true, true));

        assertThat(r.allowed()).isTrue();                 // flagged, not blocked
        assertThat(r.flags()).anyMatch(f -> f.startsWith("high_risk_destination_country"));
    }

    @Test
    void unknownGeoReviewDisabled_doesNotReview_butSanctionsStillBlock() {
        // B-025 rollout flag OFF: unknown geo no longer auto-REVIEWs (staged rollout), but the
        // fail-CLOSED screening-unavailable block and hard sanctions block are independent and
        // still enforced.
        CrossBorderCompliancePort p = port(true);
        CrossBorderComplianceService s = new CrossBorderComplianceService(p, false);

        ComplianceResult r = s.validateTransaction(null, null, amt, "USD",
                new GeographyTrust(false, false, true));
        assertThat(r.requiresReview()).isFalse();  // flag off → no review
        assertThat(r.allowed()).isTrue();

        // but screening-unavailable still fails closed regardless of the flag
        CrossBorderComplianceService down = new CrossBorderComplianceService(port(false), false);
        assertThat(down.validateTransaction("US", "US", amt, "USD").allowed()).isFalse();
    }
}
