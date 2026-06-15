package com.intranet.controller;

import com.intranet.dto.rms.UtilizationReportRequestDTO;
import com.intranet.dto.rms.UtilizationReportResponseDTO;
import com.intranet.service.UtilizationReportingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/utilization")
@RequiredArgsConstructor
@Slf4j
public class UtilizationReportingController {

    private final UtilizationReportingService utilizationReportingService;

    @PostMapping("/report")
    @Operation(summary = "Generate comprehensive utilization report with filtering")
    public ResponseEntity<UtilizationReportResponseDTO> generateReport(@Valid @RequestBody UtilizationReportRequestDTO request, HttpServletRequest httpRequest) {
        log.info("Generating utilization report: type={}, startDate={}, endDate={}", 
                request.getReportType(), request.getStartDate(), request.getEndDate());
        
        UtilizationReportResponseDTO response = utilizationReportingService.generateUtilizationReport(request, httpRequest);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/report/quick")
    @Operation(summary = "Quick utilization report with default parameters")
    public ResponseEntity<UtilizationReportResponseDTO> quickReport(
            @RequestParam(required = false) String reportType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest httpRequest) {
        
        // Default to current month if no dates provided
        LocalDate today = LocalDate.now();
        LocalDate defaultStartDate = today.withDayOfMonth(1);
        LocalDate defaultEndDate = today;
        
        UtilizationReportRequestDTO request = UtilizationReportRequestDTO.builder()
                .startDate(startDate != null ? startDate : defaultStartDate)
                .endDate(endDate != null ? endDate : defaultEndDate)
                .reportType(reportType != null ? reportType : "SUMMARY")
                .groupBy("MONTHLY")
                .includeTrends(true)
                .includeAlerts(true)
                .approvedOnly(true)
                .overUtilizationThreshold(90.0)
                .underUtilizationThreshold(60.0)
                .build();
        
        UtilizationReportResponseDTO response = utilizationReportingService.generateUtilizationReport(request, httpRequest);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/export/excel")
    @Operation(summary = "Export utilization report to Excel")
    public void exportToExcel(@Valid @RequestBody UtilizationReportRequestDTO request, HttpServletResponse response, HttpServletRequest httpRequest) throws IOException {
        log.info("Exporting utilization report to Excel: type={}, startDate={}, endDate={}", 
                request.getReportType(), request.getStartDate(), request.getEndDate());
        
        // Generate the report first
        UtilizationReportResponseDTO reportData = utilizationReportingService.generateUtilizationReport(request, httpRequest);
        
        // Set response headers
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", 
                String.format("attachment; filename=utilization_report_%s_%s.xlsx", 
                        reportData.getStartDate(), reportData.getEndDate()));
        
        // Export to Excel (implementation needed)
        // utilizationReportingService.exportToExcel(reportData, response.getOutputStream());
        
        // For now, return a simple CSV export
        exportToCSV(reportData, response);
    }

    @PostMapping("/export/csv")
    @Operation(summary = "Export utilization report to CSV")
    public void exportToCSV(@Valid @RequestBody UtilizationReportRequestDTO request, HttpServletResponse response, HttpServletRequest httpRequest) throws IOException {
        log.info("Exporting utilization report to CSV: type={}, startDate={}, endDate={}", 
                request.getReportType(), request.getStartDate(), request.getEndDate());
        
        // Generate the report first
        UtilizationReportResponseDTO reportData = utilizationReportingService.generateUtilizationReport(request, httpRequest);
        
        // Set response headers
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", 
                String.format("attachment; filename=utilization_report_%s_%s.csv", 
                        reportData.getStartDate(), reportData.getEndDate()));
        
        // Export to CSV
        exportToCSV(reportData, response);
    }

