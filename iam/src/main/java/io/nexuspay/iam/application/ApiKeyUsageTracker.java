package io.nexuspay.iam.application;

import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DX-5c: stamps {@code last_used_at} on a successfully-authenticated API key.
 *
 * <p>This is a SEPARATE bean from {@link ApiKeyService} on purpose: Spring's {@code @Transactional}
 * proxy only applies on calls that cross a bean boundary. If {@code authenticate} called a
 * {@code @Transactional} method on {@code this}, the self-invocation would bypass the proxy and the
 * update would not run in its own transaction. ApiKeyService injects this tracker and calls it across
 * the bean boundary so the proxy (and the {@code @Transactional} commit) takes effect.
 *
 * <p>{@code last_used_at} is OBSERVABILITY ONLY and fails OPEN: the caller wraps {@link #touch} in a
 * try/catch and swallows any exception. A touch failure must NEVER deny a valid key.
 */
@Component
public class ApiKeyUsageTracker {

    private final JpaApiKeyRepository apiKeyRepository;

    public ApiKeyUsageTracker(JpaApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Best-effort, single-row, id-keyed update of {@code last_used_at}. Runs in its own transaction.
     */
    @Transactional
    public void touch(String id, Instant now) {
        apiKeyRepository.touchLastUsedAt(id, now);
    }
}
