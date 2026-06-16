package io.nexuspay.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * INT-1 Spring Data repository for {@link PaymentWebhookMetadataEntity}. By-PK only
 * ({@code findById}/{@code existsById}/{@code save}); RLS scopes every row to the tenant.
 */
public interface PaymentWebhookMetadataRepository
        extends JpaRepository<PaymentWebhookMetadataEntity, String> {
}
