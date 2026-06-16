package com.intranet.controller.RMS;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.rms.ResourceSummaryMinimalDTO;
import com.intranet.dto.rms.MonthlySummaryResponseDTO;
import com.intranet.dto.rms.TimeSheetSummaryResponseDTO;
import com.intranet.dto.rms.UserSummarySimplifiedDTO;
import com.intranet.dto.UserHoursDTO;
import com.intranet.service.RMS.RMSTimeSheetService;
import com.intranet.service.DashboardService;
import com.intranet.util.cache.UserDirectoryService;
import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor

public class RMSTimeSheetController {

    private final RMSTimeSheetService timeSheetService;
    private final DashboardService dashboardService;
    private final UserDirectoryService userDirectoryService;

    @GetMapping("/RMS/summary")
    @Operation(summary = "Get RMS utilization intelligence summary")
    public ResponseEntity<TimeSheetSummaryResponseDTO> getSummary(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate endDate,
            HttpServletRequest request) {

        // Default to current month if no dates provided
        LocalDate today = LocalDate.now();
        LocalDate defaultStartDate = today.withDayOfMonth(1);
        LocalDate defaultEndDate = today;

        LocalDate effectiveStartDate = startDate != null ? startDate : defaultStartDate;
        LocalDate effectiveEndDate = endDate != null ? endDate : defaultEndDate;

        return ResponseEntity.ok(
                timeSheetService.getSummary(effectiveStartDate, effectiveEndDate, request)
        );
    }

    @GetMapping("/RMS/resource-summaries")
    @Operation(summary = "Get RMS resource-level summaries for all users")
    public ResponseEntity<List<ResourceSummaryMinimalDTO>> getAllResourceSummaries(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate endDate) {

        // Default to current month if no dates provided
        LocalDate today = LocalDate.now();
        LocalDate defaultStartDate = today.withDayOfMonth(1);
        LocalDate defaultEndDate = today;

        LocalDate effectiveStartDate = startDate != null ? startDate : defaultStartDate;
        LocalDate effectiveEndDate = endDate != null ? endDate : defaultEndDate;

        return ResponseEntity.ok(
                timeSheetService.getAllResourceSummaries(effectiveStartDate, effectiveEndDate)
        );
    }

    @GetMapping("/RMS/users")
    @Operation(summary = "Get simplified user summaries with resource context, hourly split, trend signal, and utilization")
    public ResponseEntity<List<UserSummarySimplifiedDTO>> getAllUserSummariesSimplified(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate endDate) {

        // Default to current month if no dates provided
        LocalDate today = LocalDate.now();
        LocalDate defaultStartDate = today.withDayOfMonth(1);
        LocalDate defaultEndDate = today;

        LocalDate effectiveStartDate = startDate != null ? startDate : defaultStartDate;
        LocalDate effectiveEndDate = endDate != null ? endDate : defaultEndDate;

        return ResponseEntity.ok(
                timeSheetService.getAllUserSummariesSimplified(effectiveStartDate, effectiveEndDate)
        );
    }

    @GetMapping("/RMS/monthly-summary")
    @Operation(summary = "Get monthly/period summary with overall KPIs, resource summaries, trends and hour breakdown")
    public ResponseEntity<MonthlySummaryResponseDTO> getMonthlySummary(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate endDate) {

        // Default to current month if no dates provided
        LocalDate today = LocalDate.now();
        LocalDate defaultStartDate = today.withDayOfMonth(1);
        LocalDate defaultEndDate = today;

        LocalDate effectiveStartDate = startDate != null ? startDate : defaultStartDate;
        LocalDate effectiveEndDate = endDate != null ? endDate : defaultEndDate;

        return ResponseEntity.ok(
                timeSheetService.getMonthlySummary(effectiveStartDate, effectiveEndDate)
        );
    }
}
