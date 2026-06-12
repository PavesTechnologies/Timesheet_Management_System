package com.intranet.config;

import com.intranet.entity.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional annotation for fine-grained audit control on controller methods.
 * When present, the AOP aspect uses these values instead of auto-detecting.
 *
 * Example:
 *   @Auditable(action = AuditAction.CREATE, entityType = "TIMESHEET", description = "Created a new timesheet")
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    AuditAction action();

    String entityType();

    String description() default "";
}
