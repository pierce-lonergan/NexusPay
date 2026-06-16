package io.nexuspay.gateway.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * INT-4: persistence port for the {@link WebhookDeliveryEntity} ledger.
 *
 * <p>The endpoint signing secret is NOT a column on this entity, so NO list/replay projection over this
 * repository can ever leak it (leak-by-construction-impossible). The {@code canonical_body} IS a column but
 * is deliberately excluded from the API DTO (see {@code WebhookDeliveryResponse}).</p>
 */
public interface JpaWebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, String> {

    // INT-4 BLOCKER (recovery sweep): the retrier must re-drive BOTH
    //   (a) FAILED rows whose next_attempt_at is due (the normal backoff path), AND
    //   (b) STALE PENDING rows — a delivery whose PENDING row committed (own tx, no Kafka-JPA tx manager)
    //       but whose outcome was NEVER persisted because the process crashed (or recordOutcome threw) in the
    //       window between the PENDING commit and the outcome write. Without (b) such a row is stuck PENDING
    //       forever: the retrier (FAILED-only) skipped it AND a Kafka redelivery is short-circuited because
    //       recordDelivery's idempotency probe finds the existing PENDING row -> null -> the consumer does not
    //       re-attempt. That is a SILENTLY LOST delivery, defeating persisted at-least-once (invariant #1).
    //
    // The PENDING arm is guarded by a staleness threshold (created_at <= :staleBefore) so a row that is merely
    // mid-FIRST-attempt (recorded milliseconds ago, send in flight on the consumer thread) is NOT double-driven
    // by the retrier — only a row old enough that its first attempt provably crashed is swept.
    //
    // Status is compared as a String literal because @Enumerated(STRING) persists the enum name; the Pageable
    // bounds the batch (mirrors the findUnpublishedEvents LIMIT idiom without a parameterized HQL LIMIT).
    @Query("SELECT d FROM WebhookDeliveryEntity d WHERE "
         + "(d.status = 'FAILED' AND d.nextAttemptAt <= :now) "
         + "OR (d.status = 'PENDING' AND d.createdAt <= :staleBefore) "
         + "ORDER BY d.nextAttemptAt ASC NULLS FIRST")
    List<WebhookDeliveryEntity> findDueForRetry(@Param("now") Instant now,
                                                @Param("staleBefore") Instant staleBefore,
                                                Pageable batch);

    // INT-4 SHOULD_FIX (per-row claim -> no double-send): atomically claim a single due/stale row for THIS
    // leader before sending. The leader lock has a 5s TTL but a batch of slow/timing-out endpoints can take
    // far longer than 5s, so the lock can expire mid-batch and a second replica can load the SAME rows; the
    // (endpoint_id,event_id) unique index only blocks double-RECORDING, not double-SENDING. This conditional
    // UPDATE is the row claim: it flips the row to FAILED and pushes next_attempt_at out to :claimUntil (a short
    // lease) ONLY if the row is still in the exact claimable state the scan saw. Exactly one leader's UPDATE
    // affects 1 row; a second leader's UPDATE affects 0 and skips the row. The claim also normalizes a swept
    // PENDING row to FAILED, so if THIS attempt also crashes the row is left FAILED+future -> recoverable as a
    // normal backoff row (never stuck PENDING again). No new persisted SENDING state is introduced, so there is
    // no fresh stuck-state to recover: a crash after claiming simply lets the lease (:claimUntil) elapse and the
    // row becomes due again. Returns the number of rows updated (1 = claimed by us, 0 = lost the race / already
    // moved on by recordOutcome).
    // @Transactional here so the claim has its OWN short write tx in both modes: under RLS it joins the
    // tenant-bound outer tx opened by runInTenant; without RLS (no outer tx) it provides the required
    // transaction so the @Modifying UPDATE does not fail with TransactionRequiredException. It is a short
    // DB-only write — the HTTP send deliberately runs AFTER, outside any transaction.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WebhookDeliveryEntity d SET d.status = 'FAILED', d.nextAttemptAt = :claimUntil, "
         + "d.updatedAt = :now WHERE d.id = :id AND ("
         + "(d.status = 'FAILED' AND d.nextAttemptAt <= :now) "
         + "OR (d.status = 'PENDING' AND d.createdAt <= :staleBefore))")
    int claimForRetry(@Param("id") String id,
                      @Param("now") Instant now,
                      @Param("staleBefore") Instant staleBefore,
                      @Param("claimUntil") Instant claimUntil);

    // Idempotency probe (cheap fast path before saveAndFlush; the unique index is the real backstop).
    Optional<WebhookDeliveryEntity> findByEndpointIdAndEventId(String endpointId, String eventId);

    // List API -- tenant-scoped, paginated, by endpoint and/or event. NEVER returns the secret (not on this entity).
    Page<WebhookDeliveryEntity> findByTenantId(String tenantId, Pageable pageable);
    Page<WebhookDeliveryEntity> findByTenantIdAndEndpointId(String tenantId, String endpointId, Pageable pageable);
    Page<WebhookDeliveryEntity> findByTenantIdAndEventId(String tenantId, String eventId, Pageable pageable);
    Page<WebhookDeliveryEntity> findByTenantIdAndEndpointIdAndEventId(String tenantId, String endpointId,
                                                                     String eventId, Pageable pageable);

    // Replay lookup -- tenant-scoped by-id (no existence oracle: foreign/absent -> empty -> 404),
    // mirrors JpaWebhookEndpointRepository.findByIdAndTenantId.
    Optional<WebhookDeliveryEntity> findByIdAndTenantId(String id, String tenantId);
}
