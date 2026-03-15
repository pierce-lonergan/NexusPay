package io.nexuspay.iam.config;

import io.nexuspay.iam.application.AuditService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AOP aspect for automatic audit logging on @Audited methods.
 * Captures the actor from SecurityContext and logs after successful execution.
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(audited)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();

        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            String actor = "anonymous";
            String tenantId = "default";

            if (auth != null && auth.getPrincipal() instanceof NexusPayPrincipal principal) {
                actor = principal.userId();
                tenantId = principal.tenantId();
            }

            auditService.logAction(
                    actor,
                    audited.action(),
                    audited.resourceType(),
                    null,
                    Map.of("method", joinPoint.getSignature().toShortString()),
                    null,
                    tenantId
            );
        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }

        return result;
    }
}
