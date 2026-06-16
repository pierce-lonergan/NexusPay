package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.domain.PurchaseOrder;
import io.nexuspay.common.tenant.TenantOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for purchase order lifecycle management.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Service
public class PurchaseOrderService implements ManagePurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

    private final B2bRepository repository;
    private final B2bEventPublisher eventPublisher;

    public PurchaseOrderService(B2bRepository repository, B2bEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public PurchaseOrderResult createPurchaseOrder(CreatePurchaseOrderCommand command) {
        PurchaseOrder po = PurchaseOrder.create(
                command.tenantId(), command.buyerId(), command.sellerId(),
                command.poNumber(), command.currency(), command.terms());

        po.setTaxAmount(command.taxAmount());
        if (command.lineItems() != null) {
            command.lineItems().forEach(po::addLineItem);
        }

        po = repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", po.getId(), "PurchaseOrderCreated",
                Map.of("poNumber", po.getPoNumber(), "buyerId", command.buyerId(),
                        "sellerId", command.sellerId(), "amount", po.getAmount(),
                        "currency", command.currency(), "tenantId", command.tenantId()),
                command.tenantId());

        log.info("PO created: id={}, poNumber={}, amount={}{}", po.getId(), po.getPoNumber(), po.getAmount(), po.getCurrency());
        return toResult(po);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrderResult getPurchaseOrder(String poId, String tenantId) {
        return toResult(findOrThrow(poId, tenantId));
    }

    @Override
    @Transactional
    public PurchaseOrderResult submitPurchaseOrder(String poId, String tenantId) {
        PurchaseOrder po = findOrThrow(poId, tenantId);
        po.submit();
        po = repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderSubmitted",
                Map.of("poNumber", po.getPoNumber(), "tenantId", tenantId), tenantId);

        log.info("PO submitted: id={}", poId);
        return toResult(po);
    }

    @Override
    @Transactional
    public PurchaseOrderResult approvePurchaseOrder(String poId, String tenantId) {
        PurchaseOrder po = findOrThrow(poId, tenantId);
        po.approve();
        po = repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderApproved",
                Map.of("poNumber", po.getPoNumber(), "dueDate", po.getDueDate().toString(),
                        "tenantId", tenantId), tenantId);

        log.info("PO approved: id={}, dueDate={}", poId, po.getDueDate());
        return toResult(po);
    }

    @Override
    @Transactional
    public void cancelPurchaseOrder(String poId, String tenantId) {
        PurchaseOrder po = findOrThrow(poId, tenantId);
        po.cancel();
        repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderCancelled",
                Map.of("tenantId", tenantId), tenantId);

        log.info("PO cancelled: id={}", poId);
    }

    private PurchaseOrder findOrThrow(String poId, String tenantId) {
        // SEC-23: tenant-scoped finder + 404 on absent OR wrong-tenant (no existence oracle).
        return TenantOwnership.require(
                repository.findPurchaseOrderById(poId, tenantId), "Purchase order");
    }

    private PurchaseOrderResult toResult(PurchaseOrder po) {
        return new PurchaseOrderResult(
                po.getId(), po.getPoNumber(), po.getBuyerId(), po.getSellerId(),
                po.getAmount(), po.getTaxAmount(), po.getCurrency(), po.getStatus(),
                po.getTerms(), po.getDueDate(), po.getLineItems(), po.getCreatedAt());
    }
}
