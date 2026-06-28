package io.nexuspay.payment.domain.projection;

import java.time.Instant;

/**
 * GAP-076 (critique v3 F1): a single row of the refunds READ-MODEL projection — the durable,
 * denormalized view served by {@code GET /v1/refunds}.
 *
 * <p>Decoupled from the JPA entity (mirrors {@link PaymentProjectionRow}); built from a
 * {@code RefundResponse} at write time and stamped with the server-derived {@code tenantId} +
 * {@code livemode}. Best-effort projection — never a source of truth (see
 * {@link PaymentProjectionRow}).</p>
 *
 * @param refundId      the gateway refund id (= {@code RefundResponse.gatewayRefundId}); the PK / upsert key
 * @param paymentId     the parent payment id (indexed; backs the {@code ?payment=} filter)
 * @param tenantId      the SERVER-DERIVED trusted tenant (never client metadata)
 * @param livemode      the SERVER-DERIVED key mode (true = live key, false = test key)
 * @param status        the refund status at the last projected write (pending/succeeded/failed)
 * @param amount        amount in minor units
 * @param currency      ISO 4217 currency code (nullable)
 * @param reason        the refund reason (nullable)
 * @param connectorName the PSP connector that processed it (nullable)
 * @param errorCode     error code when the refund failed (nullable)
 * @param errorMessage  error message when the refund failed (nullable)
 * @param createdAt     the refund's creation instant (from the FIRST write; never overwritten)
 * @param updatedAt     when this projection row was last upserted
 */
public record RefundProjectionRow(
        String refundId,
        String paymentId,
        String tenantId,
        boolean livemode,
        String status,
        long amount,
        String currency,
        String reason,
        String connectorName,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
