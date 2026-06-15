package io.nexuspay.vault.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.domain.MigrationStatus;
import io.nexuspay.vault.domain.VaultMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultMigrationServiceTest {

    @Mock
    private VaultRepository repository;

    @Mock
    private VaultEventPublisher eventPublisher;

    @InjectMocks
    private VaultMigrationService service;

    private static final String TENANT = "tenant-1";

    @Test
    void startMigration_createsRecord() {
        // Arrange
        when(repository.saveMigration(any(VaultMigration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        VaultMigration migration = service.startMigration(TENANT, "spreedly", 5000);

        // Assert
        ArgumentCaptor<VaultMigration> migrationCaptor = ArgumentCaptor.forClass(VaultMigration.class);
        verify(repository).saveMigration(migrationCaptor.capture());
        VaultMigration saved = migrationCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MigrationStatus.PENDING);
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getSourceProvider()).isEqualTo("spreedly");
        assertThat(saved.getTotalCards()).isEqualTo(5000);

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher).publishEvent(eq("VaultMigration"), anyString(), eventTypeCaptor.capture(),
                any(Map.class), eq(TENANT));
        assertThat(eventTypeCaptor.getValue()).isEqualTo("MigrationStarted");
    }

    @Test
    void getMigrationStatus_found() {
        // Arrange
        VaultMigration migration = VaultMigration.create(TENANT, "stripe", 1000);
        // SEC-BATCH-1: migration loaded tenant-scoped.
        when(repository.findMigrationById(migration.getId(), TENANT)).thenReturn(Optional.of(migration));

        // Act
        VaultMigration result = service.getMigrationStatus(migration.getId(), TENANT);

        // Assert
        assertThat(result).isSameAs(migration);
        assertThat(result.getSourceProvider()).isEqualTo("stripe");
    }

    @Test
    void getMigrationStatus_notFound_throwsException() {
        when(repository.findMigrationById("vm_nonexistent", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMigrationStatus("vm_nonexistent", TENANT))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMigrationStatus_crossTenant_throwsNotFound() {
        // SEC-20: migration owned by tenant-2 → tenant-scoped finder empty for tenant-1 → 404.
        when(repository.findMigrationById("vm_foreign", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMigrationStatus("vm_foreign", TENANT))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
