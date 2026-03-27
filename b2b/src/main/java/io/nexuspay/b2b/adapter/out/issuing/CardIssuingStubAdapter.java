package io.nexuspay.b2b.adapter.out.issuing;

import io.nexuspay.b2b.application.port.out.CardIssuingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub card issuing adapter for development and testing.
 * Real provider integration (Marqeta, Lithic, Stripe Issuing) is tracked in GAP-066.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Component
public class CardIssuingStubAdapter implements CardIssuingPort {

    private static final Logger log = LoggerFactory.getLogger(CardIssuingStubAdapter.class);

    @Override
    public IssuingResult issueCard(IssuingRequest request) {
        String externalCardId = "ext_card_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String cardLast4 = String.format("%04d", (int) (Math.random() * 10000));

        log.info("Virtual card issued (stub): type={}, limit={} {}, externalId={}",
                request.cardType(), request.amountLimit(), request.currency(), externalCardId);

        return new IssuingResult(externalCardId, cardLast4, "stub-issuer");
    }

    @Override
    public void freezeCard(String externalCardId) {
        log.info("Virtual card frozen (stub): externalId={}", externalCardId);
    }

    @Override
    public void cancelCard(String externalCardId) {
        log.info("Virtual card cancelled (stub): externalId={}", externalCardId);
    }
}
