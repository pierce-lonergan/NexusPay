package io.nexuspay.fraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for device fingerprints.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface JpaDeviceFingerprintRepository extends JpaRepository<DeviceFingerprintEntity, UUID> {

    Optional<DeviceFingerprintEntity> findByTenantIdAndFingerprintHash(String tenantId, String fingerprintHash);
}
