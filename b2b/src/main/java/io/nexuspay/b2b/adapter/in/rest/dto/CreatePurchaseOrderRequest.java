package io.nexuspay.b2b.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreatePurchaseOrderRequest(
        @NotBlank String buyerId,
        @NotBlank String sellerId,
        @NotBlank String poNumber,
        @NotBlank String currency,
        String terms,
        long taxAmount,
        List<LineItemDto> lineItems
) {
    public record LineItemDto(
            String description,
            int quantity,
            long unitCost,
            String commodityCode,
            String unitOfMeasure
    ) {}
}
