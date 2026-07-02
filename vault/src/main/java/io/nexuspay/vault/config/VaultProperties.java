package io.nexuspay.vault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Vault module configuration properties bound to {@code nexuspay.vault.*}.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.vault")
public class VaultProperties {

    private Encryption encryption = new Encryption();
    private NetworkTokens networkTokens = new NetworkTokens();
    private KeyRotation keyRotation = new KeyRotation();

    public Encryption getEncryption() { return encryption; }
    public void setEncryption(Encryption encryption) { this.encryption = encryption; }

    public NetworkTokens getNetworkTokens() { return networkTokens; }
    public void setNetworkTokens(NetworkTokens networkTokens) { this.networkTokens = networkTokens; }

    public KeyRotation getKeyRotation() { return keyRotation; }
    public void setKeyRotation(KeyRotation keyRotation) { this.keyRotation = keyRotation; }

    public static class Encryption {
        private String provider = "software";
        private String masterKey = "dev-vault-key-base64-min-32-chars!!";
        private String currentKeyId = "key-001";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getMasterKey() { return masterKey; }
        public void setMasterKey(String masterKey) { this.masterKey = masterKey; }

        public String getCurrentKeyId() { return currentKeyId; }
        public void setCurrentKeyId(String currentKeyId) { this.currentKeyId = currentKeyId; }
    }

    /**
     * GAP-059: encryption key-rotation background job config, bound to
     * {@code nexuspay.vault.key-rotation.*}. OFF by default — the job bean is not even created
     * unless {@code enabled=true} (structural {@code @ConditionalOnProperty} gate), and even then
     * no-ops unless {@code retiredKeyId} is set. The ACTIVE key is NEVER configured here; it is
     * resolved at runtime via {@code EncryptionPort.currentKeyId()} so there is exactly one source
     * of truth for the current key and no key material is hardcoded.
     */
    public static class KeyRotation {
        private boolean enabled = false;
        private String retiredKeyId;
        private int batchSize = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getRetiredKeyId() { return retiredKeyId; }
        public void setRetiredKeyId(String retiredKeyId) { this.retiredKeyId = retiredKeyId; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class NetworkTokens {
        private boolean visaEnabled = true;
        private boolean mastercardEnabled = true;
        private boolean amexEnabled = true;

        public boolean isVisaEnabled() { return visaEnabled; }
        public void setVisaEnabled(boolean visaEnabled) { this.visaEnabled = visaEnabled; }

        public boolean isMastercardEnabled() { return mastercardEnabled; }
        public void setMastercardEnabled(boolean mastercardEnabled) { this.mastercardEnabled = mastercardEnabled; }

        public boolean isAmexEnabled() { return amexEnabled; }
        public void setAmexEnabled(boolean amexEnabled) { this.amexEnabled = amexEnabled; }
    }
}
