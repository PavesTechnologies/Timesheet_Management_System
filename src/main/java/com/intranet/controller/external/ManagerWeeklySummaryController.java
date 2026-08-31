package com.intranet.controller.external;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.UserDTO;
import com.intranet.dto.external.ManagerWeeklySummaryDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.external.ManagerWeeklySummaryService;
import com.intranet.util.MonthScope;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor
public class ManagerWeeklySummaryController {

    @Autowired
    private final ManagerWeeklySummaryService managerWeeklySummaryService;

    @GetMapping("/manager")
    @Operation(summary = "Get weekly submitted timesheets grouped by user for the manager",
               description = "Defaults to the current calendar month. Pass month=1..12 (and optionally "
                           + "year) to switch; only the current and previous calendar month are accepted.")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<List<ManagerWeeklySummaryDTO>> getSubmittedWeeklySummary(
            @CurrentUser UserDTO user,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");
        // Step 0: Resolve the requested month — current by default, previous when asked for.
        // Anything else is rejected by MonthScope (400 via TimesheetQueueScopeAdvice).
        YearMonth scope = MonthScope.resolve(month, year);
        LocalDate startOfMonth = scope.atDay(1);
        LocalDate endOfMonth = scope.atEndOfMonth();

        List<ManagerWeeklySummaryDTO> summary =
                managerWeeklySummaryService.getWeeklySubmittedTimesheetsByManager(user.getId(), authHeader, startOfMonth, endOfMonth);

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/manager/previous-month/pending")
    @Operation(summary = "Get previous month's timesheets that are still pending review by this manager")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<List<ManagerWeeklySummaryDTO>> getPreviousMonthPendingForManager(
            @CurrentUser UserDTO user,
            HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");
        LocalDate firstOfPrevMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate lastOfPrevMonth = firstOfPrevMonth.withDayOfMonth(firstOfPrevMonth.lengthOfMonth());

        List<ManagerWeeklySummaryDTO> summary =
                managerWeeklySummaryService.getPendingTimesheetsByManagerForPreviousMonth(
                        user.getId(), authHeader, firstOfPrevMonth, lastOfPrevMonth);

        return ResponseEntity.ok(summary);
    }
}
