package io.nexuspay.b2b.adapter.in.rest.dto;

import java.time.Instant;

public record VirtualCardResponse(
        String cardId, String cardLast4, String cardType,
        long amountLimit, long spentAmount, long availableBalance,
        String currency, String status, String issuingProvider,
        Instant expiresAt, Instant createdAt
) {}
