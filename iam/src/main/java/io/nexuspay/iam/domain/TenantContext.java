package io.nexuspay.iam.domain;

/**
 * ThreadLocal holder for the current tenant ID.
 *
 * Set by {@link io.nexuspay.iam.adapter.in.filter.TenantContextFilter} at request entry.
 * Read by {@link io.nexuspay.iam.config.TenantAwareDataSourceConfig} to inject
 * {@code SET LOCAL app.current_tenant_id = ?} into every database transaction,
 * enabling PostgreSQL Row-Level Security (RLS).
 *
 * <p>Also used by Kafka producers to propagate tenant context in event headers,
 * and by MDC logging for structured log correlation.</p>
 *
 * <p>Virtual-thread safe: ThreadLocal works correctly with virtual threads since
 * each virtual thread has its own ThreadLocal storage.</p>
 *
 * @since 0.2.0 (Sprint 2.1)
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * System tenant ID used for operations that are not scoped to a specific tenant
     * (migrations, health checks, system jobs).
     */
    public static final String SYSTEM_TENANT = "system";

    private TenantContext() {
        // Utility class
    }

    /**
     * Sets the tenant ID for the current thread/virtual thread.
     *
     * @param tenantId the tenant identifier (e.g., "tenant_abc123")
     * @throws IllegalArgumentException if tenantId is null or blank
     */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null or blank");
        }
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Gets the tenant ID for the current thread.
     *
     * @return the tenant ID, or {@code null} if not set
     */
    public static String get() {
        return CURRENT_TENANT.get();
    }

    /**
     * Gets the tenant ID, falling back to a default if not set.
     *
     * @param defaultTenantId fallback value
     * @return the tenant ID, or {@code defaultTenantId} if not set
     */
    public static String getOrDefault(String defaultTenantId) {
        String tenantId = CURRENT_TENANT.get();
        return tenantId != null ? tenantId : defaultTenantId;
    }

    /**
     * Returns true if a tenant context is currently set.
     */
    public static boolean isSet() {
        return CURRENT_TENANT.get() != null;
    }

    /**
     * Clears the tenant context. Must be called in a finally block
     * to prevent ThreadLocal leaks.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
