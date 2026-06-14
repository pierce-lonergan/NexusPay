package io.nexuspay.app.rls;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a cross-tenant background job / Kafka consumer method that must run on the
 * {@code nexuspay_system} (BYPASSRLS) connection (B-002). {@link SystemRoleAspect} pins the
 * {@link DbRole#SYSTEM} role BEFORE the transaction begins, so the routing datasource hands the
 * transaction a system connection that sees every tenant's rows.
 *
 * <p>Inert when RLS enforcement is off: the aspect bean only exists under
 * {@code nexuspay.multi-tenancy.rls.enforce=true}, so at the default this annotation is a no-op.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemTransactional {
}
