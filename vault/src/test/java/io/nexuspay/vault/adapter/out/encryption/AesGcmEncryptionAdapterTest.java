package io.nexuspay.vault.adapter.out.encryption;

import io.nexuspay.common.crypto.EncryptionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptionAdapterTest {

    private AesGcmEncryptionAdapter adapter;

    /** 32-byte test master key, base64-encoded. */
    private static final String TEST_MASTER_KEY = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private static final String KEY_ID = "key-001";

    @BeforeEach
    void setUp() {
        adapter = new AesGcmEncryptionAdapter(TEST_MASTER_KEY, KEY_ID);
    }

    @Test
    void encryptDecrypt_roundTrip() {
        String pan = "4111111111111111";
        byte[] plaintext = pan.getBytes(StandardCharsets.UTF_8);

        EncryptionPort.EncryptionResult encrypted = adapter.encrypt(plaintext, KEY_ID);
        byte[] decrypted = adapter.decrypt(encrypted.ciphertext(), KEY_ID);

        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(pan);
    }

    @Test
    void encrypt_producesUniqueCiphertext() {
        byte[] plaintext = "4111111111111111".getBytes(StandardCharsets.UTF_8);

        EncryptionPort.EncryptionResult first = adapter.encrypt(plaintext, KEY_ID);
        EncryptionPort.EncryptionResult second = adapter.encrypt(plaintext, KEY_ID);

        // Each encryption uses a random IV, so ciphertext should differ
        assertThat(Arrays.equals(first.ciphertext(), second.ciphertext())).isFalse();
    }

    @Test
    void decrypt_wrongKeyId_throwsException() {
        byte[] plaintext = "4111111111111111".getBytes(StandardCharsets.UTF_8);

        EncryptionPort.EncryptionResult encrypted = adapter.encrypt(plaintext, "key-001");

        // Decrypting with a different keyId derives a different key, causing GCM auth tag failure
        assertThatThrownBy(() -> adapter.decrypt(encrypted.ciphertext(), "key-002"))
                .isInstanceOf(AesGcmEncryptionAdapter.VaultEncryptionException.class);
    }

    @Test
    void generateFingerprint_deterministic() {
        String pan = "4111111111111111";

        String first = adapter.generateFingerprint(pan);
        String second = adapter.generateFingerprint(pan);

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotBlank();
    }

    @Test
    void generateFingerprint_differentPans_differentResults() {
        String fingerprint1 = adapter.generateFingerprint("4111111111111111");
        String fingerprint2 = adapter.generateFingerprint("5500000000000004");

        assertThat(fingerprint1).isNotEqualTo(fingerprint2);
    }
}
