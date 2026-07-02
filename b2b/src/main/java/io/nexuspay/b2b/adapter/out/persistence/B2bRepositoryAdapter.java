package io.nexuspay.b2b.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.domain.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adapter implementing {@link B2bRepository} by delegating to Spring Data JPA
 * repositories and mapping between domain models and JPA entities.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Component
public class B2bRepositoryAdapter implements B2bRepository {

    private final JpaPurchaseOrderRepository poRepo;
    private final JpaB2bInvoiceRepository invoiceRepo;
    private final JpaVirtualCardRepository cardRepo;
    private final JpaVendorPaymentRepository vendorPaymentRepo;
    private final ObjectMapper objectMapper;

    public B2bRepositoryAdapter(JpaPurchaseOrderRepository poRepo,
                                 JpaB2bInvoiceRepository invoiceRepo,
                                 JpaVirtualCardRepository cardRepo,
                                 JpaVendorPaymentRepository vendorPaymentRepo,
                                 ObjectMapper objectMapper) {
        this.poRepo = poRepo;
        this.invoiceRepo = invoiceRepo;
        this.cardRepo = cardRepo;
        this.vendorPaymentRepo = vendorPaymentRepo;
        this.objectMapper = objectMapper;
    }

    // --- PurchaseOrder ---

    @Override
    public PurchaseOrder savePurchaseOrder(PurchaseOrder po) {
        return toDomain(poRepo.save(toEntity(po)));
    }

