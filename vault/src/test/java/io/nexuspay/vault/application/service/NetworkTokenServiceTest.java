package io.nexuspay.vault.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.vault.application.port.in.ProvisionNetworkTokenUseCase.NetworkTokenResult;
import io.nexuspay.vault.application.port.out.AmexTokenServicePort;
import io.nexuspay.vault.application.port.out.MastercardMdesPort;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.application.port.out.VisaTokenServicePort;
import io.nexuspay.vault.application.port.out.VisaTokenServicePort.NetworkTokenProvisionResult;
import io.nexuspay.vault.domain.CardBrand;
import io.nexuspay.vault.domain.CryptogramResult;
import io.nexuspay.vault.domain.NetworkToken;
import io.nexuspay.vault.domain.NetworkType;
import io.nexuspay.vault.domain.TokenState;
import io.nexuspay.vault.domain.VaultToken;
import io.nexuspay.vault.domain.VaultedCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkTokenServiceTest {

    @Mock
    private VaultRepository repository;

    @Mock
    private VisaTokenServicePort visaPort;

    @Mock
    private MastercardMdesPort mastercardPort;

    @Mock
    private AmexTokenServicePort amexPort;

    @Mock
    private VaultEventPublisher eventPublisher;

    @InjectMocks
    private NetworkTokenService service;

    private static final String TENANT = "tenant-1";

    private VaultToken createVaultToken(String tokenId, String cardId) {
        VaultToken token = new VaultToken();
        token.setId(tokenId);
        token.setVaultedCardId(cardId);
        token.setTenantId(TENANT);
        return token;
    }

    private VaultedCard createVaultedCard(String cardId, CardBrand brand) {
        VaultedCard card = new VaultedCard();
        card.setId(cardId);
        card.setTenantId(TENANT);
        card.setPanLast4("1111");
        card.setPanBin("41111111");
        card.setBrand(brand);
        card.setExpMonth(12);
        card.setExpYear(2028);
        card.setCardholderName("John Doe");
        card.setCreatedAt(Instant.now());
        return card;
    }

    @Test
    void provision_visaCard_callsVtsPort() {
        // Arrange
        VaultToken token = createVaultToken("tok_123", "vc_456");
        VaultedCard card = createVaultedCard("vc_456", CardBrand.VISA);

        // SEC-BATCH-1: vault token is loaded tenant-scoped before provisioning.
        when(repository.findTokenById("tok_123", TENANT)).thenReturn(Optional.of(token));
        when(repository.findCardById("vc_456")).thenReturn(Optional.of(card));
        when(visaPort.provisionToken(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new NetworkTokenProvisionResult("vts_ref_001", "7890", "12/2028"));
        when(repository.saveNetworkToken(any(NetworkToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        NetworkTokenResult result = service.provision("tok_123", TENANT, NetworkType.VISA_VTS);

        // Assert
        verify(visaPort).provisionToken(eq("1111"), eq("41111111"), eq("VISA"), eq(12), eq(2028), eq("John Doe"));
        verify(repository).saveNetworkToken(any(NetworkToken.class));
        verify(eventPublisher).publishEvent(eq("NetworkToken"), anyString(), eq("NetworkTokenProvisioned"),
                any(Map.class), eq(TENANT));

        assertThat(result.tokenLast4()).isEqualTo("7890");
        assertThat(result.network()).isEqualTo(NetworkType.VISA_VTS);
        assertThat(result.status()).isEqualTo(TokenState.PROVISIONED);
    }

    @Test
    void provision_mastercardCard_callsMdesPort() {
        // Arrange
        VaultToken token = createVaultToken("tok_mc", "vc_mc");
        VaultedCard card = createVaultedCard("vc_mc", CardBrand.MASTERCARD);
        card.setPanLast4("0004");
        card.setPanBin("55000000");

        when(repository.findTokenById("tok_mc", TENANT)).thenReturn(Optional.of(token));
        when(repository.findCardById("vc_mc")).thenReturn(Optional.of(card));
        when(mastercardPort.provisionToken(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new NetworkTokenProvisionResult("mdes_ref_001", "5678", "06/2029"));
        when(repository.saveNetworkToken(any(NetworkToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        NetworkTokenResult result = service.provision("tok_mc", TENANT, NetworkType.MC_MDES);

        // Assert
        verify(mastercardPort).provisionToken(eq("0004"), eq("55000000"), eq("MASTERCARD"),
                eq(12), eq(2028), eq("John Doe"));
        assertThat(result.network()).isEqualTo(NetworkType.MC_MDES);
    }

    @Test
    void generateCryptogram_success() {
        // Arrange
        NetworkToken networkToken = new NetworkToken();
        networkToken.setId("nt_001");
        networkToken.setNetwork(NetworkType.VISA_VTS);
        networkToken.setTokenReference("vts_ref_001");

        CryptogramResult expectedResult = new CryptogramResult("AABBCCDD", "05", Instant.now().plusSeconds(300));

        // SEC-BATCH-1: network token is loaded tenant-scoped before any cryptogram is produced.
        when(repository.findNetworkTokenById("nt_001", TENANT)).thenReturn(Optional.of(networkToken));
        when(visaPort.generateCryptogram("vts_ref_001", 5000L, "USD")).thenReturn(expectedResult);

        // Act
        var request = new io.nexuspay.vault.domain.CryptogramRequest("tok_123", "nt_001", 5000L, "USD", "merch_001");
        CryptogramResult result = service.generate(request, TENANT);

        // Assert
        assertThat(result.cryptogram()).isEqualTo("AABBCCDD");
        assertThat(result.eci()).isEqualTo("05");
    }

    @Test
    void provision_notFoundToken_throwsException() {
        when(repository.findTokenById("tok_missing", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provision("tok_missing", TENANT, NetworkType.VISA_VTS))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void provision_crossTenantToken_throwsNotFound() {
        // SEC-BATCH-1: vault token owned by tenant-2 → empty for tenant-1 → 404, no provisioning,
        // no network port invoked.
        when(repository.findTokenById("tok_foreign", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provision("tok_foreign", TENANT, NetworkType.VISA_VTS))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(visaPort, never()).provisionToken(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString());
        verify(repository, never()).saveNetworkToken(any());
    }

    @Test
    void generate_crossTenantNetworkToken_throwsNotFound() {
        // SEC-BATCH-1: network token owned by tenant-2 → empty for tenant-1 → 404. Crucially, NO
        // payment-grade cryptogram is generated for a foreign card.
        when(repository.findNetworkTokenById("nt_foreign", TENANT)).thenReturn(Optional.empty());

        var request = new io.nexuspay.vault.domain.CryptogramRequest("tok_x", "nt_foreign", 5000L, "USD", "merch_001");
        assertThatThrownBy(() -> service.generate(request, TENANT))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(visaPort, never()).generateCryptogram(anyString(), anyLong(), anyString());
        verify(mastercardPort, never()).generateCryptogram(anyString(), anyLong(), anyString());
        verify(amexPort, never()).generateCryptogram(anyString(), anyLong(), anyString());
    }
}
