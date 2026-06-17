package io.nexuspay.billing.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * JPA implementation of the subscription and product billing ports.
 *
 * <p>The invoice/dunning port lives in {@link JpaInvoiceRepositoryAdapter}:
 * {@code InvoiceRepository.findById/findByTenant} share parameter lists with
 * {@code SubscriptionRepository}'s but return different types, so one class
 * cannot implement both interfaces.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Repository
public class JpaBillingRepositoryAdapter implements SubscriptionRepository, ProductRepository {

    private final JpaProductRepo jpaProducts;
    private final JpaPriceRepo jpaPrices;
    private final JpaSubscriptionRepo jpaSubscriptions;
    private final ObjectMapper objectMapper;

    public JpaBillingRepositoryAdapter(JpaProductRepo jpaProducts, JpaPriceRepo jpaPrices,
                                        JpaSubscriptionRepo jpaSubscriptions,
                                        ObjectMapper objectMapper) {
        this.jpaProducts = jpaProducts;
        this.jpaPrices = jpaPrices;
        this.jpaSubscriptions = jpaSubscriptions;
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
    public Optional<Price> findPriceByIdAndTenantId(String id, String tenantId) {
        return jpaPrices.findByIdAndTenantId(id, tenantId).map(this::toPrice);
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
    public Optional<Subscription> findByIdAndTenantId(String id, String tenantId) {
        return jpaSubscriptions.findByIdAndTenantId(id, tenantId).map(this::toSubscription);
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
        Optional<PriceEntity> findByIdAndTenantId(String id, String tenantId);
        List<PriceEntity> findByProductIdAndActiveTrue(String productId);
        List<PriceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
    }

    interface JpaSubscriptionRepo extends JpaRepository<SubscriptionEntity, String> {
        Optional<SubscriptionEntity> findByIdAndTenantId(String id, String tenantId);
        List<SubscriptionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
        List<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, String customerId);
        List<SubscriptionEntity> findByStatus(String status, PageRequest page);

        @Query("SELECT s FROM SubscriptionEntity s WHERE s.status IN ('ACTIVE','PAST_DUE') AND s.currentPeriodEnd < ?1")
        List<SubscriptionEntity> findDueForRenewal(Instant before, PageRequest page);

        @Query("SELECT s FROM SubscriptionEntity s WHERE s.status = 'TRIALING' AND s.trialEnd < ?1")
        List<SubscriptionEntity> findExpiredTrials(Instant before, PageRequest page);
    }

}
