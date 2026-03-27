package io.nexuspay.b2b.domain;

import java.util.List;

/**
 * Level 3 commercial card data for the lowest interchange rates.
 * Contains line-item detail required by Visa/MC for large-ticket corporate transactions.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public record Level3Data(
        List<Level3LineItem> lineItems,
        long shippingAmount,
        long dutyAmount,
        String destinationPostalCode,
        String destinationCountryCode
) {
    public record Level3LineItem(
            String description,
            int quantity,
            long unitCost,
            String commodityCode,
            String unitOfMeasure,
            long discountAmount,
            long taxAmount,
            String taxRate
    ) {}
}
