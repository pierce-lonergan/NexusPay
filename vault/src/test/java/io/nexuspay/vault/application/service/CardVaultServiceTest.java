package io.nexuspay.vault.application.service;

import io.nexuspay.vault.application.port.in.VaultCardUseCase;
import io.nexuspay.vault.application.port.in.VaultCardUseCase.VaultCardCommand;
import io.nexuspay.vault.application.port.in.VaultCardUseCase.VaultCardResult;
import io.nexuspay.vault.application.port.in.VaultCardUseCase.VaultedCardInfo;
import io.nexuspay.vault.application.port.out.EncryptionPort;
import io.nexuspay.vault.application.port.out.EncryptionPort.EncryptionResult;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.domain.CardBrand;
import io.nexuspay.vault.domain.VaultToken;
import io.nexuspay.vault.domain.VaultedCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardVaultServiceTest {

    @Mock
    private VaultRepository repository;

    @Mock
    private EncryptionPort encryption;

    @Mock
    private VaultEventPublisher eventPublisher;

    @InjectMocks
    private CardVaultService service;

    private static final String TENANT = "tenant-1";
    private static final String VISA_PAN = "4111111111111111";

    @Test
    void vaultCard_validPan_returnsVaultToken() {
        // Arrange
        VaultCardCommand command = new VaultCardCommand(TENANT, VISA_PAN, 12, 2028, "John Doe");

        when(encryption.generateFingerprint(VISA_PAN)).thenReturn("fp_abc123");
        when(repository.findCardByFingerprint(TENANT, "fp_abc123")).thenReturn(Optional.empty());
        when(encryption.currentKeyId()).thenReturn("key-001");
        when(encryption.encrypt(any(byte[].class), eq("key-001")))
                .thenReturn(new EncryptionResult(new byte[]{1, 2, 3}, "key-001"));
        when(repository.saveCard(any(VaultedCard.class))).thenAnswer(invocation -> {
            VaultedCard card = invocation.getArgument(0);
            return card;
        });
        when(repository.saveToken(any(VaultToken.class))).thenAnswer(invocation -> {
            VaultToken token = invocation.getArgument(0);
            return token;
        });

        // Act
        VaultCardResult result = service.vaultCard(command);

        // Assert
        assertThat(result.vaultTokenId()).startsWith("tok_");
        assertThat(result.panLast4()).isEqualTo("1111");
        assertThat(result.brand()).isEqualTo(CardBrand.VISA);

        verify(encryption).encrypt(any(byte[].class), eq("key-001"));
        verify(repository).saveCard(any(VaultedCard.class));
        verify(repository).saveToken(any(VaultToken.class));

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher).publishEvent(eq("VaultedCard"), anyString(), eventTypeCaptor.capture(),
                any(Map.class), eq(TENANT));
        assertThat(eventTypeCaptor.getValue()).isEqualTo("CardVaulted");
    }

    @Test
    void vaultCard_duplicateFingerprint_returnsExistingToken() {
        // Arrange
        VaultCardCommand command = new VaultCardCommand(TENANT, VISA_PAN, 12, 2028, "John Doe");

        VaultedCard existingCard = new VaultedCard();
        existingCard.setId("vc_existing");
        existingCard.setPanLast4("1111");
        existingCard.setBrand(CardBrand.VISA);

        VaultToken existingToken = new VaultToken();
        existingToken.setId("tok_existing");
        existingToken.setVaultedCardId("vc_existing");

        when(encryption.generateFingerprint(VISA_PAN)).thenReturn("fp_abc123");
        when(repository.findCardByFingerprint(TENANT, "fp_abc123")).thenReturn(Optional.of(existingCard));
        // findTokenForCard uses cardId as fallback reference
        when(repository.findTokenById("vc_existing")).thenReturn(Optional.of(existingToken));

        // Act
        VaultCardResult result = service.vaultCard(command);

        // Assert
        assertThat(result.vaultTokenId()).isEqualTo("tok_existing");
        assertThat(result.panLast4()).isEqualTo("1111");
        assertThat(result.brand()).isEqualTo(CardBrand.VISA);

        verify(encryption, never()).encrypt(any(byte[].class), anyString());
        verify(repository, never()).saveCard(any(VaultedCard.class));
    }

    @Test
    void vaultCard_invalidLuhn_throwsException() {
        VaultCardCommand command = new VaultCardCommand(TENANT, "4111111111111112", 12, 2028, "John Doe");

        assertThatThrownBy(() -> service.vaultCard(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Luhn");
    }

    @Test
    void getCard_validToken_returnsMetadata() {
        // Arrange
        VaultToken token = new VaultToken();
        token.setId("tok_123");
        token.setVaultedCardId("vc_456");

        VaultedCard card = new VaultedCard();
        card.setId("vc_456");
        card.setPanLast4("1111");
        card.setPanBin("41111111");
        card.setBrand(CardBrand.VISA);
        card.setExpMonth(12);
        card.setExpYear(2028);
        card.setCardholderName("John Doe");
        card.setCreatedAt(Instant.parse("2026-01-15T10:00:00Z"));

        when(repository.findTokenById("tok_123")).thenReturn(Optional.of(token));
        when(repository.findCardById("vc_456")).thenReturn(Optional.of(card));

        // Act
        VaultedCardInfo info = service.getCard("tok_123", TENANT);

        // Assert
        assertThat(info.vaultTokenId()).isEqualTo("tok_123");
        assertThat(info.panLast4()).isEqualTo("1111");
        assertThat(info.brand()).isEqualTo(CardBrand.VISA);
        assertThat(info.cardholderName()).isEqualTo("John Doe");
        // encryptedPan is never exposed in VaultedCardInfo
    }

    @Test
    void deleteCard_cascadesDeletes() {
        // Arrange
        VaultToken token = new VaultToken();
        token.setId("tok_123");
        token.setVaultedCardId("vc_456");

        when(repository.findTokenById("tok_123")).thenReturn(Optional.of(token));

        // Act
        service.deleteCard("tok_123", TENANT);

        // Assert — verify cascade order
        var inOrder = org.mockito.Mockito.inOrder(repository, eventPublisher);
        inOrder.verify(repository).deleteNetworkTokensByCardId("vc_456");
        inOrder.verify(repository).deleteToken("tok_123");
        inOrder.verify(repository).deleteCard("vc_456");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(eventPublisher).publishEvent(eq("VaultedCard"), eq("vc_456"), eventTypeCaptor.capture(),
                any(Map.class), eq(TENANT));
        assertThat(eventTypeCaptor.getValue()).isEqualTo("CardDeleted");
    }

    @Test
    void detectBrand_visa_returnsVisa() {
        assertThat(CardVaultService.detectBrand("4111")).isEqualTo(CardBrand.VISA);
    }

    @Test
    void detectBrand_mastercard_returnsMastercard() {
        assertThat(CardVaultService.detectBrand("5100")).isEqualTo(CardBrand.MASTERCARD);
    }

    @Test
    void detectBrand_amex_returnsAmex() {
        assertThat(CardVaultService.detectBrand("3400")).isEqualTo(CardBrand.AMEX);
    }
}
