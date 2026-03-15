package io.nexuspay.payment.adapter.in.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface InboundWebhookRepository extends JpaRepository<InboundWebhook, String> {

    Optional<InboundWebhook> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /**
     * Deletes processed/failed webhooks older than the given cutoff.
     * Used by the cleanup job to prevent unbounded table growth (GAP-006).
     */
    @Modifying
    @Query("DELETE FROM InboundWebhook w WHERE w.status <> 'RECEIVED' AND w.receivedAt < :cutoff")
    int deleteProcessedBefore(Instant cutoff);
}
