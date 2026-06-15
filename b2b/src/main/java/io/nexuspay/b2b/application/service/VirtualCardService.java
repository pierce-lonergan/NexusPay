package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.IssueVirtualCardUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.application.port.out.CardIssuingPort;
import io.nexuspay.b2b.domain.VirtualCard;
import io.nexuspay.common.tenant.TenantOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for virtual card issuance and lifecycle management.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Service
public class VirtualCardService implements IssueVirtualCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(VirtualCardService.class);

    private final B2bRepository repository;
    private final CardIssuingPort cardIssuingPort;
    private final B2bEventPublisher eventPublisher;

    public VirtualCardService(B2bRepository repository, CardIssuingPort cardIssuingPort,
                               B2bEventPublisher eventPublisher) {
        this.repository = repository;
        this.cardIssuingPort = cardIssuingPort;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public VirtualCardResult issueCard(IssueCardCommand command) {
        // Issue via provider
        var issuingResult = cardIssuingPort.issueCard(new CardIssuingPort.IssuingRequest(
                command.cardType(), command.amountLimit(), command.currency(),
                command.expiresAt(), command.merchantCategoryCodes()));

        // Create domain model
        VirtualCard card = VirtualCard.create(
                command.tenantId(), issuingResult.providerName(),
                command.cardType(), command.amountLimit(),
                command.currency(), command.expiresAt());
        card.setExternalCardId(issuingResult.externalCardId());
        card.setCardLast4(issuingResult.cardLast4());
        card.setMerchantCategoryCodes(command.merchantCategoryCodes());
        if (command.purchaseOrderId() != null) {
            card.setPurchaseOrderId(command.purchaseOrderId());
        }

        card = repository.saveVirtualCard(card);

        eventPublisher.publishEvent("VirtualCard", card.getId(), "VirtualCardIssued",
                Map.of("cardType", command.cardType().name(),
                        "amountLimit", command.amountLimit(),
                        "currency", command.currency(),
                        "provider", issuingResult.providerName(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Virtual card issued: id={}, type={}, limit={}{}", card.getId(),
                command.cardType(), command.amountLimit(), command.currency());

        return toResult(card);
    }

    @Override
    @Transactional(readOnly = true)
    public VirtualCardResult getCard(String cardId, String tenantId) {
        return toResult(findOrThrow(cardId, tenantId));
    }

    @Override
    @Transactional
    public void freezeCard(String cardId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped lifecycle write.
        VirtualCard card = findOrThrow(cardId, tenantId);
        card.freeze();
        repository.saveVirtualCard(card);

        if (card.getExternalCardId() != null) {
            cardIssuingPort.freezeCard(card.getExternalCardId());
        }

        eventPublisher.publishEvent("VirtualCard", cardId, "VirtualCardFrozen",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Virtual card frozen: id={}", cardId);
    }

    @Override
    @Transactional
    public void cancelCard(String cardId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped lifecycle write.
        VirtualCard card = findOrThrow(cardId, tenantId);
        card.cancel();
        repository.saveVirtualCard(card);

        if (card.getExternalCardId() != null) {
            cardIssuingPort.cancelCard(card.getExternalCardId());
        }

        eventPublisher.publishEvent("VirtualCard", cardId, "VirtualCardCancelled",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Virtual card cancelled: id={}", cardId);
    }

    private VirtualCard findOrThrow(String cardId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped finder + 404 on absent OR wrong-tenant.
        return TenantOwnership.require(
                repository.findVirtualCardById(cardId, tenantId), "Virtual card");
    }

    private VirtualCardResult toResult(VirtualCard card) {
        return new VirtualCardResult(
                card.getId(), card.getCardLast4(), card.getCardType(),
                card.getAmountLimit(), card.getSpentAmount(), card.availableBalance(),
                card.getCurrency(), card.getStatus(), card.getIssuingProvider(),
                card.getExpiresAt(), card.getCreatedAt());
    }
}
