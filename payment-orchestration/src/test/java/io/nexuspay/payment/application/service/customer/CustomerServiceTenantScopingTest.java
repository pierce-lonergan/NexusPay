package io.nexuspay.payment.application.service.customer;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.payment.application.port.out.CustomerRepository;
import io.nexuspay.payment.domain.customer.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-26 tests for {@link CustomerService}: the tenant-scoped by-id teeth that stop a tenant-A caller
 * from reading/mutating/deleting a tenant-B customer through the REST controller. Plain JUnit + Mockito
 * (no Spring). Mirrors {@code DisputeLifecycleServiceTenantScopingTest}.
 *
 * <p>Every REST-facing read/mutation resolves the customer via the tenant-scoped finder
 * ({@code findByIdAndTenantId}) and 404s ({@link ResourceNotFoundException}, via
 * {@code TenantOwnership.require}) on a foreign/absent id. There is NO unscoped finder to fall back to.</p>
 */
class CustomerServiceTenantScopingTest {

    private static final String TENANT = "t1";
    private static final String ATTACKER = "attacker";

    private CustomerRepository repo;
    private CustomerService svc;

    @BeforeEach
    void setUp() {
        repo = mock(CustomerRepository.class);
        svc = new CustomerService(repo);
        // save echoes its argument so the server-generated id flows back (do NOT hardcode it).
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Customer ownedCustomer() {
        Customer c = new Customer();
        c.setId("cus_existing");
        c.setTenantId(TENANT);
        c.setLivemode(false);
        c.setEmail("jane@example.com");
        c.setName("Jane");
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }

    // ---- (a) create: cus_ id + livemode stamped from caller ----

    @Test
    void createMintsCusId_andStampsLivemode() {
        Customer created = svc.create(TENANT, false, "jane@example.com", "Jane", "desc",
                Map.of("k", "v"));

        // server-generated id: assert the prefix + non-blank, never a hardcoded value.
        assertThat(created.getId()).startsWith("cus_");
        assertThat(created.getId()).isNotBlank();
        assertThat(created.getTenantId()).isEqualTo(TENANT);
        assertThat(created.isLivemode()).isFalse();
        verify(repo).save(any());
    }

    @Test
    void createLiveKeyStampsLivemodeTrue() {
        Customer created = svc.create(TENANT, true, null, null, null, null);
        assertThat(created.isLivemode()).isTrue();
        assertThat(created.getId()).startsWith("cus_");
    }

    // ---- (b) findById scoped to tenant, no oracle ----

    @Test
    void findByIdScopesToTenant() {
        Customer c = ownedCustomer();
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.of(c));

        assertThat(svc.findById("cus_existing", TENANT)).contains(c);
        verify(repo).findByIdAndTenantId("cus_existing", TENANT);
    }

