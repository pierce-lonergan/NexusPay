package io.nexuspay.vault.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.vault.adapter.in.rest.dto.*;
import io.nexuspay.vault.application.port.in.*;
import io.nexuspay.vault.domain.CryptogramRequest;
import io.nexuspay.vault.domain.CryptogramResult;
import io.nexuspay.vault.domain.NetworkType;
import io.nexuspay.vault.domain.VaultMigration;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the card vault API.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@RestController
@RequestMapping("/v1/vault")
public class VaultController {

    private final VaultCardUseCase vaultCardUseCase;
    private final ProvisionNetworkTokenUseCase provisionUseCase;
    private final GenerateCryptogramUseCase cryptogramUseCase;
    private final MigrateVaultUseCase migrateUseCase;

    public VaultController(VaultCardUseCase vaultCardUseCase,
                           ProvisionNetworkTokenUseCase provisionUseCase,
                           GenerateCryptogramUseCase cryptogramUseCase,
                           MigrateVaultUseCase migrateUseCase) {
        this.vaultCardUseCase = vaultCardUseCase;
        this.provisionUseCase = provisionUseCase;
        this.cryptogramUseCase = cryptogramUseCase;
        this.migrateUseCase = migrateUseCase;
    }

    @PostMapping("/cards")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<VaultCardResponse> vaultCard(
            @Valid @RequestBody VaultCardRequest request) {

        var result = vaultCardUseCase.vaultCard(new VaultCardUseCase.VaultCardCommand(
                CallerTenant.require(), request.pan(), request.expMonth(), request.expYear(),
                request.cardholderName()));

        return ResponseEntity.status(HttpStatus.CREATED).body(new VaultCardResponse(
                result.vaultTokenId(), result.panLast4(),
                result.brand().name(), result.fingerprint()));
    }

    @GetMapping("/cards/{token}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<VaultedCardInfoResponse> getCard(
            @PathVariable String token) {

        var info = vaultCardUseCase.getCard(token, CallerTenant.require());

        return ResponseEntity.ok(new VaultedCardInfoResponse(
                info.vaultTokenId(), info.panLast4(), info.panBin(),
                info.brand().name(), info.expMonth(), info.expYear(),
                info.cardholderName(), info.createdAt()));
    }

    @DeleteMapping("/cards/{token}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> deleteCard(
            @PathVariable String token) {

        vaultCardUseCase.deleteCard(token, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cards/{token}/network-tokens")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<NetworkTokenResponse> provisionNetworkToken(
            @PathVariable String token,
            @Valid @RequestBody NetworkTokenRequest request) {

        NetworkType network = NetworkType.valueOf(request.network());
        var result = provisionUseCase.provision(token, CallerTenant.require(), network);

        return ResponseEntity.status(HttpStatus.CREATED).body(new NetworkTokenResponse(
                result.networkTokenId(), result.tokenLast4(),
                result.status().name(), result.network().name()));
    }

    @PostMapping("/cards/{token}/cryptogram")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<CryptogramResponse> generateCryptogram(
            @PathVariable String token,
            @Valid @RequestBody CryptogramRequestDto request) {

        CryptogramResult result = cryptogramUseCase.generate(
                new CryptogramRequest(token, request.networkTokenId(),
                        request.amount(), request.currency(), request.merchantId()),
                CallerTenant.require());

        return ResponseEntity.ok(new CryptogramResponse(
                result.cryptogram(), result.eci(), result.expiresAt()));
    }

    @PostMapping("/migrations")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<MigrationResponse> startMigration(
            @Valid @RequestBody MigrationRequest request) {

        VaultMigration migration = migrateUseCase.startMigration(
                CallerTenant.require(), request.sourceProvider(), request.totalCards());

        return ResponseEntity.status(HttpStatus.CREATED).body(new MigrationResponse(
                migration.getId(), migration.getStatus().name(),
                migration.getSourceProvider(), migration.getTotalCards(),
                migration.getMigratedCount(), migration.getFailedCount()));
    }

    @GetMapping("/migrations/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<MigrationResponse> getMigrationStatus(
            @PathVariable String id) {

        VaultMigration migration = migrateUseCase.getMigrationStatus(id, CallerTenant.require());

        return ResponseEntity.ok(new MigrationResponse(
                migration.getId(), migration.getStatus().name(),
                migration.getSourceProvider(), migration.getTotalCards(),
                migration.getMigratedCount(), migration.getFailedCount()));
    }
}
