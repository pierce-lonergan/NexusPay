package io.nexuspay.payment.application.service.paymentmethod;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.payment.application.port.out.PaymentMethodRepository;
import io.nexuspay.payment.application.service.customer.CustomerService;
import io.nexuspay.payment.domain.customer.Customer;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-3b SEC-26 / PCI tests for {@link PaymentMethodService}. Plain JUnit + Mockito (no Spring) mirroring
 * {@code CustomerServiceTenantScopingTest}: a mocked {@link PaymentMethodRepository} + {@link
 * CustomerService} and the REAL {@link io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures}
 * registry. {@code repo.save} echoes its argument so the server-generated id flows back (never hardcoded —
 * L-071).
 */
class PaymentMethodServiceTest {

    private static final String TENANT = "t1";
    private static final String ATTACKER = "attacker";
    private static final String CUS = "cus_existing";

    private PaymentMethodRepository repo;
    private CustomerService customerService;
    private PaymentMethodService svc;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentMethodRepository.class);
        customerService = mock(CustomerService.class);
        svc = new PaymentMethodService(repo, customerService);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Customer customer(String tenant, boolean livemode) {
        Customer c = new Customer();
        c.setId(CUS);
        c.setTenantId(tenant);
        c.setLivemode(livemode);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }

    // ---- (a) TEST attach with pm_card_visa: visa/4242, livemode=false, pm_ id, synthetic ref, NO PAN ----

    @Test
    void attachTestFixtureVisa_storesDisplayAndSyntheticRef_noPan() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, false)));

        PaymentMethod pm = svc.attach(TENANT, CUS, /*livemode*/ false, /*isTest*/ true,
                "card", "pm_card_visa", null, null, null, null, null, null);

        assertThat(pm.getId()).startsWith("pm_").isNotBlank(); // server-generated — assert prefix (L-071)
        assertThat(pm.getTenantId()).isEqualTo(TENANT);
        assertThat(pm.getCustomerId()).isEqualTo(CUS);
        assertThat(pm.isLivemode()).isFalse();
        assertThat(pm.getBrand()).isEqualTo("visa");
        assertThat(pm.getLast4()).isEqualTo("4242");
        assertThat(pm.getExpMonth()).isEqualTo(12);
        assertThat(pm.getExpYear()).isEqualTo(2034);
        assertThat(pm.getFunding()).isEqualTo("credit");
        // the synthetic opaque ref the future 3c charge resolves — NOT a PAN.
        assertThat(pm.getCredentialRef()).isEqualTo("pmref_test_pm_card_visa");

        // PCI: no field anywhere holds a 16-digit PAN.
        assertThat(pm.getCredentialRef()).doesNotContainPattern("\\d{13,19}");
        assertThat(pm.getLast4()).hasSizeLessThanOrEqualTo(4);
        verify(repo).save(any());
    }

    // ---- (b) LIVE-key supplying a fixture token -> 400 (mode gate) ----

    @Test
    void attachFixtureUnderLiveKey_throws400_modeGate() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        assertThatThrownBy(() -> svc.attach(TENANT, CUS, /*livemode*/ true, /*isTest*/ false,
                "card", "pm_card_visa", null, null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void attachUnknownFixture_throws400() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, false)));

        assertThatThrownBy(() -> svc.attach(TENANT, CUS, false, true,
                "card", "pm_card_nope", null, null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(repo, never()).save(any());
    }

    // ---- LIVE/opaque path: a real opaque token is stored verbatim, display from request ----

    @Test
    void attachOpaqueLiveToken_storesVerbatim_displayFromRequest() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        PaymentMethod pm = svc.attach(TENANT, CUS, /*livemode*/ true, /*isTest*/ false,
                "card", "ptok_live_abc123", "visa", "1111", 1, 2030, "debit", null);

        assertThat(pm.getCredentialRef()).isEqualTo("ptok_live_abc123"); // verbatim, no parse
        assertThat(pm.getBrand()).isEqualTo("visa");
        assertThat(pm.getLast4()).isEqualTo("1111");
        assertThat(pm.isLivemode()).isTrue();
    }

    // ---- PCI value-level backstop: a raw PAN in the live-path credential_ref / last4 -> 400, never saves ----

    @Test
    void attachLiveCredentialRefIsRawPan_throws400_neverSaves() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        // credential_ref is the field most likely to receive a pasted card number — a 16-digit PAN-shaped
        // value (built programmatically, never a literal card number) is rejected before persist, never
        // stored verbatim in the indexed credential_ref column.
        String panShapedRef = "9".repeat(16);
        assertThatThrownBy(() -> svc.attach(TENANT, CUS, /*livemode*/ true, /*isTest*/ false,
                "card", panShapedRef, "visa", "1111", 1, 2030, "credit", null))
                .isInstanceOf(InvalidRequestException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void attachLiveCredentialRefIsSpacedPan_throws400_neverSaves() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        // Spaced PAN presentations are normalized (spaces stripped) before the shape check. The spaced
        // value is assembled programmatically so no literal card number ever appears.
        String spacedPan = String.join(" ", "9".repeat(4), "9".repeat(4), "9".repeat(4), "9".repeat(4));
        assertThatThrownBy(() -> svc.attach(TENANT, CUS, true, false,
                "card", spacedPan, "visa", "1111", 1, 2030, "credit", null))
                .isInstanceOf(InvalidRequestException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void attachLiveLast4IsFullPan_throws400_neverSaves() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        // last4 must be exactly 4 digits — a full 16-digit PAN-shaped last4 (built programmatically, never a
        // literal card number) is rejected at the service boundary, not left to the DB column width (which
        // would 500 or silently truncate).
        String panShapedLast4 = "9".repeat(16);
        assertThatThrownBy(() -> svc.attach(TENANT, CUS, true, false,
                "card", "ptok_live_ok", "visa", panShapedLast4, 1, 2030, "credit", null))
                .isInstanceOf(InvalidRequestException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void attachLiveLast4ExactlyFourDigits_isAccepted() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        PaymentMethod pm = svc.attach(TENANT, CUS, true, false,
                "card", "ptok_live_ok", "visa", "4242", 1, 2030, "credit", null);
        assertThat(pm.getLast4()).isEqualTo("4242");
        verify(repo).save(any());
    }

    // ---- (c) cross-tenant: foreign customer -> attach/list 404, never saves ----

    @Test
    void attachForeignCustomer_throws404_neverSaves() {
        when(customerService.findById("cus_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.attach(ATTACKER, "cus_victim", false, true,
                "card", "pm_card_visa", null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void listForeignCustomer_throws404_notEmptyList() {
        when(customerService.findById("cus_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.listByCustomer("cus_victim", ATTACKER, 20, 0))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).findByCustomerAndTenant(any(), any(), anyInt(), anyInt());
    }

    @Test
    void retrieveForeignTenant_returnsEmpty_noOracle() {
        when(repo.findByIdAndTenantId("pm_victim", ATTACKER)).thenReturn(Optional.empty());
        assertThat(svc.findById("pm_victim", ATTACKER)).isEmpty();
    }

    @Test
    void detachForeignTenant_throws404_neverSaves() {
        when(repo.findByIdAndTenantId("pm_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.detach("pm_victim", ATTACKER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).save(any());
    }

    // ---- (d) livemode mismatch: test method onto a live customer -> 400 ----

    @Test
    void attachTestMethodOntoLiveCustomer_throws400() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        // caller key is TEST (livemode=false) but the customer is live (livemode=true) -> mismatch.
        assertThatThrownBy(() -> svc.attach(TENANT, CUS, /*livemode*/ false, /*isTest*/ true,
                "card", "pm_card_visa", null, null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(repo, never()).save(any());
    }

    // ---- (f) detach soft-deletes: sets deletedAt + bumps updatedAt; then it disappears ----

    @Test
    void detach_softDeletes_thenRetrieveAndListNoLongerSurface() {
        PaymentMethod pm = PaymentMethod.create(TENANT, CUS, false, "card",
                "visa", "4242", 12, 2034, "credit", "pmref_test_pm_card_visa", null);
        pm.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        pm.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        Instant originalUpdated = pm.getUpdatedAt();
        when(repo.findByIdAndTenantId(pm.getId(), TENANT)).thenReturn(Optional.of(pm));

        svc.detach(pm.getId(), TENANT);

        ArgumentCaptor<PaymentMethod> captor = ArgumentCaptor.forClass(PaymentMethod.class);
        verify(repo).save(captor.capture());
        PaymentMethod saved = captor.getValue();
        assertThat(saved.getDeletedAt()).isNotNull();
        assertThat(saved.isDeleted()).isTrue();
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(originalUpdated);

        // Model the soft-delete-aware finders excluding the detached row (the JPA adapter's *DeletedAtIsNull
        // contract): retrieve empty + the customer's list omits it.
        when(repo.findByIdAndTenantId(pm.getId(), TENANT)).thenReturn(Optional.empty());
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, false)));
        when(repo.findByCustomerAndTenant(CUS, TENANT, 20, 0)).thenReturn(java.util.List.of());

        assertThat(svc.findById(pm.getId(), TENANT)).isEmpty();
        assertThat(svc.listByCustomer(CUS, TENANT, 20, 0)).doesNotContain(pm).isEmpty();
    }

    // ---- (g) metadata carrying card/pan/cvc keys is stripped before persist (L-069, fed through real path) ----

    @Test
    void attachStripsPanAndReservedKeysFromMetadata() {
        when(customerService.findById(CUS, TENANT)).thenReturn(Optional.of(customer(TENANT, true)));

        // PAN-shaped metadata values are assembled programmatically so no literal card number appears.
        String panShaped = "9".repeat(16);
        Map<String, Object> dirty = new LinkedHashMap<>();
        dirty.put("pan", panShaped);
        dirty.put("card", Map.of("number", panShaped, "cvc", "123"));
        dirty.put("__livemode", true);
        dirty.put("note", "ok");

        PaymentMethod pm = svc.attach(TENANT, CUS, true, false,
                "card", "ptok_live_abc", "visa", "1111", 1, 2030, "credit", dirty);

        Map<String, Object> stored = pm.getMetadata();
        assertThat(stored).containsEntry("note", "ok");
        assertThat(stored).doesNotContainKeys("pan", "card", "__livemode");
        assertThat(stored.values()).noneMatch(v -> String.valueOf(v).contains(panShaped));
    }
}
