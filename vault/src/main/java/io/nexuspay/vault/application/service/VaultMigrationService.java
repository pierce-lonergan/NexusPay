package io.nexuspay.vault.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.vault.application.port.in.MigrateVaultUseCase;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.domain.VaultMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for vault-to-vault migration from external providers.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Service
public class VaultMigrationService implements MigrateVaultUseCase {

    private static final Logger log = LoggerFactory.getLogger(VaultMigrationService.class);

    private final VaultRepository repository;
    private final VaultEventPublisher eventPublisher;

    public VaultMigrationService(VaultRepository repository, VaultEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public VaultMigration startMigration(String tenantId, String sourceProvider, int totalCards) {
        VaultMigration migration = VaultMigration.create(tenantId, sourceProvider, totalCards);
        migration = repository.saveMigration(migration);

        eventPublisher.publishEvent("VaultMigration", migration.getId(), "MigrationStarted",
                Map.of("sourceProvider", sourceProvider, "totalCards", totalCards,
                        "tenantId", tenantId),
                tenantId);

        log.info("Vault migration started: id={}, source={}, totalCards={}, tenant={}",
                migration.getId(), sourceProvider, totalCards, tenantId);

        return migration;
    }

    @Override
    @Transactional(readOnly = true)
    public VaultMigration getMigrationStatus(String migrationId, String tenantId) {
        // SEC-BATCH-1 (SEC-20): tenant-scoped by-id read — 404 on absent OR wrong-tenant.
        return TenantOwnership.require(
                repository.findMigrationById(migrationId, tenantId), "Migration");
    }
}
