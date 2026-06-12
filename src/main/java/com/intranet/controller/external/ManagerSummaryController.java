package com.intranet.controller.external;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.intranet.dto.UserDTO;
import com.intranet.security.CurrentUser;
import com.intranet.service.Manager.ManagerSummaryService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class ManagerSummaryController {

        @Autowired
        private ManagerSummaryService dashboardService;

        @GetMapping("/manager/summary")
        @Operation(summary = "Get manager dashboard summary", description = "Retrieve a summary of team performance and statistics for managers.")
        @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
        public ResponseEntity<Map<String, Object>> getTeamSummary(
                @CurrentUser UserDTO user,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                HttpServletRequest req
        ) {

        String token = req.getHeader("Authorization");
        if (token == null) token = "";

        LocalDate today = LocalDate.now();
        if (startDate == null) startDate = today.withDayOfMonth(1);
        if (endDate == null) endDate = today.with(TemporalAdjusters.lastDayOfMonth());

        Map<String, Object> summary =
                dashboardService.generateManagerSummary(
                        user.getId(), startDate, endDate,
                        token
                );

        return ResponseEntity.ok(summary);
        }

}
