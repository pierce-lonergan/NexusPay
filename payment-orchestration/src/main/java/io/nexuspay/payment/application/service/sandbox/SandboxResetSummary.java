package io.nexuspay.payment.application.service.sandbox;

/**
 * GAP-077 (critique v3 F4): the per-table deleted-count summary returned by {@link SandboxResetService}.
 *
 * <p>Each field is the number of TEST rows (tenant + {@code livemode=false} scoped) hard-deleted from that
 * table during the all-or-nothing reset. The gateway controller renders it as the snake_case wire response
 * (L-072). The satellite/log tables (event_outbox, webhook_deliveries, payment_webhook_metadata,
 * payment_screening_origin) are deliberately NOT represented here — they are never touched by the reset
 * (see {@link SandboxResetService} for the rationale).</p>
 */
public record SandboxResetSummary(
        long payments,
        long refunds,
        long customers,
        long paymentMethods,
        long mandates) {
}
