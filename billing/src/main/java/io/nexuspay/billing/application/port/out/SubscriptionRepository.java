package io.nexuspay.billing.application.port.out;

import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.billing.domain.SubscriptionState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output port for subscription persistence.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public interface SubscriptionRepository {

    Subscription save(Subscription subscription);

    Optional<Subscription> findById(String id);

    /**
     * SEC-26: tenant-scoped by-id finder. Empty result means "absent OR not owned by this tenant",
     * so callers can collapse both into a single not-found path (no cross-tenant existence oracle).
     */
    Optional<Subscription> findByIdAndTenantId(String id, String tenantId);

    List<Subscription> findByTenant(String tenantId, int limit, int offset);

    List<Subscription> findByCustomer(String tenantId, String customerId);

    List<Subscription> findByStatus(SubscriptionState status, int limit);

    /** Finds subscriptions whose current period has ended and need renewal. */
    List<Subscription> findDueForRenewal(Instant before, int limit);

    /** Finds trialing subscriptions whose trial has expired. */
    List<Subscription> findExpiredTrials(Instant before, int limit);
}
