package com.intranet.controller;

import com.intranet.dto.TimeSheetBulkReviewRequestDTO;
import com.intranet.security.CurrentUser;
import com.intranet.dto.UserDTO;
import com.intranet.service.TimeSheetBulkMultiReviewService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/timesheets")
@RequiredArgsConstructor
public class TimeSheetBulkMultiReviewController {

    private final TimeSheetBulkMultiReviewService bulkMultiReviewService;

    @PostMapping("/review_multiple_users")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    @Operation(summary = "Approve or reject timesheets for multiple users in one go")
    public ResponseEntity<?> reviewMultipleUsersTimesheets(
            @CurrentUser UserDTO manager,
            @RequestBody List<TimeSheetBulkReviewRequestDTO> bulkReviews) {

        Map<String, Object> result = bulkMultiReviewService.reviewMultipleUsers(manager.getId(), bulkReviews);
        return ResponseEntity.ok(result);
    }
}
