package com.intranet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intranet.dto.UserDTO;
import com.intranet.entity.AuditAction;
import com.intranet.entity.AuditLog;
import com.intranet.entity.AuditStatus;
import com.intranet.security.CurrentUser;
import com.intranet.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * AOP Aspect that automatically intercepts all controller methods
 * (GET, POST, PUT, DELETE) and creates audit log entries.
 *
 * Uses the @Auditable annotation for explicit control when present,
 * otherwise auto-detects action and entity type from the HTTP method
 * and controller class name.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Intercepts all controller methods annotated with HTTP method annotations.
     * Covers GET, POST, PUT, DELETE across all @RestController classes in the project.
     */
    @Around("execution(* com.intranet.controller..*(..)) && (" +
            "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.DeleteMapping))")
    public Object auditLog(ProceedingJoinPoint joinPoint) throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        // ── 1. Extract HTTP request context ──
        HttpServletRequest request = getHttpServletRequest();
        String httpMethod = request != null ? request.getMethod() : detectHttpMethod(method);
        String endpoint = request != null ? request.getRequestURI() : "";

        // ── 2. Extract user from JWT SecurityContext (works for all endpoints) ──
        UserDTO currentUser = extractUserFromSecurityContext();

        // ── 3. Extract @RequestBody from method args ──
        Object requestBody = extractRequestBody(method, args);

        // ── 4. Determine action and entity type ──
        AuditAction action;
        String entityType;
        String description;

        Auditable auditable = method.getAnnotation(Auditable.class);
        if (auditable != null) {
            // Use explicit annotation values
            action = auditable.action();
            entityType = auditable.entityType();
            description = auditable.description().isEmpty()
                    ? buildAutoDescription(action, entityType, httpMethod, endpoint)
                    : auditable.description();
        } else {
            // Auto-detect from HTTP method and controller class
            action = resolveAction(httpMethod);
            entityType = resolveEntityType(joinPoint.getTarget().getClass());
            description = buildAutoDescription(action, entityType, httpMethod, endpoint);
        }

        // ── 5. Extract target user ID (for manager actions) ──
        Long targetUserId = extractTargetUserId(method, args);

        // ── 6. Execute the original method ──
        Object result;
        try {
            result = joinPoint.proceed();

            // ── 7. Determine success/failure from ResponseEntity status ──
            AuditStatus status = AuditStatus.SUCCESS;
            String errorMessage = null;

            if (result instanceof ResponseEntity<?> responseEntity) {
                int statusCode = responseEntity.getStatusCode().value();
                if (statusCode >= 400) {
                    status = AuditStatus.FAILURE;
                    errorMessage = extractErrorMessage(responseEntity);
                }
            }

            // ── 8. Build and save audit log (async) ──
            buildAndSaveLog(currentUser, action, entityType, null, description,
                    requestBody, targetUserId, request, status, errorMessage);

            return result;

        } catch (Throwable ex) {
            // ── 9. Log failure on exception ──
            buildAndSaveLog(currentUser, action, entityType, null, description,
                    requestBody, targetUserId, request, AuditStatus.FAILURE, ex.getMessage());
            throw ex;
        }
    }

    // ──────────────────────────────────────────────
    //  Helper Methods
    // ──────────────────────────────────────────────

    private void buildAndSaveLog(UserDTO user, AuditAction action, String entityType,
                                  Long entityId, String description, Object requestBody,
                                  Long targetUserId, HttpServletRequest request,
                                  AuditStatus status, String errorMessage) {

        String requestBodyJson = null;
        if (requestBody != null) {
            try {
                requestBodyJson = objectMapper.writeValueAsString(requestBody);
            } catch (Exception e) {
                requestBodyJson = requestBody.toString();
            }
        }

        String clientIp = null;
        String httpMethod = null;
        String endpoint = null;

        if (request != null) {
            clientIp = getClientIp(request);
            httpMethod = request.getMethod();
            endpoint = request.getRequestURI();
        }

        AuditLog auditLog = AuditLog.builder()
                .userId(user != null ? user.getId() : null)
                .userName(user != null ? user.getName() : null)
                .userEmail(user != null ? user.getEmail() : null)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .description(description)
                .requestBody(requestBodyJson)
                .endpoint(endpoint)
                .httpMethod(httpMethod)
                .ipAddress(clientIp)
                .status(status)
                .errorMessage(errorMessage)
                .targetUserId(targetUserId)
                .build();

        auditLogService.logAsync(auditLog);
    }

    /**
     * Extract UserDTO from method parameters annotated with @CurrentUser.
     */
    private UserDTO extractCurrentUser(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        for (int i = 0; i < parameters.length; i++) {
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof CurrentUser && args[i] instanceof UserDTO) {
                    return (UserDTO) args[i];
                }
            }
        }
        return null;
    }

    /**
     * Fallback: Extract user info directly from the JWT token in SecurityContext.
     * Used when the controller method doesn't have a @CurrentUser parameter
     * (e.g., InternalProjectController, WeekInfoController).
     */
    private UserDTO extractUserFromSecurityContext() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                UserDTO user = new UserDTO();
                String userIdStr = jwt.getClaimAsString("user_id");
                if (userIdStr != null) {
                    user.setId(Long.valueOf(userIdStr));
                }
                user.setName(jwt.getClaimAsString("name"));
                user.setEmail(jwt.getClaimAsString("email"));
                return user;
            }
        } catch (Exception e) {
            log.warn("Could not extract user from SecurityContext: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract the @RequestBody argument.
     */
    private Object extractRequestBody(Method method, Object[] args) {
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        for (int i = 0; i < paramAnnotations.length; i++) {
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof RequestBody) {
                    return args[i];
                }
            }
        }
        return null;
    }

    /**
     * Try to extract a target user ID from common DTO patterns.
     * Looks for userId/user_id fields in @RequestBody objects and @RequestParam.
     */
    private Long extractTargetUserId(Method method, Object[] args) {
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        for (int i = 0; i < paramAnnotations.length; i++) {
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof RequestBody && args[i] != null) {
                    try {
                        // Try reflection to find getUserId() method
                        java.lang.reflect.Method getUserId = args[i].getClass().getMethod("getUserId");
                        Object value = getUserId.invoke(args[i]);
                        if (value instanceof Long) return (Long) value;
                        if (value instanceof Number) return ((Number) value).longValue();
                    } catch (Exception ignored) {
                        // No getUserId method — that's fine
                    }
                }
            }
        }
        return null;
    }

    /**
     * Map HTTP method to audit action.
     */
    private AuditAction resolveAction(String httpMethod) {
        if (httpMethod == null) return AuditAction.CREATE;
        return switch (httpMethod.toUpperCase()) {
            case "POST" -> AuditAction.CREATE;
            case "PUT" -> AuditAction.UPDATE;
            case "DELETE" -> AuditAction.DELETE;
            default -> AuditAction.CREATE;
        };
    }

    /**
     * Derive entity type from the controller class name.
     * e.g., TimeSheetController → TIMESHEET
     *        HolidayExcludeUsersController → HOLIDAY_EXCLUDE_USERS
     *        TimeSheetReviewController → TIMESHEET_REVIEW
     */
    private String resolveEntityType(Class<?> controllerClass) {
        String className = controllerClass.getSimpleName();

        // Remove "Controller" suffix
        String name = className.replace("Controller", "")
                               .replace("Contoller", ""); // handle the typo in UserContoller

        // Convert camelCase to UPPER_SNAKE_CASE
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }

        return sb.toString();
    }

    /**
     * Build a human-readable description.
     */
    private String buildAutoDescription(AuditAction action, String entityType,
                                         String httpMethod, String endpoint) {
        return String.format("%s %s — %s %s", action, entityType, httpMethod, endpoint);
    }

    /**
     * Detect HTTP method from Spring annotations when HttpServletRequest is unavailable.
     */
    private String detectHttpMethod(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) return "GET";
        if (method.isAnnotationPresent(PostMapping.class)) return "POST";
        if (method.isAnnotationPresent(PutMapping.class)) return "PUT";
        if (method.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        return "UNKNOWN";
    }

    /**
     * Get HttpServletRequest from the current request context.
     */
    private HttpServletRequest getHttpServletRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract error message from a failed ResponseEntity.
     */
    private String extractErrorMessage(ResponseEntity<?> responseEntity) {
        Object body = responseEntity.getBody();
        if (body instanceof String) return (String) body;
        if (body != null) {
            try {
                return objectMapper.writeValueAsString(body);
            } catch (Exception e) {
                return body.toString();
            }
        }
        return "HTTP " + responseEntity.getStatusCode().value();
    }

    /**
     * Extract real client IP, considering reverse proxies.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
