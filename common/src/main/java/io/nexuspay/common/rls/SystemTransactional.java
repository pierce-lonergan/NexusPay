package io.nexuspay.common.rls;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a cross-tenant background job / Kafka-consumer method that must run on the
 * {@code nexuspay_system} (BYPASSRLS) database connection (B-002).
 *
 * <p>Lives in {@code common} (not {@code app}) so every module's scheduled jobs and consumers can
 * declare it; the advice that acts on it — {@code SystemRoleAspect} in the {@code app} composition
 * root — pins the SYSTEM role on the calling thread BEFORE the transaction begins, so the routing
 * datasource hands the transaction a system connection that sees every tenant's rows. The role pin
 * is a call-scoped thread-local, so it also covers nested {@code @Transactional} service calls made
 * synchronously within the annotated method (annotate the job's entry method; you need not chase the
 * inner transaction boundary). It does NOT propagate to work handed to other threads (async
 * callbacks, executors) — those run on the default APP role.</p>
 *
 * <p>Use ONLY for work that legitimately spans all tenants (aggregate rollups, retention sweeps,
 * outbox relay, DLQ reprocessing, lag/health monitors). A method that processes a single tenant's
 * data (e.g. a Kafka event carrying a tenantId) must instead bind {@code TenantContext} and stay on
 * the RLS-bound APP role — annotating such a method here would bypass RLS and open a cross-tenant
 * hole.</p>
 *
 * <p>Inert when RLS enforcement is off: the acting aspect bean only exists under
 * {@code nexuspay.multi-tenancy.rls.enforce=true}, so at the default this annotation is a no-op.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemTransactional {
}
