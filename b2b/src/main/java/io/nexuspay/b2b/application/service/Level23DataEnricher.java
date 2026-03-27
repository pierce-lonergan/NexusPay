package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enriches payment requests with Level 2 and Level 3 commercial card data
 * for reduced interchange rates on corporate card transactions.
 *
 * <p>Level 2 data (summary fields) qualifies for ~0.3-0.5% lower interchange.
 * Level 3 data (line-item detail) qualifies for the lowest available rates
 * on large-ticket commercial transactions.</p>
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Component
public class Level23DataEnricher {

    private static final Logger log = LoggerFactory.getLogger(Level23DataEnricher.class);

    /**
     * Builds Level 2 data from a purchase order.
     */
    public Level2Data buildLevel2Data(PurchaseOrder po) {
        return new Level2Data(
                po.getPoNumber(),
                po.getTaxAmount(),
                po.getTaxAmount() > 0 ? "Y" : "N",
                null,  // merchant tax ID — set by caller
                po.getPoNumber()
        );
    }

    /**
     * Builds Level 3 data with line-item detail from a purchase order.
     */
    public Level3Data buildLevel3Data(PurchaseOrder po) {
        List<Level3Data.Level3LineItem> lineItems = po.getLineItems().stream()
                .map(li -> new Level3Data.Level3LineItem(
                        li.description(),
                        li.quantity(),
                        li.unitCost(),
                        li.commodityCode(),
                        li.unitOfMeasure(),
                        0,  // discount amount
                        0,  // per-item tax amount
                        null // tax rate
                ))
                .toList();

        return new Level3Data(
                lineItems,
                0,     // shipping amount
                0,     // duty amount
                null,  // destination postal code — set by caller
                null   // destination country code — set by caller
        );
    }

    /**
     * Determines whether a purchase order qualifies for Level 3 enrichment.
     * Typically requires line items with commodity codes.
     */
    public boolean qualifiesForLevel3(PurchaseOrder po) {
        if (po.getLineItems() == null || po.getLineItems().isEmpty()) {
            return false;
        }
        // At least one line item must have a commodity code
        boolean hasCommCodes = po.getLineItems().stream()
                .anyMatch(li -> li.commodityCode() != null && !li.commodityCode().isBlank());

        if (hasCommCodes) {
            log.debug("PO {} qualifies for Level 3 enrichment ({} line items)",
                    po.getId(), po.getLineItems().size());
        }
        return hasCommCodes;
    }
}
