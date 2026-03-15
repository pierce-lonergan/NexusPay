package io.nexuspay.billing.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JPA implementation of billing repository ports.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Repository
public class JpaBillingRepositoryAdapter implements SubscriptionRepository, ProductRepository, InvoiceRepository {

    private final JpaProductRepo jpaProducts;
    private final JpaPriceRepo jpaPrices;
    private final JpaSubscriptionRepo jpaSubscriptions;
    private final JpaInvoiceRepo jpaInvoices;
    private final JpaLineItemRepo jpaLineItems;
    private final JpaDunningRepo jpaDunning;
    private final ObjectMapper objectMapper;

    public JpaBillingRepositoryAdapter(JpaProductRepo jpaProducts, JpaPriceRepo jpaPrices,
                                        JpaSubscriptionRepo jpaSubscriptions, JpaInvoiceRepo jpaInvoices,
                                        JpaLineItemRepo jpaLineItems, JpaDunningRepo jpaDunning,
                                        ObjectMapper objectMapper) {
        this.jpaProducts = jpaProducts;
        this.jpaPrices = jpaPrices;
        this.jpaSubscriptions = jpaSubscriptions;
        this.jpaInvoices = jpaInvoices;
        this.jpaLineItems = jpaLineItems;
        this.jpaDunning = jpaDunning;
        this.objectMapper = objectMapper;
    }

    // ==================== Product ====================

    @Override
    public Product saveProduct(Product p) {
        jpaProducts.save(toProductEntity(p));
        return p;
    }

    @Override
    public Optional<Product> findProductById(String id) {
        return jpaProducts.findById(id).map(this::toProduct);
    }

    @Override
    public List<Product> findProductsByTenant(String tenantId, int limit, int offset) {
        return jpaProducts.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream().map(this::toProduct).toList();
    }

    // ==================== Price ====================

    @Override
    public Price savePrice(Price p) {
        jpaPrices.save(toPriceEntity(p));
        return p;
    }

    @Override
    public Optional<Price> findPriceById(String id) {
        return jpaPrices.findById(id).map(this::toPrice);
    }

    @Override
    public List<Price> findPricesByProduct(String productId) {
        return jpaPrices.findByProductIdAndActiveTrue(productId).stream().map(this::toPrice).toList();
    }

    @Override
    public List<Price> findPricesByTenant(String tenantId, int limit, int offset) {
        return jpaPrices.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream().map(this::toPrice).toList();
    }

    // ==================== Subscription ====================

    @Override
    public Subscription save(Subscription s) {
        jpaSubscriptions.save(toSubEntity(s));
        return s;
    }

    @Override
    public Optional<Subscription> findById(String id) {
        return jpaSubscriptions.findById(id).map(this::toSubscription);
    }

    @Override
    public List<Subscription> findByTenant(String tenantId, int limit, int offset) {
        return jpaSubscriptions.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream().map(this::toSubscription).toList();
    }

    @Override
    public List<Subscription> findByCustomer(String tenantId, String customerId) {
        return jpaSubscriptions.findByTenantIdAndCustomerId(tenantId, customerId)
                .stream().map(this::toSubscription).toList();
    }

    @Override
    public List<Subscription> findByStatus(SubscriptionState status, int limit) {
        return jpaSubscriptions.findByStatus(status.name(), PageRequest.of(0, limit))
                .stream().map(this::toSubscription).toList();
    }

    @Override
    public List<Subscription> findDueForRenewal(Instant before, int limit) {
        return jpaSubscriptions.findDueForRenewal(before, PageRequest.of(0, limit))
                .stream().map(this::toSubscription).toList();
    }

    @Override
    public List<Subscription> findExpiredTrials(Instant before, int limit) {
        return jpaSubscriptions.findExpiredTrials(before, PageRequest.of(0, limit))
                .stream().map(this::toSubscription).toList();
    }

    // ==================== Invoice ====================

    @Override
    public Invoice save(Invoice inv) {
        jpaInvoices.save(toInvoiceEntity(inv));
        return inv;
    }

