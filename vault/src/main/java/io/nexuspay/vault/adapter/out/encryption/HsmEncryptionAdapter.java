package io.nexuspay.vault.adapter.out.encryption;

import io.nexuspay.vault.application.port.out.EncryptionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * HSM-based encryption adapter placeholder for production use.
 * Requires CloudHSM (AWS) or Thales Luna HSM configuration.
 *
 * @since 0.4.0 (Sprint 4.1)
 * @see io.nexuspay.vault.adapter.out.encryption.AesGcmEncryptionAdapter
 */
@Component
@ConditionalOnProperty(name = "nexuspay.vault.encryption.provider", havingValue = "hsm")
public class HsmEncryptionAdapter implements EncryptionPort {

    private static final String NOT_IMPLEMENTED =
            "HSM encryption requires CloudHSM or Thales Luna configuration — see GAP-056";

    @Override
    public EncryptionResult encrypt(byte[] plaintext, String keyId) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, String keyId) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public String currentKeyId() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public String generateFingerprint(String pan) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
