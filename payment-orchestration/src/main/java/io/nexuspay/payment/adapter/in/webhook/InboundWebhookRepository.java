package io.nexuspay.payment.adapter.in.webhook;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface InboundWebhookRepository extends JpaRepository<InboundWebhook, String> {

    Optional<InboundWebhook> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /**
     * GAP-015 (reprocess concurrency): re-loads a single inbound webhook FOR UPDATE so the operator
     * reprocess path can serialize the status-read + FAILED-&gt;PROCESSED flip + outbox re-insert under a
     * row lock. Two concurrent {@code POST /v1/admin/webhooks/reprocess/{id}} on the SAME FAILED id would
     * otherwise both pass a plain non-locking status==FAILED read and each mint a fresh outbox event
     * (distinct outer event_id, so the consumer's dedup does not collapse them) -&gt; a double
     * capture/refund/dispute fan-out. With this pessimistic lock the second committer BLOCKS on the first,
     * then re-reads status==PROCESSED and short-circuits to the 200 no-op — the transition-plus-outbox-write
     * is atomic under concurrency, never double-inserting. Mirrors {@code JpaPendingApprovalRepository
     * .findByIdForUpdate} (SEC-24 approval reconcile) and {@code JpaPayoutRepository}'s PESSIMISTIC_WRITE idiom.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM InboundWebhook w WHERE w.id = :id")
    Optional<InboundWebhook> findByIdForUpdate(@Param("id") String id);

    /**
     * Deletes processed/failed webhooks older than the given cutoff.
     * Used by the cleanup job to prevent unbounded table growth (GAP-006).
     */
    @Modifying
    @Query("DELETE FROM InboundWebhook w WHERE w.status <> 'RECEIVED' AND w.receivedAt < :cutoff")
    int deleteProcessedBefore(Instant cutoff);
}
