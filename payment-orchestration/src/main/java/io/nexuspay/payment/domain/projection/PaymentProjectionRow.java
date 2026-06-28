package io.nexuspay.payment.domain.projection;

import java.time.Instant;

/**
 * GAP-076 (critique v3 F1): a single row of the payments READ-MODEL projection — the durable,
 * denormalized view served by {@code GET /v1/payments}.
 *
 * <p>This is a plain domain record DECOUPLED from the JPA entity, so gateway-api can map it to its
 * public DTO without depending on the persistence layer (no new modulith edge). It is built from a
 * {@code PaymentResponse} at write time and stamped with the server-derived {@code tenantId} +
 * {@code livemode}.</p>
 *
 * <p><b>Not a source of truth.</b> This row is a best-effort projection. Nothing reads it to make a
 * money decision, re-drive a capture, or reconcile the ledger; it is ONLY served by the list endpoint.
 * The double-entry ledger + the PSP/mock are the sole sources of truth.</p>
 *
 * @param paymentId     the gateway payment id (= {@code PaymentResponse.gatewayPaymentId}); the PK / upsert key
 * @param tenantId      the SERVER-DERIVED trusted tenant (never client metadata)
 * @param livemode      the SERVER-DERIVED key mode (true = live key, false = test key)
 * @param status        the payment lifecycle status at the last projected write
 * @param amount        amount in minor units
 * @param currency      ISO 4217 currency code (nullable on a bare intent)
 * @param captureMethod automatic / manual (nullable)
 * @param customerId    the customer id (nullable)
 * @param connectorName the PSP connector that processed it (nullable)
 * @param errorCode     error code when the payment failed (nullable)
 * @param errorMessage  error message when the payment failed (nullable)
 * @param createdAt     the payment's creation instant (from the FIRST write; never overwritten)
 * @param updatedAt     when this projection row was last upserted
 */
public record PaymentProjectionRow(
        String paymentId,
        String tenantId,
        boolean livemode,
        String status,
        long amount,
        String currency,
        String captureMethod,
        String customerId,
        String connectorName,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
