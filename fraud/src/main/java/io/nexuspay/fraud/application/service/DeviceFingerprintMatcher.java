package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.out.DeviceFingerprintRepository;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.DeviceFingerprint;
import io.nexuspay.fraud.domain.model.RiskSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Matches and manages device fingerprints during fraud assessment.
 *
 * <p>New devices get a neutral reputation (50). Known devices have their
 * reputation tracked over time. Low-reputation devices contribute risk signals.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Service
public class DeviceFingerprintMatcher {

    private static final Logger log = LoggerFactory.getLogger(DeviceFingerprintMatcher.class);
    private static final int LOW_REPUTATION_THRESHOLD = 30;
    private static final int NEW_DEVICE_SCORE_PENALTY = 15;

    private final DeviceFingerprintRepository repository;
    private final FraudProperties fraudProperties;

    public DeviceFingerprintMatcher(DeviceFingerprintRepository repository,
                                     FraudProperties fraudProperties) {
        this.repository = repository;
        this.fraudProperties = fraudProperties;
    }

    /**
     * Matches or creates a device fingerprint and returns a risk signal if applicable.
     *
     * @param context payment context with device information
     * @return risk signal if the device is new or has low reputation, null otherwise
     */
    public RiskSignal matchAndAssess(PaymentContext context) {
        if (!fraudProperties.getDeviceFingerprint().isEnabled()) return null;
        if (context.deviceFingerprintHash() == null) {
            return new RiskSignal("device_fingerprint", "no_fingerprint",
                    NEW_DEVICE_SCORE_PENALTY, "No device fingerprint provided");
        }

        Optional<DeviceFingerprint> existing = repository
                .findByTenantIdAndFingerprintHash(context.tenantId(), context.deviceFingerprintHash());

        if (existing.isPresent()) {
            DeviceFingerprint fp = existing.get();
            fp.recordSighting();
            repository.save(fp);

            if (fp.isFlagged()) {
                return new RiskSignal("device_fingerprint", "flagged_device",
                        40, "Device has been flagged for fraud");
            }

            if (fp.getReputationScore() < LOW_REPUTATION_THRESHOLD) {
                return new RiskSignal("device_fingerprint", "low_reputation_device",
                        25, String.format("Device reputation %d below threshold %d",
                        fp.getReputationScore(), LOW_REPUTATION_THRESHOLD));
            }

            // Known device with good reputation — no risk signal
            return null;
        }

        // New device — create and return mild risk signal
        DeviceFingerprint newFp = new DeviceFingerprint();
        newFp.setTenantId(context.tenantId());
        newFp.setFingerprintHash(context.deviceFingerprintHash());
        newFp.setCustomerId(context.customerId());
        newFp.setIpAddress(context.ipAddress());
        newFp.setIpCountry(context.ipCountry());

        if (context.deviceInfo() != null) {
            Map<String, String> di = context.deviceInfo();
            newFp.setBrowserFamily(di.get("browser"));
            newFp.setOsFamily(di.get("os"));
            newFp.setDeviceType(di.get("device_type"));
            newFp.setScreenResolution(di.get("screen_resolution"));
            newFp.setLanguage(di.get("language"));
            String tz = di.get("timezone_offset");
            if (tz != null) {
                try { newFp.setTimezoneOffset(Integer.parseInt(tz)); } catch (NumberFormatException ignored) {}
            }
        }

        repository.save(newFp);
        log.debug("New device fingerprint recorded for tenant={}, hash={}",
                context.tenantId(), context.deviceFingerprintHash());

        return new RiskSignal("device_fingerprint", "new_device",
                NEW_DEVICE_SCORE_PENALTY, "First-time device — no history");
    }
}
