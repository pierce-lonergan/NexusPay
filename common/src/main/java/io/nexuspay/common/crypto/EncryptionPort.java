package io.nexuspay.common.crypto;

/**
 * Module-portable encryption contract. Implementations may use software
 * encryption (AES-256-GCM for dev/test) or HSM (CloudHSM/Thales Luna
 * for production).
 *
 * <p>Lives in {@code common} — like {@link io.nexuspay.common.tenant.TenantPrincipal}
 * and {@link io.nexuspay.common.rls.TenantWorkRunner} — so that any module on the
 * {@code :common} compile classpath can require an encryption capability WITHOUT a
 * cross-module dependency edge to {@code :vault} (L-048 common-routing precedent).
 * The {@code :common} module is declared {@code Type.OPEN}, so consuming this type
 * from another module is Modulith-clean with no internal-access flag.</p>
 *
 * <p>The concrete bean ({@code AesGcmEncryptionAdapter} in {@code :vault}) is
 * discovered at the composition root ({@code :app}, which depends on both
 * {@code :vault} and {@code :gateway-api}), so a single adapter instance satisfies
 * both the vault {@code CardVaultService} and the gateway {@code TokenizationService}
 * injection points without any new {@code gateway-api → vault} edge.</p>
 *
 * @since 0.4.0 (Sprint 4.1); lifted to {@code common} in SEC-BATCH-3 (B-004/SEC-04)
 */
public interface EncryptionPort {

    EncryptionResult encrypt(byte[] plaintext, String keyId);

    byte[] decrypt(byte[] ciphertext, String keyId);

    String currentKeyId();

    String generateFingerprint(String pan);

    record EncryptionResult(byte[] ciphertext, String keyId) {}
}
