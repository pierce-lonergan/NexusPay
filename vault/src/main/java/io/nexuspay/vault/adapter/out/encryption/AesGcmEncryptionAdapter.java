package io.nexuspay.vault.adapter.out.encryption;

import io.nexuspay.vault.application.port.out.EncryptionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Software-based AES-256-GCM encryption adapter for development and testing.
 * In production, use {@link HsmEncryptionAdapter} with CloudHSM or Thales Luna.
 *
 * <p>Ciphertext format: {@code [12-byte IV][encrypted data + 16-byte auth tag]}</p>
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Component
@ConditionalOnProperty(name = "nexuspay.vault.encryption.provider", havingValue = "software", matchIfMissing = true)
public class AesGcmEncryptionAdapter implements EncryptionPort {

    private static final Logger log = LoggerFactory.getLogger(AesGcmEncryptionAdapter.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String masterKeyBase64;
    private final String currentKeyId;

    public AesGcmEncryptionAdapter(
            @org.springframework.beans.factory.annotation.Value("${nexuspay.vault.encryption.master-key}") String masterKeyBase64,
            @org.springframework.beans.factory.annotation.Value("${nexuspay.vault.encryption.current-key-id}") String currentKeyId) {
        this.masterKeyBase64 = masterKeyBase64;
        this.currentKeyId = currentKeyId;
        log.info("AES-256-GCM software encryption adapter initialized with key ID: {}", currentKeyId);
    }

    @Override
    public EncryptionResult encrypt(byte[] plaintext, String keyId) {
        try {
            SecretKey key = deriveKey(keyId);
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);

            // Prepend IV to ciphertext
            byte[] result = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);

            return new EncryptionResult(result, keyId);
        } catch (Exception e) {
            throw new VaultEncryptionException("Failed to encrypt data", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, String keyId) {
        try {
            SecretKey key = deriveKey(keyId);

            // Extract IV from first 12 bytes
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[ciphertext.length - IV_LENGTH];
            System.arraycopy(ciphertext, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new VaultEncryptionException("Failed to decrypt data", e);
        }
    }

    @Override
    public String currentKeyId() {
        return currentKeyId;
    }

    @Override
    public String generateFingerprint(String pan) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(pan.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new VaultEncryptionException("Failed to generate fingerprint", e);
        }
    }

    private SecretKey deriveKey(String keyId) {
        // In a real system, keyId would look up from a key store.
        // For the software adapter, we derive from master key + keyId.
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(masterKeyBase64));
            digest.update(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] keyBytes = digest.digest();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new VaultEncryptionException("Failed to derive encryption key", e);
        }
    }

    public static class VaultEncryptionException extends RuntimeException {
        public VaultEncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
