package com.intranet.controller.reports;

import com.intranet.dto.MonthlyUserReportDTO;
import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.MonthlyUserReportService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class MonthlyUserReportController {

    private final MonthlyUserReportService reportService;

    @GetMapping("/user_monthly")
    @Operation(summary = "Get monthly report for the current user", description = "Retrieve a detailed monthly report for the authenticated user.")
    @PreAuthorize("hasAuthority('EDIT_TIMESHEET') or hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> getMonthlyReport(
            @CurrentUser UserDTO currentUser,
            @RequestParam(required = false) int month,
            @RequestParam(required = false) int year) {
        try {
        if (month <= 0 || month > 12 || year <= 0) {
            return ResponseEntity.badRequest().body("Invalid month or year parameter");
        }
        if (month == 0 && year == 0) {
            month = java.time.LocalDate.now().getMonthValue();
            year = java.time.LocalDate.now().getYear();
        }

        LocalDate now = LocalDate.now();
        // Validate future or same month/year selection
        if (year > now.getYear() ||
        (year == now.getYear() && month >= now.getMonthValue())) {

            // prevent future OR current month processing
            return ResponseEntity.badRequest()
                .body("You cannot select the current or future month.");
        }
        
        MonthlyUserReportDTO report = reportService.getMonthlyUserReport(currentUser.getId(), month, year);
        return ResponseEntity.ok(report);
        } 
        catch (IllegalStateException e) {
        // PENDING leaves error
        return ResponseEntity.badRequest().body(e.getMessage());
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().body("Error generating report: " + e.getMessage());
        }
    }
}
