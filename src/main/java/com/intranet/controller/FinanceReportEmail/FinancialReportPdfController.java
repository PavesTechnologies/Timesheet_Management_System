package com.intranet.controller.FinanceReportEmail;

import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.FinanceReportEmail.FinancialPdfEmailSender;
import com.intranet.service.FinanceReportEmail.FinancialPdfGeneratorService;
import com.intranet.service.FinanceReportEmail.FinancialPdfTemplateBuilder;
import com.intranet.service.report.TimesheetFinanceReportService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/finance/report")
@RequiredArgsConstructor
public class FinancialReportPdfController {

    private final TimesheetFinanceReportService financeService;
    private final FinancialPdfTemplateBuilder templateBuilder;
    private final FinancialPdfGeneratorService pdfGenerator;
    private final FinancialPdfEmailSender emailSender;

    @GetMapping("/monthly_pdf")
    @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Send Monthly Financial Report PDF via Email")
    public ResponseEntity<?> sendFinancialReportEmail(
            @CurrentUser UserDTO currentUser,
            @RequestParam int month,
            @RequestParam int year
    ) {
        try {
            Map<String, Object> financeData =
                    financeService.getTimesheetFinanceReport(month, year);

            String monthName = java.time.Month.of(month)
                    .getDisplayName(java.time.format.TextStyle.FULL,
                            java.util.Locale.ENGLISH);

            String html = templateBuilder.buildFinanceHtml(financeData, monthName, year);
            byte[] pdfBytes = pdfGenerator.generatePdfFromHtml(html);

            String toEmail = currentUser.getEmail();
            String adminName = currentUser.getName();

            emailSender.sendFinancialReportPdf(toEmail, pdfBytes, monthName, year, adminName);

            return ResponseEntity.ok("Financial report emailed to " + toEmail);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}
