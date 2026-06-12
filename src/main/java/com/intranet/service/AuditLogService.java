package com.intranet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intranet.dto.UserDTO;
import com.intranet.entity.AuditAction;
import com.intranet.entity.AuditLog;
import com.intranet.entity.AuditStatus;
import com.intranet.repository.AuditLogRepo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepo auditLogRepo;
    private final ObjectMapper objectMapper;

    /**
     * Save an audit log entry asynchronously (non-blocking).
     */
    @Async("auditLogExecutor")
    public void logAsync(AuditLog auditLog) {
        try {
            auditLogRepo.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save audit log: {}", e.getMessage(), e);
        }
    }

    /**
     * Convenience method to build and save an audit log entry.
     */
    public void log(UserDTO user, AuditAction action, String entityType,
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

        AuditLog auditLog = AuditLog.builder()
                .userId(user != null ? user.getId() : null)
                .userName(user != null ? user.getName() : null)
                .userEmail(user != null ? user.getEmail() : null)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .description(description)
                .requestBody(requestBodyJson)
                .endpoint(request != null ? request.getRequestURI() : null)
                .httpMethod(request != null ? request.getMethod() : null)
                .ipAddress(request != null ? getClientIp(request) : null)
                .status(status)
                .errorMessage(errorMessage)
                .targetUserId(targetUserId)
                .build();

        logAsync(auditLog);
    }

    /**
     * Overloaded convenience for SUCCESS logs.
     */
    public void logSuccess(UserDTO user, AuditAction action, String entityType,
                           Long entityId, String description, Object requestBody,
                           Long targetUserId, HttpServletRequest request) {
        log(user, action, entityType, entityId, description, requestBody,
                targetUserId, request, AuditStatus.SUCCESS, null);
    }

    /**
     * Overloaded convenience for FAILURE logs.
     */
    public void logFailure(UserDTO user, AuditAction action, String entityType,
                           Long entityId, String description, Object requestBody,
                           Long targetUserId, HttpServletRequest request,
                           String errorMessage) {
        log(user, action, entityType, entityId, description, requestBody,
                targetUserId, request, AuditStatus.FAILURE, errorMessage);
    }

    /**
     * Query audit logs with optional filters (all parameters nullable).
     */
    public Page<AuditLog> getAuditLogs(Long userId, AuditAction action,
                                        String entityType, LocalDateTime startDate,
                                        LocalDateTime endDate, Pageable pageable) {
        return auditLogRepo.findWithFilters(userId, action, entityType, startDate, endDate, pageable);
    }

    /**
     * Get all audit logs for a specific entity.
     */
    public java.util.List<AuditLog> getEntityAuditTrail(String entityType, Long entityId) {
        return auditLogRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    /**
     * Extract real client IP, considering proxies.
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
