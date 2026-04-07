package com.intranet.controller.RMS;

import java.time.LocalDate;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intranet.dto.rms.TimeSheetSummaryResponseDTO;
import com.intranet.service.RMS.RMSTimeSheetService;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")

public class RMSTimeSheetController {

    private final RMSTimeSheetService timeSheetService;

    @GetMapping("/RMS/summary")
    @Operation(summary = "Controller for TimeSheet related operations in RMS")
    public ResponseEntity<TimeSheetSummaryResponseDTO> getSummary(
            @RequestParam Long userId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {

        return ResponseEntity.ok(
                timeSheetService.getSummary(userId, startDate, endDate)
        );
    }
}