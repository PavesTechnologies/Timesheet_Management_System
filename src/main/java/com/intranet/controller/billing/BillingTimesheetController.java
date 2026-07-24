package com.intranet.controller.billing;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.billing.BillingSnapshotResponseDTO;
import com.intranet.service.billing.BillingTimesheetService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor
public class BillingTimesheetController {

    private final BillingTimesheetService billingTimesheetService;

    @GetMapping("/billing")
    @Operation(summary = "Get approved, billable timesheet hours for a project and billing period",
            description = "Returns approved and billable timesheet entries only, aggregated per resource, "
                    + "for consumption by the Billing Snapshot acquisition flow.")
    // @PreAuthorize("hasAuthority('TIMESHEET_ADMIN')")
    public ResponseEntity<?> getBillingTimesheets(
            @RequestParam Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billingPeriodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billingPeriodEnd) {

        if (billingPeriodStart.isAfter(billingPeriodEnd)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "billingPeriodStart must not be after billingPeriodEnd"));
        }

        try {
            BillingSnapshotResponseDTO response = billingTimesheetService.getApprovedBillableTimesheets(
                    projectId, billingPeriodStart, billingPeriodEnd);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
}
