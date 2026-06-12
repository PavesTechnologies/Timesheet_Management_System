package com.intranet.controller.reports;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.service.report.TimesheetFinanceReportService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class TimeSheetReportFinanceController {

    private final TimesheetFinanceReportService timesheetFinanceReportService;

    @GetMapping("/monthly_finance")
    @Operation  (summary = "Get monthly finance report", description = "Retrieve a detailed monthly finance report.")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    public ResponseEntity<?> getMonthlyFinanceReport(
        @RequestParam(required = false) Integer month,
        @RequestParam(required = false) Integer year
        ) {

        // If month/year are missing → set current month/year
        if (month == null || year == null) {
            month = java.time.LocalDate.now().getMonthValue();
            year = java.time.LocalDate.now().getYear();
        }

        if (month < 1 || month > 12 || year < 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month or year parameter"));
        }

        else if (month<= 0 || year<=0 || month > 12 || year < 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Month and Year parameters are required"));
        }
        try{

            Map<String, Object> response = timesheetFinanceReportService.getTimesheetFinanceReport(month, year);
                    return ResponseEntity.ok(response);
        }

        catch(IllegalStateException e){
                return ResponseEntity.badRequest().body(e.getMessage());
        }

        catch(Exception e){
                return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
        
    }
    
}