    @Test
    void findByIdForeignTenantReturnsEmpty_noOracle() {
        when(repo.findByIdAndTenantId("cus_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThat(svc.findById("cus_victim", ATTACKER)).isEmpty();
        verify(repo).findByIdAndTenantId("cus_victim", ATTACKER);
    }

    // ---- (c) update: foreign tenant 404s, never saves; owned update transitions ----

    @Test
    void updateForeignTenantThrows404_andNeverSaves() {
        when(repo.findByIdAndTenantId("cus_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.update("cus_victim", ATTACKER, "evil@x.com", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repo).findByIdAndTenantId("cus_victim", ATTACKER);
        verify(repo, never()).save(any());
    }

    @Test
    void updateOwnedAppliesAndSaves() {
        Customer c = ownedCustomer();
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.of(c));

        Customer result = svc.update("cus_existing", TENANT, "new@example.com", "New Name", null, null);

        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getName()).isEqualTo("New Name");
        // unchanged field stays
        assertThat(result.isLivemode()).isFalse();
        verify(repo).findByIdAndTenantId("cus_existing", TENANT);
        verify(repo).save(c);
    }

    // ---- (d) delete: foreign 404s/never saves; owned sets deletedAt + bumps updatedAt ----

    @Test
    void deleteForeignTenantThrows404_andNeverSaves() {
        when(repo.findByIdAndTenantId("cus_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.delete("cus_victim", ATTACKER))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repo).findByIdAndTenantId("cus_victim", ATTACKER);
        verify(repo, never()).save(any());
    }

    @Test
    void deleteOwnedSetsDeletedAt_andBumpsUpdatedAt() {
        Customer c = ownedCustomer();
        Instant originalUpdated = c.getUpdatedAt();
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.of(c));

        svc.delete("cus_existing", TENANT);

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(repo).save(captor.capture());
        Customer saved = captor.getValue();
        assertThat(saved.getDeletedAt()).isNotNull();
        assertThat(saved.isDeleted()).isTrue();
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(originalUpdated);
    }

    // ---- (d2) soft-delete invisibility: the service trusts the soft-delete finder, so a deleted row no
    // longer surfaces from retrieve OR list. Proven behaviorally (not by name-pinning a derived query):
    // the tenant-scoped finder returns empty for the deleted id and the list finder omits it.

    @Test
    void afterSoftDelete_rowDisappearsFromRetrieveAndList() {
        Customer c = ownedCustomer();
        // Stage 1: the row is live — retrieve finds it, list contains it.
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.of(c));
        when(repo.findByTenant(TENANT, 20, 0)).thenReturn(java.util.List.of(c));
        assertThat(svc.findById("cus_existing", TENANT)).isPresent();
        assertThat(svc.listByTenant(TENANT, 20, 0)).contains(c);

        // Stage 2: soft-delete it, then model the soft-delete-aware finders excluding the deleted row
        // (this is the contract the JPA adapter's *DeletedAtIsNull finders implement).
        svc.delete("cus_existing", TENANT);
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.empty());
        when(repo.findByTenant(TENANT, 20, 0)).thenReturn(java.util.List.of());

        // The service surfaces NO deleted row from either read path (no resurrection, no oracle).
        assertThat(svc.findById("cus_existing", TENANT)).isEmpty();
        assertThat(svc.listByTenant(TENANT, 20, 0)).doesNotContain(c).isEmpty();
    }

    // ---- (PII) metadata sanitize on create AND update: PAN/card keys and __-prefixed server-reserved
    // control keys must be stripped before persist. L-069: the forbidden values are fed THROUGH the real
    // create/update path, not asserted absent on a fixture that never contained them.

    @Test
    void createStripsPanAndReservedKeysFromMetadata() {
        java.util.Map<String, Object> dirty = new java.util.LinkedHashMap<>();
        dirty.put("pan", "4111111111111111");        // PAN — must never be persisted
        dirty.put("card", java.util.Map.of("number", "4111", "cvc", "123")); // nested card material
        dirty.put("__livemode", true);                // server-reserved control key
        dirty.put("__test_outcome", "decline");       // server-reserved control key
        dirty.put("plan", "gold");                    // legitimate key — survives

        Customer created = svc.create(TENANT, false, null, null, null, dirty);

        java.util.Map<String, Object> stored = created.getMetadata();
        assertThat(stored).containsEntry("plan", "gold");
        assertThat(stored).doesNotContainKeys("pan", "card", "__livemode", "__test_outcome");
        // the smuggle is gone at every depth — no card subtree survived.
        assertThat(stored.values()).noneMatch(v -> String.valueOf(v).contains("4111111111111111"));
    }

    @Test
    void updateStripsPanAndReservedKeysFromMetadata() {
        Customer c = ownedCustomer();
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.of(c));

        java.util.Map<String, Object> dirty = new java.util.LinkedHashMap<>();
        dirty.put("pan", "4111111111111111");
        dirty.put("__livemode", true);
        dirty.put("note", "ok");

        Customer updated = svc.update("cus_existing", TENANT, null, null, null, dirty);

        java.util.Map<String, Object> stored = updated.getMetadata();
        assertThat(stored).containsEntry("note", "ok");
        assertThat(stored).doesNotContainKeys("pan", "__livemode");
    }

    @Test
    void updateNullMetadataLeavesExistingUnchanged() {
        Customer c = ownedCustomer();
        c.setMetadata(java.util.Map.of("plan", "gold"));
        when(repo.findByIdAndTenantId("cus_existing", TENANT)).thenReturn(Optional.of(c));

        // null metadata = leave unchanged; it must NOT be sanitized to {} (partial-update semantics).
        Customer updated = svc.update("cus_existing", TENANT, "new@example.com", null, null, null);

        assertThat(updated.getMetadata()).containsEntry("plan", "gold");
        assertThat(updated.getEmail()).isEqualTo("new@example.com");
    }
}