    @Override
    public Optional<Invoice> findById(String id) {
        // Disambiguate — this is the invoice findById
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

    private ProductEntity toProductEntity(Product p) {
        ProductEntity e = new ProductEntity();
        e.setId(p.getId()); e.setTenantId(p.getTenantId()); e.setName(p.getName());
        e.setDescription(p.getDescription()); e.setActive(p.isActive());
        e.setMetadata(toJson(p.getMetadata()));
        e.setCreatedAt(p.getCreatedAt()); e.setUpdatedAt(p.getUpdatedAt());
        return e;
    }

    private Product toProduct(ProductEntity e) {
        Product p = new Product();
        p.setId(e.getId()); p.setTenantId(e.getTenantId()); p.setName(e.getName());
        p.setDescription(e.getDescription()); p.setActive(e.isActive());
        p.setMetadata(fromJsonMap(e.getMetadata()));
        p.setCreatedAt(e.getCreatedAt()); p.setUpdatedAt(e.getUpdatedAt());
        return p;
    }

    private PriceEntity toPriceEntity(Price p) {
        PriceEntity e = new PriceEntity();
        e.setId(p.getId()); e.setProductId(p.getProductId()); e.setTenantId(p.getTenantId());
        e.setCurrency(p.getCurrency()); e.setPricingModel(p.getPricingModel().name());
        e.setUnitAmount(p.getUnitAmount()); e.setTiers(toJson(p.getTiers()));
        e.setBillingInterval(p.getBillingInterval()); e.setBillingIntervalCount(p.getBillingIntervalCount());
        e.setTrialDays(p.getTrialDays()); e.setActive(p.isActive());
        e.setEffectiveFrom(p.getEffectiveFrom()); e.setCreatedAt(p.getCreatedAt());
        return e;
    }

    private Price toPrice(PriceEntity e) {
        Price p = new Price();
        p.setId(e.getId()); p.setProductId(e.getProductId()); p.setTenantId(e.getTenantId());
        p.setCurrency(e.getCurrency()); p.setPricingModel(PricingModel.valueOf(e.getPricingModel()));
        p.setUnitAmount(e.getUnitAmount()); p.setTiers(fromJsonList(e.getTiers()));
        p.setBillingInterval(e.getBillingInterval()); p.setBillingIntervalCount(e.getBillingIntervalCount());
        p.setTrialDays(e.getTrialDays()); p.setActive(e.isActive());
        p.setEffectiveFrom(e.getEffectiveFrom()); p.setCreatedAt(e.getCreatedAt());
        return p;
    }

    private SubscriptionEntity toSubEntity(Subscription s) {
        SubscriptionEntity e = new SubscriptionEntity();
        e.setId(s.getId()); e.setTenantId(s.getTenantId()); e.setCustomerId(s.getCustomerId());
        e.setPriceId(s.getPriceId()); e.setStatus(s.getStatus().name()); e.setQuantity(s.getQuantity());
        e.setCurrentPeriodStart(s.getCurrentPeriodStart()); e.setCurrentPeriodEnd(s.getCurrentPeriodEnd());
        e.setTrialStart(s.getTrialStart()); e.setTrialEnd(s.getTrialEnd());
        e.setCanceledAt(s.getCanceledAt()); e.setCancelAtPeriodEnd(s.isCancelAtPeriodEnd());
        e.setPaymentMethodId(s.getPaymentMethodId()); e.setMetadata(toJson(s.getMetadata()));
        e.setCreatedAt(s.getCreatedAt()); e.setUpdatedAt(s.getUpdatedAt());
        return e;
    }

    private Subscription toSubscription(SubscriptionEntity e) {
        Subscription s = new Subscription();
        s.setId(e.getId()); s.setTenantId(e.getTenantId()); s.setCustomerId(e.getCustomerId());
        s.setPriceId(e.getPriceId()); s.setStatus(SubscriptionState.valueOf(e.getStatus()));
        s.setQuantity(e.getQuantity());
        s.setCurrentPeriodStart(e.getCurrentPeriodStart()); s.setCurrentPeriodEnd(e.getCurrentPeriodEnd());
        s.setTrialStart(e.getTrialStart()); s.setTrialEnd(e.getTrialEnd());
        s.setCanceledAt(e.getCanceledAt()); s.setCancelAtPeriodEnd(e.isCancelAtPeriodEnd());
        s.setPaymentMethodId(e.getPaymentMethodId()); s.setMetadata(fromJsonMap(e.getMetadata()));
        s.setCreatedAt(e.getCreatedAt()); s.setUpdatedAt(e.getUpdatedAt());
        return s;
    }

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

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonList(String json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }

    // ==================== Spring Data JPA interfaces ====================

    interface JpaProductRepo extends JpaRepository<ProductEntity, String> {
        List<ProductEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
    }

    interface JpaPriceRepo extends JpaRepository<PriceEntity, String> {
        List<PriceEntity> findByProductIdAndActiveTrue(String productId);
        List<PriceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
    }

    interface JpaSubscriptionRepo extends JpaRepository<SubscriptionEntity, String> {
        List<SubscriptionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
        List<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, String customerId);
        List<SubscriptionEntity> findByStatus(String status, PageRequest page);

        @Query("SELECT s FROM SubscriptionEntity s WHERE s.status IN ('ACTIVE','PAST_DUE') AND s.currentPeriodEnd < ?1")
        List<SubscriptionEntity> findDueForRenewal(Instant before, PageRequest page);

        @Query("SELECT s FROM SubscriptionEntity s WHERE s.status = 'TRIALING' AND s.trialEnd < ?1")
        List<SubscriptionEntity> findExpiredTrials(Instant before, PageRequest page);
    }

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
