package com.intranet.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.UserDTO;
import com.intranet.entity.TimeSheet;
import com.intranet.repository.TimeSheetRepo;
import com.intranet.security.CurrentUser;
import com.intranet.service.DashboardService;
import com.intranet.service.TimeUtil;

import io.swagger.v3.oas.annotations.Operation;


@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private TimeSheetRepo timeSheetRepo;
    @Autowired
    private DashboardService dashboardService;

    /**
     * Get total hours entered by a user in the current month
     */
    @GetMapping("/total_hours")
    @Operation(summary = "Get total hours entered by a user in the current month")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getTotalHoursCurrentMonth(
        @CurrentUser UserDTO user
    ) 
    {
     try{
        if (user.getId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "User ID cannot be null",
                    "data", Map.of()
            ));
        }

        LocalDate now = LocalDate.now();
        LocalDate startOfMonth = now.withDayOfMonth(1);
        LocalDate endOfMonth = now.withDayOfMonth(now.lengthOfMonth());

        // Fetch timesheets for user in current month
        List<TimeSheet> timesheets = timeSheetRepo.findByUserIdAndWorkDateBetween(user.getId(), startOfMonth, endOfMonth);
        if (timesheets.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "No timesheets found for current month",
                    "data", Map.of("totalHours", "0")
            ));
        }

        // Collect all hoursWorked values
        List<BigDecimal> hoursList = timesheets.stream()
                .map(TimeSheet::getHoursWorked)
                .collect(Collectors.toList());

        // Sum hours using TimeUtil
        BigDecimal totalHours = TimeUtil.sumHours(hoursList);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Total hours fetched successfully",
                "data", Map.of("totalHours", totalHours.toPlainString())
        ));
    }
    catch(Exception e){
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Error fetching total hours",
                "data", Map.of()
        ));
    }

    }
    

    @GetMapping("/summary")
    @Operation(summary = "Get summary of the dashboard")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getSummary(
        @CurrentUser UserDTO user
    ) {
        return ResponseEntity.ok(dashboardService.getDashboardSummary(user.getId(), LocalDate.now().withDayOfMonth(1), LocalDate.now().with(TemporalAdjusters.lastDayOfMonth())));
    }

    @GetMapping("/summary/lastMonth")
    @Operation(summary = "Get summary for the last 1 month window")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getLastMonthSummary(
            @CurrentUser UserDTO user
    ) {
        LocalDate endDate = LocalDate.now().with(TemporalAdjusters.lastDayOfMonth());
        LocalDate startDate = endDate.minusMonths(2).withDayOfMonth(1);
        return ResponseEntity.ok(
                dashboardService.getDashboardSummary(
                        user.getId(),
                        startDate,
                        endDate
                )
        );
    }

    @GetMapping("/summary/last3Months")
    @Operation(summary = "Get summary for the last 3 months window")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getLast3MonthsSummary(
            @CurrentUser UserDTO user
    ) {
        LocalDate endDate = LocalDate.now().with(TemporalAdjusters.lastDayOfMonth());
        LocalDate startDate = endDate.minusMonths(3).withDayOfMonth(1);           

        return ResponseEntity.ok(
                dashboardService.getDashboardSummary(
                        user.getId(),
                        startDate,
                        endDate
                )
        );
    }

    @GetMapping("/summary/dateRangeMonths")
    @Operation(summary = "Get summary for the date range months window")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getDateRangeMonthsSummary(
            @CurrentUser UserDTO user,
            LocalDate startDate,
            LocalDate endDate
    ) {

        if (startDate == null) {
            startDate = LocalDate.now().withDayOfMonth(1);
        }
        if (endDate == null) {
            endDate = LocalDate.now().with(TemporalAdjusters.lastDayOfMonth());
        }

        return ResponseEntity.ok(
                dashboardService.getDashboardSummary(
                        user.getId(),
                        startDate,
                        endDate
                )
        );
    }
}
