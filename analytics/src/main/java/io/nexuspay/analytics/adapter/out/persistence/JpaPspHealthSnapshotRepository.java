package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaPspHealthSnapshotRepository extends JpaRepository<PspHealthSnapshotEntity, UUID> {

    @Query("SELECT e FROM PspHealthSnapshotEntity e WHERE e.tenantId = :tenantId " +
            "AND e.pspConnector = :pspConnector ORDER BY e.snapshotTime DESC LIMIT 1")
    Optional<PspHealthSnapshotEntity> findLatest(String tenantId, String pspConnector);

    @Query("SELECT e FROM PspHealthSnapshotEntity e WHERE e.tenantId = :tenantId " +
            "AND e.snapshotTime = (SELECT MAX(e2.snapshotTime) FROM PspHealthSnapshotEntity e2 " +
            "WHERE e2.tenantId = :tenantId AND e2.pspConnector = e.pspConnector)")
    List<PspHealthSnapshotEntity> findAllLatest(String tenantId);

    List<PspHealthSnapshotEntity> findByTenantIdAndPspConnectorAndSnapshotTimeBetween(
            String tenantId, String pspConnector, Instant from, Instant to);
}
