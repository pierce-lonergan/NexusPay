package io.nexuspay.payment.adapter.out.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Finds unpublished events ordered by creation time.
     * Limit applied to prevent unbounded queries during relay polling.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC LIMIT 100")
    List<OutboxEvent> findUnpublishedEvents();

    /**
     * Deletes published outbox events older than the given cutoff.
     * Used by the cleanup job to prevent unbounded table growth (GAP-005).
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    int deletePublishedBefore(Instant cutoff);
}
