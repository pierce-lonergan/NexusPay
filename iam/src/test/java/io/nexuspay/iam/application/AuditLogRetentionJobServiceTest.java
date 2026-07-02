package io.nexuspay.iam.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-027: audit_log retention sweep. Mock the {@link EntityManager} to assert:
 * <ul>
 *   <li>the DELETE targets {@code audit_log} with a strict {@code timestamp < NOW() - :days} predicate
 *       (the column is {@code timestamp}, NOT {@code created_at});</li>
 *   <li>the {@code days} parameter equals the configured window (default 2555 = 7 years);</li>
 *   <li>the delete is BATCHED — it loops while a page returns the full batch size and stops on a short
 *       page (so a large table is never deleted in one long-lock statement);</li>
 *   <li>the rows-deleted counter is incremented by the rows removed.</li>
 * </ul>
 */
class AuditLogRetentionJobServiceTest {

    private EntityManager entityManager;
    private Query query;
    private MeterRegistry meterRegistry;
    private AuditLogRetentionJobService service;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        meterRegistry = new SimpleMeterRegistry();
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        when(query.setParameter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(query);
        service = new AuditLogRetentionJobService(entityManager, meterRegistry);
        // Default window (as if @Value resolved the default 2555).
        ReflectionTestUtils.setField(service, "auditRetentionDays", 2555);
    }

    @Test
    void deletesAuditLog_strictlyOlderThanConfiguredWindow_batched_andMetered() {
        // First page returns a FULL batch (5000) -> loop continues; second page short (137) -> loop ends.
        when(query.executeUpdate()).thenReturn(5000, 137);

        service.cleanupExpiredAuditLog();

        // The native DELETE targets audit_log on the `timestamp` column, strictly older-than.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createNativeQuery(sql.capture());
        String deleteSql = sql.getValue();
        assertThat(deleteSql).contains("DELETE FROM audit_log");
        assertThat(deleteSql).contains("timestamp <");
        assertThat(deleteSql).doesNotContain("created_at");

        // The configured window is threaded as the :days param.
        verify(query, atLeastOnce()).setParameter(eq("days"), eq(2555));

        // Batched: two executeUpdate iterations (full page then short page).
        verify(query, times(2)).executeUpdate();

        // Metered: 5000 + 137 rows deleted.
        double counted = meterRegistry.get("nexuspay.iam.audit_retention.rows_deleted").counter().count();
        assertThat(counted).isEqualTo(5137.0);
    }

    @Test
    void nonPositiveWindow_isRefused_noDelete() {
        ReflectionTestUtils.setField(service, "auditRetentionDays", 0);

        service.cleanupExpiredAuditLog();

        // A zero/negative window must never delete (guard against wiping the whole audit table).
        verify(query, times(0)).executeUpdate();
    }
}
