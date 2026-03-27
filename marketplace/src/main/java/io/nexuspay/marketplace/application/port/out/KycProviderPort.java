package io.nexuspay.marketplace.application.port.out;

import io.nexuspay.marketplace.domain.KycStatus;

/**
 * Outbound port for KYC/KYB verification provider integration.
 * Abstracts over providers like Onfido, Persona, or Jumio.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface KycProviderPort {

    /**
     * Initiates KYC verification for a connected account.
     *
     * @return result containing the verification session reference and initial status
     */
    KycVerificationResult initiateVerification(KycVerificationRequest request);

    /**
     * Checks the current status of a KYC verification.
     */
    KycStatus checkVerificationStatus(String verificationReference);

    record KycVerificationRequest(
            String accountId,
            String businessName,
            String email,
            String country
    ) {}

    record KycVerificationResult(
            String verificationReference,
            KycStatus status,
            String verificationUrl
    ) {}
}
