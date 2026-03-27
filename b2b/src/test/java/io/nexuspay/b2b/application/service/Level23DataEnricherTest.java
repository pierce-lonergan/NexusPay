package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Level23DataEnricher}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
class Level23DataEnricherTest {

    private Level23DataEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new Level23DataEnricher();
    }

    @Test
    void buildLevel2Data_extractsFromPO() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-001", "USD", PaymentTerms.NET_30);
        po.setTaxAmount(5000);

        Level2Data l2 = enricher.buildLevel2Data(po);

        assertEquals("PO-001", l2.customerReferenceNumber());
        assertEquals(5000, l2.taxAmount());
        assertEquals("Y", l2.taxIndicator());
    }

    @Test
    void buildLevel2Data_noTax() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-002", "USD", PaymentTerms.NET_30);
        po.setTaxAmount(0);

        Level2Data l2 = enricher.buildLevel2Data(po);

        assertEquals("N", l2.taxIndicator());
    }

    @Test
    void buildLevel3Data_buildsLineItems() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-003", "USD", PaymentTerms.NET_30);
        po.addLineItem(new LineItem("Widget A", 10, 100, "WDGT-A", "EA"));
        po.addLineItem(new LineItem("Widget B", 5, 200, "WDGT-B", "BX"));

        Level3Data l3 = enricher.buildLevel3Data(po);

        assertEquals(2, l3.lineItems().size());
        assertEquals("Widget A", l3.lineItems().get(0).description());
        assertEquals(10, l3.lineItems().get(0).quantity());
        assertEquals(100, l3.lineItems().get(0).unitCost());
        assertEquals("WDGT-A", l3.lineItems().get(0).commodityCode());
    }

    @Test
    void qualifiesForLevel3_trueWhenCommodityCodesPresent() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-004", "USD", PaymentTerms.NET_30);
        po.addLineItem(new LineItem("Widget", 10, 100, "WDGT-01", "EA"));

        assertTrue(enricher.qualifiesForLevel3(po));
    }

    @Test
    void qualifiesForLevel3_falseWhenNoLineItems() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-005", "USD", PaymentTerms.NET_30);

        assertFalse(enricher.qualifiesForLevel3(po));
    }

    @Test
    void qualifiesForLevel3_falseWhenNoCommodityCodes() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-006", "USD", PaymentTerms.NET_30);
        po.addLineItem(new LineItem("Widget", 10, 100, null, "EA"));

        assertFalse(enricher.qualifiesForLevel3(po));
    }
}
