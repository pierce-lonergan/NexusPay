package io.nexuspay.marketplace.adapter.out.kyc;

import io.nexuspay.marketplace.application.port.out.KycProviderPort;
import io.nexuspay.marketplace.domain.KycStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub KYC provider adapter for development and testing.
 * Real provider integration (Onfido, Persona, Jumio) is tracked in GAP-061.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Component
public class KycProviderStubAdapter implements KycProviderPort {

    private static final Logger log = LoggerFactory.getLogger(KycProviderStubAdapter.class);

    @Override
    public KycVerificationResult initiateVerification(KycVerificationRequest request) {
        String verificationRef = "kyc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String verificationUrl = "https://kyc-stub.nexuspay.io/verify/" + verificationRef;

        log.info("KYC verification initiated (stub): account={}, ref={}", request.accountId(), verificationRef);

        return new KycVerificationResult(verificationRef, KycStatus.IN_REVIEW, verificationUrl);
    }

    @Override
    public KycStatus checkVerificationStatus(String verificationReference) {
        log.info("KYC status check (stub): ref={}, returning VERIFIED", verificationReference);
        return KycStatus.VERIFIED;
    }
}
