package io.nexuspay.payment.adapter.out.persistence.mandate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.domain.mandate.Mandate;
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
 * TEST-3d: entity<->domain mapping coverage for {@link JpaMandateRepositoryAdapter} — the metadata JSON
 * round-trip, the status/type/scenario/revokedAt carry-through, that the TENANT-scoped (NOT soft-delete)
 * finders are the ones called, and — the key divergence from the pm adapter — that a REVOKED mandate is
 * STILL returned by {@code findByIdAndTenantId} (no deleted-filter).
 *
 * <p>L-071: the {@code ObjectMapper} is built via {@code new ObjectMapper().findAndRegisterModules()}
 * (Mandate carries an Instant-derived time + a metadata Map).</p>
 */
class JpaMandateRepositoryAdapterTest {

    private JpaMandateRepositoryAdapter.JpaMandateRepo jpaRepo;
    private JpaMandateRepositoryAdapter adapter;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        jpaRepo = mock(JpaMandateRepositoryAdapter.JpaMandateRepo.class);
        adapter = new JpaMandateRepositoryAdapter(jpaRepo, mapper);
    }

    @Test
    void saveSerializesMetadata_stampsMandateId_carriesAllFields() {
        Mandate m = Mandate.create("t1", "cus_1", "pm_1", false,
                Mandate.TYPE_MULTI_USE, "recurring", Map.of("plan", "gold"));

        adapter.save(m);

        ArgumentCaptor<MandateEntity> captor = ArgumentCaptor.forClass(MandateEntity.class);
        verify(jpaRepo).save(captor.capture());
        MandateEntity e = captor.getValue();
        assertThat(e.getId()).startsWith("mandate_");
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getCustomerId()).isEqualTo("cus_1");
        assertThat(e.getPaymentMethodId()).isEqualTo("pm_1");
        assertThat(e.getStatus()).isEqualTo(Mandate.STATUS_ACTIVE);
        assertThat(e.getType()).isEqualTo(Mandate.TYPE_MULTI_USE);
        assertThat(e.getScenario()).isEqualTo("recurring");
        assertThat(e.isLivemode()).isFalse();
        assertThat(e.getMetadataJson()).contains("plan").contains("gold");
        assertThat(e.getRevokedAt()).isNull();
    }

    @Test
    void findByIdAndTenantId_usesTenantScopedFinder_roundTripsAllFields() {
        MandateEntity e = new MandateEntity();
        e.setId("mandate_1");
        e.setTenantId("t1");
        e.setCustomerId("cus_1");
        e.setPaymentMethodId("pm_1");
        e.setStatus(Mandate.STATUS_ACTIVE);
        e.setType(Mandate.TYPE_SINGLE_USE);
        e.setScenario("unscheduled");
        e.setLivemode(true);
        e.setMetadataJson("{\"plan\":\"gold\"}");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        e.setCreatedAt(created);
        e.setUpdatedAt(created);
        e.setRevokedAt(null);
        when(jpaRepo.findByIdAndTenantId("mandate_1", "t1")).thenReturn(Optional.of(e));

        Optional<Mandate> result = adapter.findByIdAndTenantId("mandate_1", "t1");

        assertThat(result).isPresent();
        Mandate m = result.get();
        assertThat(m.getMetadata()).containsEntry("plan", "gold");
        assertThat(m.isLivemode()).isTrue();
        assertThat(m.getPaymentMethodId()).isEqualTo("pm_1");
        assertThat(m.getType()).isEqualTo(Mandate.TYPE_SINGLE_USE);
        assertThat(m.getScenario()).isEqualTo("unscheduled");
        verify(jpaRepo).findByIdAndTenantId("mandate_1", "t1");
    }

    @Test
    void findByIdAndTenantId_returnsRevokedMandate_noDeletedFilter() {
        // The divergence from the pm adapter: a REVOKED (INACTIVE, revoked_at set) row is STILL returned.
        MandateEntity e = new MandateEntity();
        e.setId("mandate_revoked");
        e.setTenantId("t1");
        e.setCustomerId("cus_1");
        e.setPaymentMethodId("pm_1");
        e.setStatus(Mandate.STATUS_INACTIVE);
        e.setType(Mandate.TYPE_MULTI_USE);
        e.setLivemode(false);
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        e.setCreatedAt(created);
        e.setUpdatedAt(created);
        e.setRevokedAt(Instant.parse("2026-02-01T00:00:00Z"));
        when(jpaRepo.findByIdAndTenantId("mandate_revoked", "t1")).thenReturn(Optional.of(e));

        Optional<Mandate> result = adapter.findByIdAndTenantId("mandate_revoked", "t1");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(Mandate.STATUS_INACTIVE);
        assertThat(result.get().getRevokedAt()).isNotNull();
    }

    @Test
    void findByTenant_usesTenantScopedFinder_newestFirst() {
        MandateEntity e = new MandateEntity();
        e.setId("mandate_1");
        e.setTenantId("t1");
        e.setCustomerId("cus_1");
        e.setPaymentMethodId("pm_1");
        e.setStatus(Mandate.STATUS_ACTIVE);
        e.setType(Mandate.TYPE_MULTI_USE);
        e.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        e.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(jpaRepo.findByTenantIdOrderByCreatedAtDesc(eq("t1"), any(PageRequest.class)))
                .thenReturn(List.of(e));

        List<Mandate> result = adapter.findByTenant("t1", 20, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("t1");
        verify(jpaRepo).findByTenantIdOrderByCreatedAtDesc(eq("t1"), any(PageRequest.class));
    }

    @Test
    void byIdFinderName_hasNoDeletedAtFilter() throws NoSuchMethodException {
        // Pin the by-id finder name so a refactor can't silently ADD a deleted/status filter that would
        // make a revoked mandate 404 (the prompt requires it stay retrievable).
        Method byId = JpaMandateRepositoryAdapter.JpaMandateRepo.class
                .getMethod("findByIdAndTenantId", String.class, String.class);
        assertThat(byId.getName()).isEqualTo("findByIdAndTenantId");
        assertThat(byId.getName()).doesNotContain("DeletedAt");
        assertThat(byId.getName()).doesNotContain("Status");
    }
}
