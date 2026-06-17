package io.nexuspay.iam.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JpaApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {

    List<ApiKeyEntity> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);

    List<ApiKeyEntity> findAllByTenantIdAndRevokedAtIsNull(String tenantId);

    /**
     * DX-5c: stamp {@code last_used_at} on the already-matched key by id. Single-row, id-keyed
     * UPDATE — observability only (fail-open); the caller best-effort/throttles and swallows failure.
     * Returns the affected row count (0 if the id no longer exists).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApiKeyEntity k SET k.lastUsedAt = :now WHERE k.id = :id")
    int touchLastUsedAt(@Param("id") String id, @Param("now") Instant now);

    /**
     * DX-5c: tenant-scoped single-key lookup by id. Used by tenant-scoped revoke and rotate so an
     * other-tenant key id resolves to {@code empty()} — indistinguishable from a missing id (no IDOR
     * oracle). The caller maps {@code empty()} to the uniform {@code invalidApiKey()} 404/401.
     */
    Optional<ApiKeyEntity> findByIdAndTenantId(String id, String tenantId);
}
