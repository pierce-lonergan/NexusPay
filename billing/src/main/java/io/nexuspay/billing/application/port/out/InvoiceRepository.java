package io.nexuspay.billing.application.port.out;

import io.nexuspay.billing.domain.DunningAttempt;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceLineItem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output port for invoice and dunning persistence.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public interface InvoiceRepository {

    Invoice save(Invoice invoice);

    Optional<Invoice> findById(String id);

    /**
     * SEC-26: tenant-scoped by-id finder. Empty result means "absent OR not owned by this tenant",
     * so callers can collapse both into a single not-found path (no cross-tenant existence oracle).
     */
    Optional<Invoice> findByIdAndTenantId(String id, String tenantId);

    List<Invoice> findByTenant(String tenantId, int limit, int offset);

    List<Invoice> findBySubscription(String subscriptionId);

    List<Invoice> findByCustomer(String tenantId, String customerId, int limit, int offset);

    InvoiceLineItem saveLineItem(InvoiceLineItem item);

    List<InvoiceLineItem> findLineItemsByInvoice(String invoiceId);

    // -- Dunning --

    DunningAttempt saveDunningAttempt(DunningAttempt attempt);

    List<DunningAttempt> findDunningBySubscription(String subscriptionId);

    List<DunningAttempt> findPendingDunning(Instant before, int limit);
}
