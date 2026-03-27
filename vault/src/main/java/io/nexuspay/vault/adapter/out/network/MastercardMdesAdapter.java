package io.nexuspay.vault.adapter.out.network;

import io.nexuspay.vault.application.port.out.MastercardMdesPort;
import io.nexuspay.vault.application.port.out.VisaTokenServicePort;
import io.nexuspay.vault.domain.CryptogramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Stub adapter for Mastercard Digital Enablement Service (MDES).
 *
 * <p>Real MDES enrollment requires 3-6 months of business certification.
 * This stub returns simulated responses for development and testing.</p>
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Component
public class MastercardMdesAdapter implements MastercardMdesPort {

    private static final Logger log = LoggerFactory.getLogger(MastercardMdesAdapter.class);

    @Override
    public VisaTokenServicePort.NetworkTokenProvisionResult provisionToken(
            String panLast4, String panBin, String brand,
            int expMonth, int expYear, String cardholderName) {
        String tokenRef = "mdes_" + UUID.randomUUID().toString().substring(0, 16);
        String tokenLast4 = String.valueOf((int) (Math.random() * 9000) + 1000);
        String expiry = String.format("%02d%02d", expMonth, expYear % 100);

        log.info("MC MDES stub: provisioned token ref={}, last4={}", tokenRef, tokenLast4);
        return new VisaTokenServicePort.NetworkTokenProvisionResult(tokenRef, tokenLast4, expiry);
    }

    @Override
    public CryptogramResult generateCryptogram(String tokenReference, long amount, String currency) {
        String cryptogram = UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        log.info("MC MDES stub: generated cryptogram for token={}", tokenReference);
        return new CryptogramResult(cryptogram, "07", Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    @Override
    public void suspendToken(String tokenReference) {
        log.info("MC MDES stub: suspended token ref={}", tokenReference);
    }

    @Override
    public void deleteToken(String tokenReference) {
        log.info("MC MDES stub: deleted token ref={}", tokenReference);
    }
}
