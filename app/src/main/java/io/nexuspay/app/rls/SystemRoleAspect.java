package io.nexuspay.app.rls;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pins {@link DbRole#SYSTEM} around any {@link SystemTransactional} method (B-002).
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so it runs strictly BEFORE Spring's
 * {@code TransactionInterceptor} (LOWEST_PRECEDENCE): the role is set before the transaction
 * begins, so {@code doBegin → getConnection()} picks the {@code nexuspay_system} pool. Because
 * it advises the proxy, the method's own {@code @Transactional} still fires — no self-invocation
 * problem, no bean split. Only active under {@code rls.enforce=true} (dormant otherwise).</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "nexuspay.multi-tenancy.rls.enforce", havingValue = "true")
public class SystemRoleAspect {

    @Around("@annotation(io.nexuspay.app.rls.SystemTransactional)")
    public Object pinSystemRole(ProceedingJoinPoint pjp) throws Throwable {
        Object[] result = new Object[1];
        DbRoleContext.runAs(DbRole.SYSTEM, () -> result[0] = pjp.proceed());
        return result[0];
    }
}
