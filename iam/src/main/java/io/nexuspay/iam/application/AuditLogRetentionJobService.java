package io.nexuspay.iam.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nexuspay.common.rls.SystemTransactional;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAP-027 (compliance): retention sweep for the {@code audit_log} table (iam, V1102), which otherwise
 * grows UNBOUNDEDLY — the analytics {@code DataRetentionJobService} prunes analytics rollups but skips
 * audit_log entirely. Mirrors that service's structure/idioms (native SQL, @SystemTransactional cross-
 * tenant sweep, @Scheduled cron) but adds bounded BATCHING because audit_log can grow very large.
 *
 * <h3>Retention decision — configurable HARD-DELETE, CONSERVATIVE-LONG default.</h3>
 * A configurable hard-delete of rows strictly older than a window, matching the {@code DataRetentionJobService}
 * precedent (which also hard-deletes). An archive-to-cold-storage path would need a new sink/table/export
 * pipeline that does not exist in this codebase; the honest reference-platform choice is a documented
 * hard-delete with a GENEROUS default, leaving true WORM/archival as a documented deployment concern.
 *
 * <p><b>Default window = {@code nexuspay.iam.audit-retention-days} = 2555 (7 years).</b> audit_log holds
 * security/compliance-relevant events (actor, action, ip, tenant), so — unlike analytics rollups — it is
 * retained DELIBERATELY LONG: 7 years is the conservative compliance floor for financial audit trails
 * (SOX/PCI-adjacent norms) and is FAR from deleting recent or medium-age history. Operators can SHORTEN
 * via the property if their policy differs, but the default never silently drops recent audit history.</p>
 *
 * <h3>Safety.</h3>
 * <ul>
 *   <li><b>Strictly older-than</b> — {@code WHERE timestamp < now - :days}: nothing inside the window is
 *       ever touched. (The column is {@code timestamp}, per V1102 — NOT {@code created_at}.)</li>
 *   <li><b>Bounded + batched</b> — deletes in bounded pages via a {@code ctid} sub-select
 *       ({@code LIMIT :batch}) in a loop until a page comes back short, so a huge audit_log is never
 *       deleted in one multi-million-row statement holding a long lock.</li>
 *   <li><b>Cross-tenant SYSTEM sweep</b> — runs {@code @SystemTransactional} (BYPASSRLS), deleting across
 *       all tenants, which is correct for a system retention job (not tenant-scoped), exactly like
 *       {@code DataRetentionJobService}.</li>
 *   <li><b>Metered</b> — a Micrometer counter records rows deleted per run.</li>
 * </ul>
 *
 * <p><b>Compliance follow-up (documented, not built):</b> if a deployment must retain security-category
 * audit events (login, key rotation, approval) longer/forever, the honest extension is an {@code action}
 * category filter excluding those rows from the delete. The generous 7-year default + operator override
 * is the shipped control.</p>
 */
@Service
public class AuditLogRetentionJobService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogRetentionJobService.class);

    /** Bounded page size per DELETE, so a large audit_log is never deleted in one long-lock statement. */
    private static final int BATCH_SIZE = 5_000;

    /**
     * Conservative-LONG default: 7 years. audit_log is security/compliance data and is retained LONGER
     * than analytics rollups; operators may shorten via the property but the default never drops recent
     * history.
     */
    @Value("${nexuspay.iam.audit-retention-days:2555}")
    private int auditRetentionDays;

    private final EntityManager entityManager;
    private final Counter rowsDeleted;

    public AuditLogRetentionJobService(EntityManager entityManager, MeterRegistry meterRegistry) {
        this.entityManager = entityManager;
        this.rowsDeleted = Counter.builder("nexuspay.iam.audit_retention.rows_deleted")
                .description("Count of audit_log rows hard-deleted by the retention sweep (rows strictly "
                        + "older than the configured window)")
                .register(meterRegistry);
    }

    /**
     * Runs daily at 04:30 UTC — staggered ~30 min after the 04:00 analytics retention job so they do not
     * contend. Deletes audit_log rows strictly older than the configured window in bounded batches.
     */
    @SystemTransactional
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void cleanupExpiredAuditLog() {
        int days = auditRetentionDays;
        if (days <= 0) {
            LOG.warn("audit-retention-days={} is non-positive — skipping audit_log retention sweep "
                    + "(refusing to delete with a zero/negative window)", days);
            return;
        }

        LOG.info("Starting audit_log retention sweep: deleting rows older than {} days", days);

        long totalDeleted = 0;
        int page;
        do {
            // Bounded page: select at most BATCH_SIZE ctids strictly older than the cutoff and delete them.
            // The cutoff is recomputed each iteration from NOW(), but the predicate is monotone (rows only
            // age INTO the window), so successive pages drain the eligible set until a short page ends it.
            page = entityManager.createNativeQuery(
                            "DELETE FROM audit_log WHERE ctid IN ("
                                    + "SELECT ctid FROM audit_log "
                                    + "WHERE timestamp < NOW() - CAST(:days || ' days' AS INTERVAL) "
                                    + "ORDER BY timestamp LIMIT :batch)")
                    .setParameter("days", days)
                    .setParameter("batch", BATCH_SIZE)
                    .executeUpdate();
            totalDeleted += page;
            if (page > 0) {
                rowsDeleted.increment(page);
            }
        } while (page == BATCH_SIZE);

        LOG.info("audit_log retention sweep complete: deleted {} row(s) older than {} days",
                totalDeleted, days);
    }
}
