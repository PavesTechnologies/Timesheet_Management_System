package com.intranet.controller.RMS;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.rms.RMSProjectDetailResponseDTO;
import com.intranet.dto.rms.RMSProjectHoursSummaryResponseDTO;
import com.intranet.service.RMS.RMSProjectHoursSummaryService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor
public class RMSProjectHoursSummaryController {

    private final RMSProjectHoursSummaryService service;

    @GetMapping("/RMS/project-hours-summary")
    @Operation(summary = "Get all projects with planned, actual, pending, utilization, billable, non-billable, and resource hours")
    public ResponseEntity<RMSProjectHoursSummaryResponseDTO> getProjectHoursSummary() {
        return ResponseEntity.ok(service.getProjectHoursSummary());
    }

    @GetMapping("/RMS/project-hours-summary/{projectId}")
    @Operation(summary = "Get one timesheet project detail with full project info, resources, and tasks")
    public ResponseEntity<RMSProjectDetailResponseDTO> getProjectDetail(@PathVariable Long projectId) {
        return ResponseEntity.ok(service.getProjectDetail(projectId));
    }
}
