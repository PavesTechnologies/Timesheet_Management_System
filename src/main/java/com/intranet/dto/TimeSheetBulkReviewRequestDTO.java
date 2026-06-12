package com.intranet.dto;

import lombok.Data;
import java.util.List;

@Data
public class TimeSheetBulkReviewRequestDTO {
    private Long userId;                 // Whose timesheets are being reviewed
    private List<Long> timesheetIds;     // Legacy: list of timesheet IDs that all get the same status
    private String status;               // Legacy: APPROVED or REJECTED (applies to all timesheetIds)
    private String comments;             // Optional (required when at least one timesheet is REJECTED)

    // Mixed-verdict mode: reviewer rejects some days and approves the rest in one submit.
    // When either of these is non-empty, the legacy `timesheetIds`/`status` pair is ignored.
    private List<Long> approvedTimesheetIds;
    private List<Long> rejectedTimesheetIds;
}
