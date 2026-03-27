package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JpaRevenueHourlyRepository extends JpaRepository<RevenueHourlyEntity, UUID> {

    List<RevenueHourlyEntity> findByTenantIdAndBucketHourBetween(
            String tenantId, Instant from, Instant to);
}
