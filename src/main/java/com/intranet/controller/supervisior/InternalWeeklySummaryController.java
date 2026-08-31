package com.intranet.controller.supervisior;
import org.springframework.beans.factory.annotation.Value;
import com.intranet.dto.external.ManagerWeeklySummaryDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.supervisior.InternalWeeklySummaryService;
import com.intranet.util.MonthScope;
import com.intranet.dto.UserDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor
public class InternalWeeklySummaryController {

    private final InternalWeeklySummaryService internalWeeklyService;

    @Value("${eos.api.base-url}")
    private String eosBaseUrl;

    @Value("${ums.api.base-url}")
    private String umsBaseUrl;

    @GetMapping("/internal/summary")
    @Operation(summary = "Get weekly internal project summary for all users",
               description = "Defaults to the current calendar month. Pass month=1..12 (and optionally "
                           + "year) to switch; only the current and previous calendar month are accepted.")
    @PreAuthorize("hasAuthority('REVIEW_INTERNAL_TIMESHEET') or hasAuthority('TIMESHEET_ADMIN')")
    public ResponseEntity<List<ManagerWeeklySummaryDTO>> getInternalWeeklySummary(
            @CurrentUser UserDTO user,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");

        // Current by default, previous when asked for; anything else is a 400 via
        // TimesheetQueueScopeAdvice.
        YearMonth scope = MonthScope.resolve(month, year);
        LocalDate startOfMonth = scope.atDay(1);
        LocalDate endOfMonth = scope.atEndOfMonth();

        List<ManagerWeeklySummaryDTO> summary =
                internalWeeklyService.getInternalWeeklySummary(authHeader, startOfMonth, endOfMonth);

        return ResponseEntity.ok(summary);
    }


    @GetMapping("/internal/summary/reportingManager")
    @Operation(summary = "Get weekly internal project summary for all users under reporting manager",
               description = "Defaults to the current calendar month. Pass month=1..12 (and optionally "
                           + "year) to switch; only the current and previous calendar month are accepted.")
    @PreAuthorize("hasAuthority('REVIEW_INTERNAL_TIMESHEET') or hasAuthority('TIMESHEET_ADMIN')")
    public ResponseEntity<List<ManagerWeeklySummaryDTO>> getInternalWeeklySummaryReportingManager(
            @CurrentUser UserDTO user,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");

        // Resolved before the empId guard so a bad month is a consistent 400 rather than a
        // silent empty list for a user with no OBS id.
        YearMonth scope = MonthScope.resolve(month, year);
        LocalDate startOfMonth = scope.atDay(1);
        LocalDate endOfMonth = scope.atEndOfMonth();

        String managerEmpid = user.getEmployee_id();
        if (managerEmpid == null || managerEmpid.isBlank() || "No OBS User UUID".equals(managerEmpid)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<ManagerWeeklySummaryDTO> summary =
                internalWeeklyService.getInternalWeeklySummaryForReportingManager(
                        authHeader, managerEmpid, startOfMonth, endOfMonth);

        return ResponseEntity.ok(summary);
    }


    @GetMapping("/reporting-manager/users")
    @Operation(summary = "Get the current reporting manager's direct reports (for holiday-exclude dropdown)")
    @PreAuthorize("hasAuthority('REVIEW_INTERNAL_TIMESHEET') or hasAuthority('TIMESHEET_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getReportingManagerUsers(
            @CurrentUser UserDTO user,
            HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");

        String managerEmpid = user.getEmployee_id();
        if (managerEmpid == null || managerEmpid.isBlank() || "No OBS User UUID".equals(managerEmpid)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        return ResponseEntity.ok(
                internalWeeklyService.getReportingManagerUsers(authHeader, managerEmpid));
    }
}
