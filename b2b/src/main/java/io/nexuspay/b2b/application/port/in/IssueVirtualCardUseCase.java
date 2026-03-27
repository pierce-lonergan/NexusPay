package io.nexuspay.b2b.application.port.in;

import io.nexuspay.b2b.domain.VirtualCardStatus;
import io.nexuspay.b2b.domain.VirtualCardType;

import java.time.Instant;
import java.util.List;

/**
 * Use case for issuing and managing virtual cards.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface IssueVirtualCardUseCase {

    VirtualCardResult issueCard(IssueCardCommand command);

    VirtualCardResult getCard(String cardId, String tenantId);

    void freezeCard(String cardId, String tenantId);

    void cancelCard(String cardId, String tenantId);

    record IssueCardCommand(
            String tenantId,
            VirtualCardType cardType,
            long amountLimit,
            String currency,
            Instant expiresAt,
            List<String> merchantCategoryCodes,
            String purchaseOrderId
    ) {}

    record VirtualCardResult(
            String cardId,
            String cardLast4,
            VirtualCardType cardType,
            long amountLimit,
            long spentAmount,
            long availableBalance,
            String currency,
            VirtualCardStatus status,
            String issuingProvider,
            Instant expiresAt,
            Instant createdAt
    ) {}
}
