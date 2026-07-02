package io.nexuspay.vault.application.service;

import io.nexuspay.common.crypto.EncryptionPort;
import io.nexuspay.vault.adapter.out.encryption.AesGcmEncryptionAdapter;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.domain.CardBrand;
import io.nexuspay.vault.domain.VaultedCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-059: atomicity + idempotency of {@code CardVaultService.rotateCardKey} against a REAL
 * {@link AesGcmEncryptionAdapter} (mocked repo). The adapter derives a distinct AES key per key-id
 * from the same master key, so re-encryption under a new key-id is genuinely a different ciphertext
 * that only decrypts under the new key.
 *
 * <ul>
 *   <li>After rotation the persisted card's {@code encryptionKeyId} is the CURRENT active key AND its
 *       ciphertext decrypts under that active key back to the ORIGINAL PAN.</li>
 *   <li>Both {@code encryptedPan} and {@code encryptionKeyId} are written together in ONE saveCard
 *       (no window where they disagree).</li>
 *   <li>A card NOT on the retired key returns SKIPPED and is never re-encrypted/persisted (idempotent).</li>
 * </ul>
 */
class CardVaultServiceRotationTest {

    /** 32-byte test master key, base64-encoded (same shape as AesGcmEncryptionAdapterTest). */
    private static final String TEST_MASTER_KEY = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private static final String RETIRED_KEY = "key-000";
    private static final String ACTIVE_KEY = "key-001";
    private static final String PAN = "4111111111111111";
    private static final String TENANT = "tenant-1";

    private VaultRepository repository;
    private AesGcmEncryptionAdapter encryption;
    private CardVaultService service;

    @BeforeEach
    void setUp() {
        repository = mock(VaultRepository.class);
        // Real adapter; the CURRENT active key is key-001.
        encryption = new AesGcmEncryptionAdapter(TEST_MASTER_KEY, ACTIVE_KEY);
        service = new CardVaultService(repository, encryption, mock(VaultEventPublisher.class));
    }

    private VaultedCard cardOnKey(String keyId) {
        // Encrypt the PAN under `keyId` so the seeded card is genuinely readable under that key.
        EncryptionPort.EncryptionResult enc = encryption.encrypt(PAN.getBytes(StandardCharsets.UTF_8), keyId);
        VaultedCard card = VaultedCard.create(TENANT, enc.ciphertext(), "1111", "411111",
                CardBrand.VISA, 12, 2030, "John Doe", keyId, "fp_test");
        card.setId("vc_rotate_1");
        return card;
    }

    @Test
    void rotate_reEncryptsOntoActiveKey_andCiphertextDecryptsToOriginalPan() {
        VaultedCard onRetired = cardOnKey(RETIRED_KEY);
        byte[] oldCiphertext = onRetired.getEncryptedPan().clone();
        when(repository.findCardById("vc_rotate_1")).thenReturn(Optional.of(onRetired));
        when(repository.saveCard(any(VaultedCard.class))).thenAnswer(inv -> inv.getArgument(0));

        CardVaultService.RotationOutcome outcome = service.rotateCardKey("vc_rotate_1", RETIRED_KEY);

        assertThat(outcome).isEqualTo(CardVaultService.RotationOutcome.ROTATED);

        ArgumentCaptor<VaultedCard> cap = ArgumentCaptor.forClass(VaultedCard.class);
        verify(repository).saveCard(cap.capture());
        VaultedCard saved = cap.getValue();

        // key id flipped to the active key...
        assertThat(saved.getEncryptionKeyId()).isEqualTo(ACTIVE_KEY);
        // ...ciphertext changed (re-encrypted, not left as-is)...
        assertThat(saved.getEncryptedPan()).isNotEqualTo(oldCiphertext);
        // ...and the new ciphertext decrypts under the ACTIVE key back to the ORIGINAL PAN.
        byte[] roundTrip = encryption.decrypt(saved.getEncryptedPan(), ACTIVE_KEY);
        assertThat(new String(roundTrip, StandardCharsets.UTF_8)).isEqualTo(PAN);
    }

    @Test
    void rotate_cardNotOnRetiredKey_returnsSkipped_andNeverReEncrypts() {
        // Card already on the active key — must be skipped without decrypting/persisting.
        VaultedCard onActive = cardOnKey(ACTIVE_KEY);
        when(repository.findCardById("vc_rotate_1")).thenReturn(Optional.of(onActive));

        CardVaultService.RotationOutcome outcome = service.rotateCardKey("vc_rotate_1", RETIRED_KEY);

        assertThat(outcome).isEqualTo(CardVaultService.RotationOutcome.SKIPPED);
        verify(repository, never()).saveCard(any());
    }

    @Test
    void rotate_isIdempotent_secondCallSkips() {
        // First call rotates; the saved card is now on the active key. A second call on the same card
        // (re-loaded on the active key) must SKIP — proving a re-run / racing replica is a safe no-op.
        VaultedCard onRetired = cardOnKey(RETIRED_KEY);
        when(repository.findCardById("vc_rotate_1"))
                .thenReturn(Optional.of(onRetired));
        when(repository.saveCard(any(VaultedCard.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.rotateCardKey("vc_rotate_1", RETIRED_KEY))
                .isEqualTo(CardVaultService.RotationOutcome.ROTATED);

        // onRetired was mutated in place to the active key by the first rotation; a second call skips.
        assertThat(service.rotateCardKey("vc_rotate_1", RETIRED_KEY))
                .isEqualTo(CardVaultService.RotationOutcome.SKIPPED);
    }
}
