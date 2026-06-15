package io.nexuspay.vault.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.vault.application.port.in.GenerateCryptogramUseCase;
import io.nexuspay.vault.application.port.in.ProvisionNetworkTokenUseCase;
import io.nexuspay.vault.application.port.out.*;
import io.nexuspay.vault.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for provisioning network tokens and generating cryptograms.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Service
public class NetworkTokenService implements ProvisionNetworkTokenUseCase, GenerateCryptogramUseCase {

    private static final Logger log = LoggerFactory.getLogger(NetworkTokenService.class);

    private final VaultRepository repository;
    private final VisaTokenServicePort visaPort;
    private final MastercardMdesPort mastercardPort;
    private final AmexTokenServicePort amexPort;
    private final VaultEventPublisher eventPublisher;

    public NetworkTokenService(VaultRepository repository,
                               VisaTokenServicePort visaPort,
                               MastercardMdesPort mastercardPort,
                               AmexTokenServicePort amexPort,
                               VaultEventPublisher eventPublisher) {
        this.repository = repository;
        this.visaPort = visaPort;
        this.mastercardPort = mastercardPort;
        this.amexPort = amexPort;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public NetworkTokenResult provision(String vaultTokenId, String tenantId, NetworkType network) {
        // SEC-BATCH-1: scope+assert the vault token to the caller's tenant BEFORE provisioning — ownership
        // is derived from the principal, never trusted from the request. 404 on absent OR wrong-tenant.
        VaultToken token = TenantOwnership.require(
                repository.findTokenById(vaultTokenId, tenantId), "Vault token");

        VaultedCard card = repository.findCardById(token.getVaultedCardId())
                .orElseThrow(() -> new IllegalStateException("Vaulted card not found for token: " + vaultTokenId));

        // Route to the correct network port
        VisaTokenServicePort.NetworkTokenProvisionResult result = provisionFromNetwork(
                network, card.getPanLast4(), card.getPanBin(),
                card.getBrand().name(), card.getExpMonth(), card.getExpYear(),
                card.getCardholderName()
        );

        // Create and persist the network token
        NetworkToken networkToken = NetworkToken.create(
                card.getId(), tenantId, network,
                result.tokenReference(), result.tokenLast4(), result.tokenExpiry()
        );
        networkToken = repository.saveNetworkToken(networkToken);

        // Publish event
        eventPublisher.publishEvent("NetworkToken", networkToken.getId(), "NetworkTokenProvisioned",
                Map.of("vaultTokenId", vaultTokenId, "network", network.name(),
                        "tokenLast4", result.tokenLast4(), "tenantId", tenantId),
                tenantId);

        log.info("Network token provisioned: id={}, network={}, tenant={}",
                networkToken.getId(), network, tenantId);

        return new NetworkTokenResult(
                networkToken.getId(), result.tokenLast4(),
                networkToken.getStatus(), network
        );
    }

    @Override
    @Transactional(readOnly = true)
    public CryptogramResult generate(CryptogramRequest request, String tenantId) {
        // SEC-BATCH-1: scope+assert the network token to the caller's tenant BEFORE invoking the
        // visa/mc/amex port — no payment-grade cryptogram is produced for a token the caller doesn't own.
        NetworkToken networkToken = TenantOwnership.require(
                repository.findNetworkTokenById(request.networkTokenId(), tenantId), "Network token");

        CryptogramResult result;
        switch (networkToken.getNetwork()) {
            case VISA_VTS -> result = visaPort.generateCryptogram(
                    networkToken.getTokenReference(), request.amount(), request.currency());
            case MC_MDES -> result = mastercardPort.generateCryptogram(
                    networkToken.getTokenReference(), request.amount(), request.currency());
            case AMEX -> result = amexPort.generateCryptogram(
                    networkToken.getTokenReference(), request.amount(), request.currency());
            default -> throw new IllegalStateException("Unsupported network: " + networkToken.getNetwork());
        }

        log.info("Cryptogram generated for network token: id={}, network={}",
                networkToken.getId(), networkToken.getNetwork());

        return result;
    }

    private VisaTokenServicePort.NetworkTokenProvisionResult provisionFromNetwork(
            NetworkType network, String panLast4, String panBin, String brand,
            int expMonth, int expYear, String cardholderName) {
        return switch (network) {
            case VISA_VTS -> visaPort.provisionToken(panLast4, panBin, brand, expMonth, expYear, cardholderName);
            case MC_MDES -> mastercardPort.provisionToken(panLast4, panBin, brand, expMonth, expYear, cardholderName);
            case AMEX -> amexPort.provisionToken(panLast4, panBin, brand, expMonth, expYear, cardholderName);
        };
    }
}
