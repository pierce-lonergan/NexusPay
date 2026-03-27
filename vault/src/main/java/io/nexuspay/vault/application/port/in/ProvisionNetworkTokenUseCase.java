package io.nexuspay.vault.application.port.in;

import io.nexuspay.vault.domain.NetworkType;
import io.nexuspay.vault.domain.TokenState;

/**
 * Use case for provisioning network tokens (Visa VTS, MC MDES, Amex).
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface ProvisionNetworkTokenUseCase {

    NetworkTokenResult provision(String vaultTokenId, String tenantId, NetworkType network);

    record NetworkTokenResult(
            String networkTokenId,
            String tokenLast4,
            TokenState status,
            NetworkType network
    ) {}
}
