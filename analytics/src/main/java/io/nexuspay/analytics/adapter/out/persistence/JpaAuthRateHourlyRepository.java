package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JpaAuthRateHourlyRepository extends JpaRepository<AuthRateHourlyEntity, UUID> {

    List<AuthRateHourlyEntity> findByTenantIdAndBucketHourBetween(
            String tenantId, Instant from, Instant to);

    List<AuthRateHourlyEntity> findByTenantIdAndBucketHourBetweenAndPspConnector(
            String tenantId, Instant from, Instant to, String pspConnector);
}
