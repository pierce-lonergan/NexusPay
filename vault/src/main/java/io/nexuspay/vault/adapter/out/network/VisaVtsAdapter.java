package io.nexuspay.vault.adapter.out.network;

import io.nexuspay.vault.application.port.out.VisaTokenServicePort;
import io.nexuspay.vault.domain.CryptogramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Stub adapter for Visa Token Service (VTS).
 *
 * <p>Real VTS enrollment requires 3-6 months of business certification.
 * This stub returns simulated responses for development and testing.</p>
 *
 * @since 0.4.0 (Sprint 4.1)
 * @see io.nexuspay.vault.application.port.out.VisaTokenServicePort
 */
@Component
public class VisaVtsAdapter implements VisaTokenServicePort {

    private static final Logger log = LoggerFactory.getLogger(VisaVtsAdapter.class);

    @Override
    public NetworkTokenProvisionResult provisionToken(String panLast4, String panBin, String brand,
                                                       int expMonth, int expYear, String cardholderName) {
        String tokenRef = "vts_" + UUID.randomUUID().toString().substring(0, 16);
        String tokenLast4 = String.valueOf((int) (Math.random() * 9000) + 1000);
        String expiry = String.format("%02d%02d", expMonth, expYear % 100);

        log.info("Visa VTS stub: provisioned token ref={}, last4={}", tokenRef, tokenLast4);
        return new NetworkTokenProvisionResult(tokenRef, tokenLast4, expiry);
    }

    @Override
    public CryptogramResult generateCryptogram(String tokenReference, long amount, String currency) {
        String cryptogram = UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        log.info("Visa VTS stub: generated cryptogram for token={}", tokenReference);
        return new CryptogramResult(cryptogram, "05", Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    @Override
    public void suspendToken(String tokenReference) {
        log.info("Visa VTS stub: suspended token ref={}", tokenReference);
    }

    @Override
    public void deleteToken(String tokenReference) {
        log.info("Visa VTS stub: deleted token ref={}", tokenReference);
    }
}
