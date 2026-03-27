package io.nexuspay.vault.application.port.in;

import io.nexuspay.vault.domain.CryptogramRequest;
import io.nexuspay.vault.domain.CryptogramResult;

/**
 * Use case for generating TAVV/CAVV cryptograms for tokenized e-commerce transactions.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface GenerateCryptogramUseCase {

    CryptogramResult generate(CryptogramRequest request, String tenantId);
}
