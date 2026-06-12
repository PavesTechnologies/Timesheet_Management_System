package com.intranet.controller.reports;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.report.ManagerMonthlyReportService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ManagerViewReportController {

    private final ManagerMonthlyReportService managerMonthlyReportService;

    @GetMapping("/managerMonthly")
    @Operation(summary = "Get manager monthly report", description = "Retrieve a detailed monthly report for managers.")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> managerMonthlyReport(
            @CurrentUser UserDTO currentUser,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            HttpServletRequest req
    ) {

        LocalDate now = LocalDate.now();
        // Validate future or same month/year selection
        if (year > now.getYear() ||
        (year == now.getYear() && month >= now.getMonthValue())) {

            // prevent future OR current month processing
            return ResponseEntity.badRequest()
                .body("You cannot select the current or future month.");
        }


        String token = req.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body("Authorization token is missing");
        }

        try {
            // Default to current month/year if not provided
            LocalDate today = LocalDate.now();
            int selectedMonth = (month != null && month >= 1 && month <= 12) ? month : today.getMonthValue();
            int selectedYear  = (year != null && year > 2000) ? year : today.getYear();

            // Build correct date range
            LocalDate startDate = LocalDate.of(selectedYear, selectedMonth, 1);
            LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

            // Generate report
            Map<String, Object> report =
                    managerMonthlyReportService.generateManagerMonthlyReport(
                            currentUser.getId(),
                            currentUser.getName(),
                            startDate,
                            endDate,
                            month,
                            year,
                            token
                    );

            return ResponseEntity.ok(report);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
