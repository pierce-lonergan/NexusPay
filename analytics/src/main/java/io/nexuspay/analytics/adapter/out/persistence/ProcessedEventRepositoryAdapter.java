package io.nexuspay.analytics.adapter.out.persistence;

import io.nexuspay.analytics.application.port.out.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * SEC-18 dedup-marker adapter.
 *
 * <p><strong>FIX (SEC-18, no tx-poisoning under concurrency for multi-rollup events):</strong>
 * claims {@code (event_id, rollup_kind)} with a single native
 * {@code INSERT ... ON CONFLICT (event_id, rollup_kind) DO NOTHING} and reads the affected-row
 * count. A first delivery inserts the marker (1 row → {@code true}); a redelivery — whether
 * sequential OR a concurrent race — conflicts and inserts 0 rows ({@code false}) WITHOUT raising a
 * {@code 23505}. This matters because a single event legitimately calls {@code markProcessed} more
 * than once in the SAME transaction (PaymentFailed → {@code AUTH_RATE_HOURLY} then
 * {@code DECLINE_DAILY}). The previous {@code saveAndFlush}+catch backstop poisoned the Postgres
 * transaction on a genuine concurrent dup of the FIRST rollup, so the SIBLING marker insert then
 * failed with "current transaction is aborted" and wrongly propagated to the DLT instead of cleanly
 * skipping. {@code ON CONFLICT DO NOTHING} never raises on OUR idempotency conflict, so a sibling
 * marker in the same transaction is unaffected.</p>
 *
 * <p>Only the {@code (event_id, rollup_kind)} unique conflict is absorbed by {@code DO NOTHING}; any
 * OTHER integrity violation (e.g. NOT NULL) still throws — an unrelated error is never swallowed as a
 * benign duplicate.</p>
 *
 * <p>Uses {@code CAST(... AS timestamptz)} (NEVER the {@code ::} cast shorthand, per L-041 / L-054 /
 * L-056: Hibernate parses {@code :param} after {@code ::} as a named-parameter token in native
 * queries) where an explicit cast is needed.</p>
 *
 * <p><strong>Atomicity:</strong> {@code markProcessed} runs inside the same REQUIRES_NEW
 * transaction opened by {@code tenantWork.runInTenant(...)} that wraps the consumer's
 * {@code doConsume} (and thus the additive upsert). So the marker insert and the additive upsert
 * commit/rollback together: if the upsert later fails and the tx rolls back, the marker is rolled
 * back too, and a redelivery legitimately reprocesses (no lost update). The {@code executeUpdate}
 * runs the INSERT + UNIQUE check synchronously HERE so a dup is observed BEFORE the additive
 * upsert runs.</p>
 *
 * @since SEC-18
 */
@Component
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessedEventRepositoryAdapter.class);

    private final EntityManager entityManager;

    public ProcessedEventRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean markProcessed(String eventId, String rollupKind, String tenantId) {
        // Single-statement claim: INSERT ... ON CONFLICT DO NOTHING. Returns the number of rows
        // actually inserted — 1 on first delivery (claimed), 0 on a (sequential OR concurrent)
        // redelivery. No 23505 is raised on OUR (event_id, rollup_kind) conflict, so a sibling
        // markProcessed in the SAME transaction is never poisoned. Any UNRELATED integrity error
        // (e.g. NOT NULL) is NOT covered by DO NOTHING and still throws.
        Query query = entityManager.createNativeQuery("""
                INSERT INTO analytics.processed_analytics_events
                    (event_id, rollup_kind, tenant_id, created_at)
                VALUES (:eventId, :rollupKind, :tenantId, CAST(:createdAt AS timestamptz))
                ON CONFLICT (event_id, rollup_kind) DO NOTHING
                """);
        query.setParameter("eventId", eventId);
        query.setParameter("rollupKind", rollupKind);
        query.setParameter("tenantId", tenantId);
        query.setParameter("createdAt", Instant.now().toString());

        int inserted = query.executeUpdate();
        if (inserted == 0) {
            LOG.debug("Redelivery for event_id={}, rollup_kind={} — marker already present; "
                    + "skipping additive upsert", eventId, rollupKind);
            return false;
        }
        return true;
    }

    @Override
    public long countMarkers(String eventId, String rollupKind) {
        Query query = entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM analytics.processed_analytics_events
                WHERE event_id = :eventId AND rollup_kind = :rollupKind
                """);
        query.setParameter("eventId", eventId);
        query.setParameter("rollupKind", rollupKind);
        return ((Number) query.getSingleResult()).longValue();
    }
}
