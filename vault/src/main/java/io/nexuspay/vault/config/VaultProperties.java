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

    public Encryption getEncryption() { return encryption; }
    public void setEncryption(Encryption encryption) { this.encryption = encryption; }

    public NetworkTokens getNetworkTokens() { return networkTokens; }
    public void setNetworkTokens(NetworkTokens networkTokens) { this.networkTokens = networkTokens; }

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
