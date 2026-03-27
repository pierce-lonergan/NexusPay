package io.nexuspay.vault.application.port.out;

/**
 * Out-port for encryption operations. Implementations may use software
 * encryption (AES-256-GCM for dev/test) or HSM (CloudHSM/Thales Luna
 * for production).
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface EncryptionPort {

    EncryptionResult encrypt(byte[] plaintext, String keyId);

    byte[] decrypt(byte[] ciphertext, String keyId);

    String currentKeyId();

    String generateFingerprint(String pan);

    record EncryptionResult(byte[] ciphertext, String keyId) {}
}
