package io.nexuspay.b2b.adapter.in.rest.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PurchaseOrderResponse(
        String poId, String poNumber, String buyerId, String sellerId,
        long amount, long taxAmount, String currency, String status,
        String terms, LocalDate dueDate, List<LineItemDto> lineItems, Instant createdAt
) {
    public record LineItemDto(String description, int quantity, long unitCost,
                               String commodityCode, String unitOfMeasure) {}
}
