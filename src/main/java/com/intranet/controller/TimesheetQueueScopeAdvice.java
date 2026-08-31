package com.intranet.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.intranet.controller.external.ManagerWeeklySummaryController;
import com.intranet.controller.supervisior.InternalWeeklySummaryController;

/**
 * Turns the month-scope rejection from {@link com.intranet.util.MonthScope} into a 400 with the
 * reason in the body. Without this the exception escapes as a bodyless 500, because the app has
 * no other advice and {@code server.error.include-message} is left at its {@code never} default.
 *
 * <p>Deliberately scoped with {@code assignableTypes} to the two approval-queue controllers: a
 * global advice would silently reclassify every {@code IllegalArgumentException} in the app.
 */
@RestControllerAdvice(assignableTypes = {
        ManagerWeeklySummaryController.class,
        InternalWeeklySummaryController.class
})
public class TimesheetQueueScopeAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadScope(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
