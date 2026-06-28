package io.nexuspay.payment.application.service.clock;

import io.nexuspay.payment.application.port.out.TestClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-078 (critique v3 F5): unit-pins {@link TestClockService} over a mocked tenant-scoped repository.
 *
 * <ul>
 *   <li>{@code nowFor} returns the frozen instant when a row exists, else real {@code Instant.now()} (within
 *       tolerance) when absent or the tenant is null/blank;</li>
 *   <li>{@code set} upserts; {@code clear} deletes -> {@code nowFor} reverts to real; {@code get} reflects
 *       set/clear (Optional present/empty);</li>
 *   <li>only the TENANT-SCOPED finder is used (no unscoped repository call exists/used).</li>
 * </ul>
 */
class TestClockServiceTest {

    private static final String TENANT = "tenant-A";
    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private TestClockRepository repo;
    private TestClockService service;

    @BeforeEach
    void setUp() {
        repo = mock(TestClockRepository.class);
        service = new TestClockService(repo);
    }

    @Test
    void nowFor_returnsFrozenInstant_whenRowExists() {
        when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(FIXED));

        assertThat(service.nowFor(TENANT)).isEqualTo(FIXED);
        // tenant-scoped finder only.
        verify(repo).findByTenantId(TENANT);
    }

    @Test
    void nowFor_returnsRealTime_whenNoRow() {
        when(repo.findByTenantId(TENANT)).thenReturn(Optional.empty());
        Instant before = Instant.now();

        Instant now = service.nowFor(TENANT);

        assertThat(now).isAfterOrEqualTo(before);
        assertThat(now).isNotEqualTo(FIXED);
    }

    @Test
    void nowFor_nullOrBlankTenant_returnsRealTime_neverHitsRepo() {
        Instant before = Instant.now();

        assertThat(service.nowFor(null)).isAfterOrEqualTo(before);
        assertThat(service.nowFor("  ")).isAfterOrEqualTo(before);

        verify(repo, never()).findByTenantId(anyString());
    }

    @Test
    void set_upsertsTheFrozenInstant() {
        service.set(TENANT, FIXED);

        verify(repo).upsert(TENANT, FIXED);
    }

    @Test
    void set_nullInstant_throws_andDoesNotUpsert() {
        assertThatThrownBy(() -> service.set(TENANT, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).upsert(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clear_deletesTheRow() {
        service.clear(TENANT);

        verify(repo).deleteByTenantId(TENANT);
    }

    @Test
    void get_reflectsSetThenClear() {
        when(repo.findByTenantId(TENANT)).thenReturn(Optional.of(FIXED));
        assertThat(service.get(TENANT)).contains(FIXED);

        when(repo.findByTenantId(TENANT)).thenReturn(Optional.empty());
        assertThat(service.get(TENANT)).isEmpty();
    }

    @Test
    void get_nullOrBlankTenant_isEmpty_neverHitsRepo() {
        assertThat(service.get(null)).isEmpty();
        assertThat(service.get(" ")).isEmpty();
        verify(repo, never()).findByTenantId(anyString());
    }
}
