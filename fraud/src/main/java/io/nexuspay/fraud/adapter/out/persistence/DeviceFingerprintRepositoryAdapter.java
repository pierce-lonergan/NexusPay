package io.nexuspay.fraud.adapter.out.persistence;

import io.nexuspay.fraud.application.port.out.DeviceFingerprintRepository;
import io.nexuspay.fraud.domain.model.DeviceFingerprint;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter mapping between DeviceFingerprint domain and DeviceFingerprintEntity JPA entity.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
public class DeviceFingerprintRepositoryAdapter implements DeviceFingerprintRepository {

    private final JpaDeviceFingerprintRepository jpaRepo;

    public DeviceFingerprintRepositoryAdapter(JpaDeviceFingerprintRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public DeviceFingerprint save(DeviceFingerprint fp) {
        DeviceFingerprintEntity entity = toEntity(fp);
        DeviceFingerprintEntity saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<DeviceFingerprint> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<DeviceFingerprint> findByTenantIdAndFingerprintHash(String tenantId, String hash) {
        return jpaRepo.findByTenantIdAndFingerprintHash(tenantId, hash).map(this::toDomain);
    }

    private DeviceFingerprintEntity toEntity(DeviceFingerprint fp) {
        DeviceFingerprintEntity e = new DeviceFingerprintEntity();
        e.setId(fp.getId());
        e.setTenantId(fp.getTenantId());
        e.setFingerprintHash(fp.getFingerprintHash());
        e.setCustomerId(fp.getCustomerId());
        e.setBrowserFamily(fp.getBrowserFamily());
        e.setOsFamily(fp.getOsFamily());
        e.setDeviceType(fp.getDeviceType());
        e.setScreenResolution(fp.getScreenResolution());
        e.setTimezoneOffset(fp.getTimezoneOffset());
        e.setLanguage(fp.getLanguage());
        e.setIpAddress(fp.getIpAddress());
        e.setIpCountry(fp.getIpCountry());
        e.setIpCity(fp.getIpCity());
        e.setReputationScore(fp.getReputationScore());
        e.setFirstSeenAt(fp.getFirstSeenAt());
        e.setLastSeenAt(fp.getLastSeenAt());
        e.setTimesSeen(fp.getTimesSeen());
        e.setFlagged(fp.isFlagged());
        return e;
    }

    private DeviceFingerprint toDomain(DeviceFingerprintEntity e) {
        DeviceFingerprint fp = new DeviceFingerprint();
        fp.setId(e.getId());
        fp.setTenantId(e.getTenantId());
        fp.setFingerprintHash(e.getFingerprintHash());
        fp.setCustomerId(e.getCustomerId());
        fp.setBrowserFamily(e.getBrowserFamily());
        fp.setOsFamily(e.getOsFamily());
        fp.setDeviceType(e.getDeviceType());
        fp.setScreenResolution(e.getScreenResolution());
        fp.setTimezoneOffset(e.getTimezoneOffset());
        fp.setLanguage(e.getLanguage());
        fp.setIpAddress(e.getIpAddress());
        fp.setIpCountry(e.getIpCountry());
        fp.setIpCity(e.getIpCity());
        fp.setReputationScore(e.getReputationScore());
        fp.setFirstSeenAt(e.getFirstSeenAt());
        fp.setLastSeenAt(e.getLastSeenAt());
        fp.setTimesSeen(e.getTimesSeen());
        fp.setFlagged(e.isFlagged());
        return fp;
    }
}
