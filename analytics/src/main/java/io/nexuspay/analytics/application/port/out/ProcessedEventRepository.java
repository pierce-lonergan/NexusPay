package io.nexuspay.analytics.application.port.out;

/**
 * Out-port for the SEC-18 analytics idempotency marker.
 *
 * <p>Each additive rollup consumer calls {@link #markProcessed} BEFORE its additive upsert and
 * skips the upsert when this returns {@code false} (a redelivery). The dedup is per
 * {@code (eventId, rollupKind)} so a single event that legitimately updates SEVERAL distinct
 * rollups still updates each exactly once, and a redelivery updates none.</p>
 *
 * @since SEC-18
 */
public interface ProcessedEventRepository {

    /**
     * Atomically claims {@code (eventId, rollupKind)} as processed by inserting a marker row.
     *
     * @param eventId    the stable logical event id (envelope {@code event_id}, or a deterministic
     *                   fallback key when absent)
     * @param rollupKind the per-upsert-target discriminator (e.g. {@code AUTH_RATE_HOURLY},
     *                   {@code DECLINE_DAILY}, {@code REVENUE_HOURLY})
     * @param tenantId   the tenant the event belongs to (for RLS parity)
     * @return {@code true} if THIS call inserted the marker (first delivery → proceed with the
     *         upsert); {@code false} if it was already present (redelivery → SKIP the upsert)
     */
    boolean markProcessed(String eventId, String rollupKind, String tenantId);

    /**
     * Returns the number of dedup-marker rows present for {@code (eventId, rollupKind)} — 0 before
     * any delivery, exactly 1 after one-or-more deliveries of the same logical event/rollup. The
     * UNIQUE on {@code (event_id, rollup_kind)} caps this at 1, so a redelivery that correctly
     * dedups leaves the count at 1. Used by the SEC-18 gate to OBSERVE the dedup marker directly for
     * a rollup that carries no additive counter (e.g. routing latency enrichment), so the test is a
     * real regression detector rather than a vacuous assertion on a non-additive field.
     */
    long countMarkers(String eventId, String rollupKind);
}
