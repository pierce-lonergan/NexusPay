package io.nexuspay.iam.application;

import io.nexuspay.common.api.ApiScope;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ConflictException;
import io.nexuspay.common.exception.InvalidRequestException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages API key lifecycle: creation, authentication, expiry, rotation, revocation.
 * Keys follow Stripe convention: sk_test_{random} / sk_live_{random}.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final int KEY_RANDOM_BYTES = 24;
    private static final int PREFIX_DISPLAY_LENGTH = 12;
    // DX-5c: throttle window for the best-effort last_used_at stamp. We only re-touch a key whose
    // last_used_at is null or older than this — keeping authenticate() off a write on every request.
    private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

    private final JpaApiKeyRepository apiKeyRepository;
    private final ApiKeyUsageTracker usageTracker;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(JpaApiKeyRepository apiKeyRepository, ApiKeyUsageTracker usageTracker) {
        this.apiKeyRepository = apiKeyRepository;
        this.usageTracker = usageTracker;
    }

    /**
     * Back-compat 4-arg overload: creates a never-expiring, UNRESTRICTED API key
     * (expiresAt = null, scopes = null).
     */
    @Transactional
    public CreateApiKeyResult createApiKey(String name, String role, String tenantId, boolean live) {
        return createApiKey(name, role, tenantId, live, null, null);
    }

    /**
     * Back-compat 5-arg overload: creates an UNRESTRICTED key with an optional expiry (scopes = null).
     */
    @Transactional
    public CreateApiKeyResult createApiKey(String name, String role, String tenantId, boolean live,
                                           Instant expiresAt) {
        return createApiKey(name, role, tenantId, live, expiresAt, null);
    }

    /**
     * Creates a new API key with an OPTIONAL absolute expiry and OPTIONAL scopes. Returns the full key
     * (shown once).
     *
     * <p>DX-5c fail-closed guard: if {@code expiresAt} is non-null it MUST be in the future
     * (strictly after now); creating an already-expired key is rejected. A {@code null} {@code expiresAt}
     * means the key never expires.
     *
     * <p>DX-5c-ii: {@code scopes} is validated against the {@link ApiScope} vocabulary and persisted as a
     * canonical csv; an UNKNOWN scope is rejected fail-closed ({@link InvalidRequestException}, 400) so a
     * bad scope is never persisted. A {@code null}/empty set yields an UNRESTRICTED (role-based) key —
     * back-compat. Scopes NARROW the role, never widen it.
     */
    @Transactional
    public CreateApiKeyResult createApiKey(String name, String role, String tenantId, boolean live,
                                           Instant expiresAt, Set<String> scopes) {
        Instant now = Instant.now();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            // Reject minting a key whose expiry is at-or-before now — a key that is born expired.
            // Caller-caused → InvalidRequestException (400), NOT a raw IllegalArgumentException (which the
            // codebase reserves for internal invariants and maps to 500).
            throw new InvalidRequestException("API key expiresAt must be in the future");
        }
        // DX-5c-ii: validate + canonicalize scopes (fail-closed on unknown). null csv == unrestricted.
        String scopesCsv = ApiScope.toCanonicalCsv(scopes);

        GeneratedKey generated = generateKey(live);
        var entity = new ApiKeyEntity(generated.id(), generated.keyHash(), generated.keyPrefix(),
                name, role, tenantId, live, now, null, expiresAt, null, null, scopesCsv);
        apiKeyRepository.save(entity);

        log.info("Created API key: id={}, prefix={}, role={}, tenant={}, expiresAt={}, scopes={}",
                generated.id(), generated.keyPrefix(), role, tenantId, expiresAt, scopesCsv);
        return new CreateApiKeyResult(generated.id(), generated.fullKey(), generated.keyPrefix(),
                name, role, tenantId, live, expiresAt, parsePersistedScopes(scopesCsv));
    }

    /**
     * Authenticates a raw API key and returns the principal.
     */
    public NexusPayPrincipal authenticate(String rawKey) {
        if (rawKey == null || (!rawKey.startsWith("sk_test_") && !rawKey.startsWith("sk_live_"))) {
            return null; // Not an API key — let JWT filter handle it
        }

        Instant now = Instant.now();
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), PREFIX_DISPLAY_LENGTH));
        List<ApiKeyEntity> candidates = apiKeyRepository.findByKeyPrefixAndRevokedAtIsNull(prefix);

        // SEC-22: a 12-char key_prefix may be shared by >1 un-revoked key. Iterate the (typically
        // size-1) candidate list and bcrypt-match each — never throw on multiple candidates. At most
        // one hash can match a given raw key, so first-match is unambiguous. The single terminal
        // throw below is reached identically for 0 candidates, N candidates none matching, or a
        // revoked-only prefix (revoked rows are excluded by the query) — uniform failure, no oracle.
        for (ApiKeyEntity candidate : candidates) {
            if (passwordEncoder.matches(rawKey, candidate.getKeyHash())) {
                // Critique 3.2: FAIL-CLOSED prefix/is_live consistency check. createApiKey derives the
                // stored key_prefix and is_live from the same `live` argument, but authenticate has, until
                // now, trusted is_live ALONE for mode — leaving the prefix STRING unverified. Enforce the
                // invariant at the boundary: a "sk_live_" prefix REQUIRES is_live=true and "sk_test_"
                // REQUIRES is_live=false. A row with a mismatched prefix/is_live (manual DB edit, a future
                // code path, corruption) must NOT authenticate. On mismatch, log a WARNING (key id/prefix
                // only — NEVER the raw key) and fail closed: `continue` so this matched-but-inconsistent
                // candidate is treated identically to a non-match and falls through to the single terminal
                // invalidApiKey() throw below — same exception, no distinguishing error/oracle.
                if (!prefixAgreesWithLive(candidate.getKeyPrefix(), candidate.isLive())) {
                    log.warn("Rejecting API key with inconsistent prefix/is_live: id={}, prefix={}, is_live={}",
                            candidate.getId(), candidate.getKeyPrefix(), candidate.isLive());
                    continue;
                }
                // DX-5c: FAIL-CLOSED expiry check. A key with a non-null expires_at that is at-or-after
                // `now` is EXPIRED. Log a WARNING (id/prefix only — NEVER the raw key) and `continue` so
                // an expired key is treated IDENTICALLY to a non-match and falls through to the single
                // terminal invalidApiKey() throw below — same exception, no oracle (an expired key is
                // indistinguishable from an invalid one to the caller). A null expires_at never expires.
                if (candidate.getExpiresAt() != null && !now.isBefore(candidate.getExpiresAt())) {
                    log.warn("Rejecting expired API key: id={}, prefix={}, expiresAt={}",
                            candidate.getId(), candidate.getKeyPrefix(), candidate.getExpiresAt());
                    continue;
                }
                // DX-5c: best-effort, THROTTLED last_used_at stamp. Observability only, fail-OPEN — wrapped
                // in try/catch with the exception SWALLOWED (logged at debug); a touch failure must NEVER
                // deny a valid key. Throttled so we don't write on every request: only when last_used_at is
                // null or older than the throttle window. Delegated to a separate bean so the
                // @Transactional proxy applies (a self-invoked method would bypass it).
                touchIfStale(candidate, now);
                // DX-5c-ii: parse the matched key's persisted scopes csv into the principal's scope set.
                // A NULL/empty scopes column yields an UNRESTRICTED (role-based) principal — back-compat,
                // identical to every pre-DX-5c-ii key. A non-empty value RESTRICTS the principal to those
                // scopes (NARROWS the role, never widens). parseCsv is fail-closed on unknown tokens at
                // creation; here we tolerate an already-persisted value defensively (a manual DB edit or a
                // legacy row) by filtering to the KNOWN vocabulary rather than throwing on authenticate —
                // an authentication path must not 400. An empty result after filtering means unrestricted.
                Set<String> scopes = parsePersistedScopes(candidate.getScopes());
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
                        candidate.isLive(),
                        scopes
                );
            }
        }
        throw AuthorizationException.invalidApiKey();
    }

    /**
     * DX-5c: tenant-scoped revoke (IDOR fix). Looks the key up by id AND tenantId so an admin can only
     * revoke keys in their OWN tenant; an other-tenant key id resolves to the SAME uniform
     * {@code invalidApiKey()} as a missing id (no cross-tenant existence oracle).
     */
    @Transactional
    public void revokeApiKey(String keyId, String tenantId) {
        var entity = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(AuthorizationException::invalidApiKey);
        entity.setRevokedAt(Instant.now());
        apiKeyRepository.save(entity);
        log.info("Revoked API key: id={}, tenant={}", keyId, tenantId);
    }

    /**
     * DX-5c: rotate a key with an overlap window. Mints a NEW key (new id + secret, same role/tenant/
     * live/expiry-policy as the old) and SHORTENS the old key's life to an overlap deadline so existing
     * callers have time to swap. Tenant-scoped (IDOR-safe) like revoke.
     *
     * <ul>
     *   <li>The old key is found via {@code findByIdAndTenantId}; absent => {@code invalidApiKey()}
     *       (an other-tenant key id is indistinguishable from a missing one — no oracle).</li>
     *   <li>The old key must be USABLE now (not revoked, not already expired) and not already
     *       superseded ({@code replaced_by} unset) — else {@code IllegalStateException}.</li>
     *   <li>The new key inherits the old key's ORIGINAL {@code expiresAt} (or null).</li>
     *   <li>The old key's new {@code expires_at} NEVER EXTENDS its life: it becomes the EARLIER of its
     *       original expiry and {@code now+overlap}. A zero/negative overlap retires it immediately
     *       ({@code expires_at = now}).</li>
     * </ul>
     *
     * @return the NEW key's result (full key shown once).
     */
    @Transactional
    public CreateApiKeyResult rotateApiKey(String keyId, String tenantId, Duration overlap) {
        Instant now = Instant.now();
        ApiKeyEntity old = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(AuthorizationException::invalidApiKey);

        // The old key must still be usable (not revoked, not already expired) to be rotated. These are
        // caller-caused state conflicts → ConflictException (409). We log the key id (caller's own,
        // tenant-scoped) but keep the thrown MESSAGE id-free so the 409 body carries no identifier.
        if (old.getRevokedAt() != null
                || (old.getExpiresAt() != null && !now.isBefore(old.getExpiresAt()))) {
            log.warn("Refusing to rotate a revoked/expired API key: id={}, tenant={}", keyId, tenantId);
            throw new ConflictException("API key cannot be rotated in its current state", "key_not_rotatable");
        }
        // Do not re-rotate an already-superseded key.
        if (old.getReplacedBy() != null) {
            log.warn("Refusing to re-rotate an already-superseded API key: id={}, tenant={}", keyId, tenantId);
            throw new ConflictException("API key has already been rotated", "key_not_rotatable");
        }

        Instant inheritedExpiry = old.getExpiresAt(); // new key inherits the ORIGINAL expiry (or null)
        // DX-5c-ii: the successor INHERITS the rotated key's scopes VERBATIM — a rotation must NEVER widen
        // (or change) scope. The persisted csv is copied straight across; no re-validation widens it.
        String inheritedScopes = old.getScopes();

        // Mint the NEW key: same role/tenant/live/scopes as the old; inherits the old key's original expiry.
        GeneratedKey generated = generateKey(old.isLive());
        var newEntity = new ApiKeyEntity(generated.id(), generated.keyHash(), generated.keyPrefix(),
                old.getName(), old.getRole(), old.getTenantId(), old.isLive(), now, null,
                inheritedExpiry, null, null, inheritedScopes);
        apiKeyRepository.save(newEntity);

        // Shorten the OLD key: expires_at = EARLIER of (original expiry, now+overlap). Never extend.
        // A zero/negative overlap => now+overlap <= now => retire immediately.
        Instant overlapDeadline = now.plus(overlap);
        Instant newOldExpiry = (old.getExpiresAt() == null)
                ? overlapDeadline
                : earlier(old.getExpiresAt(), overlapDeadline);
        old.setExpiresAt(newOldExpiry);
        old.setReplacedBy(generated.id());
        apiKeyRepository.save(old);

        log.info("Rotated API key: oldId={}, newId={}, tenant={}, oldExpiresAt={}, overlap={}, scopes={}",
                keyId, generated.id(), tenantId, newOldExpiry, overlap, inheritedScopes);
        return new CreateApiKeyResult(generated.id(), generated.fullKey(), generated.keyPrefix(),
                old.getName(), old.getRole(), old.getTenantId(), old.isLive(), inheritedExpiry,
                parsePersistedScopes(inheritedScopes));
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listActiveKeys(String tenantId) {
        return apiKeyRepository.findAllByTenantIdAndRevokedAtIsNull(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * DX-5c: best-effort, throttled last_used_at stamp. Fail-OPEN — any exception is swallowed (logged
     * at debug). Only re-touches when last_used_at is null or older than {@link #LAST_USED_THROTTLE}.
     */
    private void touchIfStale(ApiKeyEntity candidate, Instant now) {
        Instant lastUsed = candidate.getLastUsedAt();
        boolean stale = lastUsed == null || lastUsed.isBefore(now.minus(LAST_USED_THROTTLE));
        if (!stale) {
            return;
        }
        try {
            usageTracker.touch(candidate.getId(), now);
        } catch (Exception e) {
            // Observability only — never deny a valid key because the stamp failed.
            log.debug("last_used_at touch failed for id={}: {}", candidate.getId(), e.getMessage());
        }
    }

    /**
     * DX-5c-ii: parse a PERSISTED scopes csv into the principal's scope set, DEFENSIVELY (no throw).
     *
     * <p>{@link ApiScope#parseCsv(String)} fail-closes on unknown tokens at CREATION; on the AUTHENTICATE
     * path we must never 400 a request because a stored value drifted (manual DB edit, legacy row), so we
     * filter to the KNOWN vocabulary and silently drop any unknown token. A null/blank csv, or one whose
     * tokens are all unknown, yields {@code null} == UNRESTRICTED (fail SAFE here: an unparseable scope
     * column never accidentally LOCKS OUT a valid key, and never GRANTS an unknown capability either — an
     * unknown token simply isn't added). Returns {@code null} for unrestricted, else an immutable set.</p>
     */
    private static Set<String> parsePersistedScopes(String csv) {
        if (csv == null || csv.isBlank()) {
            return null; // UNRESTRICTED
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String token = raw.trim();
            if (!token.isEmpty() && ApiScope.isValid(token)) {
                parsed.add(token);
            }
        }
        return parsed.isEmpty() ? null : Set.copyOf(parsed);
    }

    private record GeneratedKey(String id, String fullKey, String keyHash, String keyPrefix) {}

    /**
     * DX-5c: shared key generation + fail-closed prefix/is_live assertion. Used by both createApiKey
     * and rotateApiKey so the minting path (and the Critique-3.2 write-boundary invariant) is identical.
     */
    private GeneratedKey generateKey(boolean live) {
        String prefix = live ? "sk_live_" : "sk_test_";
        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String fullKey = prefix + randomPart;

        String keyHash = passwordEncoder.encode(fullKey);
        String keyPrefix = fullKey.substring(0, Math.min(fullKey.length(), PREFIX_DISPLAY_LENGTH));
        String id = PrefixedId.apiKey();

        // Critique 3.2 (belt-and-suspenders): the prefix and the is_live flag are derived from the SAME
        // `live` argument above, so they always agree here. Lock that invariant at the write boundary so a
        // future refactor cannot persist a mismatched row — authenticate() enforces the same invariant at
        // the read boundary (fail-closed), making the prefix a verified control rather than cosmetic.
        if (!prefixAgreesWithLive(keyPrefix, live)) {
            throw new IllegalStateException("API key prefix/is_live mismatch at creation");
        }
        return new GeneratedKey(id, fullKey, keyHash, keyPrefix);
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * Critique 3.2: does the stored {@code key_prefix} string AGREE with the {@code is_live} flag?
     * A {@code "sk_live_"} prefix agrees only with {@code live == true}; a {@code "sk_test_"} prefix only
     * with {@code live == false}. Any other prefix (corruption / a future scheme) agrees with NEITHER and
     * returns {@code false} — fail-closed by construction. {@code prefix} is the persisted 12-char display
     * prefix (e.g. {@code "sk_live_AbCd"}), which always starts with the full {@code "sk_live_"} /
     * {@code "sk_test_"} 8-char token, so {@code startsWith} is exact.
     */
    private static boolean prefixAgreesWithLive(String prefix, boolean live) {
        if (prefix == null) {
            return false;
        }
        if (prefix.startsWith("sk_live_")) {
            return live;
        }
        if (prefix.startsWith("sk_test_")) {
            return !live;
        }
        return false;
    }

    private ApiKey toDomain(ApiKeyEntity entity) {
        return new ApiKey(entity.getId(), entity.getKeyHash(), entity.getKeyPrefix(),
                entity.getName(), entity.getRole(), entity.getTenantId(), entity.isLive(),
                entity.getCreatedAt(), entity.getRevokedAt(),
                entity.getExpiresAt(), entity.getLastUsedAt(), entity.getReplacedBy());
    }

    public record CreateApiKeyResult(
            String id, String fullKey, String keyPrefix, String name,
            String role, String tenantId, boolean live, Instant expiresAt,
            // DX-5c-ii: the key's scopes (null/empty == UNRESTRICTED). Surfaced so the controller can echo
            // them in the create/rotate response.
            Set<String> scopes
    ) {}
}
