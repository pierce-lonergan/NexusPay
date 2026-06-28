package io.nexuspay.payment.application.service.mandate;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.payment.application.port.out.MandateRepository;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.mandate.Mandate;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-3d SEC-26 / consent-integrity tests for {@link MandateService}. Plain JUnit + Mockito (no Spring)
 * mirroring {@code PaymentMethodServiceTest}: a mocked {@link MandateRepository} + {@link
 * PaymentMethodService}. {@code repo.save} echoes its argument so the server-generated id flows back (never
 * hardcoded — L-071).
 */
class MandateServiceTest {

    private static final String TENANT = "t1";
    private static final String ATTACKER = "attacker";
    private static final String CUS = "cus_owner";
    private static final String PM = "pm_owned";

    private MandateRepository repo;
    private PaymentMethodService pmService;
    private MandateService svc;

    @BeforeEach
    void setUp() {
        repo = mock(MandateRepository.class);
        pmService = mock(PaymentMethodService.class);
        svc = new MandateService(repo, pmService);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private PaymentMethod pm(String id, String tenant, String customer, boolean livemode) {
        PaymentMethod p = new PaymentMethod();
        p.setId(id);
        p.setTenantId(tenant);
        p.setCustomerId(customer);
        p.setLivemode(livemode);
        p.setType("card");
        p.setCredentialRef("pmref_test_pm_card_visa");
        return p;
    }

    // ---- (a) create from a tenant-owned pm_: mandate_ id, ACTIVE, livemode false, MULTI_USE, derived cus_ --

    @Test
    void create_fromOwnedPm_mintsActiveMandate_customerDerivedFromPm() {
        when(pmService.findById(PM, TENANT)).thenReturn(Optional.of(pm(PM, TENANT, CUS, false)));

        Mandate m = svc.create(TENANT, /*livemode*/ false, /*isTest*/ true,
                PM, /*type*/ null, "recurring", null);

        assertThat(m.getId()).startsWith("mandate_").isNotBlank(); // server-generated — assert prefix (L-071)
        assertThat(m.getTenantId()).isEqualTo(TENANT);
        assertThat(m.getCustomerId()).isEqualTo(CUS);              // DERIVED from the pm_'s owner
        assertThat(m.getPaymentMethodId()).isEqualTo(PM);
        assertThat(m.getStatus()).isEqualTo(Mandate.STATUS_ACTIVE);
        assertThat(m.isLivemode()).isFalse();
        assertThat(m.getType()).isEqualTo(Mandate.TYPE_MULTI_USE); // default
        assertThat(m.getScenario()).isEqualTo("recurring");
        assertThat(m.getRevokedAt()).isNull();
        verify(repo).save(any());
    }

    @Test
    void create_explicitSingleUseType_isAccepted() {
        when(pmService.findById(PM, TENANT)).thenReturn(Optional.of(pm(PM, TENANT, CUS, false)));

        Mandate m = svc.create(TENANT, false, true, PM, "SINGLE_USE", null, null);
        assertThat(m.getType()).isEqualTo(Mandate.TYPE_SINGLE_USE);
    }

    @Test
    void create_invalidType_throws400_neverSaves() {
        when(pmService.findById(PM, TENANT)).thenReturn(Optional.of(pm(PM, TENANT, CUS, false)));

        assertThatThrownBy(() -> svc.create(TENANT, false, true, PM, "BOGUS", null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "invalid_type");
        verify(repo, never()).save(any());
    }

    // ---- (b) create citing a FOREIGN pm_ -> 404 no-oracle, never saves ----

    @Test
    void create_foreignPm_throws404_neverSaves() {
        when(pmService.findById("pm_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.create(ATTACKER, false, true, "pm_victim", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).save(any());
    }

    // ---- (c) livemode mismatch -> 400, never saves ----

    @Test
    void create_livemodeMismatch_throws400_neverSaves() {
        // caller key is TEST (livemode=false) but the pm_ is live (livemode=true) -> mismatch.
        when(pmService.findById(PM, TENANT)).thenReturn(Optional.of(pm(PM, TENANT, CUS, true)));

        assertThatThrownBy(() -> svc.create(TENANT, /*livemode*/ false, /*isTest*/ true,
                PM, null, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "livemode_mismatch");
        verify(repo, never()).save(any());
    }

    // ---- (d) revoke -> INACTIVE + revokedAt set + updatedAt bumped ----

    @Test
    void revoke_setsInactive_stampsRevokedAt_bumpsUpdatedAt() {
        Mandate m = Mandate.create(TENANT, CUS, PM, false, Mandate.TYPE_MULTI_USE, null, null);
        m.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        m.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        Instant originalUpdated = m.getUpdatedAt();
        when(repo.findByIdAndTenantId(m.getId(), TENANT)).thenReturn(Optional.of(m));

        svc.revoke(m.getId(), TENANT);

        ArgumentCaptor<Mandate> captor = ArgumentCaptor.forClass(Mandate.class);
        verify(repo).save(captor.capture());
        Mandate saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Mandate.STATUS_INACTIVE);
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getRevokedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(originalUpdated);
    }

    @Test
    void revoke_foreignTenant_throws404_neverSaves() {
        when(repo.findByIdAndTenantId("mandate_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.revoke("mandate_victim", ATTACKER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).save(any());
    }

    // ---- (e) a revoked mandate is STILL retrievable (INACTIVE, not empty) ----

    @Test
    void findById_revokedMandate_stillReturnedInactive() {
        Mandate m = Mandate.create(TENANT, CUS, PM, false, Mandate.TYPE_MULTI_USE, null, null);
        m.revoke();
        when(repo.findByIdAndTenantId(m.getId(), TENANT)).thenReturn(Optional.of(m));

        Optional<Mandate> result = svc.findById(m.getId(), TENANT);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(Mandate.STATUS_INACTIVE);
        assertThat(result.get().isActive()).isFalse();
    }

    @Test
    void findById_foreignTenant_returnsEmpty_noOracle() {
        when(repo.findByIdAndTenantId("mandate_victim", ATTACKER)).thenReturn(Optional.empty());
        assertThat(svc.findById("mandate_victim", ATTACKER)).isEmpty();
    }

    // ---- (f) validateActiveForCharge: the off-session consent gate ----

    @Test
    void validateActiveForCharge_activeMatchingPm_passes() {
        Mandate m = Mandate.create(TENANT, CUS, PM, false, Mandate.TYPE_MULTI_USE, null, null);
        when(repo.findByIdAndTenantId(m.getId(), TENANT)).thenReturn(Optional.of(m));

        // no throw
        svc.validateActiveForCharge(m.getId(), TENANT, PM);
    }

    @Test
    void validateActiveForCharge_foreignOrMissing_throws404() {
        when(repo.findByIdAndTenantId("mandate_foreign", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.validateActiveForCharge("mandate_foreign", TENANT, PM))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validateActiveForCharge_inactive_throws400_invalidMandate() {
        Mandate m = Mandate.create(TENANT, CUS, PM, false, Mandate.TYPE_MULTI_USE, null, null);
        m.revoke(); // INACTIVE
        when(repo.findByIdAndTenantId(m.getId(), TENANT)).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> svc.validateActiveForCharge(m.getId(), TENANT, PM))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "invalid_mandate");
    }

    @Test
    void validateActiveForCharge_pmMismatch_throws400_mismatch() {
        Mandate m = Mandate.create(TENANT, CUS, PM, false, Mandate.TYPE_MULTI_USE, null, null);
        when(repo.findByIdAndTenantId(m.getId(), TENANT)).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> svc.validateActiveForCharge(m.getId(), TENANT, "pm_other"))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "mandate_payment_method_mismatch");
    }
}
