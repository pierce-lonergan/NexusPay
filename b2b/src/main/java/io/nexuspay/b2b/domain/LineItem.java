package io.nexuspay.b2b.domain;

/**
 * A single line item within a purchase order.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public record LineItem(
        String description,
        int quantity,
        long unitCost,
        String commodityCode,
        String unitOfMeasure
) {
    /**
     * Total cost for this line item in minor currency units.
     */
    public long totalCost() {
        return (long) quantity * unitCost;
    }
}
