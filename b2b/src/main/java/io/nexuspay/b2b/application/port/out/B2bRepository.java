package io.nexuspay.b2b.application.port.out;

import io.nexuspay.b2b.domain.*;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for B2B persistence operations.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface B2bRepository {

    // --- PurchaseOrder ---
    PurchaseOrder savePurchaseOrder(PurchaseOrder po);
    Optional<PurchaseOrder> findPurchaseOrderById(String id);
    List<PurchaseOrder> findPurchaseOrdersByTenantId(String tenantId);

    // --- B2bInvoice ---
    B2bInvoice saveInvoice(B2bInvoice invoice);
    Optional<B2bInvoice> findInvoiceById(String id);
    Optional<B2bInvoice> findInvoiceByPurchaseOrderId(String purchaseOrderId);

    // --- VirtualCard ---
    VirtualCard saveVirtualCard(VirtualCard card);
    Optional<VirtualCard> findVirtualCardById(String id);
    List<VirtualCard> findVirtualCardsByTenantId(String tenantId);

    // --- VendorPayment ---
    VendorPayment saveVendorPayment(VendorPayment payment);
    Optional<VendorPayment> findVendorPaymentById(String id);
    List<VendorPayment> findVendorPaymentsByBatchId(String batchId);
    List<VendorPayment> findVendorPaymentsByVendorId(String vendorId);
}
