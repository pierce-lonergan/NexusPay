package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.payment.adapter.out.persistence.ScreeningOriginEntity;
import io.nexuspay.payment.adapter.out.persistence.ScreeningOriginRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-07 (B-007): {@link ScreeningOriginService#assertOwnedBy} is the fail-closed tenant-ownership gate
 * used by every payment-lifecycle path (get/capture/cancel/confirm/refund). The trusted ownership source
 * is the server-owned origin store; an absent origin and a wrong-tenant origin BOTH collapse to a 404
 * ({@link ResourceNotFoundException}) so there is no cross-tenant existence oracle. A matching origin
 * passes.
 *
 * <p>Each assertion fails if the corresponding branch of {@code assertOwnedBy} is weakened — e.g. if the
 * absent-origin case stopped throwing (fail-OPEN), or the tenant comparison were dropped.</p>
 */
class ScreeningOriginServiceAssertOwnedByTest {

    private ScreeningOriginRepository repository;
    private ScreeningOriginService service;

    @BeforeEach
    void setUp() {
        repository = mock(ScreeningOriginRepository.class);
        service = new ScreeningOriginService(repository);
    }

    private ScreeningOriginEntity origin(String paymentId, String tenantId) {
        return new ScreeningOriginEntity(paymentId, tenantId, ScreeningMode.INTERACTIVE.name(), Instant.now());
    }

    @Test
    void passesWhenOriginTenantMatchesCaller() {
        when(repository.findById("pay_a")).thenReturn(Optional.of(origin("pay_a", "tenant-A")));

        assertThatCode(() -> service.assertOwnedBy("pay_a", "tenant-A"))
                .as("matching tenant must be allowed")
                .doesNotThrowAnyException();
    }

    @Test
    void throwsNotFoundWhenOriginBelongsToAnotherTenant() {
        when(repository.findById("pay_a")).thenReturn(Optional.of(origin("pay_a", "tenant-A")));

        assertThatThrownBy(() -> service.assertOwnedBy("pay_a", "tenant-B"))
                .as("a wrong-tenant id must 404 (no existence oracle), never authorize the operation")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void throwsNotFoundWhenNoOriginExists_failClosed() {
        when(repository.findById("pay_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertOwnedBy("pay_missing", "tenant-A"))
                .as("absent origin must FAIL CLOSED (404) — we cannot prove ownership, so we must not act")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void throwsNotFoundWhenOriginTenantIsNull() {
        // A legacy origin row with a null tenant cannot prove ownership → fail closed.
        when(repository.findById("pay_legacy")).thenReturn(Optional.of(origin("pay_legacy", null)));

        assertThatThrownBy(() -> service.assertOwnedBy("pay_legacy", "tenant-A"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
