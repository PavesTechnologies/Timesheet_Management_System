package com.intranet.controller;

import com.intranet.dto.UserDTO;
import com.intranet.dto.WeeklySummaryDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.WeeklySummaryService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/timesheet")
@RequiredArgsConstructor
public class WeeklySummaryController {

    private final WeeklySummaryService weeklySummaryService;

    /**
     * Get weekly summary of a user for the current month
     *
     * @param userId the ID of the user
     * @return weekly summary DTO
     */
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') OR hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Get weekly summary of a user for the current month")
    public ResponseEntity<?> getWeeklySummary2(@CurrentUser UserDTO user) {
        try{
        WeeklySummaryDTO summary = weeklySummaryService.getUserWeeklyTimeSheetHistory(user.getId());
        return ResponseEntity.ok(summary);
        }
        catch(Exception e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/historyRange")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') OR hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Get weekly summary of a user for any given date range")
    public ResponseEntity<?> getWeeklySummary(
            @CurrentUser UserDTO user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            WeeklySummaryDTO summary = weeklySummaryService.getUserWeeklyTimeSheetHistoryRange(user.getId(), startDate, endDate);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

}
