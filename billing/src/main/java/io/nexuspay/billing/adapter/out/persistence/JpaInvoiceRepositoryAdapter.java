package io.nexuspay.billing.adapter.out.persistence;

import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.domain.DunningAttempt;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceLineItem;
import io.nexuspay.billing.domain.InvoiceStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA implementation of the invoice and dunning billing port.
 *
 * <p>Kept separate from {@link JpaBillingRepositoryAdapter}: the
 * {@code findById}/{@code findByTenant} signatures of {@code InvoiceRepository}
 * collide with {@code SubscriptionRepository}'s (same parameters, different
 * return types), so one class cannot implement both interfaces.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Repository
public class JpaInvoiceRepositoryAdapter implements InvoiceRepository {

    private final JpaInvoiceRepo jpaInvoices;
    private final JpaLineItemRepo jpaLineItems;
    private final JpaDunningRepo jpaDunning;

    public JpaInvoiceRepositoryAdapter(JpaInvoiceRepo jpaInvoices,
                                       JpaLineItemRepo jpaLineItems,
                                       JpaDunningRepo jpaDunning) {
        this.jpaInvoices = jpaInvoices;
        this.jpaLineItems = jpaLineItems;
        this.jpaDunning = jpaDunning;
    }

    // ==================== Invoice ====================

    @Override
    public Invoice save(Invoice inv) {
        jpaInvoices.save(toInvoiceEntity(inv));
        return inv;
    }

    @Override
    public Optional<Invoice> findById(String id) {
        return jpaInvoices.findById(id).map(this::toInvoice);
    }

    @Override
    public List<Invoice> findByTenant(String tenantId, int limit, int offset) {
        return jpaInvoices.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream().map(this::toInvoice).toList();
    }

    @Override
    public List<Invoice> findBySubscription(String subscriptionId) {
        return jpaInvoices.findBySubscriptionId(subscriptionId).stream().map(this::toInvoice).toList();
    }

