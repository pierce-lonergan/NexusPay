package io.nexuspay.payment.adapter.out.persistence.paymentmethod;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-3b: entity<->domain mapping coverage for {@link JpaPaymentMethodRepositoryAdapter} — the metadata
 * JSON round-trip, the deletedAt + display-field carry-through, that the SOFT-DELETE-aware finders are the
 * ones called, and that NO PAN ever lands in the entity.
 *
 * <p>L-071: the {@code ObjectMapper} is built via {@code new ObjectMapper().findAndRegisterModules()}
 * (PaymentMethod carries an Instant-derived time + a metadata Map).</p>
 */
class JpaPaymentMethodRepositoryAdapterTest {

    private JpaPaymentMethodRepositoryAdapter.JpaPaymentMethodRepo jpaRepo;
    private JpaPaymentMethodRepositoryAdapter adapter;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        jpaRepo = mock(JpaPaymentMethodRepositoryAdapter.JpaPaymentMethodRepo.class);
        adapter = new JpaPaymentMethodRepositoryAdapter(jpaRepo, mapper);
    }

    @Test
    void saveSerializesMetadata_stampsPmId_carriesDisplayAndCredentialRef_noPan() {
        PaymentMethod pm = PaymentMethod.create("t1", "cus_1", false, "card",
                "visa", "4242", 12, 2034, "credit", "pmref_test_pm_card_visa",
                Map.of("plan", "gold"));

        adapter.save(pm);

        ArgumentCaptor<PaymentMethodEntity> captor = ArgumentCaptor.forClass(PaymentMethodEntity.class);
        verify(jpaRepo).save(captor.capture());
        PaymentMethodEntity e = captor.getValue();
        assertThat(e.getId()).startsWith("pm_");
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getCustomerId()).isEqualTo("cus_1");
        assertThat(e.isLivemode()).isFalse();
        assertThat(e.getBrand()).isEqualTo("visa");
        assertThat(e.getLast4()).isEqualTo("4242");
        assertThat(e.getCredentialRef()).isEqualTo("pmref_test_pm_card_visa");
        assertThat(e.getMetadataJson()).contains("plan").contains("gold");
        assertThat(e.getDeletedAt()).isNull();
        // PCI: no 16-digit PAN anywhere in the entity's persisted strings.
        assertThat(e.getCredentialRef()).doesNotContainPattern("\\d{13,19}");
        assertThat(e.getLast4()).hasSizeLessThanOrEqualTo(4);
    }

    @Test
    void findByIdAndTenantId_usesSoftDeleteFinder_andRoundTripsMetadataDeletedAtDisplay() {
        PaymentMethodEntity e = new PaymentMethodEntity();
        e.setId("pm_1");
        e.setTenantId("t1");
        e.setCustomerId("cus_1");
        e.setLivemode(true);
        e.setType("card");
        e.setBrand("amex");
        e.setLast4("0005");
        e.setExpMonth(12);
        e.setExpYear(2034);
        e.setFunding("credit");
        e.setCredentialRef("ptok_live_x");
        e.setMetadataJson("{\"plan\":\"gold\"}");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        e.setCreatedAt(created);
        e.setUpdatedAt(created);
        e.setDeletedAt(null);
        when(jpaRepo.findByIdAndTenantIdAndDeletedAtIsNull("pm_1", "t1")).thenReturn(Optional.of(e));

        Optional<PaymentMethod> result = adapter.findByIdAndTenantId("pm_1", "t1");

        assertThat(result).isPresent();
        PaymentMethod pm = result.get();
        assertThat(pm.getMetadata()).containsEntry("plan", "gold");
        assertThat(pm.isLivemode()).isTrue();
        assertThat(pm.getBrand()).isEqualTo("amex");
        assertThat(pm.getLast4()).isEqualTo("0005");
        assertThat(pm.getCredentialRef()).isEqualTo("ptok_live_x");
        assertThat(pm.getDeletedAt()).isNull();
        verify(jpaRepo).findByIdAndTenantIdAndDeletedAtIsNull("pm_1", "t1");
    }

    @Test
    void findByCustomerAndTenant_usesSoftDeleteFinder() {
        PaymentMethodEntity e = new PaymentMethodEntity();
        e.setId("pm_1");
        e.setTenantId("t1");
        e.setCustomerId("cus_1");
        e.setType("card");
        e.setCredentialRef("pmref_test_pm_card_visa");
        e.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        e.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(jpaRepo.findByCustomerIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq("cus_1"), eq("t1"), any(PageRequest.class))).thenReturn(List.of(e));

        List<PaymentMethod> result = adapter.findByCustomerAndTenant("cus_1", "t1", 20, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerId()).isEqualTo("cus_1");
        verify(jpaRepo).findByCustomerIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq("cus_1"), eq("t1"), any(PageRequest.class));
    }

    @Test
    void softDeleteFinderNamesAreTheExpectedDerivedQueries() throws NoSuchMethodException {
        // Pin both derived-query method names so a rename can't silently drop the deleted_at filter.
        Method byId = JpaPaymentMethodRepositoryAdapter.JpaPaymentMethodRepo.class
                .getMethod("findByIdAndTenantIdAndDeletedAtIsNull", String.class, String.class);
        Method list = JpaPaymentMethodRepositoryAdapter.JpaPaymentMethodRepo.class
                .getMethod("findByCustomerIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc",
                        String.class, String.class, PageRequest.class);
        assertThat(byId.getName()).contains("DeletedAtIsNull");
        assertThat(list.getName()).contains("DeletedAtIsNull");
    }
}
