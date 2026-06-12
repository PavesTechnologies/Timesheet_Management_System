package com.intranet.controller;

import com.intranet.dto.ReviewedTimesheetAuditDTO;
import com.intranet.dto.TimeSheetBulkReviewRequestDTO;
import com.intranet.dto.UserDTO;
import com.intranet.entity.TimeSheetReview;
import com.intranet.security.CurrentUser;
import com.intranet.service.TimeSheetReviewService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/timesheets")
@RequiredArgsConstructor
public class TimeSheetReviewController {

    private static final Set<TimeSheetReview.Status> ALLOWED_AUDIT_STATUSES =
            Set.of(TimeSheetReview.Status.APPROVED, TimeSheetReview.Status.REJECTED);

    private final TimeSheetReviewService reviewService;

    @PostMapping("/review")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Approve or reject multiple timesheets for a user by manager")
    public ResponseEntity<?> reviewMultipleTimesheets(
            @CurrentUser UserDTO manager,
            @RequestBody TimeSheetBulkReviewRequestDTO dto) {

        String message = reviewService.reviewMultipleTimesheets(manager.getId(), dto);
        return ResponseEntity.ok().body(
                java.util.Map.of("message", message, "status", dto.getStatus())
        );
    }


    @PostMapping("/review/internal")
    @PreAuthorize("hasAuthority('REVIEW_INTERNAL_TIMESHEET') OR hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Approve or reject multiple timesheets for a user by manager internal")
    public ResponseEntity<?> reviewMultipleTimesheetsInternal(
            @CurrentUser UserDTO manager,
            @RequestBody TimeSheetBulkReviewRequestDTO dto) {

        String message = reviewService.reviewInternalTimesheets(manager.getId(), dto);
        return ResponseEntity.ok().body(
                java.util.Map.of("message", message, "status", dto.getStatus())
        );
    }

    @PostMapping("/review/internal/bulk")
    @PreAuthorize("hasAuthority('REVIEW_INTERNAL_TIMESHEET') OR hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "Approve or reject multiple timesheets for a user by manager internal")
    public ResponseEntity<?> reviewMultipleTimesheetsInternal(
            @CurrentUser UserDTO manager,
            @RequestBody List<TimeSheetBulkReviewRequestDTO> dto) {

        try{
          for(TimeSheetBulkReviewRequestDTO ts : dto) {
              reviewService.reviewInternalTimesheets(manager.getId(), ts);
          }
          return ResponseEntity.ok().body("Success");
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Failed to submit timesheets");
        }
    }

    @GetMapping("/review/audit")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET') "
                + "OR hasAuthority('REVIEW_INTERNAL_TIMESHEET') "
                + "OR hasAuthority('TIMESHEET_ADMIN')")
    @Operation(summary = "List timesheets reviewed (approved/rejected) by the current manager — audit view")
    public ResponseEntity<Page<ReviewedTimesheetAuditDTO>> getReviewedAudit(
            @CurrentUser UserDTO manager,
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) List<TimeSheetReview.Status> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        LocalDate today = LocalDate.now();
        if (startDate == null) startDate = today.withDayOfMonth(1);
        if (endDate   == null) endDate   = today.withDayOfMonth(today.lengthOfMonth());

        List<TimeSheetReview.Status> effectiveStatuses = new ArrayList<>();
        if (statuses != null) {
            for (TimeSheetReview.Status s : statuses) {
                if (ALLOWED_AUDIT_STATUSES.contains(s)) effectiveStatuses.add(s);
            }
        }
        if (effectiveStatuses.isEmpty()) {
            effectiveStatuses = List.of(TimeSheetReview.Status.APPROVED,
                                        TimeSheetReview.Status.REJECTED);
        }

        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(
                reviewService.getReviewedTimesheetsForManager(
                        manager.getId(), userId, startDate, endDate,
                        effectiveStatuses, authHeader, pageable));
    }
}
