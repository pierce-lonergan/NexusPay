package io.nexuspay.payment.adapter.out.persistence.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.domain.customer.Customer;
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
 * TEST-3a: entity<->domain mapping coverage for {@link JpaCustomerRepositoryAdapter} — the metadata JSON
 * round-trip, the deletedAt carry-through, and that the SOFT-DELETE-aware finders are the ones called.
 *
 * <p>L-071: the {@code ObjectMapper} is built via {@code new ObjectMapper().findAndRegisterModules()}
 * (Customer carries an Instant-derived time + a metadata Map) — a bare {@code new ObjectMapper()} would
 * throw on Instant and fail the whole class.</p>
 */
class JpaCustomerRepositoryAdapterTest {

    // The Spring Data interface is package-private (nested in the adapter); mock it via its type.
    private JpaCustomerRepositoryAdapter.JpaCustomerRepo jpaRepo;
    private JpaCustomerRepositoryAdapter adapter;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jpaRepo = mock(JpaCustomerRepositoryAdapter.JpaCustomerRepo.class);
        adapter = new JpaCustomerRepositoryAdapter(jpaRepo, mapper);
    }

    @Test
    void saveSerializesMetadataToJson() {
        Customer c = Customer.create("t1", false, "jane@x.com", "Jane", "desc",
                Map.of("plan", "gold"));

        adapter.save(c);

        ArgumentCaptor<CustomerEntity> captor = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(jpaRepo).save(captor.capture());
        CustomerEntity e = captor.getValue();
        assertThat(e.getId()).startsWith("cus_");
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.isLivemode()).isFalse();
        assertThat(e.getMetadataJson()).contains("plan").contains("gold");
        assertThat(e.getDeletedAt()).isNull();
    }

    @Test
    void findByIdAndTenantId_usesSoftDeleteFinder_andRoundTripsMetadataAndDeletedAt() {
        CustomerEntity e = new CustomerEntity();
        e.setId("cus_1");
        e.setTenantId("t1");
        e.setLivemode(true);
        e.setEmail("jane@x.com");
        e.setMetadataJson("{\"plan\":\"gold\"}");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        e.setCreatedAt(created);
        e.setUpdatedAt(created);
        e.setDeletedAt(null);
        when(jpaRepo.findByIdAndTenantIdAndDeletedAtIsNull("cus_1", "t1")).thenReturn(Optional.of(e));

        Optional<Customer> result = adapter.findByIdAndTenantId("cus_1", "t1");

        assertThat(result).isPresent();
        Customer c = result.get();
        assertThat(c.getMetadata()).containsEntry("plan", "gold");
        assertThat(c.isLivemode()).isTrue();
        // the soft-delete-aware finder is the one used (no oracle, no resurrection).
        verify(jpaRepo).findByIdAndTenantIdAndDeletedAtIsNull("cus_1", "t1");
    }

    @Test
    void findByTenant_usesSoftDeleteFinder_andMapsDeletedAt() {
        CustomerEntity e = new CustomerEntity();
        e.setId("cus_1");
        e.setTenantId("t1");
        e.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        e.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(jpaRepo.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(eq("t1"), any(PageRequest.class)))
                .thenReturn(List.of(e));

        List<Customer> result = adapter.findByTenant("t1", 20, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDeletedAt()).isNull();
        verify(jpaRepo).findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(eq("t1"), any(PageRequest.class));
    }

    @Test
    void softDeleteFinderNameIsTheExpectedDerivedQuery() throws NoSuchMethodException {
        // Pin the derived-query method names so a rename can't silently drop the deleted_at filter.
        Method byId = JpaCustomerRepositoryAdapter.JpaCustomerRepo.class
                .getMethod("findByIdAndTenantIdAndDeletedAtIsNull", String.class, String.class);
        Method list = JpaCustomerRepositoryAdapter.JpaCustomerRepo.class
                .getMethod("findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc", String.class, PageRequest.class);
        assertThat(byId.getName()).contains("DeletedAtIsNull");
        assertThat(list.getName()).contains("DeletedAtIsNull");
    }
}
