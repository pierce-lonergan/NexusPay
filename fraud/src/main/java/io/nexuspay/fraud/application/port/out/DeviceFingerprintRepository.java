package io.nexuspay.fraud.application.port.out;

import io.nexuspay.fraud.domain.model.DeviceFingerprint;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for device fingerprint persistence.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface DeviceFingerprintRepository {

    DeviceFingerprint save(DeviceFingerprint fingerprint);

    Optional<DeviceFingerprint> findById(UUID id);

    Optional<DeviceFingerprint> findByTenantIdAndFingerprintHash(String tenantId, String fingerprintHash);
}
