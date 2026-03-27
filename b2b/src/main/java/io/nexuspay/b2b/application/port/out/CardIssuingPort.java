package io.nexuspay.b2b.application.port.out;

import io.nexuspay.b2b.domain.VirtualCardType;

import java.time.Instant;
import java.util.List;

/**
 * Outbound port for virtual card issuing via provider integration.
 * Abstracts over providers like Marqeta, Lithic, or Stripe Issuing.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface CardIssuingPort {

    IssuingResult issueCard(IssuingRequest request);

    void freezeCard(String externalCardId);

    void cancelCard(String externalCardId);

    record IssuingRequest(
            VirtualCardType cardType,
            long amountLimit,
            String currency,
            Instant expiresAt,
            List<String> merchantCategoryCodes
    ) {}

    record IssuingResult(
            String externalCardId,
            String cardLast4,
            String providerName
    ) {}
}
