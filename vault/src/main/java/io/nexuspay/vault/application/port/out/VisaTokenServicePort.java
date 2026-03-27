package io.nexuspay.vault.application.port.out;

import io.nexuspay.vault.domain.CryptogramResult;

/**
 * Out-port for Visa Token Service (VTS) network token operations.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface VisaTokenServicePort {

    NetworkTokenProvisionResult provisionToken(String panLast4, String panBin, String brand,
                                                int expMonth, int expYear, String cardholderName);

    CryptogramResult generateCryptogram(String tokenReference, long amount, String currency);

    void suspendToken(String tokenReference);

    void deleteToken(String tokenReference);

    record NetworkTokenProvisionResult(String tokenReference, String tokenLast4, String tokenExpiry) {}
}