    @Override
    public List<Invoice> findByCustomer(String tenantId, String customerId, int limit, int offset) {
        return jpaInvoices.findByTenantIdAndCustomerIdOrderByCreatedAtDesc(
                tenantId, customerId, PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream().map(this::toInvoice).toList();
    }

    @Override
    public InvoiceLineItem saveLineItem(InvoiceLineItem item) {
        jpaLineItems.save(toLineItemEntity(item));
        return item;
    }

    @Override
    public List<InvoiceLineItem> findLineItemsByInvoice(String invoiceId) {
        return jpaLineItems.findByInvoiceId(invoiceId).stream().map(this::toLineItem).toList();
    }

    // ==================== Dunning ====================

    @Override
    public DunningAttempt saveDunningAttempt(DunningAttempt a) {
        jpaDunning.save(toDunningEntity(a));
        return a;
    }

    @Override
    public List<DunningAttempt> findDunningBySubscription(String subscriptionId) {
        return jpaDunning.findBySubscriptionIdOrderByAttemptNumber(subscriptionId)
                .stream().map(this::toDunning).toList();
    }

    @Override
    public List<DunningAttempt> findPendingDunning(Instant before, int limit) {
        return jpaDunning.findPendingBefore(before, PageRequest.of(0, limit))
                .stream().map(this::toDunning).toList();
    }

    // ==================== Entity mappers ====================

    private InvoiceEntity toInvoiceEntity(Invoice inv) {
        InvoiceEntity e = new InvoiceEntity();
        e.setId(inv.getId()); e.setTenantId(inv.getTenantId()); e.setSubscriptionId(inv.getSubscriptionId());
        e.setCustomerId(inv.getCustomerId()); e.setStatus(inv.getStatus().name());
        e.setCurrency(inv.getCurrency()); e.setSubtotal(inv.getSubtotal()); e.setTax(inv.getTax());
        e.setTotal(inv.getTotal()); e.setAmountPaid(inv.getAmountPaid()); e.setAmountDue(inv.getAmountDue());
        e.setPaymentId(inv.getPaymentId()); e.setDueDate(inv.getDueDate()); e.setPaidAt(inv.getPaidAt());
        e.setPeriodStart(inv.getPeriodStart()); e.setPeriodEnd(inv.getPeriodEnd()); e.setCreatedAt(inv.getCreatedAt());
        return e;
    }

    private Invoice toInvoice(InvoiceEntity e) {
        Invoice inv = new Invoice();
        inv.setId(e.getId()); inv.setTenantId(e.getTenantId()); inv.setSubscriptionId(e.getSubscriptionId());
        inv.setCustomerId(e.getCustomerId()); inv.setStatus(InvoiceStatus.valueOf(e.getStatus()));
        inv.setCurrency(e.getCurrency()); inv.setSubtotal(e.getSubtotal()); inv.setTax(e.getTax());
        inv.setTotal(e.getTotal()); inv.setAmountPaid(e.getAmountPaid()); inv.setAmountDue(e.getAmountDue());
        inv.setPaymentId(e.getPaymentId()); inv.setDueDate(e.getDueDate()); inv.setPaidAt(e.getPaidAt());
        inv.setPeriodStart(e.getPeriodStart()); inv.setPeriodEnd(e.getPeriodEnd()); inv.setCreatedAt(e.getCreatedAt());
        return inv;
    }

    private InvoiceLineItemEntity toLineItemEntity(InvoiceLineItem li) {
        InvoiceLineItemEntity e = new InvoiceLineItemEntity();
        e.setId(li.getId()); e.setInvoiceId(li.getInvoiceId()); e.setTenantId(li.getTenantId());
        e.setDescription(li.getDescription()); e.setAmount(li.getAmount()); e.setCurrency(li.getCurrency());
        e.setQuantity(li.getQuantity()); e.setProration(li.isProration());
        e.setPeriodStart(li.getPeriodStart()); e.setPeriodEnd(li.getPeriodEnd());
        return e;
    }

    private InvoiceLineItem toLineItem(InvoiceLineItemEntity e) {
        return new InvoiceLineItem(e.getId(), e.getInvoiceId(), e.getTenantId(),
                e.getDescription(), e.getAmount(), e.getCurrency(), e.getQuantity(),
                e.isProration(), e.getPeriodStart(), e.getPeriodEnd());
    }

    private DunningAttemptEntity toDunningEntity(DunningAttempt d) {
        DunningAttemptEntity e = new DunningAttemptEntity();
        e.setId(d.getId()); e.setSubscriptionId(d.getSubscriptionId()); e.setInvoiceId(d.getInvoiceId());
        e.setTenantId(d.getTenantId()); e.setAttemptNumber(d.getAttemptNumber()); e.setPaymentId(d.getPaymentId());
        e.setStatus(d.getStatus().name()); e.setScheduledAt(d.getScheduledAt()); e.setAttemptedAt(d.getAttemptedAt());
        e.setFailureReason(d.getFailureReason()); e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    private DunningAttempt toDunning(DunningAttemptEntity e) {
        DunningAttempt d = new DunningAttempt();
        d.setId(e.getId()); d.setSubscriptionId(e.getSubscriptionId()); d.setInvoiceId(e.getInvoiceId());
        d.setTenantId(e.getTenantId()); d.setAttemptNumber(e.getAttemptNumber()); d.setPaymentId(e.getPaymentId());
        d.setStatus(DunningAttempt.Status.valueOf(e.getStatus())); d.setScheduledAt(e.getScheduledAt());
        d.setAttemptedAt(e.getAttemptedAt()); d.setFailureReason(e.getFailureReason()); d.setCreatedAt(e.getCreatedAt());
        return d;
    }

    // ==================== Spring Data JPA interfaces ====================

    interface JpaInvoiceRepo extends JpaRepository<InvoiceEntity, String> {
        List<InvoiceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
        List<InvoiceEntity> findBySubscriptionId(String subscriptionId);
        List<InvoiceEntity> findByTenantIdAndCustomerIdOrderByCreatedAtDesc(String tenantId, String customerId, PageRequest page);
    }

    interface JpaLineItemRepo extends JpaRepository<InvoiceLineItemEntity, String> {
        List<InvoiceLineItemEntity> findByInvoiceId(String invoiceId);
    }

    interface JpaDunningRepo extends JpaRepository<DunningAttemptEntity, String> {
        List<DunningAttemptEntity> findBySubscriptionIdOrderByAttemptNumber(String subscriptionId);

        @Query("SELECT d FROM DunningAttemptEntity d WHERE d.status = 'PENDING' AND d.scheduledAt < ?1")
        List<DunningAttemptEntity> findPendingBefore(Instant before, PageRequest page);
    }
}
