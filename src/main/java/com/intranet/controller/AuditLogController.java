package com.intranet.controller;

import com.intranet.entity.AuditAction;
import com.intranet.entity.AuditLog;
import com.intranet.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * Single endpoint to fetch all audit logs with optional filters.
     *
     * Supports filtering by:
     *   - userId       (who performed the action)
     *   - action       (CREATE, UPDATE, DELETE, APPROVE, REJECT, SUBMIT, READ, etc.)
     *   - entityType   (TIMESHEET, TIMESHEET_REVIEW, HOLIDAY_EXCLUDE_USERS, etc.)
     *   - startDate    (from date, inclusive)
     *   - endDate      (to date, inclusive)
     *   - page         (0-indexed page number, default 0)
     *   - size         (page size, default 50)
     *
     * Example:
     *   GET /api/audit-logs?action=CREATE&entityType=TIMESHEET&startDate=2026-05-01&endDate=2026-05-31&page=0&size=20
     */
    @GetMapping
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Get all audit logs with optional filters (Admin only)")
    public ResponseEntity<Page<AuditLog>> getAuditLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // Convert LocalDate to LocalDateTime for the query
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Pageable pageable = PageRequest.of(page, size);

        Page<AuditLog> auditLogs = auditLogService.getAuditLogs(
                userId, action, entityType, startDateTime, endDateTime, pageable);

        return ResponseEntity.ok(auditLogs);
    }
}
