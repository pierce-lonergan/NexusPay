package io.nexuspay.iam.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic audit logging via AOP.
 * Use for non-financial operations. Financial operations should use
 * explicit AuditService.logAction() calls.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** The action name (e.g., "api_key_created", "config_read"). */
    String action();

    /** The resource type (e.g., "ApiKey", "Config"). */
    String resourceType() default "";
}