    @Override
    public Optional<PurchaseOrder> findPurchaseOrderById(String id) {
        return poRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<PurchaseOrder> findPurchaseOrderById(String id, String tenantId) {
        return poRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<PurchaseOrder> findPurchaseOrdersByTenantId(String tenantId) {
        return poRepo.findByTenantId(tenantId).stream().map(this::toDomain).toList();
    }

    // --- B2bInvoice ---

    @Override
    public B2bInvoice saveInvoice(B2bInvoice invoice) {
        return toDomain(invoiceRepo.save(toEntity(invoice)));
    }

    @Override
    public Optional<B2bInvoice> findInvoiceById(String id) {
        return invoiceRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<B2bInvoice> findInvoiceById(String id, String tenantId) {
        return invoiceRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public Optional<B2bInvoice> findInvoiceByPurchaseOrderId(String purchaseOrderId) {
        return invoiceRepo.findByPurchaseOrderId(purchaseOrderId).map(this::toDomain);
    }

    // --- VirtualCard ---

    @Override
    public VirtualCard saveVirtualCard(VirtualCard card) {
        return toDomain(cardRepo.save(toEntity(card)));
    }

    @Override
    public Optional<VirtualCard> findVirtualCardById(String id) {
        return cardRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<VirtualCard> findVirtualCardById(String id, String tenantId) {
        return cardRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<VirtualCard> findVirtualCardsByTenantId(String tenantId) {
        return cardRepo.findByTenantId(tenantId).stream().map(this::toDomain).toList();
    }

    // --- VendorPayment ---

    @Override
    public VendorPayment saveVendorPayment(VendorPayment payment) {
        return toDomain(vendorPaymentRepo.save(toEntity(payment)));
    }

    @Override
    public Optional<VendorPayment> findVendorPaymentById(String id) {
        return vendorPaymentRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<VendorPayment> findVendorPaymentById(String id, String tenantId) {
        return vendorPaymentRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<VendorPayment> findVendorPaymentsByBatchId(String batchId) {
        return vendorPaymentRepo.findByBatchId(batchId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<VendorPayment> findVendorPaymentsByVendorId(String vendorId) {
        return vendorPaymentRepo.findByVendorId(vendorId).stream().map(this::toDomain).toList();
    }

    // --- Entity ↔ Domain Mapping: PurchaseOrder ---

    private PurchaseOrderEntity toEntity(PurchaseOrder po) {
        PurchaseOrderEntity e = new PurchaseOrderEntity();
        e.setId(po.getId());
        e.setTenantId(po.getTenantId());
        e.setBuyerId(po.getBuyerId());
        e.setSellerId(po.getSellerId());
        e.setPoNumber(po.getPoNumber());
        e.setAmount(po.getAmount());
        e.setTaxAmount(po.getTaxAmount());
        e.setCurrency(po.getCurrency());
        e.setStatus(po.getStatus().name());
        e.setTerms(po.getTerms() != null ? po.getTerms().name() : null);
        try {
            e.setLineItems(objectMapper.writeValueAsString(po.getLineItems()));
        } catch (Exception ex) {
            e.setLineItems("[]");
        }
        e.setDueDate(po.getDueDate());
        e.setCreatedAt(po.getCreatedAt());
        e.setUpdatedAt(po.getUpdatedAt());
        e.setCreatedBy(po.getCreatedBy());
        return e;
    }

    private PurchaseOrder toDomain(PurchaseOrderEntity e) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(e.getId());
        po.setTenantId(e.getTenantId());
        po.setBuyerId(e.getBuyerId());
        po.setSellerId(e.getSellerId());
        po.setPoNumber(e.getPoNumber());
        po.setAmount(e.getAmount());
        po.setTaxAmount(e.getTaxAmount());
        po.setCurrency(e.getCurrency());
        po.setStatus(PurchaseOrderStatus.valueOf(e.getStatus()));
        po.setTerms(e.getTerms() != null ? PaymentTerms.valueOf(e.getTerms()) : null);
        try {
            po.setLineItems(objectMapper.readValue(e.getLineItems(), new TypeReference<List<LineItem>>() {}));
        } catch (Exception ex) {
            po.setLineItems(new ArrayList<>());
        }
        po.setDueDate(e.getDueDate());
        po.setCreatedAt(e.getCreatedAt());
        po.setUpdatedAt(e.getUpdatedAt());
        po.setCreatedBy(e.getCreatedBy());
        return po;
    }

    // --- Entity ↔ Domain Mapping: B2bInvoice ---

    private B2bInvoiceEntity toEntity(B2bInvoice inv) {
        B2bInvoiceEntity e = new B2bInvoiceEntity();
        e.setId(inv.getId());
        e.setTenantId(inv.getTenantId());
        e.setPurchaseOrderId(inv.getPurchaseOrderId());
        e.setBuyerId(inv.getBuyerId());
        e.setSellerId(inv.getSellerId());
        e.setInvoiceNumber(inv.getInvoiceNumber());
        e.setAmount(inv.getAmount());
        e.setTaxAmount(inv.getTaxAmount());
        e.setCurrency(inv.getCurrency());
        e.setStatus(inv.getStatus().name());
        e.setTerms(inv.getTerms() != null ? inv.getTerms().name() : null);
        e.setDueDate(inv.getDueDate());
        e.setPaidAt(inv.getPaidAt());
        e.setCreatedAt(inv.getCreatedAt());
        return e;
    }

    private B2bInvoice toDomain(B2bInvoiceEntity e) {
        B2bInvoice inv = new B2bInvoice();
        inv.setId(e.getId());
        inv.setTenantId(e.getTenantId());
        inv.setPurchaseOrderId(e.getPurchaseOrderId());
        inv.setBuyerId(e.getBuyerId());
        inv.setSellerId(e.getSellerId());
        inv.setInvoiceNumber(e.getInvoiceNumber());
        inv.setAmount(e.getAmount());
        inv.setTaxAmount(e.getTaxAmount());
        inv.setCurrency(e.getCurrency());
        inv.setStatus(InvoiceStatus.valueOf(e.getStatus()));
        inv.setTerms(e.getTerms() != null ? PaymentTerms.valueOf(e.getTerms()) : null);
        inv.setDueDate(e.getDueDate());
        inv.setPaidAt(e.getPaidAt());
        inv.setCreatedAt(e.getCreatedAt());
        return inv;
    }

    // --- Entity ↔ Domain Mapping: VirtualCard ---

    private VirtualCardEntity toEntity(VirtualCard card) {
        VirtualCardEntity e = new VirtualCardEntity();
        e.setId(card.getId());
        e.setTenantId(card.getTenantId());
        e.setIssuingProvider(card.getIssuingProvider());
        e.setExternalCardId(card.getExternalCardId());
        e.setCardLast4(card.getCardLast4());
        e.setCardType(card.getCardType().name());
        e.setAmountLimit(card.getAmountLimit());
        e.setCurrency(card.getCurrency());
        e.setMerchantCategoryCodes(card.getMerchantCategoryCodes() != null
                ? String.join(",", card.getMerchantCategoryCodes()) : null);
        e.setExpiresAt(card.getExpiresAt());
        e.setStatus(card.getStatus().name());
        e.setSpentAmount(card.getSpentAmount());
        e.setPurchaseOrderId(card.getPurchaseOrderId());
        e.setCreatedAt(card.getCreatedAt());
        return e;
    }

    private VirtualCard toDomain(VirtualCardEntity e) {
        VirtualCard card = new VirtualCard();
        card.setId(e.getId());
        card.setTenantId(e.getTenantId());
        card.setIssuingProvider(e.getIssuingProvider());
        card.setExternalCardId(e.getExternalCardId());
        card.setCardLast4(e.getCardLast4());
        card.setCardType(VirtualCardType.valueOf(e.getCardType()));
        card.setAmountLimit(e.getAmountLimit());
        card.setCurrency(e.getCurrency());
        card.setMerchantCategoryCodes(e.getMerchantCategoryCodes() != null
                ? Arrays.asList(e.getMerchantCategoryCodes().split(",")) : new ArrayList<>());
        card.setExpiresAt(e.getExpiresAt());
        card.setStatus(VirtualCardStatus.valueOf(e.getStatus()));
        card.setSpentAmount(e.getSpentAmount());
        card.setPurchaseOrderId(e.getPurchaseOrderId());
        card.setCreatedAt(e.getCreatedAt());
        return card;
    }

    // --- Entity ↔ Domain Mapping: VendorPayment ---

    private VendorPaymentEntity toEntity(VendorPayment vp) {
        VendorPaymentEntity e = new VendorPaymentEntity();
        e.setId(vp.getId());
        e.setTenantId(vp.getTenantId());
        e.setVendorId(vp.getVendorId());
        e.setAmount(vp.getAmount());
        e.setCurrency(vp.getCurrency());
        e.setMethod(vp.getMethod().name());
        e.setStatus(vp.getStatus().name());
        e.setBatchId(vp.getBatchId());
        e.setRemittanceInfo(vp.getRemittanceInfo());
        e.setScheduledAt(vp.getScheduledAt());
        e.setPaidAt(vp.getPaidAt());
        e.setExternalReference(vp.getExternalReference());
        e.setCreatedAt(vp.getCreatedAt());
        e.setCreatedBy(vp.getCreatedBy());
        return e;
    }

    private VendorPayment toDomain(VendorPaymentEntity e) {
        VendorPayment vp = new VendorPayment();
        vp.setId(e.getId());
        vp.setTenantId(e.getTenantId());
        vp.setVendorId(e.getVendorId());
        vp.setAmount(e.getAmount());
        vp.setCurrency(e.getCurrency());
        vp.setMethod(VendorPaymentMethod.valueOf(e.getMethod()));
        vp.setStatus(VendorPaymentStatus.valueOf(e.getStatus()));
        vp.setBatchId(e.getBatchId());
        vp.setRemittanceInfo(e.getRemittanceInfo());
        vp.setScheduledAt(e.getScheduledAt());
        vp.setPaidAt(e.getPaidAt());
        vp.setExternalReference(e.getExternalReference());
        vp.setCreatedAt(e.getCreatedAt());
        vp.setCreatedBy(e.getCreatedBy());
        return vp;
    }
}