    private void exportToCSV(UtilizationReportResponseDTO reportData, HttpServletResponse response) throws IOException {
        StringBuilder csv = new StringBuilder();
        
        // Header
        csv.append("Utilization Report\n");
        csv.append("Period:").append(reportData.getStartDate()).append(" to ").append(reportData.getEndDate()).append("\n");
        csv.append("Report Type:").append(reportData.getReportType()).append("\n");
        csv.append("Approved Data Only:").append(reportData.isApprovedDataOnly()).append("\n\n");
        
        // Summary metrics
        csv.append("SUMMARY METRICS\n");
        csv.append("Total Hours,").append(reportData.getTotalHours()).append("\n");
        csv.append("Planned Hours,").append(reportData.getPlannedHours()).append("\n");
        csv.append("Utilization %,").append(reportData.getUtilizationPercentage()).append("%\n");
        csv.append("Billable Hours,").append(reportData.getBillableHours()).append("\n");
        csv.append("Non-Billable Hours,").append(reportData.getNonBillableHours()).append("\n");
        csv.append("Internal Hours,").append(reportData.getInternalHours()).append("\n\n");
        
        // Resource utilization
        if (reportData.getResourceUtilizations() != null && !reportData.getResourceUtilizations().isEmpty()) {
            csv.append("RESOURCE UTILIZATION\n");
            csv.append("Resource ID,Resource Name,Role,Total Hours,Billable Hours,Non-Billable Hours,Internal Hours,Planned Hours,Utilization %,Billable Ratio,Utilization Band,Trend,Confidence Score\n");
            
            for (var resource : reportData.getResourceUtilizations()) {
                csv.append(resource.getResourceId()).append(",")
                   .append(resource.getResourceName()).append(",")
                   .append(resource.getRole()).append(",")
                   .append(resource.getTotalHours()).append(",")
                   .append(resource.getBillableHours()).append(",")
                   .append(resource.getNonBillableHours()).append(",")
                   .append(resource.getInternalHours()).append(",")
                   .append(resource.getPlannedHours()).append(",")
                   .append(resource.getUtilizationPercentage()).append("%,")
                   .append(resource.getBillableRatio()).append("%,")
                   .append(resource.getUtilizationBand()).append(",")
                   .append(resource.getTrendSignal()).append(",")
                   .append(resource.getConfidenceScore()).append("\n");
            }
            csv.append("\n");
        }
        
        // Alerts
        if (reportData.getAlerts() != null && !reportData.getAlerts().isEmpty()) {
            csv.append("ALERTS\n");
            csv.append("ID,Type,Severity,Scope,Title,Message,Recommendation,Status,Created Date\n");
            
            for (var alert : reportData.getAlerts()) {
                csv.append(alert.getId()).append(",")
                   .append(alert.getType()).append(",")
                   .append(alert.getSeverity()).append(",")
                   .append(alert.getScope()).append(",")
                   .append("\"").append(alert.getTitle()).append("\",")
                   .append("\"").append(alert.getMessage()).append("\",")
                   .append("\"").append(alert.getRecommendation()).append("\",")
                   .append(alert.getStatus()).append(",")
                   .append(alert.getCreatedDate()).append("\n");
            }
            csv.append("\n");
        }
        
        // Patterns
        if (reportData.getPatterns() != null && !reportData.getPatterns().isEmpty()) {
            csv.append("PATTERNS\n");
            csv.append("ID,Pattern Type,Severity,Scope,Title,Description,Impact,Recommendation,Status,Detected Date\n");
            
            for (var pattern : reportData.getPatterns()) {
                csv.append(pattern.getId()).append(",")
                   .append(pattern.getPatternType()).append(",")
                   .append(pattern.getSeverity()).append(",")
                   .append(pattern.getScope()).append(",")
                   .append("\"").append(pattern.getTitle()).append("\",")
                   .append("\"").append(pattern.getDescription()).append("\",")
                   .append("\"").append(pattern.getImpact()).append("\",")
                   .append("\"").append(pattern.getRecommendation()).append("\",")
                   .append(pattern.getStatus()).append(",")
                   .append(pattern.getDetectedDate()).append("\n");
            }
        }
        
        // Write to response
        response.getWriter().write(csv.toString());
        response.getWriter().flush();
    }
}
