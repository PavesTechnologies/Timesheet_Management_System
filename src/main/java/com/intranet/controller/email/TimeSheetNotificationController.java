package com.intranet.controller.email;

import com.intranet.dto.email.TimeSheetSummaryEmailDTO;
import com.intranet.dto.email.WeeklySubmissionEmailDTO;
import com.intranet.service.email.managerReviews.ManagerNotificationEmailService;
import com.intranet.service.email.managerReviews.TimeSheetNotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/timesheet")
@Tag(name = "Timesheet Notifications", description = "APIs for sending timesheet summary or status emails and weekly submission emails to managers.")
public class TimeSheetNotificationController {

    private final TimeSheetNotificationService timeSheetNotificationService;
    private final ManagerNotificationEmailService managerNotificationEmailService;


    /**
     * Endpoint: POST /api/emails/timesheet-summary
     * Accepts a JSON array of timesheet summaries and sends professional emails to each user.
     */
    @Operation(summary = "Send timesheet summary/status emails to users when timesheet is approved or rejected by the manager.")
    @PostMapping("/response")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET')")
    public ResponseEntity<?> sendTimeSheetSummaryEmails(
            @RequestBody List<TimeSheetSummaryEmailDTO> summaries
    ) {
        if (summaries == null || summaries.isEmpty()) {
            return ResponseEntity.badRequest().body("No email data provided.");
        }

        try {
            timeSheetNotificationService.sendTimeSheetSummaryEmails(summaries);
            return ResponseEntity.ok("✅ Emails have been sent successfully to " + summaries.size() + " recipients.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("❌ Failed to send emails: " + e.getMessage());
        }
    }

    /**
     * Endpoint to send weekly submission emails to managers.
     *
     * Example POST Body:
     * [
     *   {
     *     "managerId": 22,
     *     "managerName": "Ramesh Manager",
     *     "managerEmail": "ramesh.manager@pavestechnologies.com",
     *     "userId": 17,
     *     "userName": "Rohit Lingarker",
     *     "startDate": "2025-11-03",
     *     "endDate": "2025-11-09",
     *     "totalHoursLogged": 40.00
     *   }
     * ]
     */
    @PostMapping("/notify")
    @Operation(summary = "Send weekly submission emails to managers.")
    @PreAuthorize("hasAuthority('APPROVE_TIMESHEET') or hasAuthority('EDIT_TIMESHEET')")
    public ResponseEntity<?> sendWeeklySubmissionEmails(@RequestBody List<WeeklySubmissionEmailDTO> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return ResponseEntity.badRequest().body("❌ No submissions provided.");
        }

        try {
            managerNotificationEmailService.sendWeeklySubmissionEmails(submissions);
            return ResponseEntity.ok().body(String.format("✅ %d weekly submission email(s) sent successfully.", submissions.size()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("❌ Failed to send weekly submission emails: " + e.getMessage());
        }
    }
}
