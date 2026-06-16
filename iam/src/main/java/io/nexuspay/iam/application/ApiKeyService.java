package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.ApiKey;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Manages API key lifecycle: creation, authentication, revocation.
 * Keys follow Stripe convention: sk_test_{random} / sk_live_{random}.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final int KEY_RANDOM_BYTES = 24;
    private static final int PREFIX_DISPLAY_LENGTH = 12;

    private final JpaApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(JpaApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Creates a new API key. Returns the full key (shown once) and the persisted entity.
     */
    @Transactional
    public CreateApiKeyResult createApiKey(String name, String role, String tenantId, boolean live) {
        String prefix = live ? "sk_live_" : "sk_test_";
        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String fullKey = prefix + randomPart;

        String keyHash = passwordEncoder.encode(fullKey);
        String keyPrefix = fullKey.substring(0, Math.min(fullKey.length(), PREFIX_DISPLAY_LENGTH));
        String id = PrefixedId.apiKey();

        var entity = new ApiKeyEntity(id, keyHash, keyPrefix, name, role, tenantId, live, Instant.now(), null);
        apiKeyRepository.save(entity);

        log.info("Created API key: id={}, prefix={}, role={}, tenant={}", id, keyPrefix, role, tenantId);
        return new CreateApiKeyResult(id, fullKey, keyPrefix, name, role, tenantId, live);
    }

    /**
     * Authenticates a raw API key and returns the principal.
     */
    public NexusPayPrincipal authenticate(String rawKey) {
        if (rawKey == null || (!rawKey.startsWith("sk_test_") && !rawKey.startsWith("sk_live_"))) {
            return null; // Not an API key — let JWT filter handle it
        }

        String prefix = rawKey.substring(0, Math.min(rawKey.length(), PREFIX_DISPLAY_LENGTH));
        List<ApiKeyEntity> candidates = apiKeyRepository.findByKeyPrefixAndRevokedAtIsNull(prefix);

        // SEC-22: a 12-char key_prefix may be shared by >1 un-revoked key. Iterate the (typically
        // size-1) candidate list and bcrypt-match each — never throw on multiple candidates. At most
        // one hash can match a given raw key, so first-match is unambiguous. The single terminal
        // throw below is reached identically for 0 candidates, N candidates none matching, or a
        // revoked-only prefix (revoked rows are excluded by the query) — uniform failure, no oracle.
        for (ApiKeyEntity candidate : candidates) {
            if (passwordEncoder.matches(rawKey, candidate.getKeyHash())) {
                // INT-3: the mode is SERVER-DERIVED from the matched entity's is_live column — never
                // inferred from the raw key string. A sk_test_ key (is_live=false) yields a TEST
                // principal whose payment ops route to the mock gateway; a sk_live_ key yields a LIVE
                // principal that reaches HyperSwitch.
                return new NexusPayPrincipal(
                        candidate.getId(),
                        candidate.getTenantId(),
                        candidate.getRole(),
                        NexusPayPrincipal.AuthMethod.API_KEY,
                        null,
                        candidate.isLive()
                );
            }
        }
        throw AuthorizationException.invalidApiKey();
    }

    @Transactional
    public void revokeApiKey(String keyId) {
        var entity = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> AuthorizationException.invalidApiKey());
        entity.setRevokedAt(Instant.now());
        apiKeyRepository.save(entity);
        log.info("Revoked API key: id={}", keyId);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listActiveKeys(String tenantId) {
        return apiKeyRepository.findAllByTenantIdAndRevokedAtIsNull(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    private ApiKey toDomain(ApiKeyEntity entity) {
        return new ApiKey(entity.getId(), entity.getKeyHash(), entity.getKeyPrefix(),
                entity.getName(), entity.getRole(), entity.getTenantId(), entity.isLive(),
                entity.getCreatedAt(), entity.getRevokedAt());
    }

    public record CreateApiKeyResult(
            String id, String fullKey, String keyPrefix, String name,
            String role, String tenantId, boolean live
    ) {}
}
