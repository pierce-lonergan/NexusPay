package io.nexuspay.vault.adapter.in.rest.dto;

/**
 * Response DTO for vault migration status.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record MigrationResponse(
        String id,
        String status,
        String sourceProvider,
        int totalCards,
        int migratedCount,
        int failedCount
) {}
